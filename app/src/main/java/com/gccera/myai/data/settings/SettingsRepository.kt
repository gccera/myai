package com.gccera.myai.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class LlmBackend { CPU, GPU }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultSystemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxTokens: Int = 1024,
    val backend: LlmBackend = LlmBackend.CPU,
    val activeChatModelId: String? = null,
    val activeImageModelId: String? = null,
    /** Optional Hugging Face read token, only for licence-gated models. Stored on device only. */
    val hfToken: String = "",
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, honest assistant running fully offline on the user's phone. " +
                "Answer concisely and use Markdown for lists and code."
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val sysPrompt = stringPreferencesKey("system_prompt")
        val temp = floatPreferencesKey("temperature")
        val topK = intPreferencesKey("top_k")
        val topP = floatPreferencesKey("top_p")
        val maxTokens = intPreferencesKey("max_tokens")
        val backend = stringPreferencesKey("backend")
        val chatModel = stringPreferencesKey("active_chat_model")
        val imageModel = stringPreferencesKey("active_image_model")
        val hfToken = stringPreferencesKey("hf_token")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::settingsFrom)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val n = transform(settingsFrom(p))
            p[K.onboarding] = n.onboardingDone
            p[K.theme] = n.themeMode.name
            p[K.dynamic] = n.dynamicColor
            p[K.sysPrompt] = n.defaultSystemPrompt
            p[K.temp] = n.temperature
            p[K.topK] = n.topK
            p[K.topP] = n.topP
            p[K.maxTokens] = n.maxTokens
            p[K.backend] = n.backend.name
            if (n.activeChatModelId != null) p[K.chatModel] = n.activeChatModelId else p.remove(K.chatModel)
            if (n.activeImageModelId != null) p[K.imageModel] = n.activeImageModelId else p.remove(K.imageModel)
            p[K.hfToken] = n.hfToken
        }
    }

    private fun settingsFrom(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingDone = p[K.onboarding] ?: d.onboardingDone,
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            defaultSystemPrompt = p[K.sysPrompt] ?: d.defaultSystemPrompt,
            temperature = p[K.temp] ?: d.temperature,
            topK = p[K.topK] ?: d.topK,
            topP = p[K.topP] ?: d.topP,
            maxTokens = p[K.maxTokens] ?: d.maxTokens,
            backend = p[K.backend]?.let { runCatching { LlmBackend.valueOf(it) }.getOrNull() } ?: d.backend,
            activeChatModelId = p[K.chatModel],
            activeImageModelId = p[K.imageModel],
            hfToken = p[K.hfToken] ?: "",
        )
    }

    /** Wipes all preferences (used by Settings → Clear all data). */
    suspend fun clear() { context.dataStore.edit { it.clear() } }
}
