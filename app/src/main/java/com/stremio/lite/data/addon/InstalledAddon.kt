package com.stremio.lite.data.addon

import com.stremio.lite.data.api.AddonFlags
import com.stremio.lite.data.api.ApiAddonDescriptor
import kotlinx.serialization.Serializable

enum class AddonSource { ACCOUNT, LOCAL }

@Serializable
data class InstalledAddon(
    val transportUrl: String,
    val manifest: Manifest,
    val official: Boolean = false,
    val protected: Boolean = false,
    val source: AddonSource = AddonSource.LOCAL
) {
    val baseUrl: String get() = AddonClient.baseUrlOf(transportUrl)
    val id: String get() = manifest.id
    val name: String get() = manifest.name

    /** Catalogs that can be listed without the user first supplying a required extra. */
    val browsableCatalogs: List<CatalogDef>
        get() = manifest.catalogs.filter { catalog ->
            catalog.requiredExtras.none { it != "skip" }
        }

    fun toApiDescriptor(): ApiAddonDescriptor = ApiAddonDescriptor(
        transportUrl = transportUrl,
        transportName = null,
        manifest = manifest,
        flags = AddonFlags(official = official, protected = protected)
    )

    companion object {
        fun fromApi(descriptor: ApiAddonDescriptor): InstalledAddon = InstalledAddon(
            transportUrl = descriptor.transportUrl,
            manifest = descriptor.manifest,
            official = descriptor.flags?.official == true,
            protected = descriptor.flags?.protected == true,
            source = AddonSource.ACCOUNT
        )
    }
}

/** Addons every fresh install gets, matching Stremio's own defaults. */
object DefaultAddons {
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"
    const val OPENSUBTITLES = "https://opensubtitles-v3.strem.io/manifest.json"

    val urls = listOf(CINEMETA, OPENSUBTITLES)

    /** Popular community addons offered as one-tap installs in the Addons screen. */
    val suggestions = listOf(
        "Cinemeta (metadata)" to CINEMETA,
        "OpenSubtitles v3" to OPENSUBTITLES,
        "Torrentio" to "https://torrentio.strem.fun/manifest.json",
        "Public Domain Movies" to "https://5b0b0f4e9c5a-public-domain-movies.baby-beamup.club/manifest.json"
    )
}
