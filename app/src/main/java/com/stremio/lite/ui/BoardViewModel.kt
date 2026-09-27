package com.stremio.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremio.lite.AppContainer
import com.stremio.lite.data.addon.CatalogDef
import com.stremio.lite.data.addon.InstalledAddon
import com.stremio.lite.data.addon.MetaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CatalogRow(
    val addon: InstalledAddon,
    val catalog: CatalogDef,
    val items: List<MetaItem>
) {
    val key: String get() = "${addon.id}:${catalog.type}:${catalog.id}"
    val title: String get() = catalog.name ?: catalog.id
    val subtitle: String get() = "${addon.name} · ${catalog.type}"
}

data class BoardUiState(
    val loading: Boolean = false,
    val rows: List<CatalogRow> = emptyList(),
    val error: String? = null
)

class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)

    val addons: StateFlow<List<InstalledAddon>> = container.addons.addons
    val library = container.library.items

    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    private val _search = MutableStateFlow(BoardUiState())
    val searchState: StateFlow<BoardUiState> = _search.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            container.addons.load()
            container.library.refresh()
            loadCatalogs()
        }
    }

    fun refresh() = viewModelScope.launch {
        container.session.authKey?.let { key ->
            runCatching { container.addons.pullFromAccount(key) }
            container.library.refresh()
        }
        loadCatalogs()
    }

    /** Every browsable catalog of every installed addon, fetched in parallel. */
    private suspend fun loadCatalogs() {
        _state.value = _state.value.copy(loading = true, error = null)
        val requests = container.addons.addons.value.flatMap { addon ->
            addon.browsableCatalogs.map { catalog -> addon to catalog }
        }
        if (requests.isEmpty()) {
            _state.value = BoardUiState(loading = false, rows = emptyList())
            return
        }
        val rows = coroutineScope {
            requests.map { (addon, catalog) ->
                async(Dispatchers.IO) {
                    runCatching { container.addonClient.catalog(addon, catalog.type, catalog.id) }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { CatalogRow(addon, catalog, it) }
                }
            }.mapNotNull { it.await() }
        }
        _state.value = BoardUiState(
            loading = false,
            rows = rows,
            error = if (rows.isEmpty()) "No catalogs returned any items" else null
        )
    }

    fun search(query: String) {
        if (query.isBlank()) {
            _search.value = BoardUiState()
            return
        }
        _search.value = BoardUiState(loading = true)
        viewModelScope.launch {
            val targets = container.addons.addons.value.flatMap { addon ->
                addon.manifest.catalogs.filter { it.supportsSearch }.map { addon to it }
            }
            val rows = coroutineScope {
                targets.map { (addon, catalog) ->
                    async(Dispatchers.IO) {
                        runCatching {
                            container.addonClient.catalog(
                                addon,
                                catalog.type,
                                catalog.id,
                                mapOf("search" to query)
                            )
                        }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { CatalogRow(addon, catalog, it) }
                    }
                }.mapNotNull { it.await() }
            }
            _search.value = BoardUiState(
                loading = false,
                rows = rows,
                error = if (rows.isEmpty()) "No results for \"$query\"" else null
            )
        }
    }
}
