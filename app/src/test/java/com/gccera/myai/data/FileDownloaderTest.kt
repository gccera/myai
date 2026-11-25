package com.gccera.myai.data

import com.gccera.myai.data.models.DownloadException
import com.gccera.myai.data.models.FileDownloader
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class FileDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val downloader = FileDownloader(OkHttpClient())
    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    @Test fun downloadsAndVerifies() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())
        val dest = tmp.root.resolve("m/model.bin")
        var last = 0L
        downloader.download(server.url("/f").toString(), dest, sha, progress = { last = it })
        assertTrue(dest.exists())
        assertEquals(payload.size.toLong(), last)
        assertFalse(tmp.root.resolve("m/model.bin.part").exists())
    }

    @Test fun resumesWithRangeRequest() = runBlocking {
        val dest = tmp.root.resolve("model.bin")
        val part = tmp.root.resolve("model.bin.part")
        part.writeBytes(payload.copyOfRange(0, 100_000)) // simulate a paused download
        server.enqueue(MockResponse.Builder().code(206).body(Buffer().write(payload.copyOfRange(100_000, payload.size))).build())
        downloader.download(server.url("/f").toString(), dest, sha, progress = {})
        assertEquals("bytes=100000-", server.takeRequest().headers["Range"])
        assertTrue(dest.readBytes().contentEquals(payload))
    }

    @Test fun restartsWhenServerIgnoresRange() = runBlocking {
        val dest = tmp.root.resolve("model.bin")
        tmp.root.resolve("model.bin.part").writeBytes(ByteArray(10) { 9 })
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(payload)).build())
        downloader.download(server.url("/f").toString(), dest, sha, progress = {})
        assertTrue(dest.readBytes().contentEquals(payload))
    }

    @Test fun checksumMismatchDiscardsPartialFile() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())
        val dest = tmp.root.resolve("model.bin")
        val err = runCatching { downloader.download(server.url("/f").toString(), dest, "0".repeat(64), progress = {}) }.exceptionOrNull()
        assertTrue(err is DownloadException && err.message!!.contains("Checksum"))
        assertFalse(dest.exists())
        assertFalse(tmp.root.resolve("model.bin.part").exists())
    }

    @Test fun gatedModelGivesHelpfulError() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        val err = runCatching { downloader.download(server.url("/f").toString(), tmp.root.resolve("x"), sha, progress = {}) }.exceptionOrNull()
        assertTrue(err is DownloadException && (err as DownloadException).httpCode == 401 && err.message!!.contains("token"))
    }

    @Test fun sendsBearerTokenWhenProvided() = runBlocking {
        server.enqueue(MockResponse.Builder().body(Buffer().write(payload)).build())
        downloader.download(server.url("/f").toString(), tmp.root.resolve("y"), sha, authToken = "hf_test", progress = {})
        assertEquals("Bearer hf_test", server.takeRequest().headers["Authorization"])
    }
}
