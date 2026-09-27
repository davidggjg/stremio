package com.stremio.lite.data.addon

import android.content.Context
import com.stremio.lite.data.api.SessionStore
import com.stremio.lite.data.api.StremioApi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * Single source of truth for the installed addons.
 *
 * When the user is signed in, the account's addon collection wins: it is pulled from
 * `addonCollectionGet` and every install/uninstall is pushed back with `addonCollectionSet`,
 * so the phone and web.stremio.com stay in sync. Offline (or signed out) the same list is
 * served from a local cache file.
 */
class AddonRepository(
    context: Context,
    private val api: StremioApi,
    private val client: AddonClient,
    private val session: SessionStore
) {
    private val cacheFile = File(context.filesDir, "addons.json")
    private val mutex = Mutex()

    private val _addons = MutableStateFlow<List<InstalledAddon>>(emptyList())
    val addons: StateFlow<List<InstalledAddon>> = _addons.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    /** Loads the cache immediately, then refreshes from the account when signed in. */
    suspend fun load() {
        readCache()?.let { if (it.isNotEmpty()) _addons.value = it }
        val authKey = session.authKey
        if (authKey != null) {
            runCatching { pullFromAccount(authKey) }
                .onFailure { _syncError.value = it.message }
        } else if (_addons.value.isEmpty()) {
            installDefaults()
        }
    }

    suspend fun pullFromAccount(authKey: String) {
        val response = api.addonCollectionGet(authKey)
        val addons = response.addons.map(InstalledAddon::fromApi)
        if (addons.isNotEmpty()) {
            _addons.value = addons
            writeCache(addons)
        }
        _syncError.value = null
    }

    /** Called after login: adopt the account's collection, keeping local-only addons. */
    suspend fun onSignedIn(authKey: String) {
        val localOnly = _addons.value.filter { it.source == AddonSource.LOCAL }
        pullFromAccount(authKey)
        val merged = mergeIn(_addons.value, localOnly)
        if (merged.size != _addons.value.size) {
            _addons.value = merged
            writeCache(merged)
            pushToAccount(authKey, merged)
        }
    }

    suspend fun onSignedOut() {
        // Keep whatever was installed, but mark it local so it is no longer tied to the account.
        val local = _addons.value.map { it.copy(source = AddonSource.LOCAL) }
        _addons.value = local
        writeCache(local)
    }

    suspend fun installFromUrl(transportUrl: String): Result<InstalledAddon> = runCatching {
        val manifestUrl = AddonClient.normalizeTransportUrl(transportUrl)
        val manifest = client.fetchManifest(manifestUrl)
        val addon = InstalledAddon(
            transportUrl = manifestUrl,
            manifest = manifest,
            source = if (session.authKey != null) AddonSource.ACCOUNT else AddonSource.LOCAL
        )
        mutex.withLock {
            val next = _addons.value.filterNot { it.id == addon.id || it.transportUrl == addon.transportUrl } + addon
            _addons.value = next
            writeCache(next)
            session.authKey?.let { pushToAccount(it, next) }
        }
        addon
    }

    suspend fun uninstall(addon: InstalledAddon): Result<Unit> = runCatching {
        if (addon.protected) error("${addon.name} is a protected addon and cannot be removed")
        mutex.withLock {
            val next = _addons.value.filterNot { it.transportUrl == addon.transportUrl }
            _addons.value = next
            writeCache(next)
            session.authKey?.let { pushToAccount(it, next) }
        }
    }

    private suspend fun pushToAccount(authKey: String, addons: List<InstalledAddon>) {
        runCatching { api.addonCollectionSet(authKey, addons.map { it.toApiDescriptor() }) }
            .onFailure { _syncError.value = "Could not sync addons: ${it.message}" }
    }

    private suspend fun installDefaults() = coroutineScope {
        val fetched = DefaultAddons.urls.map { url ->
            async(Dispatchers.IO) {
                runCatching {
                    InstalledAddon(url, client.fetchManifest(url), official = true, source = AddonSource.LOCAL)
                }.getOrNull()
            }
        }.mapNotNull { it.await() }
        if (fetched.isNotEmpty()) {
            _addons.value = fetched
            writeCache(fetched)
        }
    }

    /** Addons that can answer a given resource request, in collection order. */
    fun addonsFor(resource: String, type: String? = null, id: String? = null): List<InstalledAddon> =
        _addons.value.filter { it.manifest.supports(resource, type, id) }

    private fun mergeIn(base: List<InstalledAddon>, extra: List<InstalledAddon>): List<InstalledAddon> {
        val known = base.map { it.transportUrl }.toSet()
        return base + extra.filterNot { known.contains(it.transportUrl) }
    }

    private suspend fun readCache(): List<InstalledAddon>? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext null
        runCatching {
            AddonJson.json.decodeFromString(ListSerializer(InstalledAddon.serializer()), cacheFile.readText())
        }.getOrNull()
    }

    private suspend fun writeCache(addons: List<InstalledAddon>) = withContext(Dispatchers.IO) {
        runCatching {
            cacheFile.writeText(
                AddonJson.json.encodeToString(ListSerializer(InstalledAddon.serializer()), addons)
            )
        }
    }
}
