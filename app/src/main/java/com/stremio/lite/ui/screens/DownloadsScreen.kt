package com.stremio.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stremio.lite.data.download.DownloadRepository
import com.stremio.lite.data.download.DownloadStatus
import com.stremio.lite.data.download.DownloadTask
import com.stremio.lite.ui.theme.GlassBackground
import com.stremio.lite.ui.theme.Palette
import com.stremio.lite.ui.DownloadsViewModel

@Composable
fun DownloadsScreen(onPlayFile: (String) -> Unit) {
    val viewModel: DownloadsViewModel = viewModel()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Downloads", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Finished videos are copied to Movies/Stremio Lite on your phone " +
                        "(working copy: ${viewModel.downloadDirPath()})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    TextButton(onClick = { viewModel.resumeAll() }) { Text("Resume all") }
                    TextButton(onClick = { viewModel.pauseAll() }) { Text("Pause all") }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Speed settings", style = MaterialTheme.typography.titleSmall)

                    Text(
                        "Connections per file: ${settings.connectionsPerDownload}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = settings.connectionsPerDownload.toFloat(),
                        onValueChange = {
                            viewModel.updateSettings(settings.copy(connectionsPerDownload = it.toInt()))
                        },
                        valueRange = 1f..16f,
                        steps = 14
                    )

                    Text(
                        "Files at the same time: ${settings.parallelDownloads}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = settings.parallelDownloads.toFloat(),
                        onValueChange = {
                            viewModel.updateSettings(settings.copy(parallelDownloads = it.toInt()))
                        },
                        valueRange = 1f..6f,
                        steps = 4
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = settings.wifiOnly,
                            onCheckedChange = { viewModel.updateSettings(settings.copy(wifiOnly = it)) }
                        )
                        Text("Wi-Fi only", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        if (tasks.isEmpty()) {
            item { EmptyMessage("Nothing downloaded yet. Open a title, pick a stream and tap Download.") }
        }

        items(tasks.size) { index ->
            DownloadCard(
                task = tasks[index],
                onPause = { viewModel.pause(it) },
                onResume = { viewModel.resume(it) },
                onRetry = { viewModel.retry(it) },
                onDelete = { viewModel.remove(it, deleteFile = true) },
                onPlay = { onPlayFile(it.filePath) }
            )
        }
    }
}

@Composable
private fun DownloadCard(
    task: DownloadTask,
    onPause: (DownloadTask) -> Unit,
    onResume: (DownloadTask) -> Unit,
    onRetry: (DownloadTask) -> Unit,
    onDelete: (DownloadTask) -> Unit,
    onPlay: (DownloadTask) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                task.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (task.status != DownloadStatus.COMPLETED) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }

            Text(
                buildString {
                    append(DownloadRepository.formatBytes(task.downloadedBytes))
                    if (task.totalBytes > 0) append(" / ${DownloadRepository.formatBytes(task.totalBytes)}")
                    append(" · ${task.status.name.lowercase()}")
                    if (task.status == DownloadStatus.RUNNING) {
                        append(" · ${DownloadRepository.formatBytes(task.speedBytesPerSec)}/s")
                        if (task.isTorrent) {
                            append(" · ${task.seeds} seeds / ${task.peers} peers")
                        } else {
                            append(" · ${task.chunks.size} connections")
                        }
                        task.etaSeconds?.let { append(" · ${DownloadRepository.formatDuration(it)} left") }
                    }
                    if (task.fetchingMetadata) append(" · finding peers (DHT: ${task.dhtNodes})")
                    if (!task.isTorrent && !task.supportsRanges && task.status != DownloadStatus.COMPLETED) {
                        append(" · single connection (server has no range support)")
                    }
                    if (task.galleryUri != null) append(" · saved to your Movies library")
                },
                style = MaterialTheme.typography.labelSmall
            )
            task.error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (task.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> TextButton(onClick = { onPause(task) }) {
                        Icon(Icons.Filled.Pause, contentDescription = null)
                        Text("Pause")
                    }
                    DownloadStatus.PAUSED -> TextButton(onClick = { onResume(task) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Resume")
                    }
                    DownloadStatus.FAILED -> TextButton(onClick = { onRetry(task) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("Retry")
                    }
                    DownloadStatus.COMPLETED -> TextButton(onClick = { onPlay(task) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text("Play")
                    }
                }
                TextButton(onClick = { onDelete(task) }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("Delete")
                }
            }
        }
    }
}
