package com.stremio.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremio.lite.AppContainer
import com.stremio.lite.data.addon.InstalledAddon
import com.stremio.lite.data.addon.MetaItem
import com.stremio.lite.data.addon.Stream
import com.stremio.lite.data.addon.Video
import com.stremio.lite.data.download.DownloadService
import com.stremio.lite.data.download.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A stream plus the addon that produced it, so the UI can group and label by source. */
data class StreamOption(
    val addon: InstalledAddon,
    val stream: Stream
)

/** What one addon had to say about this video — shown so "no sources" is never a mystery. */
data class SourceReport(
    val addonName: String,
    val count: Int,
    val failed: Boolean,
    val skippedByManifest: Boolean = false
)

data class DetailUiState(
    val loading: Boolean = true,
    val meta: MetaItem? = null,
    val selectedVideo: Video? = null,
    val streamsLoading: Boolean = false,
    val streams: List<StreamOption> = emptyList(),
    val sources: List<SourceReport> = emptyList(),
    val pendingAddons: Int = 0,
    /** Null means "All"; otherwise only this addon's streams are shown. */
    val addonFilter: String? = null,
    val error: String? = null,
    val message: String? = null
) {
    val visibleStreams: List<StreamOption>
        get() = addonFilter?.let { name -> streams.filter { it.addon.name == name } } ?: streams

    val addonTabs: List<String>
        get() = streams.map { it.addon.name }.distinct()
}

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    val downloads: StateFlow<List<DownloadTask>> = container.downloads.tasks

    private var type: String = ""
    private var id: String = ""
    private var currentVideoId: String = ""
    private val streamJobs = mutableListOf<Job>()
    private val mutex = Mutex()

    fun load(type: String, id: String) {
        if (this.type == type && this.id == id && _state.value.meta != null) return
        this.type = type
        this.id = id
        _state.value = DetailUiState(loading = true)

        viewModelScope.launch {
            val meta = firstMeta(type, id)
            if (meta == null) {
                _state.value = DetailUiState(loading = false, error = "אף תוסף לא יודע לתאר את הפריט הזה")
                return@launch
            }
            val firstVideo = meta.videos.minByOrNull { (it.season ?: 0) * 10_000 + (it.episode ?: 0) }
            _state.value = DetailUiState(loading = false, meta = meta, selectedVideo = firstVideo)
            loadStreams(firstVideo?.id ?: id)
        }
    }

    fun selectVideo(video: Video) {
        _state.value = _state.value.copy(selectedVideo = video, streams = emptyList(), sources = emptyList())
        loadStreams(video.id)
    }

    fun selectAddonFilter(name: String?) {
        _state.value = _state.value.copy(addonFilter = name)
    }

    /**
     * Streams arrive one addon at a time, the way stremio-core keeps a `ResourceLoadable` per
     * addon: whoever answers first is on screen first.
     *
     * Addon selection is deliberately forgiving. Filtering strictly on the manifest's `types` and
     * `idPrefixes` silently excluded addons that do serve the item — the old build showed "none of
     * your addons provide streams" while Stremio, asking the same addons, got plenty. Asking an
     * addon that has nothing costs one request and it answers with an empty list.
     */
    private fun loadStreams(videoId: String) {
        streamJobs.forEach { it.cancel() }
        streamJobs.clear()
        currentVideoId = videoId

        val declared = container.addons.addons.value.filter { it.manifest.supports("stream") }
        val matching = declared.filter { it.manifest.supports("stream", type, videoId) }
        val targets = matching.ifEmpty { declared }
        val skipped = declared.filterNot { addon -> targets.any { it.transportUrl == addon.transportUrl } }

        if (targets.isEmpty()) {
            _state.value = _state.value.copy(
                streamsLoading = false,
                streams = emptyList(),
                error = "לא מותקן תוסף מקורות. הוסף אחד (למשל Torrentio) בטאב התוספים."
            )
            return
        }

        _state.value = _state.value.copy(
            streamsLoading = true,
            streams = emptyList(),
            addonFilter = null,
            pendingAddons = targets.size,
            sources = skipped.map { SourceReport(it.name, 0, failed = false, skippedByManifest = true) },
            error = null
        )

        targets.forEach { addon ->
            streamJobs += viewModelScope.launch {
                val streams = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                    runCatching {
                        container.addonClient.streams(addon, type, videoId)
                    }.getOrNull()
                }

                mutex.withLock {
                    if (currentVideoId != videoId) return@withLock
                    val current = _state.value
                    val pending = (current.pendingAddons - 1).coerceAtLeast(0)
                    val merged = current.streams + streams.orEmpty().map { StreamOption(addon, it) }
                    _state.value = current.copy(
                        streams = merged,
                        sources = current.sources + SourceReport(
                            addonName = addon.name,
                            count = streams?.size ?: 0,
                            failed = streams == null
                        ),
                        pendingAddons = pending,
                        streamsLoading = pending > 0,
                        error = if (pending == 0 && merged.isEmpty()) {
                            "אף תוסף לא החזיר מקור לפרק הזה."
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    private suspend fun firstMeta(type: String, id: String): MetaItem? = coroutineScope {
        val declared = container.addons.addons.value.filter { it.manifest.supports("meta") }
        val candidates = declared.filter { it.manifest.supports("meta", type, id) }.ifEmpty { declared }
        candidates.map { addon ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(META_TIMEOUT_MS) {
                    runCatching { container.addonClient.meta(addon, type, id) }.getOrNull()
                }
            }
        }.mapNotNull { it.await() }.firstOrNull()
    }

    /**
     * The download button. HTTP streams go through the chunked downloader; torrent streams are
     * fetched by the in-app BitTorrent engine. Either way the file ends up on the phone.
     */
    fun download(option: StreamOption) {
        val title = titleFor()
        val httpUrl = option.stream.httpUrl
        val magnet = option.stream.magnetUri(title)

        if (httpUrl == null && magnet == null) {
            _state.value = _state.value.copy(message = "למקור הזה אין קובץ להורדה.")
            return
        }

        viewModelScope.launch {
            val meta = _state.value.meta
            val result = if (httpUrl != null) {
                container.downloads.enqueue(
                    url = httpUrl,
                    suggestedName = option.stream.behaviorHints?.filename ?: "$title.mp4",
                    title = title,
                    metaId = meta?.id,
                    metaType = meta?.type,
                    poster = meta?.poster,
                    addonName = option.addon.name,
                    headers = option.stream.behaviorHints?.proxyHeaders?.request.orEmpty()
                )
            } else {
                container.downloads.enqueueTorrent(
                    magnet = magnet!!,
                    title = title,
                    fileIndex = option.stream.fileIdx,
                    trackers = trackersOf(option),
                    metaId = meta?.id,
                    metaType = meta?.type,
                    poster = meta?.poster,
                    addonName = option.addon.name
                )
            }
            _state.value = _state.value.copy(
                message = result.fold(
                    onSuccess = {
                        if (it.isTorrent) "הטורנט נוסף — מחפש מקורות ואז מוריד לטלפון"
                        else "מוריד את \"${it.fileName}\" ברקע"
                    },
                    onFailure = { "לא ניתן להתחיל את ההורדה: ${it.readableMessage()}" }
                )
            )
            DownloadService.ensureRunning(getApplication())
        }
    }

    /**
     * Play for a torrent: start the transfer, then hand the player a local URL served by the
     * stream server while the file is still downloading.
     */
    fun playTorrent(option: StreamOption, onReady: (String) -> Unit) {
        val title = titleFor()
        val magnet = option.stream.magnetUri(title) ?: run {
            _state.value = _state.value.copy(message = "למקור הזה אין קישור מגנט.")
            return
        }

        _state.value = _state.value.copy(message = "מתחיל טורנט — הניגון יתחיל ברגע שיגיעו החלקים הראשונים")
        viewModelScope.launch {
            val meta = _state.value.meta
            val existing = container.downloads.existingFor(magnet)
            val task = existing ?: container.downloads.enqueueTorrent(
                magnet = magnet,
                title = title,
                fileIndex = option.stream.fileIdx,
                trackers = trackersOf(option),
                metaId = meta?.id,
                metaType = meta?.type,
                poster = meta?.poster,
                addonName = option.addon.name
            ).getOrNull()

            if (task == null) {
                _state.value = _state.value.copy(message = "לא ניתן להתחיל את הטורנט הזה.")
                return@launch
            }
            if (existing != null && !existing.isActive) container.downloads.resume(existing.id)
            DownloadService.ensureRunning(getApplication())

            val url = container.downloads.awaitStreamUrl(task.id)
            if (url == null) {
                val failure = downloads.value.firstOrNull { it.id == task.id }?.error
                _state.value = _state.value.copy(message = failure ?: "עדיין לא נמצאו מקורות לטורנט הזה.")
            } else {
                onReady(url)
            }
        }
    }

    private fun trackersOf(option: StreamOption): List<String> = option.stream.sources
        .filter { it.startsWith("tracker:") }
        .map { it.removePrefix("tracker:") }

    private fun titleFor(): String {
        val meta = _state.value.meta
        val video = _state.value.selectedVideo
        val episodeLabel = video?.let { v ->
            v.season?.let { s -> v.episode?.let { e -> " S%02dE%02d".format(s, e) } } ?: " ${v.label}"
        }.orEmpty()
        return (meta?.name ?: "Stremio") + episodeLabel
    }

    fun downloadFor(url: String?): DownloadTask? =
        url?.let { u -> downloads.value.firstOrNull { it.url == u } }

    override fun onCleared() {
        streamJobs.forEach { it.cancel() }
        super.onCleared()
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        const val STREAM_TIMEOUT_MS = 15_000L
        const val META_TIMEOUT_MS = 12_000L
    }
}
