package com.gccera.myai.engine.code

import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.engine.ModelSessionManager
import com.gccera.myai.engine.llm.GenerationParams
import com.gccera.myai.util.ThinkTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

enum class CodeAction(val label: String, val editsCode: Boolean) {
    EXPLAIN("Explain", false),
    FIX("Fix error", true),
    COMMENT("Add comments", true),
    TESTS("Write tests", false),
    REFACTOR("Refactor", true),
    ASK("Ask", false),
}

/**
 * The Code tab's AI assistant. Runs on the on-device coding model by default, or — only if the user
 * enabled it in Settings and ticked "Use cloud" — on an OpenAI-compatible endpoint.
 */
@Singleton
class CodeAssistant @Inject constructor(
    private val models: ModelRepository,
    private val sessions: ModelSessionManager,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val http: OkHttpClient,
) {
    suspend fun cloudAvailable(): Boolean = settings.current().cloudEnabled && !secrets.get(SecretStore.CLOUD_API_KEY).isNullOrBlank()

    suspend fun cloudHost(): String = CloudClient.host(settings.current().cloudBaseUrl)

    fun run(action: CodeAction, language: String, fileName: String, code: String, isSelection: Boolean, extra: String, useCloud: Boolean): Flow<String> = channelFlow {
        val (system, user) = prompt(action, language, fileName, code, isSelection, extra)
        if (useCloud) {
            val s = settings.current()
            val key = secrets.get(SecretStore.CLOUD_API_KEY)
            require(s.cloudEnabled && !key.isNullOrBlank()) { "Cloud model is off. Enable it in Settings → Cloud model." }
            CloudClient(http, s.cloudBaseUrl, key, s.cloudModel).stream(system, user).collect { send(it) }
        } else {
            val spec = models.activeModel(ModelType.CODE) ?: models.activeModel(ModelType.CHAT)
                ?: throw IllegalStateException("Download a coding model first (Settings → Models → Code).")
            val params = GenerationParams(system, temperature = 0.2f, topK = 40, topP = 0.95f, maxTokens = minOf(1536, spec.contextTokens / 2), repetitionPenalty = null)
            sessions.withChat(spec, "Code assistant") { engine ->
                // Fresh conversation per request so long files don't accumulate in the context.
                engine.stream("code-${System.nanoTime()}", emptyList(), user, params).collect { send(it) }
            }
        }
    }

    suspend fun complete(prefix: String, suffix: String, language: String): String {
        val spec = models.activeModel(ModelType.CODE) ?: return ""
        return sessions.withChat(spec, "Autocomplete") { it.completeCode(prefix, suffix, language) }
    }

    companion object {
        const val SYSTEM = "You are an expert software engineer helping on a phone. Be concise and precise."

        fun prompt(action: CodeAction, language: String, fileName: String, code: String, isSelection: Boolean, extra: String): Pair<String, String> {
            val what = if (isSelection) "this $language code from $fileName" else "the $language file $fileName"
            val block = "```${language.lowercase()}\n${code.take(12_000)}\n```"
            val editRule = "Reply with the COMPLETE updated code in ONE ```${language.lowercase()} code block, then at most 3 short bullet points about what changed."
            val user = when (action) {
                CodeAction.EXPLAIN -> "Explain $what step by step for a developer.\n\n$block"
                CodeAction.FIX -> "Fix the bug(s) in $what." + (if (extra.isNotBlank()) "\nThe error is:\n$extra" else "") + "\n$editRule\n\n$block"
                CodeAction.COMMENT -> "Add clear comments/docstrings to $what without changing behaviour. $editRule\n\n$block"
                CodeAction.TESTS -> "Write unit tests for $what using the standard test framework for $language. Put the tests in ONE code block.\n\n$block"
                CodeAction.REFACTOR -> "Refactor $what for readability and best practices without changing behaviour." +
                    (if (extra.isNotBlank()) " Focus: $extra." else "") + " $editRule\n\n$block"
                CodeAction.ASK -> "$extra\n\nContext ($what):\n$block"
            }
            return SYSTEM to user
        }

        /** First fenced code block of a reply (or null). */
        fun extractCode(reply: String): String? {
            val text = ThinkTags.strip(reply)
            val m = Regex("(?s)```[^\\n`]*\\n(.*?)```").find(text) ?: return null
            return m.groupValues[1].trimEnd('\n')
        }
    }
}
