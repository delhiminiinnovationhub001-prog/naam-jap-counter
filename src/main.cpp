// ---------------------------------------------------------------------------
//  Naam Jap Counter
//  ESP32-S3-DevKitC-1 N16R8 or ESP32 DevKit V1 + INMP441 I2S mic
//  + 1.8" ST7735 TFT (+ micro-SD)
//
//  - Counts each chanted naam (voice burst followed by a short silence)
//  - Recognises up to 5 trained voices and keeps a count for each person
//  - Shows who chanted, their count, totals, IST date & time
//  - At 12:00 midnight IST today's counts are added to the totals and reset
//  - First boot: WiFi setup access point; its password is shown on the TFT
// ---------------------------------------------------------------------------
#include <Arduino.h>
#include <SPI.h>
#include <time.h>

#include "audio_counter.h"
#include "config.h"
#include "display.h"
#include "net.h"
#include "ota.h"
#include "storage.h"
#include "voice_id.h"
#include "web.h"
#include "word_spotter.h"

namespace {

constexpr uint32_t LONG_PRESS_MS = 5000;

uint32_t currentDateKey() {
  if (!Net::timeValid()) return 0;
  const time_t now = time(nullptr);
  struct tm t;
  localtime_r(&now, &t);
  return (t.tm_year + 1900) * 10000 + (t.tm_mon + 1) * 100 + t.tm_mday;
}

// Who chanted a word. A clear match switches to that person at once; an
// unclear one (the two voices scored almost the same) stays with whoever was
// chanting in the last few seconds, because one person chants a whole series.
int resolveSpeaker(int person, float confidence) {
  constexpr float SURE = 0.25f;
  constexpr uint32_t SERIES_MS = 8000;
  static int current = -1;
  static uint32_t lastMs = 0;
  const uint32_t now = millis();
  const bool inSeries = current >= 0 && Storage::active(current) && now - lastMs < SERIES_MS;
  if (Storage::settings().smoothing && inSeries && confidence < SURE) person = current;
  current = person;
  lastMs = now;
  return person;
}

void handleJaps() {
  const bool timeOk = Net::timeValid();
  Audio::JapEvent ev;
  while (Audio::nextEvent(ev)) {
    if (ev.person >= 0) {  // word mode: the spotter already knows who said it
      // While a person teaches fast chanting, every word is theirs.
      const int learner = WordSpotter::fastTrainingPerson();
      const int slot = learner >= 0 ? learner : resolveSpeaker(ev.person, ev.confidence);
      Storage::addCount(slot, 1, timeOk);
      Voice::noteDetection(slot, ev.confidence);
      continue;
    }
    // Training records each example both as a voice fingerprint and as a
    // word template (MFCC sequence) for the spotter.
    const int trainee = Voice::trainingPerson();
    // Word mode counts only the taught word; until it is taught, a voice
    // burst is used only as a training example.
    if (trainee < 0 && Storage::settings().wordMode) continue;
    if (trainee >= 0) {
      static Audio::Sequence seq;
      if (Audio::getSequence(ev.seqId, seq)) WordSpotter::addSample(trainee, seq);
    }
    Storage::addCount(Voice::identify(ev), 1, timeOk);
  }

  // Japs that did not fit in the queue: give them to whoever chanted last.
  const uint32_t dropped = Audio::takeDropped();
  if (dropped) {
    const int slot = Voice::lastSlot();
    Storage::addCount(slot >= 0 ? slot : Storage::GUEST, dropped, timeOk);
  }
}

// Finishes word training when voice training ends (done, stopped or cleared),
// and switches between word mode and "count any voice".
void updateWordMode() {
  static int lastTrainee = -1;
  const int trainee = Voice::trainingPerson();
  if (lastTrainee >= 0 && trainee != lastTrainee) {
    if (Storage::active(lastTrainee))
      WordSpotter::finishTraining(lastTrainee);
    else
      WordSpotter::cancelTraining();
  }
  lastTrainee = trainee;
  Audio::setSpotting(Storage::settings().wordMode && WordSpotter::ready() && trainee < 0);
}

// Serial debug commands: 'D' toggles the per-frame feature dump,
// 'T' prints the word templates, 'A' records and prints 1 s of raw audio.
void handleSerial() {
  while (Serial.available()) {
    const int c = Serial.read();
    if (c == 'D') Audio::setDump(!Audio::dumping());
    if (c == 'T') WordSpotter::dumpTemplates();
    if (c == 'A') Audio::startSnapshot();
  }
  Audio::printSnapshotIfReady();
}

// BOOT button: short press = pause/resume, hold 5 s = forget WiFi.
void handleButton() {
  static bool wasDown = false, longDone = false;
  static uint32_t downAt = 0;
  const bool down = digitalRead(PIN_BUTTON) == LOW;
  const uint32_t now = millis();

  if (down && !wasDown) {
    downAt = now;
    longDone = false;
  }
  if (down && !longDone && now - downAt >= LONG_PRESS_MS) {
    longDone = true;
    Display::showMessage("WiFi reset", "Restarting...", 5000);
    Net::forgetCredentials();
    Net::scheduleRestart(1500);
  }
  if (!down && wasDown && !longDone) {
    const uint32_t held = now - downAt;
    if (held > 40 && held < 1000) Audio::setPaused(!Audio::paused());
  }
  wasDown = down;
}

}  // namespace

void setup() {
  Serial.begin(115200);
  Serial.println("\nNaam Jap Counter starting");
  Serial.printf("Board: %s, firmware %s, PSRAM: %u bytes, people: %d\n", BOARD_NAME, FIRMWARE_VERSION,
                (unsigned)ESP.getPsramSize(), MAX_PEOPLE);
  // 1 = power on, 3 = software restart, 4 = crash (panic), 5/6/7 = watchdog, 9 = brownout
  Serial.printf("Last reset reason: %d\n", (int)esp_reset_reason());
  pinMode(PIN_BUTTON, INPUT_PULLUP);

  SPI.begin(PIN_TFT_SCLK, PIN_SD_MISO, PIN_TFT_MOSI);  // shared by TFT and SD
  Display::begin();
  Display::splash();

  Storage::begin();
  const Storage::Settings& s = Storage::settings();
  Display::setBrightness(s.brightness);

  Voice::begin();
  Voice::setMatching(s.strictness, s.smoothing);
  WordSpotter::begin();
  WordSpotter::setStrictness(s.wordStrictness);

  Audio::setTuning(s.sensitivity, s.minVoiceMs, s.minGapMs);
  Audio::begin();  // counting starts now, even while WiFi connects
  updateWordMode();

  delay(1200);
  Net::begin();
  Web::begin();
}

void loop() {
  Net::loop();
  Web::loop();
  handleJaps();
  WordSpotter::pollFastTraining();
  updateWordMode();

  const uint32_t key = currentDateKey();
  if (key) Storage::handleDate(key);

  handleButton();
  handleSerial();
  Ota::loop();
  Storage::loop();
  Display::loop();

  if (Net::restartDue()) {
    Voice::stopTraining();
    Storage::flush();
    delay(100);
    ESP.restart();
  }
  delay(2);
}
