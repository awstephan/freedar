package com.freedar

import com.freedar.radar.RadarFrame
import java.nio.file.Files
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class RadarArchiveTest {
    @Test fun `capacity pressure preserves descriptors needed to discover retained radar after restart`() {
        val root = Files.createTempDirectory("radar-capacity").toFile()
        var now = 20_000_000L
        val frame = RadarFrame(Instant.ofEpochMilli(now - 600_000),
            "https://tiles.example.com/opaque/256/{z}/{x}/{y}/2/1_1.png")
        try {
            val store = DownloadedImagery(root, { now }, capacity = 400)
            RadarArchive(store, { now }).remember(listOf(frame), store.generation())
            now++
            store.put("base", "image/png", ByteArray(120), store.generation())
            now++
            store.put("tile", "image/png", ByteArray(120) { 2 }, store.generation(), now + 600_000)
            val reopened = DownloadedImagery(root, { now }, capacity = 400)
            assertNotNull(reopened.get("tile"))
            assertEquals(listOf(frame), RadarArchive(reopened, { now }).frames())
            assertNull(reopened.get("base"))
            assertTrue(reopened.size() <= 400)
        } finally { root.deleteRecursively() }
    }

    @Test fun `reopened archive preserves opaque frame paths and original times until their individual deadlines`() {
        val root = Files.createTempDirectory("radar-archive").toFile()
        var now = 20_000_000L
        val old = RadarFrame(Instant.ofEpochMilli(now - 6_600_000),
            "https://tiles.example.com/opaque-older/256/{z}/{x}/{y}/2/1_1.png")
        val newest = RadarFrame(Instant.ofEpochMilli(now - 600_000),
            "https://tiles.example.com/opaque-newest/256/{z}/{x}/{y}/2/1_1.png")
        try {
            val store = DownloadedImagery(root, { now })
            RadarArchive(store, { now }).remember(listOf(old, newest), store.generation())
            val reopened = RadarArchive(DownloadedImagery(root, { now }), { now })
            assertEquals(listOf(old, newest), reopened.frames())
            assertEquals(20_600_000L, reopened.retentionDeadlineForRegisteredTile(
                old.tileTemplate.replace("{z}", "1").replace("{x}", "0").replace("{y}", "1").toHttpUrl()))
            assertNull(reopened.retentionDeadlineForRegisteredTile("https://tiles.example.com/unknown/256/1/0/1/2/1_1.png".toHttpUrl()))
            now += 600_000
            assertEquals(listOf(newest), reopened.frames())
            assertEquals(1, root.listFiles()!!.size)
            store.clear()
            assertTrue(reopened.frames().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
