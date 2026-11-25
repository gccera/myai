package com.gccera.myai.engine.tools

import com.gccera.myai.engine.sd.RgbImage
import kotlin.math.floor

/** Pure-Kotlin image helpers on packed ARGB pixels (desktop-testable; no android.graphics). */
object ImageOps {
    /** Bilinear resize of a single float plane. */
    fun resizePlane(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val out = FloatArray(nw * nh)
        val sx = w.toFloat() / nw; val sy = h.toFloat() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = floor(fy).toInt(); val y1 = minOf(y0 + 1, h - 1); val dy = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = floor(fx).toInt(); val x1 = minOf(x0 + 1, w - 1); val dx = fx - x0
                val a = src[y0 * w + x0] * (1 - dx) + src[y0 * w + x1] * dx
                val b = src[y1 * w + x0] * (1 - dx) + src[y1 * w + x1] * dx
                out[y * nw + x] = a * (1 - dy) + b * dy
            }
        }
        return out
    }

    /** ARGB → 4 planes (a,r,g,b) in 0..1. */
    fun toPlanes(img: RgbImage): Array<FloatArray> {
        val n = img.width * img.height
        val a = FloatArray(n); val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val p = img.argb[i]
            a[i] = (p ushr 24 and 0xFF) / 255f; r[i] = (p shr 16 and 0xFF) / 255f
            g[i] = (p shr 8 and 0xFF) / 255f; b[i] = (p and 0xFF) / 255f
        }
        return arrayOf(a, r, g, b)
    }

    fun fromPlanes(a: FloatArray, r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int): RgbImage {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return RgbImage(w, h, IntArray(w * h) { i -> (c(a[i]) shl 24) or (c(r[i]) shl 16) or (c(g[i]) shl 8) or c(b[i]) })
    }

    fun resize(img: RgbImage, nw: Int, nh: Int): RgbImage {
        val (a, r, g, b) = toPlanes(img)
        val rs = { p: FloatArray -> resizePlane(p, img.width, img.height, nw, nh) }
        return fromPlanes(rs(a), rs(r), rs(g), rs(b), nw, nh)
    }
}
