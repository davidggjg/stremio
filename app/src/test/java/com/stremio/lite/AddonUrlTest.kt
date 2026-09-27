package com.stremio.lite

import com.stremio.lite.data.addon.AddonClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonUrlTest {

    @Test
    fun `manifest urls are normalized from every form users paste`() {
        val expected = "https://v3-cinemeta.strem.io/manifest.json"
        listOf(
            "https://v3-cinemeta.strem.io/manifest.json",
            "https://v3-cinemeta.strem.io/",
            "v3-cinemeta.strem.io",
            "stremio://v3-cinemeta.strem.io/manifest.json"
        ).forEach { input ->
            assertEquals(expected, AddonClient.normalizeTransportUrl(input))
        }
    }

    @Test
    fun `base url drops the manifest suffix`() {
        assertEquals(
            "https://torrentio.strem.fun",
            AddonClient.baseUrlOf("https://torrentio.strem.fun/manifest.json")
        )
    }

    @Test
    fun `catalog extras are encoded into one path segment`() {
        assertEquals(
            "search=the%20matrix&skip=100",
            AddonClient.encodeExtra(linkedMapOf("search" to "the matrix", "skip" to "100"))
        )
    }
}
