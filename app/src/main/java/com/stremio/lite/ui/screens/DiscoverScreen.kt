package com.stremio.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stremio.lite.ui.DiscoverViewModel
import com.stremio.lite.ui.theme.GlassBackground
import com.stremio.lite.ui.theme.GlassChip

/**
 * Catalogs the board cannot show: anything that *requires* an extra such as a genre, plus every
 * catalog of every addon with paging, filtered by type.
 */
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel, onOpen: (type: String, id: String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) viewModel.loadMore() }

    GlassBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTitle("Discover", state.selected?.let { "${it.addon.name} · ${it.label}" })

            if (state.types.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    items(state.types.size) { index ->
                        val type = state.types[index]
                        GlassChip(
                            text = type.replaceFirstChar { it.uppercase() },
                            selected = type == state.selectedType,
                            onClick = { viewModel.selectType(type) }
                        )
                    }
                }
            }

            if (state.catalogs.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    items(state.catalogs.size) { index ->
                        val choice = state.catalogs[index]
                        GlassChip(
                            text = choice.label,
                            selected = choice.key == state.selected?.key,
                            onClick = { viewModel.selectCatalog(choice) }
                        )
                    }
                }
            }

            if (state.genres.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 6.dp)
                ) {
                    item {
                        GlassChip(
                            text = "All genres",
                            selected = state.selectedGenre == null,
                            onClick = { viewModel.selectGenre(null) }
                        )
                    }
                    items(state.genres.size) { index ->
                        val genre = state.genres[index]
                        GlassChip(
                            text = genre,
                            selected = genre == state.selectedGenre,
                            onClick = { viewModel.selectGenre(genre) }
                        )
                    }
                }
            }

            when {
                state.loading -> LoadingBox()
                state.items.isEmpty() -> EmptyMessage(state.error ?: "Pick a catalog above.")
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(118.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.items.size) { index ->
                        val meta = state.items[index]
                        PosterCard(
                            title = meta.name.ifBlank { meta.id },
                            poster = meta.poster,
                            subtitle = meta.releaseInfoText,
                            onClick = { onOpen(meta.type.ifBlank { state.selectedType ?: "movie" }, meta.id) }
                        )
                    }
                }
            }
        }
    }
}
