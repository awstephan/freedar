package com.freedar

import android.app.Application
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import com.freedar.radar.RadarTileRequests

/** Network budgets and provider backoff survive activity recreation. */
class FreedarApplication : Application() {
    lateinit var imagery: DownloadedImagery
        private set
    lateinit var radarArchive: RadarArchive
        private set
    val processSession = java.util.UUID.randomUUID().toString()
    lateinit var httpClient: OkHttpClient
        private set
    val radarTileRequests = RadarTileRequests(isRadarTile = { it.encodedPath.endsWith(".png") && !it.isCarto() })

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        imagery = DownloadedImagery(java.io.File(cacheDir, "downloaded-imagery"))
        radarArchive = RadarArchive(imagery)
        httpClient = configureMapNetwork(this, BuildConfig.CARTO_API_KEY, imagery, radarArchive, radarTileRequests)
        val scheduler = getSystemService(android.app.job.JobScheduler::class.java)
        if (scheduler.getPendingJob(IMAGERY_EXPIRY_JOB_ID)?.intervalMillis != 15 * 60 * 1000L) scheduler.schedule(android.app.job.JobInfo.Builder(IMAGERY_EXPIRY_JOB_ID,
            android.content.ComponentName(this, ImageryExpiryJob::class.java))
            .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
    }
    private companion object { const val IMAGERY_EXPIRY_JOB_ID = 9 }
}
