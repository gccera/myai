package com.gccera.myai.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Per-conversation generation settings (defaults copied from global Settings). */
    val systemPrompt: String,
    val temperature: Float,
    val maxTokens: Int,
)

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

object MessageStatus {
    const val COMPLETE = "complete"
    const val STREAMING = "streaming"
    const val STOPPED = "stopped"
    const val ERROR = "error"
    const val BLOCKED = "blocked"
}

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String = MessageStatus.COMPLETE,
    val modelId: String? = null,
    /** True once the user reported this reply; it is then hidden in the UI. */
    val reported: Boolean = false,
)

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val prompt: String,
    val negativePrompt: String,
    val seed: Long,
    val steps: Int,
    val guidance: Float,
    val width: Int,
    val height: Int,
    val modelId: String,
    val createdAt: Long,
    val durationMs: Long,
    val reported: Boolean = false,
)

/** In-app content reports (Google Play AI-generated content policy). Stored only on device. */
@Entity(tableName = "reports")
data class ReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentType: String, // "chat" | "image"
    val contentId: Long,
    val reason: String,
    val note: String,
    val excerpt: String,
    val modelId: String?,
    val createdAt: Long,
)
