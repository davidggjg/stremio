package com.stremio.lite

import com.stremio.lite.data.download.ChunkedDownloader
import com.stremio.lite.data.download.DownloadRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlanningTest {

    @Test
    fun `chunks cover the whole file exactly once`() {
        val total = 1_234_567_890L
        val chunks = ChunkedDownloader.planChunks(total, 8)
        // Coverage is the property that matters: every byte in exactly one chunk, no gaps.
        assertEquals(0L, chunks.first().start)
        assertEquals(total - 1, chunks.last().end)
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.end + 1, b.start) }
        assertEquals(total, chunks.sumOf { it.size })
    }

    @Test
    fun `large files get more connections than asked for`() {
        val requested = 8
        val medium = ChunkedDownloader.planChunks(1_200L * 1024 * 1024, requested)
        val huge = ChunkedDownloader.planChunks(6L * 1024 * 1024 * 1024, requested)

        // Past 700 MB, half again as many; past 4 GB, double. Beyond those sizes the limit is
        // per-connection throughput rather than the link itself.
        assertEquals(requested * 3 / 2, medium.size)
        assertEquals(requested * 2, huge.size)
        assertTrue(huge.size <= 32)
    }

    @Test
    fun `small files are not split into tiny chunks`() {
        // 6 MiB with 16 requested connections: the 2 MiB minimum caps this at 3 chunks.
        val chunks = ChunkedDownloader.planChunks(6L * 1024 * 1024, 16)
        assertEquals(3, chunks.size)
        assertEquals(6L * 1024 * 1024, chunks.sumOf { it.size })
    }

    @Test
    fun `unknown length falls back to one open-ended chunk`() {
        val chunks = ChunkedDownloader.planChunks(-1, 8)
        assertEquals(1, chunks.size)
        assertEquals(Long.MAX_VALUE, chunks.first().end)
    }

    @Test
    fun `resume offset accounts for bytes already written`() {
        // 8 MiB splits into two chunks of 4 MiB; take the second one.
        val chunk = ChunkedDownloader.planChunks(8L * 1024 * 1024, 2)[1]
        chunk.downloaded = 100
        assertEquals(chunk.start + 100, chunk.remainingFrom)
        assertTrue(!chunk.isComplete)
    }

    @Test
    fun `file names are made safe and keep an extension`() {
        assertEquals("The_Movie_2019.mkv", DownloadRepository.sanitizeFileName("The/Movie: 2019.mkv".replace(" ", "_")))
        assertTrue(DownloadRepository.sanitizeFileName("no extension here").endsWith(".mp4"))
    }

    @Test
    fun `content disposition filename is extracted`() {
        assertEquals(
            "video.mp4",
            ChunkedDownloader.fileNameFromDisposition("attachment; filename=\"video.mp4\"")
        )
    }
}
