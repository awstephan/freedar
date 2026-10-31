package com.freedar.radar

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class RadarFrame(val time: Instant, val tileTemplate: String)

fun interface RadarProvider {
    suspend fun latest(): RadarFrame
}

/** Completes after presentation; failure/cancellation must retain the preceding frame. */
fun interface RadarDisplay {
    suspend fun show(frame: RadarFrame)
}

data class RadarState(
    val frame: RadarFrame? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val ageSeconds: Long = 0,
    val newestFrameTime: Instant? = null,
    val newestAgeSeconds: Long = 0,
) {
    val stale: Boolean get() = newestFrameTime != null && newestAgeSeconds > 20 * 60
}

/** Foreground-only commands; the caller owns lifecycle and cancellation. */
class LatestRadar(
    private val provider: RadarProvider,
    private val clock: Clock,
    private val display: RadarDisplay,
) {
    private val mutableState = MutableStateFlow(RadarState())
    val state = mutableState.asStateFlow()
    private val refreshLock = Mutex()

    suspend fun refresh() {
        if (!refreshLock.tryLock()) return
        mutableState.value = mutableState.value.copy(loading = true)
        try {
            val frame = provider.latest()
            mutableState.value = state.value.copy(newestFrameTime = frame.time)
            updateAge()
            display.show(frame)
            mutableState.value = state.value.copy(frame = frame, loading = false, error = null)
            updateAge()
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(loading = false)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false,
                error = "Radar refresh failed. Missing imagery does not mean clear weather.")
        } finally {
            refreshLock.unlock()
        }
    }

    fun updateAge() {
        val now = clock.instant().epochSecond
        mutableState.value = state.value.copy(
            ageSeconds = state.value.frame?.let { (now - it.time.epochSecond).coerceAtLeast(0) } ?: 0,
            newestAgeSeconds = state.value.newestFrameTime?.let { (now - it.epochSecond).coerceAtLeast(0) } ?: 0,
        )
    }
}
