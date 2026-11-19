package com.stremio.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremio.lite.AppContainer
import com.stremio.lite.data.addon.InstalledAddon
import com.stremio.lite.data.addon.MetaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class SearchGroup(val addon: InstalledAddon, val items: List<MetaItem>)

data class SearchUiState(
    val query: String = "",
    val running: Boolean = false,
    val groups: List<SearchGroup> = emptyList(),
    val searched: Boolean = false
)

/**
 * Search fans out to every addon that declares a `search` extra and publishes each addon's
 * results the moment they land, instead of waiting for the slowest one.
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        job?.cancel()
        if (query.trim().length < MIN_QUERY) {
            _state.value = _state.value.copy(groups = emptyList(), running = false, searched = false)
            return
        }
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            search(query.trim())
        }
    }

    fun searchNow() {
        job?.cancel()
        val query = _state.value.query.trim()
        if (query.length >= MIN_QUERY) job = viewModelScope.launch { search(query) }
    }

    private suspend fun search(query: String) {
        _state.value = _state.value.copy(running = true, groups = emptyList(), searched = true)

        val targets = container.addons.addons.value.flatMap { addon ->
            addon.manifest.catalogs.filter { it.supportsSearch }.map { addon to it }
        }
        if (targets.isEmpty()) {
            _state.value = _state.value.copy(running = false)
            return
        }

        var pending = targets.size
        targets.forEach { (addon, catalog) ->
            viewModelScope.launch(Dispatchers.IO) {
                val items = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    runCatching {
                        container.addonClient.catalogPage(addon, catalog.type, catalog.id, search = query)
                    }.getOrNull()
                }.orEmpty()

                synchronized(this@SearchViewModel) {
                    pending--
                    if (items.isNotEmpty() && _state.value.query.trim() == query) {
                        val merged = _state.value.groups + SearchGroup(addon, items)
                        _state.value = _state.value.copy(groups = merged, running = pending > 0)
                    } else if (_state.value.query.trim() == query) {
                        _state.value = _state.value.copy(running = pending > 0)
                    }
                }
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 350L
        const val MIN_QUERY = 2
        const val SEARCH_TIMEOUT_MS = 12_000L
    }
}
