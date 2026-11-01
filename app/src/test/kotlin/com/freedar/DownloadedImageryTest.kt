package com.freedar

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DownloadedImageryTest {
    @Test fun `previous CARTO cache format survives upgrade without extending receipt time`() {
        val root = Files.createTempDirectory("imagery-upgrade").toFile()
        try {
            val name = java.security.MessageDigest.getInstance("SHA-256").digest("legacy".toByteArray())
                .joinToString("") { "%02x".format(it) }
            // v1 fixture: receipt=1000, content-type=x, payload=abc.
            java.io.File(root, name).writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 3, -24, 0, 1, 120, 97, 98, 99))
            var now = 1000L + 28 * 86400000L
            val upgraded = DownloadedImagery(root, { now })
            assertEquals("abc", String(upgraded.get("legacy")!!.bytes))
            now += 86400000L
            upgraded.prune()
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `radar expiry uses frame deadline while base map remains in the shared store`() {
        val root = Files.createTempDirectory("imagery").toFile()
        var now = 10_000_000L
        try {
            val store = DownloadedImagery(root, { now })
            store.put("base", "map", byteArrayOf(1), store.generation())
            // Already 110 minutes old when received: only ten minutes remain.
            store.put("radar", "radar", byteArrayOf(2), store.generation(), expiresAtMillis = now + 600_000)
            now += 599_999
            assertNotNull(DownloadedImagery(root, { now }).get("radar"))
            now++
            val reopened = DownloadedImagery(root, { now })
            assertNull(reopened.get("radar"))
            assertNotNull(reopened.get("base"))
            assertEquals(1, root.listFiles()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun `maintenance deletes expired unrequested entries and rollback cannot extend retention`() {
        val root = Files.createTempDirectory("imagery").toFile()
        var now = 1000L
        try {
            val store = DownloadedImagery(root, { now })
            store.put("unrequested", "x", byteArrayOf(1), store.generation())
            now += 29 * 86400000L
            store.prune()
            assertTrue(root.listFiles()!!.isEmpty())
            store.put("future", "x", byteArrayOf(1), store.generation())
            now -= 1000
            assertNull(store.get("future"))
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `aggregate LRU survives reopen and oversized downloads do not evict useful data`() {
        val root = Files.createTempDirectory("imagery").toFile()
        var now = 1000L
        try {
            val store = DownloadedImagery(root, { now }, capacity = 100)
            store.put("a", "x", ByteArray(10), store.generation())
            now++
            store.put("b", "x", ByteArray(10), store.generation())
            now++
            store.get("a")
            now++
            val reopened = DownloadedImagery(root, { now }, capacity = 100)
            reopened.put("c", "x", ByteArray(10), reopened.generation())
            assertNull(reopened.get("b"))
            assertNotNull(reopened.get("a"))
            assertNotNull(reopened.get("c"))
            now++
            reopened.put("huge", "x", ByteArray(101), reopened.generation())
            assertNotNull(reopened.get("a"))
            assertNotNull(reopened.get("c"))
            assertNull(reopened.get("huge"))
            assertTrue(reopened.size() <= 100)
        } finally { root.deleteRecursively() }
    }

    @Test fun `clear deletes imagery and rejects pre-clear downloads while preserving neighbors`() {
        val root = Files.createTempDirectory("app").toFile()
        try {
            val places = java.io.File(root, "place-search").apply { mkdirs() }
            java.io.File(places, "data").writeText("places")
            val store = DownloadedImagery(java.io.File(root, "imagery"))
            val old = store.generation()
            store.put("a", "x", byteArrayOf(1), old)
            store.clear()
            store.put("late", "x", byteArrayOf(1), old)
            assertEquals(0L, store.size())
            assertEquals("places", java.io.File(places, "data").readText())
            store.put("new", "x", byteArrayOf(2), store.generation())
            assertNotNull(store.get("new"))
        } finally { root.deleteRecursively() }
    }

    @Test fun `disk reuse does not extend retention and expiry deletes bytes`() {
        val root = Files.createTempDirectory("imagery").toFile()
        var now = 1000L
        try {
            val store = DownloadedImagery(root, { now })
            store.put("tile", "image/png", byteArrayOf(1, 2, 3), store.generation())
            now += 28 * 86400000L
            assertArrayEquals(byteArrayOf(1, 2, 3), DownloadedImagery(root, { now }).get("tile")!!.bytes)
            now += 86400000L
            val reopened = DownloadedImagery(root, { now })
            assertNull(reopened.get("tile"))
            assertEquals(0L, reopened.size())
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
