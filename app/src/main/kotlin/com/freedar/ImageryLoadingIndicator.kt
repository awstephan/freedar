package com.freedar

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** No input handlers or live region: map gestures pass through and frames aren't announced. */
@Composable
fun ImageryLoadingIndicator(dark: Boolean, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    var animationsEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animationsEnabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animationsEnabled) {
        phase = 0f
        if (animationsEnabled) {
            val start = withFrameMillis { it }
            while (true) withFrameMillis { phase = ((it - start) % 900) / 900f }
        }
    }
    Canvas(modifier.size(48.dp).semantics { contentDescription = "Loading map imagery" }) {
        val color = if (dark) Color.White else Color.Black
        for (index in 0..3) {
            val progress = (phase + index * .25f + .125f) % 1f
            val alpha = if (animationsEnabled) minOf(progress / .18f, (1f - progress) / .18f, 1f) * .6f else .6f
            translate((11.25f + index * 8.5f).dp.toPx(), (8f + 24f * progress).dp.toPx()) {
                val width = 5.5.dp.toPx()
                val height = 8.dp.toPx()
                val drop = Path().apply {
                    moveTo(0f, 0f)
                    cubicTo(width / 3, height * .35f, width / 2, height * .55f, width / 2, height * .68f)
                    cubicTo(width / 2, height * 1.1f, -width / 2, height * 1.1f, -width / 2, height * .68f)
                    cubicTo(-width / 2, height * .55f, -width / 3, height * .35f, 0f, 0f)
                    close()
                }
                drawPath(drop, color.copy(alpha = alpha))
            }
        }
    }
}
