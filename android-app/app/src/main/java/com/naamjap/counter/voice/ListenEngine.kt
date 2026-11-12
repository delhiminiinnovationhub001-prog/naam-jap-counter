package com.naamjap.counter.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Receives what the sound processing finds. */
interface ListenListener {
    fun onWord(d: Detection)
    fun onExample(db: FloatArray, frames: List<FloatArray>)
    fun onRejected()                 // a sound without a pitch (tap, clap...) while training
    fun onLevel(db: Float, noiseDb: Float, triggerDb: Float, voice: Boolean)
    fun onError(message: String)
}

enum class ListenMode { Count, Train }

/**
 * Processes 20 ms frames of 16 kHz audio, with the counter's levels, noise
 * tracking and gates (src/audio_counter.cpp):
 *  - Count: every frame goes to the [WordModel]; found words are reported.
 *  - Train: each voice burst (a word followed by a short pause) is cut out
 *    and reported as an example, like the counter's "Train voice".
 * Separate from the microphone so it can be tested with recorded or made-up sound.
 */
class FrameProcessor(private val model: WordModel, private val listener: ListenListener) {
    @Volatile var mode = ListenMode.Count
    @Volatile var sensitivity = 5
    @Volatile var paused = false

    private val buf = FloatArray(2 * Dsp.FRAME)  // previous + current frame (pitch needs 40 ms)
    private val mfcc = FloatArray(Dsp.CEPSTRA)
    private val x = FloatArray(Dsp.CEPSTRA)
    private val cmn = FloatArray(Dsp.CEPSTRA)
    private var cmnReady = false
    private val hp = Dsp.Biquad.highPass(120f)
    private val lp = Dsp.Biquad.lowPass(4000f)
    private var noiseDb = -75f
    private var inVoice = false
    private var voiceMs = 0
    private var silenceMs = 0
    private var warmup = 25
    private val segDb = ArrayList<Float>()
    private val segFrames = ArrayList<FloatArray>()
    private var voicedFrames = 0
    private var frames = 0

    private fun resetSeg() {
        segDb.clear(); segFrames.clear(); voicedFrames = 0; frames = 0
    }

    init {
        model.speechMean?.let { it.copyInto(cmn); cmnReady = true }
    }

    /** One frame of Dsp.FRAME samples (16-bit). */
    fun frame(pcm: ShortArray) {
        System.arraycopy(buf, Dsp.FRAME, buf, 0, Dsp.FRAME)
        var sumSq = 0.0
        for (i in 0 until Dsp.FRAME) {
            val y = hp.step(pcm[i] * Dsp.PHONE_TO_COUNTER)
            buf[Dsp.FRAME + i] = y
            val v = lp.step(y)
            sumSq += v.toDouble() * v
        }
        val rms = sqrt((sumSq / Dsp.FRAME).toFloat())
        val db = 20f * log10(rms / Dsp.FULL_SCALE + 1e-9f)
        if (warmup > 0) {
            warmup--
            noiseDb = db
            return
        }
        val margin = 22f - 1.6f * sensitivity
        val onThr = max(noiseDb + margin, -72f)
        val offThr = onThr - 3f
        Dsp.mfcc(buf, mfcc)
        val counting = mode == ListenMode.Count && !paused

        if (counting) {
            // Continuous chanting dips between syllables, so matching uses a lower
            // "is anyone speaking" gate just above the noise floor.
            val speech = db >= noiseDb + 15f
            if (speech) {
                if (!cmnReady) { mfcc.copyInto(cmn); cmnReady = true }
                for (c in 0 until Dsp.CEPSTRA) cmn[c] += 0.02f * (mfcc[c] - cmn[c])
            }
            if (cmnReady) {
                for (c in 0 until Dsp.CEPSTRA) x[c] = mfcc[c] - cmn[c]
                for (d in model.feed(x, speech)) listener.onWord(d)
            }
        }
        val collect = mode == ListenMode.Train && !paused

        fun addFrame() {
            if (!collect) return
            frames++
            if (Dsp.voiced(buf)) voicedFrames++
            if (segFrames.size < WordModel.SEQ_MAX) {
                segDb.add(db)
                segFrames.add(mfcc.copyOf())
            }
        }

        if (db >= onThr) {
            voiceMs += Dsp.FRAME_MS
            silenceMs = 0
            addFrame()
            if (!inVoice && voiceMs >= 150) inVoice = true
            if (inVoice && voiceMs > (if (counting) 120_000 else 15_000)) {
                noiseDb = db  // never-ending sound (fan, TV): the new background
                inVoice = false
                voiceMs = 0
                resetSeg()
            }
        } else if (db < offThr || !inVoice) {
            silenceMs += Dsp.FRAME_MS
            if (inVoice) {
                if (silenceMs >= 350) {
                    inVoice = false
                    voiceMs = 0
                    if (collect) {
                        // Taps and claps have no pitch: not an example of the word.
                        if (frames >= 4 && voicedFrames >= max(3, (frames * 0.3f).toInt()))
                            listener.onExample(segDb.toFloatArray(), segFrames.toList())
                        else listener.onRejected()
                    }
                    resetSeg()
                }
            } else if (silenceMs >= 3 * Dsp.FRAME_MS) {
                voiceMs = 0
                resetSeg()
            }
        } else {
            silenceMs = 0
            addFrame()
        }
        if (!inVoice && db < onThr) {
            val a = if (db < noiseDb) 0.05f else 0.005f
            noiseDb = (noiseDb + a * (db - noiseDb)).coerceIn(-95f, -30f)
        }
        listener.onLevel(db, noiseDb, onThr, inVoice)
    }
}

/** Listens to the phone's microphone on its own thread and feeds a [FrameProcessor]. */
class ListenEngine(private val model: WordModel, private val listener: ListenListener) {
    private var proc = FrameProcessor(model, listener)

    @Volatile var mode = ListenMode.Count
        set(v) {
            field = v
            proc.mode = v
            model.resetStream()
        }
    @Volatile var sensitivity = 5
        set(v) {
            field = v
            proc.sensitivity = v
        }
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    fun start() {
        if (running) return
        proc = FrameProcessor(model, listener).also { it.mode = mode; it.sensitivity = sensitivity }
        model.resetStream()
        running = true
        thread = Thread({ loop() }, "jap-listen").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    @SuppressLint("MissingPermission")  // the UI asks for RECORD_AUDIO first
    private fun loop() {
        val minBuf = AudioRecord.getMinBufferSize(Dsp.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,  // no automatic gain or noise effects
                Dsp.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, Dsp.FRAME * 2 * 8),
            )
        } catch (e: Exception) {
            listener.onError("Could not open the microphone: ${e.message}")
            running = false
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            listener.onError("The microphone is busy or not allowed")
            rec.release()
            running = false
            return
        }
        rec.startRecording()
        val pcm = ShortArray(Dsp.FRAME)
        try {
            while (running) {
                var got = 0
                while (got < Dsp.FRAME && running) {
                    val r = rec.read(pcm, got, Dsp.FRAME - got)
                    if (r <= 0) {
                        listener.onError("The microphone stopped (error $r)")
                        running = false
                        return
                    }
                    got += r
                }
                if (running) proc.frame(pcm)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}
