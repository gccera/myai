package com.gccera.myai.data.models

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.gccera.myai.data.catalog.ModelCatalogRepository
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.device.DeviceInspector
import com.gccera.myai.engine.ModelSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    data object Queued : DownloadState
    data class Downloading(val done: Long, val total: Long) : DownloadState
    data class Verifying(val total: Long) : DownloadState
    data class Paused(val done: Long, val total: Long) : DownloadState
    data class Failed(val message: String, val done: Long, val total: Long) : DownloadState
    data object Installed : DownloadState
}

data class ModelEntry(val spec: ModelSpec, val state: DownloadState) {
    val installed get() = state is DownloadState.Installed
}

class InsufficientStorageException(val neededBytes: Long, val freeBytes: Long) :
    Exception("Not enough free storage")

@Singleton
class ModelRepository @Inject constructor(
    private val catalog: ModelCatalogRepository,
    private val storage: ModelStorage,
    private val workManager: WorkManager,
    private val settings: SettingsRepository,
    private val device: DeviceInspector,
    private val sessions: ModelSessionManager,
) {
    /** Bumped after disk changes WorkManager can't tell us about (deletes). */
    private val diskVersion = MutableStateFlow(0)

    val entries: Flow<List<ModelEntry>> = combine(
        catalog.models,
        workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG),
        diskVersion,
    ) { models, infos, _ ->
        models.map { spec -> ModelEntry(spec, stateFor(spec, infos)) }
    }.flowOn(Dispatchers.IO)

    private fun stateFor(spec: ModelSpec, infos: List<WorkInfo>): DownloadState {
        if (storage.isInstalled(spec)) return DownloadState.Installed
        val total = spec.totalBytes
        // Newest work for this model decides the state.
        val info = infos.filter { ModelDownloadWorker.modelTag(spec.id) in it.tags }
            .maxByOrNull { if (it.state.isFinished) 0 else 1 }
        val onDisk = storage.bytesOnDisk(spec)
        return when (info?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                if (info.runAttemptCount > 0) DownloadState.Downloading(onDisk, total) else DownloadState.Queued
            WorkInfo.State.RUNNING -> {
                val p = info.progress
                if (p.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false)) DownloadState.Verifying(total)
                else DownloadState.Downloading(p.getLong(ModelDownloadWorker.KEY_DONE, onDisk), total)
            }
            WorkInfo.State.FAILED -> DownloadState.Failed(
                info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed", onDisk, total,
            )
            else -> if (onDisk > 0) DownloadState.Paused(onDisk, total) else DownloadState.NotDownloaded
        }
    }

    /** Starts or resumes a download. Throws [InsufficientStorageException] if it won't fit. */
    suspend fun download(spec: ModelSpec) = withContext(Dispatchers.IO) {
        val remaining = spec.totalBytes - storage.bytesOnDisk(spec)
        val free = device.freeStorageBytes()
        val margin = 300L * 1024 * 1024 // keep some headroom for the OS and the gallery
        if (remaining + margin > free) throw InsufficientStorageException(remaining + margin, free)

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to spec.id))
            .addTag(ModelDownloadWorker.TAG)
            .addTag(ModelDownloadWorker.modelTag(spec.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(ModelDownloadWorker.uniqueName(spec.id), ExistingWorkPolicy.KEEP, request)
    }

    /** Pausing = cancelling the worker; partial files remain and the next download resumes. */
    fun pause(spec: ModelSpec) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueName(spec.id))
    }

    suspend fun delete(spec: ModelSpec) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueName(spec.id)).result.get()
        sessions.unloadIfLoaded(spec.id)
        storage.delete(spec)
        workManager.pruneWork()
        settings.update {
            it.copy(
                activeChatModelId = it.activeChatModelId.takeUnless { id -> id == spec.id },
                activeImageModelId = it.activeImageModelId.takeUnless { id -> id == spec.id },
                activeCodeModelId = it.activeCodeModelId.takeUnless { id -> id == spec.id },
            )
        }
        diskVersion.value++
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        workManager.cancelAllWorkByTag(ModelDownloadWorker.TAG).result.get()
        sessions.release()
        storage.deleteAll()
        workManager.pruneWork()
        diskVersion.value++
    }

    fun isInstalled(spec: ModelSpec) = storage.isInstalled(spec)

    /** Active model of a type: the user's choice if installed, else the first installed one. */
    suspend fun activeModel(type: ModelType): ModelSpec? {
        val s = settings.current()
        val chosen = catalog.find(when (type) {
            ModelType.CHAT -> s.activeChatModelId
            ModelType.CODE -> s.activeCodeModelId
            ModelType.IMAGE -> s.activeImageModelId
            ModelType.TOOL -> null
        })
        if (chosen != null && chosen.type == type && storage.isInstalled(chosen)) return chosen
        return catalog.models.value.firstOrNull { it.type == type && storage.isInstalled(it) }
    }

    suspend fun setActive(spec: ModelSpec) = settings.update {
        when (spec.type) {
            ModelType.CHAT -> it.copy(activeChatModelId = spec.id)
            ModelType.CODE -> it.copy(activeCodeModelId = spec.id)
            ModelType.IMAGE -> it.copy(activeImageModelId = spec.id)
            ModelType.TOOL -> it
        }
    }

    /** Installed helper model of a [com.gccera.myai.data.catalog.ToolKind], if any. */
    fun toolModel(kind: String): ModelSpec? =
        catalog.models.value.firstOrNull { it.type == ModelType.TOOL && it.tool == kind && storage.isInstalled(it) }

    /** Catalog entry for a tool kind (installed or not), to offer a download. */
    fun toolSpec(kind: String): ModelSpec? = catalog.models.value.firstOrNull { it.type == ModelType.TOOL && it.tool == kind }

    fun dirOf(spec: ModelSpec) = storage.dirFor(spec)

    fun refresh() { diskVersion.value++ }
}
