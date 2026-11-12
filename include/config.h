#pragma once

// ---------------------------------------------------------------------------
//  Naam Jap Counter - hardware and default configuration
//
//  Two boards are supported; platformio.ini selects one per build target:
//    BOARD_ESP32_S3      ESP32-S3-DevKitC-1 N16R8 (16 MB flash, 8 MB PSRAM)
//    BOARD_ESP32_DEVKIT  ESP32 DevKit V1 (ESP32-WROOM-32, 4 MB flash)
// ---------------------------------------------------------------------------

#if defined(BOARD_ESP32_S3)
// Do not use GPIO 26-37 (flash + octal PSRAM), 19/20 (USB), 43/44 (UART0).
#define BOARD_NAME    "ESP32-S3"
#define PIN_I2S_SCK    4   // INMP441 SCK
#define PIN_I2S_WS     5   // INMP441 WS
#define PIN_I2S_SD     6   // INMP441 SD (data out of mic)
#define PIN_TFT_SCLK  12   // TFT SCK      (also SD_SCK)
#define PIN_TFT_MOSI  11   // TFT SDA      (also SD_MOSI)
#define PIN_TFT_CS    10   // TFT CS
#define PIN_TFT_DC     9   // TFT A0 / DC
#define PIN_TFT_RST    8   // TFT RESET
#define PIN_TFT_BL     7   // TFT LED (backlight, PWM)
#define PIN_SD_MISO   13   // SD_MISO
#define PIN_SD_CS     14   // SD_CS

#elif defined(BOARD_ESP32_DEVKIT)
// Do not use GPIO 6-11 (flash), 34-39 are input only, 1/3 are UART0.
#define BOARD_NAME    "ESP32"
#define PIN_I2S_SCK   26   // INMP441 SCK
#define PIN_I2S_WS    25   // INMP441 WS
#define PIN_I2S_SD    33   // INMP441 SD (data out of mic)
#define PIN_TFT_SCLK  18   // TFT SCK      (also SD_SCK)
#define PIN_TFT_MOSI  23   // TFT SDA      (also SD_MOSI)
#define PIN_TFT_CS     5   // TFT CS
#define PIN_TFT_DC    16   // TFT A0 / DC
#define PIN_TFT_RST   17   // TFT RESET
#define PIN_TFT_BL     4   // TFT LED (backlight, PWM)
#define PIN_SD_MISO   19   // SD_MISO
#define PIN_SD_CS     13   // SD_CS

#else
#error "Build with -DBOARD_ESP32_S3 or -DBOARD_ESP32_DEVKIT (see platformio.ini)"
#endif

// ---- INMP441 I2S microphone ----
// L/R pin of the mic must be tied to GND. The channel tried first; if it is
// silent the firmware switches to the other one by itself at start-up (on
// many ESP32 boards the INMP441 with L/R=GND arrives on the "right" slot).
#define MIC_CHANNEL_FMT I2S_CHANNEL_FMT_ONLY_LEFT

// ---- 1.8" ST7735 TFT (128x160) + micro-SD slot, shared hardware SPI ----
// Panel variant. If colours look wrong or there is a garbage strip at the
// edge, try INITR_GREENTAB or INITR_REDTAB.
#define TFT_TAB INITR_BLACKTAB

// ---- Button (on-board BOOT button) ----
// Short press: pause / resume counting.  Hold 5 s: forget WiFi.
#define PIN_BUTTON     0

#define FIRMWARE_VERSION "2.3.4"

// GitHub repository ("owner/name") that holds ota/manifest.json for updates.
// Can be changed on the web page; "" means GitHub updates are off.
#define DEFAULT_OTA_REPO "delhiminiinnovationhub001-prog/naam-jap-counter"

// ---- Network ----
#define DEVICE_NAME   "JapCounter"   // AP name prefix: JapCounter-XXXX
#define MDNS_NAME     "japcounter"   // http://japcounter.local
#define TZ_IST        "IST-5:30"     // POSIX TZ string for India
#define NTP_SERVER_1  "in.pool.ntp.org"
#define NTP_SERVER_2  "pool.ntp.org"
#define NTP_SERVER_3  "time.google.com"

// ---- Detection defaults (all adjustable from the web page) ----
#define DEFAULT_SENSITIVITY   5     // 1 (least) .. 10 (most sensitive)
#define DEFAULT_MIN_VOICE_MS  150   // voice shorter than this is ignored
#define DEFAULT_MIN_GAP_MS    350   // silence needed to end one jap
#define DEFAULT_BRIGHTNESS    100   // percent

// ---- Voice recognition ----
// A home counter for a couple or a small family: fewer people means more
// stored examples per person and a clearer choice between voices.
#define MAX_PEOPLE            2     // people who can be trained (3 also fits)
#define DEFAULT_TRAIN_CHANTS  30    // chants recorded per training run
#define DEFAULT_STRICTNESS    5     // 1 (accept loosely) .. 10 (strict, more "Guest")
#define DEFAULT_SMOOTHING     1     // use the previous japs when unsure

// ---- Word counting (count only the taught word, also in continuous chanting)
#define DEFAULT_WORD_MODE       1   // on as soon as someone has taught the word
#define DEFAULT_WORD_STRICTNESS 5   // 1 (accept loosely) .. 10 (strict, fewer counts)
#define DEFAULT_WORD_NAME       "Radha"
#define FAST_TRAIN_EXAMPLES     20  // words collected by "Teach fast chanting"

#define JAPS_PER_MALA 108
