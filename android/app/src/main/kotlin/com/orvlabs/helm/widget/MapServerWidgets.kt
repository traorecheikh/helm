package com.orvlabs.helm.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import com.orvlabs.helm.R
import com.orvlabs.helm.countryName
import com.orvlabs.helm.data.Geo
import com.orvlabs.helm.data.World
import com.orvlabs.helm.data.heatLevels
import com.orvlabs.helm.num
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.flag
import com.orvlabs.helm.ui.components.gb

class MapWidget : HelmGlance() {
    @Composable
    override fun Body(w: W) {
        val s = w.s
        val total = s?.geo?.sumOf { it.attempts24h } ?: 0
        val top = s?.geo?.sortedByDescending { it.attempts24h }.orEmpty()
        val locked = s?.lockdownUntil != null
        val sentence = if (s == null) "" else problemLine(w) ?: if (locked) tr(R.string.w_lockdown_until, com.orvlabs.helm.ui.components.clock((s.lockdownUntil ?: 0) * 1000))
        else if (total == 0) tr(R.string.w_map_quiet) else tr(R.string.w_map_headline, num(total), top.first().let { countryName(it.code, it.name) })
        val alert = if (locked) tr(R.string.w_locked_tag) to 3 else null
        val root = if (s == null) "" else "${tr(R.string.w_eb_shield)}. $sentence ${tr(R.string.w_updated_at, w.time(s.at))}"
        Frame(w, root, if (locked) "helm://shield" else "helm://map") {
            val s2 = w.s ?: return@Frame
            Eyebrow(w, tr(R.string.shield), alert)
            if (w.narrow || w.size.height < 130.dp) {
                Spacer(GlanceModifier.defaultWeight())
                Mark(Mask.number(w.ctx, if (total > 9999) "${total / 1000}K" else "$total", w.px(36.dp).toFloat()), 120.dp, 36.dp, if (locked && w.live) w.bad else w.tone)
                T(tr(R.string.w_tries_24h), w, 12, w.muted)
                T(top.take(2).joinToString("  ") { "${flag(it.code)} ${num(it.attempts24h)}" }, w, 12, w.ink)
            } else {
                val world = World.load(w.ctx)
                val mw = w.size.width - 28.dp
                val mh = w.size.height - 28.dp - 14.dp - 40.dp
                Box(GlanceModifier.defaultWeight().fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Stack(Mask.map(world, heatLevels(Geo(countries = s2.geo)), w.px(mw), w.px(mh.coerceAtLeast(40.dp)), locked), mw, mh.coerceAtLeast(40.dp), w, w.tone)
                }
                Spacer(GlanceModifier.height(6.dp))
                T(sentence, w, 13, if (locked && w.live) w.bad else w.tone, bold = true, lines = if (locked) 2 else 1)
            }
        }
    }
}

class MapReceiver : HelmReceiver() {
    override val glanceAppWidget = MapWidget()
}

class ServerWidget : HelmGlance() {
    @Composable
    private fun Row3(w: W, label: String, pct: Double, value: String, warnAt: Double, badAt: Double, wide: Boolean) {
        val col = if (!w.live) w.muted else if (pct >= badAt) w.bad else if (pct >= warnAt) w.warn else w.ok
        val bw = (if (wide) w.size.width - 28.dp - 150.dp else w.size.width - 28.dp).coerceAtLeast(40.dp)
        val bh = if (wide) 8.dp else 6.dp
        val (lit, track) = Mask.seg(pct, w.px(bw), w.px(bh))
        if (wide) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(64.dp, 16.dp)) { T(label, w, 12, w.muted, bold = true) }
                Box(GlanceModifier.size(bw, bh)) { Mark(track, bw, bh, w.off); Mark(lit, bw, bh, col) }
                Spacer(GlanceModifier.size(8.dp, 1.dp))
                T(value, w, 12, col, bold = true)
            }
        } else {
            Row(GlanceModifier.fillMaxWidth()) { T(label, w, 12, w.muted, bold = true); Spacer(GlanceModifier.defaultWeight()); T(value, w, 12, col, bold = true) }
            Spacer(GlanceModifier.height(3.dp))
            Box(GlanceModifier.size(bw, bh)) { Mark(track, bw, bh, w.off); Mark(lit, bw, bh, col) }
        }
    }

    @Composable
    override fun Body(w: W) {
        val s = w.s
        val mem = if (s != null && s.memTotal > 0) s.memUsed * 100.0 / s.memTotal else 0.0
        val disk = if (s != null && s.diskTotal > 0) s.diskUsed * 100.0 / s.diskTotal else 0.0
        val days = s?.diskFullInDays
        val alert = when {
            s == null -> null
            mem >= 90 -> tr(R.string.w_mem_at_tag, mem.toInt()) to 3
            days != null && days <= 3 -> tr(R.string.w_disk_full_tag, days.toInt().coerceAtLeast(1)) to 3
            mem >= 85 -> tr(R.string.w_mem_high) to 2
            days != null && days <= 14 -> tr(R.string.w_disk_full_tag, days.toInt().coerceAtLeast(1)) to 2
            else -> null
        }
        val root = if (s == null) "" else "${tr(R.string.w_eb_server, s.hostname)}. ${tr(R.string.w_cpu)} ${s.cpu.toInt()} %. ${tr(R.string.w_mem)} ${mem.toInt()} %. ${tr(R.string.w_disk)} ${disk.toInt()} %. ${tr(R.string.w_updated_at, w.time(s.at))}"
        Frame(w, root, "helm://server") {
            val s2 = w.s ?: return@Frame
            val wide = !w.narrow && !w.oneRow
            if (w.oneRow) {
                Row(GlanceModifier.fillMaxWidth()) { T("${tr(R.string.cpu_2)} ${s2.cpu.toInt()} %", w, 12, w.tone, bold = true); Spacer(GlanceModifier.defaultWeight()); T("${tr(R.string.memory)} ${mem.toInt()} %", w, 12, w.tone, bold = true); Spacer(GlanceModifier.defaultWeight()); T("${tr(R.string.disk)} ${disk.toInt()} %", w, 12, w.tone, bold = true) }
            } else {
                Eyebrow(w, s2.hostname.take(24), alert)
                Spacer(GlanceModifier.height(6.dp))
                Row3(w, tr(R.string.cpu_2), s2.cpu, tr(R.string.w_pct, s2.cpu.toInt()), 90.0, 101.0, wide)
                Spacer(GlanceModifier.defaultWeight())
                Row3(w, tr(R.string.memory), mem, if (wide) tr(R.string.w_used_of, gb(s2.memUsed), gb(s2.memTotal)) else tr(R.string.w_pct, mem.toInt()), 85.0, 90.0, wide)
                Spacer(GlanceModifier.defaultWeight())
                Row3(w, tr(R.string.disk), disk, if (wide) tr(R.string.w_used_of, gb(s2.diskUsed), gb(s2.diskTotal)) else tr(R.string.w_pct, disk.toInt()), 85.0, 90.0, wide)
                if (wide && days != null && w.size.height > 120.dp) { Spacer(GlanceModifier.height(4.dp)); T(trn(R.plurals.disk_full_about, days.toInt().coerceAtLeast(1)), w, 12, if (days <= 14) w.warn else w.muted) }
            }
        }
    }
}

class ServerReceiver : HelmReceiver() {
    override val glanceAppWidget = ServerWidget()
}
