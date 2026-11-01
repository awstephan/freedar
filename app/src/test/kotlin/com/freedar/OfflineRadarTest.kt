package com.freedar

import com.freedar.radar.*
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class OfflineRadarTest {
    @Test fun `clear prevents a late manifest response from restoring the disk catalog`() = runBlocking {
        val root = Files.createTempDirectory("offline-radar").toFile()
        val now = 20_000_000L
        try {
            val store = DownloadedImagery(root, { now })
            val archive = RadarArchive(store, { now })
            val response = kotlinx.coroutines.CompletableDeferred<RadarFrame>()
            val provider = RetainedRadarProvider(RadarProvider { response.await() }, archive)
            val request = kotlinx.coroutines.CoroutineScope(coroutineContext).async(
                start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { provider.history() }
            store.clear()
            response.complete(RadarFrame(Instant.ofEpochMilli(now), "https://tiles.example.com/frame/256/{z}/{x}/{y}/2/1_1.png"))
            request.await()
            assertTrue(archive.frames().isEmpty())
            assertEquals(0L, store.size())
        } finally { root.deleteRecursively() }
    }

    @Test fun `fresh offline instance shows newest available saved pixels with original time and failures intact`() = runBlocking {
        val root = Files.createTempDirectory("offline-radar").toFile()
        var now = 20_000_000L
        fun frame(age: Long, name: String) = RadarFrame(Instant.ofEpochMilli(now - age),
            "https://tiles.example.com/$name/256/{z}/{x}/{y}/2/1_1.png")
        val older = frame(2_100_000, "older")
        val newest = frame(1_500_000, "newest")
        val fresh = frame(0, "fresh")
        try {
            val store = DownloadedImagery(root, { now })
            val seed = object : RadarProvider {
                override suspend fun latest() = newest
                override suspend fun history() = listOf(older, newest)
            }
            RetainedRadarProvider(seed, RadarArchive(store, { now })).history()
            var online = false
            val reopened = DownloadedImagery(root, { now })
            val provider = RetainedRadarProvider(RadarProvider {
                if (!online) throw IOException("offline") else fresh
            }, RadarArchive(reopened, { now }))
            val attempted = mutableListOf<RadarFrame>()
            val radar = LatestRadar(provider, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), RadarDisplay {
                attempted += it
                if (it == newest) throw IOException("Viewport not downloaded")
            })
            radar.connectivity(true)
            radar.foreground()
            assertEquals(listOf(newest, older), attempted)
            assertEquals(older, radar.state.value.frame)
            assertEquals(2100L, radar.state.value.ageSeconds)
            assertEquals(newest.time, radar.state.value.newestFrameTime)
            assertEquals(TimelineMode.FOLLOWING, radar.state.value.mode)
            assertTrue(radar.state.value.retainedHistory)
            assertTrue(radar.state.value.offline)
            assertTrue(radar.state.value.stale)
            assertNotNull(radar.state.value.refreshError)
            assertTrue(newest in radar.state.value.unavailable)
            online = true
            radar.connectivity(false)
            radar.refresh()
            assertEquals(fresh, radar.state.value.frame)
            assertFalse(radar.state.value.retainedHistory)
            assertNull(radar.state.value.refreshError)
            online = false
            now += 7_200_000
            val expired = LatestRadar(provider, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC), RadarDisplay {})
            expired.foreground()
            assertNull(expired.state.value.frame)
            assertNotNull(expired.state.value.refreshError)
            assertEquals(0L, reopened.size())
        } finally { root.deleteRecursively() }
    }
}
