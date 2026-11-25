package com.gccera.myai.data

import com.gccera.myai.data.catalog.CatalogFile
import com.gccera.myai.data.catalog.ModelType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the bundled catalog: every file pinned, hashed and licensed for commercial use. */
class ModelCatalogTest {
    private val catalog = Json { ignoreUnknownKeys = true }
        .decodeFromString(CatalogFile.serializer(), File("src/main/assets/model_catalog.json").readText())

    @Test fun everyModelIsPinnedHashedAndCommercial() {
        assertTrue(catalog.models.any { it.type == ModelType.CHAT })
        assertTrue(catalog.models.any { it.type == ModelType.IMAGE })
        assertEquals("ids must be unique", catalog.models.size, catalog.models.map { it.id }.toSet().size)
        for (m in catalog.models) {
            assertTrue("${m.id} commercial", m.license.commercialUse)
            assertTrue("${m.id} ram", m.minRamGb in 1..m.recommendedRamGb)
            for (f in m.files) {
                assertTrue("${m.id}/${f.path} https", f.url.startsWith("https://huggingface.co/"))
                assertTrue("${m.id}/${f.path} pinned to a commit", Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(f.url))
                assertTrue("${m.id}/${f.path} sha256", f.sha256!!.matches(Regex("[0-9a-f]{64}")))
                assertTrue("${m.id}/${f.path} size", f.sizeBytes > 0)
            }
            if (m.type == ModelType.IMAGE) {
                assertNotNull(m.image)
                listOf("text_encoder/model.onnx", "unet/model.onnx", "vae_decoder/model.onnx", "tokenizer/vocab.json", "tokenizer/merges.txt")
                    .forEach { p -> assertTrue("${m.id} has $p", m.files.any { it.path == p }) }
            }
        }
    }

    @Test fun bundledLicenceTextExistsForEachLicence() {
        for (m in catalog.models) {
            assertTrue("licence text for ${m.license.id}", File("src/main/assets/licenses/${m.license.id}.txt").length() > 1000)
        }
    }
}
