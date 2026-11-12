#pragma once
#include <Arduino.h>

// Listens to the INMP441 on its own FreeRTOS task (core 0) and detects
// utterances: a burst of voice above the adaptive noise floor followed by a
// silence gap. For every jap it also computes a voice fingerprint
// (MFCC statistics + pitch) used to recognise who chanted.
namespace Audio {

constexpr int NUM_CEPSTRA = 12;                    // MFCC c1..c12
constexpr int FEATURE_DIM = 2 * NUM_CEPSTRA + 2;   // mean, std, pitch mean/std

constexpr int SEQ_MAX_FRAMES = 60;                 // 1.2 s of MFCC frames

// MFCC frames of one utterance (raw, before mean normalisation).
struct Sequence {
  uint8_t len;
  int8_t db[SEQ_MAX_FRAMES];  // frame level in dBFS, to trim quiet edges
  float f[SEQ_MAX_FRAMES][NUM_CEPSTRA];
};

struct JapEvent {
  float feature[FEATURE_DIM];
  uint16_t frames;   // analysed 20 ms frames; 0 = too short to fingerprint
  int8_t person;     // word mode: who said it (-1 = decide from the fingerprint)
  float confidence;  // word mode: 0..1
  uint32_t seqId;    // utterance mode: id for getSequence()
};

void begin();
void setTuning(uint8_t sensitivity, uint16_t minVoiceMs, uint16_t minGapMs);

// Next detected jap, if any.
bool nextEvent(JapEvent& ev);
// Japs detected while the event queue was full (counted without a voice).
uint32_t takeDropped();

// MFCC frames of a recent utterance event (the last few are kept).
bool getSequence(uint32_t seqId, Sequence& out);

// Word mode: count only the taught word (WordSpotter) instead of every
// voice burst. Off while training, so examples are cut at the pauses.
void setSpotting(bool on);
bool spotting();

// Debug: print every frame's level and MFCCs on Serial ("F <dB> <hex>").
void setDump(bool on);
bool dumping();

// Sounds ignored because they had no voice (taps, clicks, claps...).
uint32_t rejectedCount();

// Debug: record 1 s of raw 16-bit audio, then print it once on Serial.
void startSnapshot();
void printSnapshotIfReady();

void setPaused(bool paused);
bool paused();

// Live values for the level meter (dBFS).
float levelDb();
float noiseDb();
float thresholdDb();
bool voiceActive();

// Microphone health, from the raw samples (see startMic in the .cpp).
enum class MicState : uint8_t { Unknown, Ok, NoData, StuckHigh };
MicState micState();
const char* micStateName();  // "ok", "nosignal", "stuckhigh", "starting"
bool micRightChannel();      // channel picked at start-up
int32_t micRawMin();         // last frame's raw 24-bit range
int32_t micRawMax();

}  // namespace Audio
