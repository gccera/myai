package com.gccera.myai.engine

import com.gccera.myai.engine.sd.Fp16
import org.junit.Assert.assertEquals
import org.junit.Test

class Fp16Test {
    @Test fun roundTripMatchesJavaHalfConversion() {
        // java.lang.Float.floatToFloat16 (JDK 20+) is the reference IEEE implementation.
        val values = floatArrayOf(0f, -0f, 1f, -1f, 0.1f, 3.14159f, 65504f, 70000f, 1e-5f, 6e-8f, 1e-9f, -2.5e-3f, 12345.678f)
        for (v in values) {
            val expected = java.lang.Float.floatToFloat16(v)
            assertEquals("bits for $v", expected, Fp16.fromFloat(v))
            assertEquals("value for $v", java.lang.Float.float16ToFloat(expected), Fp16.toFloat(expected), 0f)
        }
    }

    @Test fun exhaustiveHalfToFloat() {
        for (h in 0..0xFFFF) {
            val s = h.toShort()
            val e = java.lang.Float.float16ToFloat(s)
            val a = Fp16.toFloat(s)
            if (e.isNaN()) assert(a.isNaN()) else assertEquals("h=$h", e, a, 0f)
        }
    }
}
