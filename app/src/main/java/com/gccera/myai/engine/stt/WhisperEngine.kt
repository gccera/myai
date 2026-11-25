package com.gccera.myai.engine.stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.gccera.myai.engine.tools.OrtUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.LongBuffer

/**
 * On-device speech-to-text with OpenAI Whisper (MIT) exported to ONNX (encoder + decoder without
 * KV-cache). Greedy decoding, no timestamps, ≤30 s per call. Language is auto-detected unless given.
 */
class WhisperEngine(dir: File) : AutoCloseable {
    private val encoder: OrtSession = OrtUtil.session(File(dir, "onnx/encoder_model_quantized.onnx"))
    private val decoder: OrtSession = OrtUtil.session(File(dir, "onnx/decoder_model_quantized.onnx"))
    private val idToToken: Array<String?>
    private val special: Map<String, Int>
    private val byteDecoder: Map<Char, Int> = bytesToUnicode().entries.associate { (b, c) -> c to b }
    @Volatile private var cancelled = false

    init {
        val vocab = Json.parseToJsonElement(File(dir, "vocab.json").readText()).jsonObject
        val added = Json.parseToJsonElement(File(dir, "added_tokens.json").readText()).jsonObject
        // Special tokens live in added_tokens.json, except <|endoftext|> which is in vocab.json.
        special = added.mapValues { it.value.jsonPrimitive.int } +
            vocab.filterKeys { it.startsWith("<|") && it.endsWith("|>") }.mapValues { it.value.jsonPrimitive.int }
        val size = maxOf(vocab.values.maxOf { it.jsonPrimitive.int }, special.values.max()) + 1
        idToToken = arrayOfNulls(size)
        vocab.forEach { (k, v) -> idToToken[v.jsonPrimitive.int] = k }
        special.forEach { (k, v) -> idToToken[v] = k }
    }

    private val eot = special.getValue("<|endoftext|>")
    private val sot = special.getValue("<|startoftranscript|>")
    private val transcribe = special.getValue("<|transcribe|>")
    private val noTimestamps = special.getValue("<|notimestamps|>")
    private val languageIds: Map<Int, String> = special.filterKeys { it.matches(Regex("<\\|[a-z]{2,3}(_[a-z]+)?\\|>")) }
        .entries.associate { it.value to it.key.removePrefix("<|").removeSuffix("|>") }

    fun cancel() { cancelled = true }

    data class Result(val text: String, val language: String)

    /** @param language ISO code like "en", or null to auto-detect. */
    fun transcribe(audio16k: FloatArray, language: String? = null, maxTokens: Int = 200): Result {
        cancelled = false
        val features = WhisperFeatures.logMel(audio16k)
        val hidden = OnnxTensor.createTensor(OrtUtil.env, java.nio.FloatBuffer.wrap(features), longArrayOf(1, 80, 3000)).use { f ->
            encoder.run(mapOf(encoder.inputNames.first() to f)).use { out ->
                val t = out[0] as OnnxTensor
                OrtUtil.readFloats(t) to t.info.shape
            }
        }
        OnnxTensor.createTensor(OrtUtil.env, java.nio.FloatBuffer.wrap(hidden.first), hidden.second).use { enc ->
            val langId = language?.let { special["<|$it|>"] } ?: detectLanguage(enc)
            val tokens = mutableListOf(sot, langId, transcribe, noTimestamps)
            val prefix = tokens.size
            while (tokens.size - prefix < maxTokens) {
                if (cancelled) throw java.util.concurrent.CancellationException("Cancelled")
                val logits = decode(enc, tokens)
                // Greedy over text tokens + EOT only (suppresses timestamps and other specials).
                var best = 0; var bestV = Float.NEGATIVE_INFINITY
                for (i in 0..eot) if (logits[i] > bestV) { bestV = logits[i]; best = i }
                if (best == eot) break
                tokens += best
                // Stop runaway repetition (same token 6× in a row).
                if (tokens.size >= prefix + 6 && tokens.takeLast(6).toSet().size == 1) break
            }
            return Result(detokenize(tokens.drop(prefix)).trim(), languageIds[langId] ?: "?")
        }
    }

    private fun detectLanguage(enc: OnnxTensor): Int {
        val logits = decode(enc, listOf(sot))
        return languageIds.keys.maxBy { logits[it] }
    }

    /** Logits for the last position given the full token prefix. */
    private fun decode(enc: OnnxTensor, tokens: List<Int>): FloatArray {
        val names = decoder.inputNames.toList()
        val idsName = names.first { it.contains("input_ids") }
        val encName = names.first { it.contains("encoder_hidden_states") }
        val ids = OnnxTensor.createTensor(OrtUtil.env, LongBuffer.wrap(LongArray(tokens.size) { tokens[it].toLong() }), longArrayOf(1, tokens.size.toLong()))
        return ids.use {
            decoder.run(mapOf(idsName to it, encName to enc)).use { out ->
                val t = out[0] as OnnxTensor
                val all = OrtUtil.readFloats(t)
                val vocab = t.info.shape[2].toInt()
                all.copyOfRange(all.size - vocab, all.size)
            }
        }
    }

    fun detokenize(ids: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            if (id >= eot) continue
            val tok = idToToken.getOrNull(id) ?: continue
            for (ch in tok) byteDecoder[ch]?.let { bytes.write(it) }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    override fun close() { encoder.close(); decoder.close() }

    companion object {
        /** GPT-2 byte ↔ printable-unicode table (same as CLIP's). */
        fun bytesToUnicode(): Map<Int, Char> = com.gccera.myai.engine.sd.ClipTokenizer.bytesToUnicode()
    }
}
