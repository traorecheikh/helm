package com.orvlabs.helm.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H

@Composable
internal fun Detail(visible: Boolean, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) =
    SheetFrame(visible, onDismiss) {
        H2(title)
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).imePadding().padding(top = 12.dp)) { content() }
    }

@Composable
internal fun SrvRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    left: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    mono: Boolean = false,
    titleSize: Int = 15,
    subColor: Color = H.c.muted,
    subLines: Int = 2,
) {
    Row(
        modifier.fillMaxWidth().appear().then(if (onClick != null) Modifier.press(onClick) else Modifier).heightIn(min = if (onClick != null) 52.dp else 36.dp).padding(vertical = 8.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        left?.invoke()
        Column(Modifier.weight(1f)) {
            Txt(title, size = titleSize, weight = 600, mono = mono, maxLines = 2)
            if (sub != null) Sub(sub, color = subColor, maxLines = subLines)
        }
        right?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerScreen(nav: NavController) {
    val s = Repo.state.collectAsStateWithLifecycle().value
    val host0 = Store.config.collectAsStateWithLifecycle().value?.host.orEmpty()
    val host = rememberLoad { Agent.host() }
    EveryWhileVisible(15_000) { host.reload() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var hostSheet by remember { mutableStateOf(false) }
    val snap = s.snap
    val stats = s.stats
    val h = host.value
    val c = H.c

    val cpu = stats?.cpuPercent
    val ram = snap?.memPercent
    val disk = snap?.health?.disk?.takeIf { it.totalBytes > 0 }?.let { it.usedBytes * 100.0 / it.totalBytes }
    val down = s.uptime?.services?.count { it.current == "down" } ?: 0
    val critical = down > 0 || (disk ?: 0.0) >= 90 || (ram ?: 0.0) >= 95
    val warn = (ram ?: 0.0) > 85 || (disk ?: 0.0) > 80 || (cpu ?: 0.0) > 90 || (h?.securityUpdates ?: 0) > 0 || h?.rebootRequired == true
    val tone = if (critical) Tone.Signal else if (warn) Tone.Warn else Tone.Ok
    val name = stats?.hostname?.ifEmpty { null } ?: host0
    val forecast = stats?.diskFullInDays?.takeIf { it < 30 }

    Screen {
        Card(Modifier.padding(top = 8.dp).appear(), { hostSheet = true }, if (critical) toneSoft(Tone.Signal) else c.card, pad = 20) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mk(tone)
                Txt(name, Modifier.weight(1f), size = 15, weight = 600, color = c.muted, maxLines = 2)
                stats?.uptimeSeconds?.takeIf { it > 0 }?.let { Pill(tr(R.string.srv_up, it / 86_400)) }
            }
            Row(Modifier.padding(top = 20.dp), Arrangement.spacedBy(8.dp)) {
                Ring(tr(R.string.cpu_2), cpu, 90.0, 98.0, 0, Modifier.weight(1f))
                Ring(tr(R.string.home_ram), ram, 85.0, 95.0, 1, Modifier.weight(1f))
                Ring(tr(R.string.disk), disk, 80.0, 90.0, 2, Modifier.weight(1f))
            }
            if (forecast != null || down > 0) FlowRow(Modifier.padding(top = 18.dp), Arrangement.spacedBy(8.dp), Arrangement.spacedBy(8.dp)) {
                if (down > 0) Pill(tr(R.string.down_2, "$down"), Tone.Signal)
                forecast?.let { Pill(tr(R.string.srv_full, it.toInt().coerceAtLeast(1)), if (it < 7) Tone.Signal else Tone.Warn) }
            }
        }
        if (h == null) host.error?.let { ErrorLine(it) }
        Box(Modifier.padding(top = 16.dp, bottom = 16.dp)) {
            Tabs(listOf(tr(R.string.h_title), tr(R.string.containers), tr(R.string.uptime)), tab) { tab = it }
        }
        when (tab) {
            0 -> HealthTab(host.value, snap)
            1 -> ContainersTab(nav, host)
            else -> UptimeTab(snap)
        }
        Box(Modifier.height(48.dp))
    }

    Detail(hostSheet, tr(R.string.server), { hostSheet = false }) { HostBody(host.value, stats?.cpuCount, cpu, snap, stats) }
}

@Composable
private fun Ring(label: String, percent: Double?, warnAt: Double, badAt: Double, index: Int, modifier: Modifier) {
    val c = H.c
    val reduce = LocalReduceMotion.current
    val target = ((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (reduce) sweep.snapTo(target)
        else {
            if (sweep.value == 0f) delay(index * 90L)
            sweep.animateTo(target, spring(dampingRatio = 0.65f, stiffness = 90f))
        }
    }
    val p = percent ?: 0.0
    val color = if (p >= badAt) c.bad else if (p > warnAt) c.warn else c.ok
    val track = c.ink.copy(alpha = 0.08f)
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label ${p.toInt()}%" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 9.dp.toPx()
                val d = size.minDimension - sw
                val tl = Offset(sw / 2, sw / 2)
                drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
                val sweepDeg = 360f * sweep.value.coerceIn(0f, 1f)
                if (percent != null && sweepDeg > 1f) drawArc(color, -90f, sweepDeg, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
            }
            if (percent == null) Txt("–", size = 22, weight = 700, color = c.faint)
            else Row(verticalAlignment = Alignment.Bottom) {
                DotNum("${percent.toInt().coerceIn(0, 100)}", 40, color = if (p > warnAt) color else c.ink)
                Txt("%", Modifier.padding(start = 1.dp, bottom = 2.dp), size = 11, weight = 600, color = c.muted)
            }
        }
        Txt(label, Modifier.padding(top = 8.dp), size = 13, weight = 600, color = c.muted, align = TextAlign.Center)
    }
}

private fun rate(bytesPerSec: Double) = size(bytesPerSec.toLong()) + "/s"

@Composable
private fun HostBody(
    h: com.orvlabs.helm.data.HostInfo?,
    cores: Int?,
    cpu: Double?,
    snap: com.orvlabs.helm.data.Snapshot?,
    stats: com.orvlabs.helm.data.Stats?,
) {
    val c = H.c
    val hourly = stats?.memHistory.orEmpty().chunked(4).map { ch -> ch.map { it.usedPercent }.average() }.takeLast(24)
    K(tr(R.string.memory_24_h))
    Box(Modifier.padding(top = 10.dp, bottom = 6.dp)) { MemBars(hourly) }
    if (cpu != null && cores != null) Stat(tr(R.string.cpu_2), tr(R.string.cores, cpu.toInt(), cores))
    h?.load?.takeIf { it.isNotEmpty() }?.let { Stat(tr(R.string.load), it.joinToString("  ") { v -> "%.2f".format(v) }) }
    snap?.health?.resources?.takeIf { it.memTotalBytes > 0 }?.let { Stat(tr(R.string.memory), tr(R.string.gb, gb(it.memUsedBytes), gb(it.memTotalBytes)), if (snap.memPercent > 85) c.warn else c.ink) }
    snap?.health?.disk?.takeIf { it.totalBytes > 0 }?.let { Stat(tr(R.string.disk), tr(R.string.gb, gb(it.usedBytes), gb(it.totalBytes))) }
    h?.let {
        Stat(tr(R.string.network), "↓ ${rate(it.netRxBytesPerSec)}   ↑ ${rate(it.netTxBytesPerSec)}")
        Stat(tr(R.string.failed_ssh_logins_24_h), "${it.failedSshLogins24h}")
        if (it.topProcesses.isNotEmpty()) K(tr(R.string.top_processes), Modifier.padding(top = 12.dp))
        it.topProcesses.take(3).forEach { p -> SrvRow(p.name, mono = true, titleSize = 13, right = { RightText("${p.cpuPercent.toInt()}% · ${size(p.memBytes)}", c.ink, 13) }) }
    }
}
