# Naam Jap Counter (ESP32 / ESP32-S3)

This device listens through an INMP441 microphone and counts **only the word you taught it**, for example "Radha". It works even in continuous chanting with no pauses ("radharadharadha…"). Talking, taps, claps, the TV and room noise are ignored. It recognises **up to 5 trained people** and keeps a separate count for each. A 1.8" TFT shows who is chanting, their count, and the date and time in IST.

📄 **[docs/User_Manual.pdf](docs/User_Manual.pdf)** is the full guide for users.<br>
📐 **[docs/Schematic.pdf](docs/Schematic.pdf)** has a schematic for each board and the wiring table.

## Features
- **Word counting, trained on the ESP32 itself:** a person presses *Train voice* and says the word about 20 times with short pauses. The device keeps their 8 most typical examples as patterns. It then searches the live sound for those patterns every 20 ms (streaming subsequence DTW, the SPRING algorithm), so each word is found even with no pause between words. Until a word is taught, nothing is counted.
- **Who said it:** the pattern that matches best belongs to a person, so every jap is credited to them.
- **Noise handling:** a 120 Hz high-pass filter removes rumble and hum, and speech detection uses only the 120 Hz–4 kHz band. A sound must have a pitch to be taken, so taps and clicks are rejected. Matches can only start on speech and are cleared after silence.
- The screen shows the name, that person's count today, their grand total, malas (÷108), the family totals, a match %, and a mic level bar.
- At **12:00 midnight IST**, each person's count for the day is added to their total and saved to history.
- Counts are stored in flash, so they survive power cuts. If a micro-SD card is inserted, each day is also logged to `japlog.csv`.
- **First boot:** the device starts a WiFi hotspot and shows its name and password on the TFT, then sets itself up through a captive portal.
- A web dashboard at `http://japcounter.local` lets you manage people, train voices, correct counts, change settings, and view or download history.

## Hardware
| Part | Notes |
|---|---|
| **One** board: ESP32 DevKit V1 (WROOM-32) **or** ESP32-S3-DevKitC-1 **N16R8** | Same firmware and features on both |
| INMP441 I2S MEMS mic | L/R → GND |
| 1.8" TFT ST7735 128×160 SPI | The micro-SD slot on its back is optional |

### Wiring
| Module pin | ESP32 DevKit V1 | ESP32-S3-DevKitC-1 |
|---|---|---|
| INMP441 VDD / TFT VCC | 3V3 | 3V3 |
| INMP441 GND, L/R / TFT GND | GND | GND |
| INMP441 SCK | GPIO 26 | GPIO 4 |
| INMP441 WS | GPIO 25 | GPIO 5 |
| INMP441 SD | GPIO 33 | GPIO 6 |
| TFT CS | GPIO 5 | GPIO 10 |
| TFT RESET | GPIO 17 | GPIO 8 |
| TFT A0 | GPIO 16 | GPIO 9 |
| TFT SDA + SD_MOSI | GPIO 23 | GPIO 11 |
| TFT SCK + SD_SCK | GPIO 18 | GPIO 12 |
| TFT LED | GPIO 4 | GPIO 7 |
| SD_MISO | GPIO 19 | GPIO 13 |
| SD_CS | GPIO 13 | GPIO 14 |

The on-board **BOOT** button (GPIO 0) works as a control. A short press pauses or resumes counting. Holding it for 5 seconds forgets the WiFi.
Both pin maps are in [include/config.h](include/config.h).

## Build & flash (VS Code + PlatformIO)
1. Install VS Code and the **PlatformIO IDE** extension, then open this folder.
2. Pick your board's build target in the PlatformIO toolbar: `env:esp32dev` (DevKit V1) or `env:esp32-s3-n16r8` (S3).
3. Connect the board by USB. On the S3, use the USB-C port marked **UART**.
4. Click **Build**, then **Upload**. From the command line:
   ```bash
   pio run -e esp32dev -t upload
   ```
   ```bash
   pio run -e esp32-s3-n16r8 -t upload
   ```

| Build target | RAM used | App flash used |
|---|---|---|
| `esp32dev` | 20.1 % | 77.6 % of 1.25 MB |
| `esp32-s3-n16r8` | 19.7 % | 14.9 % of 6.25 MB |

## Android app
📱 **[release/NaamJap-1.1.0.apk](release/NaamJap-1.1.0.apk)** is a signed release build for Android 8.0 or newer. Install it on your phone. It works on the same WiFi as the counter.

- **Finding the counter:** the app discovers counters automatically over mDNS (service `_japcounter._tcp`). You can also connect by IP address. The app remembers the counter and finds it again if its IP changes.
- **Screens:** Live (who is chanting, count, mic level, pause, ±1), People (add, train, rename, clear voice, remove, correct counts), History (per-person days, share as CSV), Settings (every device setting, WiFi, reset), plus the device's own web page inside the app.
- **New counter:** join the counter's setup hotspot, then tap *Open setup page* in the app.

Source code is in [android-app/](android-app/) (Kotlin and Jetpack Compose, minSdk 26, targetSdk 34). To rebuild:
```bash
cd android-app && gradlew assembleRelease
```
Gradle needs JDK 17. The release signing key is `android-app/keystore/release.jks`, and its passwords are in `android-app/keystore.properties`. Neither file is meant for version control. **Back both up.** Every future update of the app must be signed with this same key, or phones will refuse to install it over the old version.

## How voice recognition works
Every jap is turned into a 26-number "voice fingerprint". It is made from the average and spread of 12 MFCCs (measures of the voice's tone) plus the mean and spread of the pitch. Training stores 20–40 fingerprints per person. A new jap is matched to the person whose stored chants are closest, using nearest-neighbour comparison on normalised features. If the closest match is still too far, the jap is counted as Guest. When a match is uncertain, the recent japs decide, because one person usually chants a whole series.

The audio and fingerprint work runs on core 0. Recognition, the screen and the web server run on core 1.

Recognition works well for clearly different voices with one person chanting at a time. It is weaker for similar voices, and voices chanting together can't be separated. See section 13 of the manual.

## Regenerating the PDFs
```bash
python docs/build_docs.py
```
The script needs Microsoft Edge or Google Chrome installed, which it uses to print the HTML to PDF.

## Project layout
```
include/config.h        pins and defaults
src/main.cpp            setup / loop, midnight rollover, button
src/audio_counter.*     I2S mic, jap detection, voice fingerprint (core 0 task)
src/voice_id.*          training + speaker recognition
src/storage.*           per-person counts, history, SD log
src/display.*           TFT screens
src/net.*               WiFi, setup hotspot, captive portal, NTP (IST), mDNS
src/web.*, web_pages.h  dashboard, setup page, JSON API
docs/                   User_Manual.pdf, Schematic.pdf, build_docs.py
android-app/            Android app source (Kotlin + Compose)
release/                NaamJap-1.1.0.apk
```
