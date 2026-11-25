package com.gccera.myai.engine.stt

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin

/**
 * Whisper log-mel spectrogram (80 bins, 16 kHz, 30 s window), matching Hugging Face
 * `WhisperFeatureExtractor`: periodic Hann window, n_fft 400, hop 160, centre/reflect padding,
 * Slaney mel filters, log10, clamp to (max − 8), then (x + 4) / 4.
 */
object WhisperFeatures {
    const val SAMPLE_RATE = 16_000
    const val N_SAMPLES = 30 * SAMPLE_RATE
    const val N_FRAMES = 3000
    const val N_MELS = 80
    private const val N_FFT = 400
    private const val HOP = 160
    private const val N_FREQ = N_FFT / 2 + 1

    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) } // periodic Hann
    private val cosT = Array(N_FREQ) { k -> DoubleArray(N_FFT) { n -> cos(2 * PI * k * n / N_FFT) } }
    private val sinT = Array(N_FREQ) { k -> DoubleArray(N_FFT) { n -> sin(2 * PI * k * n / N_FFT) } }
    internal val melFilters: Array<DoubleArray> = melFilterBank() // [N_MELS][N_FREQ]

    /** @param audio mono PCM in [-1, 1] at 16 kHz (longer input is truncated to 30 s). */
    fun logMel(audio: FloatArray): FloatArray {
        val x = DoubleArray(N_SAMPLES) { if (it < audio.size) audio[it].toDouble() else 0.0 }
        val pad = N_FFT / 2
        // numpy "reflect" padding (edge sample not repeated)
        fun sample(i: Int): Double {
            val j = i - pad
            return when {
                j < 0 -> x[-j]
                j >= N_SAMPLES -> x[2 * (N_SAMPLES - 1) - j]
                else -> x[j]
            }
        }
        // Frames whose window only covers zero padding have zero power; skip their DFT.
        val lastAudioFrame = minOf(N_FRAMES - 1, (minOf(audio.size, N_SAMPLES) + pad) / HOP + 1)
        val mel = DoubleArray(N_MELS * N_FRAMES)
        val frame = DoubleArray(N_FFT)
        val power = DoubleArray(N_FREQ)
        for (t in 0 until N_FRAMES) {
            if (t <= lastAudioFrame) {
                for (n in 0 until N_FFT) frame[n] = sample(t * HOP + n) * window[n]
                for (k in 0 until N_FREQ) {
                    var re = 0.0; var im = 0.0
                    val c = cosT[k]; val s = sinT[k]
                    for (n in 0 until N_FFT) { re += frame[n] * c[n]; im -= frame[n] * s[n] }
                    power[k] = re * re + im * im
                }
            } else power.fill(0.0)
            for (m in 0 until N_MELS) {
                var acc = 0.0
                val f = melFilters[m]
                for (k in 0 until N_FREQ) acc += f[k] * power[k]
                mel[m * N_FRAMES + t] = log10(max(acc, 1e-10))
            }
        }
        val floor = mel.max() - 8.0
        return FloatArray(mel.size) { ((max(mel[it], floor) + 4.0) / 4.0).toFloat() }
    }

    private fun hzToMel(f: Double): Double {
        val fSp = 200.0 / 3; val minLogHz = 1000.0; val minLogMel = minLogHz / fSp; val logstep = ln(6.4) / 27.0
        return if (f < minLogHz) f / fSp else minLogMel + ln(f / minLogHz) / logstep
    }

    private fun melToHz(m: Double): Double {
        val fSp = 200.0 / 3; val minLogHz = 1000.0; val minLogMel = minLogHz / fSp; val logstep = ln(6.4) / 27.0
        return if (m < minLogMel) m * fSp else minLogHz * exp(logstep * (m - minLogMel))
    }

    private fun melFilterBank(): Array<DoubleArray> {
        val melMin = hzToMel(0.0); val melMax = hzToMel(SAMPLE_RATE / 2.0)
        val filterFreqs = DoubleArray(N_MELS + 2) { melToHz(melMin + (melMax - melMin) * it / (N_MELS + 1)) }
        val fftFreqs = DoubleArray(N_FREQ) { (SAMPLE_RATE / 2.0) * it / (N_FREQ - 1) }
        return Array(N_MELS) { m ->
            val enorm = 2.0 / (filterFreqs[m + 2] - filterFreqs[m])
            DoubleArray(N_FREQ) { k ->
                val down = (fftFreqs[k] - filterFreqs[m]) / (filterFreqs[m + 1] - filterFreqs[m])
                val up = (filterFreqs[m + 2] - fftFreqs[k]) / (filterFreqs[m + 2] - filterFreqs[m + 1])
                max(0.0, minOf(down, up)) * enorm
            }
        }
    }
}
