package com.gccera.myai.engine.sd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.Normalizer

/**
 * Pure-Kotlin port of the CLIP byte-level BPE tokenizer used by Stable Diffusion
 * (equivalent to Hugging Face `CLIPTokenizer` for normal text).
 */
class ClipTokenizer(
    private val encoder: Map<String, Int>,
    merges: List<Pair<String, String>>,
    padToken: String = END,
) {
    private val bpeRanks: Map<Pair<String, String>, Int> = merges.withIndex().associate { it.value to it.index }
    private val byteEncoder: Map<Int, Char> = bytesToUnicode()
    private val cache = HashMap<String, String>()

    val bosId: Int = encoder.getValue(START)
    val eosId: Int = encoder.getValue(END)
    val padId: Int = encoder[padToken] ?: eosId

    /** Token ids for [text] without special tokens. */
    fun encode(text: String): List<Int> {
        val clean = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(Regex("\\s+"), " ").trim().lowercase()
        val ids = ArrayList<Int>()
        for (m in PATTERN.findAll(clean)) {
            val token = m.value.toByteArray(Charsets.UTF_8)
                .joinToString("") { byteEncoder.getValue(it.toInt() and 0xFF).toString() }
            for (piece in bpe(token).split(' ')) encoder[piece]?.let(ids::add)
        }
        return ids
    }

    /** `[BOS] tokens… [EOS] [PAD]…` truncated/padded to [maxLength] (77 for SD). */
    fun encodeForModel(text: String, maxLength: Int = 77): IntArray {
        val body = encode(text).take(maxLength - 2)
        val out = IntArray(maxLength) { padId }
        out[0] = bosId
        body.forEachIndexed { i, id -> out[i + 1] = id }
        out[body.size + 1] = eosId
        return out
    }

    private fun bpe(token: String): String {
        cache[token]?.let { return it }
        // Split into symbols; the last one carries the end-of-word marker.
        var word: List<String> = token.dropLast(1).map { it.toString() } + (token.last() + "</w>")
        if (word.size == 1) return word[0].also { cache[token] = it }
        while (true) {
            var best: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (i in 0 until word.size - 1) {
                val pair = word[i] to word[i + 1]
                val r = bpeRanks[pair] ?: continue
                if (r < bestRank) { bestRank = r; best = pair }
            }
            val (first, second) = best ?: break
            val merged = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    merged.add(first + second); i += 2
                } else {
                    merged.add(word[i]); i += 1
                }
            }
            word = merged
            if (word.size == 1) break
        }
        return word.joinToString(" ").also { cache[token] = it }
    }

    companion object {
        const val START = "<|startoftext|>"
        const val END = "<|endoftext|>"

        private val PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            RegexOption.IGNORE_CASE,
        )

        /** GPT-2/CLIP reversible byte → printable-unicode mapping. */
        internal fun bytesToUnicode(): Map<Int, Char> {
            val bs = ArrayList<Int>().apply {
                addAll('!'.code..'~'.code); addAll('¡'.code..'¬'.code); addAll('®'.code..'ÿ'.code)
            }
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            return bs.indices.associate { bs[it] to cs[it].toChar() }
        }

        /** Loads `vocab.json`, `merges.txt` and (optionally) `special_tokens_map.json` from a diffusers tokenizer folder. */
        fun fromDirectory(dir: File): ClipTokenizer {
            val vocabJson = Json.parseToJsonElement(File(dir, "vocab.json").readText()).jsonObject
            val vocab = HashMap<String, Int>(vocabJson.size * 2)
            vocabJson.forEach { (k, v) -> vocab[k] = v.jsonPrimitive.int }
            val merges = File(dir, "merges.txt").readLines()
                .drop(1) // "#version: ..." header
                .filter { it.isNotBlank() }
                .map { line -> line.split(' ').let { it[0] to it[1] } }
            val pad = File(dir, "special_tokens_map.json").takeIf { it.exists() }?.let { f ->
                when (val p = Json.parseToJsonElement(f.readText()).jsonObject["pad_token"]) {
                    is JsonObject -> p["content"]?.jsonPrimitive?.content ?: END
                    is JsonPrimitive -> p.content
                    else -> END
                }
            } ?: END
            return ClipTokenizer(vocab, merges, pad)
        }
    }
}
