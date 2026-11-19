package com.stremio.lite.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stremio.lite.data.addon.MetaItem
import com.stremio.lite.data.download.DownloadRepository
import com.stremio.lite.data.download.DownloadStatus
import com.stremio.lite.data.download.DownloadTask
import com.stremio.lite.ui.DetailViewModel
import com.stremio.lite.ui.StreamOption
import com.stremio.lite.ui.theme.GlassBackground
import com.stremio.lite.ui.theme.GlassCard
import com.stremio.lite.ui.theme.GlassChip
import com.stremio.lite.ui.theme.Palette
import com.stremio.lite.ui.theme.glass

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

    GlassBackground {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Palette.Accent)
            }
            return@GlassBackground
        }

        val meta = state.meta

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item { Hero(meta = meta, fallbackTitle = id, onBack = onBack) }

            meta?.description?.let { description ->
                item {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            if (!meta?.videos.isNullOrEmpty()) {
                item { SectionHeader("פרקים") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val videos = meta!!.videos
                        items(videos.size) { index ->
                            val video = videos[index]
                            GlassChip(
                                text = video.season?.let { s ->
                                    video.episode?.let { e -> "S%02dE%02d".format(s, e) }
                                } ?: video.label,
                                selected = state.selectedVideo?.id == video.id,
                                onClick = { viewModel.selectVideo(video) }
                            )
                        }
                    }
                }
                state.selectedVideo?.let { video ->
                    item {
                        Text(
                            video.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.TextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("מקורות", style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
                    Spacer(Modifier.width(10.dp))
                    if (state.streamsLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Palette.Accent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${state.pendingAddons} תוספים עדיין עונים",
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextFaint
                        )
                    }
                }
            }

            // Source tabs, the way Stremio groups streams by the addon that produced them.
            if (state.addonTabs.size > 1) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        item {
                            GlassChip(
                                text = "הכל ${state.streams.size}",
                                selected = state.addonFilter == null,
                                onClick = { viewModel.selectAddonFilter(null) }
                            )
                        }
                        items(state.addonTabs.size) { index ->
                            val name = state.addonTabs[index]
                            val count = state.streams.count { it.addon.name == name }
                            GlassChip(
                                text = "$name $count",
                                selected = state.addonFilter == name,
                                onClick = { viewModel.selectAddonFilter(name) }
                            )
                        }
                    }
                }
            }

            val visible = state.visibleStreams
            items(visible.size) { index ->
                val option = visible[index]
                val key = option.stream.httpUrl ?: option.stream.magnetUri(meta?.name)
                val task = downloads.firstOrNull { it.url == key }
                StreamCard(
                    option = option,
                    task = task,
                    onPlay = {
                        val local = task?.takeIf { it.status == DownloadStatus.COMPLETED }?.filePath
                        when {
                            local != null -> onPlay(local, emptyMap())
                            option.stream.httpUrl != null -> onPlay(
                                option.stream.httpUrl!!,
                                option.stream.behaviorHints?.proxyHeaders?.request.orEmpty()
                            )
                            option.stream.isTorrent -> viewModel.playTorrent(option) { url ->
                                onPlay(url, emptyMap())
                            }
                            option.stream.externalUrl != null ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(option.stream.externalUrl)))
                            else -> Toast.makeText(context, "לא ניתן לנגן את המקור הזה", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDownload = { viewModel.download(option) }
                )
            }

            if (!state.streamsLoading && state.streams.isEmpty()) {
                item { EmptyMessage(state.error ?: "אין מקורות זמינים.") }
            }

            // Which addons were asked and what each said — so "no sources" is never a mystery.
            if (state.sources.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "מקורות שנשאלו",
                            style = MaterialTheme.typography.labelMedium,
                            color = Palette.TextFaint
                        )
                        state.sources.forEach { report ->
                            Text(
                                text = buildString {
                                    append("· ${report.addonName}: ")
                                    append(
                                        when {
                                            report.skippedByManifest -> "דולג (לפי המניפסט אין לו כלום לזה)"
                                            report.failed -> "לא ענה"
                                            report.count == 0 -> "אין מקורות"
                                            else -> "${report.count} מקורות"
                                        }
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = Palette.TextFaint
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(meta: MetaItem?, fallbackTitle: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
        val backdrop = meta?.background ?: meta?.poster
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = meta?.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Scrim so the title stays readable over any artwork and the hero melts into the page.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x66000000),
                        0.55f to Color(0x99101219),
                        1f to Palette.Base
                    )
                )
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(12.dp)
                .glass(CircleShape, elevation = 6.dp)
                .size(40.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזור", tint = Palette.TextPrimary)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
        ) {
            Text(
                meta?.name ?: fallbackTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val facts = listOfNotNull(
                meta?.releaseInfoText,
                meta?.runtime,
                meta?.imdbRatingText?.let { "IMDb $it" },
                meta?.allGenres?.take(2)?.joinToString(", ")?.ifBlank { null }
            )
            if (facts.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    facts.take(3).forEach { GlassChip(it) }
                }
            }
        }
    }
}

@Composable
private fun StreamCard(
    option: StreamOption,
    task: DownloadTask?,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    val downloaded = task?.status == DownloadStatus.COMPLETED
    val active = task?.isActive == true || task?.status == DownloadStatus.PAUSED

    GlassCard(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (downloaded) Palette.Success else Palette.Accent),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "נגן", tint = Color.White)
                }
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    option.stream.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    option.addon.name + if (option.stream.isTorrent) " · טורנט" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint
                )
                if (active && task != null) {
                    LinearProgressIndicator(
                        progress = { task.progress },
                        color = Palette.Accent,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    Text(
                        text = if (task.fetchingMetadata) {
                            "מחפש מקורות… (DHT: ${task.dhtNodes})"
                        } else {
                            buildString {
                                append("${(task.progress * 100).toInt()}%")
                                append(" · ${DownloadRepository.formatBytes(task.speedBytesPerSec)}/s")
                                if (task.isTorrent) append(" · ${task.seeds} משתפים / ${task.peers} עמיתים")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextFaint,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            IconButton(onClick = onDownload, enabled = !downloaded) {
                Icon(
                    if (downloaded) Icons.Filled.CheckCircle else Icons.Filled.Download,
                    contentDescription = "הורד",
                    tint = if (downloaded) Palette.Success else Palette.TextSecondary
                )
            }
        }
    }
}
