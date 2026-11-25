package com.gccera.myai.engine.code

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Optional "bring your own key" client for OpenAI-compatible `/chat/completions` endpoints (OpenAI,
 * OpenRouter, Groq, Together, local servers…). Disabled unless the user turns it on in Settings;
 * when used, the prompt (including code) is sent to that provider.
 */
class CloudClient(private val http: OkHttpClient, private val baseUrl: String, private val apiKey: String, private val model: String) {

    fun stream(system: String, user: String, temperature: Float = 0.2f, maxTokens: Int = 1500): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }.toString()
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Cloud model error HTTP ${resp.code}: ${resp.body.string().take(300)}")
            val src = resp.body.source()
            while (!src.exhausted()) {
                val line = src.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                parseDelta(data)?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Extracts choices[0].delta.content from one SSE chunk. */
        fun parseDelta(data: String): String? = runCatching {
            val choices = Json.parseToJsonElement(data).jsonObject["choices"] as? JsonArray ?: return null
            val delta = choices.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return null
            (delta["content"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()

        fun host(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url
    }
}
