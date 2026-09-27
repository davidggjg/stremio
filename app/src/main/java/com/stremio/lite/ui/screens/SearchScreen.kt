package com.stremio.lite.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stremio.lite.ui.BoardViewModel

@Composable
fun SearchScreen(onOpen: (type: String, id: String) -> Unit) {
    val viewModel: BoardViewModel = viewModel()
    val state by viewModel.searchState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search your addons") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
            modifier = Modifier.fillMaxWidth()
        )

        if (state.loading) {
            LoadingBox()
        } else if (state.rows.isEmpty()) {
            EmptyMessage(state.error ?: "Type a title and press search.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows.size) { index ->
                    val row = state.rows[index]
                    CatalogRowView(row) { meta -> onOpen(meta.type.ifBlank { row.catalog.type }, meta.id) }
                }
            }
        }
    }
}
