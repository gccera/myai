package com.gccera.myai.data.repo

import android.content.Context
import android.net.Uri
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit on-device OCR (bundled Latin-script model) and translation (~59 languages; each
 * language pack ≈30 MB is downloaded once from Google and then works offline).
 */
@Singleton
class MlKitRepository @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile private var started = false

    /**
     * ML Kit's auto-start provider is removed from the manifest; we start it on first use, after the
     * user consented (Google's ML Kit SDK sends API usage/performance metrics, never images or text).
     */
    @Synchronized fun ensureStarted() {
        if (!started) { com.google.mlkit.common.MlKit.initialize(context); started = true }
    }

    suspend fun recognizeText(uri: Uri): String {
        ensureStarted()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try { recognizer.process(InputImage.fromFilePath(context, uri)).await().text } finally { recognizer.close() }
    }

    /** BCP-47 code of the detected language, or null if undetermined. */
    suspend fun detectLanguage(text: String): String? {
        ensureStarted()
        val client = LanguageIdentification.getClient()
        return try { client.identifyLanguage(text).await().takeIf { it != "und" } } finally { client.close() }
    }

    /** Languages ML Kit can translate, as (code, display name), sorted by name. */
    fun languages(): List<Pair<String, String>> = TranslateLanguage.getAllLanguages()
        .map { it to Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).replaceFirstChar(Char::uppercase) }
        .sortedBy { it.second }

    suspend fun downloadedLanguages(): Set<String> =
        if (!started) emptySet() else RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()

    suspend fun deleteLanguage(code: String) {
        ensureStarted()
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
    }

    /** Translates [text]; [source] null = auto-detect. Downloads missing language packs on Wi-Fi or mobile data. */
    suspend fun translate(text: String, source: String?, target: String): Pair<String, String> {
        val src = source ?: detectLanguage(text)?.let { TranslateLanguage.fromLanguageTag(it) }
            ?: throw IllegalArgumentException("Couldn't detect the language; pick it manually.")
        if (src == target) return text to src
        ensureStarted()
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build())
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translator.translate(text).await() to src
        } finally { translator.close() }
    }
}

/** Minimal Task → coroutine bridge (avoids the kotlinx-coroutines-play-services dependency). */
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
