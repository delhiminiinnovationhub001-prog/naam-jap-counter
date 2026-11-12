package com.naamjap.counter.bhakti

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Reads text aloud with the phone's own text-to-speech in Hindi (works
 * offline once the Hindi voice is installed). The Hindi voice also reads the
 * Sanskrit verses, which are written in the same Devanagari script.
 */
class Speaker(context: Context) {

    enum class State { Starting, Ready, NoHindi, Failed }

    private val _state = MutableStateFlow(State.Starting)
    val state: StateFlow<State> = _state
    private val _speaking = MutableStateFlow<String?>(null)   // id of what is being read
    val speaking: StateFlow<String?> = _speaking
    private var onDone: ((String) -> Unit)? = null
    var rate = 0.85f

    private lateinit var tts: TextToSpeech

    init {
        // The engine answers later on the main thread, after tts is set.
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                _state.value = State.Failed
            } else {
                val r = runCatching { tts.setLanguage(Locale("hi", "IN")) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                _state.value = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) State.NoHindi else State.Ready
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                // Ids are "<item>#<part>"; the last part ends the item.
                if (utteranceId.endsWith("#end")) {
                    val item = utteranceId.removeSuffix("#end")
                    _speaking.value = null
                    onDone?.invoke(item)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { _speaking.value = null }
        })
    }

    /** Reads [parts] one after another with a short pause between; [onFinished] gets [id] at the end. */
    fun speak(id: String, parts: List<String>, onFinished: ((String) -> Unit)? = null) {
        if (_state.value != State.Ready) return
        onDone = onFinished
        tts.stop()
        tts.setSpeechRate(rate)
        _speaking.value = id
        val clean = parts.map { Devotional.forSpeech(it) }.filter { it.isNotBlank() }
        clean.forEachIndexed { i, p ->
            if (i > 0) tts.playSilentUtterance(700, TextToSpeech.QUEUE_ADD, "$id#gap$i")
            tts.speak(p, TextToSpeech.QUEUE_ADD, null, if (i == clean.lastIndex) "$id#end" else "$id#$i")
        }
    }

    fun stop() {
        onDone = null
        tts.stop()
        _speaking.value = null
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    companion object {
        /** Opens the phone's text-to-speech settings, to install the Hindi voice. */
        fun installVoiceIntent() = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
