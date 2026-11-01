package com.freedar

import android.app.job.JobParameters
import android.app.job.JobService

/** Best-effort background deletion. Every store access also enforces the deadline. */
class ImageryExpiryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val retry = try { (application as FreedarApplication).imagery.prune(); false }
                catch (_: java.io.IOException) { true }
            jobFinished(params, retry)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
