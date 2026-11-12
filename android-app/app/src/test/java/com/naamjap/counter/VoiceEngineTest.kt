package com.naamjap.counter

import com.naamjap.counter.voice.Detection
import com.naamjap.counter.voice.Dsp
import com.naamjap.counter.voice.FrameProcessor
import com.naamjap.counter.voice.ListenListener
import com.naamjap.counter.voice.ListenMode
import com.naamjap.counter.voice.Template
import com.naamjap.counter.voice.WordModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The phone's word counting (ported from the counter) on made-up speech:
 * a voiced, two-syllable "Ra-dha" with formants and a falling pitch, varied
 * in length, pitch and loudness every time, over background noise.
 */
class VoiceEngineTest {

    /** One sound segment: length, pitch from/to, two formants, loudness; noise = unvoiced. */
    private data class Seg(val ms: Int, val f0a: Float, val f0b: Float, val f1: Float, val f2: Float, val amp: Float, val noise: Boolean = false)

    private class Voice(val pitch: Float, val formant: Float)

    private val vijay = Voice(1.0f, 1.0f)
    private val nidhi = Voice(1.6f, 1.15f)   // higher voice, shorter vocal tract

    private fun radha(v: Voice) = listOf(
        Seg(60, 150f, 150f, 420f, 1300f, 0.45f),
        Seg(180, 150f, 146f, 760f, 1250f, 1f),
        Seg(50, 144f, 140f, 320f, 1650f, 0.3f),
        Seg(210, 140f, 112f, 740f, 1180f, 0.95f),
    ).map { it.copy(f0a = it.f0a * v.pitch, f0b = it.f0b * v.pitch, f1 = it.f1 * v.formant, f2 = it.f2 * v.formant) }

    private fun krishna(v: Voice) = listOf(
        Seg(70, 0f, 0f, 0f, 0f, 0.2f, noise = true),
        Seg(160, 165f, 160f, 330f, 2250f, 0.9f),
        Seg(110, 0f, 0f, 0f, 0f, 0.35f, noise = true),
        Seg(200, 158f, 120f, 620f, 1050f, 0.9f),
    ).map { it.copy(f0a = it.f0a * v.pitch, f0b = it.f0b * v.pitch, f1 = it.f1 * v.formant, f2 = it.f2 * v.formant) }

    private val rng = Random(42)
    private var phase = 0.0
    private val noiseAmp = 18f  // about -65 dBFS background

    private fun speak(segs: List<Seg>, out: MutableList<Short>, speed: Float = 1f, loud: Float = 3000f) {
        val dur = rng.nextFloat() * 0.3f + 0.85f
        val pitch = rng.nextFloat() * 0.1f + 0.95f
        val form = rng.nextFloat() * 0.06f + 0.97f
        val gain = loud * (rng.nextFloat() * 0.6f + 0.7f)
        for (s in segs) {
            val n = (s.ms * dur / speed * Dsp.SAMPLE_RATE / 1000).roundToInt()
            for (i in 0 until n) {
                val t = i.toFloat() / n
                val env = minOf(1f, minOf(i, n - i) / 80f)  // 5 ms ramps
                var y: Float
                if (s.noise) {
                    y = (rng.nextFloat() * 2 - 1) * s.amp
                } else {
                    val f0 = (s.f0a + (s.f0b - s.f0a) * t) * pitch
                    phase += 2 * PI * f0 / Dsp.SAMPLE_RATE
                    y = 0f
                    var k = 1
                    while (k * f0 < 4000) {
                        val f = k * f0
                        val a = exp(-((f - s.f1 * form) / 160f).let { it * it }) + 0.6f * exp(-((f - s.f2 * form) / 200f).let { it * it }) + 0.03f
                        y += a * sin(k * phase).toFloat() / k
                        k++
                    }
                    y *= s.amp
                }
                out.add((y * env * gain + (rng.nextFloat() * 2 - 1) * noiseAmp).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
    }

    private fun silence(ms: Int, out: MutableList<Short>) {
        repeat(ms * Dsp.SAMPLE_RATE / 1000) { out.add(((rng.nextFloat() * 2 - 1) * noiseAmp).roundToInt().toShort()) }
    }

    private fun tap(out: MutableList<Short>) {
        repeat(160) { i -> out.add((exp(-i / 25f) * 20000 * (if (i % 2 == 0) 1 else -1)).roundToInt().toShort()) }
    }

    private class Rec : ListenListener {
        val words = ArrayList<Detection>()
        val examples = ArrayList<Pair<FloatArray, List<FloatArray>>>()
        var rejected = 0
        override fun onWord(d: Detection) { words.add(d) }
        override fun onExample(db: FloatArray, frames: List<FloatArray>) { examples.add(db to frames) }
        override fun onRejected() { rejected++ }
        override fun onLevel(db: Float, noiseDb: Float, triggerDb: Float, voice: Boolean) {}
        override fun onError(message: String) { throw AssertionError(message) }
    }

    private fun run(proc: FrameProcessor, audio: List<Short>) {
        val frame = ShortArray(Dsp.FRAME)
        var i = 0
        while (i + Dsp.FRAME <= audio.size) {
            for (k in 0 until Dsp.FRAME) frame[k] = audio[i + k]
            proc.frame(frame)
            i += Dsp.FRAME
        }
    }

    /** Trains [name] like the app does: 20 words with pauses. */
    private fun train(model: WordModel, name: String, v: Voice, taps: Int = 0): Rec {
        val rec = Rec()
        val proc = FrameProcessor(model, rec).apply { mode = ListenMode.Train }
        val audio = ArrayList<Short>()
        silence(1000, audio)
        repeat(20) { i ->
            speak(radha(v), audio)
            silence(650, audio)
            if (i < taps) { tap(audio); silence(600, audio) }
        }
        run(proc, audio)
        val ex: List<Template> = rec.examples.mapNotNull { (db, f) -> model.makeExample(name, db, f) }
        assertTrue("examples cut from training: ${ex.size}", ex.size >= 18)
        model.finishTraining(name, ex, keepOld = false)
        return rec
    }

    private fun count(model: WordModel, build: (MutableList<Short>) -> Unit): List<Detection> {
        val rec = Rec()
        val proc = FrameProcessor(model, rec)
        val audio = ArrayList<Short>()
        silence(1000, audio)
        build(audio)
        silence(1500, audio)
        model.resetStream()
        run(proc, audio)
        return rec.words
    }

    @Test fun countsWordsWithPauses() {
        val model = WordModel()
        train(model, "vijay", vijay)
        val words = count(model) { a -> repeat(30) { speak(radha(vijay), a); silence(450, a) } }
        println("with pauses: ${words.size} of 30, threshold ${model.threshold}")
        assertEquals(30, words.size)
    }

    @Test fun countsContinuousChanting() {
        val model = WordModel()
        train(model, "vijay", vijay)
        // radharadharadha... with almost no gap, a bit faster than when teaching
        val words = count(model) { a -> repeat(30) { speak(radha(vijay), a, speed = 1.25f); silence(30, a) } }
        println("continuous: ${words.size} of 30")
        assertTrue("continuous: ${words.size} of 30", words.size in 27..30)
    }

    @Test fun nothingInSilenceOrNoise() {
        val model = WordModel()
        train(model, "vijay", vijay)
        val words = count(model) { a ->
            silence(10_000, a)
            repeat(15) { tap(a); silence(500, a) }
        }
        assertEquals("false counts in silence and taps", 0, words.size)
    }

    @Test fun anotherWordIsNotCounted() {
        val model = WordModel()
        train(model, "vijay", vijay)
        val words = count(model) { a -> repeat(30) { speak(krishna(vijay), a); silence(450, a) } }
        println("other word: ${words.size} of 30 counted")
        assertTrue("other word counted ${words.size} times", words.size <= 2)
    }

    @Test fun tapsAreNotTrainingExamples() {
        val model = WordModel()
        val rec = train(model, "vijay", vijay, taps = 6)
        // A tap is far too short to be a sound at all; it must not become an example.
        assertEquals("examples from 20 words and 6 taps", 20, rec.examples.size)
    }

    @Test fun tellsTwoVoicesApart() {
        val model = WordModel()
        train(model, "vijay", vijay)
        train(model, "Nidhi", nidhi)
        val n = count(model) { a -> repeat(20) { speak(radha(nidhi), a); silence(450, a) } }
        val v = count(model) { a -> repeat(20) { speak(radha(vijay), a); silence(450, a) } }
        println("Nidhi: ${n.size} words, ${n.count { it.person == "Nidhi" }} as Nidhi; vijay: ${v.size}, ${v.count { it.person == "vijay" }} as vijay")
        assertTrue(n.size >= 19 && v.size >= 19)
        assertTrue(n.count { it.person == "Nidhi" } >= 18)
        assertTrue(v.count { it.person == "vijay" } >= 18)
    }

    @Test fun savedModelLoadsTheSame() {
        val model = WordModel()
        train(model, "vijay", vijay)
        val copy = WordModel().apply { load(model.toJson()) }
        assertEquals(model.count("vijay"), copy.count("vijay"))
        assertEquals(model.threshold, copy.threshold, 1e-4f)
    }
}
