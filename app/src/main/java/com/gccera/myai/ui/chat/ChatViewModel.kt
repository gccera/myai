package com.gccera.myai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.db.ConversationEntity
import com.gccera.myai.data.db.MessageEntity
import com.gccera.myai.data.db.MessageStatus
import com.gccera.myai.data.db.Role
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.repo.ChatRepository
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.engine.ModelSessionManager
import com.gccera.myai.engine.llm.ChatTurn
import com.gccera.myai.engine.llm.GenerationParams
import com.gccera.myai.safety.ContentFilter
import com.gccera.myai.safety.ReportReason
import com.gccera.myai.safety.ReportRepository
import com.gccera.myai.util.ThinkTags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** A reply currently being streamed (kept in memory; written to Room periodically and at the end). */
data class Streaming(val messageId: Long, val text: String)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chats: ChatRepository,
    private val models: ModelRepository,
    private val sessions: ModelSessionManager,
    private val settings: SettingsRepository,
    private val filter: ContentFilter,
    private val reports: ReportRepository,
) : ViewModel() {

    private val _conversationId = MutableStateFlow<Long?>(null)
    val conversationId: StateFlow<Long?> = _conversationId.asStateFlow()

    val conversations = chats.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversation: StateFlow<ConversationEntity?> = _conversationId
        .flatMapLatest { id -> if (id == null) flowOf(null) else chats.conversation(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<MessageEntity>> = _conversationId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chats.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _streaming = MutableStateFlow<Streaming?>(null)
    val streaming = _streaming.asStateFlow()

    val session = sessions.state

    /** Name of the active chat model (null = none installed). */
    val activeModel: StateFlow<ModelSpec?> = combineActive()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var job: Job? = null
    val isGenerating get() = job?.isActive == true

    init {
        viewModelScope.launch {
            chats.fixInterrupted()
            _conversationId.value = chats.latestConversationId()
        }
    }

    private fun combineActive(): StateFlow<ModelSpec?> =
        models.entries.map { models.activeModel(ModelType.CHAT) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun newChat() {
        if (isGenerating) return
        viewModelScope.launch {
            // Reuse an existing empty chat instead of piling up blank ones.
            val current = _conversationId.value
            if (current != null && chats.getMessages(current).isEmpty()) return@launch
            _conversationId.value = chats.createConversation()
        }
    }

    fun selectConversation(id: Long) { if (!isGenerating) _conversationId.value = id }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        if (isGenerating && id == _conversationId.value) stop()
        chats.deleteConversation(id)
        if (_conversationId.value == id) _conversationId.value = chats.latestConversationId()
    }

    fun deleteAllConversations() = viewModelScope.launch {
        stop(); chats.deleteAll(); _conversationId.value = null
    }

    fun updateConversationSettings(systemPrompt: String, temperature: Float, maxTokens: Int, title: String) =
        viewModelScope.launch {
            val c = conversation.value ?: return@launch
            chats.updateConversation(c.copy(
                systemPrompt = systemPrompt, temperature = temperature, maxTokens = maxTokens,
                title = title.ifBlank { c.title },
            ))
        }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || isGenerating) return
        if (filter.check(prompt).blocked) {
            viewModelScope.launch { _events.send(ContentFilter.BLOCKED_PROMPT_MESSAGE) }
            return
        }
        job = viewModelScope.launch {
            val convId = _conversationId.value ?: chats.createConversation().also { _conversationId.value = it }
            val history = historyOf(chats.getMessages(convId))
            chats.addMessage(MessageEntity(conversationId = convId, role = Role.USER, content = prompt, createdAt = now()))
            chats.touch(convId, titleFrom = prompt)
            generate(convId, history, prompt)
        }
    }

    /** Re-runs the last user message, replacing the selected (last) assistant reply. */
    fun regenerate(assistantMessageId: Long) {
        if (isGenerating) return
        job = viewModelScope.launch {
            val convId = _conversationId.value ?: return@launch
            val all = chats.getMessages(convId)
            val idx = all.indexOfFirst { it.id == assistantMessageId }
            if (idx <= 0) return@launch
            val userIdx = (idx - 1 downTo 0).firstOrNull { all[it].role == Role.USER } ?: return@launch
            // Drop this reply and everything after it, then answer the same question again.
            all.drop(idx).forEach { chats.deleteMessage(it.id) }
            generate(convId, historyOf(all.take(userIdx)), all[userIdx].content)
        }
    }

    private suspend fun generate(convId: Long, history: List<ChatTurn>, prompt: String) {
        val spec = models.activeModel(ModelType.CHAT)
        if (spec == null) { _events.send("Download a chat model first (Models tab)."); return }
        val conv = chats.getConversation(convId) ?: return
        val s = settings.current()
        // Leave at least half of the context window for the conversation itself.
        val params = GenerationParams(conv.systemPrompt, conv.temperature, s.topK, s.topP, conv.maxTokens.coerceAtMost(spec.contextTokens / 2))
        val msgId = chats.addMessage(MessageEntity(
            conversationId = convId, role = Role.ASSISTANT, content = "", createdAt = now(),
            status = MessageStatus.STREAMING, modelId = spec.id,
        ))
        _streaming.value = Streaming(msgId, "")
        val raw = StringBuilder()
        var status = MessageStatus.COMPLETE
        var lastSave = 0L
        try {
            sessions.withChat(spec, "Chat reply") { engine ->
                engine.stream("conv-$convId", history, prompt, params).collect { delta ->
                    raw.append(delta)
                    val visible = ThinkTags.strip(raw.toString())
                    if (filter.check(visible).blocked) {
                        status = MessageStatus.BLOCKED
                        throw CancellationException("blocked")
                    }
                    _streaming.value = Streaming(msgId, raw.toString())
                    if (now() - lastSave > 1500) { lastSave = now(); chats.updateMessage(msgId, visible, MessageStatus.STREAMING) }
                }
            }
        } catch (e: CancellationException) {
            if (status != MessageStatus.BLOCKED) status = MessageStatus.STOPPED
        } catch (e: Exception) {
            status = MessageStatus.ERROR
            _events.send(e.message ?: "Generation failed")
        } finally {
            withContext(NonCancellable) {
                val final = when (status) {
                    MessageStatus.BLOCKED -> ContentFilter.BLOCKED_REPLY_MESSAGE
                    MessageStatus.ERROR -> ThinkTags.strip(raw.toString()).ifBlank { "_Error: could not generate a reply._" }
                    else -> ThinkTags.strip(raw.toString()).trim()
                }
                chats.updateMessage(msgId, final, status)
                chats.touch(convId)
                _streaming.value = null
            }
        }
    }

    fun stop() { job?.cancel() }

    fun deleteMessage(id: Long) = viewModelScope.launch { if (!isGenerating) chats.deleteMessage(id) }

    fun report(messageId: Long, reason: ReportReason, note: String) = viewModelScope.launch {
        reports.reportMessage(messageId, reason, note)
        _events.send("Thanks — the reply was reported and hidden.")
    }

    fun reportEmailIntent(m: MessageEntity, reason: ReportReason, note: String) =
        reports.emailIntent("chat reply", reason, note, m.content, m.modelId)

    /** Only finished, unreported, non-blocked turns are fed back to the model as context. */
    private fun historyOf(messages: List<MessageEntity>): List<ChatTurn> = messages
        .filter { !it.reported && it.content.isNotBlank() && it.status in setOf(MessageStatus.COMPLETE, MessageStatus.STOPPED) }
        .map { ChatTurn(it.role == Role.USER, it.content) }

    private fun now() = System.currentTimeMillis()
}
