package com.freedar

import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Disk failure cannot turn a successful provider response into a failed refresh. */
class RetainedRadarProvider(private val live: RadarProvider, private val archive: RadarArchive) : RadarProvider {
    override suspend fun latest() = history().last()
    override suspend fun history(): List<RadarFrame> {
        val generation = archive.beginRefresh()
        val frames = live.history()
        withContext(Dispatchers.IO) {
            try { archive.remember(frames, generation) }
            catch (_: java.io.IOException) { /* Network imagery remains usable. */ }
        }
        return frames
    }
    override suspend fun retainedHistory() = withContext(Dispatchers.IO) { archive.enterRetainedOnlyMode() }
}
