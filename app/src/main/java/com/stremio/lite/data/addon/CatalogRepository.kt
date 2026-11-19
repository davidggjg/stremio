package com.stremio.lite.data.addon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class RowStatus { IDLE, LOADING, READY, ERROR }

/**
 * One board row: an (addon, catalog) pair and whatever has been loaded for it so far.
 * Mirrors stremio-core's `ResourceLoadable` per row — placeholder, loading, ready or error —
 * so a slow or dead addon never holds up the rows around it.
 */
data class CatalogRow(
    val addon: InstalledAddon,
    val catalog: CatalogDef,
    val items: List<MetaItem> = emptyList(),
    val status: RowStatus = RowStatus.IDLE,
    val skip: Int = 0,
    val endReached: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null
) {
    val key: String get() = "${addon.transportUrl}|${catalog.type}|${catalog.id}"
    val title: String get() = catalog.name ?: catalog.id.replaceFirstChar { it.uppercase() }
    val subtitle: String get() = "${addon.name} · ${catalog.type}"
    val supportsPaging: Boolean get() = catalog.supportsSkip
}

/**
 * Holds the board's rows for the whole app session.
 *
 * Two things make this feel fast where the first version did not: rows are only requested when
 * they scroll into view (stremio-core's `LoadRange`), and the state lives here rather than in a
 * ViewModel, so moving between tabs or in and out of a title never refetches anything.
 */
class CatalogRepository(
    private val addons: AddonRepository,
    private val client: AddonClient,
    private val scope: CoroutineScope
) {
    private val _rows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val rows: StateFlow<List<CatalogRow>> = _rows.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    /** Rebuilds the placeholder rows from the installed addons, keeping anything already loaded. */
    fun syncWithAddons() {
        val existing = _rows.value.associateBy { it.key }
        val next = addons.addons.value
            .flatMap { addon -> addon.browsableCatalogs.map { addon to it } }
            .sortedByDescending { (_, catalog) -> typePriority(catalog.type) }
            .map { (addon, catalog) ->
                val key = "${addon.transportUrl}|${catalog.type}|${catalog.id}"
                existing[key] ?: CatalogRow(addon, catalog)
            }
        if (next.map { it.key } != _rows.value.map { it.key }) _rows.value = next
    }

    /**
     * Requests the rows between [first] and [last] (plus a prefetch margin) that have not been
     * loaded yet. Called from the list as it scrolls.
     */
    fun loadRange(first: Int, last: Int, force: Boolean = false) {
        val rows = _rows.value
        if (rows.isEmpty()) return
        val from = (first - PREFETCH_MARGIN).coerceAtLeast(0)
        val to = (last + PREFETCH_MARGIN).coerceAtMost(rows.lastIndex)
        for (index in from..to) {
            val row = rows.getOrNull(index) ?: continue
            if (!force && row.status != RowStatus.IDLE) continue
            loadRow(row.key, force)
        }
    }

    fun retry(key: String) = loadRow(key, force = true)

    private fun loadRow(key: String, force: Boolean) {
        if (jobs[key]?.isActive == true) return
        val row = _rows.value.firstOrNull { it.key == key } ?: return

        patch(key) { it.copy(status = RowStatus.LOADING, error = null) }
        jobs[key] = scope.launch(Dispatchers.IO) {
            val items = withTimeoutOrNull(ROW_TIMEOUT_MS) {
                runCatching {
                    client.catalogPage(row.addon, row.catalog.type, row.catalog.id, forceRefresh = force)
                }.getOrNull()
            }
            patch(key) {
                when {
                    items == null -> it.copy(status = RowStatus.ERROR, error = "${row.addon.name} did not answer")
                    items.isEmpty() -> it.copy(status = RowStatus.READY, items = emptyList(), endReached = true)
                    else -> it.copy(
                        status = RowStatus.READY,
                        items = items,
                        skip = items.size,
                        endReached = items.size < CATALOG_PAGE_SIZE
                    )
                }
            }
            jobs.remove(key)
        }
    }

    /** Horizontal pagination: `skip` counts items already received, exactly as the core does. */
    fun loadNextPage(key: String) {
        val row = _rows.value.firstOrNull { it.key == key } ?: return
        if (row.endReached || row.loadingMore || !row.supportsPaging || row.status != RowStatus.READY) return
        if (jobs[key]?.isActive == true) return

        patch(key) { it.copy(loadingMore = true) }
        jobs[key] = scope.launch(Dispatchers.IO) {
            val more = withTimeoutOrNull(ROW_TIMEOUT_MS) {
                runCatching {
                    client.catalogPage(row.addon, row.catalog.type, row.catalog.id, skip = row.skip)
                }.getOrNull()
            }.orEmpty()
            patch(key) {
                it.copy(
                    items = it.items + more.filterNot { meta -> it.items.any { seen -> seen.id == meta.id } },
                    skip = it.skip + more.size,
                    endReached = more.size < CATALOG_PAGE_SIZE,
                    loadingMore = false
                )
            }
            jobs.remove(key)
        }
    }

    fun refreshAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _rows.value = _rows.value.map { it.copy(status = RowStatus.IDLE, items = emptyList(), skip = 0, endReached = false) }
    }

    private fun patch(key: String, transform: (CatalogRow) -> CatalogRow) {
        val index = _rows.value.indexOfFirst { it.key == key }
        if (index < 0) return
        _rows.value = _rows.value.toMutableList().also { it[index] = transform(it[index]) }
    }

    companion object {
        /** stremio-core: CATALOG_PAGE_SIZE. A short page means the catalog has ended. */
        const val CATALOG_PAGE_SIZE = 100
        private const val PREFETCH_MARGIN = 2
        private const val ROW_TIMEOUT_MS = 12_000L

        /** stremio-core: TYPE_PRIORITIES — movies first, then series, then channels and tv. */
        fun typePriority(type: String): Int = when (type.lowercase()) {
            "all" -> 5
            "movie" -> 4
            "series" -> 3
            "channel" -> 2
            "tv" -> 1
            else -> Int.MIN_VALUE
        }
    }
}
