#pragma once
#include <Arduino.h>

#include "audio_counter.h"

// Recognises who chanted from the voice fingerprint of each jap.
//
// Training records ~20 chants of one person. Each later jap is compared with
// every stored chant (nearest neighbours on normalised features); if no one
// is close enough it is counted for "Guest". When a jap is ambiguous the
// recent japs decide, because one person usually chants a whole series.
namespace Voice {

void begin();
void setMatching(uint8_t strictness, bool smoothing);

// Returns the slot (person 0..MAX_PEOPLE-1 or Storage::GUEST) for a jap.
// While training, the jap is stored as a sample of the person in training.
int identify(const Audio::JapEvent& ev);

// Records a jap already attributed by the word spotter (for the screen/app).
void noteDetection(int slot, float confidence);

int lastSlot();             // -1 before the first jap
uint8_t lastConfidence();   // 0..100

int sampleCount(int person);
void clearVoice(int person);

void startTraining(int person, int chants, bool keepExisting);
void stopTraining();
int trainingPerson();  // -1 when not training
int trainingDone();
int trainingTarget();

}  // namespace Voice
