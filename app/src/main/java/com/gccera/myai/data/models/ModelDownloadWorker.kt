package com.gccera.myai.data.models

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gccera.myai.MyAiApp
import com.gccera.myai.data.catalog.ModelCatalogRepository
import com.gccera.myai.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

/**
 * Downloads every file of one model, sequentially, as a foreground job.
 * Pause = WorkManager cancels this worker; `.part` files stay, so the next run resumes.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val catalog: ModelCatalogRepository,
    private val storage: ModelStorage,
    private val settings: SettingsRepository,
    okHttp: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    private val downloader = FileDownloader(okHttp)
    private var lastProgressAt = 0L

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val spec = catalog.find(modelId)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Unknown model $modelId"))
        val token = settings.current().hfToken.takeIf { it.isNotBlank() }
        val total = spec.totalBytes

        runCatching { setForeground(foregroundInfo(spec.name, 0, total, verifying = false)) }

        return try {
            var completedBefore = 0L
            for (file in spec.files) {
                val dest = storage.fileFor(spec, file)
                if (dest.exists() && (file.sizeBytes <= 0 || dest.length() == file.sizeBytes)) {
                    completedBefore += dest.length() // Already downloaded & verified earlier.
                    continue
                }
                val base = completedBefore
                downloader.download(
                    url = file.url,
                    dest = dest,
                    expectedSha256 = file.sha256,
                    authToken = token,
                    progress = { bytes -> report(spec.name, base + bytes, total, verifying = false) },
                    onVerifying = { report(spec.name, base + file.sizeBytes, total, verifying = true, force = true) },
                )
                completedBefore += dest.length()
            }
            storage.markInstalled(spec)
            Result.success()
        } catch (e: CancellationException) {
            throw e // Paused/cancelled: keep .part files for resume.
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun report(name: String, done: Long, total: Long, verifying: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressAt < 500) return // Throttle UI/notification updates.
        lastProgressAt = now
        setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_VERIFYING to verifying))
        setForegroundAsync(foregroundInfo(name, done, total, verifying))
    }

    private fun foregroundInfo(name: String, done: Long, total: Long, verifying: Boolean): ForegroundInfo {
        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        val n: Notification = NotificationCompat.Builder(applicationContext, MyAiApp.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (verifying) "Verifying $name" else "Downloading $name")
            .setContentText(if (verifying) "Checking SHA-256…" else "$pct%")
            .setProgress(100, pct, verifying)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val notifId = 1000 + (id.hashCode() and 0xFFFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(notifId, n)
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
        const val TAG = "model-download"
        fun uniqueName(modelId: String) = "download-$modelId"
        fun modelTag(modelId: String) = "model:$modelId"
    }
}
