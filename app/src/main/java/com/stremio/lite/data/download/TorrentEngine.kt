package com.stremio.lite.data.download

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack

/**
 * In-app BitTorrent engine (libtorrent through libtorrent4j).
 *
 * A torrent stream from an addon is an infoHash plus trackers, so the flow is: fetch the metadata
 * over DHT and the trackers, pick the video file, and download only that file. While it downloads
 * the live handle is published in [active] so [TorrentStreamServer] can serve the bytes that have
 * already arrived — that is what makes a torrent playable before it has finished, the same trick
 * Stremio's own local server does.
 */
class TorrentEngine(context: Context) {

    private val session = SessionManager()
    private val stateDir = File(context.filesDir, "torrents").apply { mkdirs() }

    /** Torrents currently being downloaded, by task id. */
    private val active = ConcurrentHashMap<String, ActiveTorrent>()

    class ActiveTorrent(
        val handle: TorrentHandle,
        val info: TorrentInfo,
        val fileIndex: Int,
        val file: File
    ) {
        val fileSize: Long = info.files().fileSize(fileIndex)
        private val fileOffset: Long = info.files().fileOffset(fileIndex)
        private val pieceLength: Int = info.pieceLength()

        fun pieceOf(positionInFile: Long): Int = ((fileOffset + positionInFile) / pieceLength).toInt()

        fun hasByte(positionInFile: Long): Boolean = handle.havePiece(pieceOf(positionInFile))

        /**
         * Tells libtorrent we are about to read here: the pieces around [positionInFile] get
         * deadlines so they are fetched before the rest of the file.
         */
        fun prioritizeAround(positionInFile: Long, pieces: Int = READ_AHEAD_PIECES) {
            val first = pieceOf(positionInFile)
            val last = (first + pieces).coerceAtMost(info.numPieces() - 1)
            for (piece in first..last) {
                if (!handle.havePiece(piece)) {
                    handle.piecePriority(piece, Priority.TOP_PRIORITY)
                    // Deadline in ms; earlier pieces are wanted sooner.
                    handle.setPieceDeadline(piece, (piece - first) * DEADLINE_STEP_MS)
                }
            }
        }

        private companion object {
            const val READ_AHEAD_PIECES = 12
            const val DEADLINE_STEP_MS = 400
        }
    }

    fun activeTorrent(taskId: String): ActiveTorrent? = active[taskId]

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
        val fetchingMetadata: Boolean = false,
        /** Nodes the DHT has found; 0 for a long time means the network is blocking DHT. */
        val dhtNodes: Long = 0
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
            // Peer discovery has to be switched on explicitly: without DHT an addon's infoHash
            // finds nobody, which shows up as a torrent stuck on "0 sources".
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            .setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            .setString(
                settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                DHT_BOOTSTRAP_NODES
            )
            .setString(
                settings_pack.string_types.listen_interfaces.swigValue(),
                "0.0.0.0:6881,[::]:6881"
            )
            .setString(settings_pack.string_types.user_agent.swigValue(), USER_AGENT)

        session.start(SessionParams(settings))
    }

    /** How many DHT nodes the session knows about — the honest answer to "why 0 sources". */
    fun dhtNodes(): Long = if (session.isRunning) session.dhtNodes() else 0

    @Synchronized
    fun shutdown() {
        active.clear()
        if (session.isRunning) session.stop()
    }

    /**
     * Downloads one file out of the torrent, keeping the handle published for the stream server.
     *
     * @param preferredFileIndex the addon's `fileIdx`, when it provided one.
     * @return the finished file on disk.
     */
    suspend fun download(
        taskId: String,
        magnetOrInfoHash: String,
        saveDir: File,
        preferredFileIndex: Int?,
        connectionsLimit: Int,
        extraTrackers: List<String> = emptyList(),
        onMetadata: (Metadata) -> Unit,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        ensureStarted(connectionsLimit)
        saveDir.mkdirs()

        val magnet = toMagnet(magnetOrInfoHash)
        onProgress(Progress(0, 0, 0, 0, fetchingMetadata = true, dhtNodes = dhtNodes()))

        val torrentInfo = loadOrFetchMetadata(magnet) { onProgress(
            Progress(0, 0, 0, 0, fetchingMetadata = true, dhtNodes = dhtNodes())
        ) } ?: throw DownloadException(
            if (dhtNodes() == 0L) {
                "No peers and no DHT nodes: this network is blocking BitTorrent. Try Wi-Fi " +
                    "instead of mobile data, or pick another stream."
            } else {
                "Could not find peers for this torrent after ${METADATA_TIMEOUT_SEC}s. Try another stream."
            }
        )

        val files = torrentInfo.files()
        val fileIndex = pickFileIndex(
            files = (0 until torrentInfo.numFiles()).map { files.filePath(it) to files.fileSize(it) },
            preferred = preferredFileIndex
        )
        val fileSize = files.fileSize(fileIndex)
        val relativePath = files.filePath(fileIndex)
        val targetFile = File(saveDir, relativePath)
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
            // Sequential order means the start of the video lands first, so playback can begin
            // while the rest is still arriving.
            TorrentFlags.SEQUENTIAL_DOWNLOAD
        )

        val handle = awaitHandle(torrentInfo)
            ?: throw DownloadException("The torrent could not be added to the session")
        handle.prioritizeFiles(priorities)

        // Trackers from the addon (and public fallbacks) are attached to the live torrent too:
        // metadata restored from cache carries none, leaving DHT as the only source.
        (extraTrackers + FALLBACK_TRACKERS).distinct().forEach { url ->
            runCatching { handle.addTracker(AnnounceEntry(url)) }
        }
        runCatching { handle.forceReannounce() }
        runCatching { handle.forceDHTAnnounce() }

        active[taskId] = ActiveTorrent(handle, torrentInfo, fileIndex, targetFile)

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
                        seeds = status.numSeeds(),
                        dhtNodes = dhtNodes()
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
            runCatching { handle.saveResumeData() }
        } finally {
            active.remove(taskId)
            // Stop the transfer but keep the downloaded file.
            runCatching { session.remove(handle) }
        }

        targetFile.also {
            if (!it.exists()) throw DownloadException("Torrent finished but ${it.name} is missing")
        }
    }

    private suspend fun loadOrFetchMetadata(magnet: String, onTick: () -> Unit): TorrentInfo? {
        val hash = infoHashOf(magnet)
        val cached = hash?.let { File(stateDir, "$it.torrent") }
        if (cached?.exists() == true) {
            runCatching { return TorrentInfo(cached) }
        }
        // Give DHT a moment to find nodes before asking it for a torrent nobody announced yet.
        repeat(DHT_WARMUP_TICKS) {
            if (dhtNodes() > 0) return@repeat
            onTick()
            delay(1_000)
        }
        val data = withContext(Dispatchers.IO) {
            session.fetchMagnet(magnet, METADATA_TIMEOUT_SEC, stateDir)
        } ?: return null
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

    companion object {
        private const val METADATA_TIMEOUT_SEC = 120
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val HANDLE_ATTEMPTS = 20
        private const val DHT_WARMUP_TICKS = 15
        private const val USER_AGENT = "StremioLite/1.1 libtorrent/2.0"

        private const val DHT_BOOTSTRAP_NODES =
            "router.bittorrent.com:6881,router.utorrent.com:6881," +
                "dht.transmissionbt.com:6881,dht.libtorrent.org:25401"

        private val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "m2ts", "mpg", "mpeg", "flv", "wmv"
        )

        /** Trackers worth trying when an addon supplies none. */
        val FALLBACK_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.tracker.cl:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://tracker.torrent.eu.org:451/announce"
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
