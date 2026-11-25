package com.gccera.myai.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestConversation(): ConversationEntity?

    @Insert suspend fun insertConversation(c: ConversationEntity): Long
    @Update suspend fun updateConversation(c: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessage(id: Long): MessageEntity?

    @Insert suspend fun insertMessage(m: MessageEntity): Long
    @Update suspend fun updateMessage(m: MessageEntity)

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String, status: String)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    /** Messages left in "streaming" state by a crash/kill are marked stopped on next launch. */
    @Query("UPDATE messages SET status = 'stopped' WHERE status = 'streaming'")
    suspend fun fixInterruptedMessages()
}

@Dao
interface ImageDao {
    @Query("SELECT * FROM images ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun get(id: Long): ImageEntity?

    @Query("SELECT * FROM images WHERE id = :id")
    fun observe(id: Long): Flow<ImageEntity?>

    @Insert suspend fun insert(img: ImageEntity): Long
    @Update suspend fun update(img: ImageEntity)

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM images")
    suspend fun all(): List<ImageEntity>

    @Query("DELETE FROM images")
    suspend fun deleteAll()
}

@Dao
interface ReportDao {
    @Insert suspend fun insert(r: ReportEntity): Long

    @Query("SELECT COUNT(*) FROM reports")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM reports")
    suspend fun deleteAll()
}
