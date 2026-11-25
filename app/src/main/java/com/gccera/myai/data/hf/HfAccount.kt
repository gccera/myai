package com.gccera.myai.data.hf

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.gccera.myai.BuildConfig
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.engine.code.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface HfAccountState {
    data object SignedOut : HfAccountState
    data class SignedIn(val username: String, val fullName: String?, val viaOAuth: Boolean) : HfAccountState
}

/**
 * "Sign in with Hugging Face" (OAuth 2.0 authorization code + PKCE, public client, no secret) and the
 * alternative of pasting an access token. Tokens are stored encrypted (Android Keystore, [SecretStore]) and
 * are sent only to huggingface.co — to download gated models and to list the user's own models.
 */
@Singleton
class HfAccount @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
) {
    private val prefs = context.getSharedPreferences("hf_account", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()
    private val _state = MutableStateFlow(loadState())
    val state = _state.asStateFlow()

    val oauthConfigured: Boolean get() = CLIENT_ID.isNotBlank()

    private fun loadState(): HfAccountState {
        val user = prefs.getString(K_USER, null) ?: return HfAccountState.SignedOut
        val hasToken = secrets.has(SecretStore.HF_ACCESS) || secrets.has(SecretStore.HF_TOKEN)
        return if (hasToken) HfAccountState.SignedIn(user, prefs.getString(K_NAME, null), prefs.getBoolean(K_OAUTH, false))
        else HfAccountState.SignedOut
    }

    /** One-time move of a token saved by older versions in plain DataStore into encrypted storage. */
    suspend fun migrateLegacyToken() {
        val legacy = settings.current().hfToken
        if (legacy.isNotBlank()) {
            runCatching { useAccessToken(legacy) }
            settings.update { it.copy(hfToken = "") }
        }
    }

    // ---------------- OAuth (PKCE) ----------------

    /** Builds the authorize URL and remembers the PKCE verifier/state (survives process death). */
    fun authorizeUri(): Uri {
        check(oauthConfigured) { "Hugging Face sign-in isn't configured in this build (missing OAuth client ID)." }
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(16)
        prefs.edit().putString(K_VERIFIER, verifier).putString(K_STATE, state).apply()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    fun isRedirect(uri: Uri?) = uri != null && uri.toString().startsWith(REDIRECT_URI)

    /** Completes sign-in from the redirect (com.gccera.myai://oauth/callback?code=…&state=…). */
    suspend fun handleRedirect(uri: Uri) = withContext(Dispatchers.IO) {
        uri.getQueryParameter("error")?.let { throw IOException("Sign-in cancelled: ${uri.getQueryParameter("error_description") ?: it}") }
        val code = uri.getQueryParameter("code") ?: throw IOException("No authorization code returned")
        val expected = prefs.getString(K_STATE, null)
        if (expected == null || uri.getQueryParameter("state") != expected) throw IOException("Sign-in state mismatch; please try again.")
        val verifier = prefs.getString(K_VERIFIER, null) ?: throw IOException("Sign-in expired; please try again.")
        val json = postToken(FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", code).add("redirect_uri", REDIRECT_URI)
            .add("client_id", CLIENT_ID).add("code_verifier", verifier).build())
        prefs.edit().remove(K_VERIFIER).remove(K_STATE).apply()
        saveTokens(json)
        val me = get(USERINFO, json.str("access_token")!!)
        signedIn(me.str("preferred_username") ?: me.str("name") ?: "user", me.str("name"), oauth = true)
    }

    // ---------------- Personal access token ----------------

    /** Validates a pasted token with /api/whoami-v2, then stores it encrypted. */
    suspend fun useAccessToken(token: String) = withContext(Dispatchers.IO) {
        val t = token.trim()
        require(t.startsWith("hf_")) { "Hugging Face tokens start with hf_" }
        val me = get(WHOAMI, t)
        secrets.put(SecretStore.HF_TOKEN, t)
        secrets.put(SecretStore.HF_ACCESS, null); secrets.put(SecretStore.HF_REFRESH, null)
        signedIn(me.str("name") ?: "user", me.str("fullname"), oauth = false)
    }

    fun signOut() {
        listOf(SecretStore.HF_ACCESS, SecretStore.HF_REFRESH, SecretStore.HF_TOKEN).forEach { secrets.put(it, null) }
        prefs.edit().clear().apply()
        _state.value = HfAccountState.SignedOut
    }

    /** A usable bearer token (refreshing an expired OAuth token), or null when signed out. */
    suspend fun token(): String? = withContext(Dispatchers.IO) {
        secrets.get(SecretStore.HF_TOKEN)?.let { return@withContext it }
        val access = secrets.get(SecretStore.HF_ACCESS) ?: return@withContext null
        if (System.currentTimeMillis() < prefs.getLong(K_EXPIRES, Long.MAX_VALUE) - 60_000) return@withContext access
        refreshLock.withLock {
            val refresh = secrets.get(SecretStore.HF_REFRESH) ?: return@withLock access
            runCatching {
                saveTokens(postToken(FormBody.Builder().add("grant_type", "refresh_token")
                    .add("refresh_token", refresh).add("client_id", CLIENT_ID).build()))
                secrets.get(SecretStore.HF_ACCESS)
            }.getOrElse { access }
        }
    }

    private fun saveTokens(json: JsonObject) {
        secrets.put(SecretStore.HF_ACCESS, json.str("access_token") ?: throw IOException("No access token returned"))
        json.str("refresh_token")?.let { secrets.put(SecretStore.HF_REFRESH, it) }
        val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull
        prefs.edit().putLong(K_EXPIRES, if (expiresIn != null) System.currentTimeMillis() + expiresIn * 1000 else Long.MAX_VALUE).apply()
    }

    private fun signedIn(user: String, name: String?, oauth: Boolean) {
        prefs.edit().putString(K_USER, user).putString(K_NAME, name).putBoolean(K_OAUTH, oauth).apply()
        _state.value = HfAccountState.SignedIn(user, name, oauth)
    }

    private fun postToken(body: FormBody): JsonObject {
        http.newCall(Request.Builder().url(TOKEN).post(body).build()).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw IOException("Hugging Face sign-in failed (HTTP ${r.code}): ${text.take(200)}")
            return Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun get(url: String, token: String): JsonObject =
        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
            if (r.code == 401) throw IOException("Hugging Face rejected the token (invalid or expired).")
            if (!r.isSuccessful) throw IOException("Hugging Face error HTTP ${r.code}")
            Json.parseToJsonElement(r.body.string()).jsonObject
        }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    private fun randomUrlSafe(bytes: Int): String =
        Base64.encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    companion object {
        val CLIENT_ID: String = BuildConfig.HF_OAUTH_CLIENT_ID
        const val REDIRECT_URI = "com.gccera.myai://oauth/callback"
        /** Profile + read access to gated repos the user was granted + the user's own (private) repos. */
        const val SCOPES = "openid profile gated-repos read-repos"
        private const val AUTHORIZE = "https://huggingface.co/oauth/authorize"
        private const val TOKEN = "https://huggingface.co/oauth/token"
        private const val USERINFO = "https://huggingface.co/oauth/userinfo"
        private const val WHOAMI = "https://huggingface.co/api/whoami-v2"
        private const val K_VERIFIER = "pkce_verifier"; private const val K_STATE = "oauth_state"
        private const val K_USER = "username"; private const val K_NAME = "fullname"; private const val K_OAUTH = "oauth"
        private const val K_EXPIRES = "expires_at"
    }
}
