#pragma once
#include <Arduino.h>

// Firmware updates over WiFi, without a USB cable.
//
//  - Web page: upload a firmware .bin file.
//  - GitHub:   the device reads ota/manifest.json from a public repository
//              (raw.githubusercontent.com, TLS-verified) and installs the
//              firmware listed there for this board if it is newer.
//
// Both need a 4-digit PIN that is shown only on the counter's own screen, so
// only someone standing at the counter can approve an update. The image's
// MD5 is checked before the board switches to it; counts, names, voices and
// WiFi are kept. If an update fails, the old firmware keeps running.
namespace Ota {

enum class State { Idle, PinShown, Uploading, Downloading, Done, Failed };

void begin();
void loop();  // expires the PIN, restarts after a finished update

// PIN shown on the TFT for 3 minutes.
void showPin();
bool pinOk(const String& pin);
String pin();  // for the display

State state();
uint8_t progress();  // 0..100 while writing
String message();    // last result or error, for the web page

// Web upload (WebServer upload callbacks).
bool uploadStart(const String& pin, size_t size);
bool uploadWrite(const uint8_t* data, size_t len);
bool uploadFinish();

// GitHub. The repository is "owner/name"; files are read from `branch`.
struct Available {
  bool ok;
  String version;
  String notes;
  size_t size;
};
Available checkGithub();                     // blocks ~1-3 s
bool installGithub(const String& pin);       // starts a background download
bool isNewer(const String& version);         // than FIRMWARE_VERSION

}  // namespace Ota
