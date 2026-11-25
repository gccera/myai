package com.gccera.myai.engine

import com.gccera.myai.engine.llm.CodeCompletionText
import com.gccera.myai.engine.rag.TextChunker
import com.gccera.myai.engine.rag.WordPieceTokenizer
import com.gccera.myai.engine.stt.WhisperFeatures
import com.gccera.myai.engine.tts.Speaker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class Phase2EnginesTest {
    private fun res(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI())
    private fun f32(f: File): FloatArray { val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); return FloatArray(b.remaining()).also { b.get(it) } }

    /** IDs produced by Hugging Face BertTokenizer for all-MiniLM-L6-v2 (accents, punctuation, CJK). */
    @Test fun wordPieceMatchesHuggingFace() {
        val tok = WordPieceTokenizer.fromFile(res("minilm/vocab.txt"))
        for (c in Json.parseToJsonElement(res("minilm/reference_ids.json").readText()).jsonArray) {
            val text = c.jsonObject["text"]!!.jsonPrimitive.content
            val ids = c.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(text, ids, tok.encode(text).toList())
        }
    }

    /** Log-mel features vs Hugging Face WhisperFeatureExtractor on real speech. */
    @Test fun whisperLogMelMatchesHuggingFace() {
        val mel = WhisperFeatures.logMel(f32(res("whisper/speech_2s.f32")))
        val ref = f32(res("whisper/speech_2s.mel300.f32")) // [80][300]
        var maxDiff = 0f
        for (m in 0 until 80) for (t in 0 until 300) maxDiff = maxOf(maxDiff, abs(mel[m * 3000 + t] - ref[m * 300 + t]))
        assertTrue("max diff $maxDiff", maxDiff < 1e-4f)
    }

    @Test fun chunkerKeepsTextAndOverlaps() {
        val text = (1..60).joinToString(" ") { "Sentence number $it talks about topic ${it % 7}." }
        val chunks = TextChunker.chunk(text, target = 300, overlap = 60)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 400 })
        assertTrue(chunks.first().startsWith("Sentence number 1 "))
        assertTrue(chunks.last().contains("number 60"))
    }

    @Test fun speakerStripsMarkdown() {
        assertEquals("Title Bold and code then a link.", Speaker.plain("# Title\n**Bold** and `code` then [a link](http://x).\n"))
        assertEquals("Look: (code block)", Speaker.plain("Look:\n```kotlin\nval x = 1\n```"))
    }

    @Test fun codeCompletionCleanup() {
        // echoed current line + fences removed; stops before repeating the code after the cursor
        assertEquals("map(num => num * 2);",
            CodeCompletionText.clean("```js\nmap(num => num * 2);\n```", "const d = nums.", ";\nconsole.log(d);", 3))
        assertEquals("\"Hello, \$name!\"", CodeCompletionText.clean("    return \"Hello, \$name!\"", "fun f() {\n    return ", "\n}", 3))
        assertEquals("a()\nb()", CodeCompletionText.clean("a()\nb()\nprint(x)", "", "\nprint(x)\n", 3))
        // model repeated the lines before the cursor (seen with Qwen2.5-Coder on HTML)
        assertEquals("<li>Banana</li>", CodeCompletionText.clean("<ul>\n  <li>Apple</li>\n  <li>Banana</li>", "<ul>\n  <li>Apple</li>\n  ", "\n</ul>", 3))
    }
}
