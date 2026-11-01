package com.freedar

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DownloadedImageryTest {
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
            val store = DownloadedImagery(root, { now }, capacity = 60)
            store.put("a", "x", ByteArray(10), store.generation())
            now++
            store.put("b", "x", ByteArray(10), store.generation())
            now++
            store.get("a")
            now++
            val reopened = DownloadedImagery(root, { now }, capacity = 60)
            reopened.put("c", "x", ByteArray(10), reopened.generation())
            assertNull(reopened.get("b"))
            assertNotNull(reopened.get("a"))
            assertNotNull(reopened.get("c"))
            now++
            reopened.put("huge", "x", ByteArray(61), reopened.generation())
            assertNotNull(reopened.get("a"))
            assertNotNull(reopened.get("c"))
            assertNull(reopened.get("huge"))
            assertTrue(reopened.size() <= 60)
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
