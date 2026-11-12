#pragma once
#include <Arduino.h>

#include "audio_counter.h"

// Counts one taught word inside continuous chanting ("radharadharadha...").
//
// Each person teaches the word by saying it ~20 times with pauses; the most
// typical examples are kept as templates (MFCC sequences). While counting,
// every 20 ms frame is matched against all templates at once with streaming
// subsequence DTW (the SPRING algorithm), so a word is found wherever it
// starts and ends, with no pause needed. Overlapping hits from different
// templates are merged into one jap, credited to the best-matching person.
namespace WordSpotter {

constexpr int DIM = Audio::NUM_CEPSTRA;
// Longest template (frames). A trimmed "Radha" is 12-21 frames; 32 (640 ms)
// leaves room for slower words while keeping 2 x 20 templates in RAM.
constexpr int MAX_LEN = 32;
constexpr int MIN_LEN = 8;                       // shorter examples are rejected (160 ms)

struct Detection {
  int8_t person;
  float score;       // normalised DTW distance (lower = better)
  float confidence;  // 0..1
};

// ---- main task
void begin();  // loads saved templates (needs LittleFS mounted)
void setStrictness(uint8_t strictness);
void beginTraining(int person, bool keepExisting);
bool addSample(int person, const Audio::Sequence& seq);  // while training
void finishTraining(int person);  // keeps the best examples, saves, goes live
void cancelTraining();
void clear(int person);
int templateCount(int person);
int fastTemplateCount(int person);

// Learning fast chanting: the person chants continuously; each word found is
// cut out and kept as an extra example (up to 8 "fast" templates per person).
// Needs the word taught with pauses first.
bool beginFastTraining(int person, int examples);
void pollFastTraining();  // main loop: collects captured examples, ends when done
void stopFastTraining();
int fastTrainingPerson();  // -1 when not learning
int fastTrainingDone();
int fastTrainingTarget();
bool ready();  // at least one person has templates
float recentScore();  // closest match during the last second (for tuning)
float threshold();    // scores at or below this count as the word

// Debug: prints all templates on Serial ("T <person> <len> <hex int8...>").
void dumpTemplates();

// ---- audio task (one call per 20 ms frame, x = mean-normalised MFCCs).
// `speech` = someone is audible; matches only start on speech frames, and a
// silence clears all partial matches, so background noise is never counted.
bool feed(const float* x, bool speech, Detection& out);
void resetStream();

}  // namespace WordSpotter
