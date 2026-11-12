#pragma once
#include <Arduino.h>

// Firmware updates over WiFi, without a USB cable.
//
//  - Web page: upload a firmware .bin file.
//  - GitHub:   the device reads ota/manifest.json from a public repository
//              (raw.githubusercontent.com) and installs the firmware listed
//              there for this board if it is newer and its RSA signature
//              (made with the publisher's private key) is valid.
//
// Both need a 4-digit PIN that is shown only on the counter's own screen, so
// only someone standing at the counter can approve an update. The image's
// integrity is checked before the board switches to it; counts, names, voices and
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
// With a valid PIN: restarts into update mode. A secure connection needs more
// contiguous memory than is left while counting, so the download runs right
// after a restart, before audio, voices and the web server are loaded.
bool installGithub(const String& pin);
bool isNewer(const String& version);         // than FIRMWARE_VERSION

bool updatePending();  // at boot: Install was pressed before the restart
void runUpdateMode();  // downloads, verifies, installs, restarts (never returns)

}  // namespace Ota
