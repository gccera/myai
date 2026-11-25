package com.gccera.myai.engine.stt

import com.gccera.myai.data.catalog.ToolKind
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.engine.ModelSessionManager
import com.gccera.myai.engine.requireTool
import javax.inject.Inject
import javax.inject.Singleton

/** Whisper transcription through the single-model session manager. */
@Singleton
class SpeechToText @Inject constructor(
    private val models: ModelRepository,
    private val sessions: ModelSessionManager,
    private val settings: SettingsRepository,
) {
    fun isAvailable() = models.toolModel(ToolKind.SPEECH) != null

    suspend fun transcribe(audio: FloatArray): String {
        if (audio.size < WhisperFeatures.SAMPLE_RATE / 4) return "" // < 0.25 s: nothing said
        val spec = models.requireTool(ToolKind.SPEECH)
        val lang = settings.current().speechLanguage.takeIf { it != "auto" }
        return sessions.withTool(spec, "Transcribing") { dir -> WhisperEngine(dir).use { it.transcribe(audio, lang).text } }
    }
}
