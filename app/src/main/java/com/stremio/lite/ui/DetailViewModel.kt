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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A stream plus the addon that produced it, so the UI can say where it came from. */
data class StreamOption(
    val addon: InstalledAddon,
    val stream: Stream
)

data class DetailUiState(
    val loading: Boolean = true,
    val meta: MetaItem? = null,
    val selectedVideo: Video? = null,
    val streamsLoading: Boolean = false,
    val streams: List<StreamOption> = emptyList(),
    val error: String? = null,
    val message: String? = null
)

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    val downloads: StateFlow<List<DownloadTask>> = container.downloads.tasks

    private var type: String = ""
    private var id: String = ""

    fun load(type: String, id: String) {
        if (this.type == type && this.id == id && _state.value.meta != null) return
        this.type = type
        this.id = id
        _state.value = DetailUiState(loading = true)

        viewModelScope.launch {
            val meta = firstMeta(type, id)
            if (meta == null) {
                _state.value = DetailUiState(loading = false, error = "No addon could describe this item")
                return@launch
            }
            // Series start at the first episode; movies stream on their own id.
            val firstVideo = meta.videos.minByOrNull { (it.season ?: 0) * 1000 + (it.episode ?: 0) }
            _state.value = DetailUiState(loading = false, meta = meta, selectedVideo = firstVideo)
            loadStreams(firstVideo?.id ?: id)
        }
    }

    fun selectVideo(video: Video) {
        _state.value = _state.value.copy(selectedVideo = video, streams = emptyList())
        viewModelScope.launch { loadStreams(video.id) }
    }

    /** Asks every stream-capable addon at once; slow addons never block the fast ones. */
    private suspend fun loadStreams(videoId: String) {
        _state.value = _state.value.copy(streamsLoading = true, error = null)
        val addons = container.addons.addonsFor("stream", type, videoId)
        val options = coroutineScope {
            addons.map { addon ->
                async(Dispatchers.IO) {
                    runCatching { container.addonClient.streams(addon, type, videoId) }
                        .getOrElse { emptyList() }
                        .map { StreamOption(addon, it) }
                }
            }.flatMap { it.await() }
        }
        _state.value = _state.value.copy(
            streamsLoading = false,
            streams = options,
            error = if (options.isEmpty() && addons.isNotEmpty()) {
                "No streams found. Install a stream addon (e.g. Torrentio) or try another episode."
            } else if (addons.isEmpty()) {
                "None of your addons provide streams for this type."
            } else {
                null
            }
        )
    }

    private suspend fun firstMeta(type: String, id: String): MetaItem? = coroutineScope {
        val candidates = container.addons.addonsFor("meta", type, id)
        candidates.map { addon ->
            async(Dispatchers.IO) { runCatching { container.addonClient.meta(addon, type, id) }.getOrNull() }
        }.mapNotNull { it.await() }.firstOrNull()
    }

    /**
     * The download button. HTTP streams go through the chunked downloader; torrent streams are
     * fetched by the in-app BitTorrent engine, which pulls the video file out of the torrent.
     * Either way the file ends up on the phone.
     */
    fun download(option: StreamOption) {
        val meta = _state.value.meta
        val video = _state.value.selectedVideo
        val episodeLabel = video?.let { v ->
            v.season?.let { s -> v.episode?.let { e -> " S%02dE%02d".format(s, e) } } ?: " ${v.label}"
        }.orEmpty()
        val title = (meta?.name ?: "Stremio") + episodeLabel

        val httpUrl = option.stream.httpUrl
        val magnet = option.stream.magnetUri(title)

        if (httpUrl == null && magnet == null) {
            _state.value = _state.value.copy(message = "This stream has no file to download.")
            return
        }

        viewModelScope.launch {
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
                    metaId = meta?.id,
                    metaType = meta?.type,
                    poster = meta?.poster,
                    addonName = option.addon.name
                )
            }
            _state.value = _state.value.copy(
                message = result.fold(
                    onSuccess = {
                        if (it.isTorrent) "Torrent added — finding peers, then downloading to your phone"
                        else "Downloading \"${it.fileName}\" in the background"
                    },
                    onFailure = { "Could not start the download: ${it.readableMessage()}" }
                )
            )
            DownloadService.ensureRunning(getApplication())
        }
    }

    fun downloadFor(url: String?): DownloadTask? =
        url?.let { u -> downloads.value.firstOrNull { it.url == u } }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
