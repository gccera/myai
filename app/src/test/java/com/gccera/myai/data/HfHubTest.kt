package com.gccera.myai.data

import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.hf.HfHub
import com.gccera.myai.data.hf.HubDetail
import com.gccera.myai.data.hf.HubFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parsing uses a real Hub API response (models?filter=litert-lm&search=gemma3, Nov 2026). */
class HfHubTest {
    private val sample = Json.parseToJsonElement(
        File(javaClass.classLoader!!.getResource("hf/search_gemma3.json")!!.toURI()).readText()
    ).jsonArray.map { HfHub.parseModel(it.jsonObject) }

    @Test fun parsesSearchResults() {
        val gemma = sample.first { it.id == "litert-community/Gemma3-1B-IT" }
        assertTrue(gemma.gated)                  // "gated": "auto"
        assertEquals("gemma", gemma.license)
        assertTrue(gemma.downloads > 0 && gemma.likes > 0)
        assertTrue(sample.first { it.id.startsWith("google/") }.gated) // "gated": "manual"
    }

    @Test fun commercialLicences() {
        assertTrue(HfHub.commercialOk("apache-2.0")); assertTrue(HfHub.commercialOk("gemma")); assertTrue(HfHub.commercialOk("llama3.2"))
        assertFalse(HfHub.commercialOk("cc-by-nc-4.0")); assertFalse(HfHub.commercialOk("other")); assertFalse(HfHub.commercialOk(null))
    }

    @Test fun pickedFileBecomesPinnedVerifiedSpec() {
        val d = HubDetail(sample.first { it.id == "litert-community/Gemma3-1B-IT" }, "a9bd85643f05cdb1e5cd5af7ba4e74b0e8210ca0", emptyList())
        val f = HubFile("Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm", 584_417_280, "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be")
        val spec = HfHub.toSpec(d.copy(files = listOf(f)), f, ModelType.CHAT)
        assertEquals("https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/a9bd85643f05cdb1e5cd5af7ba4e74b0e8210ca0/Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm", spec.files.single().url)
        assertEquals(f.sha256, spec.files.single().sha256)
        assertEquals(4096, spec.contextTokens)   // from "ekv4096"
        assertTrue(spec.requiresHfToken && spec.custom && spec.license.commercialUse)
        assertEquals(3 to 4, spec.minRamGb to spec.recommendedRamGb)
        assertTrue(spec.id.startsWith("hf-"))
    }
}
