#include "ota.h"

#include <HTTPClient.h>
#include <Update.h>
#include <WiFiClientSecure.h>

#include "config.h"
#include "net.h"
#include "ota_certs.h"
#include "storage.h"

namespace Ota {
namespace {

constexpr uint32_t PIN_VALID_MS = 180000;  // 3 minutes
constexpr uint32_t RESTART_DELAY_MS = 2500;
constexpr uint32_t STALL_TIMEOUT_MS = 15000;

volatile State gState = State::Idle;
char gPin[5] = "";
uint32_t pinUntilMs = 0;
volatile uint8_t gProgress = 0;
char gMsg[112] = "";
size_t expected = 0, written = 0;

// Found by checkGithub(), used by the download task.
String jobUrl, jobMd5;
size_t jobSize = 0;
TaskHandle_t job = nullptr;

void setMessage(const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(gMsg, sizeof gMsg, fmt, ap);
  va_end(ap);
  Serial.printf("[ota] %s\n", gMsg);
}

String baseUrl() {
  const Storage::Settings& s = Storage::settings();
  return "https://raw.githubusercontent.com/" + s.otaRepo + "/" + s.otaBranch + "/";
}

// Tiny JSON readers for the flat manifest; `from` starts the search.
String jsonString(const String& j, const char* key, int from = 0) {
  const int k = j.indexOf(String("\"") + key + "\"", from);
  if (k < 0) return "";
  const int q1 = j.indexOf('"', j.indexOf(':', k) + 1);
  const int q2 = j.indexOf('"', q1 + 1);
  return (q1 < 0 || q2 < 0) ? String("") : j.substring(q1 + 1, q2);
}

long jsonNumber(const String& j, const char* key, int from = 0) {
  const int k = j.indexOf(String("\"") + key + "\"", from);
  if (k < 0) return -1;
  return j.substring(j.indexOf(':', k) + 1).toInt();
}

void parseVersion(const String& v, int out[3]) {
  out[0] = out[1] = out[2] = 0;
  int part = 0;
  for (size_t i = 0; i < v.length() && part < 3; i++) {
    const char c = v[i];
    if (c == '.')
      part++;
    else if (isDigit(c))
      out[part] = out[part] * 10 + (c - '0');
  }
}

void finish(bool ok) {
  if (ok) {
    gProgress = 100;
    gState = State::Done;
    setMessage("Update installed. Restarting...");
    Net::scheduleRestart(RESTART_DELAY_MS);
  } else {
    gState = State::Failed;
  }
}

void downloadTask(void*) {
  WiFiClientSecure client;
  client.setCACert(OTA_ROOT_CAS);
  HTTPClient http;
  http.setTimeout(STALL_TIMEOUT_MS);
  bool ok = false;
  if (!http.begin(client, jobUrl)) {
    setMessage("Could not open the download");
  } else {
    const int code = http.GET();
    const int len = code == 200 ? http.getSize() : -1;
    if (code != 200) {
      setMessage("GitHub download: HTTP %d", code);
    } else if (len <= 0 || (size_t)len != jobSize) {
      setMessage("Download size %d does not match the manifest (%u)", len, (unsigned)jobSize);
    } else if (!Update.begin(len)) {
      setMessage("No room for the update: %s", Update.errorString());
    } else {
      Update.setMD5(jobMd5.c_str());
      WiFiClient* stream = http.getStreamPtr();
      static uint8_t buf[1024];
      size_t got = 0;
      uint32_t lastData = millis();
      while (got < (size_t)len && millis() - lastData < STALL_TIMEOUT_MS) {
        const size_t avail = stream->available();
        if (!avail) {
          delay(2);
          continue;
        }
        const int r = stream->readBytes(buf, min(avail, sizeof buf));
        if (r <= 0) continue;
        if (Update.write(buf, r) != (size_t)r) break;
        got += r;
        lastData = millis();
        gProgress = got * 100 / len;
      }
      if (got == (size_t)len && Update.end()) {  // also checks the MD5
        ok = true;
      } else {
        setMessage("Update failed: %s", got == (size_t)len ? Update.errorString() : "download stopped");
        Update.abort();
      }
    }
    http.end();
  }
  finish(ok);
  job = nullptr;
  vTaskDelete(nullptr);
}

}  // namespace

void begin() {}

void loop() {
  if (gState == State::PinShown && (int32_t)(millis() - pinUntilMs) > 0) {
    gState = State::Idle;
    gPin[0] = 0;
  }
}

void showPin() {
  if (gState == State::Uploading || gState == State::Downloading) return;
  snprintf(gPin, sizeof gPin, "%04u", (unsigned)(esp_random() % 10000));
  pinUntilMs = millis() + PIN_VALID_MS;
  gState = State::PinShown;
}

bool pinOk(const String& p) {
  return gState == State::PinShown && gPin[0] && p == gPin && (int32_t)(millis() - pinUntilMs) < 0;
}

String pin() { return gPin; }
State state() { return gState; }
uint8_t progress() { return gProgress; }
String message() { return gMsg; }

bool uploadStart(const String& p, size_t size) {
  if (!pinOk(p)) {
    setMessage("Wrong or expired PIN");
    return false;
  }
  if (!Update.begin(size > 0 ? size : UPDATE_SIZE_UNKNOWN)) {
    setMessage("No room for the update: %s", Update.errorString());
    gState = State::Failed;
    return false;
  }
  gPin[0] = 0;  // one use
  expected = size;
  written = 0;
  gProgress = 0;
  gState = State::Uploading;
  setMessage("Receiving firmware...");
  return true;
}

bool uploadWrite(const uint8_t* data, size_t len) {
  if (gState != State::Uploading) return false;
  if (Update.write(const_cast<uint8_t*>(data), len) != len) {
    setMessage("Write failed: %s", Update.errorString());
    Update.abort();
    gState = State::Failed;
    return false;
  }
  written += len;
  if (expected) gProgress = min<size_t>(99, written * 100 / expected);
  return true;
}

bool uploadFinish() {
  if (gState != State::Uploading) return false;
  // end(true): accept the received length; the image itself (chip type,
  // checksum and SHA-256) is verified before the board switches to it.
  const bool ok = Update.end(true);
  if (!ok) setMessage("Not a valid firmware for this board: %s", Update.errorString());
  finish(ok);
  return ok;
}

Available checkGithub() {
  Available a = {false, "", "", 0};
  if (Storage::settings().otaRepo.isEmpty()) {
    setMessage("Set the GitHub repository first");
    return a;
  }
  if (!Net::connected()) {
    setMessage("No WiFi");
    return a;
  }
  if (!Net::timeValid()) {
    setMessage("Clock not set yet (needed to check GitHub's certificate)");
    return a;
  }
  WiFiClientSecure client;
  client.setCACert(OTA_ROOT_CAS);
  HTTPClient http;
  http.setTimeout(8000);
  if (!http.begin(client, baseUrl() + "ota/manifest.json")) {
    setMessage("Could not reach GitHub");
    return a;
  }
  const int code = http.GET();
  if (code != 200) {
    if (code == 404)
      setMessage("No ota/manifest.json in that repository (is it public?)");
    else
      setMessage("GitHub: HTTP %d", code);
    http.end();
    return a;
  }
  const String j = http.getString();
  http.end();

  a.version = jsonString(j, "version");
  a.notes = jsonString(j, "notes");
  const int board = j.indexOf("\"" BOARD_NAME "\"");  // the quote after the name keeps ESP32 / ESP32-S3 apart
  if (board < 0) {
    setMessage("The manifest has no firmware for " BOARD_NAME);
    return a;
  }
  jobUrl = baseUrl() + jsonString(j, "path", board);
  jobMd5 = jsonString(j, "md5", board);
  jobSize = jsonNumber(j, "size", board);
  a.size = jobSize;
  a.ok = !a.version.isEmpty() && jobMd5.length() == 32 && jobSize > 0;
  if (!a.ok)
    setMessage("The manifest is incomplete");
  else if (isNewer(a.version))
    setMessage("Version %s is available (this is %s)", a.version.c_str(), FIRMWARE_VERSION);
  else
    setMessage("Up to date (%s)", FIRMWARE_VERSION);
  return a;
}

bool installGithub(const String& p) {
  if (job || gState == State::Uploading || gState == State::Downloading) return false;
  if (jobUrl.isEmpty()) {
    setMessage("Check for updates first");
    return false;
  }
  if (!pinOk(p)) {
    setMessage("Wrong or expired PIN");
    return false;
  }
  gPin[0] = 0;
  gProgress = 0;
  gState = State::Downloading;
  setMessage("Downloading from GitHub...");
  if (xTaskCreatePinnedToCore(downloadTask, "ota_dl", 10240, nullptr, 1, &job, 1) != pdPASS) {
    setMessage("Not enough memory to start the download");
    gState = State::Failed;
    return false;
  }
  return true;
}

bool isNewer(const String& version) {
  int a[3], b[3];
  parseVersion(version, a);
  parseVersion(FIRMWARE_VERSION, b);
  for (int i = 0; i < 3; i++)
    if (a[i] != b[i]) return a[i] > b[i];
  return false;
}

}  // namespace Ota
