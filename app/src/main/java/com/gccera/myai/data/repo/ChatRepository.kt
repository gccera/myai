package com.gccera.myai.data.repo

import com.gccera.myai.data.db.ChatDao
import com.gccera.myai.data.db.ConversationEntity
import com.gccera.myai.data.db.MessageEntity
import com.gccera.myai.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val dao: ChatDao,
    private val settings: SettingsRepository,
) {
    val conversations = dao.observeConversations()
    fun conversation(id: Long) = dao.observeConversation(id)
    fun messages(conversationId: Long) = dao.observeMessages(conversationId)

    suspend fun getConversation(id: Long) = dao.getConversation(id)
    suspend fun latestConversationId(): Long? = dao.latestConversation()?.id
    suspend fun getMessages(conversationId: Long) = dao.getMessages(conversationId)

    suspend fun createConversation(): Long {
        val s = settings.current()
        val now = System.currentTimeMillis()
        return dao.insertConversation(ConversationEntity(
            title = "New chat", createdAt = now, updatedAt = now,
            systemPrompt = s.defaultSystemPrompt, temperature = s.temperature, maxTokens = s.maxTokens,
        ))
    }

    suspend fun updateConversation(c: ConversationEntity) = dao.updateConversation(c)

    suspend fun touch(id: Long, titleFrom: String? = null) {
        val c = dao.getConversation(id) ?: return
        val title = if (c.title == "New chat" && !titleFrom.isNullOrBlank()) {
            titleFrom.lines().first().trim().take(40).ifBlank { c.title }
        } else c.title
        dao.updateConversation(c.copy(updatedAt = System.currentTimeMillis(), title = title))
    }

    suspend fun addMessage(m: MessageEntity): Long = dao.insertMessage(m)
    suspend fun updateMessage(id: Long, content: String, status: String) = dao.updateMessageContent(id, content, status)
    suspend fun deleteMessage(id: Long) = dao.deleteMessage(id)
    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)
    suspend fun deleteAll() = dao.deleteAllConversations()
    suspend fun fixInterrupted() = dao.fixInterruptedMessages()
}
