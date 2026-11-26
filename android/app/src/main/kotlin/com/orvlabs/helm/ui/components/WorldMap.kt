package com.orvlabs.helm.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import com.orvlabs.helm.ui.components.LocalCompact
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.data.Geo
import com.orvlabs.helm.data.World
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlin.math.abs
import kotlinx.coroutines.delay

class Ripple(val code: String, val start: Long = SystemClock.uptimeMillis())

@Composable
fun rememberRipples(geo: Geo?): SnapshotStateList<Ripple> {
    val ripples = remember { mutableStateListOf<Ripple>() }
    val seen = remember { mutableSetOf<String>() }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(geo) {
        val g = geo ?: return@LaunchedEffect
        val fresh = g.recent.filter { r ->
            r.country != null && seen.add(r.ip) && (!first[0] || r.at * 1000 > System.currentTimeMillis() - 600_000)
        }
        first[0] = false
        fresh.take(12).forEach {
            ripples.add(Ripple(it.country!!))
            delay(140)
        }
    }
    return ripples
}

@Composable
fun WorldMap(
    world: World,
    levels: Map<String, Int>,
    blocked: Set<String>,
    home: String?,
    ripples: SnapshotStateList<Ripple>,
    desc: String,
    modifier: Modifier = Modifier,
    onTap: ((String) -> Unit)? = null,
) {
    val c = H.c
    val reduce = LocalReduceMotion.current
    val life = if (reduce) 4000L else 1200L
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(ripples.size) {
        while (ripples.isNotEmpty()) {
            androidx.compose.runtime.withFrameMillis { now = SystemClock.uptimeMillis() }
            ripples.removeAll { now - it.start > life }
            if (reduce) delay(250)
        }
    }
    val heat = listOf(c.off, c.muted, c.ink.copy(alpha = 0.65f), c.ink)
    Canvas(
        modifier.fillMaxWidth().then(if (LocalCompact.current) Modifier.widthIn(max = 480.dp) else Modifier).aspectRatio(world.cols.toFloat() / world.rows).semantics { contentDescription = desc }
            .then(
                if (onTap == null) Modifier else Modifier.pointerInput(world) {
                    detectTapGestures { o ->
                        val step = size.width / world.cols.toFloat()
                        val col = (o.x / step).toInt()
                        val row = (o.y / step).toInt()
                        var best: Pair<Int, Int>? = null
                        for (dr in -2..2) for (dc in -2..2) {
                            val r = row + dr
                            val cc = col + dc
                            if (r !in 0 until world.rows || cc !in 0 until world.cols || world.cells[r * world.cols + cc] < 0) continue
                            val d = abs(dr) + abs(dc)
                            if (best == null || d < best.first) best = d to world.cells[r * world.cols + cc]
                        }
                        best?.let { onTap(world.countries[it.second].code) }
                    }
                },
            ),
    ) {
        val step = size.width / world.cols
        val r = step * 0.36f
        val hot = ripples.associate { it.code to ((now - it.start) / life.toFloat()).coerceIn(0f, 1f) }
        for (i in world.cells.indices) {
            val ci = world.cells[i]
            if (ci < 0) continue
            val code = world.countries[ci].code
            val cx = (i % world.cols) * step + step / 2
            val cy = (i / world.cols) * step + step / 2
            val t = hot[code]
            when {
                code in blocked -> drawCircle(c.muted, r * 0.85f, Offset(cx, cy), style = Stroke(0.7.dp.toPx()))
                code == home -> drawCircle(c.ink, r * 1.1f, Offset(cx, cy))
                else -> {
                    val lvl = levels[code] ?: 0
                    drawCircle(heat[lvl], r * (0.9f + 0.1f * lvl), Offset(cx, cy))
                }
            }
            if (t != null) drawCircle(c.muted.copy(alpha = if (reduce) 0.9f else 1f - t), r * 1.2f, Offset(cx, cy))
        }
        world.country(home ?: "")?.let {
            drawCircle(c.ink, step * 2.6f, Offset(world.cellX(it.x).toFloat() * step, world.cellY(it.y).toFloat() * step), style = Stroke(1.dp.toPx()))
        }
        ripples.forEach { rp ->
            val wc = world.country(rp.code) ?: return@forEach
            val t = ((now - rp.start) / life.toFloat()).coerceIn(0f, 1f)
            val center = Offset(world.cellX(wc.x).toFloat() * step, world.cellY(wc.y).toFloat() * step)
            if (reduce) drawCircle(c.muted, step * 3f, center, style = Stroke(1.5.dp.toPx()))
            else drawCircle(c.muted.copy(alpha = 1f - t), step * (2f + 11f * t), center, style = Stroke(1.6.dp.toPx()))
        }
    }
}
