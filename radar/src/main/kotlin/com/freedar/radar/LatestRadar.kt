package com.freedar.radar

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RadarFrame(val time: Instant, val tileTemplate: String)

fun interface RadarProvider {
    suspend fun latest(): RadarFrame
    suspend fun history(): List<RadarFrame> = listOf(latest())
}

/** Completes after presentation; failure/cancellation must retain the preceding frame. */
fun interface RadarDisplay {
    suspend fun show(frame: RadarFrame)
}

enum class TimelineMode { FOLLOWING, SELECTED, PLAYING }

data class RadarState(
    val mode: TimelineMode = TimelineMode.FOLLOWING,
    val unavailable: Set<RadarFrame> = emptySet(),
    val selection: RadarFrame? = null,
    val frames: List<RadarFrame> = emptyList(),
    val frame: RadarFrame? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val ageSeconds: Long = 0,
    val newestFrameTime: Instant? = null,
    val newestAgeSeconds: Long = 0,
) {
    val stale: Boolean get() = newestFrameTime != null && newestAgeSeconds > 20 * 60
    // RainViewer's published historical cadence is ten minutes, not a fixed frame count.
    val gaps: List<Pair<RadarFrame, RadarFrame>> get() = frames.zipWithNext().filter {
        it.second.time.epochSecond - it.first.time.epochSecond > 600
    }
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
    private var playback: Job? = null
    private var presentation: Job? = null
    private val displayLock = Mutex()
    private var revision = 0L

    private suspend fun present(frame: RadarFrame) = coroutineScope {
        val request = ++revision
        presentation?.cancel()
        val task = async {
            displayLock.withLock {
                ensureActive()
                if (request != revision) return@withLock
                mutableState.value = state.value.copy(loading = true, selection = frame)
                try {
                    display.show(frame)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (request == revision) mutableState.value = state.value.copy(
                        unavailable = state.value.unavailable + frame,
                        error = "Radar frame unavailable. Retaining previous imagery; gaps do not mean clear weather.",
                    )
                    return@withLock
                }
                ensureActive()
                if (request == revision) {
                    mutableState.value = state.value.copy(frame = frame, error = null,
                        unavailable = state.value.unavailable - frame)
                    updateAge()
                }
            }
        }
        presentation = task
        try {
            task.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } finally {
            if (request == revision) {
                presentation = null
                mutableState.value = state.value.copy(loading = false)
            }
        }
    }

    suspend fun play() {
        pause()
        val job = currentCoroutineContext()[Job]
        playback = job
        var index = 0
        try {
            // Opening always finishes its newest-frame attempt before history competes.
            refreshLock.withLock { }
            if (state.value.frames.isEmpty()) return
            mutableState.value = state.value.copy(mode = TimelineMode.PLAYING)
            while (currentCoroutineContext().isActive) {
                val frames = state.value.frames.filterNot { it in state.value.unavailable }
                if (frames.isEmpty()) break
                val frame = frames[index.coerceAtMost(frames.lastIndex)]
                present(frame)
                if (frame in state.value.unavailable) continue
                delay(if (frame == frames.last()) 1000 else 500)
                index = (index + 1) % frames.size
            }
        } finally {
            if (playback == job) {
                playback = null
                mutableState.value = state.value.copy(mode = TimelineMode.SELECTED)
            }
        }
    }

    fun pause() {
        playback?.cancel()
        playback = null
        if (state.value.mode == TimelineMode.PLAYING) {
            mutableState.value = state.value.copy(mode = TimelineMode.SELECTED, selection = state.value.frame)
        }
    }

    suspend fun select(frame: RadarFrame) {
        pause()
        if (frame !in state.value.frames) return
        mutableState.value = state.value.copy(mode = TimelineMode.SELECTED)
        present(frame)
    }

    suspend fun latest() {
        pause()
        mutableState.value = state.value.copy(mode = TimelineMode.FOLLOWING)
        val frame = state.value.frames.lastOrNull() ?: return
        present(frame)
    }

    suspend fun refresh() {
        if (!refreshLock.tryLock()) return
        mutableState.value = mutableState.value.copy(loading = true)
        try {
            val returned = provider.history().distinctBy { it.time }.sortedBy { it.time }
            val frame = returned.last()
            val frames = returned.filter { it.time >= frame.time.minusSeconds(7200) }
            val retrySelection = state.value.selection?.takeIf {
                state.value.mode == TimelineMode.SELECTED && it in state.value.unavailable
            }
            mutableState.value = state.value.copy(frames = frames, newestFrameTime = frame.time,
                unavailable = emptySet())
            updateAge()
            if (state.value.mode == TimelineMode.FOLLOWING) {
                present(frame)
            } else if (retrySelection != null) {
                present(retrySelection)
            }
            mutableState.value = state.value.copy(loading = false)
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
