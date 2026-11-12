#include "ota.h"

#include <HTTPClient.h>
#include <Preferences.h>
#include <Update.h>
#include <WiFiClientSecure.h>
#include <mbedtls/base64.h>
#include <mbedtls/pk.h>
#include <mbedtls/sha256.h>

#include "config.h"
#include "display.h"
#include "net.h"
#include "ota_pubkey.h"
#include "storage.h"
#include "word_spotter.h"

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
String jobUrl, jobSha256, jobSig, jobVersion;
size_t jobSize = 0;


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

String toHex(const uint8_t* d, size_t n) {
  static const char* hex = "0123456789abcdef";
  String s;
  s.reserve(n * 2);
  for (size_t i = 0; i < n; i++) {
    s += hex[d[i] >> 4];
    s += hex[d[i] & 15];
  }
  return s;
}

// The publisher signs "naamjap|<board>|<version>|<size>|<sha256 hex>" with the
// private key (RSA-2048, SHA-256). Only firmware signed with it is accepted,
// so neither a fake server nor a changed file in the repository can install.
bool signatureOk(const String& sha256Hex) {
  const String msg = String("naamjap|" BOARD_NAME "|") + jobVersion + "|" + String((unsigned)jobSize) + "|" + sha256Hex;
  uint8_t hash[32];
  mbedtls_sha256_ret((const uint8_t*)msg.c_str(), msg.length(), hash, 0);
  uint8_t sig[512];
  size_t sigLen = 0;
  if (mbedtls_base64_decode(sig, sizeof sig, &sigLen, (const uint8_t*)jobSig.c_str(), jobSig.length()) != 0) return false;
  mbedtls_pk_context pk;
  mbedtls_pk_init(&pk);
  bool ok = mbedtls_pk_parse_public_key(&pk, (const uint8_t*)OTA_PUBLIC_KEY, sizeof(OTA_PUBLIC_KEY)) == 0 &&
            mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, hash, sizeof hash, sig, sigLen) == 0;
  mbedtls_pk_free(&pk);
  return ok;
}

// Runs in the main loop (not a separate task: a task's own stack would take
// memory the secure connection needs). The screen is redrawn between chunks.
void runDownload() {
  // The TLS certificate is not checked (verifying GitHub's 4096-bit chain does
  // not fit in this ESP32's memory next to everything else); authenticity comes
  // from the firmware signature instead, checked before the switch-over.
  WordSpotter::releaseTrainingMemory();  // ~15 KB; the board restarts afterwards
  WiFiClientSecure client;
  client.setInsecure();
  HTTPClient http;
  http.setTimeout(STALL_TIMEOUT_MS);
  bool ok = false;
  if (!http.begin(client, jobUrl)) {
    setMessage("Could not open the download");
  } else {
    const int code = http.GET();
    const int len = code == 200 ? http.getSize() : -1;
    if (code < 0) {
      char err[80] = "";
      client.lastError(err, sizeof err);
      setMessage("Download connection failed: %s (free block %u)", err,
                 (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
    } else if (code != 200) {
      setMessage("GitHub download: HTTP %d", code);
    } else if (len <= 0 || (size_t)len != jobSize) {
      setMessage("Download size %d does not match the manifest (%u)", len, (unsigned)jobSize);
    } else if (!Update.begin(len)) {
      setMessage("No room for the update: %s", Update.errorString());
    } else {
      mbedtls_sha256_context sha;
      mbedtls_sha256_init(&sha);
      mbedtls_sha256_starts_ret(&sha, 0);
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
        mbedtls_sha256_update_ret(&sha, buf, r);
        got += r;
        lastData = millis();
        const uint8_t p = got * 100 / len;
        if (p != gProgress) {
          gProgress = p;
          Display::loop();  // progress bar
        }
      }
      uint8_t digest[32];
      mbedtls_sha256_finish_ret(&sha, digest);
      mbedtls_sha256_free(&sha);
      const String sha256Hex = toHex(digest, sizeof digest);
      if (got != (size_t)len) {
        setMessage("Update failed: download stopped");
        Update.abort();
      } else if (sha256Hex != jobSha256) {
        setMessage("Update rejected: the file does not match the manifest");
        Update.abort();
      } else if (!signatureOk(sha256Hex)) {
        setMessage("Update rejected: invalid signature");
        Update.abort();
      } else if (!Update.end()) {
        setMessage("Update failed: %s", Update.errorString());
      } else {
        ok = true;
      }
    }
    http.end();
  }
  finish(ok);
}

}  // namespace

void begin() {
  // Show the result of an update attempt made in update mode before the restart.
  Preferences p;
  p.begin("ota", false);
  if (p.isKey("msg")) {
    snprintf(gMsg, sizeof gMsg, "%s", p.getString("msg", "").c_str());
    p.remove("msg");
  }
  p.end();
}

bool updatePending() {
  Preferences p;
  p.begin("ota", true);
  const bool pending = p.isKey("pending") && p.getBool("pending", false);
  p.end();
  return pending;
}

void runUpdateMode() {
  Preferences p;
  p.begin("ota", false);
  p.putBool("pending", false);  // never loop into update mode
  p.end();
  gState = State::Downloading;
  gProgress = 0;
  setMessage("Update mode: connecting to WiFi...");
  Display::loop();
  Net::begin();
  bool ok = false;
  if (!Net::connected()) {
    setMessage("Update mode: no WiFi");
  } else {
    setMessage("Update mode: free memory %u, largest block %u", (unsigned)ESP.getFreeHeap(),
               (unsigned)ESP.getMaxAllocHeap());
    const Available a = checkGithub();
    if (!a.ok) {
      // checkGithub() set the reason
    } else if (!isNewer(a.version)) {
      setMessage("Already up to date (%s)", FIRMWARE_VERSION);
    } else {
      gState = State::Downloading;
      Display::loop();
      runDownload();
      ok = gState == State::Done;
    }
  }
  if (!ok) gState = State::Failed;
  // Keep the result on screen a moment, then restart into the (new) firmware.
  const uint32_t t = millis();
  while (millis() - t < 5000) {
    Display::loop();
    delay(20);
  }
  p.begin("ota", false);
  p.putString("msg", ok ? String("Updated to the new version") : String(gMsg));
  p.end();
  ESP.restart();
}

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
  // Also on the USB serial port: reading it needs the counter in hand too.
  Serial.printf("[ota] PIN %s (valid 3 min)\n", gPin);
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
  WiFiClientSecure client;
  client.setInsecure();  // see runDownload: the signature proves authenticity
  HTTPClient http;
  http.setTimeout(8000);
  if (!http.begin(client, baseUrl() + "ota/manifest.json")) {
    setMessage("Could not reach GitHub");
    return a;
  }
  const int code = http.GET();
  if (code < 0) {
    char err[96] = "";
    client.lastError(err, sizeof err);
    setMessage("Secure connection failed: %s (largest free block %u)", err,
               (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT));
    http.end();
    return a;
  }
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
  jobSha256 = jsonString(j, "sha256", board);
  jobSig = jsonString(j, "sig", board);
  jobSize = jsonNumber(j, "size", board);
  jobVersion = a.version;
  a.size = jobSize;
  a.ok = !a.version.isEmpty() && jobSha256.length() == 64 && jobSig.length() > 100 && jobSize > 0;
  if (!a.ok)
    setMessage("The manifest is incomplete");
  else if (isNewer(a.version))
    setMessage("Version %s is available (this is %s)", a.version.c_str(), FIRMWARE_VERSION);
  else
    setMessage("Up to date (%s)", FIRMWARE_VERSION);
  return a;
}

bool installGithub(const String& p) {
  if (gState == State::Uploading || gState == State::Downloading) return false;
  if (!pinOk(p)) {
    setMessage("Wrong or expired PIN");
    return false;
  }
  gPin[0] = 0;
  Preferences pr;
  pr.begin("ota", false);
  pr.putBool("pending", true);
  pr.end();
  gState = State::Downloading;
  gProgress = 0;
  setMessage("Restarting into update mode...");
  Net::scheduleRestart(1500);
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
