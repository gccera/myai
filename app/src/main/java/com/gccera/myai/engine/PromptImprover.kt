package com.gccera.myai.engine

import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.engine.llm.GenerationParams
import com.gccera.myai.safety.ContentFilter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Improve my prompt": asks the on-device chat model to rewrite a short idea into a detailed
 * Stable Diffusion prompt. The chat model is unloaded automatically when image generation starts
 * (see [ModelSessionManager]).
 */
@Singleton
class PromptImprover @Inject constructor(
    private val sessions: ModelSessionManager,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val filter: ContentFilter,
) {
    suspend fun improve(prompt: String): String {
        require(prompt.isNotBlank()) { "Type a prompt first." }
        if (filter.check(prompt).blocked) throw IllegalArgumentException(ContentFilter.BLOCKED_PROMPT_MESSAGE)
        val spec = models.activeModel(ModelType.CHAT)
            ?: throw IllegalStateException("Download a chat model to use prompt improvement.")
        val s = settings.current()
        val raw = sessions.withChat(spec, "Prompt improvement") { engine ->
            engine.complete(
                systemPrompt = SYSTEM,
                prompt = "Idea: ${prompt.trim()}\nPrompt:",
                params = GenerationParams(SYSTEM, temperature = 0.7f, topK = s.topK, topP = s.topP, maxTokens = 160),
            )
        }
        val cleaned = cleanup(raw)
        if (cleaned.isBlank()) throw IllegalStateException("The model returned an empty prompt. Try again.")
        if (filter.check(cleaned).blocked) throw IllegalStateException(ContentFilter.BLOCKED_PROMPT_MESSAGE)
        return cleaned
    }

    companion object {
        private const val SYSTEM =
            "You write prompts for the Stable Diffusion image model. Rewrite the user's idea as ONE " +
                "vivid, comma-separated prompt under 60 words: subject, setting, style, lighting, camera " +
                "or medium, and quality tags. Output only the prompt — no quotes, no explanations."

        /** Removes reasoning blocks, labels and quotes some small models add. */
        fun cleanup(raw: String): String = raw
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)<think>.*"), "")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(" ")
            .removePrefix("Prompt:").trim()
            .trim('"', '\'', '`', ' ')
            .take(600)
    }
}
