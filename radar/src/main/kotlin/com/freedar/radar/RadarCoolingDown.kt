package com.freedar.radar

import java.io.IOException

/** Temporary provider-wide throttle, not evidence that a radar frame is missing. */
class RadarCoolingDown(val retryAfterMillis: Long) : IOException("Radar is cooling down")
