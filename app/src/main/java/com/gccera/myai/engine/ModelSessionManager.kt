package com.gccera.myai.engine

import android.content.Context
import android.util.Log
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.models.ModelStorage
import com.gccera.myai.data.settings.LlmBackend
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.device.DeviceInspector
import com.gccera.myai.engine.llm.LlmEngine
import com.gccera.myai.engine.sd.StableDiffusionPipeline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

class ModelBusyException(message: String) : Exception(message)
class ModelLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the UI shows about the single in-memory model. */
sealed interface SessionState {
    data object Empty : SessionState
    data class Loading(val spec: ModelSpec) : SessionState
    data class Ready(val spec: ModelSpec, val detail: String) : SessionState
    data class Busy(val spec: ModelSpec, val task: String) : SessionState
}

/**
 * Guarantees that at most ONE model (chat or image) is in memory at a time.
 *
 * Chat models are 0.3–2.6 GB and SD 1.5 needs ~2–3 GB; holding both would get the app killed on
 * most phones. Every use goes through [withChat] / [withImage], which:
 *   1. takes an exclusive lock (a second concurrent use fails fast with [ModelBusyException]),
 *   2. unloads whatever other model is loaded, then loads the requested one if needed,
 *   3. runs the block, keeping the model warm for the next request of the same kind.
 */
@Singleton
class ModelSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val storage: ModelStorage,
    private val device: DeviceInspector,
) {
    private sealed interface Loaded { val spec: ModelSpec }
    private class ChatLoaded(override val spec: ModelSpec, val engine: LlmEngine, val backend: LlmBackend) : Loaded
    private class ImageLoaded(override val spec: ModelSpec, val pipeline: StableDiffusionPipeline) : Loaded

    private val lock = Mutex()
    private var loaded: Loaded? = null
    @Volatile private var activeTask: String? = null

    private val _state = MutableStateFlow<SessionState>(SessionState.Empty)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    suspend fun <T> withChat(spec: ModelSpec, task: String, block: suspend (LlmEngine) -> T): T {
        require(spec.type == ModelType.CHAT)
        return exclusive(task) {
            val engine = ensureChat(spec)
            _state.value = SessionState.Busy(spec, task)
            try { block(engine) } finally { _state.value = readyState() }
        }
    }

    suspend fun <T> withImage(spec: ModelSpec, task: String, block: suspend (StableDiffusionPipeline) -> T): T {
        require(spec.type == ModelType.IMAGE)
        return exclusive(task) {
            val pipeline = ensureImage(spec)
            _state.value = SessionState.Busy(spec, task)
            try { block(pipeline) } finally { _state.value = readyState() }
        }
    }

    private suspend fun <T> exclusive(task: String, block: suspend () -> T): T {
        if (!lock.tryLock()) throw ModelBusyException("Please wait: ${activeTask ?: "another task"} is still running.")
        activeTask = task
        try { return block() } finally { activeTask = null; lock.unlock() }
    }

    private suspend fun ensureChat(spec: ModelSpec): LlmEngine = withContext(Dispatchers.IO) {
        val backend = settings.current().backend
        (loaded as? ChatLoaded)?.let { if (it.spec.id == spec.id && it.backend == backend) return@withContext it.engine }
        closeLoaded()
        _state.value = SessionState.Loading(spec)
        val path = storage.fileFor(spec, spec.primaryFile)
        if (!path.exists()) throw ModelLoadException("${spec.name} is not downloaded.")
        val engine = try {
            LlmEngine.create(spec, path.path, backend, context.cacheDir.path)
        } catch (t: Throwable) {
            if (backend == LlmBackend.GPU) {
                // Not every GPU/driver is supported; fall back to the always-available CPU backend.
                Log.w(TAG, "GPU init failed, falling back to CPU", t)
                runCatching { LlmEngine.create(spec, path.path, LlmBackend.CPU, context.cacheDir.path) }
                    .getOrElse { failLoad(spec, it) }
            } else failLoad(spec, t)
        }
        loaded = ChatLoaded(spec, engine, backend)
        engine
    }

    private suspend fun ensureImage(spec: ModelSpec): StableDiffusionPipeline = withContext(Dispatchers.IO) {
        (loaded as? ImageLoaded)?.let { if (it.spec.id == spec.id) return@withContext it.pipeline }
        closeLoaded()
        _state.value = SessionState.Loading(spec)
        // 12 GB+ phones keep the SD networks loaded between images; others load each network for
        // its phase only. Either way guidance runs as two batch-1 passes (see the pipeline).
        val keepLoaded = device.inspect().ramClassGb >= 12
        val pipeline = try {
            StableDiffusionPipeline(spec, storage.dirFor(spec), keepLoaded)
        } catch (t: Throwable) { failLoad(spec, t) }
        loaded = ImageLoaded(spec, pipeline)
        pipeline
    }

    private fun failLoad(spec: ModelSpec, t: Throwable): Nothing {
        _state.value = SessionState.Empty
        val msg = if (t is OutOfMemoryError) "Not enough memory to load ${spec.name}. Close other apps or pick a smaller model."
        else "Could not load ${spec.name}: ${t.message ?: t.javaClass.simpleName}"
        throw ModelLoadException(msg, t)
    }

    private fun readyState(): SessionState = when (val l = loaded) {
        null -> SessionState.Empty
        is ChatLoaded -> SessionState.Ready(l.spec, l.engine.backendName)
        is ImageLoaded -> SessionState.Ready(l.spec, "ONNX Runtime CPU")
    }

    private fun closeLoaded() {
        val l = loaded ?: return
        loaded = null
        runCatching {
            when (l) {
                is ChatLoaded -> l.engine.close()
                is ImageLoaded -> l.pipeline.close()
            }
        }.onFailure { Log.w(TAG, "close failed", it) }
        _state.value = SessionState.Empty
        // Native memory is freed by close(); nudge the GC for the Java-side buffers too.
        System.gc()
    }

    /** Frees the model if nothing is using it right now (called on memory pressure). */
    fun releaseIfIdle() {
        if (lock.tryLock()) try { closeLoaded() } finally { lock.unlock() }
    }

    suspend fun release() = lock.withLock { closeLoaded() }

    suspend fun unloadIfLoaded(modelId: String) = lock.withLock {
        if (loaded?.spec?.id == modelId) closeLoaded()
    }

    /** Called from settings when the backend changes so the next chat reloads on the new backend. */
    fun currentTask(): String? = activeTask

    private companion object { const val TAG = "ModelSession" }
}
