package com.stremio.lite

import com.stremio.lite.data.addon.AddonJson
import com.stremio.lite.data.addon.Manifest
import com.stremio.lite.data.addon.StreamsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestParsingTest {

    @Test
    fun `resources given as plain strings and as objects both parse`() {
        val json = """
        {
          "id": "org.test",
          "name": "Test",
          "version": "1.0.0",
          "resources": [
            "catalog",
            { "name": "stream", "types": ["movie"], "idPrefixes": ["tt"] }
          ],
          "types": ["movie", "series"],
          "catalogs": [
            { "type": "movie", "id": "top", "name": "Top",
              "extra": [{ "name": "search" }, { "name": "skip" }] }
          ]
        }
        """.trimIndent()

        val manifest = AddonJson.json.decodeFromString(Manifest.serializer(), json)
        assertEquals(listOf("catalog", "stream"), manifest.normalizedResources.map { it.name })
        assertTrue(manifest.supports("stream", "movie", "tt1234"))
        // idPrefixes on the stream resource exclude non-IMDb ids.
        assertFalse(manifest.supports("stream", "movie", "kitsu:42"))
        // A series stream is out of scope for this resource definition.
        assertFalse(manifest.supports("stream", "series", "tt1234"))
        assertTrue(manifest.catalogs.first().supportsSearch)
        assertTrue(manifest.catalogs.first().supportsSkip)
    }

    @Test
    fun `streams separate downloadable http urls from torrents`() {
        val json = """
        {"streams":[
          {"url":"https://cdn.example.com/a.mp4","name":"HTTP 1080p",
           "behaviorHints":{"filename":"a.mp4","videoSize":123}},
          {"infoHash":"c9e15763f722f23e98a29decdfae341b98d53056","fileIdx":0,"name":"Torrent",
           "sources":["tracker:udp://tracker.opentrackr.org:1337/announce"]},
          {"externalUrl":"https://example.com/watch"}
        ]}
        """.trimIndent()

        val streams = AddonJson.json.decodeFromString(StreamsResponse.serializer(), json).streams
        assertEquals(3, streams.size)
        assertEquals("https://cdn.example.com/a.mp4", streams[0].httpUrl)
        assertEquals("a.mp4", streams[0].behaviorHints?.filename)
        assertTrue(streams[1].isTorrent)
        assertEquals(null, streams[1].httpUrl)
        val magnet = streams[1].magnetUri("Some Movie")!!
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d53056"))
        assertTrue(magnet.contains("tracker.opentrackr.org"))
        assertEquals(null, streams[2].httpUrl)
    }

    @Test
    fun `api envelope errors surface as errors`() {
        val json = """{"error":{"code":2,"message":"User not found"}}"""
        val envelope = AddonJson.json.decodeFromString(
            com.stremio.lite.data.api.ApiEnvelope.serializer(
                com.stremio.lite.data.api.AuthResponse.serializer()
            ),
            json
        )
        assertEquals("User not found", envelope.error?.message)
        assertEquals(null, envelope.result)
    }
}
