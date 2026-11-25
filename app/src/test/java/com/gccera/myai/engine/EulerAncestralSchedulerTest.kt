package com.gccera.myai.engine

import com.gccera.myai.engine.sd.EulerAncestralScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EulerAncestralSchedulerTest {

    @Test fun leadingSpacingMatchesDiffusersSd15Timesteps() {
        // diffusers, SD 1.5 config (steps_offset=1, "leading"), 20 steps -> 951, 901, ..., 1
        val s = EulerAncestralScheduler(20, "leading", stepsOffset = 1)
        assertArrayEquals(DoubleArray(20) { 951.0 - 50 * it }, s.timesteps, 1e-9)
        assertEquals(0.0, s.sigmas.last(), 0.0)
    }

    @Test fun trailingSpacingForTurbo() {
        // diffusers "trailing": 1 step -> [999]; 4 steps -> [999, 749, 499, 249]
        assertArrayEquals(doubleArrayOf(999.0), EulerAncestralScheduler(1, "trailing").timesteps, 1e-9)
        assertArrayEquals(doubleArrayOf(999.0, 749.0, 499.0, 249.0), EulerAncestralScheduler(4, "trailing").timesteps, 1e-9)
    }

    @Test fun sigmaMaxMatchesKnownSdValue() {
        // Well-known SD schedule: sigma at t=999 is ~14.6146, at t=0 ~0.0292.
        val s = EulerAncestralScheduler(1, "trailing")
        assertEquals(14.6146, s.sigmas[0], 1e-3)
        assertEquals(14.6146, s.initNoiseSigma, 1e-3) // trailing: init sigma = max sigma
        val lead = EulerAncestralScheduler(20, "leading")
        assertEquals(sqrt(lead.sigmas[0] * lead.sigmas[0] + 1), lead.initNoiseSigma, 1e-9)
    }

    @Test fun finalStepIsDeterministicDenoise() {
        // At the last step sigma_to = 0, so no noise is added and the result is the x0 prediction.
        val s = EulerAncestralScheduler(2, "trailing")
        val latents = floatArrayOf(1f, -2f)
        val eps = floatArrayOf(0.5f, 0.25f)
        val sigma = s.sigmas[1]
        s.step(eps, 1, latents) { error("no noise expected on the final step") }
        assertEquals((1 - sigma * 0.5).toFloat(), latents[0], 1e-5f)
        assertEquals((-2 - sigma * 0.25).toFloat(), latents[1], 1e-5f)
    }

    @Test fun inputScaleShrinksNoisyLatents() {
        val s = EulerAncestralScheduler(10, "leading")
        assertTrue(s.inputScale(0) < s.inputScale(9))
    }
}
