#include "web.h"

#include <WebServer.h>
#include <WiFi.h>
#include <time.h>

#include "audio_counter.h"
#include "config.h"
#include "display.h"
#include "net.h"
#include "storage.h"
#include "voice_id.h"
#include <Update.h>

#include "ota.h"
#include "web_pages_gz.h"  // built from web_pages.h
#include "word_spotter.h"

namespace Web {
namespace {

constexpr size_t MAX_NAME_LEN = 16;

WebServer server(80);

String jsonEscape(const String& in) {
  String out;
  out.reserve(in.length() + 4);
  for (size_t i = 0; i < in.length(); i++) {
    const char c = in[i];
    if (c == '"' || c == '\\') {
      out += '\\';
      out += c;
    } else if ((uint8_t)c >= 0x20) {
      out += c;
    }
  }
  return out;
}

String u64(uint64_t v) {
  char b[24];
  snprintf(b, sizeof b, "%llu", (unsigned long long)v);
  return b;
}

// The TFT font is ASCII only, and names also go into CSV files.
String cleanName(const String& in) {
  String out;
  for (size_t i = 0; i < in.length() && out.length() < MAX_NAME_LEN; i++) {
    const char c = in[i];
    if (c >= 0x20 && c <= 0x7E && c != ',' && c != '"' && c != '\\') out += c;
  }
  out.trim();
  return out;
}

void sendJson(const String& body, int code = 200) {
  server.sendHeader("Cache-Control", "no-store");
  server.send(code, "application/json", body);
}

void sendOk() { sendJson("{\"ok\":true}"); }
void sendError(const char* msg) { sendJson(String("{\"ok\":false,\"error\":\"") + msg + "\"}", 400); }

int slotArg() {
  const int slot = server.arg("slot").toInt();
  return (slot >= 0 && slot < Storage::SLOTS) ? slot : -1;
}

void handleStatus() {
  char timeStr[24] = "";
  if (Net::timeValid()) {
    const time_t now = time(nullptr);
    struct tm t;
    localtime_r(&now, &t);
    strftime(timeStr, sizeof timeStr, "%d-%m-%Y %I:%M:%S %p", &t);  // 12-hour IST
  }
  const Storage::Settings& s = Storage::settings();

  String j;
  j.reserve(1400);
  j = "{\"people\":[";
  for (int i = 0; i < Storage::SLOTS; i++) {
    if (i) j += ',';
    j += "{\"n\":\"" + jsonEscape(i == Storage::GUEST ? String("Guest") : Storage::name(i)) + "\"";
    j += ",\"a\":" + String(Storage::active(i) ? "true" : "false");
    j += ",\"t\":" + String(Storage::today(i));
    j += ",\"g\":" + u64(Storage::grand(i));
    j += ",\"p\":" + String(Storage::prevDay(i));
    j += ",\"s\":" + String(Voice::sampleCount(i)) + "}";
  }
  j += "],\"cur\":" + String(Voice::lastSlot());
  j += ",\"conf\":" + String(Voice::lastConfidence());
  j += ",\"train\":{\"p\":" + String(Voice::trainingPerson()) + ",\"d\":" +
       String(Voice::trainingDone()) + ",\"t\":" + String(Voice::trainingTarget()) + "}";
  j += ",\"tAll\":" + String(Storage::todayAll());
  j += ",\"gAll\":" + u64(Storage::grandAll());
  j += ",\"pAll\":" + String(Storage::prevAll());
  j += ",\"rej\":" + String(Audio::rejectedCount());
  char wb[200];
  const float ws = WordSpotter::recentScore();
  snprintf(wb, sizeof wb,
           ",\"word\":{\"name\":\"%s\",\"mode\":%s,\"ready\":%s,\"active\":%s,\"strict\":%u,"
           "\"score\":%.2f,\"thr\":%.2f,\"t\":[",
           jsonEscape(s.wordName).c_str(), s.wordMode ? "true" : "false",
           WordSpotter::ready() ? "true" : "false", Audio::spotting() ? "true" : "false",
           s.wordStrictness, isfinite(ws) ? ws : -1.0f,
           WordSpotter::ready() ? WordSpotter::threshold() : -1.0f);
  j += wb;
  for (int i = 0; i < MAX_PEOPLE; i++) {
    if (i) j += ',';
    j += String(WordSpotter::templateCount(i));
  }
  j += "],\"f\":[";
  for (int i = 0; i < MAX_PEOPLE; i++) {
    if (i) j += ',';
    j += String(WordSpotter::fastTemplateCount(i));
  }
  j += "],\"fast\":{\"p\":" + String(WordSpotter::fastTrainingPerson()) + ",\"d\":" +
       String(WordSpotter::fastTrainingDone()) + ",\"t\":" + String(WordSpotter::fastTrainingTarget()) + "}}";
  j += ",\"mic\":\"" + String(Audio::micStateName()) + "\",\"micCh\":\"" +
       (Audio::micRightChannel() ? "right" : "left") + "\",\"raw\":[" + String(Audio::micRawMin()) + "," +
       String(Audio::micRawMax()) + "]";

  char b[640];
  snprintf(b, sizeof b,
           ",\"paused\":%s,\"voice\":%s,\"level\":%.1f,\"noise\":%.1f,\"thr\":%.1f,"
           "\"time\":\"%s\",\"timeOk\":%s,\"wifi\":%s,\"ap\":%s,\"ssid\":\"%s\",\"ip\":\"%s\","
           "\"rssi\":%d,\"sd\":%s,\"sens\":%u,\"minv\":%u,\"gap\":%u,\"bright\":%u,"
           "\"strict\":%u,\"smooth\":%s,\"tog\":%s,\"tchants\":%u,\"uptime\":%u}",
           Audio::paused() ? "true" : "false", Audio::voiceActive() ? "true" : "false",
           Audio::levelDb(), Audio::noiseDb(), Audio::thresholdDb(), timeStr,
           Net::timeValid() ? "true" : "false", Net::connected() ? "true" : "false",
           Net::apActive() ? "true" : "false", jsonEscape(Net::ssid()).c_str(), Net::ip().c_str(),
           Net::rssi(), Storage::sdOk() ? "true" : "false", s.sensitivity, s.minVoiceMs,
           s.minGapMs, s.brightness, s.strictness, s.smoothing ? "true" : "false",
           s.together ? "true" : "false", s.trainChants,
           (unsigned)(millis() / 1000));
  j += b;
  sendJson(j);
}

void handleSettings() {
  Storage::Settings& s = Storage::settings();
  if (server.hasArg("sens")) s.sensitivity = constrain(server.arg("sens").toInt(), 1, 10);
  if (server.hasArg("minv")) s.minVoiceMs = constrain(server.arg("minv").toInt(), 40, 1000);
  if (server.hasArg("gap")) s.minGapMs = constrain(server.arg("gap").toInt(), 100, 3000);
  if (server.hasArg("bright")) s.brightness = constrain(server.arg("bright").toInt(), 5, 100);
  if (server.hasArg("strict")) s.strictness = constrain(server.arg("strict").toInt(), 1, 10);
  if (server.hasArg("tchants")) s.trainChants = constrain(server.arg("tchants").toInt(), 10, 40);
  if (server.hasArg("smooth")) s.smoothing = server.arg("smooth").toInt() != 0;
  if (server.hasArg("tog")) {  // set from the app
    const bool on = server.arg("tog").toInt() != 0;
    if (on && !s.together) Storage::resetTogetherWords();
    s.together = on;
  }
  if (server.hasArg("wmode")) s.wordMode = server.arg("wmode").toInt() != 0;
  if (server.hasArg("wstrict")) s.wordStrictness = constrain(server.arg("wstrict").toInt(), 1, 10);
  if (server.hasArg("wname")) {
    const String name = cleanName(server.arg("wname"));
    if (name.isEmpty()) return sendError("Please type the word in English letters");
    s.wordName = name;
  }
  Storage::saveSettings();
  Audio::setTuning(s.sensitivity, s.minVoiceMs, s.minGapMs);
  Voice::setMatching(s.strictness, s.smoothing);
  WordSpotter::setStrictness(s.wordStrictness);
  Display::setBrightness(s.brightness);
  sendOk();
}

void handlePerson() {
  const String action = server.arg("action");
  const int slot = slotArg();
  const bool isPerson = slot >= 0 && slot < MAX_PEOPLE;

  if (action == "add") {
    const String name = cleanName(server.arg("name"));
    if (name.isEmpty()) return sendError("Please type a name in English letters");
    for (int i = 0; i < MAX_PEOPLE; i++) {
      if (!Storage::active(i)) {
        Voice::clearVoice(i);  // drop anything left from an old person
        WordSpotter::clear(i);
        Storage::setName(i, name);
        return sendJson("{\"ok\":true,\"slot\":" + String(i) + "}");
      }
    }
    return sendError("All places are used. Remove someone first.");
  }
  if (action == "stop") {
    Voice::stopTraining();
    WordSpotter::stopFastTraining();
    return sendOk();
  }
  if (!isPerson || !Storage::active(slot)) return sendError("Unknown person");

  if (action == "name") {
    const String name = cleanName(server.arg("name"));
    if (name.isEmpty()) return sendError("Please type a name in English letters");
    Storage::setName(slot, name);
  } else if (action == "fast") {
    if (Voice::trainingPerson() >= 0) return sendError("Finish the current training first");
    if (!WordSpotter::beginFastTraining(slot, FAST_TRAIN_EXAMPLES))
      return sendError("Teach the word with pauses first, then fast chanting");
  } else if (action == "train" || action == "more") {
    WordSpotter::stopFastTraining();
    WordSpotter::beginTraining(slot, action == "more");
    Voice::startTraining(slot, Storage::settings().trainChants, action == "more");
  } else if (action == "clearvoice") {
    Voice::clearVoice(slot);
    WordSpotter::clear(slot);
  } else if (action == "remove") {
    Voice::clearVoice(slot);
    WordSpotter::clear(slot);
    Storage::removePerson(slot);
  } else {
    return sendError("Unknown action");
  }
  sendOk();
}

void handleCount() {
  const int slot = slotArg();
  if (slot < 0) return sendError("Unknown person");
  const String op = server.arg("op");
  const long n = server.arg("n").toInt();
  if (op == "set") {
    if (n < 0) return sendError("Invalid count");
    Storage::setToday(slot, (uint32_t)n);
  } else if (op == "add") {
    const uint32_t today = Storage::today(slot);
    if (n >= 0)
      Storage::addCount(slot, (uint32_t)n, Net::timeValid());
    else
      Storage::setToday(slot, (uint32_t)(-n) >= today ? 0 : today - (uint32_t)(-n));
  } else {
    return sendError("Unknown operation");
  }
  sendOk();
}

void handleReset() {
  if (server.arg("what") != "all") return sendError("Unknown reset");
  Storage::resetAll();
  sendOk();
}

void handleScan() {
  const char* scanning = "{\"scanning\":true}";
  if (server.hasArg("fresh")) {
    WiFi.scanDelete();
    WiFi.scanNetworks(true);
    return sendJson(scanning);
  }
  const int n = WiFi.scanComplete();
  if (n == WIFI_SCAN_RUNNING) return sendJson(scanning);
  if (n < 0) {
    WiFi.scanNetworks(true);
    return sendJson(scanning);
  }
  String j = "{\"nets\":[";
  bool first = true;
  for (int i = 0; i < n; i++) {
    const String ssid = WiFi.SSID(i);
    if (ssid.isEmpty()) continue;
    bool dup = false;
    for (int k = 0; k < i && !dup; k++) dup = WiFi.SSID(k) == ssid;
    if (dup) continue;
    if (!first) j += ',';
    first = false;
    j += "{\"s\":\"" + jsonEscape(ssid) + "\",\"r\":" + String(WiFi.RSSI(i)) +
         ",\"e\":" + (WiFi.encryptionType(i) == WIFI_AUTH_OPEN ? "0" : "1") + "}";
  }
  j += "]}";
  sendJson(j);
}

void handleWifiSave() {
  const String ssid = server.arg("ssid");
  if (ssid.isEmpty() || ssid.length() > 32) return sendError("WiFi name is required");
  if (server.arg("pass").length() > 64) return sendError("Password too long");
  Net::saveCredentials(ssid, server.arg("pass"));
  sendOk();
  Display::showMessage("WiFi saved", "Restarting...", 5000);
  Net::scheduleRestart(1500);
}

void handleForget() {
  Net::forgetCredentials();
  sendOk();
  Display::showMessage("WiFi cleared", "Restarting...", 5000);
  Net::scheduleRestart(1500);
}

void handleHistoryCsv() {
  server.sendHeader("Content-Disposition", "attachment; filename=\"jap_history.csv\"");
  server.send(200, "text/csv", Storage::historyCsv());
}

// ------------------------------------------------------------ firmware update

const char* otaStateName(Ota::State s) {
  switch (s) {
    case Ota::State::PinShown: return "pin";
    case Ota::State::Uploading: return "uploading";
    case Ota::State::Downloading: return "downloading";
    case Ota::State::Done: return "done";
    case Ota::State::Failed: return "failed";
    default: return "idle";
  }
}

void handleOtaStatus() {
  const Storage::Settings& s = Storage::settings();
  sendJson(String("{\"state\":\"") + otaStateName(Ota::state()) + "\",\"progress\":" + String(Ota::progress()) +
           ",\"message\":\"" + jsonEscape(Ota::message()) + "\",\"version\":\"" FIRMWARE_VERSION "\",\"board\":\"" BOARD_NAME
           "\",\"repo\":\"" + jsonEscape(s.otaRepo) + "\",\"branch\":\"" + jsonEscape(s.otaBranch) + "\"}");
}

void handleOtaCheck() {
  const Ota::Available a = Ota::checkGithub();
  sendJson(String("{\"ok\":") + (a.ok ? "true" : "false") + ",\"version\":\"" + jsonEscape(a.version) +
           "\",\"notes\":\"" + jsonEscape(a.notes) + "\",\"size\":" + String((unsigned)a.size) +
           ",\"newer\":" + (a.ok && Ota::isNewer(a.version) ? "true" : "false") + ",\"message\":\"" +
           jsonEscape(Ota::message()) + "\"}");
}

void handleOtaInstall() {
  if (!Ota::installGithub(server.arg("pin"))) return sendError(Ota::message().c_str());
  sendOk();
}

void handleOtaSource() {
  Storage::Settings& s = Storage::settings();
  String repo = server.arg("repo");
  repo.trim();
  // "owner/name", GitHub characters only (or empty = off)
  bool valid = repo.isEmpty() || (repo.indexOf('/') > 0 && repo.indexOf('/') == repo.lastIndexOf('/') && !repo.endsWith("/"));
  for (size_t i = 0; i < repo.length() && valid; i++) {
    const char c = repo[i];
    valid = isAlphaNumeric(c) || c == '-' || c == '_' || c == '.' || c == '/';
  }
  if (!valid) return sendError("Use the form owner/repository");
  String branch = server.hasArg("branch") ? server.arg("branch") : String("main");
  branch.trim();
  if (branch.isEmpty()) branch = "main";
  s.otaRepo = repo;
  s.otaBranch = branch;
  Storage::saveSettings();
  sendOk();
}

// Firmware file upload. The PIN shown on the counter's screen is sent as ?pin=.
void handleOtaUploadData() {
  HTTPUpload& up = server.upload();
  if (up.status == UPLOAD_FILE_START) {
    Ota::uploadStart(server.arg("pin"), 0);
  } else if (up.status == UPLOAD_FILE_WRITE) {
    Ota::uploadWrite(up.buf, up.currentSize);
  } else if (up.status == UPLOAD_FILE_END) {
    Ota::uploadFinish();
  } else if (up.status == UPLOAD_FILE_ABORTED) {
    Update.abort();
  }
}

void handleOtaUploadDone() {
  if (Ota::state() == Ota::State::Done) return sendOk();
  sendError(Ota::message().c_str());
}

void handleNotFound() {
  // Captive portal: phones probe random URLs; send them to the setup page.
  if (Net::apActive()) {
    server.sendHeader("Location", "http://192.168.4.1/setup", true);
    server.send(302, "text/plain", "");
    return;
  }
  server.send(404, "text/plain", "Not found");
}

void sendGz(const char* type, const uint8_t* data, size_t len) {
  server.sendHeader("Content-Encoding", "gzip");
  server.send_P(200, type, (PGM_P)data, len);
}

}  // namespace

void begin() {
  // Pages are sent gzip-compressed (tools/gzip_pages.py): a third of the
  // bytes, so they load even when the WiFi link drops packets.
  server.on("/", HTTP_GET, [] { sendGz("text/html", DASHBOARD_HTML_GZ, DASHBOARD_HTML_GZ_LEN); });
  server.on("/setup", HTTP_GET, [] { sendGz("text/html", SETUP_HTML_GZ, SETUP_HTML_GZ_LEN); });
  server.on("/style.css", HTTP_GET, [] {
    server.sendHeader("Cache-Control", "max-age=86400");
    sendGz("text/css", STYLE_CSS_GZ, STYLE_CSS_GZ_LEN);
  });
  server.on("/history.csv", HTTP_GET, handleHistoryCsv);

  server.on("/api/info", HTTP_GET, [] {
    sendJson("{\"app\":\"japcounter\",\"id\":\"" + Net::deviceId() + "\",\"fw\":\"" FIRMWARE_VERSION
             "\",\"board\":\"" BOARD_NAME "\",\"maxPeople\":" + String(MAX_PEOPLE) +
             ",\"freeHeap\":" + String(ESP.getFreeHeap()) + ",\"minFreeHeap\":" + String(ESP.getMinFreeHeap()) +
             ",\"maxBlock\":" + String(ESP.getMaxAllocHeap()) +
             ",\"reset\":" + String((int)esp_reset_reason()) + "}");
  });
  server.on("/api/status", HTTP_GET, handleStatus);
  server.on("/api/history", HTTP_GET, [] { sendJson(Storage::historyJson(60)); });
  server.on("/api/summary", HTTP_GET, [] { sendJson(Storage::summaryJson(24)); });
  server.on("/api/ota/status", HTTP_GET, handleOtaStatus);
  server.on("/api/ota/pin", HTTP_POST, [] {
    Ota::showPin();
    sendOk();
  });
  server.on("/api/ota/check", HTTP_POST, handleOtaCheck);
  server.on("/api/ota/install", HTTP_POST, handleOtaInstall);
  server.on("/api/ota/source", HTTP_POST, handleOtaSource);
  server.on("/api/ota/upload", HTTP_POST, handleOtaUploadDone, handleOtaUploadData);
  server.on("/api/scan", HTTP_GET, handleScan);
  server.on("/api/settings", HTTP_POST, handleSettings);
  server.on("/api/person", HTTP_POST, handlePerson);
  server.on("/api/count", HTTP_POST, handleCount);
  server.on("/api/reset", HTTP_POST, handleReset);
  server.on("/api/pause", HTTP_POST, [] {
    Audio::setPaused(!Audio::paused());
    sendOk();
  });
  server.on("/api/wifi", HTTP_POST, handleWifiSave);
  server.on("/api/forget", HTTP_POST, handleForget);
  server.onNotFound(handleNotFound);
  server.begin();
}

void loop() { server.handleClient(); }

}  // namespace Web
