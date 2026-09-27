package com.stremio.lite

import com.stremio.lite.data.download.TorrentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentEngineTest {

    @Test
    fun `a bare infoHash becomes a magnet with fallback trackers`() {
        val magnet = TorrentEngine.toMagnet("c9e15763f722f23e98a29decdfae341b98d53056")
        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:c9e15763f722f23e98a29decdfae341b98d53056"))
        assertTrue(magnet.contains("tracker.opentrackr.org"))
    }

    @Test
    fun `a magnet that already has trackers is left alone`() {
        val original = "magnet:?xt=urn:btih:abc123&tr=udp%3A%2F%2Fmy.tracker%3A80"
        assertEquals(original, TorrentEngine.toMagnet(original))
    }

    @Test
    fun `infoHash is read back out of a magnet`() {
        assertEquals(
            "c9e15763f722f23e98a29decdfae341b98d53056",
            TorrentEngine.infoHashOf("magnet:?xt=urn:btih:C9E15763F722F23E98A29DECDFAE341B98D53056&dn=x")
        )
    }

    @Test
    fun `the addon's fileIdx wins when it is in range`() {
        val files = listOf(
            "pack/S01E01.mkv" to 900_000_000L,
            "pack/S01E02.mkv" to 950_000_000L,
            "pack/S01E03.mkv" to 800_000_000L
        )
        assertEquals(2, TorrentEngine.pickFileIndex(files, preferred = 2))
        // Out of range values are ignored rather than crashing.
        assertEquals(1, TorrentEngine.pickFileIndex(files, preferred = 99))
    }

    @Test
    fun `without fileIdx the biggest video file is chosen over junk`() {
        val files = listOf(
            "Movie/RARBG.txt" to 30L,
            "Movie/sample.mkv" to 40_000_000L,
            "Movie/Movie.2019.1080p.mkv" to 8_000_000_000L,
            "Movie/subs/eng.srt" to 50_000L,
            "Movie/cover.jpg" to 400_000L
        )
        assertEquals(2, TorrentEngine.pickFileIndex(files, preferred = null))
    }

    @Test
    fun `with no video files at all the largest file is chosen`() {
        val files = listOf("a.txt" to 10L, "b.bin" to 500L, "c.nfo" to 20L)
        assertEquals(1, TorrentEngine.pickFileIndex(files, preferred = null))
    }
}
