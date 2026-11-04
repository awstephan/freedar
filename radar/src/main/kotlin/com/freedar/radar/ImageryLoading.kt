package com.freedar.radar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single-threaded ownership of qualifying imagery work. Panels only suppress its presentation. */
class ImageryLoading(private val scope: CoroutineScope) {
    class Operation internal constructor()

    private val pending = mutableSetOf<Operation>()
    private var delayJob: Job? = null
    private val mutableVisible = MutableStateFlow(false)
    val visible = mutableVisible.asStateFlow()

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
