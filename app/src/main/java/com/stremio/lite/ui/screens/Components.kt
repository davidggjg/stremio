package com.stremio.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stremio.lite.data.addon.CatalogRow
import com.stremio.lite.data.addon.MetaItem
import com.stremio.lite.data.addon.RowStatus
import com.stremio.lite.ui.theme.GlassCard
import com.stremio.lite.ui.theme.GlassChip
import com.stremio.lite.ui.theme.Palette
import com.stremio.lite.ui.theme.glass

private val PosterWidth = 124.dp

@Composable
fun PosterCard(
    title: String,
    poster: String?,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(modifier = modifier.width(PosterWidth).padding(6.dp)) {
        GlassCard(
            shape = RoundedCornerShape(14.dp),
            elevation = 12.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            onClick = onClick
        ) {
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = title.take(2).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Palette.TextFaint
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Placeholder shown while a row is still being fetched, so the board has shape immediately. */
@Composable
private fun PosterSkeleton() {
    Column(modifier = Modifier.width(PosterWidth).padding(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .glass(RoundedCornerShape(14.dp), elevation = 0.dp)
        )
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.8f)
                .height(10.dp)
                .glass(RoundedCornerShape(6.dp), elevation = 0.dp)
        )
    }
}

/**
 * One board row. It reports when it nears its end so the next page (`skip`) can be fetched, which
 * is how Stremio pages catalogs.
 */
@Composable
fun CatalogRowView(
    row: CatalogRow,
    onOpen: (MetaItem) -> Unit,
    onLoadMore: () -> Unit = {},
    onRetry: () -> Unit = {}
) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, color = Palette.TextPrimary)
                Text(row.subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
            }
            if (row.status == RowStatus.LOADING || row.loadingMore) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Palette.Accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        when {
            row.status == RowStatus.ERROR -> Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    row.error ?: "לא ניתן לטעון",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextFaint,
                    modifier = Modifier.weight(1f)
                )
                GlassChip("נסה שוב", onClick = onRetry)
            }

            row.items.isEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(6) { PosterSkeleton() }
            }

            else -> {
                val listState = rememberLazyListState()
                val nearEnd by remember(row.key, row.items.size) {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= row.items.size - 4
                    }
                }
                LaunchedEffect(nearEnd) { if (nearEnd && row.supportsPaging && !row.endReached) onLoadMore() }

                LazyRow(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(row.items.size) { index ->
                        val meta = row.items[index]
                        PosterCard(
                            title = meta.name.ifBlank { meta.id },
                            poster = meta.poster,
                            subtitle = meta.releaseInfoText,
                            onClick = { onOpen(meta) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp)
    }
}

@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Palette.TextPrimary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint)
        }
    }
}
