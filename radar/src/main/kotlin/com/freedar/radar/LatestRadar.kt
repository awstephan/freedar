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
    /** Previously received descriptors, with original times; never a successful live refresh. */
    suspend fun retainedHistory(): List<RadarFrame> = emptyList()
}

/** Completes after presentation; failure/cancellation must retain the preceding frame. */
fun interface RadarDisplay {
    suspend fun show(frame: RadarFrame)
    /** Native renderers load invisibly, await this gate, then expose the ready frame. */
    suspend fun showWhenReady(frame: RadarFrame, awaitPresentationTime: suspend () -> Unit) {
        awaitPresentationTime()
        show(frame)
    }
    /** Load newest into in-session memory without replacing displayed imagery. */
    suspend fun prepare(frame: RadarFrame) {}
}

enum class TimelineMode { FOLLOWING, SELECTED, PLAYING }

enum class PlaybackSpeed(val fps: Int) { SLOW(1), NORMAL(2), FAST(4) }

data class RadarState(
    val mode: TimelineMode = TimelineMode.FOLLOWING,
    val unavailable: Set<RadarFrame> = emptySet(),
    /** Requested slider target; can differ from imagery while loading or after failure. */
    val selection: RadarFrame? = null,
    val frames: List<RadarFrame> = emptyList(),
    /** Last successfully presented image. Only this frame may label the visible imagery. */
    val frame: RadarFrame? = null,
    val loading: Boolean = false,
    val refreshError: String? = null,
    val imageError: String? = null,
    val offline: Boolean = false,
    val retainedHistory: Boolean = false,
    val ageSeconds: Long = 0,
    val newestFrameTime: Instant? = null,
    val newestAgeSeconds: Long = 0,
) {
    val error: String? get() = listOfNotNull(refreshError, imageError).takeIf { it.isNotEmpty() }?.joinToString("\n")
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
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    restoredSelection: RadarFrame? = null,
    restoredMode: TimelineMode = TimelineMode.FOLLOWING,
    restoredAwaySince: Long? = null,
) {
    private val mutableState = MutableStateFlow(RadarState(selection = restoredSelection,
        mode = if (restoredSelection == null) TimelineMode.FOLLOWING else restoredMode))
    val state = mutableState.asStateFlow()
    private val refreshLock = Mutex()
    private var playback: Job? = null
    /** Changes apply to the next presentation interval; never shorten the newest hold. */
    var speed: PlaybackSpeed = PlaybackSpeed.NORMAL
    private var presentation: Job? = null
    private val displayLock = Mutex()
    private var revision = 0L
    private var fetching = false
    private var awaySince: Long? = restoredAwaySince
    private var resumeMode = restoredMode
    val backgroundTime: Long? get() = awaySince
    val backgroundMode: TimelineMode get() = resumeMode

    /** Idempotent so platform stop and coroutine cancellation can both notify us. */
    fun background() {
        if (awaySince != null) return
        awaySince = monotonicMillis()
        resumeMode = state.value.mode
        pause()
        presentation?.cancel()
    }

    /** Refresh on every return; the caller starts playback only after this completes. */
    suspend fun foreground(): Boolean {
        val away = awaySince
        awaySince = null
        if (away != null) {
            val elapsed = monotonicMillis() - away
            val mode = if (elapsed < 0 || elapsed >= 300000) TimelineMode.FOLLOWING else resumeMode
            mutableState.value = state.value.copy(mode = mode)
        }
        updateAge()
        refresh()
        return state.value.mode == TimelineMode.PLAYING
    }

    private fun updateLoading() {
        mutableState.value = state.value.copy(loading = fetching || presentation != null)
    }

    private suspend fun present(frame: RadarFrame, prepareOnly: Boolean = false,
        notBefore: Long? = null): Boolean = coroutineScope {
        val request = ++revision
        presentation?.cancel()
        val task = async {
            displayLock.withLock {
                ensureActive()
                if (request != revision) return@withLock false
                mutableState.value = state.value.copy(loading = true,
                    selection = if (prepareOnly || state.value.mode == TimelineMode.PLAYING) state.value.selection else frame)
                try {
                    if (prepareOnly) display.prepare(frame) else display.showWhenReady(frame) {
                        if (notBefore != null) delay((notBefore - monotonicMillis()).coerceAtLeast(0))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (request == revision) mutableState.value = state.value.copy(
                        unavailable = state.value.unavailable + frame,
                        imageError = "Radar frame unavailable. Missing imagery does not mean clear weather.",
                    )
                    return@withLock false
                }
                ensureActive()
                if (request == revision) {
                    mutableState.value = state.value.copy(frame = if (prepareOnly) state.value.frame else frame,
                        imageError = if (prepareOnly) state.value.imageError else null,
                        selection = if (prepareOnly) state.value.selection else frame,
                        unavailable = state.value.unavailable - frame)
                    updateAge()
                }
                true
            }
        }
        presentation = task
        try {
            task.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        } finally {
            if (request == revision) {
                presentation = null
                updateLoading()
            }
        }
    }

    /** Play starts oldest; resume restores an active session after recreation or a short absence. */
    suspend fun play(resume: Boolean = false) {
        pause()
        val job = currentCoroutineContext()[Job]
        playback = job
        var previous: RadarFrame? = if (resume) state.value.frame else null
        var nextPresentation = monotonicMillis()
        try {
            // Opening always finishes its newest-frame attempt before history competes.
            refreshLock.withLock { }
            if (state.value.frames.isEmpty()) return
            mutableState.value = state.value.copy(mode = TimelineMode.PLAYING)
            while (currentCoroutineContext().isActive) {
                refreshLock.withLock { }
                val frames = state.value.frames.filterNot { it in state.value.unavailable }
                if (frames.isEmpty()) break
                val frame = frames.firstOrNull { previous == null || it.time > previous!!.time } ?: frames.first()
                if (!present(frame, notBefore = nextPresentation)) continue
                previous = frame
                val period = if (frame == frames.last()) 1000L else 1000L / speed.fps
                nextPresentation = monotonicMillis() + period
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
        showLatest()
    }

    private suspend fun showLatest() {
        val frames = state.value.frames
        if (state.value.retainedHistory) {
            for (frame in frames.asReversed()) if (present(frame)) break
        } else frames.lastOrNull()?.let { present(it) }
    }

    suspend fun refresh(retryDisplayed: Boolean = false) {
        if (!refreshLock.tryLock()) return
        fetching = true
        updateLoading()
        try {
            var retained = false
            val history = try { provider.history() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    retained = true
                    provider.retainedHistory().ifEmpty { throw failure }
                }
            val returned = history.distinctBy { it.time }.sortedBy { it.time }
            val frame = returned.last()
            val frames = returned.filter { it.time >= frame.time.minusSeconds(7200) }
            val newestChanged = frame.time != state.value.newestFrameTime
            val retrySelection = state.value.selection?.takeIf {
                state.value.mode == TimelineMode.SELECTED && (retryDisplayed || it in state.value.unavailable)
            }
            mutableState.value = state.value.copy(frames = frames, newestFrameTime = frame.time,
                retainedHistory = retained,
                refreshError = if (retained) "Radar refresh failed. Showing saved radar, not live data." else null,
                unavailable = emptySet())
            updateAge()
            if (state.value.mode == TimelineMode.FOLLOWING) {
                showLatest()
            } else if (state.value.frame == null && state.value.selection != null) {
                present(requireNotNull(state.value.selection))
            } else if (retrySelection != null) {
                present(retrySelection)
            } else if (state.value.mode == TimelineMode.PLAYING && newestChanged) {
                present(frame, prepareOnly = true)
            }
            updateAge()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                refreshError = "Radar refresh failed. Missing imagery does not mean clear weather.")
        } finally {
            fetching = false
            updateAge()
            updateLoading()
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

    fun connectivity(offline: Boolean) {
        mutableState.value = state.value.copy(offline = offline)
    }
}
