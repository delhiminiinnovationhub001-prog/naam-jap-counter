package com.naamjap.counter.voice

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One taught example of the word: MFCC frames with the example's own mean
 * removed. [mean] is that removed mean (the voice's average sound), if known.
 */
class Template(val person: String, val fast: Boolean, val f: Array<FloatArray>, val mean: FloatArray? = null) {
    val len get() = f.size
}

/** A counted word. [scores] holds each trained person's closest match (lower = closer). */
data class Detection(val person: String, val score: Float, val confidence: Float, val scores: Map<String, Float>)

/**
 * Word spotting, the same method as the counter (src/word_spotter.cpp):
 * every person teaches the word; their most typical examples become templates;
 * the live sound is searched for them every 20 ms with streaming subsequence
 * DTW (SPRING), so words are found even in continuous chanting.
 *
 * Training happens on the phone and is used on the phone (its microphone sounds
 * different from the counter's, so the counter keeps its own training).
 */
class WordModel {
    companion object {
        const val KEEP_PER_PERSON = 20
        const val KEEP_FAST = 8
        const val MAX_POOL = 40
        const val MIN_LEN = 8
        const val MAX_LEN = 32
        const val SEQ_MAX = 60
        private const val FINALIZE_DELAY = 8
        private const val SILENCE_RESET = 8
        private const val TRIM_DB = 15
        private const val FAST_LOOSEN = 1.25f
        private const val RING = 128
        private const val MIN_SPACING = 15
        private const val NO_START = Long.MAX_VALUE
        private const val DIM = Dsp.CEPSTRA

        private fun frameDist(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until DIM) {
                val d = a[i] - b[i]
                s += d * d
            }
            return sqrt(s)
        }

        /** Whole-sequence DTW, normalised by n + m. */
        fun dtw(a: Template, b: Template): Float {
            var prev = FloatArray(b.len + 1) { Float.POSITIVE_INFINITY }
            var cur = FloatArray(b.len + 1)
            prev[0] = 0f
            for (i in 1..a.len) {
                cur[0] = Float.POSITIVE_INFINITY
                for (j in 1..b.len) {
                    val c = frameDist(a.f[i - 1], b.f[j - 1])
                    cur[j] = c + minOf(prev[j - 1], prev[j], cur[j - 1])
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.len] / (a.len + b.len)
        }
    }

    private val lock = Any()
    private var templates: List<Template> = emptyList()
    private var people: List<String> = emptyList()  // index for per-person arrays
    private var tracks: List<Track> = emptyList()
    private var baseDistance = 0f
    var strictness = 5
        set(v) {
            field = v.coerceIn(1, 10)
            synchronized(lock) { applyStrictness() }
        }
    @Volatile var threshold = 1e9f
        private set

    // Fast-chanting learning: words found while it is on are also kept as examples.
    @Volatile var fastPerson: String? = null
        private set
    private val fastPool = ArrayList<Template>()

    // ---------------------------------------------------------------- model

    fun ready() = templates.isNotEmpty()
    fun trainedPeople(): List<String> = synchronized(lock) { templates.map { it.person }.distinct() }
    fun count(person: String) = templates.count { it.person.equals(person, true) }
    fun fastCount(person: String) = templates.count { it.person.equals(person, true) && it.fast }

    fun clear(person: String) = synchronized(lock) {
        setTemplates(templates.filterNot { it.person.equals(person, true) })
    }

    fun rename(from: String, to: String) = synchronized(lock) {
        setTemplates(templates.map { if (it.person.equals(from, true)) Template(to, it.fast, it.f, it.mean) else it })
    }

    private fun setTemplates(list: List<Template>) {
        templates = list
        people = list.map { it.person }.distinct()
        val means = list.mapNotNull { it.mean }
        speechMean = if (means.isEmpty()) null else FloatArray(DIM) { i -> means.sumOf { it[i].toDouble() }.toFloat() / means.size }
        rebuild()
    }

    /**
     * The trained voices' average sound. Live frames are normalised by a running
     * average of the speech heard; starting it here (instead of from the first
     * sound) means the first words after pressing Start are counted too.
     */
    @Volatile var speechMean: FloatArray? = null
        private set

    private fun applyStrictness() {
        val loosen = if (fastPerson != null) FAST_LOOSEN else 1f
        threshold = if (baseDistance > 0) baseDistance * (1.9f - 0.08f * strictness) * loosen else 1e9f
    }

    /** Threshold from the usual distance between examples of the same person (like with like). */
    private fun rebuild() {
        val intra = ArrayList<Float>()
        for (i in templates.indices) for (j in i + 1 until templates.size) {
            val a = templates[i]
            val b = templates[j]
            if (a.person == b.person && a.fast == b.fast) intra.add(dtw(a, b))
        }
        baseDistance = when {
            intra.isNotEmpty() -> intra.sorted()[intra.size / 2]
            templates.isEmpty() -> 0f
            else -> 2f
        }
        applyStrictness()
        tracks = templates.map { Track(it.len) }
        pending = null
    }

    /** The k most typical of the candidates (smallest total distance to the others). */
    private fun medoids(c: List<Template>, k: Int): List<Template> {
        if (c.size <= k) return c
        val total = FloatArray(c.size)
        for (i in c.indices) for (j in i + 1 until c.size) {
            val d = dtw(c[i], c[j])
            total[i] += d
            total[j] += d
        }
        return c.indices.sortedBy { total[it] }.take(k).map { c[it] }
    }

    // ------------------------------------------------------------- training

    /**
     * Turns one spoken example (frame levels in dB + raw MFCC frames) into a
     * template: keeps the loud part, shrinks a slow word to MAX_LEN, removes its mean.
     * Returns null if it is too short to be the word.
     */
    fun makeExample(person: String, db: FloatArray, frames: List<FloatArray>): Template? {
        val n = min(frames.size, db.size)
        if (n < MIN_LEN) return null
        val peak = (0 until n).maxOf { db[it] }
        var first = 0
        var last = n - 1
        var trim = TRIM_DB
        while (trim >= 9) {
            first = 0
            last = n - 1
            while (first < last && db[first] < peak - trim) first++
            while (last > first && db[last] < peak - trim) last--
            if (last - first + 1 <= MAX_LEN) break
            trim -= 3
        }
        val span = last - first + 1
        if (span < MIN_LEN) return null
        val len = min(span, MAX_LEN)
        val mean = FloatArray(DIM)
        for (f in first..last) for (i in 0 until DIM) mean[i] += frames[f][i]
        for (i in 0 until DIM) mean[i] /= span
        val out = Array(len) { f ->
            val pos = if (len > 1) f.toFloat() * (span - 1) / (len - 1) else 0f
            val a = first + pos.toInt()
            val b = min(a + 1, last)
            val w = pos - pos.toInt()
            FloatArray(DIM) { i -> frames[a][i] * (1 - w) + frames[b][i] * w - mean[i] }
        }
        return Template(person, false, out, mean)
    }

    /** New slow examples replace the person's old slow ones; fast ones are kept. */
    fun finishTraining(person: String, examples: List<Template>, keepOld: Boolean) = synchronized(lock) {
        val mine = templates.filter { it.person.equals(person, true) }
        val fast = mine.filter { it.fast }.take(KEEP_FAST)
        val pool = examples + if (keepOld) mine.filter { !it.fast } else emptyList()
        val keep = fast + medoids(pool.take(MAX_POOL + KEEP_PER_PERSON), KEEP_PER_PERSON - fast.size)
        setTemplates(templates.filterNot { it.person.equals(person, true) } + keep.map { Template(person, it.fast, it.f, it.mean) })
    }

    fun beginFast(person: String): Boolean = synchronized(lock) {
        if (count(person) == 0) return false
        fastPool.clear()
        fastPerson = templates.first { it.person.equals(person, true) }.person
        applyStrictness()
        true
    }

    fun fastLearnt(): Int = synchronized(lock) { fastPool.size }

    /** Ends fast learning: old + new fast examples compete for KEEP_FAST places. */
    fun stopFast(keep: Boolean) = synchronized(lock) {
        val who = fastPerson ?: return
        fastPerson = null
        applyStrictness()
        if (!keep || fastPool.isEmpty()) {
            fastPool.clear()
            return
        }
        val mine = templates.filter { it.person == who }
        val fast = medoids(mine.filter { it.fast } + fastPool, KEEP_FAST)
        val slow = medoids(mine.filter { !it.fast }, KEEP_PER_PERSON - fast.size)
        fastPool.clear()
        setTemplates(templates.filterNot { it.person == who } + fast + slow)
    }

    // ------------------------------------------------------------- spotting

    private class Track(m: Int) {
        val d = FloatArray(m + 1) { Float.POSITIVE_INFINITY }
        val s = LongArray(m + 1) { NO_START }
        var bestRaw = Float.POSITIVE_INFINITY
        var bestNorm = Float.POSITIVE_INFINITY
        var ts = 0L
        var te = 0L
        fun reset() {
            d.fill(Float.POSITIVE_INFINITY)
            s.fill(NO_START)
            bestRaw = Float.POSITIVE_INFINITY
            bestNorm = Float.POSITIVE_INFINITY
        }
    }

    private class Hit(var person: Int, var score: Float, var ts: Long, var te: Long, val per: FloatArray)

    private var frameNo = 0L
    private var silenceRun = SILENCE_RESET
    private var pending: Hit? = null
    private var lastTs = 0L
    private var lastTe = 0L
    private var haveLast = false
    private val ring = Array(RING) { FloatArray(DIM) }
    private var personRing = Array(RING) { FloatArray(0) }
    private var framePerson = FloatArray(0)
    private val out = ArrayDeque<Detection>()
    @Volatile var recentScore = Float.POSITIVE_INFINITY
        private set
    private var recentMin = Float.POSITIVE_INFINITY
    private var recentFrames = 0

    fun resetStream() = synchronized(lock) {
        tracks.forEach { it.reset() }
        pending = null
        haveLast = false
        silenceRun = SILENCE_RESET
    }

    private fun newHit(person: Int, score: Float, ts: Long, te: Long): Hit {
        val per = FloatArray(people.size) { Float.POSITIVE_INFINITY }
        per[person] = score
        return Hit(person, score, ts, te, per)
    }

    private fun springStep(tr: Track, tp: Template, who: Int, x: FloatArray, speech: Boolean, t: Long): Hit? {
        val m = tp.len
        var prevDiag = if (speech) 0f else Float.POSITIVE_INFINITY
        var prevDiagS = t
        tr.d[0] = prevDiag
        tr.s[0] = t
        for (i in 1..m) {
            val up = tr.d[i]
            val upS = tr.s[i]
            val left = tr.d[i - 1]
            val leftS = tr.s[i - 1]
            var best = prevDiag
            var bestS = prevDiagS
            if (left < best) { best = left; bestS = leftS }
            if (up < best) { best = up; bestS = upS }
            prevDiag = up
            prevDiagS = upS
            tr.d[i] = frameDist(x, tp.f[i - 1]) + best
            tr.s[i] = bestS
        }
        var hit: Hit? = null
        if (tr.bestRaw < Float.POSITIVE_INFINITY) {
            var final = true
            for (i in 1..m) if (tr.d[i] < tr.bestRaw && tr.s[i] <= tr.te) { final = false; break }
            if (final) {
                hit = newHit(who, tr.bestNorm, tr.ts, tr.te)
                tr.bestRaw = Float.POSITIVE_INFINITY
                tr.bestNorm = Float.POSITIVE_INFINITY
                for (i in 1..m) if (tr.s[i] <= hit.te) tr.d[i] = Float.POSITIVE_INFINITY
            }
        }
        // Matched length: at least 70% of the template, at most twice as long.
        if (tr.s[m] != NO_START) {
            val n = t - tr.s[m] + 1
            if (n * 10 >= m * 7 && n <= m * 2) {
                val norm = tr.d[m] / (m + n)
                if (norm < recentMin) recentMin = norm
                if (norm < framePerson[who]) framePerson[who] = norm
                if (norm <= threshold && tr.d[m] < tr.bestRaw) {
                    tr.bestRaw = tr.d[m]
                    tr.bestNorm = norm
                    tr.ts = tr.s[m]
                    tr.te = t
                }
            }
        }
        return hit
    }

    private fun overlaps(ats: Long, ate: Long, bts: Long, bte: Long): Boolean {
        val ov = min(ate, bte) - max(ats, bts)
        val shorter = min(ate - ats, bte - bts) + 1
        return ov * 2 > shorter
    }

    private fun finishWord(h: Hit) {
        // Every person's closest score for this word, even below the threshold.
        val from = h.ts + (h.te - h.ts) / 2
        val to = h.te + FINALIZE_DELAY / 2
        var t = from
        while (t <= to && t < frameNo) {
            if (frameNo - t < RING) {
                val pr = personRing[(t % RING).toInt()]
                for (p in h.per.indices) if (p < pr.size) h.per[p] = min(h.per[p], pr[p])
            }
            t++
        }
        for (p in h.per.indices) if (h.per[p] < h.score) { h.score = h.per[p]; h.person = p }
        if (haveLast && overlaps(h.ts, h.te, lastTs, lastTe)) return
        if (haveLast && (h.ts + h.te) - (lastTs + lastTe) < 2 * MIN_SPACING) return  // max ~3 words/s
        lastTs = h.ts
        lastTe = h.te
        haveLast = true
        captureFast(h)
        var second = Float.POSITIVE_INFINITY
        for (p in h.per.indices) if (p != h.person) second = min(second, h.per[p])
        val confidence = when {
            second.isFinite() && second > 0 -> ((second - h.score) / second * 5f).coerceIn(0f, 1f)
            people.size > 1 -> 1f
            else -> (1f - h.score / (threshold * 1.25f)).coerceIn(0f, 1f)
        }
        val scores = people.indices.associate { people[it] to h.per[it] }
        out.addLast(Detection(people[h.person], h.score, confidence, scores))
    }

    private fun captureFast(h: Hit) {
        val who = fastPerson ?: return
        val len = (h.te - h.ts + 1).toInt()
        if (len < MIN_LEN || len > MAX_LEN || frameNo - h.ts >= RING || fastPool.size >= MAX_POOL) return
        val mean = FloatArray(DIM)
        for (t in h.ts..h.te) for (i in 0 until DIM) mean[i] += ring[(t % RING).toInt()][i]
        for (i in 0 until DIM) mean[i] /= len
        fastPool.add(Template(who, true, Array(len) { f ->
            val r = ring[((h.ts + f) % RING).toInt()]
            FloatArray(DIM) { i -> r[i] - mean[i] }
        }))
    }

    private fun mergeHit(h: Hit) {
        if (haveLast && overlaps(h.ts, h.te, lastTs, lastTe)) return
        val p = pending
        if (p != null && overlaps(p.ts, p.te, h.ts, h.te)) {
            if (h.score < p.score) { p.person = h.person; p.score = h.score }
            for (i in p.per.indices) p.per[i] = min(p.per[i], h.per[i])
            p.ts = min(p.ts, h.ts)
            p.te = max(p.te, h.te)
            return
        }
        if (p != null) finishWord(p)
        pending = h
    }

    /**
     * Feeds one frame (MFCCs already normalised by the running speech mean).
     * Returns the words that finished on this frame.
     */
    fun feed(x: FloatArray, speech: Boolean): List<Detection> = synchronized(lock) {
        if (templates.isEmpty()) return emptyList()
        if (framePerson.size != people.size) {
            framePerson = FloatArray(people.size)
            personRing = Array(RING) { FloatArray(people.size) { Float.POSITIVE_INFINITY } }
        }
        val t = ++frameNo
        x.copyInto(ring[(t % RING).toInt()])
        framePerson.fill(Float.POSITIVE_INFINITY)
        silenceRun = if (speech) 0 else silenceRun + 1
        if (silenceRun < SILENCE_RESET) {
            for (k in templates.indices) {
                val tp = templates[k]
                springStep(tracks[k], tp, people.indexOf(tp.person), x, speech, t)?.let { mergeHit(it) }
            }
        } else if (silenceRun == SILENCE_RESET) {
            for (k in tracks.indices) {
                val tr = tracks[k]
                if (tr.bestRaw < Float.POSITIVE_INFINITY)
                    mergeHit(newHit(people.indexOf(templates[k].person), tr.bestNorm, tr.ts, tr.te))
                tr.reset()
            }
        }
        framePerson.copyInto(personRing[(t % RING).toInt()])
        pending?.let { if (t > it.te + FINALIZE_DELAY) { finishWord(it); pending = null } }
        if (++recentFrames >= 50) {
            recentScore = recentMin
            recentMin = Float.POSITIVE_INFINITY
            recentFrames = 0
        }
        if (out.isEmpty()) return emptyList()
        val list = out.toList()
        out.clear()
        list
    }

    // --------------------------------------------------------------- storage

    fun toJson(): JSONObject = synchronized(lock) {
        val arr = JSONArray()
        for (t in templates) {
            val bb = ByteBuffer.allocate(t.len * DIM * 4).order(ByteOrder.LITTLE_ENDIAN)
            t.f.forEach { fr -> fr.forEach { bb.putFloat(it) } }
            val o = JSONObject().put("p", t.person).put("fast", t.fast).put("n", t.len)
                .put("f", Base64.getEncoder().encodeToString(bb.array()))
            t.mean?.let { m -> o.put("m", JSONArray(m.map { it.toDouble() })) }
            arr.put(o)
        }
        JSONObject().put("v", 1).put("strict", strictness).put("t", arr)
    }

    fun load(j: JSONObject) = synchronized(lock) {
        val arr = j.optJSONArray("t") ?: JSONArray()
        val list = ArrayList<Template>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.getInt("n")
            val bb = ByteBuffer.wrap(Base64.getDecoder().decode(o.getString("f"))).order(ByteOrder.LITTLE_ENDIAN)
            if (n !in MIN_LEN..MAX_LEN || bb.remaining() < n * DIM * 4) continue
            val m = o.optJSONArray("m")?.let { a -> if (a.length() == DIM) FloatArray(DIM) { a.getDouble(it).toFloat() } else null }
            list.add(Template(o.getString("p"), o.optBoolean("fast"), Array(n) { FloatArray(DIM) { bb.float } }, m))
        }
        strictness = j.optInt("strict", 5).coerceIn(1, 10)
        setTemplates(list)
    }

    /** For the UI: how close the last second came, as a % of the threshold (100 = would count). */
    fun recentMatchPercent(): Int {
        val r = recentScore
        if (!r.isFinite() || threshold >= 1e8f) return 0
        return (threshold / r * 100f).roundToInt().coerceIn(0, 200)
    }
}
