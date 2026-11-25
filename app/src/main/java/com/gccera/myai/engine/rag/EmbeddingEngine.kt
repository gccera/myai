package com.gccera.myai.engine.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.gccera.myai.engine.tools.OrtUtil
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Sentence embeddings with all-MiniLM-L6-v2 (Apache-2.0): mean pooling + L2 normalisation (384-d). */
class EmbeddingEngine(dir: File, modelFile: String = "onnx/model_qint8_arm64.onnx") : AutoCloseable {
    private val session: OrtSession = OrtUtil.session(File(dir, modelFile))
    private val tokenizer = WordPieceTokenizer.fromFile(File(dir, "vocab.txt"))

    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        val n = ids.size.toLong()
        fun t(v: (Int) -> Long) = OnnxTensor.createTensor(OrtUtil.env, LongBuffer.wrap(LongArray(ids.size, v)), longArrayOf(1, n))
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            for (name in session.inputNames) inputs[name] = when {
                name.contains("input_ids") -> t { ids[it].toLong() }
                name.contains("attention") -> t { 1L }
                else -> t { 0L } // token_type_ids
            }
            val hidden = session.run(inputs).use { out -> OrtUtil.readFloats(out[0] as OnnxTensor) }
            val dim = hidden.size / ids.size
            val v = FloatArray(dim)
            for (i in ids.indices) for (d in 0 until dim) v[d] += hidden[i * dim + d]
            var norm = 0f
            for (d in 0 until dim) { v[d] /= ids.size; norm += v[d] * v[d] }
            norm = sqrt(norm).coerceAtLeast(1e-12f)
            for (d in 0 until dim) v[d] /= norm
            return v
        } finally { inputs.values.forEach { it.close() } }
    }

    override fun close() = session.close()

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
    }
}
