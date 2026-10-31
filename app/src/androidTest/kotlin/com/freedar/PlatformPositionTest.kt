package com.freedar

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlatformPositionTest {
    @get:Rule val permission = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Suppress("DEPRECATION")
    @Test fun platformGpsFixIsOneShotAndCancellationStopsDeliveryWithoutGoogleServices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(LocationManager::class.java)
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("appops set ${context.packageName} android:mock_location allow")
        var cancel: () -> Unit = {}
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 1, 1)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val received = mutableListOf<PositionResult>()
            val ready = CountDownLatch(1)
            val source = AndroidPositionSource(context) { error("Already granted") }
            instrumentation.runOnMainSync {
                source.permission { assertTrue(it) }
                cancel = source.locate { received += it; ready.countDown() }
            }
            fun fix(latitude: Double) = manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,
                Location(LocationManager.GPS_PROVIDER).apply {
                    this.latitude = latitude; longitude = -100.0; accuracy = 50f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            fix(40.0)
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(listOf(PositionResult.Fix(LocalPosition(40.0, -100.0))), received)
                cancel()
                cancel = source.locate { received += it }
                cancel()
            }
            fix(41.0)
            Thread.sleep(500)
            instrumentation.runOnMainSync { assertEquals(1, received.size) }
        } finally {
            instrumentation.runOnMainSync { cancel() }
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            shell("appops set ${context.packageName} android:mock_location default")
        }
    }
}
