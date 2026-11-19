package com.stremio.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stremio.lite.data.addon.RowStatus
import com.stremio.lite.ui.BoardViewModel
import com.stremio.lite.ui.theme.GlassBackground
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun BoardScreen(viewModel: BoardViewModel, onOpen: (type: String, id: String) -> Unit) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Only the rows on screen are ever requested — this is stremio-core's LoadRange, and it is
    // the difference between the board appearing at once and waiting for every addon.
    LaunchedEffect(listState, rows.size) {
        snapshotFlow {
            val info = listState.layoutInfo.visibleItemsInfo
            (info.firstOrNull()?.index ?: 0) to (info.lastOrNull()?.index ?: 0)
        }
            .distinctUntilChanged()
            .debounce(80)
            .collect { (first, last) -> viewModel.onVisibleRange(first, last) }
    }

    GlassBackground {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                ScreenTitle(
                    title = "ראשי",
                    subtitle = "${addons.size} תוספים · ${rows.size} קטלוגים"
                )
            }

            if (library.isNotEmpty()) {
                item { SectionHeader("ממשיכים לצפות") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(library.size) { index ->
                            val item = library[index]
                            PosterCard(
                                title = item.name.ifBlank { item.id },
                                poster = item.poster,
                                subtitle = item.state?.takeIf { it.progress > 0f }
                                    ?.let { "נצפה ${(it.progress * 100).toInt()}%" },
                                onClick = { onOpen(item.type, item.id) }
                            )
                        }
                    }
                }
            }

            items(rows.size, key = { rows[it].key }) { index ->
                val row = rows[index]
                CatalogRowView(
                    row = row,
                    onOpen = { meta -> onOpen(meta.type.ifBlank { row.catalog.type }, meta.id) },
                    onLoadMore = { viewModel.loadNextPage(row.key) },
                    onRetry = { viewModel.retryRow(row.key) }
                )
            }

            if (rows.isEmpty()) {
                item {
                    EmptyMessage(
                        "אין עדיין קטלוגים. התחבר לחשבון Stremio שלך או התקן תוספים כדי למלא את המסך."
                    )
                }
            }

            if (rows.isNotEmpty() && rows.all { it.status == RowStatus.ERROR }) {
                item { EmptyMessage("אף תוסף לא הגיב. בדוק את החיבור ונסה שוב.") }
            }
        }
    }
}
