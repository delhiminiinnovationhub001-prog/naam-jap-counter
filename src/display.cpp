#include "display.h"

#include <Adafruit_GFX.h>
#include <Adafruit_ST7735.h>
#include <SPI.h>
#include <time.h>

#include "audio_counter.h"
#include "config.h"
#include "net.h"
#include "ota.h"
#include "storage.h"
#include "voice_id.h"
#include "word_spotter.h"

namespace Display {
namespace {

Adafruit_ST7735 tft(&SPI, PIN_TFT_CS, PIN_TFT_DC, PIN_TFT_RST);

constexpr int16_t W = 160;
constexpr int16_t H = 128;
constexpr uint16_t C_BG = ST77XX_BLACK;
constexpr uint16_t C_HEAD = 0x2104;     // dark grey
constexpr uint16_t C_SAFFRON = 0xFC00;  // RGB(255,128,0)
constexpr uint16_t C_MUTED = 0x8410;
constexpr uint16_t C_TEXT = ST77XX_WHITE;
constexpr uint8_t BL_CHANNEL = 0;

// Mic level bar
constexpr int16_t BAR_X = 3, BAR_Y = 114, BAR_W = W - 6, BAR_H = 10;
constexpr float BAR_MIN_DB = -80.0f, BAR_MAX_DB = -20.0f;

enum class Screen { None, Main, Setup, Message };
Screen screen = Screen::None;
uint32_t messageUntilMs = 0;
uint32_t lastUpdateMs = 0;

// Last drawn text of each area, so only changed parts are repainted.
String cTime, cDate, cTitle, cBig, cRow1, cRow2, cRow3;
int cWifi, cSd, cVoice;
int16_t cBarW, cThrX;

void resetCache() {
  cTime = cDate = cTitle = cBig = cRow1 = cRow2 = cRow3 = "\x01";
  cWifi = cSd = cVoice = -1;
  cBarW = cThrX = -1;
}

// 1234567 -> "12,34,567"
String indian(uint64_t v) {
  char raw[24];
  snprintf(raw, sizeof raw, "%llu", (unsigned long long)v);
  const int len = strlen(raw);
  char rev[32];
  int r = 0;
  for (int i = len - 1, digits = 0; i >= 0; i--, digits++) {
    if (digits == 3 || (digits > 3 && (digits - 3) % 2 == 0)) rev[r++] = ',';
    rev[r++] = raw[i];
  }
  String out;
  for (int i = r - 1; i >= 0; i--) out += rev[i];
  return out;
}

void textAt(int16_t x, int16_t y, const String& s, uint8_t size, uint16_t fg, uint16_t bg) {
  tft.setTextSize(size);
  tft.setTextColor(fg, bg);
  tft.setCursor(x, y);
  tft.print(s);
}

void centered(int16_t y, int16_t h, const String& s, uint8_t size, uint16_t fg, uint16_t bg = C_BG) {
  tft.fillRect(0, y, W, h, bg);
  const int16_t w = s.length() * 6 * size;
  textAt((W - w) / 2, y + (h - 8 * size) / 2, s, size, fg, bg);
}

// One text row with a left and an optional right-aligned part.
void row(int16_t y, const String& left, const String& right, uint16_t color) {
  tft.fillRect(0, y, W, 9, C_BG);
  textAt(4, y + 1, left, 1, color, C_BG);
  if (right.length()) textAt(W - 4 - right.length() * 6, y + 1, right, 1, color, C_BG);
}

uint8_t fitSize(const String& s, uint8_t preferred) {
  while (preferred > 1 && (int)s.length() * 6 * preferred > W - 4) preferred--;
  return preferred;
}

void drawWifiIcon(int state) {  // 0 = offline, 1 = online, 2 = setup AP
  const uint16_t c = state == 1 ? ST77XX_GREEN : state == 2 ? ST77XX_YELLOW : ST77XX_RED;
  tft.fillRect(142, 0, 18, 13, C_HEAD);
  for (int i = 0; i < 3; i++) {
    const int16_t h = 3 + i * 3;
    tft.fillRect(144 + i * 5, 11 - h, 3, h, c);
  }
}

// ---------------------------------------------------------------- main screen

void drawMainStatic() {
  tft.fillScreen(C_BG);
  tft.fillRect(0, 0, W, 13, C_HEAD);
  tft.drawFastHLine(8, 72, W - 16, C_MUTED);
  tft.drawRect(BAR_X - 1, BAR_Y - 1, BAR_W + 2, BAR_H + 2, C_MUTED);
  resetCache();
}

void updateHeader() {
  char tb[16], db[12];
  if (Net::timeValid()) {
    const time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    strftime(tb, sizeof tb, "%I:%M:%S %p", &t);  // 12-hour IST, e.g. 09:45:03 PM
    strftime(db, sizeof db, "%d-%m-%y", &t);
  } else {
    strcpy(tb, "--:--:-- --");
    strcpy(db, "no time ");
  }
  if (cTime != tb) {
    cTime = tb;
    textAt(2, 3, cTime, 1, C_TEXT, C_HEAD);
  }
  if (cDate != db) {
    cDate = db;
    textAt(74, 3, cDate, 1, C_TEXT, C_HEAD);
  }

  const int sd = Storage::sdOk();
  if (sd != cSd) {
    cSd = sd;
    textAt(124, 3, "SD", 1, sd ? ST77XX_GREEN : C_HEAD, C_HEAD);
  }
  const int wifi = Net::connected() ? 1 : Net::apActive() ? 2 : 0;
  if (wifi != cWifi) {
    cWifi = wifi;
    drawWifiIcon(wifi);
  }
}

// Shows the person who chanted last (or is training) with their counts.
// With no people set up, it shows the combined counts like a plain counter.
void updateCounts() {
  const int training = Voice::trainingPerson();
  const int fast = WordSpotter::fastTrainingPerson();
  const int slot = training >= 0 ? training : fast >= 0 ? fast : Voice::lastSlot();
  const bool perPerson = slot >= 0 && (Storage::activeCount() > 0 || slot != Storage::GUEST);

  const bool needsTeaching = training < 0 && Storage::settings().wordMode && !WordSpotter::ready();
  String title;
  uint16_t titleColor = C_SAFFRON;
  if (needsTeaching) {
    title = "TEACH WORD";
    titleColor = ST77XX_YELLOW;
  } else if (training >= 0) {
    title = Storage::name(training) + " " + String(Voice::trainingDone()) + "/" +
            String(Voice::trainingTarget());
    titleColor = ST77XX_YELLOW;
  } else if (fast >= 0) {
    title = Storage::name(fast) + " FAST " + String(WordSpotter::fastTrainingDone()) + "/" +
            String(WordSpotter::fastTrainingTarget());
    titleColor = ST77XX_YELLOW;
  } else if (Audio::paused()) {
    title = "PAUSED";
    titleColor = ST77XX_RED;
  } else {
    title = perPerson ? Storage::name(slot) : Audio::spotting() ? Storage::settings().wordName : String("TODAY");
    title.toUpperCase();
  }
  if (title != cTitle) {
    cTitle = title;
    centered(15, 18, title, fitSize(title, 2), titleColor);
  }

  const uint32_t today = perPerson ? Storage::today(slot) : Storage::todayAll();
  const String big = indian(today);
  if (big != cBig) {
    cBig = big;
    centered(35, 34, big, fitSize(big, 4), training >= 0 ? ST77XX_YELLOW : C_SAFFRON);
  }

  String r1 = "Total " + indian(perPerson ? Storage::grand(slot) : Storage::grandAll());
  String r1right = "Mala " + indian(today / JAPS_PER_MALA);
  if (r1 + r1right != cRow1) {
    cRow1 = r1 + r1right;
    row(76, r1, r1right, C_TEXT);
  }

  String r2, r3;
  if (needsTeaching) {
    r2 = "Not counting yet: open";
    r3 = "app/web > People > Train";
  } else if (training >= 0) {
    r2 = "Say " + Storage::settings().wordName + ", pause,";
    r3 = "then again...";
  } else if (fast >= 0) {
    r2 = "Chant fast, no pauses";
    r3 = "learning your speed...";
  } else if (perPerson) {
    r2 = "All today " + indian(Storage::todayAll());
    r3 = "All total " + indian(Storage::grandAll());
  } else {
    r2 = "Prev day " + indian(Storage::prevAll());
    r3 = "";
  }
  // The counter's address for the web page, so it can always be found even
  // after the router hands out a new one: alternates with "All total" every 4 s.
  const bool normalRow = !needsTeaching && training < 0 && fast < 0;
  const bool showIp = normalRow && Net::connected() && (r3.isEmpty() || (millis() / 4000) % 2);
  if (showIp) r3 = "IP " + Net::ip();
  if (r2 != cRow2) {
    cRow2 = r2;
    row(89, r2, "", C_MUTED);
  }
  String r3right;
  if (perPerson && training < 0 && fast < 0 && slot != Storage::GUEST)
    r3right = String(Voice::lastConfidence()) + "%";  // match confidence
  if (r3 + r3right != cRow3) {
    cRow3 = r3 + r3right;
    row(100, r3, r3right, C_MUTED);
  }
}

int16_t dbToX(float db) {
  float f = (db - BAR_MIN_DB) / (BAR_MAX_DB - BAR_MIN_DB);
  f = constrain(f, 0.0f, 1.0f);
  return (int16_t)(f * (BAR_W - 1));
}

void updateLevelBar() {
  const Audio::MicState mic = Audio::micState();
  if (mic == Audio::MicState::NoData || mic == Audio::MicState::StuckHigh) {
    if (cVoice != -2) {  // draw the warning once
      cVoice = -2;
      cBarW = cThrX = -1;
      tft.fillRect(BAR_X, BAR_Y, BAR_W, BAR_H, ST77XX_RED);
      textAt((W - 13 * 6) / 2, BAR_Y + 1, "NO MIC SIGNAL", 1, ST77XX_WHITE, ST77XX_RED);
      tft.drawRect(BAR_X - 1, BAR_Y - 1, BAR_W + 2, BAR_H + 2, C_MUTED);
    }
    return;
  }
  const int16_t bw = dbToX(Audio::levelDb());
  const int16_t tx = dbToX(Audio::thresholdDb());
  const int voice = Audio::voiceActive();
  if (bw == cBarW && tx == cThrX && voice == cVoice) return;
  cBarW = bw;
  cThrX = tx;
  cVoice = voice;
  const uint16_t col = Audio::paused() ? C_MUTED : voice ? ST77XX_GREEN : ST77XX_CYAN;
  tft.fillRect(BAR_X, BAR_Y, bw, BAR_H, col);
  tft.fillRect(BAR_X + bw, BAR_Y, BAR_W - bw, BAR_H, C_BG);
  tft.drawFastVLine(BAR_X + tx, BAR_Y, BAR_H, ST77XX_RED);
}

// ------------------------------------------------------- milestone animations
//
// Every 100 japs of a person (100, 200 ... 900): rings burst from the centre
// and the number appears. Every 1000: the screen flashes the word ("RADHA")
// in changing colours, with fireworks, then holds the number.

enum class Anim { None, Hundred, Thousand };
constexpr uint32_t ANIM_FRAME_MS = 60;
constexpr int HUNDRED_FRAMES = 38;    // ~2.3 s
constexpr int THOUSAND_FRAMES = 90;   // ~5.4 s
const uint16_t FLASH_COLORS[] = {C_SAFFRON, ST77XX_MAGENTA, ST77XX_YELLOW, ST77XX_CYAN, ST77XX_RED, ST77XX_GREEN};

Anim anim = Anim::None;
uint32_t animStartMs = 0;
int animLastFrame = -1;
uint64_t animValue = 0;
String animName;
uint32_t lastSeen[Storage::SLOTS];
bool seenInit = false;

uint16_t flashColor(int i) { return FLASH_COLORS[i % (sizeof FLASH_COLORS / sizeof FLASH_COLORS[0])]; }

void startAnim(uint64_t value, int slot) {
  anim = value % 1000 == 0 ? Anim::Thousand : Anim::Hundred;
  animStartMs = millis();
  animLastFrame = -1;
  animValue = value;
  animName = slot == Storage::GUEST ? String("") : Storage::name(slot);
  animName.toUpperCase();
}

// Starts an animation when someone's count today passes a multiple of 100.
void checkMilestones() {
  for (int s = 0; s < Storage::SLOTS; s++) {
    const uint32_t v = Storage::today(s);
    if (seenInit && v > lastSeen[s] && v / 100 > lastSeen[s] / 100 && v >= 100) startAnim((uint64_t)(v / 100) * 100, s);
    lastSeen[s] = v;
  }
  seenInit = true;
}

void burst(int16_t cx, int16_t cy, int16_t r, uint16_t c) {
  for (int k = 0; k < 8; k++) {
    const float a = k * PI / 4;
    tft.fillCircle(cx + (int16_t)(cosf(a) * r), cy + (int16_t)(sinf(a) * r), 2, c);
  }
}

void drawHundred(int f) {
  const int16_t cx = W / 2, cy = 52;
  if (f == 0) tft.fillScreen(C_BG);
  if (f < 14) {
    // Rings expanding from the centre, saffron and yellow.
    tft.drawCircle(cx, cy, 6 + f * 7, f % 2 ? ST77XX_YELLOW : C_SAFFRON);
    tft.drawCircle(cx, cy, 5 + f * 7, f % 2 ? ST77XX_YELLOW : C_SAFFRON);
  }
  if (f == 14) tft.fillScreen(C_BG);
  if (f >= 14) {
    if (f % 4 == 0) burst(cx, cy, 44 + (f % 8), flashColor(f / 4));
    const String num = indian(animValue);
    if (f == 14 || f % 6 == 0) {
      tft.setTextSize(fitSize(num, 5));
      tft.setTextColor(f % 12 < 6 ? C_SAFFRON : ST77XX_YELLOW);
      const uint8_t sz = fitSize(num, 5);
      tft.setCursor((W - num.length() * 6 * sz) / 2, cy - 4 * sz);
      tft.print(num);
    }
    if (f == 14) {
      centered(92, 10, "JAPS", 1, C_TEXT);
      if (animName.length()) centered(106, 18, animName, fitSize(animName, 2), C_SAFFRON);
    }
  }
}

void drawThousand(int f) {
  String word = Storage::settings().wordName;
  word.toUpperCase();
  const uint8_t wsz = fitSize(word, 4);
  const String num = indian(animValue);
  if (f < 24) {
    // Phase 1: full-screen colour flashes with the word.
    const uint16_t bg = flashColor(f);
    const uint16_t fg = f % 2 ? ST77XX_WHITE : C_BG;
    tft.fillScreen(bg);
    tft.setTextSize(wsz);
    tft.setTextColor(fg);
    tft.setCursor((W - word.length() * 6 * wsz) / 2, 40);
    tft.print(word);
  } else if (f < 66) {
    // Phase 2: fireworks around the word, which changes colour.
    if (f == 24) tft.fillScreen(C_BG);
    if (f % 3 == 0) {
      const int16_t x = 15 + (esp_random() % (W - 30)), y = 15 + (esp_random() % (H - 30));
      const uint16_t c = flashColor(esp_random() % 6);
      for (int r = 3; r <= 15; r += 4) burst(x, y, r, c);
    }
    tft.setTextSize(wsz);
    tft.setTextColor(flashColor(f / 2), C_BG);
    tft.setCursor((W - word.length() * 6 * wsz) / 2, 36);
    tft.print(word);
    if (f == 24 || f % 10 == 0) {
      tft.setTextSize(3);
      tft.setTextColor(ST77XX_WHITE, C_BG);
      tft.setCursor((W - num.length() * 18) / 2, 78);
      tft.print(num);
    }
  } else if (f == 66) {
    // Phase 3: calm saffron card with the word, number and name.
    tft.fillScreen(C_SAFFRON);
    tft.setTextSize(wsz);
    tft.setTextColor(ST77XX_WHITE);
    tft.setCursor((W - word.length() * 6 * wsz) / 2, 18);
    tft.print(word);
    centered(60, 26, num, fitSize(num, 3), C_BG, C_SAFFRON);
    centered(90, 10, "JAPS", 1, C_BG, C_SAFFRON);
    if (animName.length()) centered(104, 18, animName, fitSize(animName, 2), ST77XX_WHITE, C_SAFFRON);
  }
}

// Returns true while an animation owns the screen.
bool runAnimation() {
  if (anim == Anim::None) return false;
  const int frame = (millis() - animStartMs) / ANIM_FRAME_MS;
  const int total = anim == Anim::Thousand ? THOUSAND_FRAMES : HUNDRED_FRAMES;
  if (frame >= total) {
    anim = Anim::None;
    screen = Screen::None;  // redraw the normal screen
    return false;
  }
  for (int f = animLastFrame + 1; f <= frame; f++) {
    // Skip frames we are late for, but never skip a "draw once" frame.
    if (f < frame && f != 0 && f != 14 && f != 24 && f != 66) continue;
    if (anim == Anim::Thousand)
      drawThousand(f);
    else
      drawHundred(f);
  }
  animLastFrame = frame;
  return true;
}

// ------------------------------------------------------------ firmware update

Ota::State otaLast = Ota::State::Idle;
bool otaOwnsScreen = false, otaFailShown = false;
uint32_t otaHideMs = 0;
uint8_t otaProg = 255;

void drawOtaStatic(Ota::State st) {
  tft.fillScreen(C_BG);
  switch (st) {
    case Ota::State::PinShown:
      centered(8, 10, "FIRMWARE UPDATE", 1, C_SAFFRON);
      centered(24, 18, "PIN", 2, C_TEXT);
      centered(48, 42, Ota::pin(), 5, ST77XX_YELLOW);
      centered(98, 10, "Type it on the web page", 1, C_MUTED);
      centered(110, 10, "valid for 3 minutes", 1, C_MUTED);
      break;
    case Ota::State::Uploading:
    case Ota::State::Downloading:
      centered(14, 18, "UPDATING", 2, C_SAFFRON);
      centered(40, 10, st == Ota::State::Downloading ? "from GitHub" : "from the web page", 1, C_TEXT);
      tft.drawRect(9, 64, W - 18, 16, C_MUTED);
      centered(100, 10, "Do not switch off", 1, ST77XX_YELLOW);
      break;
    case Ota::State::Done:
      centered(40, 18, "UPDATE OK", 2, ST77XX_GREEN);
      centered(70, 10, "Restarting...", 1, C_TEXT);
      break;
    default: {
      centered(30, 18, "UPDATE FAILED", fitSize("UPDATE FAILED", 2), ST77XX_RED);
      String m = Ota::message();
      centered(62, 10, m.substring(0, 26), 1, C_TEXT);
      if (m.length() > 26) centered(74, 10, m.substring(26, 52), 1, C_TEXT);
      centered(100, 10, "Old firmware still runs", 1, C_MUTED);
      break;
    }
  }
}

// Returns true while an update owns the screen.
bool drawOta() {
  const Ota::State st = Ota::state();
  const uint32_t now = millis();
  if (st != Ota::State::Failed) otaFailShown = false;
  const bool hide = st == Ota::State::Idle || (st == Ota::State::Failed && otaFailShown && (int32_t)(now - otaHideMs) > 0);
  if (hide) {
    otaLast = st;
    if (otaOwnsScreen) {
      otaOwnsScreen = false;
      screen = Screen::None;  // redraw the normal screen
    }
    return false;
  }
  if (st != otaLast || !otaOwnsScreen) {
    otaLast = st;
    otaOwnsScreen = true;
    otaProg = 255;
    drawOtaStatic(st);
    if (st == Ota::State::Failed) {
      otaFailShown = true;
      otaHideMs = now + 6000;
    }
  }
  if (st == Ota::State::Uploading || st == Ota::State::Downloading) {
    const uint8_t p = Ota::progress();
    if (p != otaProg) {
      otaProg = p;
      tft.fillRect(11, 66, (W - 22) * p / 100, 12, ST77XX_GREEN);
      centered(84, 10, String(p) + "%", 1, C_TEXT);
    }
  }
  return true;
}

// --------------------------------------------------------------- setup screen

void drawSetupStatic() {
  tft.fillScreen(C_BG);
  centered(0, 13, "WIFI SETUP", 1, C_BG, C_SAFFRON);
  textAt(4, 18, "1. Join this WiFi:", 1, C_MUTED, C_BG);
  centered(28, 10, Net::apSsid(), 1, C_TEXT);
  textAt(4, 42, "Password:", 1, C_MUTED, C_BG);
  centered(52, 18, Net::apPassword(), 2, ST77XX_YELLOW);
  textAt(4, 76, "2. Open in browser:", 1, C_MUTED, C_BG);
  centered(86, 18, "192.168.4.1", 2, ST77XX_CYAN);
  resetCache();
}

void updateSetup() {
  const String s = "Today: " + indian(Storage::todayAll());
  if (s != cBig) {
    cBig = s;
    centered(114, 10, s, 1, C_SAFFRON);
  }
}

}  // namespace

void begin() {
  // 25 kHz: backlight current pulses at 5 kHz used to leak into the mic's
  // 3.3 V supply as an audible whine; 25 kHz is above the mic's audio band.
  ledcSetup(BL_CHANNEL, 25000, 8);
  ledcAttachPin(PIN_TFT_BL, BL_CHANNEL);
  setBrightness(DEFAULT_BRIGHTNESS);
  tft.initR(TFT_TAB);
  tft.setRotation(1);  // landscape, pins on the left
  tft.setTextWrap(false);
  tft.fillScreen(C_BG);
}

void setBrightness(uint8_t percent) {
  percent = constrain(percent, 5, 100);
  // 256 = fully on with no switching at all (8-bit resolution).
  ledcWrite(BL_CHANNEL, percent >= 100 ? 256 : (uint32_t)percent * 255 / 100);
}

void splash() {
  tft.fillScreen(C_BG);
  centered(28, 18, "NAAM JAP", 2, C_SAFFRON);
  centered(50, 10, "COUNTER", 1, C_TEXT);
  centered(84, 10, "|| Radhe Radhe ||", 1, C_MUTED);
  screen = Screen::Message;
  messageUntilMs = millis();
}

void showConnecting(const String& ssid) { showMessage("Connecting", ssid, 0); }

void showMessage(const String& title, const String& detail, uint32_t holdMs) {
  tft.fillScreen(C_BG);
  centered(40, 18, title, fitSize(title, 2), C_SAFFRON);
  centered(70, 10, detail, 1, C_TEXT);
  screen = Screen::Message;
  messageUntilMs = millis() + holdMs;
}

void loop() {
  const uint32_t now = millis();
  if (screen == Screen::Message && (int32_t)(now - messageUntilMs) < 0) return;
  if (now - lastUpdateMs < 50) return;
  lastUpdateMs = now;

  if (drawOta()) return;
  checkMilestones();
  if (runAnimation()) return;

  const Screen want = (Net::apActive() && !Net::connected()) ? Screen::Setup : Screen::Main;
  if (want != screen) {
    screen = want;
    if (want == Screen::Main)
      drawMainStatic();
    else
      drawSetupStatic();
  }

  if (screen == Screen::Main) {
    updateHeader();
    updateCounts();
    updateLevelBar();
  } else {
    updateSetup();
  }
}

}  // namespace Display
