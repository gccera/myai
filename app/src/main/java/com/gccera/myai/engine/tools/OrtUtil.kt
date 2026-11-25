package com.gccera.myai.engine.tools

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.gccera.myai.engine.sd.Fp16
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small helpers shared by the ONNX "tool" models (Whisper, MiniLM, Swin2SR, MODNet). */
object OrtUtil {
    val env: OrtEnvironment get() = OrtEnvironment.getEnvironment()

    fun session(file: File, threads: Int = 4): OrtSession = env.createSession(file.path, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads.coerceIn(1, Runtime.getRuntime().availableProcessors()))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
    })

    /** Float input in whatever element type the model declares (fp32 or fp16). */
    fun floatTensor(session: OrtSession, input: String, data: FloatArray, shape: LongArray): OnnxTensor {
        val type = (session.inputInfo.getValue(input).info as TensorInfo).type
        return if (type == OnnxJavaType.FLOAT16) {
            val buf = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder())
            val sb = buf.asShortBuffer(); for (f in data) sb.put(Fp16.fromFloat(f))
            OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.FLOAT16)
        } else OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    fun readFloats(t: OnnxTensor): FloatArray = when (t.info.type) {
        OnnxJavaType.FLOAT16 -> {
            val sb = t.byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            FloatArray(sb.remaining()) { Fp16.toFloat(sb.get(it)) }
        }
        else -> { val fb = t.floatBuffer; FloatArray(fb.remaining()).also { fb.get(it) } }
    }

    fun shapeOf(t: OnnxTensor): LongArray = t.info.shape
}

/** Counts "performance" CPU cores from cpufreq (cores within 80% of the fastest max frequency). */
object CpuInfo {
    fun performanceCores(cap: Int = 6): Int {
        val all = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until all).mapNotNull { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
        }
        // No cpufreq info (some devices/containers): assume all cores are usable.
        val fast = if (freqs.size == all && freqs.isNotEmpty()) freqs.count { it >= freqs.max() * 0.8 } else all
        return fast.coerceIn(2, cap).coerceAtMost(all)
    }
}
