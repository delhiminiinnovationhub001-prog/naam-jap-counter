package com.naamjap.counter.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sound processing, the same as the counter's firmware (src/audio_counter.cpp):
 * 16 kHz, 20 ms frames, 120 Hz high-pass, speech-band level, 12 MFCCs and a
 * pitch check. Keeping the numbers identical keeps the phone's behaviour (and
 * the tuning done on the counter) the same.
 */
object Dsp {
    const val SAMPLE_RATE = 16000
    const val FRAME_MS = 20
    const val FRAME = SAMPLE_RATE * FRAME_MS / 1000  // 320 samples
    const val CEPSTRA = 12
    const val FULL_SCALE = 8388608f                   // 2^23, the counter's 24-bit scale
    const val PHONE_TO_COUNTER = 256f                 // 16-bit phone samples -> 24-bit scale

    private const val NFFT = 512
    private const val NBINS = NFFT / 2 + 1
    private const val NMEL = 26
    private const val MEL_LO = 100f
    private const val MEL_HI = 7000f
    private const val PITCH_MIN_LAG = SAMPLE_RATE / 500
    private const val PITCH_MAX_LAG = SAMPLE_RATE / 70
    const val PITCH_VOICED_CORR = 0.45f

    private val window = FloatArray(FRAME) { n -> (0.54 - 0.46 * cos(2 * PI * n / (FRAME - 1))).toFloat() }
    private val twCos = FloatArray(NFFT / 2) { k -> cos(2 * PI * k / NFFT).toFloat() }
    private val twSin = FloatArray(NFFT / 2) { k -> sin(2 * PI * k / NFFT).toFloat() }
    private val melLo = IntArray(NMEL)
    private val melMid = IntArray(NMEL)
    private val melHi = IntArray(NMEL)
    private val dct = Array(CEPSTRA) { c -> FloatArray(NMEL) { m -> cos(PI * (c + 1) * (m + 0.5) / NMEL).toFloat() } }

    init {
        fun hzToMel(hz: Float) = 2595f * log10(1f + hz / 700f)
        fun melToHz(mel: Float) = 700f * (10f.pow(mel / 2595f) - 1f)
        val lo = hzToMel(MEL_LO)
        val hi = hzToMel(MEL_HI)
        val bins = IntArray(NMEL + 2) { i ->
            val hz = melToHz(lo + (hi - lo) * i / (NMEL + 1))
            floor((NFFT + 1) * hz / SAMPLE_RATE).toInt()
        }
        for (m in 0 until NMEL) {
            melLo[m] = bins[m]
            melMid[m] = max(bins[m + 1], melLo[m] + 1)
            melHi[m] = min(max(bins[m + 2], melMid[m] + 1), NBINS - 1)
        }
    }

    /** Second-order filter (RBJ cookbook), as in the firmware. */
    class Biquad private constructor(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f

        fun step(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            private fun make(nb0: Float, nb1: Float, nb2: Float, na0: Float, na1: Float, na2: Float) =
                Biquad(nb0 / na0, nb1 / na0, nb2 / na0, na1 / na0, na2 / na0)

            fun highPass(hz: Float, q: Float = 0.7071f): Biquad {
                val w = (2 * PI * hz / SAMPLE_RATE).toFloat()
                val c = cos(w)
                val alpha = sin(w) / (2 * q)
                return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun lowPass(hz: Float, q: Float = 0.7071f): Biquad {
                val w = (2 * PI * hz / SAMPLE_RATE).toFloat()
                val c = cos(w)
                val alpha = sin(w) / (2 * q)
                return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }
        }
    }

    private val re = FloatArray(NFFT)
    private val im = FloatArray(NFFT)
    private val logMel = FloatArray(NMEL)

    /**
     * MFCCs of the frame at buf[FRAME until 2*FRAME] (buf[FRAME-1] is the sample
     * before it, for pre-emphasis). Not thread-safe: called from the audio thread only.
     */
    fun mfcc(buf: FloatArray, out: FloatArray) {
        var prev = buf[FRAME - 1]
        for (n in 0 until FRAME) {
            val x = buf[FRAME + n]
            re[n] = (x - 0.97f * prev) * window[n]
            im[n] = 0f
            prev = x
        }
        for (n in FRAME until NFFT) {
            re[n] = 0f
            im[n] = 0f
        }
        fft()
        for (k in 0 until NBINS) re[k] = re[k] * re[k] + im[k] * im[k]
        for (m in 0 until NMEL) {
            var e = 0f
            for (k in melLo[m] until melMid[m]) e += re[k] * (k - melLo[m]).toFloat() / (melMid[m] - melLo[m])
            for (k in melMid[m]..melHi[m]) e += re[k] * (melHi[m] - k).toFloat() / (melHi[m] - melMid[m])
            logMel[m] = ln(e + 1f)
        }
        for (c in 0 until CEPSTRA) {
            var v = 0f
            val row = dct[c]
            for (m in 0 until NMEL) v += row[m] * logMel[m]
            out[c] = v
        }
    }

    /** Normalised autocorrelation over the 40 ms in buf: true if the sound has a pitch. */
    fun voiced(buf: FloatArray): Boolean {
        val n = 2 * FRAME
        val energy = FloatArray(n + 1)
        for (i in 0 until n) energy[i + 1] = energy[i] + buf[i] * buf[i]
        var best = 0f
        for (lag in PITCH_MIN_LAG..PITCH_MAX_LAG) {
            val len = n - lag
            var r = 0f
            for (i in 0 until len) r += buf[i] * buf[i + lag]
            val corr = r / sqrt(energy[len] * (energy[n] - energy[lag]) + 1e-9f)
            if (corr > best) best = corr
        }
        return best > PITCH_VOICED_CORR
    }

    private fun fft() {
        var j = 0
        for (i in 1 until NFFT) {
            var bit = NFFT shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= NFFT) {
            val half = len / 2
            val step = NFFT / len
            var i = 0
            while (i < NFFT) {
                for (k in 0 until half) {
                    val wr = twCos[k * step]
                    val wi = -twSin[k * step]
                    val a = i + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }
}
