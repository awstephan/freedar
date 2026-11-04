package com.freedar.radar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

// LatestRadar presentation revisions start at one; startup has a stable, separate identity.
private const val INITIAL_RADAR_PREPARATION = 0L

/** Single-threaded ownership of qualifying imagery work. Panels only suppress its presentation. */
class ImageryLoading(private val scope: CoroutineScope) {
    class Operation internal constructor()

    private val pending = mutableSetOf<Operation>()
    private var delayJob: Job? = null
    private val mutableVisible = MutableStateFlow(false)
    val visible = mutableVisible.asStateFlow()

    /** Collect for the foreground lifetime; replacements release the previous operation first. */
    suspend fun trackRadar(state: StateFlow<RadarState>) {
        state.map { if (it.initialImageryLoading) INITIAL_RADAR_PREPARATION else it.framePreparation }
            .distinctUntilChanged().collectLatest { preparation ->
                if (preparation != null) {
                    val operation = begin()
                    try { awaitCancellation() } finally { end(operation) }
                }
            }
    }

    fun begin(): Operation {
        val operation = Operation()
        if (pending.isEmpty()) {
            delayJob = scope.launch {
                delay(200)
                mutableVisible.value = true
            }
        }
        pending.add(operation)
        return operation
    }

    /** Readiness, cancellation and terminal errors all release only their own contribution. */
    fun end(operation: Operation) {
        if (!pending.remove(operation)) return
        if (pending.isEmpty()) {
            delayJob?.cancel()
            delayJob = null
            mutableVisible.value = false
        }
    }
}
