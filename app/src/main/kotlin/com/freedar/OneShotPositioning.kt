package com.freedar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalPosition(val latitude: Double, val longitude: Double)
sealed interface PositionResult {
    data class Fix(val position: LocalPosition) : PositionResult
    data object Unavailable : PositionResult
    data object Failed : PositionResult
}

/** Main-thread boundary. A source may finish synchronously; cancellation is best effort. */
interface ForegroundPositionSource {
    fun permission(result: (Boolean) -> Unit)
    fun locate(result: (PositionResult) -> Unit): () -> Unit
}

data class PositioningState(val waiting: Boolean = false, val message: String? = null)

/** Commits a center-and-latest intent once. All calls and callbacks run on the UI thread. */
class OneShotPositioning(
    private val source: ForegroundPositionSource,
    private val centerAndLatest: (LocalPosition) -> Unit,
) {
    private val mutableState = MutableStateFlow(PositioningState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var stop: (() -> Unit)? = null

    fun locate() {
        if (state.value.waiting) return
        val request = ++generation
        mutableState.value = PositioningState(true, "Finding your location…")
        source.permission { allowed ->
            if (request == generation && state.value.waiting) {
                if (allowed) {
                    val cancel = source.locate { result ->
                        if (request == generation && state.value.waiting) {
                            finish(when (result) {
                                is PositionResult.Fix -> null
                                PositionResult.Unavailable -> "Location unavailable. Try again or explore the map manually."
                                PositionResult.Failed -> "Location failed. Try again or explore the map manually."
                            })
                            if (result is PositionResult.Fix) centerAndLatest(result.position)
                        }
                    }
                    if (request == generation && state.value.waiting) stop = cancel else cancel()
                } else finish("Location permission denied. You can still explore the map manually.")
            }
        }
    }

    fun cancel() { if (state.value.waiting) finish("Location cancelled.") }

    /** Call before pan/zoom or any search-selection camera navigation. */
    fun navigationStarted() { if (state.value.waiting) finish(null) }
    fun background() { if (state.value.waiting) finish(null) }

    private fun finish(message: String?) {
        generation++
        val cancel = stop
        stop = null
        mutableState.value = PositioningState(message = message)
        cancel?.invoke()
    }
}
