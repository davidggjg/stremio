package com.stremio.lite.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stremio.lite.ui.SearchViewModel
import com.stremio.lite.ui.theme.GlassBackground
import com.stremio.lite.ui.theme.Palette

@Composable
fun SearchScreen(viewModel: SearchViewModel, onOpen: (type: String, id: String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    GlassBackground {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("Search your addons") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.searchNow() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent,
                    unfocusedBorderColor = Palette.GlassBorderTop,
                    focusedContainerColor = Palette.GlassBottom,
                    unfocusedContainerColor = Palette.GlassBottom
                ),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            // Results stream in per addon, so the first answer shows immediately instead of
            // after the slowest addon finishes.
            if (state.running) {
                LinearProgressIndicator(
                    color = Palette.Accent,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                state.groups.forEach { group ->
                    item(key = group.addon.transportUrl) {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                "${group.addon.name} · ${group.items.size}",
                                style = MaterialTheme.typography.titleSmall,
                                color = Palette.TextSecondary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(group.items.size) { index ->
                                    val meta = group.items[index]
                                    PosterCard(
                                        title = meta.name.ifBlank { meta.id },
                                        poster = meta.poster,
                                        subtitle = meta.releaseInfoText,
                                        onClick = { onOpen(meta.type, meta.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (!state.running && state.searched && state.groups.isEmpty()) {
                    item { EmptyMessage("Nothing found for \"${state.query}\".") }
                } else if (!state.searched) {
                    item { EmptyMessage("Type at least two letters — results appear as each addon answers.") }
                }
            }
        }
    }
}
