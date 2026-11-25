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
    /** Imported document this chat answers questions about (RAG), if any. */
    val documentId: Long? = null,
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
    /** Photo attached to a user message (vision models). App-private file path. */
    val imagePath: String? = null,
    /** Document excerpts used to answer (RAG), shown under the reply. */
    val sources: String? = null,
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

/** A PDF/TXT/MD file imported for "chat with documents". */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val chunkCount: Int,
    val charCount: Int,
)

/** One searchable passage of a document with its MiniLM embedding (384 float32, little-endian). */
@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val idx: Int,
    val text: String,
    val embedding: ByteArray,
) {
    override fun equals(other: Any?) = other is ChunkEntity && other.id == id
    override fun hashCode() = id.hashCode()
}
