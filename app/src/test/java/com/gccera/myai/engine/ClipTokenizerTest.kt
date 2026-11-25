package com.gccera.myai.engine

import com.gccera.myai.engine.sd.ClipTokenizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Compares our Kotlin CLIP BPE port with ids produced by Hugging Face transformers' CLIPTokenizer. */
class ClipTokenizerTest {
    private val dir = File(javaClass.classLoader!!.getResource("clip/vocab.json")!!.toURI()).parentFile!!
    private val tokenizer = ClipTokenizer.fromDirectory(dir)

    @Test fun specialTokens() {
        assertEquals(49406, tokenizer.bosId)
        assertEquals(49407, tokenizer.eosId)
        assertEquals(49407, tokenizer.padId) // SD 1.5 pads with <|endoftext|>
    }

    @Test fun matchesHuggingFaceReference() {
        val ref = Json.parseToJsonElement(File(dir, "reference_ids.json").readText()).jsonArray
        for (case in ref) {
            val text = case.jsonObject["text"]!!.jsonPrimitive.content
            val expected = case.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            assertArrayEquals("ids for '$text'", expected, tokenizer.encodeForModel(text))
        }
    }

    @Test fun truncatesTo77WithEos() {
        val ids = tokenizer.encodeForModel("word ".repeat(200))
        assertEquals(77, ids.size)
        assertEquals(49406, ids.first())
        assertEquals(49407, ids.last())
    }
}
