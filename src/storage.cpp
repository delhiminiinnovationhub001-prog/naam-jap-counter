#include "storage.h"

#include <LittleFS.h>
#include <Preferences.h>
#include <SD.h>
#include <SPI.h>
#include <vector>

namespace Storage {
namespace {

// One line per day: YYYY-MM-DD,person1,...,person5,guest
constexpr const char* HISTORY_FILE = "/history.csv";
constexpr const char* SD_LOG_FILE = "/japlog.csv";
constexpr const char* SD_NAMES_FILE = "/names.txt";
constexpr uint32_t SAVE_IDLE_MS = 3000;   // save once chanting pauses...
constexpr uint32_t SAVE_MAX_MS = 30000;   // ...or at least this often

Preferences prefs;
Settings gSettings;
String gNames[MAX_PEOPLE];
uint32_t gToday[SLOTS], gPrev[SLOTS], gUnsynced[SLOTS];
uint64_t gTotal[SLOTS];
uint32_t gDateKey = 0;
bool gDirty = false, gSd = false;
uint32_t lastChangeMs = 0, lastSaveMs = 0;

bool validSlot(int slot) { return slot >= 0 && slot < SLOTS; }

void saveCounts() {
  prefs.putBytes("today", gToday, sizeof gToday);
  prefs.putBytes("total", gTotal, sizeof gTotal);
  prefs.putBytes("prev", gPrev, sizeof gPrev);
  prefs.putUInt("date", gDateKey);
  gDirty = false;
  lastSaveMs = millis();
}

void markDirty() {
  gDirty = true;
  lastChangeMs = millis();
}

// Loads a per-slot array. Data saved with a different number of people (e.g.
// the old 5-person layout) is mapped: people keep their slot while it exists,
// the old Guest and any dropped slots are added to Guest, so no count is lost.
template <typename T>
void loadSlots(const char* key, T* dst) {
  memset(dst, 0, sizeof(T) * SLOTS);
  const size_t n = prefs.getBytesLength(key) / sizeof(T);
  if (n == 0) return;
  std::vector<T> old(n);
  prefs.getBytes(key, old.data(), n * sizeof(T));
  if (n == (size_t)SLOTS) {
    memcpy(dst, old.data(), sizeof(T) * SLOTS);
    return;
  }
  const size_t people = n - 1;  // last slot was Guest
  for (size_t i = 0; i < people; i++) {
    if (i < (size_t)MAX_PEOPLE)
      dst[i] = old[i];
    else
      dst[GUEST] += old[i];
  }
  dst[GUEST] += old[people];
  Serial.printf("[storage] '%s' migrated from %u to %d slots\n", key, (unsigned)n, SLOTS);
}

// Same mapping for a history line's counts ("n,n,n,...").
String normalizeCounts(const String& counts) {
  std::vector<uint32_t> v;
  int start = 0;
  while (start <= (int)counts.length()) {
    int comma = counts.indexOf(',', start);
    if (comma < 0) comma = counts.length();
    v.push_back(strtoul(counts.substring(start, comma).c_str(), nullptr, 10));
    start = comma + 1;
  }
  if (v.size() == (size_t)SLOTS || v.empty()) return counts;
  uint32_t out[SLOTS] = {0};
  const size_t people = v.size() - 1;
  for (size_t i = 0; i < people; i++) {
    if (i < (size_t)MAX_PEOPLE)
      out[i] = v[i];
    else
      out[GUEST] += v[i];
  }
  out[GUEST] += v[people];
  String s;
  for (int i = 0; i < SLOTS; i++) {
    if (i) s += ',';
    s += String(out[i]);
  }
  return s;
}

String formatKey(uint32_t key) {
  char b[12];
  snprintf(b, sizeof b, "%04u-%02u-%02u", (unsigned)(key / 10000), (unsigned)(key / 100 % 100),
           (unsigned)(key % 100));
  return b;
}

void writeSdNames() {
  if (!gSd) return;
  File f = SD.open(SD_NAMES_FILE, FILE_WRITE);
  if (!f) return;
  for (int i = 0; i < MAX_PEOPLE; i++) f.printf("person%d=%s\n", i + 1, gNames[i].c_str());
  f.close();
}

void appendHistory(uint32_t key, const uint32_t* counts) {
  String line = formatKey(key);
  uint64_t sum = 0;
  for (int i = 0; i < SLOTS; i++) {
    line += ',';
    line += String(counts[i]);
    sum += counts[i];
  }
  File f = LittleFS.open(HISTORY_FILE, FILE_APPEND);
  if (f) {
    f.print(line + "\n");
    f.close();
  }
  if (gSd) {
    File s = SD.open(SD_LOG_FILE, FILE_APPEND);
    if (s) {
      s.printf("%s,%llu\n", line.c_str(), (unsigned long long)sum);
      s.close();
    }
  }
}

// History lines are "YYYY-MM-DD,n,n,...". Calls fn(date, counts) for valid ones.
template <typename Fn>
void forEachHistoryLine(Fn fn) {
  File f = LittleFS.open(HISTORY_FILE, "r");
  if (!f) return;
  while (f.available()) {
    String l = f.readStringUntil('\n');
    l.trim();
    if (l.length() < 12 || !isDigit(l[0]) || l[10] != ',') continue;
    fn(l.substring(0, 10), normalizeCounts(l.substring(11)));
  }
  f.close();
}

}  // namespace

void begin() {
  prefs.begin("jap", false);
  loadSlots("today", gToday);
  loadSlots("total", gTotal);
  loadSlots("prev", gPrev);
  memset(gUnsynced, 0, sizeof gUnsynced);
  gDateKey = prefs.getUInt("date", 0);
  for (int i = 0; i < MAX_PEOPLE; i++) {
    const String key = "n" + String(i);
    gNames[i] = prefs.isKey(key.c_str()) ? prefs.getString(key.c_str(), "") : String("");
  }

  gSettings.sensitivity = prefs.getUChar("sens", DEFAULT_SENSITIVITY);
  gSettings.minVoiceMs = prefs.getUShort("minv", DEFAULT_MIN_VOICE_MS);
  gSettings.minGapMs = prefs.getUShort("gap", DEFAULT_MIN_GAP_MS);
  gSettings.brightness = prefs.getUChar("bright", DEFAULT_BRIGHTNESS);
  gSettings.strictness = prefs.getUChar("strict", DEFAULT_STRICTNESS);
  gSettings.smoothing = prefs.getBool("smooth", DEFAULT_SMOOTHING);
  gSettings.trainChants = prefs.getUChar("tchants", DEFAULT_TRAIN_CHANTS);
  gSettings.wordMode = prefs.getBool("wmode", DEFAULT_WORD_MODE);
  gSettings.wordStrictness = prefs.getUChar("wstrict", DEFAULT_WORD_STRICTNESS);
  gSettings.wordName = prefs.isKey("wname") ? prefs.getString("wname", DEFAULT_WORD_NAME) : String(DEFAULT_WORD_NAME);
  gSettings.otaRepo = prefs.isKey("orepo") ? prefs.getString("orepo", "") : String(DEFAULT_OTA_REPO);
  gSettings.otaBranch = prefs.isKey("obranch") ? prefs.getString("obranch", "main") : String("main");

  if (!LittleFS.begin(true)) Serial.println("[storage] LittleFS mount failed");

  gSd = SD.begin(PIN_SD_CS, SPI, 4000000);
  if (gSd && !SD.exists(SD_LOG_FILE)) {
    File s = SD.open(SD_LOG_FILE, FILE_WRITE);
    if (s) {
      s.print("date");
      for (int i = 0; i < MAX_PEOPLE; i++) s.printf(",person%d", i + 1);
      s.print(",guest,total\n");
      s.close();
    }
  }
  writeSdNames();
  Serial.printf("[storage] all today=%u people=%d date=%u sd=%d\n", (unsigned)todayAll(),
                activeCount(), (unsigned)gDateKey, gSd);
}

void loop() {
  if (!gDirty) return;
  const uint32_t now = millis();
  if (now - lastChangeMs > SAVE_IDLE_MS || now - lastSaveMs > SAVE_MAX_MS) saveCounts();
}

void flush() {
  if (gDirty) saveCounts();
}

Settings& settings() { return gSettings; }

void saveSettings() {
  prefs.putUChar("sens", gSettings.sensitivity);
  prefs.putUShort("minv", gSettings.minVoiceMs);
  prefs.putUShort("gap", gSettings.minGapMs);
  prefs.putUChar("bright", gSettings.brightness);
  prefs.putUChar("strict", gSettings.strictness);
  prefs.putBool("smooth", gSettings.smoothing);
  prefs.putUChar("tchants", gSettings.trainChants);
  prefs.putBool("wmode", gSettings.wordMode);
  prefs.putUChar("wstrict", gSettings.wordStrictness);
  prefs.putString("wname", gSettings.wordName);
  prefs.putString("orepo", gSettings.otaRepo);
  prefs.putString("obranch", gSettings.otaBranch);
}

String name(int slot) {
  if (slot == GUEST) return "Guest";
  return (slot >= 0 && slot < MAX_PEOPLE) ? gNames[slot] : String("");
}

bool active(int slot) { return slot >= 0 && slot < MAX_PEOPLE && !gNames[slot].isEmpty(); }

int activeCount() {
  int n = 0;
  for (int i = 0; i < MAX_PEOPLE; i++) n += active(i);
  return n;
}

void setName(int slot, const String& n) {
  if (slot < 0 || slot >= MAX_PEOPLE) return;
  gNames[slot] = n;
  prefs.putString(("n" + String(slot)).c_str(), n);
  writeSdNames();
}

void removePerson(int slot) {
  if (slot < 0 || slot >= MAX_PEOPLE) return;
  setName(slot, "");
  gToday[slot] = gPrev[slot] = gUnsynced[slot] = 0;
  gTotal[slot] = 0;
  saveCounts();
}

uint32_t today(int slot) { return validSlot(slot) ? gToday[slot] : 0; }
uint64_t grand(int slot) { return validSlot(slot) ? gTotal[slot] + gToday[slot] : 0; }
uint32_t prevDay(int slot) { return validSlot(slot) ? gPrev[slot] : 0; }

uint32_t todayAll() {
  uint32_t s = 0;
  for (int i = 0; i < SLOTS; i++) s += gToday[i];
  return s;
}
uint64_t grandAll() {
  uint64_t s = 0;
  for (int i = 0; i < SLOTS; i++) s += gTotal[i] + gToday[i];
  return s;
}
uint32_t prevAll() {
  uint32_t s = 0;
  for (int i = 0; i < SLOTS; i++) s += gPrev[i];
  return s;
}

void addCount(int slot, uint32_t n, bool timeValid) {
  if (!validSlot(slot)) return;
  gToday[slot] += n;
  if (!timeValid) gUnsynced[slot] += n;
  markDirty();
}

void setToday(int slot, uint32_t n) {
  if (!validSlot(slot)) return;
  gToday[slot] = n;
  gUnsynced[slot] = 0;
  markDirty();
}

void resetAll() {
  memset(gToday, 0, sizeof gToday);
  memset(gPrev, 0, sizeof gPrev);
  memset(gUnsynced, 0, sizeof gUnsynced);
  memset(gTotal, 0, sizeof gTotal);
  LittleFS.remove(HISTORY_FILE);
  saveCounts();
}

void handleDate(uint32_t dateKey) {
  if (dateKey == gDateKey) {
    memset(gUnsynced, 0, sizeof gUnsynced);
    return;
  }
  if (gDateKey == 0) {  // very first time the clock is known
    gDateKey = dateKey;
    memset(gUnsynced, 0, sizeof gUnsynced);
    saveCounts();
    return;
  }
  // Day changed (midnight, or the device was off over midnight). Japs made
  // this boot before the clock synced belong to the new day.
  uint32_t closed[SLOTS];
  for (int i = 0; i < SLOTS; i++) {
    const uint32_t carry = min(gUnsynced[i], gToday[i]);
    closed[i] = gToday[i] - carry;
    gTotal[i] += closed[i];
    gPrev[i] = closed[i];
    gToday[i] = carry;
    gUnsynced[i] = 0;
  }
  appendHistory(gDateKey, closed);
  Serial.printf("[storage] day %u closed\n", (unsigned)gDateKey);
  gDateKey = dateKey;
  saveCounts();
}

bool sdOk() { return gSd; }

bool fileExists(const String& path) {
  File root = LittleFS.open("/");
  if (!root) return false;
  const String name = path.startsWith("/") ? path.substring(1) : path;
  bool found = false;
  for (File f = root.openNextFile(); f && !found; f = root.openNextFile()) {
    String n = f.name();
    if (n.startsWith("/")) n = n.substring(1);
    found = n == name;
  }
  return found;
}

String historyJson(size_t maxEntries) {
  std::vector<String> lines;
  forEachHistoryLine([&](const String& date, const String& counts) {
    for (size_t i = 0; i < counts.length(); i++)
      if (!isDigit(counts[i]) && counts[i] != ',') return;
    lines.push_back("{\"d\":\"" + date + "\",\"c\":[" + counts + "]}");
    if (lines.size() > maxEntries) lines.erase(lines.begin());
  });
  String j = "[";
  for (int i = (int)lines.size() - 1; i >= 0; i--) {  // newest first
    if (j.length() > 1) j += ',';
    j += lines[i];
  }
  j += "]";
  return j;
}

String summaryJson(size_t maxMonths) {
  struct Period {
    String key;  // "2026" or "2026-11"
    uint64_t c[SLOTS];
  };
  std::vector<Period> years, months;
  auto add = [](std::vector<Period>& list, const String& key, const uint64_t* counts) {
    for (Period& p : list)
      if (p.key == key) {
        for (int i = 0; i < SLOTS; i++) p.c[i] += counts[i];
        return;
      }
    Period p;
    p.key = key;
    memcpy(p.c, counts, sizeof p.c);
    list.push_back(p);
  };
  auto addDay = [&](const String& date, const uint64_t* counts) {
    add(years, date.substring(0, 4), counts);
    add(months, date.substring(0, 7), counts);
  };

  forEachHistoryLine([&](const String& date, const String& counts) {
    uint64_t c[SLOTS] = {0};
    int start = 0, i = 0;
    while (i < SLOTS && start <= (int)counts.length()) {
      int comma = counts.indexOf(',', start);
      if (comma < 0) comma = counts.length();
      c[i++] = strtoull(counts.substring(start, comma).c_str(), nullptr, 10);
      start = comma + 1;
    }
    addDay(date, c);
  });
  if (gDateKey) {  // today is not in the history yet
    uint64_t c[SLOTS];
    for (int i = 0; i < SLOTS; i++) c[i] = gToday[i];
    addDay(formatKey(gDateKey), c);
  }

  auto toJson = [](const std::vector<Period>& list, size_t maxItems, const char* field) {
    String j = "[";
    // newest first (history is in date order)
    const int first = (int)list.size() - 1, last = max(0, (int)list.size() - (int)maxItems);
    for (int k = first; k >= last; k--) {
      if (k != first) j += ',';
      j += String("{\"") + field + "\":\"" + list[k].key + "\",\"c\":[";
      for (int i = 0; i < SLOTS; i++) {
        if (i) j += ',';
        char b[24];
        snprintf(b, sizeof b, "%llu", (unsigned long long)list[k].c[i]);
        j += b;
      }
      j += "]}";
    }
    return j + "]";
  };
  return "{\"years\":" + toJson(years, 100, "y") + ",\"months\":" + toJson(months, maxMonths, "m") + "}";
}

String historyCsv() {
  String csv = "date";
  for (int i = 0; i < MAX_PEOPLE; i++) {
    String label = gNames[i];
    if (label.isEmpty()) label = "person" + String(i + 1);
    csv += "," + label;
  }
  csv += ",Guest,Total\n";
  forEachHistoryLine([&](const String& date, const String& counts) {
    uint64_t sum = 0;
    int start = 0;
    while (start <= (int)counts.length()) {
      int comma = counts.indexOf(',', start);
      if (comma < 0) comma = counts.length();
      sum += strtoull(counts.substring(start, comma).c_str(), nullptr, 10);
      start = comma + 1;
    }
    char tail[24];
    snprintf(tail, sizeof tail, ",%llu\n", (unsigned long long)sum);
    csv += date + "," + counts + tail;
  });
  return csv;
}

}  // namespace Storage
