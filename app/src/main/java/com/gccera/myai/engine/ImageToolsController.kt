package com.gccera.myai.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.gccera.myai.data.catalog.ToolKind
import com.gccera.myai.data.db.ImageEntity
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.repo.ImageRepository
import com.gccera.myai.di.AppScope
import com.gccera.myai.engine.sd.RgbImage
import com.gccera.myai.engine.tools.BackgroundRemover
import com.gccera.myai.engine.tools.ImageOps
import com.gccera.myai.engine.tools.Upscaler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ImageTool(val label: String, val kind: String) {
    UPSCALE_2X("Upscale ×2", ToolKind.UPSCALE_2X),
    UPSCALE_4X("Upscale ×4", ToolKind.UPSCALE_4X),
    REMOVE_BACKGROUND("Remove background", ToolKind.BACKGROUND),
}

sealed interface ImageToolState {
    data object Idle : ImageToolState
    data class Running(val tool: ImageTool, val done: Int, val total: Int) : ImageToolState
    data class Done(val imageId: Long, val path: String) : ImageToolState
    data class Error(val message: String) : ImageToolState
}

/** Runs upscaling / background removal on a gallery image or a picked photo; the result goes to the Gallery. */
@Singleton
class ImageToolsController @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
    private val models: ModelRepository,
    private val sessions: ModelSessionManager,
    private val images: ImageRepository,
) {
    private val _state = MutableStateFlow<ImageToolState>(ImageToolState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var upscaler: Upscaler? = null

    fun runOnGalleryImage(imageId: Long, tool: ImageTool) = start(tool) {
        val src = images.get(imageId) ?: error("Image not found")
        Triple(images.loadBitmap(src.filePath) ?: error("Could not read image"), src.prompt, src)
    }

    fun runOnUri(uri: Uri, tool: ImageTool) = start(tool) { Triple(decode(uri), "Imported photo", null) }

    private fun start(tool: ImageTool, load: suspend () -> Triple<Bitmap, String, ImageEntity?>) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val spec = models.toolModel(tool.kind) ?: throw MissingModelException(models.toolSpec(tool.kind)?.name ?: tool.label)
                _state.value = ImageToolState.Running(tool, 0, 1)
                val (bmp, prompt, src) = load()
                // Keep memory bounded: upscaling works on ≤768 px inputs, matting on ≤2048 px.
                var input = toRgb(bmp)
                val limit = if (tool == ImageTool.REMOVE_BACKGROUND) 2048 else 768
                val longest = maxOf(input.width, input.height)
                if (longest > limit) {
                    val s = limit.toFloat() / longest
                    input = ImageOps.resize(input, (input.width * s).toInt(), (input.height * s).toInt())
                }
                val started = System.currentTimeMillis()
                val out = sessions.withTool(spec, tool.label) { dir ->
                    val file = java.io.File(dir, "onnx/model.onnx")
                    when (tool) {
                        ImageTool.REMOVE_BACKGROUND -> BackgroundRemover(file).use { it.removeBackground(input) }
                        else -> Upscaler(file, if (tool == ImageTool.UPSCALE_2X) 2 else 4).also { upscaler = it }.use { u ->
                            u.upscale(input) { d, n -> _state.value = ImageToolState.Running(tool, d, n) }
                        }
                    }
                }
                upscaler = null
                val bitmap = Bitmap.createBitmap(out.argb, out.width, out.height, Bitmap.Config.ARGB_8888)
                val id = images.save(bitmap, ImageEntity(
                    filePath = "", prompt = "${tool.label}: $prompt", negativePrompt = "",
                    seed = src?.seed ?: 0, steps = 0, guidance = 0f, width = out.width, height = out.height,
                    modelId = spec.id, createdAt = System.currentTimeMillis(), durationMs = System.currentTimeMillis() - started,
                ))
                bitmap.recycle()
                _state.value = ImageToolState.Done(id, images.pathOf(id))
            } catch (e: CancellationException) {
                _state.value = ImageToolState.Idle
            } catch (e: OutOfMemoryError) {
                _state.value = ImageToolState.Error("Not enough memory for this image. Try a smaller one.")
            } catch (e: Exception) {
                _state.value = ImageToolState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() { upscaler?.cancel(); job?.cancel() }
    fun reset() { if (job?.isActive != true) _state.value = ImageToolState.Idle }

    private fun decode(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 2048) { val s = 2048f / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
            }
        } else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)

    companion object {
        fun toRgb(b: Bitmap): RgbImage {
            val bmp = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            return RgbImage(bmp.width, bmp.height, px)
        }
    }
}
