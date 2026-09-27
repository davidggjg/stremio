package com.stremio.lite.data.download

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny HTTP server bound to localhost that serves a torrent *while it downloads*.
 *
 * This is the same idea as Stremio's own streaming server (which listens on 11470 and exposes
 * `/{infoHash}/{fileIdx}`): the player is handed an ordinary HTTP URL, and the server answers
 * range requests out of the partially-downloaded file, asking libtorrent for the pieces the
 * player is about to read. So pressing Play starts the download, and the download is what feeds
 * playback — one transfer, not two.
 *
 * URL shape: `http://127.0.0.1:<port>/stream/<taskId>`
 */
class TorrentStreamServer(private val engine: TorrentEngine) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool()

    @Volatile
    var port: Int = -1
        private set

    @Synchronized
    fun ensureStarted(): Int {
        if (running.get() && port > 0) return port

        // Port 0 lets the OS pick a free one; only loopback is bound, so nothing is exposed
        // to the network.
        val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort
        running.set(true)

        workers.execute {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running.get()) continue else break
                }
                workers.execute { runCatching { serve(client) } }
            }
        }
        return port
    }

    fun urlFor(taskId: String): String = "http://127.0.0.1:${ensureStarted()}/stream/$taskId"

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = -1
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return

            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "" }
            val taskId = path.substringAfter("/stream/", "").substringBefore('?')

            val torrent = engine.activeTorrent(taskId)
            val output = BufferedOutputStream(socket.getOutputStream())

            if (taskId.isBlank() || torrent == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val total = torrent.fileSize
            val (start, end) = parseRange(rangeHeader, total)
            val length = end - start + 1

            val headers = buildString {
                append(if (rangeHeader != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${mimeTypeOf(torrent.file.name)}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (rangeHeader != null) append("Content-Range: bytes $start-$end/$total\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray())
            output.flush()

            // HEAD is how players probe for length and range support before streaming.
            if (method.equals("HEAD", ignoreCase = true)) return

            streamRange(torrent, start, end, output)
        }
    }

    /**
     * Copies [start]..[end] to the player, waiting for each piece to arrive and telling libtorrent
     * to fetch what is about to be read first.
     */
    private fun streamRange(
        torrent: TorrentEngine.ActiveTorrent,
        start: Long,
        end: Long,
        output: BufferedOutputStream
    ) {
        torrent.prioritizeAround(start)

        var position = start
        val buffer = ByteArray(CHUNK_SIZE)

        RandomAccessFile(torrent.file, "r").use { raf ->
            while (position <= end && running.get()) {
                if (!torrent.hasByte(position)) {
                    torrent.prioritizeAround(position)
                    if (!awaitByte(torrent, position)) return
                    continue
                }
                // Read no further than the end of the piece we know we have.
                val toRead = minOf(buffer.size.toLong(), end - position + 1).toInt()
                if (raf.length() < position + toRead) {
                    // libtorrent has the piece but has not flushed it yet.
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }
                raf.seek(position)
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }
                try {
                    output.write(buffer, 0, read)
                    output.flush()
                } catch (e: IOException) {
                    // The player seeked or closed the connection; that is normal.
                    return
                }
                position += read
            }
        }
    }

    /** Waits for the piece holding [position]; false means we gave up. */
    private fun awaitByte(torrent: TorrentEngine.ActiveTorrent, position: Long): Boolean {
        var waited = 0L
        while (waited < PIECE_WAIT_TIMEOUT_MS) {
            if (!running.get()) return false
            if (torrent.hasByte(position)) return true
            Thread.sleep(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return false
    }

    internal companion object {
        const val BACKLOG = 8
        const val CHUNK_SIZE = 64 * 1024
        const val POLL_INTERVAL_MS = 150L
        const val PIECE_WAIT_TIMEOUT_MS = 120_000L
        const val SOCKET_TIMEOUT_MS = 30_000

        fun parseRange(header: String?, total: Long): Pair<Long, Long> {
            if (header == null) return 0L to (total - 1)
            val spec = header.removePrefix("bytes=").trim()
            val last = (total - 1).coerceAtLeast(0)
            // Clamp the start first: a player probing past the end must not produce an inverted
            // range, which would otherwise throw and kill the connection thread.
            val start = (spec.substringBefore('-').toLongOrNull() ?: 0L).coerceIn(0, last)
            val end = (spec.substringAfter('-').toLongOrNull() ?: last).coerceIn(start, last)
            return start to end
        }

        fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "ts", "m2ts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
    }
}
