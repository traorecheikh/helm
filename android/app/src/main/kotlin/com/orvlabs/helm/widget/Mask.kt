package com.orvlabs.helm.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import com.orvlabs.helm.R
import com.orvlabs.helm.data.World
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** White-on-transparent masks; colour comes from Image tint so day and night need no re-render. */
class Layers(val ink: Bitmap? = null, val muted: Bitmap? = null, val warn: Bitmap? = null, val signal: Bitmap? = null, val off: Bitmap? = null)

object Mask {
    private fun bmp(w: Int, h: Int) = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
    private fun fill() = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1 }
    private fun ring(sw: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; style = Paint.Style.STROKE; strokeWidth = sw }

    fun number(ctx: Context, text: String, capPx: Float): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(ctx, R.font.schibsted); fontVariationSettings = "'wght' 800"; letterSpacing = -0.04f; color = -1
            textSize = 100f
        }
        val probe = android.graphics.Rect().also { paint.getTextBounds("0", 0, 1, it) }
        paint.textSize = capPx / (probe.height() / 100f)
        val b = android.graphics.Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        val out = bmp(b.width() + 2, b.height() + 2)
        Canvas(out).drawText(text, (-b.left + 1).toFloat(), (-b.top + 1).toFloat(), paint)
        return out
    }

    /** dots: 0 running, 1 failing, 2 stopped, 3 building; already ordered problem-first. */
    fun grid(dots: List<Int>, w: Int, h: Int): Layers {
        val n = max(1, dots.size)
        var bestP = 1; var bestC = 1
        for (c in 1..n) { val r = ceil(n / c.toDouble()).toInt(); val p = floor(min(w / c.toDouble(), h / r.toDouble())).toInt(); if (p >= bestP) { bestP = p; bestC = c } }
        val p = bestP.toFloat(); val c = bestC; val r = ceil(n / c.toDouble()).toInt()
        val ink = bmp(w, h); val muted = bmp(w, h); val sig = bmp(w, h)
        val ci = Canvas(ink); val cm = Canvas(muted); val cs = Canvas(sig)
        val ox = (w - c * p).coerceAtLeast(0f); val oy = (h - r * p).coerceAtLeast(0f)
        val d = max(3f, (0.64f * p).roundToInt().toFloat())
        dots.forEachIndexed { i, k ->
            val x = ox + (i % c) * p + p / 2; val y = oy + (i / c) * p + p / 2
            when (k) {
                0 -> ci.drawCircle(x, y, d / 2, fill())
                3 -> ci.drawCircle(x, y, d / 2 - max(1f, 0.14f * p) / 2, ring(max(1f, (0.14f * p).roundToInt().toFloat())))
                2 -> cm.drawCircle(x, y, max(1.5f, 0.2f * p), fill())
                else -> { cs.drawCircle(x, y, max(1.5f, 0.22f * p), fill()); cs.drawCircle(x, y, 0.46f * p - 0.5f, ring(max(1f, 0.1f * p))) }
            }
        }
        return Layers(ink = ink, muted = muted, signal = sig)
    }

    /** returns lit mask and track mask of a rounded meter. */
    fun seg(pct: Double, w: Int, h: Int): Pair<Bitmap, Bitmap> {
        val a = bmp(w, h); val b = bmp(w, h)
        val r = h / 2f
        Canvas(b).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, fill())
        if (pct > 0) Canvas(a).drawRoundRect(0f, 0f, max(h.toFloat(), (w * pct / 100).toFloat().coerceAtMost(w.toFloat())), h.toFloat(), r, r, fill())
        return a to b
    }

    /** 60 days, height encodes the value; today shortened with a bullseye when down now. */
    fun strip(values: List<Double?>, w: Int, h: Int, downNow: Boolean): Layers {
        val ink = bmp(w, h); val warn = bmp(w, h); val off = bmp(w, h); val sig = bmp(w, h)
        val gap = max(1f, floor(w / 60f * 0.4f)); val bar = floor((w - 59 * gap) / 60f)
        val left = w - (60 * bar + 59 * gap)
        val slots = List(60) { i -> values.getOrNull(i - (60 - values.size)) }
        slots.forEachIndexed { i, v ->
            val x = left + i * (bar + gap)
            val today = i == 59 && downNow
            val (cv, hh) = when {
                today -> sig to h * 0.7f
                v == null -> off to 2f
                v >= 99.95 -> ink to h.toFloat()
                v >= 99.0 -> warn to h * 0.55f
                else -> sig to h * 0.25f
            }
            Canvas(cv).drawRoundRect(x, h - hh, x + bar, h.toFloat(), bar / 2, bar / 2, fill())
            if (today) Canvas(sig).drawCircle(x + bar / 2, bar / 2 + 1, bar, fill())
        }
        return Layers(ink = ink, warn = warn, off = off, signal = sig)
    }

    fun map(world: World, levels: Map<String, Int>, w: Int, h: Int, lockdown: Boolean): Layers {
        val p = floor(min(w / world.cols.toFloat(), h / world.rows.toFloat())).coerceAtLeast(1f)
        val ox = (w - world.cols * p) / 2; val oy = (h - world.rows * p) / 2
        val off = bmp(w, h); val ink = bmp(w, h); val sig = bmp(w, h)
        val co = Canvas(off); val ci = Canvas(ink)
        for (i in world.cells.indices) {
            val ci2 = world.cells[i]; if (ci2 < 0) continue
            val lvl = levels[world.countries[ci2].code] ?: 0
            val x = ox + (i % world.cols) * p + p / 2; val y = oy + (i / world.cols) * p + p / 2
            if (lvl == 0) co.drawCircle(x, y, max(0.8f, 0.25f * p), fill()) else ci.drawCircle(x, y, max(1f, 0.25f * p + 0.1f * p * lvl), fill())
        }
        world.country("SN")?.let { Canvas(if (lockdown) sig else ink).drawCircle(ox + world.cellX(it.x).toFloat() * p, oy + world.cellY(it.y).toFloat() * p, 2.6f * p, ring(max(1.5f, 0.15f * p))) }
        return Layers(off = off, ink = ink, signal = sig)
    }

    /** marks: 0 nothing, 1 deploy ok, 2 resolved, 3 still down; last slot of a running night is "now" (ring). */
    fun horizon(marks: List<Int>, slots: Int, w: Int, h: Int, now: Boolean): Layers {
        val p = floor(w / slots.toFloat()); val off = bmp(w, h); val ink = bmp(w, h); val warn = bmp(w, h); val sig = bmp(w, h)
        val d = max(3f, 0.64f * p); val cy = h / 2f
        for (i in 0 until slots) {
            val x = i * p + p / 2
            val k = marks.getOrNull(i * marks.size.coerceAtLeast(1) / slots)
            when {
                k == null && !now -> Canvas(off).drawCircle(x, cy, 0.25f * p, fill())
                k == null -> Unit
                now && i == marks.size - 1 -> Canvas(ink).drawCircle(x, cy, d / 2 - 1, ring(max(1f, 0.14f * p)))
                k == 1 -> Canvas(ink).drawCircle(x, cy, d / 2, fill())
                k == 2 -> Canvas(warn).drawCircle(x, cy, d / 2, fill())
                k == 3 -> { Canvas(sig).drawCircle(x, cy, 0.22f * p, fill()); Canvas(sig).drawCircle(x, cy, 0.46f * p - 0.5f, ring(max(1f, 0.1f * p))) }
                else -> Canvas(off).drawCircle(x, cy, 0.25f * p, fill())
            }
        }
        return Layers(off = off, ink = ink, warn = warn, signal = sig)
    }
}
