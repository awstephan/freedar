package com.freedar

import com.freedar.radar.RadarProvider
import android.os.Bundle

/** Local-fixture host exercising the production activity's lifecycle and UI. */
class LifecycleTestActivity : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val restored = savedInstanceState ?: savedForTest
        savedForTest = null
        super.onCreate(restored)
    }
    fun saveBeforeStop(): Bundle = Bundle().also { onSaveInstanceState(it) }
    override fun radarProvider(): RadarProvider = provider
    override fun baseStyleUrl(): String? = null
    override fun elapsedMillis(): Long = elapsed
    override fun positionSource(): ForegroundPositionSource = positionSource ?: super.positionSource()

    companion object {
        lateinit var provider: RadarProvider
        var elapsed: Long = 0
        var savedForTest: Bundle? = null
        var positionSource: ForegroundPositionSource? = null
    }
}
