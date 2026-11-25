package com.gccera.myai.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.gccera.myai.data.catalog.ToolKind
import com.gccera.myai.data.db.ChunkEntity
import com.gccera.myai.data.db.DocumentDao
import com.gccera.myai.data.db.DocumentEntity
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.engine.ModelSessionManager
import com.gccera.myai.engine.rag.EmbeddingEngine
import com.gccera.myai.engine.rag.TextChunker
import com.gccera.myai.engine.requireTool
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

data class RetrievedChunk(val documentName: String, val index: Int, val text: String, val score: Float)

/**
 * "Chat with documents": imports PDF/TXT/MD files, splits them into passages, embeds them with
 * MiniLM on-device and retrieves the most relevant passages for a question (cosine similarity).
 * Everything stays in the app's private database.
 */
@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DocumentDao,
    private val models: ModelRepository,
    private val sessions: ModelSessionManager,
) {
    val documents = dao.observeAll()

    suspend fun import(uri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): DocumentEntity {
        val spec = models.requireTool(ToolKind.EMBEDDING)
        val name = displayName(uri)
        val text = withContext(Dispatchers.IO) { extractText(uri, name) }
        require(text.isNotBlank()) { "No text found in $name (scanned PDFs need OCR first: Tools → Extract text)." }
        val chunks = TextChunker.chunk(text)
        val vectors = sessions.withTool(spec, "Indexing $name") { dir ->
            EmbeddingEngine(dir).use { e -> chunks.mapIndexed { i, c -> onProgress(i + 1, chunks.size); e.embed(c) } }
        }
        val doc = DocumentEntity(name = name, createdAt = System.currentTimeMillis(), chunkCount = chunks.size, charCount = text.length)
        val id = dao.insert(doc)
        dao.insertChunks(chunks.mapIndexed { i, c -> ChunkEntity(documentId = id, idx = i, text = c, embedding = vectors[i].toBytes()) })
        return doc.copy(id = id)
    }

    suspend fun search(documentId: Long, query: String, k: Int = 4): List<RetrievedChunk> {
        val doc = dao.get(documentId) ?: return emptyList()
        val spec = models.requireTool(ToolKind.EMBEDDING)
        val q = sessions.withTool(spec, "Searching ${doc.name}") { dir -> EmbeddingEngine(dir).use { it.embed(query) } }
        return dao.chunks(documentId)
            .map { RetrievedChunk(doc.name, it.idx, it.text, EmbeddingEngine.cosine(q, it.embedding.toFloats())) }
            .sortedByDescending { it.score }
            .take(k)
            .sortedBy { it.index }
    }

    suspend fun get(id: Long) = dao.get(id)

    suspend fun delete(id: Long) { dao.detachFromConversations(id); dao.delete(id) }
    suspend fun deleteAll() = dao.deleteAll()

    private fun extractText(uri: Uri, name: String): String {
        val mime = context.contentResolver.getType(uri) ?: ""
        return context.contentResolver.openInputStream(uri)!!.use { input ->
            if (mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)) {
                PDFBoxResourceLoader.init(context)
                PDDocument.load(input).use { PDFTextStripper().getText(it) }
            } else input.bufferedReader().readText()
        }
    }

    private fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "document"

    companion object {
        fun FloatArray.toBytes(): ByteArray =
            ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> forEach { b.putFloat(it) } }.array()

        fun ByteArray.toFloats(): FloatArray {
            val fb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }

        /** Builds the prompt that grounds the model's answer in the retrieved passages. */
        fun groundedPrompt(question: String, chunks: List<RetrievedChunk>): String = buildString {
            appendLine("Answer the question using the excerpts from \"${chunks.firstOrNull()?.documentName}\" below. ")
            appendLine("If the answer is not in the excerpts, say so. Cite excerpts like [1].")
            appendLine()
            chunks.forEachIndexed { i, c -> appendLine("[${i + 1}] ${c.text}"); appendLine() }
            append("Question: ").append(question)
        }
    }
}
