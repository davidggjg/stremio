package com.stremio.lite.data.download

import kotlinx.serialization.Serializable

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

@Serializable
data class ChunkState(
    val index: Int,
    val start: Long,
    val end: Long,
    /** Bytes already written for this chunk; the next request resumes at start + downloaded. */
    var downloaded: Long = 0
) {
    val size: Long get() = end - start + 1
    val isComplete: Boolean get() = downloaded >= size
    val remainingFrom: Long get() = start + downloaded
}

@Serializable
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val title: String,
    /** Where this came from, so the UI can link back to the show/movie. */
    val metaId: String? = null,
    val metaType: String? = null,
    val poster: String? = null,
    val addonName: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val filePath: String,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val connections: Int = 8,
    val supportsRanges: Boolean = false,
    val chunks: List<ChunkState> = emptyList(),
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    /** Transient, not worth persisting accurately: bytes/second of the last sample window. */
    val speedBytesPerSec: Long = 0
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING

    val etaSeconds: Long?
        get() = if (speedBytesPerSec > 0 && totalBytes > 0) {
            ((totalBytes - downloadedBytes) / speedBytesPerSec).coerceAtLeast(0)
        } else {
            null
        }
}

@Serializable
data class DownloadSettings(
    /** Range requests opened in parallel for a single file. */
    val connectionsPerDownload: Int = 8,
    /** Files downloaded at the same time. */
    val parallelDownloads: Int = 2,
    val wifiOnly: Boolean = false
)
