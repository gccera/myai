package com.gccera.myai.ui.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.catalog.ImageModelConfig
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.db.ImageEntity
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.repo.ImageRepository
import com.gccera.myai.device.DeviceInspector
import com.gccera.myai.engine.ImageGenState
import com.gccera.myai.engine.ImageGenerationController
import com.gccera.myai.engine.PromptImprover
import com.gccera.myai.engine.sd.ImageGenParams
import com.gccera.myai.safety.ReportReason
import com.gccera.myai.safety.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

data class ImageForm(
    val prompt: String = "",
    val negativePrompt: String = "blurry, low quality, deformed, watermark, text",
    val steps: Int = 20,
    val guidance: Float = 7.5f,
    val seedText: String = "",
    val randomSeed: Boolean = true,
    val size: ImageSize = ImageSize.S512,
)

enum class ImageSize(val w: Int, val h: Int, val label: String) {
    S256(256, 256, "256 × 256 (fastest)"),
    S384(384, 384, "384 × 384"),
    S512(512, 512, "512 × 512 (best)"),
    P384x512(384, 512, "384 × 512 portrait"),
    L512x384(512, 384, "512 × 384 landscape"),
}

@HiltViewModel
class ImageViewModel @Inject constructor(
    private val controller: ImageGenerationController,
    private val improver: PromptImprover,
    private val models: ModelRepository,
    private val images: ImageRepository,
    private val reports: ReportRepository,
    device: DeviceInspector,
) : ViewModel() {

    // Peak RAM measured for SD 1.5 fp16 on ONNX Runtime CPU: ~4.5 GB at 512 px vs ~3.8 GB at 384 px,
    // so phones below the 8 GB class default to 384 px.
    private val _form = MutableStateFlow(
        ImageForm(size = if (device.inspect().ramClassGb >= 8) ImageSize.S512 else ImageSize.S384)
    )
    val form = _form.asStateFlow()

    val genState = controller.state

    private val _improving = MutableStateFlow(false)
    val improving = _improving.asStateFlow()

    val activeModel: StateFlow<ModelSpec?> = models.entries.map { models.activeModel(ModelType.IMAGE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var appliedDefaultsFor: String? = null

    init {
        // Apply the model's recommended steps/guidance once per model.
        viewModelScope.launch {
            activeModel.collect { spec ->
                val cfg = spec?.image ?: return@collect
                if (appliedDefaultsFor != spec.id) {
                    appliedDefaultsFor = spec.id
                    _form.update { it.copy(steps = cfg.defaultSteps, guidance = cfg.defaultGuidance) }
                }
            }
        }
    }

    fun config(): ImageModelConfig = activeModel.value?.image ?: ImageModelConfig()

    fun update(transform: (ImageForm) -> ImageForm) = _form.update(transform)

    fun improvePrompt() = viewModelScope.launch {
        if (controller.isRunning) { _events.send("Wait for the current image to finish."); return@launch }
        _improving.value = true
        try {
            val better = improver.improve(_form.value.prompt)
            _form.update { it.copy(prompt = better) }
        } catch (e: Exception) {
            _events.send(e.message ?: "Could not improve the prompt")
        } finally {
            _improving.value = false
        }
    }

    fun generate() {
        val f = _form.value
        if (f.prompt.isBlank()) { viewModelScope.launch { _events.send("Type a prompt first.") }; return }
        val seed = if (f.randomSeed) Random.nextLong(0, 1L shl 32) else f.seedText.toLongOrNull() ?: 0L
        if (f.randomSeed) _form.update { it.copy(seedText = seed.toString()) }
        controller.generate(ImageGenParams(
            prompt = f.prompt.trim(),
            negativePrompt = if (config().supportsNegativePrompt) f.negativePrompt.trim() else "",
            steps = f.steps.coerceIn(1, config().maxSteps),
            guidance = f.guidance,
            seed = seed,
            width = f.size.w,
            height = f.size.h,
        ))
    }

    fun cancel() = controller.cancel()
    fun dismissResult() = controller.reset()

    suspend fun image(id: Long): ImageEntity? = images.get(id)

    fun saveToGallery(id: Long) = viewModelScope.launch {
        val img = images.get(id) ?: return@launch
        runCatching { images.exportToGallery(img) }
            .onSuccess { _events.send("Saved to Pictures") }
            .onFailure { _events.send("Could not save: ${it.message}") }
    }

    fun needsLegacyPermission() = images.needsLegacyStoragePermission()

    fun report(id: Long, reason: ReportReason, note: String) = viewModelScope.launch {
        reports.reportImage(id, reason, note)
        controller.reset()
        _events.send("Thanks — the image was reported and hidden.")
    }

    suspend fun reportEmail(id: Long, reason: ReportReason, note: String) = images.get(id)?.let {
        reports.emailIntent("generated image", reason, note, it.prompt, it.modelId)
    }

    fun isDone(s: ImageGenState) = s is ImageGenState.Done
}
