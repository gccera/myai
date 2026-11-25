package com.gccera.myai.engine.tools

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.gccera.myai.engine.sd.RgbImage
import java.io.File

/**
 * Super-resolution with Swin2SR (Apache-2.0) exported to ONNX, ×2 or ×4.
 * The image is processed in overlapping tiles so memory stays bounded on phones
 * (a full 512² Swin pass would need several GB of activations).
 */
class Upscaler(modelFile: File, val scale: Int, private val tile: Int = 128, private val overlap: Int = 16) : AutoCloseable {
    private val session: OrtSession = OrtUtil.session(modelFile)
    private val input = session.inputNames.first()
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    fun upscale(img: RgbImage, onProgress: (Int, Int) -> Unit = { _, _ -> }): RgbImage {
        cancelled = false
        val (alpha, r, g, b) = ImageOps.toPlanes(img)
        val w = img.width; val h = img.height
        val ow = w * scale; val oh = h * scale
        val outR = FloatArray(ow * oh); val outG = FloatArray(ow * oh); val outB = FloatArray(ow * oh)
        val tilesX = (w + tile - 1) / tile; val tilesY = (h + tile - 1) / tile
        var done = 0
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            if (cancelled) throw java.util.concurrent.CancellationException("Cancelled")
            // Core region of this tile, then the padded region we actually feed the model.
            val cx0 = tx * tile; val cy0 = ty * tile
            val cx1 = minOf(cx0 + tile, w); val cy1 = minOf(cy0 + tile, h)
            val px0 = maxOf(0, cx0 - overlap); val py0 = maxOf(0, cy0 - overlap)
            val px1 = minOf(w, cx1 + overlap); val py1 = minOf(h, cy1 + overlap)
            val tw = px1 - px0; val th = py1 - py0
            // Swin2SR needs sides that are multiples of its window size (8): pad by mirroring.
            val pw = (tw + 7) / 8 * 8; val ph = (th + 7) / 8 * 8
            val data = FloatArray(3 * pw * ph)
            for (y in 0 until ph) for (x in 0 until pw) {
                val sx = px0 + mirror(x, tw); val sy = py0 + mirror(y, th)
                val si = sy * w + sx; val di = y * pw + x
                data[di] = r[si]; data[pw * ph + di] = g[si]; data[2 * pw * ph + di] = b[si]
            }
            val out = OrtUtil.floatTensor(session, input, data, longArrayOf(1, 3, ph.toLong(), pw.toLong())).use { t ->
                session.run(mapOf(input to t)).use { res -> OrtUtil.readFloats(res[0] as OnnxTensor) }
            }
            val opw = pw * scale; val oph = ph * scale
            // Copy only the core region back (overlap hides seams).
            for (y in cy0 * scale until cy1 * scale) for (x in cx0 * scale until cx1 * scale) {
                val sx = x - px0 * scale; val sy = y - py0 * scale
                val si = sy * opw + sx; val di = y * ow + x
                outR[di] = out[si]; outG[di] = out[opw * oph + si]; outB[di] = out[2 * opw * oph + si]
            }
            onProgress(++done, tilesX * tilesY)
        }
        val outA = ImageOps.resizePlane(alpha, w, h, ow, oh)
        return ImageOps.fromPlanes(outA, outR, outG, outB, ow, oh)
    }

    private fun mirror(i: Int, n: Int): Int = if (i < n) i else (2 * n - 1 - i).coerceIn(0, n - 1)

    override fun close() = session.close()
}
