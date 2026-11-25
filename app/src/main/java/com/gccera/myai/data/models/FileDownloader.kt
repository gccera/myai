package com.gccera.myai.data.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class DownloadException(message: String, val httpCode: Int? = null) : IOException(message)

/**
 * Resumable HTTP download with SHA-256 verification.
 * - Appends to `<dest>.part` using an HTTP Range request, so pause/resume (and app restarts) continue
 *   where they left off.
 * - The finished file is hashed; only on a match is it renamed to its final name.
 * - Cancellation (pause) is cooperative: the coroutine is checked on every buffer.
 */
class FileDownloader(private val client: OkHttpClient) {

    fun interface Progress { fun onBytes(bytesThisFile: Long) }

    suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String?,
        authToken: String? = null,
        progress: Progress,
        onVerifying: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var existing = if (part.exists()) part.length() else 0L

        val req = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
            // OkHttp drops Authorization automatically when Hugging Face redirects to its CDN host.
            if (!authToken.isNullOrBlank()) header("Authorization", "Bearer $authToken")
        }.build()

        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 416 -> Unit // Range not satisfiable: .part is already complete.
                resp.code == 401 || resp.code == 403 -> throw DownloadException(
                    "Access denied (HTTP ${resp.code}). This model is licence-gated: accept its licence on " +
                        "huggingface.co and add a Hugging Face read token in Settings.", resp.code,
                )
                !resp.isSuccessful -> throw DownloadException("Download failed (HTTP ${resp.code})", resp.code)
                else -> {
                    val append = resp.code == 206 && existing > 0
                    if (!append) existing = 0 // Server ignored Range: start over.
                    val body = resp.body
                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(256 * 1024)
                            var total = existing
                            progress.onBytes(total)
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                                progress.onBytes(total)
                            }
                        }
                    }
                }
            }
        }

        if (expectedSha256 != null) {
            onVerifying()
            val actual = sha256(part)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                part.delete() // Corrupt or tampered: discard so the next attempt starts clean.
                throw DownloadException("Checksum mismatch for ${dest.name} (expected $expectedSha256, got $actual)")
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw DownloadException("Could not move ${part.name} into place")
    }

    companion object {
        suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 20).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
