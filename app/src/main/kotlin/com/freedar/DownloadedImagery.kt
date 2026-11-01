package com.freedar

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Sole device imagery store. Retention is measured from receipt, never from access. */
class DownloadedImagery(private val directory: File, private val now: () -> Long = System::currentTimeMillis,
    private val capacity: Long = 100_000_000L) {
    data class Entry(val contentType: String, val bytes: ByteArray)
    private var epoch = 0L
    init {
        directory.mkdirs()
        try { prune() } catch (_: java.io.IOException) { /* Access retries cleanup before serving bytes. */ }
    }
    @Synchronized fun generation() = epoch
    @Synchronized fun get(key: String): Entry? {
        prune()
        val file = file(key)
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use {
                it.readLong()
                val entry = Entry(it.readUTF(), it.readBytes())
                file.setLastModified(now())
                entry
            }
        } catch (_: java.io.IOException) { file.delete(); null }
    }
    @Synchronized fun put(key: String, contentType: String, bytes: ByteArray, generation: Long) {
        if (generation != epoch) return
        // Include file metadata and staging bytes in the budget, not just tile payloads.
        val encoded = java.io.ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { it.writeLong(now()); it.writeUTF(contentType); it.write(bytes) }
        }.toByteArray()
        if (encoded.size > capacity) return
        prune()
        val target = file(key)
        val temporary = File(directory, "pending")
        try {
            evictToFit(encoded.size.toLong())
            temporary.writeBytes(encoded)
            if (!temporary.renameTo(target)) throw java.io.IOException("Cannot store imagery")
            target.setLastModified(now())
            prune()
        } finally { temporary.delete() }
    }
    @Synchronized fun clear() {
        epoch++
        files().forEach(::delete)
    }
    @Synchronized fun remove(key: String) { delete(file(key)) }
    @Synchronized fun size(): Long { prune(); return files().sumOf { it.length() } }
    @Synchronized fun prune() {
        files().forEach { file ->
            val received = try { DataInputStream(file.inputStream()).use { it.readLong() } }
                catch (_: java.io.IOException) { Long.MIN_VALUE }
            // Delete a day before CARTO's ceiling, allowing margin for background scheduling.
            if (received > now() || received <= now() - 29L * 86400000 || file.name == "pending") {
                delete(file)
            }
        }
        evictToFit(0)
    }
    private fun evictToFit(incomingBytes: Long) {
        var size = files().sumOf { it.length() }
        files().sortedBy { it.lastModified() }.forEach {
            if (size + incomingBytes > capacity) { val length = it.length(); delete(it); size -= length }
        }
    }
    private fun files() = directory.listFiles()?.toList().orEmpty()
    private fun delete(file: File) {
        if (!file.delete() && file.exists()) throw java.io.IOException("Cannot delete downloaded imagery")
    }
    private fun file(key: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) })
}
