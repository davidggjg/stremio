package com.stremio.lite.data.download

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo

/**
 * In-app BitTorrent engine (libtorrent through libtorrent4j).
 *
 * A torrent stream from an addon is just an infoHash plus trackers, so the flow is:
 * fetch the metadata over DHT, pick the video file inside the torrent, download *only* that file
 * with every other file set to IGNORE, and hand back the finished file. Nothing is handed off to
 * another app and no seeding continues afterwards.
 */
class TorrentEngine(context: Context) {

    private val session = SessionManager()
    private val stateDir = File(context.filesDir, "torrents").apply { mkdirs() }

    data class Metadata(
        val name: String,
        val fileIndex: Int,
        val fileName: String,
        val fileSize: Long
    )

    data class Progress(
        val downloadedBytes: Long,
        val speedBytesPerSec: Long,
        val peers: Int,
        val seeds: Int,
        val fetchingMetadata: Boolean = false
    )

    @Synchronized
    fun ensureStarted(connectionsLimit: Int) {
        if (session.isRunning) {
            session.applySettings(SettingsPack().connectionsLimit(connectionsLimit.coerceIn(20, 500)))
            return
        }
        val settings = SettingsPack()
            .connectionsLimit(connectionsLimit.coerceIn(20, 500))
            .activeDownloads(-1)
            .activeSeeds(0)
            .activeLimit(-1)
        session.start(SessionParams(settings))
    }

    @Synchronized
    fun shutdown() {
        if (session.isRunning) session.stop()
    }

    /**
     * Downloads one file out of the torrent.
     *
     * @param preferredFileIndex the addon's `fileIdx`, when it provided one.
     * @return the finished file on disk.
     */
    suspend fun download(
        magnetOrInfoHash: String,
        saveDir: File,
        preferredFileIndex: Int?,
        connectionsLimit: Int,
        onMetadata: (Metadata) -> Unit,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        ensureStarted(connectionsLimit)
        saveDir.mkdirs()

        val magnet = toMagnet(magnetOrInfoHash)
        onProgress(Progress(0, 0, 0, 0, fetchingMetadata = true))

        val torrentInfo = loadOrFetchMetadata(magnet)
            ?: throw DownloadException(
                "Could not find peers for this torrent (no metadata after ${METADATA_TIMEOUT_SEC}s). " +
                    "Try another stream."
            )

        val files = torrentInfo.files()
        val fileIndex = pickFileIndex(
            files = (0 until torrentInfo.numFiles()).map { files.filePath(it) to files.fileSize(it) },
            preferred = preferredFileIndex
        )
        val fileSize = files.fileSize(fileIndex)
        val relativePath = files.filePath(fileIndex)
        onMetadata(
            Metadata(
                name = torrentInfo.name(),
                fileIndex = fileIndex,
                fileName = File(relativePath).name,
                fileSize = fileSize
            )
        )

        // Everything except the chosen file is skipped, so the phone only stores the video.
        val priorities = Array(torrentInfo.numFiles()) { Priority.IGNORE }
        priorities[fileIndex] = Priority.TOP_PRIORITY

        val resumeFile = File(stateDir, "${torrentInfo.infoHash()}.resume").takeIf { it.exists() }

        session.download(
            torrentInfo,
            saveDir,
            resumeFile,
            priorities,
            null,
            // Sequential order means the start of the video is on disk first, so it can be
            // previewed while the rest arrives.
            TorrentFlags.SEQUENTIAL_DOWNLOAD
        )

        val handle = awaitHandle(torrentInfo)
            ?: throw DownloadException("The torrent could not be added to the session")
        handle.prioritizeFiles(priorities)

        try {
            var lastDone = 0L
            var lastTime = System.currentTimeMillis()
            while (true) {
                if (!isActive) throw kotlinx.coroutines.CancellationException()
                val status = handle.status()
                val done = handle.fileProgress().getOrElse(fileIndex) { status.totalWantedDone() }
                val now = System.currentTimeMillis()
                val elapsed = (now - lastTime).coerceAtLeast(1)

                onProgress(
                    Progress(
                        downloadedBytes = done,
                        speedBytesPerSec = ((done - lastDone) * 1000 / elapsed).coerceAtLeast(0),
                        peers = status.numPeers(),
                        seeds = status.numSeeds()
                    )
                )
                lastDone = done
                lastTime = now

                status.errorCode()?.takeIf { it.isError }?.let { error ->
                    throw DownloadException("Torrent error: ${error.message}")
                }
                if (done >= fileSize && fileSize > 0) break
                if (status.isFinished || status.isSeeding) break

                delay(PROGRESS_INTERVAL_MS)
            }
            // Flush libtorrent's cache so the file on disk is complete before anything reads it.
            handle.flushCache()
            saveResumeState(handle)
        } finally {
            // Stop the transfer but keep the downloaded file.
            runCatching { session.remove(handle) }
        }

        File(saveDir, relativePath).also {
            if (!it.exists()) throw DownloadException("Torrent finished but ${it.name} is missing")
        }
    }

    private fun loadOrFetchMetadata(magnet: String): TorrentInfo? {
        val hash = infoHashOf(magnet)
        val cached = hash?.let { File(stateDir, "$it.torrent") }
        if (cached?.exists() == true) {
            runCatching { return TorrentInfo(cached) }
        }
        val data = session.fetchMagnet(magnet, METADATA_TIMEOUT_SEC, stateDir) ?: return null
        val info = runCatching { TorrentInfo.bdecode(data) }.getOrNull() ?: return null
        cached?.let { runCatching { it.writeBytes(data) } }
        return info
    }

    private suspend fun awaitHandle(info: TorrentInfo): TorrentHandle? {
        repeat(HANDLE_ATTEMPTS) {
            val handle = session.find(info.infoHash())
            if (handle != null && handle.isValid) return handle
            delay(500)
        }
        return null
    }

    private fun saveResumeState(handle: TorrentHandle) {
        // Best effort: on the next run libtorrent rechecks what is already on disk anyway, and the
        // .torrent metadata is already cached from the magnet fetch.
        runCatching { handle.saveResumeData() }
    }

    companion object {
        private const val METADATA_TIMEOUT_SEC = 90
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val HANDLE_ATTEMPTS = 20

        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts", "mpg", "mpeg", "flv", "wmv"
        )

        /** Trackers worth trying when an addon supplies none. */
        private val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce"
        )

        fun toMagnet(input: String): String {
            if (input.startsWith("magnet:", ignoreCase = true)) return withFallbackTrackers(input)
            // A bare infoHash (40 hex chars, or 32 chars base32).
            return withFallbackTrackers("magnet:?xt=urn:btih:$input")
        }

        private fun withFallbackTrackers(magnet: String): String {
            if (magnet.contains("&tr=")) return magnet
            return magnet + FALLBACK_TRACKERS.joinToString("") {
                "&tr=" + java.net.URLEncoder.encode(it, "UTF-8")
            }
        }

        fun infoHashOf(magnet: String): String? =
            Regex("urn:btih:([A-Za-z0-9]+)").find(magnet)?.groupValues?.get(1)?.lowercase()

        /**
         * The addon's `fileIdx` when it is usable, otherwise the largest video file, otherwise the
         * largest file in the torrent. Multi-episode packs are exactly why `fileIdx` matters.
         *
         * Takes plain (path, size) pairs so the choice can be tested without a live torrent.
         */
        fun pickFileIndex(files: List<Pair<String, Long>>, preferred: Int?): Int {
            if (files.isEmpty()) return 0
            if (preferred != null && preferred in files.indices) return preferred

            val indexed = files.withIndex()
            val videos = indexed.filter { (_, file) ->
                file.first.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
            }
            val pool = if (videos.isEmpty()) indexed.toList() else videos
            return pool.maxByOrNull { it.value.second }?.index ?: 0
        }
    }
}
