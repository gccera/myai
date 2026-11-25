package com.gccera.myai.engine.sd

/**
 * IEEE-754 half-precision conversion in pure Kotlin (same results as android.util.Half, which we
 * avoid so the diffusion pipeline can also be unit-tested on a desktop JVM).
 */
object Fp16 {
    fun fromFloat(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        var mant = bits and 0x7FFFFF
        if (exp == 0xFF) return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort() // Inf/NaN
        var e = exp - 127 + 15
        if (e >= 0x1F) return (sign or 0x7C00).toShort() // overflow -> Inf
        if (e <= 0) {
            if (e < -10) return sign.toShort() // underflow -> 0
            mant = mant or 0x800000
            val shift = 14 - e
            var half = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (rem > halfway || (rem == halfway && (half and 1) != 0)) half++
            return (sign or half).toShort()
        }
        var half = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) != 0)) half++ // round to nearest even
        return (sign or half).toShort()
    }

    fun toFloat(h: Short): Float {
        val v = h.toInt() and 0xFFFF
        val sign = (v and 0x8000) shl 16
        val exp = (v ushr 10) and 0x1F
        val mant = v and 0x3FF
        val bits = when (exp) {
            0 -> if (mant == 0) sign else {
                // subnormal: normalise
                var m = mant; var e = -1
                do { e++; m = m shl 1 } while (m and 0x400 == 0)
                sign or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
            }
            0x1F -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
