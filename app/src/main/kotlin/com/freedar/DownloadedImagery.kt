package com.freedar

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Sole device imagery store. Deadlines never extend on access. */
class DownloadedImagery(private val directory: File, private val now: () -> Long = System::currentTimeMillis,
    private val capacity: Long = 100_000_000L) {
    data class Entry(val contentType: String, val bytes: ByteArray)
    private var epoch = 0L
    init {
        directory.mkdirs()
        try { prune() } catch (_: java.io.IOException) { /* Access retries cleanup before serving bytes. */ }
    }
    @Synchronized fun generation() = epoch
    /** Small provider descriptors share the imagery budget and their own frame deadlines. */
    @Synchronized fun entries(contentType: String): List<Entry> {
        prune()
        return files().mapNotNull { file ->
            try {
                DataInputStream(file.inputStream().buffered()).use {
                    readDeadline(it)
                    if (it.readUTF() == contentType) Entry(contentType, it.readBytes()) else null
                }
            } catch (_: java.io.IOException) { delete(file); null }
        }
    }
    @Synchronized fun get(key: String): Entry? {
        prune()
        val file = file(key)
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use {
                readDeadline(it)
                val entry = Entry(it.readUTF(), it.readBytes())
                file.setLastModified(now())
                entry
            }
        } catch (_: java.io.IOException) { file.delete(); null }
    }
    @Synchronized fun put(key: String, contentType: String, bytes: ByteArray, generation: Long,
        expiresAtMillis: Long = now() + BASE_RETENTION_MILLIS) {
        if (generation != epoch) return
        if (expiresAtMillis <= now()) { remove(key); return }
        directory.mkdirs() // Android may reclaim the cache directory while this owner is alive.
        // Include file metadata and staging bytes in the budget, not just tile payloads.
        val encoded = java.io.ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeLong(FORMAT_V2); it.writeLong(now())
                it.writeLong(minOf(expiresAtMillis, now() + BASE_RETENTION_MILLIS))
                it.writeUTF(contentType); it.write(bytes)
            }
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
            val deadline = try { DataInputStream(file.inputStream()).use(::readDeadline) }
                catch (_: java.io.IOException) { Long.MIN_VALUE }
            if (deadline <= now() || file.name == "pending") {
                delete(file)
            }
        }
        evictToFit(0)
    }
    private fun readDeadline(input: DataInputStream): Long {
        val first = input.readLong()
        val received = if (first == FORMAT_V2) input.readLong() else first
        val deadline = if (first == FORMAT_V2) input.readLong() else received + BASE_RETENTION_MILLIS
        return if (received > now() || received < 0) Long.MIN_VALUE else deadline
    }
    private fun evictToFit(incomingBytes: Long) {
        val candidates = files()
        var size = candidates.sumOf { it.length() }
        if (size + incomingBytes <= capacity) return
        // Frame catalog records are index metadata: evict LRU imagery before its
        // descriptors, or still-retained radar pixels become undiscoverable on restart.
        val descriptors = candidates.filter(::isFrameDescriptor).toSet()
        candidates.sortedWith(compareBy<File> { it in descriptors }.thenBy { it.lastModified() }).forEach {
            if (size + incomingBytes > capacity) { val length = it.length(); delete(it); size -= length }
        }
    }
    private fun isFrameDescriptor(file: File): Boolean = try {
        DataInputStream(file.inputStream().buffered()).use {
            readDeadline(it)
            it.readUTF() == RADAR_FRAME_TYPE
        }
    } catch (_: java.io.IOException) { false }
    private fun files() = directory.listFiles()?.toList().orEmpty()
    private fun delete(file: File) {
        if (!file.delete() && file.exists()) throw java.io.IOException("Cannot delete downloaded imagery")
    }
    private fun file(key: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) })
    companion object {
        internal const val RADAR_FRAME_TYPE = "application/vnd.freedar.radar-frame"
        private const val FORMAT_V2 = -2L
        private const val BASE_RETENTION_MILLIS = 29L * 86400000
    }
}
