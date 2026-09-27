package com.stremio.lite

import com.stremio.lite.data.download.ChunkedDownloader
import com.stremio.lite.data.download.DownloadStatus
import com.stremio.lite.data.download.DownloadTask
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the downloader against a server that honours range requests, which is the path the app
 * takes for a normal addon stream.
 */
class ChunkedDownloaderIntegrationTest {

    private lateinit var server: MockWebServer
    private val payload = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
    private val servedRanges = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                servedRanges += range.orEmpty()
                if (range == null) {
                    return MockResponse()
                        .setHeader("Content-Length", payload.size)
                        .setBody(Buffer().write(payload))
                }
                val spec = range.removePrefix("bytes=")
                val start = spec.substringBefore('-').toLong()
                val end = spec.substringAfter('-').toLongOrNull() ?: (payload.size - 1L)
                val slice = payload.copyOfRange(start.toInt(), (end + 1).toInt())
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setBody(Buffer().write(slice))
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `probe reports length and range support`(): Unit = runBlocking {
        val downloader = ChunkedDownloader(OkHttpClient())
        val probe = downloader.probe(server.url("/video.mp4").toString(), emptyMap())
        assertEquals(payload.size.toLong(), probe.totalBytes)
        assertTrue(probe.supportsRanges)
    }

    @Test
    fun `parallel chunks reassemble into the exact file`(): Unit = runBlocking {
        val downloader = ChunkedDownloader(OkHttpClient())
        val url = server.url("/video.mp4").toString()
        val target = File.createTempFile("download", ".mp4").also { it.delete() }
        val connections = 4

        val task = DownloadTask(
            id = "t1",
            url = url,
            fileName = "video.mp4",
            title = "Video",
            filePath = target.absolutePath,
            totalBytes = payload.size.toLong(),
            connections = connections,
            supportsRanges = true,
            chunks = ChunkedDownloader.planChunks(payload.size.toLong(), connections),
            status = DownloadStatus.RUNNING
        )

        var reported = 0L
        downloader.download(task, onProgress = { bytes, _ -> reported = bytes }, onChunks = {})

        assertArrayEquals(payload, target.readBytes())
        assertEquals(payload.size.toLong(), reported)
        // One request per chunk, each asking for its own range.
        val ranges = servedRanges.filter { it.isNotEmpty() && it != "bytes=0-0" }
        assertEquals(connections, ranges.size)
        target.delete()
    }

    @Test
    fun `a partially finished download resumes instead of restarting`(): Unit = runBlocking {
        val downloader = ChunkedDownloader(OkHttpClient())
        val url = server.url("/video.mp4").toString()
        val target = File.createTempFile("resume", ".mp4")
        val chunks = ChunkedDownloader.planChunks(payload.size.toLong(), 2)

        // Pretend the first chunk already finished in an earlier run.
        java.io.RandomAccessFile(target, "rw").use { raf ->
            raf.setLength(payload.size.toLong())
            raf.write(payload, 0, chunks[0].size.toInt())
        }
        chunks[0].downloaded = chunks[0].size

        val task = DownloadTask(
            id = "t2",
            url = url,
            fileName = "video.mp4",
            title = "Video",
            filePath = target.absolutePath,
            totalBytes = payload.size.toLong(),
            connections = 2,
            supportsRanges = true,
            chunks = chunks,
            status = DownloadStatus.RUNNING
        )

        servedRanges.clear()
        downloader.download(task, onProgress = { _, _ -> }, onChunks = {})

        assertArrayEquals(payload, target.readBytes())
        // Only the unfinished chunk was requested.
        assertEquals(1, servedRanges.count { it.isNotEmpty() })
        assertTrue(servedRanges.first().startsWith("bytes=${chunks[1].start}-"))
        target.delete()
    }
}
