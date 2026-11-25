package com.gccera.myai.data.hf

import com.gccera.myai.data.catalog.ModelFile
import com.gccera.myai.data.catalog.ModelLicense
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.catalog.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class HubModel(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val gated: Boolean,
    val license: String?,
    val pipeline: String?,
    val private: Boolean,
)

data class HubFile(val path: String, val size: Long, val sha256: String?)

data class HubDetail(val model: HubModel, val sha: String, val files: List<HubFile>)

enum class HubSort(val label: String, val key: String) {
    DOWNLOADS("Most downloaded", "downloads"), TRENDING("Trending", "trendingScore"),
    LIKES("Most liked", "likes"), RECENT("Recently updated", "lastModified"),
}

/**
 * Browses the Hugging Face Hub for models this app can run on-device (library tag "litert-lm",
 * i.e. `.litertlm` files for the LiteRT-LM engine). Requests carry the user's token when signed in,
 * so their own private models appear and gated access can be checked.
 */
@Singleton
class HfHub @Inject constructor(private val http: OkHttpClient, private val account: HfAccount) {

    suspend fun search(query: String, author: String? = null, sort: HubSort = HubSort.DOWNLOADS): List<HubModel> = withContext(Dispatchers.IO) {
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("filter", LIBRARY)
            .apply { if (query.isNotBlank()) addQueryParameter("search", query.trim()) }
            .apply { if (!author.isNullOrBlank()) addQueryParameter("author", author) }
            .addQueryParameter("sort", sort.key).addQueryParameter("direction", "-1").addQueryParameter("limit", "60")
            .apply { listOf("downloads", "likes", "gated", "tags", "pipeline_tag", "private").forEach { addQueryParameter("expand[]", it) } }
            .build()
        (getJson(url.toString()) as JsonArray).map { parseModel(it.jsonObject) }
    }

    suspend fun detail(id: String): HubDetail = withContext(Dispatchers.IO) {
        val o = getJson("https://huggingface.co/api/models/$id?blobs=true").jsonObject
        val files = o["siblings"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it.str("rfilename")?.endsWith(".litertlm") == true }
            .map { s -> HubFile(s.str("rfilename")!!, s["size"]?.jsonPrimitive?.longOrNull ?: 0, (s["lfs"] as? JsonObject)?.str("sha256")) }
            .sortedBy { it.size }
        HubDetail(parseModel(o), o.str("sha") ?: "main", files)
    }

    /** True if the current account (or anonymous user) may download the file (gated access granted). */
    suspend fun canDownload(id: String, sha: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val client = http.newBuilder().followRedirects(false).build()
        val req = Request.Builder().url(resolveUrl(id, sha, path)).head().apply { account.token()?.let { header("Authorization", "Bearer $it") } }.build()
        client.newCall(req).execute().use { it.code in 200..399 }
    }

    private suspend fun getJson(url: String) = Request.Builder().url(url).apply { account.token()?.let { header("Authorization", "Bearer $it") } }.build()
        .let { http.newCall(it).execute() }.use { r ->
            if (!r.isSuccessful) throw IOException("Hugging Face error HTTP ${r.code}")
            Json.parseToJsonElement(r.body.string())
        }

    companion object {
        const val LIBRARY = "litert-lm"

        fun resolveUrl(id: String, sha: String, path: String) = "https://huggingface.co/$id/resolve/$sha/$path"
        fun pageUrl(id: String) = "https://huggingface.co/$id"

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        internal fun parseModel(o: JsonObject): HubModel {
            val tags = o["tags"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val gated = when (val g = o["gated"]) { is JsonPrimitive -> g.booleanOrNull ?: (g.contentOrNull != null && g.contentOrNull != "false"); else -> false }
            return HubModel(
                id = o.str("id") ?: o.str("modelId") ?: "?",
                downloads = o["downloads"]?.jsonPrimitive?.longOrNull ?: 0,
                likes = o["likes"]?.jsonPrimitive?.longOrNull ?: 0,
                gated = gated,
                license = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:"),
                pipeline = o.str("pipeline_tag"),
                private = (o["private"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

        /** Licences that allow commercial use (possibly with conditions). Anything else is flagged. */
        private val COMMERCIAL = setOf(
            "apache-2.0", "mit", "bsd", "bsd-2-clause", "bsd-3-clause", "isc", "mpl-2.0", "unlicense", "cc0-1.0",
            "cc-by-4.0", "cc-by-sa-4.0", "artistic-2.0", "gemma", "llama2", "llama3", "llama3.1", "llama3.2", "llama3.3",
            "creativeml-openrail-m", "openrail", "openrail++", "bigscience-openrail-m",
        )
        fun commercialOk(license: String?) = license != null && license.lowercase() in COMMERCIAL

        /** Turns a picked `.litertlm` file into a catalog entry (pinned to the commit, hash-verified). */
        fun toSpec(d: HubDetail, f: HubFile, type: ModelType): ModelSpec {
            val gb = f.size / 1e9
            val (minRam, recRam) = when { gb < 0.8 -> 3 to 4; gb < 1.8 -> 4 to 6; gb < 3.0 -> 8 to 8; else -> 12 to 12 }
            val ctx = Regex("ekv(\\d{3,5})").find(f.path)?.groupValues?.get(1)?.toIntOrNull() ?: 2048
            val idPart = (d.model.id + "-" + f.path.substringBeforeLast('.')).lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
            return ModelSpec(
                id = "hf-$idPart".take(120),
                type = type,
                name = d.model.id.substringAfter('/') + if (d.files.size > 1) " · ${f.path.substringBeforeLast('.').takeLast(28)}" else "",
                description = "From Hugging Face: ${d.model.id}",
                params = "%.1f GB".format(gb),
                minRamGb = minRam, recommendedRamGb = recRam,
                contextTokens = ctx,
                requiresHfToken = d.model.gated || d.model.private,
                license = ModelLicense(id = d.model.license ?: "unknown", name = d.model.license ?: "Unknown licence",
                    url = pageUrl(d.model.id), commercialUse = commercialOk(d.model.license)),
                files = listOf(ModelFile(f.path.substringAfterLast('/'), resolveUrl(d.model.id, d.sha, f.path), f.size, f.sha256)),
                custom = true,
                vision = d.model.id.contains("gemma-4", ignoreCase = true),
            )
        }
    }
}
