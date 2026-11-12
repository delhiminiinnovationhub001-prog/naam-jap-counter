#include "audio_counter.h"

#include <driver/i2s.h>
#include <freertos/queue.h>
#include <math.h>
#include <string.h>
#include <atomic>

#include "config.h"
#include "word_spotter.h"

namespace Audio {
namespace {

constexpr i2s_port_t I2S_PORT = I2S_NUM_0;
constexpr int SAMPLE_RATE = 16000;
constexpr int FRAME_MS = 20;
constexpr int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;  // 320
constexpr float FULL_SCALE = 8388608.0f;                      // 2^23 (24-bit)
constexpr float ABS_MIN_DB = -72.0f;      // never trigger below this level
constexpr float HYSTERESIS_DB = 3.0f;     // voice ends a bit below the trigger
constexpr uint32_t STUCK_VOICE_MS = 15000;  // continuous "voice" = new noise

// Voice fingerprint
constexpr int NFFT = 512;
constexpr int NBINS = NFFT / 2 + 1;
constexpr int NMEL = 26;
constexpr float MEL_LO_HZ = 100.0f;
constexpr float MEL_HI_HZ = 7000.0f;
constexpr int PITCH_MIN_LAG = SAMPLE_RATE / 500;  // 500 Hz (child voice)
constexpr int PITCH_MAX_LAG = SAMPLE_RATE / 70;   // 70 Hz (deep male voice)
constexpr float PITCH_VOICED_CORR = 0.45f;
constexpr int MIN_FEATURE_FRAMES = 4;
constexpr int PITCH_BUF = 2 * FRAME_SAMPLES;      // 40 ms window for pitch

QueueHandle_t eventQueue = nullptr;
std::atomic<uint32_t> dropped{0};
volatile bool gPaused = false;
volatile uint8_t gSensitivity = DEFAULT_SENSITIVITY;
volatile uint16_t gMinVoiceMs = DEFAULT_MIN_VOICE_MS;
volatile uint16_t gMinGapMs = DEFAULT_MIN_GAP_MS;

constexpr int32_t MIC_ALIVE_SPAN = 8;   // a live mic always shows some noise
constexpr int MIC_DEAD_FRAMES = 100;     // 2 s of flat signal = mic fault
volatile MicState gMicState = MicState::Unknown;
volatile bool gRightChannel = false;
volatile int32_t gRawMin = 0, gRawMax = 0;

volatile float gLevelDb = -100.0f;
volatile float gNoiseDb = -80.0f;
volatile float gThresholdDb = -60.0f;
volatile bool gVoice = false;

int32_t samples[FRAME_SAMPLES];
float pitchBuf[PITCH_BUF];  // previous + current high-passed frame
float pitchEnergy[PITCH_BUF + 1];
float window[FRAME_SAMPLES];
float fftRe[NFFT], fftIm[NFFT];
float twCos[NFFT / 2], twSin[NFFT / 2];
int melLo[NMEL], melMid[NMEL], melHi[NMEL];
float dct[NUM_CEPSTRA][NMEL];

float curMfcc[NUM_CEPSTRA];  // MFCCs of the current frame

// Second-order IIR filter (RBJ cookbook, transposed direct form II).
struct Biquad {
  float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
  void set(float nb0, float nb1, float nb2, float na0, float na1, float na2) {
    b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0;
  }
  void highPass(float hz, float q) {
    const float w = 2.0f * PI * hz / SAMPLE_RATE, c = cosf(w), alpha = sinf(w) / (2.0f * q);
    set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
  }
  void lowPass(float hz, float q) {
    const float w = 2.0f * PI * hz / SAMPLE_RATE, c = cosf(w), alpha = sinf(w) / (2.0f * q);
    set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha);
  }
  float step(float x) {
    const float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }
};
constexpr float HPF_HZ = 120.0f;
constexpr float LPF_HZ = 4000.0f;
Biquad highPass, lowPass;

// A jap must be voiced (have a pitch) for this share of its frames, so taps,
// clicks, claps and coughs - which have no pitch - are not counted.
constexpr float MIN_VOICED_RATIO = 0.3f;
std::atomic<uint32_t> rejected{0};

// Raw audio snapshot for diagnosis (serial command 'A'): 1 s, 16-bit.
constexpr int SNAP_SAMPLES = SAMPLE_RATE;
int16_t* volatile snapBuf = nullptr;  // allocated only while a snapshot is taken
volatile int snapPos = -1;             // -1 = idle, SNAP_SAMPLES = full

// Word mode
constexpr float CMN_ALPHA = 0.02f;  // running speech mean, ~1 s
constexpr float SPOT_GATE_DB = 15.0f;  // speech = this far above the noise floor (tuned on recordings: 0 false counts in a quiet room)
constexpr uint32_t STUCK_VOICE_WORD_MS = 120000;  // continuous chanting is normal here
volatile bool gSpotting = false;
volatile bool gDump = false;
float cmnMean[NUM_CEPSTRA];
bool cmnReady = false;

// MFCC frames of the current utterance, and a small ring of finished ones
// for training (read by the main task through getSequence()).
constexpr int SEQ_RING = 3;
Sequence curSeq;
Sequence seqRing[SEQ_RING];
uint32_t seqRingId[SEQ_RING];
uint32_t nextSeqId = 1;
portMUX_TYPE seqMux = portMUX_INITIALIZER_UNLOCKED;

struct Accumulator {
  float sum[NUM_CEPSTRA];
  float sq[NUM_CEPSTRA];
  float pitchSum, pitchSq;
  int pitchFrames;
  int frames;
  void reset() {
    memset(this, 0, sizeof(*this));
    curSeq.len = 0;
  }
} acc;

float hzToMel(float hz) { return 2595.0f * log10f(1.0f + hz / 700.0f); }
float melToHz(float mel) { return 700.0f * (powf(10.0f, mel / 2595.0f) - 1.0f); }

void initDsp() {
  for (int n = 0; n < FRAME_SAMPLES; n++)
    window[n] = 0.54f - 0.46f * cosf(2.0f * PI * n / (FRAME_SAMPLES - 1));
  for (int k = 0; k < NFFT / 2; k++) {
    twCos[k] = cosf(2.0f * PI * k / NFFT);
    twSin[k] = sinf(2.0f * PI * k / NFFT);
  }
  const float lo = hzToMel(MEL_LO_HZ), hi = hzToMel(MEL_HI_HZ);
  int bins[NMEL + 2];
  for (int i = 0; i < NMEL + 2; i++) {
    const float hz = melToHz(lo + (hi - lo) * i / (NMEL + 1));
    bins[i] = (int)floorf((NFFT + 1) * hz / SAMPLE_RATE);
  }
  for (int m = 0; m < NMEL; m++) {
    melLo[m] = bins[m];
    melMid[m] = max(bins[m + 1], melLo[m] + 1);
    melHi[m] = min(max(bins[m + 2], melMid[m] + 1), NBINS - 1);
  }
  for (int c = 0; c < NUM_CEPSTRA; c++)
    for (int m = 0; m < NMEL; m++) dct[c][m] = cosf(PI * (c + 1) * (m + 0.5f) / NMEL);
}

void fft(float* re, float* im) {
  for (int i = 1, j = 0; i < NFFT; i++) {
    int bit = NFFT >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      float t = re[i]; re[i] = re[j]; re[j] = t;
      t = im[i]; im[i] = im[j]; im[j] = t;
    }
  }
  for (int len = 2; len <= NFFT; len <<= 1) {
    const int half = len / 2, step = NFFT / len;
    for (int i = 0; i < NFFT; i += len) {
      for (int k = 0; k < half; k++) {
        const float wr = twCos[k * step], wi = -twSin[k * step];
        const int a = i + k, b = a + half;
        const float tr = re[b] * wr - im[b] * wi;
        const float ti = re[b] * wi + im[b] * wr;
        re[b] = re[a] - tr;
        im[b] = im[a] - ti;
        re[a] += tr;
        im[a] += ti;
      }
    }
  }
}

// MFCCs of the current frame (last FRAME_SAMPLES of pitchBuf) into curMfcc.
void computeMfcc() {
  const float* frame = pitchBuf + FRAME_SAMPLES;

  // --- MFCC: pre-emphasis, window, FFT, mel filter bank, log, DCT
  float prev = pitchBuf[FRAME_SAMPLES - 1];
  for (int n = 0; n < FRAME_SAMPLES; n++) {
    fftRe[n] = (frame[n] - 0.97f * prev) * window[n];
    fftIm[n] = 0;
    prev = frame[n];
  }
  for (int n = FRAME_SAMPLES; n < NFFT; n++) fftRe[n] = fftIm[n] = 0;
  fft(fftRe, fftIm);
  for (int k = 0; k < NBINS; k++) fftRe[k] = fftRe[k] * fftRe[k] + fftIm[k] * fftIm[k];

  float logMel[NMEL];
  for (int m = 0; m < NMEL; m++) {
    float e = 0;
    for (int k = melLo[m]; k < melMid[m]; k++)
      e += fftRe[k] * (float)(k - melLo[m]) / (melMid[m] - melLo[m]);
    for (int k = melMid[m]; k <= melHi[m]; k++)
      e += fftRe[k] * (float)(melHi[m] - k) / (melHi[m] - melMid[m]);
    logMel[m] = logf(e + 1.0f);
  }
  for (int c = 0; c < NUM_CEPSTRA; c++) {
    float v = 0;
    for (int m = 0; m < NMEL; m++) v += dct[c][m] * logMel[m];
    curMfcc[c] = v;
  }
}

// Adds the current (voiced) frame to the fingerprint and utterance sequence.
void analyseFrame(float db) {
  for (int c = 0; c < NUM_CEPSTRA; c++) {
    acc.sum[c] += curMfcc[c];
    acc.sq[c] += curMfcc[c] * curMfcc[c];
  }
  if (curSeq.len < SEQ_MAX_FRAMES) {
    curSeq.db[curSeq.len] = (int8_t)constrain(lroundf(db), -127L, 0L);
    memcpy(curSeq.f[curSeq.len++], curMfcc, sizeof(curMfcc));
  }

  // --- Pitch: normalised autocorrelation over the last 40 ms
  pitchEnergy[0] = 0;
  for (int n = 0; n < PITCH_BUF; n++) pitchEnergy[n + 1] = pitchEnergy[n] + pitchBuf[n] * pitchBuf[n];
  float bestCorr = 0;
  int bestLag = 0;
  for (int lag = PITCH_MIN_LAG; lag <= PITCH_MAX_LAG; lag++) {
    const int len = PITCH_BUF - lag;
    float r = 0;
    for (int n = 0; n < len; n++) r += pitchBuf[n] * pitchBuf[n + lag];
    const float e0 = pitchEnergy[len];
    const float e1 = pitchEnergy[PITCH_BUF] - pitchEnergy[lag];
    const float corr = r / sqrtf(e0 * e1 + 1e-9f);
    if (corr > bestCorr) {
      bestCorr = corr;
      bestLag = lag;
    }
  }
  if (bestCorr > PITCH_VOICED_CORR && bestLag > 0) {
    const float lp = log2f((float)SAMPLE_RATE / bestLag);
    acc.pitchSum += lp;
    acc.pitchSq += lp * lp;
    acc.pitchFrames++;
  }
  acc.frames++;
}

void emitJap() {
  // Reject sounds without a voice: too short, or (almost) no pitch.
  if (acc.frames < MIN_FEATURE_FRAMES ||
      acc.pitchFrames < max(3, (int)(acc.frames * MIN_VOICED_RATIO))) {
    rejected++;
    return;
  }
  JapEvent ev;
  ev.frames = acc.frames;
  if (ev.frames) {
    const float n = (float)acc.frames;
    for (int c = 0; c < NUM_CEPSTRA; c++) {
      const float mean = acc.sum[c] / n;
      ev.feature[c] = mean;
      ev.feature[NUM_CEPSTRA + c] = sqrtf(fmaxf(acc.sq[c] / n - mean * mean, 0.0f));
    }
    float pm = 7.5f, ps = 0.3f;  // ~180 Hz if no pitch was found
    if (acc.pitchFrames >= 3) {
      pm = acc.pitchSum / acc.pitchFrames;
      ps = sqrtf(fmaxf(acc.pitchSq / acc.pitchFrames - pm * pm, 0.0f));
    }
    ev.feature[2 * NUM_CEPSTRA] = pm;
    ev.feature[2 * NUM_CEPSTRA + 1] = ps;
  } else {
    memset(ev.feature, 0, sizeof(ev.feature));
  }
  ev.person = -1;
  ev.confidence = 0;
  ev.seqId = 0;
  if (curSeq.len > 0) {
    portENTER_CRITICAL(&seqMux);
    const int slot = nextSeqId % SEQ_RING;
    seqRing[slot] = curSeq;
    seqRingId[slot] = nextSeqId;
    ev.seqId = nextSeqId++;
    portEXIT_CRITICAL(&seqMux);
  }
  if (xQueueSend(eventQueue, &ev, 0) != pdTRUE) dropped++;
}

void emitWord(const WordSpotter::Detection& d) {
  JapEvent ev;
  memset(ev.feature, 0, sizeof(ev.feature));
  ev.frames = 0;
  ev.person = d.person;
  ev.confidence = d.confidence;
  ev.seqId = 0;
  if (xQueueSend(eventQueue, &ev, 0) != pdTRUE) dropped++;
}

// Word mode: normalise the frame by the running speech mean, then match it.
void spotFrame(bool speech) {
  if (speech) {
    if (!cmnReady) {
      memcpy(cmnMean, curMfcc, sizeof(cmnMean));
      cmnReady = true;
    }
    for (int c = 0; c < NUM_CEPSTRA; c++) cmnMean[c] += CMN_ALPHA * (curMfcc[c] - cmnMean[c]);
  }
  if (!cmnReady) return;
  float x[NUM_CEPSTRA];
  for (int c = 0; c < NUM_CEPSTRA; c++) x[c] = curMfcc[c] - cmnMean[c];
  WordSpotter::Detection d;
  if (WordSpotter::feed(x, speech, d) && !gPaused) {
    emitWord(d);
    if (gDump) Serial.printf("W %d %.2f\n", d.person, d.score);
  }
}

void initI2s(i2s_channel_fmt_t channel) {
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
  cfg.sample_rate = SAMPLE_RATE;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT;
  cfg.channel_format = channel;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 8;
  cfg.dma_buf_len = FRAME_SAMPLES;
  cfg.use_apll = false;

  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;  // keep MCLK off GPIO0 (BOOT button)
  pins.bck_io_num = PIN_I2S_SCK;
  pins.ws_io_num = PIN_I2S_WS;
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num = PIN_I2S_SD;

  i2s_driver_install(I2S_PORT, &cfg, 0, nullptr);
  i2s_set_pin(I2S_PORT, &pins);
  i2s_zero_dma_buffer(I2S_PORT);
}

// What one frame of raw samples says about the mic.
MicState classify(int32_t mn, int32_t mx) {
  if (mx - mn > MIC_ALIVE_SPAN) return MicState::Ok;
  if (mn == -1 && mx == -1) return MicState::StuckHigh;  // data line held at 3.3 V
  return MicState::NoData;                                // data line flat (usually 0)
}

// Reads ~0.5 s from the mic (after its start-up time) and classifies it.
MicState probeMic(int32_t& mn, int32_t& mx) {
  size_t bytes;
  for (int f = 0; f < 10; f++) i2s_read(I2S_PORT, samples, sizeof(samples), &bytes, portMAX_DELAY);
  mn = INT32_MAX;
  mx = INT32_MIN;
  for (int f = 0; f < 25; f++) {
    i2s_read(I2S_PORT, samples, sizeof(samples), &bytes, portMAX_DELAY);
    for (size_t i = 0; i < bytes / sizeof(int32_t); i++) {
      const int32_t v = samples[i] >> 8;
      mn = min(mn, v);
      mx = max(mx, v);
    }
  }
  return classify(mn, mx);
}

// The INMP441 sends on the left or right slot depending on its L/R pin, and
// boards differ in which slot the driver calls "left". Use whichever has sound.
void startMic() {
  const i2s_channel_fmt_t configured = MIC_CHANNEL_FMT;
  const i2s_channel_fmt_t other =
      configured == I2S_CHANNEL_FMT_ONLY_LEFT ? I2S_CHANNEL_FMT_ONLY_RIGHT : I2S_CHANNEL_FMT_ONLY_LEFT;
  int32_t mn, mx;
  initI2s(configured);
  MicState st = probeMic(mn, mx);
  gRightChannel = configured == I2S_CHANNEL_FMT_ONLY_RIGHT;
  if (st != MicState::Ok) {
    Serial.printf("[mic] %s channel: no audio (raw %ld..%ld), trying the other channel\n",
                  gRightChannel ? "right" : "left", (long)mn, (long)mx);
    i2s_driver_uninstall(I2S_PORT);
    initI2s(other);
    int32_t mn2, mx2;
    const MicState st2 = probeMic(mn2, mx2);
    if (st2 == MicState::Ok) {
      gRightChannel = !gRightChannel;
      st = st2;
      mn = mn2;
      mx = mx2;
    } else if (st2 == MicState::StuckHigh) {
      st = st2;
    }
  }
  gMicState = st;
  gRawMin = mn;
  gRawMax = mx;
  switch (st) {
    case MicState::Ok:
      Serial.printf("[mic] OK on %s channel (raw %ld..%ld)\n", gRightChannel ? "right" : "left", (long)mn, (long)mx);
      break;
    case MicState::StuckHigh:
      Serial.println("[mic] FAULT: data line stuck at 3.3 V. Check SD wire and that VDD is 3V3 (not 5V).");
      break;
    default:
      Serial.printf("[mic] FAULT: no signal on either channel (raw %ld..%ld). Check the wiring: "
                    "SD->GPIO%d, SCK->GPIO%d, WS->GPIO%d, VDD->3V3, GND->GND, L/R->GND\n",
                    (long)mn, (long)mx, PIN_I2S_SD, PIN_I2S_SCK, PIN_I2S_WS);
      break;
  }
}

void audioTask(void*) {
  highPass.highPass(HPF_HZ, 0.7071f);
  lowPass.lowPass(LPF_HZ, 0.7071f);
  float noiseDb = -75.0f;
  bool inVoice = false;
  uint32_t voiceMs = 0, silenceMs = 0;
  int warmupFrames = 25;  // ~0.5 s for the mic to settle after power-up
  int deadFrames = 0;

  for (;;) {
    size_t bytes = 0;
    if (i2s_read(I2S_PORT, samples, sizeof(samples), &bytes, portMAX_DELAY) != ESP_OK || bytes == 0)
      continue;
    const int n = min((int)(bytes / sizeof(int32_t)), FRAME_SAMPLES);

    // Live health check: a flat signal for ~2 s means the mic stopped sending.
    int32_t mn = INT32_MAX, mx = INT32_MIN;
    for (int i = 0; i < n; i++) {
      const int32_t v = samples[i] >> 8;
      mn = min(mn, v);
      mx = max(mx, v);
    }
    const MicState st = classify(mn, mx);
    if (st == MicState::Ok) {
      deadFrames = 0;
      gMicState = MicState::Ok;
    } else if (++deadFrames >= MIC_DEAD_FRAMES) {
      gMicState = st;
    }
    gRawMin = mn;
    gRawMax = mx;

    memmove(pitchBuf, pitchBuf + FRAME_SAMPLES, FRAME_SAMPLES * sizeof(float));
    float* frame = pitchBuf + FRAME_SAMPLES;
    double sumSq = 0;
    for (int i = 0; i < FRAME_SAMPLES; i++) {
      const int32_t raw = i < n ? (samples[i] >> 8) : 0;  // 24-bit, left-justified
      if (snapBuf && snapPos >= 0 && snapPos < SNAP_SAMPLES) snapBuf[snapPos++] = (int16_t)constrain(raw >> 8, -32768L, 32767L);
      // 120 Hz high-pass: removes DC, hum, rumble and the thump of taps.
      const float y = highPass.step((float)raw);
      frame[i] = y;
      // The level that decides "someone is speaking" uses the speech band only
      // (120 Hz - 4 kHz), so fan and electrical hiss count less.
      const float v = lowPass.step(y);
      sumSq += (double)v * v;
    }
    const float rms = sqrtf((float)(sumSq / FRAME_SAMPLES));
    const float db = 20.0f * log10f(rms / FULL_SCALE + 1e-9f);

    if (warmupFrames > 0) {
      warmupFrames--;
      noiseDb = db;
      gLevelDb = db;
      continue;
    }

    // Sensitivity 1..10 -> trigger 20.4 .. 6 dB above the noise floor.
    const float margin = 22.0f - 1.6f * gSensitivity;
    const float onThr = fmaxf(noiseDb + margin, ABS_MIN_DB);
    const float offThr = onThr - HYSTERESIS_DB;

    const bool spot = gSpotting;
    computeMfcc();
    if (gDump) {
      // 12 x int16 (value * 16) as hex, for offline tuning of word matching.
      char line[8 + 12 * 4 + 2];
      int p = snprintf(line, sizeof line, "F%d ", (int)lroundf(db));
      for (int c = 0; c < NUM_CEPSTRA; c++)
        p += snprintf(line + p, sizeof line - p, "%04X",
                      (uint16_t)(int16_t)constrain(lroundf(curMfcc[c] * 16), -32768L, 32767L));
      Serial.println(line);
    }
    // Continuous chanting dips below the jap trigger between syllables, so the
    // spotter uses a lower "is anyone speaking" gate just above the noise floor.
    if (spot) spotFrame(db >= noiseDb + SPOT_GATE_DB);
    // Utterance mode (and training) builds the fingerprint of each voice burst.
    const bool collect = !gPaused && !spot;

    if (db >= onThr) {
      voiceMs += FRAME_MS;
      silenceMs = 0;
      if (collect) analyseFrame(db);
      if (!inVoice && voiceMs >= gMinVoiceMs) inVoice = true;
      if (inVoice && voiceMs > (spot ? STUCK_VOICE_WORD_MS : STUCK_VOICE_MS)) {
        // Sound never stopped (fan, TV...): treat it as the new background.
        noiseDb = db;
        inVoice = false;
        voiceMs = 0;
        acc.reset();
      }
    } else if (db < offThr || !inVoice) {
      silenceMs += FRAME_MS;
      if (inVoice) {
        if (silenceMs >= gMinGapMs) {
          inVoice = false;
          voiceMs = 0;
          if (collect) emitJap();
          acc.reset();
        }
      } else if (silenceMs >= 3 * FRAME_MS) {
        voiceMs = 0;  // a short blip that never became a jap
        acc.reset();
      }
    } else {
      silenceMs = 0;  // inside the hysteresis band while talking
      if (collect) analyseFrame(db);
    }

    // Track the background noise only while nobody is chanting.
    if (!inVoice && db < onThr) {
      const float a = (db < noiseDb) ? 0.05f : 0.005f;
      noiseDb += a * (db - noiseDb);
      noiseDb = constrain(noiseDb, -95.0f, -30.0f);
    }

    gLevelDb = db;
    gNoiseDb = noiseDb;
    gThresholdDb = onThr;
    gVoice = inVoice;
  }
}

}  // namespace

void begin() {
  initDsp();
  acc.reset();
  eventQueue = xQueueCreate(16, sizeof(JapEvent));
  startMic();
  // Core 0: audio + fingerprints (next to WiFi). Core 1: recognition, screen, web.
  xTaskCreatePinnedToCore(audioTask, "jap_audio", 6144, nullptr, 5, nullptr, 0);
}

void setTuning(uint8_t sensitivity, uint16_t minVoiceMs, uint16_t minGapMs) {
  gSensitivity = constrain(sensitivity, 1, 10);
  gMinVoiceMs = minVoiceMs;
  gMinGapMs = minGapMs;
}

bool nextEvent(JapEvent& ev) { return eventQueue && xQueueReceive(eventQueue, &ev, 0) == pdTRUE; }
uint32_t takeDropped() { return dropped.exchange(0); }

bool getSequence(uint32_t seqId, Sequence& out) {
  if (seqId == 0) return false;
  bool found = false;
  portENTER_CRITICAL(&seqMux);
  const int slot = seqId % SEQ_RING;
  if (seqRingId[slot] == seqId) {
    out = seqRing[slot];
    found = true;
  }
  portEXIT_CRITICAL(&seqMux);
  return found;
}

void setSpotting(bool on) {
  if (on == gSpotting) return;
  if (on) WordSpotter::resetStream();
  gSpotting = on;
}
bool spotting() { return gSpotting; }

void setDump(bool on) { gDump = on; }
bool dumping() { return gDump; }

uint32_t rejectedCount() { return rejected.load(); }

void startSnapshot() {
  if (!snapBuf) snapBuf = (int16_t*)malloc(SNAP_SAMPLES * sizeof(int16_t));
  if (snapBuf) snapPos = 0;
}

void printSnapshotIfReady() {
  if (snapPos != SNAP_SAMPLES) return;
  Serial.printf("AUSTART %d\n", SAMPLE_RATE);
  char line[2 + 64 * 4 + 2];
  for (int i = 0; i < SNAP_SAMPLES; i += 64) {
    int p = snprintf(line, sizeof line, "A ");
    for (int k = 0; k < 64; k++) p += snprintf(line + p, sizeof line - p, "%04X", (uint16_t)snapBuf[i + k]);
    Serial.println(line);
  }
  Serial.println("AUEND");
  snapPos = -1;
  int16_t* buf = snapBuf;
  snapBuf = nullptr;
  free(buf);
}

void setPaused(bool p) { gPaused = p; }
bool paused() { return gPaused; }

float levelDb() { return gLevelDb; }
float noiseDb() { return gNoiseDb; }
float thresholdDb() { return gThresholdDb; }
bool voiceActive() { return gVoice; }

MicState micState() { return gMicState; }
const char* micStateName() {
  switch (gMicState) {
    case MicState::Ok: return "ok";
    case MicState::NoData: return "nosignal";
    case MicState::StuckHigh: return "stuckhigh";
    default: return "starting";
  }
}
bool micRightChannel() { return gRightChannel; }
int32_t micRawMin() { return gRawMin; }
int32_t micRawMax() { return gRawMax; }

}  // namespace Audio
