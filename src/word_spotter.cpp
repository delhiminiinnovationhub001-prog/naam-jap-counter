#include "word_spotter.h"

#include <LittleFS.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#include <math.h>
#include <algorithm>
#include <vector>

#include "config.h"
#include "storage.h"

namespace WordSpotter {
namespace {

constexpr int KEEP_PER_PERSON = 20;     // templates kept per person (2 people x 20)
constexpr int KEEP_FAST = 8;            // of which learnt from fast chanting
constexpr int MAX_POOL = 40;            // training examples considered
constexpr float QUANT = 4.0f;           // template values stored as int8 * 1/4
constexpr int FINALIZE_DELAY = 8;       // frames to wait for a better hit (160 ms)
constexpr int SILENCE_RESET = 8;        // 160 ms of silence ends every partial match
constexpr int TRIM_DB = 15;             // template = frames within 15 dB of its peak
constexpr float FAST_LOOSEN = 1.25f;    // accept a bit more while learning fast chanting
constexpr int RING = 128;               // recent frames kept for cutting out examples (2.5 s)
constexpr uint8_t FAST_FLAG = 0x10;     // Template.person bit: learnt from fast chanting
constexpr int OUT_QUEUE = 4;
// Nobody chants "Radha" faster than ~3 per second, so two words closer than
// 300 ms are one word matched twice (a little slack for uneven chanting).
constexpr uint32_t MIN_SPACING = 15;    // frames between word centres (300 ms)
constexpr const char* FORMAT_MARK = "/wordv2";  // templates trimmed by level
constexpr const char* COMPACT_MARK = "/wordv3"; // templates stored at MAX_LEN 32
constexpr int OLD_MAX_LEN = 60;                 // template size before v3
constexpr uint32_t NO_START = 0xFFFFFFFF;

struct Template {
  int8_t person;  // low bits: person, FAST_FLAG: learnt from fast chanting
  uint8_t len;
  int8_t f[MAX_LEN][DIM];
};

inline int personOf(const Template& t) { return t.person & 0x0F; }
inline bool isFast(const Template& t) { return t.person & FAST_FLAG; }

// Streaming DTW state of one template (SPRING).
struct Track {
  float d[MAX_LEN + 1];
  uint32_t s[MAX_LEN + 1];
  float bestRaw;  // best candidate so far (unnormalised), INFINITY if none
  float bestNorm;
  uint32_t ts, te;
};

struct Hit {
  bool valid;
  int8_t person;  // best-matching person
  float score;    // their score
  uint32_t ts, te;
  float perPerson[MAX_PEOPLE];  // best score of each person for this word
};

Hit makeHit(int person, float score, uint32_t ts, uint32_t te) {
  Hit h = {true, (int8_t)person, score, ts, te, {}};
  for (int p = 0; p < MAX_PEOPLE; p++) h.perPerson[p] = INFINITY;
  h.perPerson[person] = score;
  return h;
}

SemaphoreHandle_t lock = nullptr;
std::vector<Template> templates;  // guarded by lock
std::vector<Track> tracks;        // guarded by lock
std::vector<Template> pool;       // training examples (main task only)
int poolPerson = -1;
float gThreshold = 1e9f;
float baseDistance = 0;  // median distance between examples of the same person
uint8_t gStrictness = DEFAULT_WORD_STRICTNESS;
volatile float gRecent = INFINITY;

// Fast-chanting learning
volatile int8_t fastPerson = -1;
QueueHandle_t captureQueue = nullptr;  // Template, audio task -> main task
int fastTarget = 0, fastDone = 0;
uint32_t fastStartMs = 0;
constexpr uint32_t FAST_TIMEOUT_MS = 60000;

// Audio-task state
uint32_t frameNo = 0;
int silenceRun = 0;
Hit pending = {};
uint32_t lastTs = 0, lastTe = 0;  // span of the last counted word
bool haveLast = false;
float recentMin = INFINITY;
int recentFrames = 0;
float ring[RING][DIM];
float personRing[RING][MAX_PEOPLE];  // each person's closest score per frame (any threshold)
float framePerson[MAX_PEOPLE];       // being filled for the current frame
Detection outQueue[OUT_QUEUE];
int outHead = 0, outCount = 0;

String path(int p) { return "/word" + String(p) + ".bin"; }

inline float frameDist(const float* x, const int8_t* t) {
  float s = 0;
  for (int i = 0; i < DIM; i++) {
    const float d = x[i] - t[i] * (1.0f / QUANT);
    s += d * d;
  }
  return sqrtf(s);
}

inline float frameDistQ(const int8_t* a, const int8_t* b) {
  int32_t s = 0;
  for (int i = 0; i < DIM; i++) {
    const int32_t d = a[i] - b[i];
    s += d * d;
  }
  return sqrtf((float)s) * (1.0f / QUANT);
}

// Whole-sequence DTW, normalised by the path length bound (n + m).
float dtw(const Template& a, const Template& b) {
  static float prev[MAX_LEN + 1], cur[MAX_LEN + 1];
  for (int j = 0; j <= b.len; j++) prev[j] = INFINITY;
  prev[0] = 0;
  for (int i = 1; i <= a.len; i++) {
    cur[0] = INFINITY;
    for (int j = 1; j <= b.len; j++) {
      const float c = frameDistQ(a.f[i - 1], b.f[j - 1]);
      cur[j] = c + fminf(prev[j - 1], fminf(prev[j], cur[j - 1]));
    }
    memcpy(prev, cur, sizeof(float) * (b.len + 1));
  }
  return prev[b.len] / (a.len + b.len);
}

// Memory for choosing and replacing templates is fixed (no heap): the heap
// gets fragmented over time and a large allocation would fail and crash.
constexpr int MAX_CANDIDATES = MAX_POOL + KEEP_PER_PERSON;
const Template* candidates[MAX_CANDIDATES];
Template keepBuf[KEEP_PER_PERSON];

// Copies the k most typical of the n candidates (smallest total distance to
// the others) to keepBuf[at...]. Returns how many were copied.
int pickMedoids(int n, int k, int at) {
  static float total[MAX_CANDIDATES];
  static int order[MAX_CANDIDATES];
  for (int i = 0; i < n; i++) {
    total[i] = 0;
    order[i] = i;
  }
  for (int i = 0; i < n; i++)
    for (int j = i + 1; j < n; j++) {
      const float d = dtw(*candidates[i], *candidates[j]);
      total[i] += d;
      total[j] += d;
    }
  std::sort(order, order + n, [](int a, int b) { return total[a] < total[b]; });
  const int count = min(n, min(k, KEEP_PER_PERSON - at));
  for (int i = 0; i < count; i++) keepBuf[at + i] = *candidates[order[i]];
  return count;
}

void resetTrack(Track& t) {
  for (int i = 0; i <= MAX_LEN; i++) {
    t.d[i] = INFINITY;
    t.s[i] = NO_START;
  }
  t.bestRaw = INFINITY;
  t.bestNorm = INFINITY;
}

void applyStrictness() {
  // Strictness 1..10 -> accept up to 1.82 .. 1.1 x the usual same-word distance.
  const float loosen = fastPerson >= 0 ? FAST_LOOSEN : 1.0f;
  gThreshold = baseDistance > 0 ? baseDistance * (1.9f - 0.08f * gStrictness) * loosen : 1e9f;
}

// Recomputes the acceptance threshold and the streaming state. Call under lock.
void rebuildLocked() {
  std::vector<float> intra;
  for (size_t i = 0; i < templates.size(); i++)
    for (size_t j = i + 1; j < templates.size(); j++)
      // Like with like: slow and fast examples of a word differ by design, and
      // mixing them would loosen the threshold until syllables match.
      if (personOf(templates[i]) == personOf(templates[j]) && isFast(templates[i]) == isFast(templates[j]))
        intra.push_back(dtw(templates[i], templates[j]));
  if (!intra.empty()) {
    std::nth_element(intra.begin(), intra.begin() + intra.size() / 2, intra.end());
    baseDistance = intra[intra.size() / 2];
  } else {
    baseDistance = templates.empty() ? 0 : 2.0f;  // one example only: a cautious guess
  }
  applyStrictness();
  tracks.assign(templates.size(), Track());
  for (Track& t : tracks) resetTrack(t);
  pending.valid = false;
}

void saveFile(int person) {
  int n = 0;
  for (const Template& t : templates) n += personOf(t) == person;
  if (n == 0) {
    LittleFS.remove(path(person));
    return;
  }
  File f = LittleFS.open(path(person), "w");
  if (!f) return;
  for (const Template& t : templates)
    if (personOf(t) == person) f.write((const uint8_t*)&t, sizeof t);
  f.close();
}

// Replaces a person's templates with keepBuf[0..count) (under lock), and saves.
// templates has its full capacity reserved at start-up, so nothing reallocates.
void setPersonTemplates(int person, int count) {
  xSemaphoreTake(lock, portMAX_DELAY);
  templates.erase(std::remove_if(templates.begin(), templates.end(),
                                 [&](const Template& t) { return personOf(t) == person; }),
                  templates.end());
  for (int i = 0; i < count; i++) templates.push_back(keepBuf[i]);
  rebuildLocked();
  xSemaphoreGive(lock);
  saveFile(person);
}

// SPRING step for one template. Returns true and fills `hit` when a match
// has become final (no later frame can improve it).
bool springStep(Track& tr, const Template& tp, const float* x, bool speech, uint32_t t, Hit& hit) {
  const int m = tp.len;
  // A match may only start on a frame where someone is speaking.
  float prevDiag = speech ? 0 : INFINITY;
  uint32_t prevDiagS = t;
  tr.d[0] = prevDiag;
  tr.s[0] = t;
  for (int i = 1; i <= m; i++) {
    const float up = tr.d[i];  // (t-1, i)
    const uint32_t upS = tr.s[i];
    const float left = tr.d[i - 1];  // (t, i-1), already updated
    const uint32_t leftS = tr.s[i - 1];
    float best = prevDiag;
    uint32_t bestS = prevDiagS;
    if (left < best) {
      best = left;
      bestS = leftS;
    }
    if (up < best) {
      best = up;
      bestS = upS;
    }
    prevDiag = up;
    prevDiagS = upS;
    tr.d[i] = frameDist(x, tp.f[i - 1]) + best;
    tr.s[i] = bestS;
  }

  bool reported = false;
  if (tr.bestRaw < INFINITY) {
    bool final = true;
    for (int i = 1; i <= m && final; i++)
      if (tr.d[i] < tr.bestRaw && tr.s[i] <= tr.te) final = false;
    if (final) {
      hit = makeHit(personOf(tp), tr.bestNorm, tr.ts, tr.te);
      reported = true;
      tr.bestRaw = INFINITY;
      tr.bestNorm = INFINITY;
      for (int i = 1; i <= m; i++)
        if (tr.s[i] <= hit.te) tr.d[i] = INFINITY;
    }
  }

  // Matched length in frames: at least 70% of the template (shorter would let a
  // single syllable count as the word), at most twice as long.
  const uint32_t n = t - tr.s[m] + 1;
  if (tr.s[m] != NO_START && n * 10 >= (uint32_t)(m * 7) && n <= (uint32_t)(m * 2)) {
    const float norm = tr.d[m] / (m + n);
    if (norm < recentMin) recentMin = norm;
    const int who = personOf(tp);
    if (norm < framePerson[who]) framePerson[who] = norm;
    if (norm <= gThreshold && tr.d[m] < tr.bestRaw) {
      tr.bestRaw = tr.d[m];
      tr.bestNorm = norm;
      tr.ts = tr.s[m];
      tr.te = t;
    }
  }
  return reported;
}

bool overlapsSpan(uint32_t ats, uint32_t ate, uint32_t bts, uint32_t bte) {
  const int32_t ov = (int32_t)min(ate, bte) - (int32_t)max(ats, bts);
  const int32_t shorter = (int32_t)min(ate - ats, bte - bts) + 1;
  return ov * 2 > shorter;
}

bool overlaps(const Hit& a, const Hit& b) { return overlapsSpan(a.ts, a.te, b.ts, b.te); }

// Who said the word: the person whose own templates matched best. Confidence
// is how clearly they beat the other person (a 20% better score = sure).
// With only one trained person it is how comfortably the word was matched.
Detection toDetection(const Hit& h) {
  Detection d;
  d.person = h.person;
  d.score = h.score;
  float second = INFINITY;
  for (int p = 0; p < MAX_PEOPLE; p++)
    if (p != h.person) second = fminf(second, h.perPerson[p]);
  int trainedOthers = 0;
  for (int p = 0; p < MAX_PEOPLE; p++)
    if (p != h.person && templateCount(p) > 0) trainedOthers++;
  if (isfinite(second) && second > 0)
    d.confidence = constrain((second - h.score) / second * 5.0f, 0.0f, 1.0f);
  else if (trainedOthers > 0)
    d.confidence = 1.0f;  // nobody else's templates came close
  else
    d.confidence = constrain(1.0f - h.score / (gThreshold * 1.25f), 0.0f, 1.0f);
  return d;
}

// While learning fast chanting: cut the word out of the recent frames and
// hand it to the main task as a new example.
void captureExample(const Hit& h) {
  const int8_t who = fastPerson;
  if (who < 0 || !captureQueue) return;
  const int len = h.te - h.ts + 1;
  if (len < MIN_LEN || len > MAX_LEN || frameNo - h.ts >= (uint32_t)RING) return;
  float mean[DIM] = {0};
  for (uint32_t t = h.ts; t <= h.te; t++)
    for (int i = 0; i < DIM; i++) mean[i] += ring[t % RING][i];
  for (int i = 0; i < DIM; i++) mean[i] /= len;
  static Template tp;  // audio task only
  tp.person = who | FAST_FLAG;
  tp.len = len;
  for (int f = 0; f < len; f++)
    for (int i = 0; i < DIM; i++)
      tp.f[f][i] = (int8_t)constrain(lroundf((ring[(h.ts + f) % RING][i] - mean[i]) * QUANT), -127L, 127L);
  xQueueSend(captureQueue, &tp, 0);
}

// A word is finished: count it (unless it is the word just counted).
// Each person's closest score for the word that ended at h.te, even when it
// did not pass the threshold, so the two voices are really compared.
void fillPerPerson(Hit& h) {
  const uint32_t from = h.ts + (h.te - h.ts) / 2, to = h.te + FINALIZE_DELAY / 2;
  for (uint32_t t = from; t <= to && t < frameNo; t++) {  // frames already stored
    if (frameNo - t >= (uint32_t)RING) continue;
    for (int p = 0; p < MAX_PEOPLE; p++) h.perPerson[p] = fminf(h.perPerson[p], personRing[t % RING][p]);
  }
}

void finishWord(Hit h) {
  fillPerPerson(h);
  // The best person may change once everyone's score is known.
  for (int p = 0; p < MAX_PEOPLE; p++)
    if (h.perPerson[p] < h.score) {
      h.score = h.perPerson[p];
      h.person = p;
    }
  if (haveLast && overlapsSpan(h.ts, h.te, lastTs, lastTe)) return;  // late duplicate
  // At most ~3 words per second.
  if (haveLast && (h.ts + h.te) - (lastTs + lastTe) < 2 * MIN_SPACING) return;
  lastTs = h.ts;
  lastTe = h.te;
  haveLast = true;
  captureExample(h);
  if (outCount < OUT_QUEUE) {
    outQueue[(outHead + outCount) % OUT_QUEUE] = toDetection(h);
    outCount++;
  }
}

// Adds a final hit to the pending word, or finishes the pending word when the
// hit belongs to a new one.
void mergeHit(const Hit& h) {
  if (haveLast && overlapsSpan(h.ts, h.te, lastTs, lastTe)) return;  // already counted
  if (pending.valid && overlaps(pending, h)) {
    if (h.score < pending.score) {
      pending.person = h.person;
      pending.score = h.score;
    }
    for (int p = 0; p < MAX_PEOPLE; p++) pending.perPerson[p] = fminf(pending.perPerson[p], h.perPerson[p]);
    pending.ts = min(pending.ts, h.ts);
    pending.te = max(pending.te, h.te);
    return;
  }
  if (pending.valid) finishWord(pending);  // a new word started: the previous one is done
  pending = h;
}

}  // namespace

void begin() {
  lock = xSemaphoreCreateMutex();
  captureQueue = xQueueCreate(6, sizeof(Template));
  // Reserve all template memory now, while the heap is still in one piece.
  templates.reserve(MAX_PEOPLE * KEEP_PER_PERSON);
  tracks.reserve(MAX_PEOPLE * KEEP_PER_PERSON);
  pool.reserve(MAX_POOL);
  templates.clear();
  if (!Storage::fileExists(FORMAT_MARK)) {
    // Templates from before level trimming include silence: drop them (retrain).
    for (int p = 0; p < MAX_PEOPLE; p++)
      if (Storage::fileExists(path(p))) LittleFS.remove(path(p));
    File f = LittleFS.open(FORMAT_MARK, "w");
    if (f) f.close();
    Serial.println("[word] old templates removed; please train the word again");
  }
  const bool compact = Storage::fileExists(COMPACT_MARK);
  for (int p = 0; p < MAX_PEOPLE; p++) {
    if (!Storage::fileExists(path(p))) continue;
    File f = LittleFS.open(path(p), "r");
    if (!f) continue;
    const size_t n = f.size() / (compact ? sizeof(Template) : 2 + OLD_MAX_LEN * DIM);
    for (size_t i = 0; i < n && i < KEEP_PER_PERSON; i++) {
      Template t;
      if (compact) {
        if (f.read((uint8_t*)&t, sizeof t) != sizeof t) break;
      } else {  // older, larger record: keep the frames that fit
        struct {
          int8_t person;
          uint8_t len;
          int8_t f[OLD_MAX_LEN][DIM];
        } old;
        if (f.read((uint8_t*)&old, sizeof old) != sizeof old) break;
        t.person = old.person;
        t.len = old.len;
        if (t.len > MAX_LEN) continue;
        memcpy(t.f, old.f, sizeof(t.f[0]) * t.len);
      }
      t.person = p | (t.person & FAST_FLAG);
      if (t.len >= MIN_LEN && t.len <= MAX_LEN) templates.push_back(t);
    }
    f.close();
  }
  if (!compact) {
    for (int p = 0; p < MAX_PEOPLE; p++) saveFile(p);
    File m = LittleFS.open(COMPACT_MARK, "w");
    if (m) m.close();
    Serial.println("[word] templates converted to the compact format");
  }
  rebuildLocked();
  Serial.printf("[word] %d templates, threshold %.2f\n", (int)templates.size(), gThreshold);
}

void setStrictness(uint8_t strictness) {
  gStrictness = constrain(strictness, 1, 10);
  xSemaphoreTake(lock, portMAX_DELAY);
  applyStrictness();
  xSemaphoreGive(lock);
}

void beginTraining(int person, bool keepExisting) {
  pool.clear();
  poolPerson = person;
  if (keepExisting)  // "add chants": the new examples compete with the old slow ones
    for (const Template& t : templates)
      if (personOf(t) == person && !isFast(t)) pool.push_back(t);
}

bool addSample(int person, const Audio::Sequence& seq) {
  if (person < 0 || person >= MAX_PEOPLE || seq.len < MIN_LEN) return false;
  if (poolPerson != person) beginTraining(person, false);
  if ((int)pool.size() >= MAX_POOL) return false;
  // Keep only the spoken part: frames within TRIM_DB of the loudest one. The
  // quiet tail after a word would otherwise make the template match silence.
  int peak = -127;
  for (int f = 0; f < seq.len; f++) peak = max(peak, (int)seq.db[f]);
  int first = 0, last = seq.len - 1;
  // Slow speakers: if the word is still too long, trim closer to its loudest part.
  for (int trim = TRIM_DB; trim >= 9; trim -= 3) {
    first = 0;
    last = seq.len - 1;
    while (first < last && seq.db[first] < peak - trim) first++;
    while (last > first && seq.db[last] < peak - trim) last--;
    if (last - first + 1 <= MAX_LEN) break;
  }
  const int span = last - first + 1;
  if (span < MIN_LEN) return false;
  // Still too long: shrink it in time (DTW stretches it back when matching).
  const int len = min(span, MAX_LEN);

  // Remove the example's own average (cepstral mean normalisation), so the
  // template does not depend on microphone distance or room.
  float mean[DIM] = {0};
  for (int f = first; f <= last; f++)
    for (int i = 0; i < DIM; i++) mean[i] += seq.f[f][i];
  for (int i = 0; i < DIM; i++) mean[i] /= span;
  Template t;
  t.person = person;
  t.len = len;
  for (int f = 0; f < len; f++) {
    const float pos = len > 1 ? (float)f * (span - 1) / (len - 1) : 0;  // linear resample
    const int a = first + (int)pos, b = min(a + 1, last);
    const float w = pos - (int)pos;
    for (int i = 0; i < DIM; i++) {
      const float v = seq.f[a][i] * (1 - w) + seq.f[b][i] * w - mean[i];
      t.f[f][i] = (int8_t)constrain(lroundf(v * QUANT), -127L, 127L);
    }
  }
  pool.push_back(t);
  return true;
}

void finishTraining(int person) {
  if (person != poolPerson || pool.empty()) {
    cancelTraining();
    return;
  }
  // New slow examples replace the old slow ones; fast ones are kept.
  int kept = 0;
  for (const Template& t : templates)
    if (personOf(t) == person && isFast(t) && kept < KEEP_FAST) keepBuf[kept++] = t;
  const int fastKept = kept;
  int n = 0;
  for (const Template& t : pool) candidates[n++] = &t;
  kept += pickMedoids(n, KEEP_PER_PERSON - kept, kept);
  setPersonTemplates(person, kept);
  Serial.printf("[word] person %d: kept %d of %d examples (+%d fast), threshold %.2f\n", person,
                kept - fastKept, n, fastKept, gThreshold);
  cancelTraining();
}

void releaseTrainingMemory() {
  pool.clear();
  pool.shrink_to_fit();
  poolPerson = -1;
}

void cancelTraining() {
  pool.clear();  // keeps its reserved capacity
  poolPerson = -1;
}

bool beginFastTraining(int person, int examples) {
  if (person < 0 || person >= MAX_PEOPLE || templateCount(person) == 0) return false;
  Template drop;
  while (xQueueReceive(captureQueue, &drop, 0) == pdTRUE) {
  }
  pool.clear();
  poolPerson = person;
  fastTarget = constrain(examples, 5, MAX_POOL);
  fastDone = 0;
  fastStartMs = millis();
  xSemaphoreTake(lock, portMAX_DELAY);
  fastPerson = person;
  applyStrictness();
  xSemaphoreGive(lock);
  return true;
}

void pollFastTraining() {
  const int who = fastPerson;
  if (who < 0) return;
  static Template tp;
  while (xQueueReceive(captureQueue, &tp, 0) == pdTRUE)
    if ((int)pool.size() < MAX_POOL) {
      pool.push_back(tp);
      fastDone++;
    }
  if (fastDone >= fastTarget || millis() - fastStartMs > FAST_TIMEOUT_MS) stopFastTraining();
}

void stopFastTraining() {
  const int who = fastPerson;
  if (who < 0) return;
  xSemaphoreTake(lock, portMAX_DELAY);
  fastPerson = -1;
  applyStrictness();
  xSemaphoreGive(lock);
  if (pool.empty() || poolPerson != who) {
    cancelTraining();
    return;
  }
  // Fast examples: old + new compete for KEEP_FAST places; slow ones fill the rest.
  int n = 0;
  for (const Template& t : templates)
    if (personOf(t) == who && isFast(t) && n < MAX_CANDIDATES) candidates[n++] = &t;
  for (const Template& t : pool)
    if (n < MAX_CANDIDATES) candidates[n++] = &t;
  int kept = pickMedoids(n, KEEP_FAST, 0);
  n = 0;
  for (const Template& t : templates)
    if (personOf(t) == who && !isFast(t)) candidates[n++] = &t;
  kept += pickMedoids(n, KEEP_PER_PERSON - kept, kept);
  setPersonTemplates(who, kept);
  Serial.printf("[word] person %d: learnt %d fast examples, now %d templates, threshold %.2f\n", who,
                (int)pool.size(), kept, gThreshold);
  cancelTraining();
}

int fastTrainingPerson() { return fastPerson; }
int fastTrainingDone() { return fastDone; }
int fastTrainingTarget() { return fastTarget; }

void clear(int person) {
  if (fastPerson == person) {
    xSemaphoreTake(lock, portMAX_DELAY);
    fastPerson = -1;
    applyStrictness();
    xSemaphoreGive(lock);
  }
  setPersonTemplates(person, 0);
  if (poolPerson == person) cancelTraining();
}

int templateCount(int person) {
  int n = 0;
  for (const Template& t : templates) n += personOf(t) == person;
  return n;
}

int fastTemplateCount(int person) {
  int n = 0;
  for (const Template& t : templates) n += personOf(t) == person && isFast(t);
  return n;
}

void dumpTemplates() {
  xSemaphoreTake(lock, portMAX_DELAY);
  Serial.printf("TH %.3f %.3f %u\n", gThreshold, baseDistance, gStrictness);
  for (const Template& t : templates) {
    Serial.printf("T %d %d ", t.person, t.len);
    for (int f = 0; f < t.len; f++)
      for (int i = 0; i < DIM; i++) Serial.printf("%02X", (uint8_t)t.f[f][i]);
    Serial.println();
  }
  Serial.println("TEND");
  xSemaphoreGive(lock);
}

bool ready() { return !templates.empty(); }
float recentScore() { return gRecent; }
float threshold() { return gThreshold; }

bool feed(const float* x, bool speech, Detection& out) {
  if (xSemaphoreTake(lock, 0) != pdTRUE) return false;  // being rebuilt: skip this frame
  const uint32_t t = ++frameNo;
  memcpy(ring[t % RING], x, sizeof(float) * DIM);
  for (int p = 0; p < MAX_PEOPLE; p++) framePerson[p] = INFINITY;
  silenceRun = speech ? 0 : silenceRun + 1;

  if (silenceRun < SILENCE_RESET) {
    for (size_t k = 0; k < templates.size(); k++) {
      Hit h;
      if (springStep(tracks[k], templates[k], x, speech, t, h)) mergeHit(h);
    }
  } else if (silenceRun == SILENCE_RESET) {
    // A pause: no word continues across it. Keep the best candidates, drop the rest.
    for (size_t k = 0; k < tracks.size(); k++) {
      Track& tr = tracks[k];
      if (tr.bestRaw < INFINITY) mergeHit(makeHit(personOf(templates[k]), tr.bestNorm, tr.ts, tr.te));
      resetTrack(tr);
    }
  }
  for (int p = 0; p < MAX_PEOPLE; p++) personRing[t % RING][p] = framePerson[p];
  if (pending.valid && t > pending.te + FINALIZE_DELAY) {
    finishWord(pending);
    pending.valid = false;
  }
  if (++recentFrames >= 50) {
    gRecent = recentMin;
    recentMin = INFINITY;
    recentFrames = 0;
  }
  bool emitted = false;
  if (outCount > 0) {
    out = outQueue[outHead];
    outHead = (outHead + 1) % OUT_QUEUE;
    outCount--;
    emitted = true;
  }
  xSemaphoreGive(lock);
  return emitted;
}

void resetStream() {
  xSemaphoreTake(lock, portMAX_DELAY);
  for (Track& t : tracks) resetTrack(t);
  pending.valid = false;
  haveLast = false;
  silenceRun = SILENCE_RESET;
  xSemaphoreGive(lock);
}

}  // namespace WordSpotter
