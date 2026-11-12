#include "net.h"

#include <DNSServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <WiFi.h>
#include <time.h>

#include "config.h"
#include "display.h"

namespace Net {
namespace {

constexpr uint32_t CONNECT_TIMEOUT_MS = 15000;
constexpr uint32_t RETRY_INTERVAL_MS = 30000;
const IPAddress AP_IP(192, 168, 4, 1);

Preferences prefs;
DNSServer dns;
String gSsid, gPass, gApSsid, gApPass;
bool gApOn = false, gMdns = false;
uint32_t lastRetryMs = 0, restartAtMs = 0;

void makeApCredentials() {
  uint8_t mac[6];
  WiFi.softAPmacAddress(mac);
  char b[32];
  snprintf(b, sizeof b, "%s-%02X%02X", DEVICE_NAME, mac[4], mac[5]);
  gApSsid = b;
  snprintf(b, sizeof b, "%08u", (unsigned)(esp_random() % 100000000UL));
  gApPass = b;
}

void startAp(bool keepTryingSta) {
  WiFi.mode(keepTryingSta ? WIFI_AP_STA : WIFI_AP);
  makeApCredentials();
  WiFi.softAPConfig(AP_IP, AP_IP, IPAddress(255, 255, 255, 0));
  WiFi.softAP(gApSsid.c_str(), gApPass.c_str());
  dns.setErrorReplyCode(DNSReplyCode::NoError);
  dns.start(53, "*", AP_IP);  // captive portal: every name -> us
  gApOn = true;
  if (keepTryingSta) {
    WiFi.begin(gSsid.c_str(), gPass.c_str());
    lastRetryMs = millis();
  }
  WiFi.scanNetworks(true);  // warm up the list for the setup page
  Serial.printf("[net] setup AP '%s' pass %s\n", gApSsid.c_str(), gApPass.c_str());
}

void stopAp() {
  dns.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  gApOn = false;
  Serial.println("[net] setup AP stopped");
}

}  // namespace

void begin() {
  prefs.begin("wifi", false);
  gSsid = prefs.getString("ssid", "");
  gPass = prefs.getString("pass", "");

  WiFi.persistent(false);
  WiFi.setAutoReconnect(true);
  WiFi.setHostname(MDNS_NAME);

  if (gSsid.isEmpty()) {
    startAp(false);
  } else {
    WiFi.mode(WIFI_STA);
    WiFi.begin(gSsid.c_str(), gPass.c_str());
    Display::showConnecting(gSsid);
    const uint32_t start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < CONNECT_TIMEOUT_MS) delay(200);
    if (WiFi.status() == WL_CONNECTED) {
      Serial.printf("[net] connected, IP %s\n", WiFi.localIP().toString().c_str());
      Display::showMessage("Connected", WiFi.localIP().toString(), 2500);
    } else {
      Serial.println("[net] saved WiFi not reachable, starting setup AP");
      startAp(true);
    }
  }

  configTzTime(TZ_IST, NTP_SERVER_1, NTP_SERVER_2, NTP_SERVER_3);
}

void loop() {
  if (gApOn) {
    dns.processNextRequest();
    if (hasCredentials() && WiFi.softAPgetStationNum() == 0) {
      if (WiFi.status() == WL_CONNECTED) {
        stopAp();
      } else if (millis() - lastRetryMs > RETRY_INTERVAL_MS) {
        WiFi.begin(gSsid.c_str(), gPass.c_str());
        lastRetryMs = millis();
      }
    }
  }

  if (!gMdns && WiFi.status() == WL_CONNECTED) {
    if (MDNS.begin(MDNS_NAME)) {
      MDNS.setInstanceName(deviceId());
      MDNS.addService("http", "tcp", 80);
      // The mobile app browses for this service.
      MDNS.addService("japcounter", "tcp", 80);
      MDNS.addServiceTxt("japcounter", "tcp", "id", deviceId());
      MDNS.addServiceTxt("japcounter", "tcp", "fw", FIRMWARE_VERSION);
      gMdns = true;
    }
  }
}

bool connected() { return WiFi.status() == WL_CONNECTED; }
bool apActive() { return gApOn; }
bool hasCredentials() { return !gSsid.isEmpty(); }
bool timeValid() { return time(nullptr) > 1700000000; }  // after Nov 2023

String ssid() { return gSsid; }
String ip() { return connected() ? WiFi.localIP().toString() : String(""); }
int rssi() { return connected() ? WiFi.RSSI() : 0; }
String deviceId() {
  uint8_t mac[6];
  WiFi.macAddress(mac);
  char b[24];
  snprintf(b, sizeof b, "%s-%02X%02X", DEVICE_NAME, mac[4], mac[5]);
  return b;
}

String apSsid() { return gApSsid; }
String apPassword() { return gApPass; }

void saveCredentials(const String& s, const String& p) {
  prefs.putString("ssid", s);
  prefs.putString("pass", p);
}

void forgetCredentials() {
  prefs.remove("ssid");
  prefs.remove("pass");
}

void scheduleRestart(uint32_t delayMs) { restartAtMs = millis() + delayMs; }
bool restartDue() { return restartAtMs != 0 && (int32_t)(millis() - restartAtMs) >= 0; }

}  // namespace Net
