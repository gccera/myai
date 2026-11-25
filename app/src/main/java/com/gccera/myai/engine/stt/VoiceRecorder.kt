package com.gccera.myai.engine.stt

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Records 16 kHz mono PCM from the microphone until [stop] is called or 30 s elapse (Whisper's window). */
class VoiceRecorder {
    @Volatile private var recording = false
    @Volatile var level: Float = 0f
        private set

    fun stop() { recording = false }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(maxSeconds: Int = 30): FloatArray = withContext(Dispatchers.IO) {
        val rate = WhisperFeatures.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        val out = ShortArray(rate * maxSeconds)
        var n = 0
        try {
            rec.startRecording()
            recording = true
            val buf = ShortArray(rate / 10)
            while (recording && n < out.size && coroutineContext.isActive) {
                val r = rec.read(buf, 0, minOf(buf.size, out.size - n))
                if (r <= 0) continue
                System.arraycopy(buf, 0, out, n, r)
                n += r
                level = (0 until r).maxOf { abs(buf[it].toInt()) } / 32768f
            }
        } finally {
            recording = false
            runCatching { rec.stop() }
            rec.release()
        }
        FloatArray(n) { out[it] / 32768f }
    }
}
