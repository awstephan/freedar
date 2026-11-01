package com.freedar

import com.freedar.radar.RadarFrame
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import okhttp3.HttpUrl

/** Original frame descriptors authorize exact tile templates; never infer time from an opaque path. */
class RadarArchive(private val store: DownloadedImagery, private val now: () -> Long = System::currentTimeMillis) {
    private var known = try { frames() } catch (_: java.io.IOException) { emptyList() }
    @Volatile var retainedOnly = false
        private set
    fun enterRetainedOnlyMode(): List<RadarFrame> { retainedOnly = true; return frames() }
    fun beginRefresh() = store.generation()

    @Synchronized fun remember(frames: List<RadarFrame>, generation: Long) {
        retainedOnly = false
        known = (known + frames).distinctBy { it.tileTemplate }.filter(::withinWindow)
        for (frame in frames.filter(::withinWindow)) {
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { it.writeLong(frame.time.epochSecond); it.writeUTF(frame.tileTemplate) }
            }.toByteArray()
            store.put("radar-frame:${frame.tileTemplate}", DownloadedImagery.RADAR_FRAME_TYPE, bytes, generation, deadline(frame))
        }
    }

    fun frames(): List<RadarFrame> = store.entries(DownloadedImagery.RADAR_FRAME_TYPE).mapNotNull {
        try {
            DataInputStream(it.bytes.inputStream()).use { input ->
                RadarFrame(Instant.ofEpochSecond(input.readLong()), input.readUTF()).takeIf(::withinWindow)
            }
        } catch (_: Exception) { null }
    }.distinctBy { it.time }.sortedBy { it.time }

    @Synchronized fun retentionDeadlineForRegisteredTile(url: HttpUrl): Long? {
        known = known.filter(::withinWindow)
        return known.firstOrNull { frame ->
            val pattern = Regex.escape(frame.tileTemplate)
                .replace("{z}", "\\E[0-7]\\Q")
                .replace("{x}", "\\E[0-9]+\\Q")
                .replace("{y}", "\\E[0-9]+\\Q")
            Regex(pattern).matches(url.toString())
        }?.let(::deadline)
    }

    private fun withinWindow(frame: RadarFrame) = frame.time.toEpochMilli() <= now() && deadline(frame) > now()
    private fun deadline(frame: RadarFrame) = frame.time.toEpochMilli() + 2 * 60 * 60 * 1000L
}
