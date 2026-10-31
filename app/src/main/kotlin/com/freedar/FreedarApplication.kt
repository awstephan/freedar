package com.freedar

import android.app.Application
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre

/** Network budgets and provider backoff survive activity recreation. */
class FreedarApplication : Application() {
    lateinit var httpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        httpClient = configureMapNetwork(this, BuildConfig.CARTO_API_KEY)
    }
}
