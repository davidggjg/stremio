package com.stremio.lite

import com.stremio.lite.data.download.TorrentStreamServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The range arithmetic the player depends on when it seeks inside a torrent that is downloading. */
class TorrentStreamServerTest {

    private val total = 1_000L

    @Test
    fun `no range header serves the whole file`() {
        assertEquals(0L to 999L, TorrentStreamServer.parseRange(null, total))
    }

    @Test
    fun `an open ended range runs to the last byte`() {
        assertEquals(500L to 999L, TorrentStreamServer.parseRange("bytes=500-", total))
    }

    @Test
    fun `a closed range is honoured exactly`() {
        assertEquals(100L to 199L, TorrentStreamServer.parseRange("bytes=100-199", total))
    }

    @Test
    fun `ranges past the end are clamped instead of overflowing the file`() {
        assertEquals(900L to 999L, TorrentStreamServer.parseRange("bytes=900-5000", total))
        assertEquals(999L to 999L, TorrentStreamServer.parseRange("bytes=9999-", total))
    }

    @Test
    fun `an inverted range collapses rather than producing a negative length`() {
        val (start, end) = TorrentStreamServer.parseRange("bytes=800-200", total)
        assertEquals(800L, start)
        assertEquals(800L, end)
    }

    @Test
    fun `video mime types follow the container`() {
        assertEquals("video/x-matroska", TorrentStreamServer.mimeTypeOf("Movie.1080p.mkv"))
        assertEquals("video/mp4", TorrentStreamServer.mimeTypeOf("Movie.mp4"))
        assertEquals("video/mp4", TorrentStreamServer.mimeTypeOf("no-extension"))
    }
}
