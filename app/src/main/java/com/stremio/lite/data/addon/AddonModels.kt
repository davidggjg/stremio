package com.stremio.lite.data.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Models for the Stremio addon protocol.
 * See https://github.com/Stremio/stremio-addon-sdk/tree/master/docs/api/responses
 */

@Serializable
data class Manifest(
    val id: String,
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val contactEmail: String? = null,
    // `resources` may be a list of plain strings or of objects: keep it raw and normalise below.
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<CatalogDef> = emptyList(),
    val addonCatalogs: List<CatalogDef> = emptyList(),
    val idPrefixes: List<String>? = null,
    val behaviorHints: BehaviorHints? = null
) {
    val normalizedResources: List<ResourceDef>
        get() = resources.map { element ->
            when {
                element is JsonPrimitive -> ResourceDef(element.contentOrNull.orEmpty())
                else -> AddonJson.json.decodeFromJsonElement(ResourceDef.serializer(), element)
            }
        }

    fun supports(resource: String, type: String? = null, id: String? = null): Boolean {
        val defs = normalizedResources.filter { it.name == resource }
        if (defs.isEmpty()) return false
        return defs.any { def ->
            val types = def.types ?: this.types
            val typeOk = type == null || types.isEmpty() || types.contains(type)
            val prefixes = def.idPrefixes ?: idPrefixes
            val idOk = id == null || prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
            typeOk && idOk
        }
    }
}

@Serializable
data class ResourceDef(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null
)

@Serializable
data class BehaviorHints(
    val adult: Boolean? = null,
    val p2p: Boolean? = null,
    val configurable: Boolean? = null,
    val configurationRequired: Boolean? = null
)

@Serializable
data class CatalogDef(
    val type: String = "other",
    val id: String = "",
    val name: String? = null,
    val extra: List<ExtraDef> = emptyList(),
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList()
) {
    val supportsSearch: Boolean
        get() = extra.any { it.name == "search" } || extraSupported.contains("search")

    val supportsSkip: Boolean
        get() = extra.any { it.name == "skip" } || extraSupported.contains("skip")

    val genres: List<String>
        get() = extra.firstOrNull { it.name == "genre" }?.options.orEmpty()

    /** Extras that must be provided, so a catalog cannot be listed unfiltered. */
    val requiredExtras: List<String>
        get() = (extra.filter { it.isRequired == true }.map { it.name } + extraRequired).distinct()
}

@Serializable
data class ExtraDef(
    val name: String,
    @SerialName("isRequired") val isRequired: Boolean? = null,
    val options: List<String>? = null,
    val optionsLimit: Int? = null
)

@Serializable
data class MetaItem(
    val id: String,
    val type: String = "other",
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: JsonElement? = null,
    val imdbRating: JsonElement? = null,
    val runtime: String? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
    val videos: List<Video> = emptyList()
) {
    val releaseInfoText: String? get() = releaseInfo?.primitiveText()
    val imdbRatingText: String? get() = imdbRating?.primitiveText()
    val allGenres: List<String> get() = genres ?: genre ?: emptyList()
}

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null
) {
    val label: String
        get() = title ?: name ?: id
}

@Serializable
data class Stream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sources: List<String> = emptyList(),
    val behaviorHints: StreamBehaviorHints? = null
) {
    val label: String
        get() = listOfNotNull(name, title ?: description).joinToString(" · ").ifBlank { "Stream" }

    /** A stream we can fetch over HTTP, and therefore download with the chunked downloader. */
    val httpUrl: String?
        get() = url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    val isTorrent: Boolean get() = infoHash != null

    fun magnetUri(displayName: String? = null): String? {
        val hash = infoHash ?: return null
        val trackers = sources.filter { it.startsWith("tracker:") }
            .map { "&tr=" + java.net.URLEncoder.encode(it.removePrefix("tracker:"), "UTF-8") }
            .joinToString("")
        val dn = displayName?.let { "&dn=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        return "magnet:?xt=urn:btih:$hash$dn$trackers"
    }
}

@Serializable
data class StreamBehaviorHints(
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    /** Some addons need request headers (e.g. Referer) to serve the stream. */
    val proxyHeaders: ProxyHeaders? = null
)

@Serializable
data class ProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null
)

@Serializable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String? = null
)

// ---- Response envelopes ----

@Serializable
data class CatalogResponse(val metas: List<MetaItem> = emptyList())

@Serializable
data class MetaResponse(val meta: MetaItem? = null)

@Serializable
data class StreamsResponse(val streams: List<Stream> = emptyList())

@Serializable
data class SubtitlesResponse(val subtitles: List<Subtitle> = emptyList())

@Serializable
data class AddonCatalogResponse(val addons: List<AddonDescriptor> = emptyList())

@Serializable
data class AddonDescriptor(
    val transportUrl: String? = null,
    val transportName: String? = null,
    val manifest: Manifest? = null
)

private fun JsonElement.primitiveText(): String? =
    runCatching { jsonPrimitive.contentOrNull }.getOrNull()
