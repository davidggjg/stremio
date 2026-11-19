package com.stremio.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremio.lite.AppContainer
import com.stremio.lite.data.addon.CatalogRow
import com.stremio.lite.data.addon.InstalledAddon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val container = AppContainer.get(app)

    val addons: StateFlow<List<InstalledAddon>> = container.addons.addons
    val library = container.library.items
    val rows: StateFlow<List<CatalogRow>> = container.catalogs.rows

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        viewModelScope.launch {
            // The cached addon list comes back immediately, so rows appear before the network
            // has answered anything.
            container.addons.load()
            container.catalogs.syncWithAddons()
            container.catalogs.loadRange(0, INITIAL_ROWS)
            container.library.refresh()
        }
    }

    /** Called by the list as it scrolls: only visible rows (plus a margin) are ever requested. */
    fun onVisibleRange(first: Int, last: Int) = container.catalogs.loadRange(first, last)

    fun loadNextPage(key: String) = container.catalogs.loadNextPage(key)

    fun retryRow(key: String) = container.catalogs.retry(key)

    fun refresh() {
        _refreshing.value = true
        viewModelScope.launch {
            container.session.authKey?.let { runCatching { container.addons.pullFromAccount(it) } }
            container.library.refresh()
            container.catalogs.syncWithAddons()
            container.catalogs.refreshAll()
            container.catalogs.loadRange(0, INITIAL_ROWS, force = true)
            _refreshing.value = false
        }
    }

    private companion object {
        /** Enough to fill the first screen; everything else waits for the scroll. */
        const val INITIAL_ROWS = 3
    }
}
