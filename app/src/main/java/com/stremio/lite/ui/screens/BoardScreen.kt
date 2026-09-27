package com.stremio.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stremio.lite.ui.BoardViewModel

@Composable
fun BoardScreen(onOpen: (type: String, id: String) -> Unit) {
    val viewModel: BoardViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val addons by viewModel.addons.collectAsStateWithLifecycle()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp)) {
                Text("Board", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${addons.size} addons · ${state.rows.size} catalogs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (library.isNotEmpty()) {
            item { SectionHeader("My Library") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(library.size) { index ->
                        val item = library[index]
                        PosterCard(
                            title = item.name.ifBlank { item.id },
                            poster = item.poster,
                            subtitle = item.state?.let { s ->
                                if (s.progress > 0f) "${(s.progress * 100).toInt()}% watched" else null
                            },
                            onClick = { onOpen(item.type, item.id) }
                        )
                    }
                }
            }
        }

        if (state.loading && state.rows.isEmpty()) {
            item { LoadingBox() }
        }

        items(state.rows.size) { index ->
            val row = state.rows[index]
            CatalogRowView(row) { meta -> onOpen(meta.type.ifBlank { row.catalog.type }, meta.id) }
        }

        if (!state.loading && state.rows.isEmpty()) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    EmptyMessage(
                        state.error
                            ?: "No catalogs yet. Sign in to your Stremio account or install addons to fill the board."
                    )
                    Button(onClick = { viewModel.refresh() }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}
