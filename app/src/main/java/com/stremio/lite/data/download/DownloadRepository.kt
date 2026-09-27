package com.stremio.lite.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import com.stremio.lite.data.addon.AddonJson
import com.stremio.lite.util.GalleryExport
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient

/**
 * Owns the download queue: which files are downloading, how many run at once, and the
 * persisted state that lets everything resume after the process is killed.
 */
class DownloadRepository(
    private val context: Context,
    client: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val downloader = ChunkedDownloader(client)
    private val torrents = TorrentEngine(context)
    private val streamServer = TorrentStreamServer(torrents)
    private val stateFile = File(context.filesDir, "downloads.json")
    private val settingsFile = File(context.filesDir, "download_settings.json")

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _settings = MutableStateFlow(DownloadSettings())
    val settings: StateFlow<DownloadSettings> = _settings.asStateFlow()

    val hasActiveDownloads = tasks.map { list -> list.any { it.isActive } }

    private val jobs = mutableMapOf<String, Job>()
    private var semaphore = Semaphore(_settings.value.parallelDownloads)

    init {
        scope.launch {
            _settings.value = readJson(settingsFile, DownloadSettings.serializer()) ?: DownloadSettings()
            semaphore = Semaphore(_settings.value.parallelDownloads)
            val restored = readJson(stateFile, ListSerializer(DownloadTask.serializer())).orEmpty()
                .map { task ->
                    // Anything that was mid-flight when we died comes back paused, not "running".
                    if (task.isActive) task.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0) else task
                }
            _tasks.value = restored
        }
    }

    // ---- queue operations ----

    /** Queues a stream for download. Returns the task, or a failure if the server cannot serve it. */
    suspend fun enqueue(
        url: String,
        suggestedName: String,
        title: String,
        metaId: String? = null,
        metaType: String? = null,
        poster: String? = null,
        addonName: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Result<DownloadTask> = runCatching {
        existingFor(url)?.let { return@runCatching it }

        val probe = downloader.probe(url, headers)
        val connections = _settings.value.connectionsPerDownload
        val fileName = sanitizeFileName(probe.fileName ?: suggestedName)
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = fileName,
            title = title,
            metaId = metaId,
            metaType = metaType,
            poster = poster,
            addonName = addonName,
            headers = headers,
            filePath = File(downloadDir(), fileName).absolutePath,
            totalBytes = probe.totalBytes,
            connections = connections,
            supportsRanges = probe.supportsRanges,
            chunks = ChunkedDownloader.planChunks(
                probe.totalBytes,
                if (probe.supportsRanges) connections else 1
            ),
            status = DownloadStatus.QUEUED
        )
        update(task)
        start(task.id)
        task
    }

    /**
     * Queues a torrent stream. Unlike the HTTP path there is nothing to probe up front: the size
     * and file name only exist once the metadata arrives over DHT, so the task starts as
     * "fetching metadata" and fills in afterwards.
     */
    suspend fun enqueueTorrent(
        magnet: String,
        title: String,
        fileIndex: Int? = null,
        trackers: List<String> = emptyList(),
        metaId: String? = null,
        metaType: String? = null,
        poster: String? = null,
        addonName: String? = null
    ): Result<DownloadTask> = runCatching {
        existingFor(magnet)?.let { return@runCatching it }

        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = magnet,
            kind = DownloadKind.TORRENT,
            fileName = sanitizeFileName(title),
            title = title,
            metaId = metaId,
            metaType = metaType,
            poster = poster,
            addonName = addonName,
            filePath = File(downloadDir(), sanitizeFileName(title)).absolutePath,
            totalBytes = -1,
            connections = _settings.value.connectionsPerDownload,
            supportsRanges = true,
            torrentFileIndex = fileIndex,
            trackers = trackers,
            fetchingMetadata = true,
            status = DownloadStatus.QUEUED
        )
        update(task)
        start(task.id)
        task
    }

    fun start(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (jobs[taskId]?.isActive == true) return
        if (task.status == DownloadStatus.COMPLETED) return

        update(task.copy(status = DownloadStatus.QUEUED, error = null))
        if (_settings.value.wifiOnly && isMeteredConnection()) {
            patch(taskId) {
                it.copy(status = DownloadStatus.PAUSED, error = "Waiting for Wi-Fi", speedBytesPerSec = 0)
            }
            persist()
            return
        }

        jobs[taskId] = scope.launch {
            semaphore.acquire()
            try {
                val current = _tasks.value.firstOrNull { it.id == taskId } ?: return@launch
                if (current.status == DownloadStatus.PAUSED) return@launch
                update(current.copy(status = DownloadStatus.RUNNING, error = null))

                if (current.isTorrent) {
                    runTorrent(current)
                } else {
                    downloader.download(
                        task = current,
                        onProgress = { bytes, speed ->
                            patch(taskId) { it.copy(downloadedBytes = bytes, speedBytesPerSec = speed) }
                        },
                        onChunks = { chunks ->
                            patch(taskId) { it.copy(chunks = chunks.map(ChunkState::copy)) }
                        }
                    )
                }

                patch(taskId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        downloadedBytes = if (it.totalBytes > 0) it.totalBytes else it.downloadedBytes,
                        speedBytesPerSec = 0,
                        fetchingMetadata = false,
                        completedAt = System.currentTimeMillis()
                    )
                }
                exportToGallery(taskId)
            } catch (e: CancellationException) {
                patch(taskId) {
                    if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0)
                    else it.copy(speedBytesPerSec = 0)
                }
                throw e
            } catch (e: Exception) {
                patch(taskId) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        error = e.message,
                        speedBytesPerSec = 0,
                        fetchingMetadata = false
                    )
                }
            } finally {
                semaphore.release()
                jobs.remove(taskId)
                persist()
            }
        }
    }

    /** Runs the torrent to completion, filling in metadata as soon as the swarm provides it. */
    private suspend fun runTorrent(task: DownloadTask) {
        val finalFile = torrents.download(
            taskId = task.id,
            magnetOrInfoHash = task.url,
            saveDir = File(downloadDir(), TORRENT_SUBDIR),
            preferredFileIndex = task.torrentFileIndex,
            connectionsLimit = task.connections * 20,
            extraTrackers = task.trackers,
            onMetadata = { meta ->
                patch(task.id) {
                    it.copy(
                        fileName = sanitizeFileName(meta.fileName),
                        totalBytes = meta.fileSize,
                        torrentFileIndex = meta.fileIndex,
                        fetchingMetadata = false
                    )
                }
            },
            onProgress = { progress ->
                patch(task.id) {
                    it.copy(
                        downloadedBytes = progress.downloadedBytes,
                        speedBytesPerSec = progress.speedBytesPerSec,
                        peers = progress.peers,
                        seeds = progress.seeds,
                        dhtNodes = progress.dhtNodes,
                        fetchingMetadata = progress.fetchingMetadata
                    )
                }
            }
        )
        // The video is buried in the torrent's folder structure; lift it into the downloads
        // directory so it sits next to the HTTP downloads.
        val target = File(downloadDir(), sanitizeFileName(finalFile.name))
        val moved = runCatching {
            if (finalFile.renameTo(target)) target
            else finalFile.copyTo(target, overwrite = true).also { finalFile.delete() }
        }.getOrDefault(finalFile)

        patch(task.id) { it.copy(filePath = moved.absolutePath, fileName = moved.name) }
    }

    /** Puts the finished video in the phone's Movies library so it appears in the gallery. */
    private suspend fun exportToGallery(taskId: String) {
        val task = _tasks.value.firstOrNull { it.id == taskId } ?: return
        val file = File(task.filePath)
        if (!file.exists()) return
        val uri = GalleryExport.export(context, file, task.fileName)
        if (uri != null) patch(taskId) { it.copy(galleryUri = uri) }
        persistNow()
    }

    fun pause(taskId: String) {
        patch(taskId) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0) }
        jobs.remove(taskId)?.cancel()
    }

    fun resume(taskId: String) = start(taskId)

    fun retry(taskId: String) {
        patch(taskId) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        start(taskId)
    }

    fun pauseAll() = _tasks.value.filter { it.isActive }.forEach { pause(it.id) }

    fun resumeAll() = _tasks.value.filter { it.status == DownloadStatus.PAUSED }.forEach { resume(it.id) }

    fun remove(taskId: String, deleteFile: Boolean) {
        jobs.remove(taskId)?.cancel()
        val task = _tasks.value.firstOrNull { it.id == taskId }
        _tasks.value = _tasks.value.filterNot { it.id == taskId }
        if (deleteFile && task != null) {
            scope.launch { runCatching { File(task.filePath).delete() } }
        }
        persist()
    }

    fun updateSettings(settings: DownloadSettings) {
        _settings.value = settings
        semaphore = Semaphore(settings.parallelDownloads.coerceAtLeast(1))
        scope.launch { writeJson(settingsFile, DownloadSettings.serializer(), settings) }
    }

    /** True when the only connection available costs money (mobile data, metered hotspot). */
    private fun isMeteredConnection(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return true
            val capabilities = manager.getNetworkCapabilities(network) ?: return true
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } else {
            @Suppress("DEPRECATION")
            manager.isActiveNetworkMetered
        }
    }

    /**
     * A local HTTP URL the player can open for a torrent that is still downloading. Null until the
     * engine has the metadata and the handle, which is when there is something to serve.
     */
    fun streamUrlFor(taskId: String): String? =
        if (torrents.activeTorrent(taskId) != null) streamServer.urlFor(taskId) else null

    /** Suspends until [taskId] can be streamed, or the task fails. */
    suspend fun awaitStreamUrl(taskId: String, timeoutMs: Long = 180_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val task = _tasks.value.firstOrNull { it.id == taskId } ?: return null
            if (task.status == DownloadStatus.FAILED) return null
            if (task.status == DownloadStatus.COMPLETED) return task.filePath
            streamUrlFor(taskId)?.let { return it }
            kotlinx.coroutines.delay(500)
        }
        return null
    }

    fun existingFor(url: String): DownloadTask? = _tasks.value.firstOrNull { it.url == url }

    fun completedFileFor(url: String): File? = _tasks.value
        .firstOrNull { it.url == url && it.status == DownloadStatus.COMPLETED }
        ?.let { File(it.filePath).takeIf(File::exists) }

    // ---- storage ----

    /** App-private external dir: no storage permission needed on any supported API level. */
    fun downloadDir(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "downloads")
        dir.mkdirs()
        return dir
    }

    private fun update(task: DownloadTask) {
        val existing = _tasks.value.indexOfFirst { it.id == task.id }
        _tasks.value = if (existing >= 0) {
            _tasks.value.toMutableList().also { it[existing] = task }
        } else {
            _tasks.value + task
        }
        persist()
    }

    private fun patch(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        val index = _tasks.value.indexOfFirst { it.id == taskId }
        if (index < 0) return
        _tasks.value = _tasks.value.toMutableList().also { it[index] = transform(it[index]) }
    }

    private var persistJob: Job? = null

    /** Progress changes several times per second; writing state is debounced. */
    private fun persist() {
        if (persistJob?.isActive == true) return
        persistJob = scope.launch {
            kotlinx.coroutines.delay(PERSIST_DEBOUNCE_MS)
            writeJson(stateFile, ListSerializer(DownloadTask.serializer()), _tasks.value)
        }
    }

    fun persistNow() {
        scope.launch { writeJson(stateFile, ListSerializer(DownloadTask.serializer()), _tasks.value) }
    }

    private suspend fun <T> readJson(file: File, serializer: kotlinx.serialization.KSerializer<T>): T? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            runCatching { AddonJson.json.decodeFromString(serializer, file.readText()) }.getOrNull()
        }

    private suspend fun <T> writeJson(file: File, serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(AddonJson.json.encodeToString(serializer, value)) }
            Unit
        }

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 1_500L
        private const val TORRENT_SUBDIR = "torrent-data"

        fun sanitizeFileName(name: String): String {
            val cleaned = name.trim()
                .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_")
                .replace(Regex("_{2,}"), "_")
                .take(120)
                .ifBlank { "stremio-download" }
            return if (cleaned.contains('.')) cleaned else "$cleaned.mp4"
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 0) return "?"
            val units = listOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            return if (unit == 0) "${bytes} B" else String.format("%.1f %s", value, units[unit])
        }

        fun formatDuration(seconds: Long): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
        }
    }
}
