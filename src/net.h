#pragma once
#include <Arduino.h>

// WiFi station / setup access point, captive-portal DNS, mDNS and IST time.
namespace Net {

void begin();  // blocks up to ~15 s while connecting to saved WiFi
void loop();

bool connected();
bool apActive();
bool hasCredentials();
bool timeValid();

String ssid();
String ip();
int rssi();
String deviceId();  // e.g. JapCounter-A1B2 (from the MAC address)
String apSsid();
String apPassword();

void saveCredentials(const String& ssid, const String& pass);
void forgetCredentials();

void scheduleRestart(uint32_t delayMs);
bool restartDue();

}  // namespace Net
