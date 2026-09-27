package com.stremio.lite.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stremio.lite.data.download.DownloadRepository
import com.stremio.lite.data.download.DownloadStatus
import com.stremio.lite.ui.DetailViewModel
import com.stremio.lite.ui.StreamOption

@Composable
fun DetailScreen(
    type: String,
    id: String,
    onBack: () -> Unit,
    onPlay: (url: String, headers: Map<String, String>) -> Unit
) {
    val viewModel: DetailViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(type, id) { viewModel.load(type, id) }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    if (state.loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val meta = state.meta

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    meta?.name ?: id,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        }

        if (meta != null) {
            item {
                Row(modifier = Modifier.padding(horizontal = 12.dp)) {
                    if (meta.poster != null) {
                        AsyncImage(
                            model = meta.poster,
                            contentDescription = meta.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(110.dp).height(165.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        val facts = listOfNotNull(
                            meta.releaseInfoText,
                            meta.runtime,
                            meta.imdbRatingText?.let { "IMDb $it" },
                            meta.allGenres.take(3).joinToString(", ").ifBlank { null }
                        )
                        facts.forEach {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            meta.description?.let { description ->
                item {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (meta.videos.isNotEmpty()) {
                item { SectionHeader("Episodes") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                        items(meta.videos.size) { index ->
                            val video = meta.videos[index]
                            val selected = state.selectedVideo?.id == video.id
                            FilledTonalButton(onClick = { viewModel.selectVideo(video) }) {
                                Text(
                                    text = video.season?.let { s -> video.episode?.let { e -> "S%02dE%02d".format(s, e) } }
                                        ?: video.label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
                state.selectedVideo?.let { video ->
                    item {
                        Text(
                            video.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        item { SectionHeader("Streams") }

        if (state.streamsLoading) {
            item { LoadingBox() }
        }

        items(state.streams.size) { index ->
            val option = state.streams[index]
            val task = downloads.firstOrNull { it.url == option.stream.httpUrl }
            StreamCard(
                option = option,
                downloadStatusText = task?.let { t ->
                    when (t.status) {
                        DownloadStatus.COMPLETED -> "Downloaded · ${DownloadRepository.formatBytes(t.downloadedBytes)}"
                        DownloadStatus.FAILED -> "Download failed: ${t.error.orEmpty()}"
                        DownloadStatus.PAUSED -> "Paused at ${(t.progress * 100).toInt()}%"
                        else -> "${(t.progress * 100).toInt()}% · ${DownloadRepository.formatBytes(t.speedBytesPerSec)}/s" +
                            " · ${t.connections} connections"
                    }
                },
                downloadProgress = task?.takeIf { it.isActive || it.status == DownloadStatus.PAUSED }?.progress,
                downloaded = task?.status == DownloadStatus.COMPLETED,
                onPlay = {
                    val local = task?.takeIf { it.status == DownloadStatus.COMPLETED }?.filePath
                    when {
                        local != null -> onPlay(local, emptyMap())
                        option.stream.httpUrl != null ->
                            onPlay(option.stream.httpUrl!!, option.stream.behaviorHints?.proxyHeaders?.request.orEmpty())
                        option.stream.externalUrl != null ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(option.stream.externalUrl)))
                        option.stream.isTorrent -> openMagnet(context, option, meta?.name)
                        else -> Toast.makeText(context, "This stream cannot be played here", Toast.LENGTH_SHORT).show()
                    }
                },
                onDownload = { viewModel.download(option) },
                onMagnet = { openMagnet(context, option, meta?.name) }
            )
        }

        if (!state.streamsLoading && state.streams.isEmpty()) {
            item { EmptyMessage(state.error ?: "No streams available.") }
        }
    }
}

@Composable
private fun StreamCard(
    option: StreamOption,
    downloadStatusText: String?,
    downloadProgress: Float?,
    downloaded: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onMagnet: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(option.stream.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                option.addon.name + if (option.stream.isTorrent) " · torrent" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            downloadStatusText?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(if (downloaded) "Play offline" else "Play")
                }
                if (option.stream.httpUrl != null) {
                    TextButton(onClick = onDownload, enabled = !downloaded) {
                        Icon(
                            if (downloaded) Icons.Filled.CheckCircle
                            else if (downloadProgress != null) Icons.Filled.Downloading
                            else Icons.Filled.Download,
                            contentDescription = null
                        )
                        Text(if (downloaded) "Saved" else "Download")
                    }
                } else if (option.stream.isTorrent) {
                    TextButton(onClick = onMagnet) {
                        Icon(Icons.Filled.Link, contentDescription = null)
                        Text("Open magnet")
                    }
                }
            }
        }
    }
}

private fun openMagnet(context: android.content.Context, option: StreamOption, title: String?) {
    val magnet = option.stream.magnetUri(title)
    if (magnet == null) {
        Toast.makeText(context, "No magnet link on this stream", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(magnet))) }
        .onFailure {
            Toast.makeText(context, "No torrent app installed to handle magnet links", Toast.LENGTH_LONG).show()
        }
}
