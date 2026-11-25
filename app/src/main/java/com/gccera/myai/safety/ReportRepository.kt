package com.gccera.myai.safety

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.gccera.myai.R
import com.gccera.myai.data.db.ChatDao
import com.gccera.myai.data.db.ImageDao
import com.gccera.myai.data.db.ReportDao
import com.gccera.myai.data.db.ReportEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class ReportReason(val label: String) {
    SEXUAL("Sexual or explicit content"),
    VIOLENT("Violent or graphic content"),
    HATEFUL("Hateful or harassing content"),
    DANGEROUS("Dangerous or illegal activity"),
    MISINFORMATION("False or misleading information"),
    OTHER("Other"),
}

/**
 * Stores reports on device and hides the reported content. Optionally builds an e-mail intent the
 * user can review and send (nothing is sent automatically — there is no server).
 */
@Singleton
class ReportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reportDao: ReportDao,
    private val chatDao: ChatDao,
    private val imageDao: ImageDao,
) {
    val count = reportDao.observeCount()

    suspend fun reportMessage(messageId: Long, reason: ReportReason, note: String) {
        val m = chatDao.getMessage(messageId) ?: return
        reportDao.insert(ReportEntity(
            contentType = "chat", contentId = m.id, reason = reason.name, note = note,
            excerpt = m.content.take(500), modelId = m.modelId, createdAt = System.currentTimeMillis(),
        ))
        chatDao.updateMessage(m.copy(reported = true))
    }

    suspend fun reportImage(imageId: Long, reason: ReportReason, note: String) {
        val img = imageDao.get(imageId) ?: return
        reportDao.insert(ReportEntity(
            contentType = "image", contentId = img.id, reason = reason.name, note = note,
            excerpt = img.prompt.take(500), modelId = img.modelId, createdAt = System.currentTimeMillis(),
        ))
        imageDao.update(img.copy(reported = true))
    }

    /** Pre-filled e-mail (text only, no image attached) that the user may choose to send. */
    fun emailIntent(contentType: String, reason: ReportReason, note: String, excerpt: String, modelId: String?): Intent {
        val body = buildString {
            appendLine("Content type: $contentType")
            appendLine("Reason: ${reason.label}")
            appendLine("Model: ${modelId ?: "unknown"}")
            if (note.isNotBlank()) appendLine("Note: $note")
            appendLine()
            appendLine("Excerpt / prompt:")
            appendLine(excerpt.take(1000))
        }
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(context.getString(R.string.report_email)))
            putExtra(Intent.EXTRA_SUBJECT, "${context.getString(R.string.app_name)} content report")
            putExtra(Intent.EXTRA_TEXT, body)
        }
    }

    suspend fun clear() = reportDao.deleteAll()
}
