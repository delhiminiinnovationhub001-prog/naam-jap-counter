package com.naamjap.counter.phone

import android.os.Handler
import android.os.Looper
import com.naamjap.counter.data.LocalStore
import com.naamjap.counter.data.Tally
import com.naamjap.counter.voice.Detection
import com.naamjap.counter.voice.ListenEngine
import com.naamjap.counter.voice.ListenListener
import com.naamjap.counter.voice.ListenMode
import com.naamjap.counter.voice.Template
import com.naamjap.counter.voice.WordModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

enum class ChantMode { Voice, Tap }

/** Teaching the word on the phone: say it, pause, again... */
data class PhoneTraining(
    val person: String,
    val done: Int,
    val target: Int,
    val last: String,       // feedback on the last sound
    val finishing: Boolean, // choosing the best examples
)

/** Learning the person's fast, continuous chanting from words found while they chant. */
data class PhoneFast(val person: String, val learnt: Int, val target: Int)

data class Level(val db: Float, val noiseDb: Float, val triggerDb: Float, val voice: Boolean, val matchPercent: Int)

/** A milestone to celebrate: every 100 japs today, a bigger one every 1000. */
data class Milestone(val count: Long, val big: Boolean, val name: String?)

data class PhoneState(
    val me: String?,
    val names: List<String>,
    val listening: Boolean,
    val paused: Boolean,        // listening session kept, microphone released (from the notification)
    val mode: ChantMode,
    val wordName: String,
    val trained: Int,           // templates of `me` on this phone
    val fastTrained: Int,
    val trainedPeople: List<String>,
    val training: PhoneTraining?,
    val fast: PhoneFast?,
    val soundsLike: String?,    // the voice check: recent words sound like another trained person
    val error: String?,
    val revision: Long,         // changes whenever counts change (UI recomputes the tally)
)

/**
 * Chanting on the phone, with or without the counter: listens with the phone's
 * microphone (or counts taps), credits japs to the name picked on this phone,
 * and keeps them in [LocalStore] until they are added to the counter.
 */
class Chanter(val store: LocalStore, private val onCountsChanged: () -> Unit) {

    val model = WordModel()
    val tally = Tally(store)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    // Declared before anything that builds the state: Kotlin sets properties in
    // file order, and reading these before they are set crashed the app at start.
    private var listening = false
    private var paused = false
    private var mode = ChantMode.Voice
    private var soundsLike: String? = null
    private var error: String? = null
    private var revision = 0L

    private val _state = MutableStateFlow(snapshot(false, ChantMode.Voice, null, null, null, null))
    val state: StateFlow<PhoneState> = _state
    private val _level = MutableStateFlow(Level(-90f, -90f, -70f, false, 0))
    val level: StateFlow<Level> = _level
    private val _milestones = MutableSharedFlow<Milestone>(extraBufferCapacity = 4)
    val milestones: SharedFlow<Milestone> = _milestones

    private var training: PhoneTraining? = null
    private val examples = ArrayList<Template>()
    private var fast: PhoneFast? = null
    private val recentWho = ArrayDeque<String>()

    private val engine = ListenEngine(model, object : ListenListener {
        override fun onWord(d: Detection) = main.post { word(d) }.let { }
        override fun onExample(db: FloatArray, frames: List<FloatArray>) = main.post { example(db, frames) }.let { }
        override fun onRejected() = main.post { trainFeedback("That sounded like a tap or noise, not a word") }.let { }
        override fun onLevel(db: Float, noiseDb: Float, triggerDb: Float, voice: Boolean) {
            _level.value = Level(db, noiseDb, triggerDb, voice, model.recentMatchPercent())
        }
        override fun onError(message: String) = main.post { update(error = message, listening = false) }.let { }
    })

    init {
        store.loadVoice()?.let { runCatching { model.load(it) } }
        engine.sensitivity = store.sensitivity
        update()
    }

    // ------------------------------------------------------------ settings

    fun pickMe(name: String) {
        store.addName(name)
        store.me = store.names.first { it.equals(name, true) }
        store.save()
        recentWho.clear()
        update(soundsLike = null)
        onCountsChanged()
    }

    fun setSensitivity(v: Int) {
        store.sensitivity = v.coerceIn(1, 10)
        engine.sensitivity = store.sensitivity
        store.save()
    }

    fun setStrictness(v: Int) {
        model.strictness = v
        saveVoice()
    }

    fun setWordName(name: String) {
        store.wordName = name.trim().ifBlank { "Radha" }
        store.save()
        update()
    }

    fun setMode(m: ChantMode) {
        if (m == ChantMode.Tap) stopListening()
        update(mode = m)
    }

    fun forgetVoice(name: String) {
        model.clear(name)
        saveVoice()
        update()
    }

    fun renamePerson(from: String, to: String) {
        model.rename(from, to)
        store.rename(from, to)
        saveVoice()
        update()
        onCountsChanged()
    }

    // ----------------------------------------------------------- listening

    fun startListening(): Boolean {
        if (store.me == null && training == null) return false
        engine.mode = if (training != null) ListenMode.Train else ListenMode.Count
        paused = false
        engine.start()
        update(listening = true, error = null)
        return true
    }

    fun stopListening() {
        engine.stop()
        paused = false
        if (fast != null) stopFast(keep = true)
        update(listening = false)
    }

    /** Pause keeps the session (and its notification) but frees the microphone, e.g. for a call. */
    fun setPaused(p: Boolean) {
        if (!listening || p == paused) return
        paused = p
        if (p) engine.stop() else engine.start()
        update()
    }

    fun tap() {
        val me = store.me ?: return
        credit(me)
    }

    /** Takes back the last phone jap of today, if the counter does not have it yet. */
    fun untap(): Boolean {
        val me = store.me ?: return false
        if (store.unsent(store.today(), me) - (store.pending?.days?.get(store.today())?.get(me) ?: 0) <= 0) return false
        store.addPhone(me, -1)
        update()
        onCountsChanged()
        return true
    }

    private fun word(d: Detection) {
        val me = store.me ?: return
        if (training != null) return
        fast?.let {
            val learnt = model.fastLearnt()
            fast = it.copy(learnt = learnt)
            if (learnt >= it.target) stopFast(keep = true)
        }
        // The voice check: the name is picked by hand, the voice only warns.
        val others = model.trainedPeople().filterNot { it.equals(me, true) }
        if (others.isNotEmpty()) {
            recentWho.addLast(d.person)
            while (recentWho.size > 5) recentWho.removeFirst()
            val other = recentWho.groupingBy { it }.eachCount().filterKeys { !it.equals(me, true) }.maxByOrNull { it.value }
            update(soundsLike = if (other != null && other.value >= 3) other.key else null)
        }
        credit(me)
    }

    private fun credit(name: String) {
        val before = tally.today(name)
        store.addPhone(name)
        val after = before + 1
        if (after % 100 == 0L) {
            val key = store.today() + "|" + name
            if ((store.celebrated[key] ?: 0) < after) {
                store.celebrated[key] = after
                store.save()
                _milestones.tryEmit(Milestone(after, after % 1000 == 0L, name))
            }
        }
        update()
        onCountsChanged()
    }

    /** Milestones of the counter's own count (the live screen), shown on the phone too. */
    fun counterMilestone(name: String?, today: Long, previous: Long) {
        if (today <= previous || today / 100 == previous / 100 || today < 100) return
        val m = today / 100 * 100
        val key = store.today() + "|counter|" + (name ?: "")
        if ((store.celebrated[key] ?: 0) >= m) return
        store.celebrated[key] = m
        store.save()
        _milestones.tryEmit(Milestone(m, m % 1000 == 0L, name))
    }

    // ------------------------------------------------------------ training

    fun startTraining(name: String, target: Int = 20) {
        stopListening()
        store.addName(name)
        examples.clear()
        training = PhoneTraining(name, 0, target, "Say ${store.wordName}, then pause. Again and again.", false)
        engine.mode = ListenMode.Train
        engine.start()
        update(listening = true, error = null)
    }

    private fun example(db: FloatArray, frames: List<FloatArray>) {
        val t = training ?: return
        if (t.finishing) return
        val ex = model.makeExample(t.person, db, frames)
        if (ex == null) {
            trainFeedback("Too short. Say the whole word ${store.wordName}")
            return
        }
        examples.add(ex)
        training = t.copy(done = examples.size, last = "Got it (${examples.size} of ${t.target})")
        update()
        if (examples.size >= t.target) finishTraining()
    }

    private fun trainFeedback(msg: String) {
        training?.let { training = it.copy(last = msg); update() }
    }

    fun redoLast() {
        val t = training ?: return
        if (examples.isEmpty()) return
        examples.removeAt(examples.size - 1)
        training = t.copy(done = examples.size, last = "Removed the last one. Say it again")
        update()
    }

    fun finishTraining() {
        val t = training ?: return
        engine.stop()
        if (examples.size < 5) {
            training = t.copy(last = "Please say at least 5 good words first")
            engine.mode = ListenMode.Train
            engine.start()
            update()
            return
        }
        training = t.copy(finishing = true, last = "Choosing your most typical words…")
        update(listening = false)
        val list = ArrayList(examples)
        worker.execute {
            model.finishTraining(t.person, list, keepOld = false)
            saveVoice()
            main.post {
                training = null
                examples.clear()
                if (store.me == null) store.me = t.person
                store.save()
                update()
            }
        }
    }

    fun cancelTraining() {
        engine.stop()
        training = null
        examples.clear()
        update(listening = false)
    }

    /** After teaching with pauses: chant fast for a while so the phone learns that too. */
    fun startFast(name: String, target: Int = 20): Boolean {
        if (!model.beginFast(name)) return false
        if (!store.me.equals(name, true)) pickMe(name)
        fast = PhoneFast(name, 0, target)
        engine.mode = ListenMode.Count
        engine.start()
        update(listening = true)
        return true
    }

    fun stopFast(keep: Boolean) {
        if (fast == null) return
        fast = null
        worker.execute {
            model.stopFast(keep)
            saveVoice()
            main.post { update() }
        }
        update()
    }

    private fun saveVoice() = store.saveVoice(model.toJson())

    // ---------------------------------------------------------------- state

    /** Call after counts changed elsewhere (sync, counter data). */
    fun refresh() = update()


    private fun update(
        listening: Boolean? = null, mode: ChantMode? = null, soundsLike: String? = this.soundsLike,
        error: String? = this.error,
    ) {
        listening?.let { this.listening = it }
        mode?.let { this.mode = it }
        this.soundsLike = soundsLike
        this.error = error
        _state.value = snapshot(this.listening, this.mode, training, fast, soundsLike, error)
    }

    private fun snapshot(
        listening: Boolean, mode: ChantMode, training: PhoneTraining?, fast: PhoneFast?, soundsLike: String?,
        error: String?,
    ): PhoneState {
        val me = store.me
        return PhoneState(
            me = me,
            names = tally.allNames(),
            listening = listening,
            paused = paused,
            mode = mode,
            wordName = store.wordName,
            trained = me?.let { model.count(it) } ?: 0,
            fastTrained = me?.let { model.fastCount(it) } ?: 0,
            trainedPeople = model.trainedPeople(),
            training = training,
            fast = fast,
            soundsLike = soundsLike,
            error = error,
            revision = ++revision,
        )
    }

    fun close() {
        engine.stop()
        worker.shutdown()
    }
}
