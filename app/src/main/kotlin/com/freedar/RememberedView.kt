package com.freedar

import android.content.Context
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

/** Camera preferences are not downloaded imagery. Absence means the first-use overview. */
class RememberedView(context: Context) {
    private val preferences = context.getSharedPreferences("remembered-view", Context.MODE_PRIVATE)
    fun read(): CameraPosition? {
        val latitude = preferences.getString("latitude", null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString("longitude", null)?.toDoubleOrNull() ?: return null
        val zoom = preferences.getString("zoom", null)?.toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || !zoom.isFinite() ||
            latitude !in -90.0..90.0 || zoom !in 0.0..18.0) return null
        return CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(zoom).build()
    }
    fun save(camera: CameraPosition) {
        val target = camera.target ?: return
        preferences.edit().putString("latitude", target.latitude.toString())
            .putString("longitude", target.longitude.toString()).putString("zoom", camera.zoom.toString()).apply()
    }
}
