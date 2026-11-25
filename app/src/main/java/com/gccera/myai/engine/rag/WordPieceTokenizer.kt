package com.gccera.myai.engine.rag

import java.io.File
import java.text.Normalizer

/** BERT uncased WordPiece tokenizer (as used by all-MiniLM-L6-v2). */
class WordPieceTokenizer(private val vocab: Map<String, Int>) {
    val cls = vocab.getValue("[CLS]")
    val sep = vocab.getValue("[SEP]")
    private val unk = vocab.getValue("[UNK]")

    fun encode(text: String, maxLength: Int = 256): IntArray {
        val ids = ArrayList<Int>()
        ids += cls
        loop@ for (word in basicTokenize(text)) {
            for (id in wordPiece(word)) {
                if (ids.size >= maxLength - 1) break@loop
                ids += id
            }
        }
        ids += sep
        return ids.toIntArray()
    }

    private fun basicTokenize(text: String): List<String> {
        val sb = StringBuilder()
        for (ch in text) {
            val c = ch.code
            when {
                c == 0 || c == 0xFFFD || (Character.isISOControl(ch) && ch !in "\t\n\r") -> Unit
                ch.isWhitespace() -> sb.append(' ')
                isCjk(c) -> sb.append(' ').append(ch).append(' ')
                else -> sb.append(ch)
            }
        }
        val out = ArrayList<String>()
        for (raw in sb.split(' ')) {
            if (raw.isEmpty()) continue
            val lower = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            val cur = StringBuilder()
            for (ch in lower) {
                if (isPunct(ch)) {
                    if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                    out += ch.toString()
                } else cur.append(ch)
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        return out
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > 100) return listOf(unk)
        val out = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                vocab[sub]?.let { found = it }
                if (found != null) break
                end--
            }
            if (found == null) return listOf(unk)
            out += found!!
            start = end
        }
        return out
    }

    private fun isPunct(ch: Char): Boolean {
        val c = ch.code
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        val t = Character.getType(ch)
        return t == Character.CONNECTOR_PUNCTUATION.toInt() || t == Character.DASH_PUNCTUATION.toInt() ||
            t == Character.START_PUNCTUATION.toInt() || t == Character.END_PUNCTUATION.toInt() ||
            t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.OTHER_PUNCTUATION.toInt()
    }

    private fun isCjk(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF ||
        c in 0x2A700..0x2B73F || c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF || c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F

    companion object {
        fun fromFile(vocab: File) = WordPieceTokenizer(vocab.readLines().withIndex().associate { it.value to it.index })
    }
}
