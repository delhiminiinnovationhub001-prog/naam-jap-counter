#include "voice_id.h"

#include <LittleFS.h>
#include <math.h>
#include <algorithm>
#include <vector>

#include "config.h"
#include "storage.h"

namespace Voice {
namespace {

constexpr int DIM = Audio::FEATURE_DIM;
constexpr int MAX_SAMPLES = 40;      // stored chants per person
constexpr int MIN_SAMPLES = 5;       // needed before a person can be recognised
constexpr int K = 3;                 // nearest neighbours per person
constexpr uint32_t SMOOTH_RESET_MS = 8000;  // a longer pause starts a new series
constexpr float SURE_CONFIDENCE = 0.35f;    // above this, trust the jap alone

struct Sample {
  float f[DIM];
};
struct ModelPoint {
  int8_t person;
  float z[DIM];  // normalised features
};

std::vector<Sample> samples[MAX_PEOPLE];
std::vector<ModelPoint> model;
float mu[DIM], sigma[DIM];
float typicalDist = 1.0f;  // usual distance between chants of the same person
bool modelReady = false;

uint8_t gStrictness = DEFAULT_STRICTNESS;
bool gSmoothing = DEFAULT_SMOOTHING;
float scores[Storage::SLOTS];
uint32_t lastJapMs = 0;
int gLast = -1;
uint8_t gConfidence = 0;

int trainPerson = -1, trainDone = 0, trainTarget = 0;

String samplePath(int p) { return "/voice" + String(p) + ".bin"; }

void saveSamples(int p) {
  const String path = samplePath(p);
  if (samples[p].empty()) {
    LittleFS.remove(path);
    return;
  }
  File f = LittleFS.open(path, "w");
  if (!f) return;
  f.write((const uint8_t*)samples[p].data(), samples[p].size() * sizeof(Sample));
  f.close();
}

void loadSamples(int p) {
  samples[p].clear();
  if (!Storage::fileExists(samplePath(p))) return;
  File f = LittleFS.open(samplePath(p), "r");
  if (!f) return;
  const size_t n = min((size_t)MAX_SAMPLES, f.size() / sizeof(Sample));
  samples[p].resize(n);
  f.read((uint8_t*)samples[p].data(), n * sizeof(Sample));
  f.close();
}

bool inModel(int p) { return Storage::active(p) && (int)samples[p].size() >= MIN_SAMPLES; }

float distance(const float* a, const float* b) {
  float s = 0;
  for (int i = 0; i < DIM; i++) {
    const float d = a[i] - b[i];
    s += d * d;
  }
  return sqrtf(s);
}

// For each person: mean of the K smallest distances from z to their chants.
// `skip` leaves one model point out (used to measure typicalDist).
void personDistances(const float* z, int skip, float* out) {
  float best[MAX_PEOPLE][K];
  int count[MAX_PEOPLE] = {0};
  for (int i = 0; i < (int)model.size(); i++) {
    if (i == skip) continue;
    const int p = model[i].person;
    const float d = distance(z, model[i].z);
    int pos;
    if (count[p] < K)
      pos = count[p]++;
    else if (d < best[p][K - 1])
      pos = K - 1;
    else
      continue;
    best[p][pos] = d;
    for (int j = pos; j > 0 && best[p][j] < best[p][j - 1]; j--) std::swap(best[p][j], best[p][j - 1]);
  }
  for (int p = 0; p < MAX_PEOPLE; p++) {
    if (count[p] == 0) {
      out[p] = INFINITY;
      continue;
    }
    float s = 0;
    for (int j = 0; j < count[p]; j++) s += best[p][j];
    out[p] = s / count[p];
  }
}

void rebuild() {
  model.clear();
  modelReady = false;

  int total = 0;
  for (int p = 0; p < MAX_PEOPLE; p++)
    if (inModel(p)) total += samples[p].size();
  if (total == 0) return;

  for (int d = 0; d < DIM; d++) {
    double sum = 0, sq = 0;
    for (int p = 0; p < MAX_PEOPLE; p++) {
      if (!inModel(p)) continue;
      for (const Sample& s : samples[p]) {
        sum += s.f[d];
        sq += (double)s.f[d] * s.f[d];
      }
    }
    mu[d] = sum / total;
    sigma[d] = sqrtf(fmaxf((float)(sq / total - (double)mu[d] * mu[d]), 1e-6f));
  }

  for (int p = 0; p < MAX_PEOPLE; p++) {
    if (!inModel(p)) continue;
    for (const Sample& s : samples[p]) {
      ModelPoint mp;
      mp.person = p;
      for (int d = 0; d < DIM; d++) mp.z[d] = (s.f[d] - mu[d]) / sigma[d];
      model.push_back(mp);
    }
  }

  std::vector<float> own;
  float d[MAX_PEOPLE];
  for (int i = 0; i < (int)model.size(); i++) {
    personDistances(model[i].z, i, d);
    if (isfinite(d[model[i].person])) own.push_back(d[model[i].person]);
  }
  if (own.empty()) {
    typicalDist = sqrtf((float)DIM);
  } else {
    std::nth_element(own.begin(), own.begin() + own.size() / 2, own.end());
    typicalDist = fmaxf(own[own.size() / 2], 0.1f);
  }
  modelReady = true;
  Serial.printf("[voice] model: %d chants, typical distance %.2f\n", (int)model.size(), typicalDist);
}

int fallbackSlot() {
  // Nobody trained yet: with exactly one named person, it must be them.
  if (Storage::activeCount() == 1)
    for (int p = 0; p < MAX_PEOPLE; p++)
      if (Storage::active(p)) return p;
  return Storage::GUEST;
}

int classify(const float* feature, float& confidence) {
  confidence = 0;
  if (!modelReady) return fallbackSlot();

  float z[DIM];
  for (int i = 0; i < DIM; i++) z[i] = (feature[i] - mu[i]) / sigma[i];
  float d[MAX_PEOPLE];
  personDistances(z, -1, d);

  int best = -1, second = -1;
  for (int p = 0; p < MAX_PEOPLE; p++) {
    if (!inModel(p)) continue;
    if (best < 0 || d[p] < d[best]) {
      second = best;
      best = p;
    } else if (second < 0 || d[p] < d[second]) {
      second = p;
    }
  }
  if (best < 0) return fallbackSlot();

  // Strictness 1..10 -> accept up to 3.2 .. 1.4 x the usual same-person distance.
  const float limit = typicalDist * (3.4f - 0.2f * gStrictness);
  if (d[best] > limit) return Storage::GUEST;

  const float c = second >= 0 ? 1.0f - d[best] / d[second] : 1.0f - d[best] / limit;
  confidence = constrain(c, 0.0f, 1.0f);
  return best;
}

}  // namespace

void begin() {
  for (int p = 0; p < MAX_PEOPLE; p++) loadSamples(p);
  rebuild();
}

void setMatching(uint8_t strictness, bool smoothing) {
  gStrictness = constrain(strictness, 1, 10);
  gSmoothing = smoothing;
}

int identify(const Audio::JapEvent& ev) {
  const uint32_t now = millis();

  if (trainPerson >= 0) {
    const int p = trainPerson;
    if (ev.frames) {
      if (samples[p].size() >= MAX_SAMPLES) samples[p].erase(samples[p].begin());
      Sample s;
      memcpy(s.f, ev.feature, sizeof(s.f));
      samples[p].push_back(s);
      trainDone++;
    }
    if (trainDone >= trainTarget) stopTraining();
    gLast = p;
    gConfidence = 100;
    lastJapMs = now;
    return p;
  }

  float confidence = 0;
  int pick;
  if (ev.frames)
    pick = classify(ev.feature, confidence);
  else
    pick = gLast >= 0 ? gLast : fallbackSlot();  // too short to fingerprint

  if (now - lastJapMs > SMOOTH_RESET_MS) memset(scores, 0, sizeof scores);
  lastJapMs = now;
  for (float& s : scores) s *= 0.55f;
  scores[pick] += 0.5f + confidence;

  int result = pick;
  if (gSmoothing && confidence < SURE_CONFIDENCE)
    result = std::max_element(scores, scores + Storage::SLOTS) - scores;
  if (result != Storage::GUEST && !Storage::active(result)) result = Storage::GUEST;

  gLast = result;
  gConfidence = (uint8_t)(confidence * 100);
  return result;
}

void noteDetection(int slot, float confidence) {
  gLast = slot;
  gConfidence = (uint8_t)(constrain(confidence, 0.0f, 1.0f) * 100);
  lastJapMs = millis();
}

int lastSlot() { return gLast; }
uint8_t lastConfidence() { return gConfidence; }

int sampleCount(int person) {
  return (person >= 0 && person < MAX_PEOPLE) ? samples[person].size() : 0;
}

void clearVoice(int person) {
  if (person < 0 || person >= MAX_PEOPLE) return;
  if (trainPerson == person) trainPerson = -1;
  samples[person].clear();
  saveSamples(person);
  if (gLast == person) gLast = -1;
  rebuild();
}

void startTraining(int person, int chants, bool keepExisting) {
  if (!Storage::active(person)) return;
  if (trainPerson >= 0) stopTraining();
  if (!keepExisting) samples[person].clear();
  trainPerson = person;
  trainDone = 0;
  trainTarget = constrain(chants, MIN_SAMPLES, MAX_SAMPLES);
}

void stopTraining() {
  if (trainPerson < 0) return;
  const int p = trainPerson;
  trainPerson = -1;
  saveSamples(p);
  rebuild();
  Serial.printf("[voice] %s trained with %d chants\n", Storage::name(p).c_str(), (int)samples[p].size());
}

int trainingPerson() { return trainPerson; }
int trainingDone() { return trainDone; }
int trainingTarget() { return trainTarget; }

}  // namespace Voice
