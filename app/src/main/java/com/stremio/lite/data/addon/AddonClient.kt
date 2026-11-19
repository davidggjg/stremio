package com.stremio.lite.data.addon

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Talks the Stremio addon HTTP transport:
 *   {transportUrl}/catalog/{type}/{id}.json
 *   {transportUrl}/catalog/{type}/{id}/{extra}.json
 *   {transportUrl}/{resource}/{type}/{id}.json
 */
class AddonClient(private val client: OkHttpClient) {

    suspend fun fetchManifest(transportUrl: String): Manifest {
        val url = normalizeTransportUrl(transportUrl)
        return get(url, Manifest.serializer())
    }

    /** Catalog request with Stremio's own paging: `skip` counts items, a page is 100. */
    suspend fun catalogPage(
        addon: InstalledAddon,
        type: String,
        catalogId: String,
        skip: Int = 0,
        genre: String? = null,
        search: String? = null,
        forceRefresh: Boolean = false
    ): List<MetaItem> {
        val extra = buildMap {
            if (skip > 0) put("skip", skip.toString())
            genre?.let { put("genre", it) }
            search?.let { put("search", it) }
        }
        val path = buildString {
            append("${addon.baseUrl}/catalog/${type.enc()}/${catalogId.enc()}")
            if (extra.isNotEmpty()) append("/${encodeExtra(extra)}")
            append(".json")
        }
        return get(path, CatalogResponse.serializer(), forceRefresh).metas
    }

    suspend fun catalog(
        addon: InstalledAddon,
        type: String,
        catalogId: String,
        extra: Map<String, String> = emptyMap()
    ): List<MetaItem> {
        val base = addon.baseUrl
        val path = buildString {
            append("$base/catalog/${type.enc()}/${catalogId.enc()}")
            if (extra.isNotEmpty()) append("/${encodeExtra(extra)}")
            append(".json")
        }
        return get(path, CatalogResponse.serializer()).metas
    }

    suspend fun meta(addon: InstalledAddon, type: String, id: String): MetaItem? =
        get("${addon.baseUrl}/meta/${type.enc()}/${id.enc()}.json", MetaResponse.serializer()).meta

    suspend fun streams(
        addon: InstalledAddon,
        type: String,
        id: String,
        forceRefresh: Boolean = false
    ): List<Stream> = get(
        "${addon.baseUrl}/stream/${type.enc()}/${id.enc()}.json",
        StreamsResponse.serializer(),
        forceRefresh
    ).streams

    suspend fun subtitles(addon: InstalledAddon, type: String, id: String): List<Subtitle> =
        get("${addon.baseUrl}/subtitles/${type.enc()}/${id.enc()}.json", SubtitlesResponse.serializer()).subtitles

    suspend fun addonCatalog(addon: InstalledAddon, type: String, id: String): List<AddonDescriptor> =
        get("${addon.baseUrl}/addon_catalog/${type.enc()}/${id.enc()}.json", AddonCatalogResponse.serializer()).addons

    private suspend fun <T> get(
        url: String,
        serializer: KSerializer<T>,
        forceRefresh: Boolean = false
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (forceRefresh) cacheControl(CacheControl.FORCE_NETWORK) }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AddonRequestException(url, response.code, body.take(300))
            }
            AddonJson.json.decodeFromString(serializer, body)
        }
    }

    companion object {
        /**
         * Accepts what users actually paste: a full manifest.json URL, a bare transport URL, or a
         * `stremio://` link. Returns the manifest URL.
         */
        fun normalizeTransportUrl(input: String): String {
            var url = input.trim()
            if (url.startsWith("stremio://", true)) {
                url = "https://" + url.removePrefix("stremio://").removePrefix("stremio:/")
            }
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "https://$url"
            }
            url = url.trimEnd('/')
            return if (url.endsWith("manifest.json", true)) url else "$url/manifest.json"
        }

        /** The transport base is the manifest URL minus the trailing `/manifest.json`. */
        fun baseUrlOf(transportUrl: String): String =
            normalizeTransportUrl(transportUrl).removeSuffix("/manifest.json")

        /** Extras are `key=value` pairs joined by `&`, URL-encoded, inside one path segment. */
        fun encodeExtra(extra: Map<String, String>): String =
            extra.entries.joinToString("&") { "${it.key.enc()}=${it.value.enc()}" }

        private fun String.enc(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }
}

private fun String.enc(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

class AddonRequestException(val url: String, val code: Int, val bodySnippet: String) :
    Exception("Addon request failed ($code): $url")
