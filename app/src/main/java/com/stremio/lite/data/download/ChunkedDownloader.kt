package com.stremio.lite.data.download

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads one file over several HTTP connections at once.
 *
 * The file is probed with a one-byte range request. If the server reports a byte range and a
 * length, the range is split into [DownloadTask.connections] chunks, each fetched by its own
 * coroutine writing straight into its own offset of a pre-allocated file. Per-chunk progress is
 * part of the task state, so a paused or killed download resumes exactly where it stopped
 * instead of starting over. Servers without range support fall back to a single stream, which
 * still resumes if they honour a plain `Range: bytes=n-`.
 */
class ChunkedDownloader(private val client: OkHttpClient) {

    data class Probe(val totalBytes: Long, val supportsRanges: Boolean, val fileName: String?)

    suspend fun probe(url: String, headers: Map<String, String>): Probe = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-0")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadException("Server refused the download (HTTP ${response.code})")
            }
            val contentRange = response.header("Content-Range")
            val acceptRanges = response.header("Accept-Ranges")?.contains("bytes", true) == true
            // 206 + Content-Range: bytes 0-0/12345 is the only reliable proof of range support.
            val total = when {
                response.code == 206 && contentRange != null ->
                    contentRange.substringAfter('/', "").toLongOrNull() ?: -1L
                else -> response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
            Probe(
                totalBytes = total,
                supportsRanges = (response.code == 206 && contentRange != null) || acceptRanges,
                fileName = response.header("Content-Disposition")?.let(::fileNameFromDisposition)
            )
        }
    }

    /**
     * Runs [task] to completion. [onProgress] is called with the running byte total at most a
     * few times per second; [onChunks] persists resumable chunk state.
     */
    suspend fun download(
        task: DownloadTask,
        onProgress: (downloaded: Long, speedBytesPerSec: Long) -> Unit,
        onChunks: (List<ChunkState>) -> Unit
    ): Unit = coroutineScope {
        val target = File(task.filePath)
        target.parentFile?.mkdirs()

        val chunks = task.chunks.ifEmpty {
            planChunks(task.totalBytes, if (task.supportsRanges) task.connections else 1)
        }
        val alreadyDone = chunks.sumOf { it.downloaded }
        val counter = AtomicLong(alreadyDone)

        if (task.totalBytes > 0) {
            // Pre-allocate so every worker can seek to its own offset from the start.
            RandomAccessFile(target, "rw").use { it.setLength(task.totalBytes) }
        }

        val reporter = launch {
            var lastBytes = counter.get()
            var lastTime = System.currentTimeMillis()
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val bytes = counter.get()
                val elapsed = (now - lastTime).coerceAtLeast(1)
                val speed = (bytes - lastBytes) * 1000 / elapsed
                lastBytes = bytes
                lastTime = now
                onProgress(bytes, speed.coerceAtLeast(0))
                onChunks(chunks)
            }
        }

        try {
            if (chunks.size == 1 && !task.supportsRanges && task.totalBytes <= 0) {
                streamWhole(task, target, counter)
            } else {
                chunks.map { chunk ->
                    async(Dispatchers.IO) { downloadChunk(task, chunk, target, counter) }
                }.awaitAll()
            }
            onChunks(chunks)
            onProgress(counter.get(), 0)
        } finally {
            reporter.cancel()
        }

        if (task.totalBytes > 0 && counter.get() < task.totalBytes) {
            throw DownloadException("Incomplete download: ${counter.get()} of ${task.totalBytes} bytes")
        }
    }

    private suspend fun downloadChunk(
        task: DownloadTask,
        chunk: ChunkState,
        target: File,
        counter: AtomicLong
    ) {
        var attempt = 0
        while (true) {
            if (chunk.isComplete) return
            try {
                fetchChunkOnce(task, chunk, target, counter)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt > MAX_RETRIES) {
                    throw DownloadException("Chunk ${chunk.index} failed: ${e.message}", e)
                }
                // Back off, then resume this chunk from the byte it reached.
                delay(RETRY_BASE_DELAY_MS * attempt)
            }
        }
    }

    private fun fetchChunkOnce(
        task: DownloadTask,
        chunk: ChunkState,
        target: File,
        counter: AtomicLong
    ) {
        val rangeHeader = if (chunk.end == Long.MAX_VALUE) {
            "bytes=${chunk.remainingFrom}-"
        } else {
            "bytes=${chunk.remainingFrom}-${chunk.end}"
        }
        val request = Request.Builder().url(task.url)
            .apply { task.headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", rangeHeader)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadException("HTTP ${response.code} on chunk ${chunk.index}")
            }
            if (response.code != 206 && chunk.downloaded > 0) {
                // The server ignored our Range: everything written so far is unusable for this chunk.
                counter.addAndGet(-chunk.downloaded)
                chunk.downloaded = 0
            }
            val source = response.body?.byteStream() ?: throw DownloadException("Empty body")
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(chunk.remainingFrom)
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val remaining = if (chunk.end == Long.MAX_VALUE) buffer.size.toLong()
                    else chunk.size - chunk.downloaded
                    if (remaining <= 0) break
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = source.read(buffer, 0, toRead)
                    if (read == -1) break
                    raf.write(buffer, 0, read)
                    chunk.downloaded += read
                    counter.addAndGet(read.toLong())
                }
            }
        }
        if (chunk.end != Long.MAX_VALUE && !chunk.isComplete) {
            throw DownloadException("Chunk ${chunk.index} ended early")
        }
    }

    /** Servers with neither a length nor range support: one plain stream, no resume. */
    private fun streamWhole(task: DownloadTask, target: File, counter: AtomicLong) {
        val request = Request.Builder().url(task.url)
            .apply { task.headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DownloadException("HTTP ${response.code}")
            val source = response.body?.byteStream() ?: throw DownloadException("Empty body")
            target.outputStream().use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    counter.addAndGet(read.toLong())
                }
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val MAX_RETRIES = 5
        private const val RETRY_BASE_DELAY_MS = 1_000L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val MIN_CHUNK_SIZE = 1L * 1024 * 1024

        /** Splits [total] into at most [connections] chunks, never smaller than 1 MiB each. */
        fun planChunks(total: Long, connections: Int): List<ChunkState> {
            if (total <= 0) return listOf(ChunkState(0, 0, Long.MAX_VALUE))
            val count = connections.coerceIn(1, 32)
                .coerceAtMost(((total + MIN_CHUNK_SIZE - 1) / MIN_CHUNK_SIZE).toInt().coerceAtLeast(1))
            val size = total / count
            return (0 until count).map { i ->
                val start = i * size
                val end = if (i == count - 1) total - 1 else start + size - 1
                ChunkState(i, start, end)
            }
        }

        fun fileNameFromDisposition(value: String): String? {
            val match = Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)").find(value) ?: return null
            return match.groupValues[1].trim().trim('"').ifBlank { null }
        }
    }
}

class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
