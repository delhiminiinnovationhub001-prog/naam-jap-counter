#pragma once
#include <Arduino.h>

#include "config.h"

// Persistent per-person counts (NVS flash), daily history (LittleFS) and an
// optional SD-card log. Days are identified by a key YYYYMMDD in IST.
//
// Slots 0..MAX_PEOPLE-1 are people; slot GUEST collects japs that did not
// match any trained voice.
namespace Storage {

constexpr int GUEST = MAX_PEOPLE;
constexpr int SLOTS = MAX_PEOPLE + 1;

struct Settings {
  uint8_t sensitivity;
  uint16_t minVoiceMs;
  uint16_t minGapMs;
  uint8_t brightness;
  uint8_t strictness;
  bool smoothing;
  bool together;        // both people chant together: every word counts for each
  uint8_t trainChants;
  bool wordMode;        // count only the taught word
  uint8_t wordStrictness;
  String wordName;      // shown on the screen, app and web page
  String otaRepo;       // GitHub "owner/name" with ota/manifest.json, "" = off
  String otaBranch;     // usually "main"
};

void begin();
void loop();   // writes pending changes to flash
void flush();  // write now (before restart)

Settings& settings();
void saveSettings();

// People
String name(int slot);  // GUEST -> "Guest"
bool active(int slot);  // a person slot that has a name
int activeCount();
void setName(int slot, const String& name);
void removePerson(int slot);  // clears name and counts

// Counts
uint32_t today(int slot);
uint64_t grand(int slot);  // previous days + today
uint32_t prevDay(int slot);
uint32_t todayAll();
uint64_t grandAll();
uint32_t prevAll();

void addCount(int slot, uint32_t n, bool timeValid);
// Moves up to n of today's counts from one person to another (a word that
// was credited to the wrong voice). Returns how many were moved.
uint32_t moveCount(int from, int to, uint32_t n);
// Words chanted in together mode since it was turned on (for the screen).
uint32_t togetherWords();
void addTogetherWord();
void resetTogetherWords();
void setToday(int slot, uint32_t n);
void resetAll();

// Call with the current IST date; at midnight moves today into the totals.
void handleDate(uint32_t dateKey);

bool sdOk();

// Like LittleFS.exists(), without the error log line for a missing file.
bool fileExists(const String& path);
String historyJson(size_t maxEntries);
// Totals per year and per month (last maxMonths), from the history plus today.
String summaryJson(size_t maxMonths);
String historyCsv();

}  // namespace Storage
