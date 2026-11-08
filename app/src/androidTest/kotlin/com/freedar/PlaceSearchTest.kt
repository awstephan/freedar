package com.freedar

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PlaceSearchTest {
    @Test fun alternateScriptsSpellingsAndSmallTownsResolveThroughTheBundledIndex() {
        PlaceSearch(InstrumentationRegistry.getInstrumentation().targetContext).use { search ->
            for ((query, id) in listOf(
                "  MÜNCHEN  " to 2867714L, "Munich" to 2867714L,
                "東京" to 1850147L, "Tokyo" to 1850147L,
                "Москва" to 524901L, "Moscow" to 524901L,
                "sao paulo" to 3448439L, "São Paulo" to 3448439L,
                "Reykjavik" to 3413829L, "St. Ives" to 2638778L,
                "Twizel" to 6209526L, "Quorn" to 2062745L,
            )) assertTrue("$query resolves to $id", search.search(query).any { it.id == id })
            for (query in listOf("", "   ", "%_", "zzzznonexistentsettlement", "' OR 1=1 --"))
                assertTrue("No places for $query", search.search(query).isEmpty())
            val prefix = search.search("Reykja")
            assertTrue(prefix.any { it.id == 3413829L })
            assertEquals(prefix.size, prefix.map { it.id }.distinct().size)
        }
    }

    @Test fun offlineSearchSurvivesCacheRemovalAndReportsDeviceTimings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()).trim() }
        val externallyOffline = InstrumentationRegistry.getArguments().getString("searchBenchmarkOffline") == "true"
        val rootedBenchmark = InstrumentationRegistry.getArguments().getString("searchBenchmarkRooted") == "true"
        org.junit.Assume.assumeTrue("Use a rooted AOSP emulator or explicitly isolate the device network",
            externallyOffline || rootedBenchmark)
        if (!externallyOffline) org.junit.Assume.assumeTrue("Rooted benchmark requires root", shell("su 0 id").contains("uid=0"))
        val wifi = shell("settings get global wifi_on")
        val data = shell("settings get global mobile_data")
        try {
            if (!externallyOffline) {
                shell("su 0 svc wifi disable")
                shell("su 0 svc data disable")
            }
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (connectivity.activeNetwork != null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertNull("Device has no active network", connectivity.activeNetwork)
            context.cacheDir.deleteRecursively()
            val installedBefore = java.io.File(context.noBackupFilesDir, "place-search/places-v1.sqlite").exists()
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            PlaceSearch(context).use { search ->
                val opened = android.os.SystemClock.elapsedRealtimeNanos()
                assertEquals(3413829L, search.search("Reykjavik").first().id)
                val first = android.os.SystemClock.elapsedRealtimeNanos()
                val timings = mutableListOf<Long>()
                repeat(10) {
                    for (query in listOf("Springfield", "Munich", "東京", "Twizel", "zzzznonexistentsettlement", "a", "san")) {
                        val before = android.os.SystemClock.elapsedRealtimeNanos()
                        search.search(query)
                        timings += (android.os.SystemClock.elapsedRealtimeNanos() - before) / 1000000
                    }
                }
                timings.sort()
                android.util.Log.i("PlaceSearchBenchmark", "installedBefore=$installedBefore openMs=${(opened-start)/1000000} firstMs=${(first-opened)/1000000} " +
                    "queries=${timings.size} medianMs=${timings[timings.size/2]} p95Ms=${timings[(timings.size*95/100)]} maxMs=${timings.last()}")
                assertTrue("Submitted queries stay below 2 seconds", timings.last() < 2000)
            }
            context.cacheDir.deleteRecursively()
            PlaceSearch(context).use { assertEquals(3413829L, it.search("Reykjavik").first().id) }
        } finally {
            if (!externallyOffline) {
                if (wifi == "1") shell("su 0 svc wifi enable")
                if (data == "1") shell("su 0 svc data enable")
            }
        }
    }

    @Test fun bundledSearchDistinguishesDuplicateNamesAndRegions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installedBefore = java.io.File(context.noBackupFilesDir, "place-search/places-v1.sqlite").exists()
        val start = android.os.SystemClock.elapsedRealtime()
        PlaceSearch(context).use { search ->
            android.util.Log.i("PlaceSearchBenchmark", "installedBefore=$installedBefore installOrOpenMs=${android.os.SystemClock.elapsedRealtime()-start}")
            val results = search.search("Springfield")
            assertTrue(results.any { it.countryCode == "US" && it.region == "Illinois" })
            assertTrue(results.any { it.countryCode == "US" && it.region == "Massachusetts" })
            assertTrue(results.all { it.country.isNotBlank() && it.region.isNotBlank() })
        }
    }
}
