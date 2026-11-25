package com.gccera.myai.engine.tools

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.gccera.myai.engine.sd.RgbImage
import java.io.File

/**
 * Portrait/foreground matting with MODNet (Apache-2.0, ONNX). Produces the same image with an
 * alpha channel (transparent background). Works best on people and clear foreground subjects.
 */
class BackgroundRemover(modelFile: File) : AutoCloseable {
    private val session: OrtSession = OrtUtil.session(modelFile)
    private val input = session.inputNames.first()

    /** Returns the matte (0..1) at the image's original size. */
    fun matte(img: RgbImage): FloatArray {
        // Preprocess like the model's preprocessor_config: shortest edge 512, sides divisible by 32,
        // normalise to [-1, 1].
        val scale = 512f / minOf(img.width, img.height)
        val nw = maxOf(32, ((img.width * scale).toInt() / 32) * 32)
        val nh = maxOf(32, ((img.height * scale).toInt() / 32) * 32)
        val (_, r, g, b) = ImageOps.toPlanes(img)
        val plane = nw * nh
        val data = FloatArray(3 * plane)
        listOf(r, g, b).forEachIndexed { c, p ->
            val rs = ImageOps.resizePlane(p, img.width, img.height, nw, nh)
            for (i in 0 until plane) data[c * plane + i] = (rs[i] - 0.5f) / 0.5f
        }
        val m = OrtUtil.floatTensor(session, input, data, longArrayOf(1, 3, nh.toLong(), nw.toLong())).use { t ->
            session.run(mapOf(input to t)).use { res -> OrtUtil.readFloats(res[0] as OnnxTensor) }
        }
        return ImageOps.resizePlane(m, nw, nh, img.width, img.height)
    }

    fun removeBackground(img: RgbImage): RgbImage {
        val matte = matte(img)
        val px = IntArray(img.argb.size) { i ->
            val a = ((matte[i].coerceIn(0f, 1f)) * ((img.argb[i] ushr 24) and 0xFF)).toInt()
            (a shl 24) or (img.argb[i] and 0xFFFFFF)
        }
        return RgbImage(img.width, img.height, px)
    }

    override fun close() = session.close()
}
