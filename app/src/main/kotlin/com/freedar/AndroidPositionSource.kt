package com.freedar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Android platform only: fresh foreground updates, no cached fix or Google services. */
class AndroidPositionSource(
    private val context: Context,
    private val requestPermission: ((Boolean) -> Unit) -> Unit,
) : ForegroundPositionSource {
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    override fun hasPermission() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun permission(result: (Boolean) -> Unit) {
        if (hasPermission()) result(true)
        else requestPermission(result)
    }

    @Suppress("MissingPermission") // permission is checked by the app; revocation is caught below.
    override fun locate(result: (PositionResult) -> Unit): () -> Unit {
        val manager = context.getSystemService(LocationManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val providers = mutableSetOf<String>()
        lateinit var listener: LocationListener
        lateinit var timeout: Runnable
        fun stop() {
            done = true
            handler.removeCallbacks(timeout)
            manager.removeUpdates(listener)
        }
        fun finish(value: PositionResult) {
            if (!done) { stop(); result(value) }
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val age = android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
                if (age < 0 || age > 30_000_000_000L || !location.latitude.isFinite() ||
                    !location.longitude.isFinite() || location.latitude !in -90.0..90.0 ||
                    location.longitude !in -180.0..180.0) return
                finish(PositionResult.Fix(LocalPosition(location.latitude, location.longitude)))
            }
            override fun onProviderDisabled(provider: String) {
                providers.remove(provider)
                if (providers.isEmpty()) finish(PositionResult.Unavailable)
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Legacy platform callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        timeout = Runnable { finish(PositionResult.Unavailable) }
        try {
            providers += listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
                (it != LocationManager.GPS_PROVIDER || granted(Manifest.permission.ACCESS_FINE_LOCATION)) &&
                    manager.isProviderEnabled(it)
            }
            if (providers.isEmpty()) finish(PositionResult.Unavailable)
            else {
                handler.postDelayed(timeout, 30_000)
                providers.toList().forEach { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
            }
        } catch (_: Exception) { finish(PositionResult.Failed) }
        return { stop() }
    }
}
