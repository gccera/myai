package com.gccera.myai.engine.llm

import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.settings.LlmBackend
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.random.Random

/** One prior message of the chat, oldest first. */
data class ChatTurn(val fromUser: Boolean, val text: String)

data class GenerationParams(
    val systemPrompt: String,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val maxTokens: Int,
)

/**
 * Thin wrapper over Google AI Edge **LiteRT-LM** (successor of the MediaPipe LLM Inference API).
 *
 * LiteRT-LM keeps the KV-cache inside a [Conversation]. We reuse one Conversation while the user keeps
 * chatting in the same thread (fast: only the new message is prefilled) and rebuild it from the Room
 * history whenever the history changed underneath (regenerate, delete, switching chats, new settings).
 */
class LlmEngine private constructor(
    private val engine: Engine,
    val spec: ModelSpec,
    val backendName: String,
) : AutoCloseable {

    private var conversation: Conversation? = null
    private var conversationKey: String? = null
    private var conversationTurns: Int = -1

    /**
     * Streams the reply to [userText] as text deltas. Cancelling the collector stops generation
     * natively (Stop button).
     */
    fun stream(
        sessionKey: String,
        history: List<ChatTurn>,
        userText: String,
        params: GenerationParams,
    ): Flow<String> = flow {
        val trimmed = trimHistory(history, userText, params)
        val key = "$sessionKey|$params"
        val reuse = conversation != null && conversationKey == key &&
            conversationTurns == history.size && trimmed.size == history.size
        val conv = if (reuse) conversation!! else {
            resetConversation()
            newConversation(params, trimmed).also {
                conversation = it; conversationKey = key
            }
        }
        val splitter = ChannelSplitter()
        var completed = false
        try {
            conv.sendMessageAsync(userText, repetitionPenaltyConfig = CHAT_PENALTY).collect { msg ->
                splitter.accept(msg).forEach { emit(it) }
            }
            splitter.finish().forEach { emit(it) }
            completed = true
            conversationTurns = history.size + 2
        } finally {
            if (!completed) {
                // Stopped or failed mid-reply: abort native work and rebuild state next time.
                runCatching { conv.cancelProcess() }
                resetConversation()
            }
        }
    }.flowOn(Dispatchers.Default)

    /** Single-turn completion in a throw-away conversation (used by "Improve my prompt"). */
    suspend fun complete(systemPrompt: String, prompt: String, params: GenerationParams): String {
        resetConversation()
        val conv = newConversation(params.copy(systemPrompt = systemPrompt), emptyList())
        return try {
            val splitter = ChannelSplitter()
            val sb = StringBuilder()
            conv.sendMessageAsync(prompt, repetitionPenaltyConfig = ONE_SHOT_PENALTY).collect { m ->
                splitter.accept(m).forEach(sb::append)
            }
            splitter.finish().forEach(sb::append)
            sb.toString()
        } finally {
            runCatching { conv.close() }
        }
    }

    private fun newConversation(params: GenerationParams, history: List<ChatTurn>): Conversation {
        val config = ConversationConfig(
            systemInstruction = params.systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
            initialMessages = history.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) },
            samplerConfig = SamplerConfig(
                topK = params.topK,
                topP = params.topP.toDouble(),
                temperature = params.temperature.toDouble(),
                seed = Random.nextInt(0, Int.MAX_VALUE),
            ),
            maxOutputToken = params.maxTokens,
            // Small reasoning models (e.g. Qwen3) spend most of their token budget "thinking" and,
            // on later turns, the parser can route the whole answer into the "thought" channel.
            // Direct answers are faster on a phone and parse reliably.
            thinkingConfig = ThinkingConfig(enableThinking = false),
        )
        return engine.createConversation(config)
    }

    /**
     * Drops the oldest turns so prompt + history + reply fit the model's context window.
     * Uses a conservative ~3.5 characters/token estimate (no tokenizer access needed).
     */
    private fun trimHistory(history: List<ChatTurn>, userText: String, params: GenerationParams): List<ChatTurn> {
        fun tokens(s: String) = (s.length / 3.5).toInt() + 4
        var budget = spec.contextTokens - params.maxTokens - tokens(params.systemPrompt) - tokens(userText) - 64
        val kept = ArrayDeque<ChatTurn>()
        for (turn in history.asReversed()) {
            budget -= tokens(turn.text)
            if (budget < 0) break
            kept.addFirst(turn)
        }
        // Conversations must start with a user turn.
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeFirst()
        return kept.toList()
    }

    private fun resetConversation() {
        runCatching { conversation?.close() }
        conversation = null
        conversationKey = null
        conversationTurns = -1
    }

    override fun close() {
        resetConversation()
        engine.close()
    }

    companion object {
        /** Blocking: loads the model file into memory (several seconds). Call off the main thread. */
        fun create(spec: ModelSpec, modelPath: String, backend: LlmBackend, cacheDir: String): LlmEngine {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = if (backend == LlmBackend.GPU) Backend.GPU() else Backend.CPU(),
                maxNumTokens = spec.contextTokens,
                cacheDir = cacheDir,
            )
            val engine = Engine(config)
            try {
                engine.initialize()
            } catch (t: Throwable) {
                runCatching { engine.close() }
                throw t
            }
            return LlmEngine(engine, spec, if (backend == LlmBackend.GPU) "LiteRT-LM GPU" else "LiteRT-LM CPU")
        }

        private val CHAT_PENALTY = RepetitionPenaltyConfig(repetitionPenalty = 1.05f)
        private val ONE_SHOT_PENALTY = RepetitionPenaltyConfig(repetitionPenalty = 1.15f)

        internal fun Message.plainText(): String =
            contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }
}

/**
 * Turns LiteRT-LM messages into text deltas. Answer text comes in `contents`; reasoning (if any)
 * arrives in `channels["thought"]` and is wrapped in <think>…</think> so the UI can show
 * "Thinking…" and hide it. If a model never produced answer text, its thought text is promoted to
 * the answer so the user never gets an empty reply.
 */
internal class ChannelSplitter {
    private val acc = StringBuilder()
    private val thought = StringBuilder()
    private var inThought = false
    private var sawAnswer = false

    fun accept(msg: Message): List<String> =
        accept(with(LlmEngine) { msg.plainText() }, msg.channels[THOUGHT])

    fun accept(text: String, thoughtDelta: String?): List<String> {
        val out = ArrayList<String>(2)
        thoughtDelta?.takeIf { it.isNotEmpty() }?.let { t ->
            if (!inThought) { out += "<think>"; inThought = true }
            thought.append(t); out += t
        }
        // The API emits deltas; be defensive in case a build emits the cumulative text.
        val delta = if (acc.isNotEmpty() && text.length > acc.length && text.startsWith(acc)) text.substring(acc.length) else text
        if (delta.isNotEmpty()) {
            if (inThought) { out += "</think>"; inThought = false }
            acc.append(delta); sawAnswer = sawAnswer || delta.isNotBlank()
            out += delta
        }
        return out
    }

    fun finish(): List<String> = buildList {
        if (inThought) add("</think>")
        if (!sawAnswer && thought.isNotBlank()) add(thought.toString().trim())
    }

    private companion object { const val THOUGHT = "thought" }
}
