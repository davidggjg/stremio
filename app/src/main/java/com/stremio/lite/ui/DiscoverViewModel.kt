package com.stremio.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremio.lite.AppContainer
import com.stremio.lite.data.addon.CatalogDef
import com.stremio.lite.data.addon.CatalogRepository
import com.stremio.lite.data.addon.InstalledAddon
import com.stremio.lite.data.addon.MetaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogChoice(val addon: InstalledAddon, val catalog: CatalogDef) {
    val key: String get() = "${addon.transportUrl}|${catalog.type}|${catalog.id}"
    val label: String get() = catalog.name ?: catalog.id.replaceFirstChar { it.uppercase() }
}

data class DiscoverUiState(
    val types: List<String> = emptyList(),
    val selectedType: String? = null,
    val catalogs: List<CatalogChoice> = emptyList(),
    val selected: CatalogChoice? = null,
    val genres: List<String> = emptyList(),
    val selectedGenre: String? = null,
    val items: List<MetaItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null
)

/**
 * The Discover page. Unlike the board it can reach catalogs that *require* an extra — a genre,
 * typically — which is why those never showed up on the home screen: an addon will not answer a
 * catalog request that omits a required property.
 */
class DiscoverViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            container.addons.load()
            rebuild()
        }
    }

    private fun rebuild() {
        val all = container.addons.addons.value.flatMap { addon ->
            addon.manifest.catalogs.map { CatalogChoice(addon, it) }
        }
        val types = all.map { it.catalog.type }
            .distinct()
            .sortedByDescending { CatalogRepository.typePriority(it) }
        val type = _state.value.selectedType ?: types.firstOrNull()
        _state.value = _state.value.copy(types = types, selectedType = type)
        selectType(type ?: return)
    }

    fun selectType(type: String) {
        val catalogs = container.addons.addons.value
            .flatMap { addon -> addon.manifest.catalogs.map { CatalogChoice(addon, it) } }
            .filter { it.catalog.type == type }
        _state.value = _state.value.copy(
            selectedType = type,
            catalogs = catalogs,
            selected = null,
            genres = emptyList(),
            selectedGenre = null,
            items = emptyList()
        )
        catalogs.firstOrNull()?.let(::selectCatalog)
    }

    fun selectCatalog(choice: CatalogChoice) {
        val genres = choice.catalog.genres
        // A catalog that requires a genre cannot be asked without one, so default to the first.
        val requiresGenre = choice.catalog.requiredExtras.contains("genre")
        val genre = if (requiresGenre) genres.firstOrNull() else null
        _state.value = _state.value.copy(
            selected = choice,
            genres = genres,
            selectedGenre = genre,
            items = emptyList(),
            endReached = false,
            error = null
        )
        load(reset = true)
    }

    fun selectGenre(genre: String?) {
        _state.value = _state.value.copy(selectedGenre = genre, items = emptyList(), endReached = false)
        load(reset = true)
    }

    fun loadMore() {
        val state = _state.value
        if (state.loading || state.loadingMore || state.endReached || state.selected == null) return
        if (!state.selected.catalog.supportsSkip) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        val state = _state.value
        val choice = state.selected ?: return
        job?.cancel()

        _state.value = state.copy(
            loading = reset,
            loadingMore = !reset,
            error = null
        )
        job = viewModelScope.launch {
            val skip = if (reset) 0 else state.items.size
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.addonClient.catalogPage(
                        addon = choice.addon,
                        type = choice.catalog.type,
                        catalogId = choice.catalog.id,
                        skip = skip,
                        genre = state.selectedGenre
                    )
                }
            }
            val current = _state.value
            if (current.selected?.key != choice.key) return@launch

            result.onSuccess { items ->
                val merged = if (reset) items
                else current.items + items.filterNot { new -> current.items.any { it.id == new.id } }
                _state.value = current.copy(
                    items = merged,
                    loading = false,
                    loadingMore = false,
                    endReached = items.size < CatalogRepository.CATALOG_PAGE_SIZE,
                    error = if (merged.isEmpty()) "הקטלוג הזה לא החזיר כלום" else null
                )
            }.onFailure { error ->
                _state.value = current.copy(
                    loading = false,
                    loadingMore = false,
                    error = error.message ?: "לא ניתן לטעון את הקטלוג הזה"
                )
            }
        }
    }
}
