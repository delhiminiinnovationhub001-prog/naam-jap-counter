#pragma once
#include <Arduino.h>

// 1.8" ST7735 TFT, landscape 160x128.
namespace Display {

void begin();
void setBrightness(uint8_t percent);
void splash();
void showConnecting(const String& ssid);
void showMessage(const String& title, const String& detail, uint32_t holdMs);
void loop();  // draws the main counter screen or the WiFi setup screen

}  // namespace Display
