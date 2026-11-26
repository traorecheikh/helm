package com.orvlabs.helm.cover

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.LockSimple
import com.adamglin.phosphoricons.fill.ShieldCheck
import com.orvlabs.helm.R
import com.orvlabs.helm.countryName
import com.orvlabs.helm.data.Geo
import com.orvlabs.helm.data.GeoCountry
import com.orvlabs.helm.data.GeoRecent
import com.orvlabs.helm.data.Panic
import com.orvlabs.helm.data.World
import com.orvlabs.helm.data.heatLevels
import com.orvlabs.helm.data.individuallyBlocked
import com.orvlabs.helm.num
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Feel
import com.orvlabs.helm.ui.components.Haptics
import com.orvlabs.helm.ui.components.HoldButton
import com.orvlabs.helm.ui.components.Ripple
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.components.ago
import com.orvlabs.helm.ui.components.clock
import com.orvlabs.helm.ui.components.flag
import com.orvlabs.helm.ui.components.press
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

@Composable
fun ShieldFace(geo: Geo?, panic: Panic?, world: World, ripples: SnapshotStateList<Ripple>, onUnban: (String) -> Unit, onLockdown: (Boolean) -> Unit) {
    val locked = panic?.active == true
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val ip = picked
    val hint = when {
        ip != null -> tr(R.string.cover_unban_hint, ip)
        panic == null -> null
        locked -> panic.until?.let { tr(R.string.w_lockdown_until, clock(it * 1000)) } ?: tr(R.string.panic_until_end)
        else -> null
    }
    Face(
        PhosphorIcons.Fill.ShieldCheck, tr(R.string.shield), if (locked) H.c.bad else H.c.ok, 0.54f,
        left = {
            Column(Modifier.fillMaxWidth()) {
                ShieldMap(world, geo, geo?.yourCountry ?: "SN", locked, ripples, Modifier)
                hint?.let { Txt(it, Modifier.padding(top = 10.dp), size = 13, color = H.c.muted, lineHeight = 1.3f) }
            }
        },
    ) {
        Attacker(geo?.countries?.filter { it.attempts24h > 0 }?.maxByOrNull { it.attempts24h })
        Column(Modifier.padding(top = 6.dp)) {
            geo?.recent.orEmpty().take(2).forEach { BanRow(it, it.ip == picked) { picked = if (picked == it.ip) null else it.ip; Haptics.fire(Feel.Tick) } }
        }
        Spacer(Modifier.weight(1f))
        when {
            ip != null -> HoldButton(tr(R.string.unban_3), 1200) { picked = null; onUnban(ip) }
            panic == null -> Unit
            locked -> HoldButton(tr(R.string.end_now), 3000) { onLockdown(false) }
            else -> HoldButton(tr(R.string.lock_down), 3000) { onLockdown(true) }
        }
    }
}

@Composable
private fun Attacker(top: GeoCountry?) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).semantics(mergeDescendants = true) {}, Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        if (top == null) {
            Txt(tr(R.string.w_map_quiet), size = 15, weight = 500, lineHeight = 1.3f)
            return@Row
        }
        Txt(flag(top.code), size = 24)
        Column {
            Txt(countryName(top.code, top.name), size = 15, weight = 600, lineHeight = 1.15f)
            Txt(tr(R.string.cover_tries, num(top.attempts24h)), size = 12, color = H.c.muted)
        }
    }
}

@Composable
private fun BanRow(r: GeoRecent, picked: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(10.dp))
            .background(if (picked) H.c.signalSoft else Color.Transparent)
            .press(onPick, tr(R.string.unban_2, r.ip)).padding(horizontal = 4.dp),
        Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
    ) {
        Txt(r.country?.let { flag(it) }.orEmpty(), size = 14)
        Txt(r.ip, Modifier.weight(1f), size = 12, mono = true, maxLines = 1)
        Txt(ago(r.at * 1000), size = 12, color = H.c.muted, maxLines = 1)
    }
}

@Composable
private fun ShieldMap(world: World, geo: Geo?, home: String, locked: Boolean, ripples: SnapshotStateList<Ripple>, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val life = if (reduce) 4000L else 1600L
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(ripples.size) {
        while (ripples.isNotEmpty()) {
            withFrameMillis { now = SystemClock.uptimeMillis() }
            ripples.removeAll { now - it.start > life }
            if (reduce) delay(250)
        }
    }
    val c = H.c
    val heat = listOf(c.off, c.warn.copy(alpha = 0.5f), c.warn, c.bad)
    val levels = remember(geo) { geo?.let { heatLevels(it) }.orEmpty() }
    val blocked = remember(geo) { geo?.let { individuallyBlocked(it) }.orEmpty() }
    val desc = tr(R.string.w_map_desc)
    Canvas(
        modifier.fillMaxWidth().aspectRatio(world.cols.toFloat() / world.rows).semantics { contentDescription = desc },
    ) {
        val step = size.width / world.cols
        val hot = ripples.associate { it.code to ((now - it.start) / life.toFloat()).coerceIn(0f, 1f) }
        for (i in world.cells.indices) {
            val ci = world.cells[i]
            if (ci < 0) continue
            val code = world.countries[ci].code
            val o = Offset((i % world.cols) * step + step / 2, (i / world.cols) * step + step / 2)
            val lvl = levels[code] ?: 0
            if (code in blocked) drawCircle(c.muted.copy(alpha = 0.6f), step * 0.3f, o, style = Stroke(0.7.dp.toPx()))
            else drawCircle(heat[lvl], step * (0.3f + 0.04f * lvl), o)
            hot[code]?.let { t -> drawCircle(c.ink.copy(alpha = if (reduce) 0.9f else 1f - t), step * 0.42f, o) }
        }
        world.country(home)?.let {
            val o = Offset(world.cellX(it.x).toFloat() * step, world.cellY(it.y).toFloat() * step)
            drawCircle(if (locked) c.bad else c.ok, step * 2.6f, o, style = Stroke(1.5.dp.toPx()))
        }
        ripples.forEach { rp ->
            val wc = world.country(rp.code) ?: return@forEach
            val t = ((now - rp.start) / life.toFloat()).coerceIn(0f, 1f)
            val o = Offset(world.cellX(wc.x).toFloat() * step, world.cellY(wc.y).toFloat() * step)
            if (reduce) { drawCircle(c.bad, step * 3f, o, style = Stroke(1.5.dp.toPx())); return@forEach }
            drawCircle(Brush.radialGradient(listOf(c.bad.copy(alpha = 0.3f * (1 - t)), Color.Transparent), o, step * 6f), step * 6f, o)
            drawCircle(c.bad.copy(alpha = 1 - t), step * (2f + 12f * t), o, style = Stroke(1.6.dp.toPx()))
            drawCircle(c.warn.copy(alpha = (1 - t) * 0.7f), step * (1f + 7f * t), o, style = Stroke(1.dp.toPx()))
        }
    }
}

@Preview(widthDp = 512, heightDp = 260, showBackground = true, backgroundColor = 0xFF0D0B12)
@Composable
private fun ShieldPreview() = CoverPreview {
    val world = World.load(LocalContext.current)
    val geo = Geo(
        countries = listOf(GeoCountry("CN", "China", "Asia", bans = 40, attempts24h = 1284), GeoCountry("US", "United States", "North America", bans = 12, attempts24h = 310)),
        recent = listOf(GeoRecent("203.0.113.4", "CN", "ssh", 1_763_500_000), GeoRecent("198.51.100.7", "US", "web", 1_763_499_000)),
        totalBans = 412, yourCountry = "SN",
    )
    ShieldFace(geo, Panic(), world, remember { SnapshotStateList<Ripple>().apply { add(Ripple("CN")) } }, {}, {})
}
