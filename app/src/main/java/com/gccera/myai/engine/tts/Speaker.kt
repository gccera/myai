package com.gccera.myai.engine.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** "Read aloud" with Android's built-in TextToSpeech (offline voices where the engine provides them). */
@Singleton
class Speaker @Inject constructor(@ApplicationContext private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<Long, String>? = null

    /** Id of the message being read, or null. */
    private val _speaking = MutableStateFlow<Long?>(null)
    val speaking = _speaking.asStateFlow()

    fun speak(id: Long, markdown: String) {
        val text = plain(markdown)
        if (tts == null) TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            pending?.let { (pid, t) -> if (ready) say(pid, t) }
            pending = null
        }.also {
            tts = it
            it.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId?.endsWith("#last") == true) _speaking.value = null }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { _speaking.value = null }
            })
        }
        if (ready) say(id, text) else pending = id to text
        _speaking.value = id
    }

    private fun say(id: Long, text: String) {
        val t = tts ?: return
        t.stop()
        // TTS engines limit utterance length (~4000 chars): speak in sentence-sized pieces.
        val parts = text.split(Regex("(?<=[.!?])\\s+")).flatMap { it.chunked(3500) }.filter { it.isNotBlank() }
        parts.forEachIndexed { i, p ->
            val uid = "$id-$i" + if (i == parts.lastIndex) "#last" else ""
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, uid)
        }
        if (parts.isEmpty()) _speaking.value = null
    }

    fun stop() { tts?.stop(); _speaking.value = null }

    companion object {
        /** Strips Markdown syntax and code blocks so the voice doesn't read symbols. */
        fun plain(md: String): String = md
            .replace(Regex("(?s)```.*?```"), " (code block) ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s+"), "")
            .replace(Regex("[*_~]{1,3}"), "")
            .replace(Regex("\\s+"), " ").trim()
    }
}
