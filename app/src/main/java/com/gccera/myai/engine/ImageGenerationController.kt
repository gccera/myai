package com.gccera.myai.engine

import android.graphics.Bitmap
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.db.ImageEntity
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.repo.ImageRepository
import com.gccera.myai.di.AppScope
import com.gccera.myai.engine.sd.ImageGenParams
import com.gccera.myai.engine.sd.StableDiffusionPipeline
import com.gccera.myai.safety.ContentFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImageGenState {
    data object Idle : ImageGenState
    data class Loading(val modelName: String) : ImageGenState
    /** [stage] describes the current phase; [secPerStep] is the latest UNet step duration. */
    data class Running(val step: Int, val total: Int, val startedAt: Long, val stage: String = "", val secPerStep: Float? = null) : ImageGenState
    data class Done(val imageId: Long, val path: String, val durationMs: Long) : ImageGenState
    data class Error(val message: String) : ImageGenState
    data object Cancelled : ImageGenState
}

/**
 * Owns image generation in an application-wide scope, so leaving the Image screen doesn't cancel
 * a multi-minute generation. The heavy work runs on [Dispatchers.Default].
 */
@Singleton
class ImageGenerationController @Inject constructor(
    @AppScope private val scope: CoroutineScope,
    private val sessions: ModelSessionManager,
    private val models: ModelRepository,
    private val images: ImageRepository,
    private val filter: ContentFilter,
) {
    private val _state = MutableStateFlow<ImageGenState>(ImageGenState.Idle)
    val state: StateFlow<ImageGenState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var pipeline: StableDiffusionPipeline? = null
    @Volatile private var cancelRequested = false

    val isRunning get() = job?.isActive == true

    fun generate(params: ImageGenParams) {
        if (isRunning) return
        val check = filter.check(params.prompt)
        if (check.blocked) { _state.value = ImageGenState.Error(ContentFilter.BLOCKED_PROMPT_MESSAGE); return }

        cancelRequested = false
        job = scope.launch {
            try {
                val spec = models.activeModel(ModelType.IMAGE)
                    ?: throw IllegalStateException("No image model installed. Download one in Models.")
                _state.value = ImageGenState.Loading(spec.name)
                val started = System.currentTimeMillis()
                val rgb = sessions.withImage(spec, "Image generation") { p ->
                    pipeline = p
                    _state.value = ImageGenState.Running(0, params.steps, started)
                    try {
                        runInterruptible(Dispatchers.Default) {
                            var stage = ""
                            var lastStepAt = 0L
                            var perStep: Float? = null
                            p.generate(params, onStage = { st ->
                                stage = st
                                if (st == "Denoising…") lastStepAt = System.currentTimeMillis()
                                val cur = _state.value as? ImageGenState.Running
                                _state.value = ImageGenState.Running(cur?.step ?: 0, params.steps, started, st, perStep)
                            }) { step, total ->
                                val now = System.currentTimeMillis()
                                if (step > 0 && lastStepAt > 0) perStep = (now - lastStepAt) / 1000f
                                lastStepAt = now
                                _state.value = ImageGenState.Running(step, total, started, stage, perStep)
                            }
                        }
                    } finally { pipeline = null }
                }
                val duration = System.currentTimeMillis() - started
                val bitmap = Bitmap.createBitmap(rgb.argb, rgb.width, rgb.height, Bitmap.Config.ARGB_8888)
                val id = images.save(bitmap, ImageEntity(
                    filePath = "", prompt = params.prompt, negativePrompt = params.negativePrompt,
                    seed = params.seed, steps = params.steps, guidance = params.guidance,
                    width = params.width, height = params.height, modelId = spec.id,
                    createdAt = System.currentTimeMillis(), durationMs = duration,
                ))
                bitmap.recycle()
                _state.value = ImageGenState.Done(id, images.pathOf(id), duration)
            } catch (e: CancellationException) {
                _state.value = ImageGenState.Cancelled
            } catch (e: OutOfMemoryError) {
                sessions.releaseIfIdle()
                _state.value = ImageGenState.Error("Ran out of memory. Close other apps or use a smaller image size.")
            } catch (e: Exception) {
                // Cancelling mid-UNet makes ONNX Runtime abort the run with an OrtException.
                _state.value = if (cancelRequested) ImageGenState.Cancelled
                else ImageGenState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        cancelRequested = true
        pipeline?.cancel()     // stops the in-flight ONNX Runtime call
        job?.cancel()
    }

    fun reset() { if (!isRunning) _state.value = ImageGenState.Idle }
}
