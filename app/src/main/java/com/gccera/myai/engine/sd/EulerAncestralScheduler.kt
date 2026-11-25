package com.gccera.myai.engine.sd

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Euler-Ancestral sampler, matching diffusers' `EulerAncestralDiscreteScheduler`
 * (scaled-linear betas 0.00085→0.012 over 1000 training steps — the SD 1.x/2.x/Turbo schedule).
 * Works well at 15–30 steps for SD 1.5 and at 1–4 steps for Turbo models (with "trailing" spacing).
 */
class EulerAncestralScheduler(
    val numInferenceSteps: Int,
    timestepSpacing: String = "leading",
    stepsOffset: Int = 1,
    private val predictionType: String = "epsilon",
    trainSteps: Int = 1000,
    betaStart: Double = 0.00085,
    betaEnd: Double = 0.012,
) {
    /** Model timesteps, high noise → low noise. */
    val timesteps: DoubleArray
    /** Noise level per step plus a trailing 0. */
    val sigmas: DoubleArray
    val initNoiseSigma: Double

    init {
        require(numInferenceSteps in 1..trainSteps)
        // scaled_linear: betas = linspace(sqrt(start), sqrt(end), N)^2
        val sqrtStart = sqrt(betaStart); val sqrtEnd = sqrt(betaEnd)
        val alphasCumprod = DoubleArray(trainSteps)
        var prod = 1.0
        for (i in 0 until trainSteps) {
            val b = sqrtStart + (sqrtEnd - sqrtStart) * i / (trainSteps - 1)
            prod *= 1.0 - b * b
            alphasCumprod[i] = prod
        }
        val trainSigmas = DoubleArray(trainSteps) { sqrt((1 - alphasCumprod[it]) / alphasCumprod[it]) }

        val n = numInferenceSteps
        timesteps = when (timestepSpacing) {
            "linspace" -> DoubleArray(n) { i -> (trainSteps - 1).toDouble() * (n - 1 - i) / max(1, n - 1) }
            "trailing" -> {
                val ratio = trainSteps.toDouble() / n
                DoubleArray(n) { i -> ((trainSteps - i * ratio).roundToInt() - 1).toDouble() }
            }
            else -> { // "leading"
                val ratio = trainSteps / n
                DoubleArray(n) { i -> ((n - 1 - i) * ratio + stepsOffset).toDouble() }
            }
        }
        sigmas = DoubleArray(n + 1) { i -> if (i < n) interp(timesteps[i], trainSigmas) else 0.0 }
        val maxSigma = sigmas.max()
        initNoiseSigma = if (timestepSpacing == "linspace" || timestepSpacing == "trailing") maxSigma
        else sqrt(maxSigma * maxSigma + 1)
    }

    /** Divide the latent by sqrt(sigma²+1) before feeding it to the UNet. */
    fun inputScale(step: Int): Float = (1.0 / sqrt(sigmas[step] * sigmas[step] + 1)).toFloat()

    /**
     * One denoising step, in place on [latents]. [noise] supplies fresh N(0,1) samples
     * (ancestral sampling adds a little noise back each step).
     */
    fun step(modelOutput: FloatArray, step: Int, latents: FloatArray, noise: () -> Float) {
        val sigma = sigmas[step]
        val sigmaTo = sigmas[step + 1]
        val sigmaUp = sqrt(sigmaTo * sigmaTo * (sigma * sigma - sigmaTo * sigmaTo) / (sigma * sigma))
        val sigmaDown = sqrt(sigmaTo * sigmaTo - sigmaUp * sigmaUp)
        val dt = sigmaDown - sigma
        val c = 1.0 / (sigma * sigma + 1)
        for (i in latents.indices) {
            val x = latents[i].toDouble()
            val e = modelOutput[i].toDouble()
            val predX0 = if (predictionType == "v_prediction") {
                e * (-sigma / sqrt(sigma * sigma + 1)) + x * c
            } else x - sigma * e
            val derivative = (x - predX0) / sigma
            var next = x + derivative * dt
            if (sigmaUp > 0) next += noise() * sigmaUp
            latents[i] = next.toFloat()
        }
    }

    private fun interp(t: Double, table: DoubleArray): Double {
        val lo = t.toInt().coerceIn(0, table.size - 1)
        val hi = (lo + 1).coerceAtMost(table.size - 1)
        val f = t - lo
        return table[lo] * (1 - f) + table[hi] * f
    }
}
