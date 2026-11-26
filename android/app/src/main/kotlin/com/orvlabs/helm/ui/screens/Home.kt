package com.orvlabs.helm.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Config
import com.orvlabs.helm.data.Link
import com.orvlabs.helm.data.Live
import com.orvlabs.helm.data.Problem
import com.orvlabs.helm.data.PulseState
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Snapshot
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.isFailure
import com.orvlabs.helm.data.quietNow
import com.orvlabs.helm.ml.Anomaly
import com.orvlabs.helm.ml.AnomalyWatch
import com.orvlabs.helm.ml.chip
import com.orvlabs.helm.ml.problem
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.DeployR
import com.orvlabs.helm.ui.ServerR
import com.orvlabs.helm.ui.SettingsR
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.goTab
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: NavController) {
    val s by Repo.state.collectAsStateWithLifecycle()
    val cfg by Store.config.collectAsStateWithLifecycle()
    EveryWhileVisible(15_000) { Repo.refresh() }
    var ripple by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Live.events.collect { ripple++; if (!it.isFailure()) Haptics.fire(Feel.Arrival) } }
    var alerts by remember { mutableStateOf(false) }
    var servers by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snap = s.snap
    LaunchedEffect(Unit) { AnomalyWatch.start() }
    val anomalies by AnomalyWatch.found.collectAsStateWithLifecycle()
    val problems = snap?.let { Repo.problems(it, s.stats) + anomalies.map { a -> a.problem() } }.orEmpty()

    Screen {
        TopBar(cfg?.host.orEmpty(), s.link, problems.isNotEmpty(), { servers = true }, { alerts = true }, { nav.navigate(SettingsR) })
        if (s.pending > 0) {
            Banner(trn(R.plurals.home_phones, s.pending), Ph.Phone, Modifier.padding(top = 8.dp).press({ nav.navigate(SettingsR) }))
        }
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.spacedBy(12.dp)) {
            when {
                snap == null && s.loading -> Card(Modifier.height(190.dp), color = H.c.raise) {}
                snap == null -> Failure(s.link == Link.Auth, s.error.orEmpty(), nav)
                else -> Content(s, snap, problems, anomalies, cfg, nav, ripple)
            }
        }
        ServersSheet(servers, cfg, { servers = false }, { servers = false; adding = true }) { id -> servers = false; scope.launch { switchServer(id) } }
        AlertsSheet(alerts, nav) { alerts = false }
        if (adding) AddServerSheet { adding = false }
    }
}

@Composable
private fun TopBar(host: String, link: Link, badge: Boolean, onHost: () -> Unit, onBell: () -> Unit, onGear: () -> Unit) {
    val c = H.c
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Box(Modifier.weight(1f, false).padding(end = 8.dp).heightIn(min = 48.dp).press(onHost, tr(R.string.switch_server)), Alignment.CenterStart) {
            Row(
                Modifier.clip(CircleShape).background(c.card, CircleShape).border(1.dp, c.line, CircleShape).padding(horizontal = 14.dp, vertical = 10.dp),
                Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
            ) {
                LiveDot(when (link) { Link.Live -> c.ok; Link.Offline -> c.warn; else -> c.bad }, pulse = false)
                Txt(host, Modifier.weight(1f, false), size = 13, weight = 600, maxLines = 1)
                Ico(Ph.Next, Modifier.rotate(90f), size = 12, tint = c.muted)
            }
        }
        Row {
            RoundIcon(Ph.Bell, tr(R.string.alerts), badge, onBell)
            RoundIcon(Ph.Gear, tr(R.string.settings), false, onGear)
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, badge: Boolean, onClick: () -> Unit) {
    val c = H.c
    Box(Modifier.size(48.dp).press(onClick, label), Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.card, CircleShape).border(1.dp, c.line, CircleShape), Alignment.Center) { Ico(icon, size = 18, desc = label) }
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(11.dp).background(c.bad, CircleShape).border(2.dp, c.bg, CircleShape))
    }
}

@Composable
private fun LiveDot(color: Color, pulse: Boolean = true) {
    val a = if (LocalReduceMotion.current || !pulse) 1f
    else rememberInfiniteTransition(label = "dot").animateFloat(1f, 0.3f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value
    Box(Modifier.size(10.dp).graphicsLayer { alpha = a }.background(color, CircleShape))
}

@Composable
private fun Failure(auth: Boolean, error: String, nav: NavController) {
    val c = H.c
    Card(Modifier.appear(), color = c.badSoft, pad = 20) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            LiveDot(c.bad)
            Txt(tr(if (auth) R.string.helm_lost_access else R.string.home_down), size = 18, weight = 700, color = c.bad)
        }
        if (error.isNotEmpty()) ErrorLine(error, Modifier.padding(top = 10.dp))
        Btn(tr(R.string.settings), { nav.navigate(SettingsR) }, Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Content(s: PulseState, snap: Snapshot, problems: List<Problem>, anomalies: List<Anomaly>, cfg: Config?, nav: NavController, ripple: Int) {
    val down = s.link == Link.Unreachable
    val dim = s.link == Link.Offline
    if (dim) Banner(tr(R.string.home_offline, clock(snap.at)), Ph.Offline)
    Hero(s, snap, cfg, ripple, nav, anomalies, problems.isNotEmpty())
    if (!down) problems.forEach { ProblemCard(it, nav) }
    Meters(snap, s, Modifier.alpha(if (down || dim) 0.45f else 1f)) { nav.goTab(ServerR) }
    if (!down) HomeVulns()
    if (!down) HomeMail()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(s: PulseState, snap: Snapshot, cfg: Config?, ripple: Int, nav: NavController, anomalies: List<Anomaly>, watch: Boolean) {
    val down = s.link == Link.Unreachable
    val dim = s.link == Link.Offline
    val services = snap.projects.flatMap { it.services }
    val building = services.filter { it.status == "running" }
    val failing = services.filter { it.status == "error" }
    val buildingApps = building.mapNotNull { it.appName }
    val running = snap.containers.count { it.state == "running" }
    val dots = snap.containers.map { ct ->
        when {
            ct.state in setOf("restarting", "dead", "paused") -> Dot.Signal
            ct.state == "running" || buildingApps.any { ct.name.startsWith(it) } -> Dot.Ink
            else -> Dot.Off
        }
    }
    val (cols, dot, gap) = if (dots.size <= 35) Triple(7, 11, 6) else if (dots.size <= 70) Triple(9, 8, 4) else Triple(12, 6, 3)
    val tone = if (down || failing.isNotEmpty()) Tone.Signal else if (watch) Tone.Warn else Tone.Ok
    val fg = toneColor(tone)
    val word = when {
        down -> tr(R.string.home_down)
        failing.size > 1 -> tr(R.string.failing_more, failing.first().name, failing.size - 1)
        failing.size == 1 -> tr(R.string.failing_one, failing.first().name)
        watch -> tr(R.string.home_watch)
        else -> tr(R.string.home_ok)
    }
    val mins = s.failingSince?.let { (System.currentTimeMillis() - it) / 60_000 } ?: 0
    val up = s.uptime?.takeIf { it.services.isNotEmpty() }?.let { u ->
        u.services.count { it.current != "up" } to "%.2f".format(u.services.mapNotNull { it.uptimePercent }.average())
    }
    val quiet = cfg?.takeIf { it.quietOn && quietNow(it.quietStart, it.quietEnd) }
    val odd = anomalies.firstOrNull()?.takeIf { !down }
    val hasPills = down || building.isNotEmpty() || up != null || quiet != null || odd != null

    Card(Modifier.appear(), onClick = if (down) null else ({ nav.goTab(ServerR) }), color = toneSoft(tone), pad = 20) {
        Column(Modifier.alpha(if (dim) 0.45f else 1f)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                Txt(word, Modifier.weight(1f, false), size = 18, weight = 700, color = fg, maxLines = 2)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), Arrangement.SpaceBetween, Alignment.Bottom) {
                Box(Modifier.alpha(if (down) 0.45f else 1f), Alignment.Center) {
                    if (!down) PulseRipple(ripple, Modifier.matchParentSize())
                    DotNum(if (snap.caps.docker) "$running" else "${(s.stats?.cpuPercent ?: 0.0).toInt()}%", if (running > 99) 100 else 130, color = fg)
                }
                if (dots.isNotEmpty()) DotGrid(dots, cols, size = dot, gap = gap)
            }
            if (hasPills) FlowRow(Modifier.padding(top = 14.dp), Arrangement.spacedBy(8.dp), Arrangement.spacedBy(8.dp)) {
                if (down) {
                    HeroPill(if (mins < 1) tr(R.string.under_a_minute) else tr(R.string.time_min, mins), c = H.c.bad)
                    HeroPill(tr(R.string.last_known_count, clock(snap.at)))
                }
                building.firstOrNull()?.let { HeroPill(tr(R.string.home_building, it.name), c = H.c.warn) }
                odd?.let { HeroPill(it.chip(), c = H.c.warn) }
                up?.let { (n, pct) -> HeroPill(if (n == 0) tr(R.string.home_up, pct) else tr(R.string.home_up_down, n, pct), c = if (n == 0) H.c.ok else H.c.bad) }
                quiet?.let { HeroPill(tr(R.string.home_quiet, "%02d:%02d".format(it.quietEnd / 60, it.quietEnd % 60))) }
            }
        }
        if (down) {
            val scope = rememberCoroutineScope()
            Btn(tr(R.string.try_again), { scope.launch { Repo.refresh() } }, Modifier.padding(top = 18.dp), BtnStyle.Ink, icon = Ph.Refresh)
        }
    }
}

@Composable
private fun HeroPill(text: String, c: Color = H.c.muted) {
    Box(Modifier.clip(CircleShape).background(H.c.card, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Txt(text, size = 12, weight = 600, color = c, maxLines = if (text.length <= 10) 1 else 2)
    }
}

@Composable
private fun ProblemCard(p: Problem, nav: NavController) {
    val tone = if (p.severe) Tone.Signal else Tone.Warn
    Card(
        Modifier.appear(), pad = 14,
        onClick = {
            if (p.target == "deploy") nav.navigate(DeployR(deploymentId = p.deploymentId)) else nav.goTab(ServerR)
        },
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(toneSoft(tone)), Alignment.Center) { Ico(Ph.Warning, size = 20, tint = toneColor(tone)) }
            Column(Modifier.weight(1f)) {
                Txt(p.title, size = 15, weight = 600)
                Sub(p.sub)
            }
            if (p.at != null) RightText(ago(p.at)) else Ico(Ph.Next, size = 16, tint = H.c.muted)
        }
    }
}

@Composable
private fun Meters(snap: Snapshot, s: PulseState, modifier: Modifier, onClick: () -> Unit) {
    val disk = snap.health.disk.run { if (totalBytes > 0) usedBytes * 100.0 / totalBytes else 0.0 }
    Card(modifier.appear(), onClick = onClick) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
            MeterTile(tr(R.string.cpu_2), s.stats?.cpuPercent)
            MeterTile(tr(R.string.home_ram), snap.memPercent)
            MeterTile(tr(R.string.disk), disk)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MeterTile(label: String, percent: Double?) {
    val c = H.c
    val p = percent ?: 0.0
    val color = if (p > 95) c.bad else if (p > 85) c.warn else c.ok
    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$label ${p.toInt()}%" }) {
        K(label)
        Txt(if (percent == null) "–" else "${p.toInt()}%", Modifier.padding(top = 2.dp), size = 22, weight = 700, color = if (p > 85) color else c.ink)
        Meter((p / 100).toFloat(), Modifier.padding(top = 8.dp), color)
    }
}
