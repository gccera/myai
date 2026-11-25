package com.gccera.myai.device

import com.gccera.myai.data.catalog.CatalogFile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelRecommenderTest {
    private val models = Json { ignoreUnknownKeys = true }
        .decodeFromString(CatalogFile.serializer(), File("src/main/assets/model_catalog.json").readText()).models

    private fun device(ramGiB: Double, abis: List<String> = listOf("arm64-v8a")) = DeviceInfo(
        "Test", "Phone", "SoC", 8, abis, (ramGiB * 1024 * 1024 * 1024).toLong(), 0, false,
        50L * 1024 * 1024 * 1024, 128L * 1024 * 1024 * 1024, 35,
    )

    @Test fun ramClassRoundsUpReportedMemory() {
        assertEquals(8, device(7.4).ramClassGb)
        assertEquals(6, device(5.6).ramClassGb)
        assertEquals(4, device(3.7).ramClassGb)
        assertEquals(12, device(11.3).ramClassGb)
    }

    @Test fun lowEndPhoneGetsTinyModelAndNoImage() {
        val r = ModelRecommender.recommend(models, device(3.7))
        assertEquals("qwen3-0.6b", r.chat?.id)
        assertNull(r.image)
    }

    @Test fun midRangePhone() {
        val r = ModelRecommender.recommend(models, device(7.4))
        assertEquals("qwen2.5-1.5b-instruct", r.chat?.id)
        assertEquals("sd15-onnx-fp16", r.image?.id)
    }

    @Test fun flagshipGetsLargestRecommendedChatModel() {
        val r = ModelRecommender.recommend(models, device(11.5))
        assertEquals("gemma4-e2b-it", r.chat?.id) // largest download that is RECOMMENDED at 12 GB
        assertEquals("qwen2.5-coder-7b-instruct", r.code?.id) // 7B coder only from 12 GB
    }

    @Test fun coderModelByRam() {
        assertEquals("qwen2.5-coder-1.5b-instruct", ModelRecommender.recommend(models, device(7.4)).code?.id)
        assertNull(ModelRecommender.recommend(models, device(2.8)).code)
    }

    @Test fun warnsOn32BitCpu() {
        val r = ModelRecommender.recommend(models, device(4.0, listOf("armeabi-v7a")))
        assertTrue(r.notes.any { it.contains("32-bit") })
    }
}
