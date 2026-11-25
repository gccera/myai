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
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.gccera.myai.data.repo.DocumentRepository
import com.gccera.myai.engine.stt.SpeechToText
import com.gccera.myai.engine.stt.VoiceRecorder
import com.gccera.myai.engine.tts.Speaker
import com.gccera.myai.util.Attachments
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Recording : VoiceState
    data object Transcribing : VoiceState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chats: ChatRepository,
    private val models: ModelRepository,
    private val sessions: ModelSessionManager,
    private val settings: SettingsRepository,
    private val filter: ContentFilter,
    private val reports: ReportRepository,
    private val documents: DocumentRepository,
    private val stt: SpeechToText,
    val speaker: Speaker,
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

    // --- Phase 2: attachments, documents, voice ---
    /** Photo waiting to be sent with the next message (app-private JPEG path). */
    private val _pendingImage = MutableStateFlow<String?>(null)
    val pendingImage = _pendingImage.asStateFlow()

    val allDocuments = documents.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Document attached to the current conversation (RAG). */
    val attachedDocument = conversation.map { c -> c?.documentId?.let { documents.get(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _indexing = MutableStateFlow<String?>(null)
    val indexing = _indexing.asStateFlow()

    private val _voice = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voice = _voice.asStateFlow()
    private val recorder = VoiceRecorder()
    /** Transcripts to append to the input field. */
    private val _transcripts = Channel<String>(Channel.BUFFERED)
    val transcripts = _transcripts.receiveAsFlow()

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

    fun attachImage(uri: Uri) = viewModelScope.launch {
        val spec = models.activeModel(ModelType.CHAT)
        if (spec?.vision != true) { _events.send("The active chat model can't see images. Use Gemma 4 E2B (Models → Chat)."); return@launch }
        runCatching { _pendingImage.value = Attachments.saveImage(context, uri) }
            .onFailure { _events.send("Couldn't read that image: ${it.message}") }
    }

    fun clearImage() { _pendingImage.value = null }

    fun importDocument(uri: Uri) = viewModelScope.launch {
        _indexing.value = "Reading document…"
        try {
            val doc = documents.import(uri) { done, total -> _indexing.value = "Indexing $done / $total passages…" }
            attachDocument(doc.id)
            _events.send("${doc.name} is ready: ask questions about it in this chat.")
        } catch (e: Exception) {
            _events.send(e.message ?: "Import failed")
        } finally { _indexing.value = null }
    }

    fun attachDocument(id: Long?) = viewModelScope.launch {
        val convId = _conversationId.value ?: chats.createConversation().also { _conversationId.value = it }
        chats.getConversation(convId)?.let { chats.updateConversation(it.copy(documentId = id)) }
    }

    fun deleteDocument(id: Long) = viewModelScope.launch { documents.delete(id) }

    @SuppressLint("MissingPermission") // the screen requests RECORD_AUDIO before calling this
    fun startVoice() {
        if (_voice.value != VoiceState.Idle) return
        if (!stt.isAvailable()) { viewModelScope.launch { _events.send("Download \"Whisper Base\" first: Settings → Models → Tools.") }; return }
        viewModelScope.launch {
            _voice.value = VoiceState.Recording
            try {
                val audio = recorder.record()
                _voice.value = VoiceState.Transcribing
                val text = stt.transcribe(audio)
                if (text.isNotBlank()) _transcripts.send(text) else _events.send("Didn't catch that. Try again.")
            } catch (e: Exception) {
                _events.send(e.message ?: "Voice input failed")
            } finally { _voice.value = VoiceState.Idle }
        }
    }

    fun stopVoice() = recorder.stop()

    fun speak(m: MessageEntity) { if (speaker.speaking.value == m.id) speaker.stop() else speaker.speak(m.id, m.content) }

    override fun onCleared() { speaker.stop(); recorder.stop() }

    fun send(text: String) {
        val prompt = text.trim()
        val image = _pendingImage.value
        if ((prompt.isEmpty() && image == null) || isGenerating) return
        if (filter.check(prompt).blocked) {
            viewModelScope.launch { _events.send(ContentFilter.BLOCKED_PROMPT_MESSAGE) }
            return
        }
        job = viewModelScope.launch {
            val convId = _conversationId.value ?: chats.createConversation().also { _conversationId.value = it }
            val history = historyOf(chats.getMessages(convId))
            val userText = prompt.ifEmpty { "Describe this image." }
            chats.addMessage(MessageEntity(conversationId = convId, role = Role.USER, content = userText, createdAt = now(), imagePath = image))
            _pendingImage.value = null
            chats.touch(convId, titleFrom = userText)
            generate(convId, history, userText, image)
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
            generate(convId, historyOf(all.take(userIdx)), all[userIdx].content, all[userIdx].imagePath)
        }
    }

    private suspend fun generate(convId: Long, history: List<ChatTurn>, prompt: String, imagePath: String? = null) {
        val spec = models.activeModel(ModelType.CHAT)
        if (spec == null) { _events.send("Download a chat model first (Models tab)."); return }
        val conv = chats.getConversation(convId) ?: return
        val s = settings.current()
        // Leave at least half of the context window for the conversation itself.
        val params = GenerationParams(conv.systemPrompt, conv.temperature, s.topK, s.topP, conv.maxTokens.coerceAtMost(spec.contextTokens / 2))
        // Chat with documents: ground the question in the most relevant passages.
        var modelPrompt = prompt
        var sources: String? = null
        conv.documentId?.let { docId ->
            try {
                _indexing.value = "Searching document…"
                val hits = documents.search(docId, prompt)
                if (hits.isNotEmpty()) {
                    modelPrompt = DocumentRepository.groundedPrompt(prompt, hits)
                    sources = hits.mapIndexed { i, h -> "[${i + 1}] ${h.text.replace('\n', ' ').take(180)}…" }.joinToString("\n")
                }
            } catch (e: Exception) {
                _events.send(e.message ?: "Document search failed")
            } finally { _indexing.value = null }
        }
        val imageBytes = imagePath?.let { p -> runCatching { java.io.File(p).readBytes() }.getOrNull() }
        if (imageBytes != null && !spec.vision) _events.send("${spec.name} can't see images; only the text was sent.")
        val msgId = chats.addMessage(MessageEntity(
            conversationId = convId, role = Role.ASSISTANT, content = "", createdAt = now(),
            status = MessageStatus.STREAMING, modelId = spec.id, sources = sources,
        ))
        _streaming.value = Streaming(msgId, "")
        val raw = StringBuilder()
        var status = MessageStatus.COMPLETE
        var lastSave = 0L
        try {
            sessions.withChat(spec, "Chat reply") { engine ->
                engine.stream("conv-$convId", history, modelPrompt, params, image = imageBytes).collect { delta ->
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
