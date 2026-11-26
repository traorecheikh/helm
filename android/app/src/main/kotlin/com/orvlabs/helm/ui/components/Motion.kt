package com.orvlabs.helm.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion

class Stagger {
    private var last = 0L
    private var n = 0

    fun next(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - last > 250) n = 0
        last = now
        return n++
    }
}

val LocalCompact = androidx.compose.runtime.compositionLocalOf { false }

val LocalStagger = staticCompositionLocalOf<Stagger?> { null }

fun Modifier.appear(): Modifier = composed {
    val reduce = LocalReduceMotion.current
    val stagger = LocalStagger.current
    val idx = remember { stagger?.next() ?: 99 }
    val a = remember { Animatable(if (reduce || idx >= 8) 1f else 0f) }
    LaunchedEffect(Unit) { if (a.value < 1f) a.animateTo(1f, tween(220, delayMillis = idx * 40, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 8.dp.toPx()
    }
}

@Composable
fun PulseRipple(trigger: Int, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val c = H.c
    val t = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0 && !reduce) {
            t.snapTo(0f)
            t.animateTo(1f, tween(900, easing = LinearOutSlowInEasing))
        }
    }
    Canvas(modifier.fillMaxSize()) {
        if (t.value < 1f) drawCircle(c.signal.copy(alpha = 0.4f * (1f - t.value)), size.minDimension * (0.25f + 0.55f * t.value), style = Stroke(2.dp.toPx()))
    }
}
