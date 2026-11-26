package com.orvlabs.helm.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.*
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.ServiceR
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H

class MemRow(val ct: AgentContainer, val svc: Svc?, val label: String)

class MemGroup(val key: String, val label: String, val rows: List<MemRow>) {
    val bytes = rows.sumOf { it.ct.memBytes }
}

private class Hit(val key: String, val label: String, val svc: Svc?, val row: String)

fun memGroups(snap: Snapshot?, cts: List<AgentContainer>): List<MemGroup> {
    val svcs = snap?.projects.orEmpty().flatMap { p -> p.services.map { p to it } }
    val groups = LinkedHashMap<String, MutableList<MemRow>>()
    val labels = HashMap<String, String>()
    cts.forEach { ct ->
        val base = ct.name.substringBefore('.')
        val found = svcs.firstOrNull { (_, s) -> s.appName?.let { a -> ct.service == a || ct.name == a || ct.name.startsWith(a) } == true }
        val hit = when {
            found != null -> Hit(found.first.projectId, found.first.name, found.second, found.second.name)
            base.startsWith("dokploy") || ct.service?.startsWith("dokploy") == true -> Hit("_dokploy", tr(R.string.mem_dokploy), null, base)
            else -> Hit("_none", tr(R.string.mem_no_project), null, ct.service ?: base)
        }
        labels[hit.key] = hit.label
        groups.getOrPut(hit.key) { mutableListOf() } += MemRow(ct, hit.svc, hit.row)
    }
    return groups.map { (k, rows) -> MemGroup(k, labels.getValue(k), rows.sortedByDescending { it.ct.memBytes }) }.sortedByDescending { it.bytes }
}

fun AgentContainer.details(): String = listOfNotNull(
    tr(R.string.mem_cpu, pct(cpuPercent, 1)),
    memLimitBytes?.takeIf { it > 0 }?.let { tr(R.string.mem_limit, size(it)) } ?: tr(R.string.no_limit),
    if (restarts > 0) trn(R.plurals.restarts_n, restarts) else null,
).joinToString(" · ")

private fun AgentContainer.problem() = state != "running" || health == "unhealthy"

private fun AgentContainer.stateWord() = tr(
    when (state) {
        "running" -> if (health == "unhealthy") R.string.ct_unhealthy else R.string.ct_running
        "exited" -> R.string.ct_exited
        "restarting" -> R.string.ct_restarting
        "paused" -> R.string.ct_paused
        "created" -> R.string.ct_created
        else -> R.string.ct_dead
    },
)

@Composable
internal fun ContainersTab(nav: NavController, host: Load<HostInfo>) {
    val snap = Repo.state.collectAsStateWithLifecycle().value.snap
    val load = rememberLoad { Agent.containers() }
    EveryWhileVisible(10_000) { load.reload() }
    var filter by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var openGroups by remember { mutableStateOf(setOf<String>()) }
    var openHist by remember { mutableStateOf<String?>(null) }
    var allSheet by remember { mutableStateOf(false) }
    val all = load.value.orEmpty()
    val q = query.trim()
    val narrowed = filter == 1 || q.isNotEmpty()
    val names = remember(snap, all) { memGroups(snap, all).flatMap { g -> g.rows.map { it.ct.name to (it.label to g.label) } }.toMap() }
    val shown = all.filter { filter == 0 || it.problem() }.filter { ct ->
        q.isEmpty() || ct.name.contains(q, true) || names[ct.name]?.let { (n, p) -> n.contains(q, true) || p.contains(q, true) } == true
    }
    val groups = remember(snap, shown) { memGroups(snap, shown) }
    val total = snap?.health?.resources?.memTotalBytes ?: 0
    val used = snap?.health?.resources?.memUsedBytes ?: 0
    val usedPct = if (total > 0) used * 100.0 / total else 0.0
    val system = (used - memGroups(snap, all).sumOf { it.bytes }).coerceAtLeast(0)
    val c = H.c
    fun share(b: Long) = b.toFloat() / total.coerceAtLeast(1)

    Chips(listOf(tr(R.string.ct_all), tr(R.string.problems)), filter, { filter = it }, mapOf(1 to all.count { it.problem() }))
    Field(tr(R.string.ct_search), query, { query = it }, Modifier.padding(top = 4.dp), mono = false)
    Failed(load)
    if (load.value == null && load.error == null) repeat(3) { Skeleton(Modifier.padding(top = 12.dp).height(92.dp), RoundedCornerShape(24.dp)) }
    if (load.value != null && groups.isEmpty()) Empty(tr(if (all.isEmpty()) R.string.no_containers_here else if (q.isNotEmpty()) R.string.ct_no_match else R.string.ct_no_problems))

    val limit = 6
    val top = if (narrowed) groups else groups.take(limit)
    val card = @Composable { i: Int, g: MemGroup ->
        GroupCard(
            g, narrowed || g.key in openGroups, share(g.bytes), i == 0 && usedPct > 85, openHist,
            { openGroups = if (g.key in openGroups) openGroups - g.key else openGroups + g.key },
            { n -> openHist = if (openHist == n) null else n }, nav,
        )
    }
    top.forEachIndexed { i, g -> card(i, g) }
    if (groups.size > top.size) Btn(tr(R.string.srv_all, groups.size), { allSheet = true }, Modifier.padding(top = 12.dp))

    if (!narrowed && groups.isNotEmpty() && total > 0) {
        Card(Modifier.padding(top = 12.dp).appear(), pad = 18) {
            Stat(tr(R.string.mem_free_system), size(system))
            Stat(tr(R.string.mem_free), size((total - used).coerceAtLeast(0)))
        }
    }

    Detail(allSheet, tr(R.string.containers), { allSheet = false }) {
        groups.forEachIndexed { i, g -> card(i, g) }
        val outside = host.value?.topProcesses.orEmpty().filter { p -> all.none { taskName(it.name) == p.name || it.service == p.name || it.name == p.name } }
        if (outside.isNotEmpty()) K(tr(R.string.mem_host), Modifier.padding(top = 16.dp))
        outside.take(5).forEach { p -> SrvRow(p.name, mono = true, titleSize = 14, sub = tr(R.string.mem_cpu, pct(p.cpuPercent, 1)), right = { RightText(size(p.memBytes), c.ink, 13) }) }
    }
}

@Composable
private fun GroupCard(
    g: MemGroup, expanded: Boolean, share: Float, hot: Boolean, openHist: String?,
    onToggle: () -> Unit, onHist: (String) -> Unit, nav: NavController,
) {
    val c = H.c
    val bad = g.rows.count { it.ct.problem() }
    val frac by animateFloatAsState(share, if (LocalReduceMotion.current) snap() else spring(0.7f, 120f), label = "share")
    Card(Modifier.padding(top = 12.dp).appear(), onToggle, pad = 18) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Txt(g.label, size = 17, weight = 700, tracking = -0.02f, maxLines = 2)
                Sub(trn(R.plurals.containers_n, g.rows.size))
            }
            if (bad > 0) Pill("$bad", Tone.Signal)
            Txt(size(g.bytes), size = 16, weight = 700)
            Ico(Ph.Next, Modifier.rotate(if (expanded) 90f else 0f), size = 16, tint = c.muted)
        }
        Meter(frac, Modifier.padding(top = 14.dp), if (hot) c.warn else c.ok)
        if (expanded) g.rows.forEach { r ->
            val isBad = r.ct.problem()
            SrvRow(
                r.label, Modifier.padding(top = 4.dp), titleSize = 14, subColor = if (isBad) c.bad else c.muted,
                sub = if (isBad) listOf(r.ct.stateWord(), r.ct.details()).joinToString(" · ") else tr(R.string.mem_cpu, pct(r.ct.cpuPercent, 1)),
                left = { Mk(if (isBad) Tone.Signal else Tone.Ok) },
                right = { RightText(size(r.ct.memBytes), c.ink, 13) },
                onClick = { onHist(r.ct.name) },
            )
            if (openHist == r.ct.name) {
                HistoryBars(r.ct.name)
                r.svc?.let { s -> Btn(tr(R.string.open), { nav.navigate(ServiceR(s.kind, s.id)) }, Modifier.padding(bottom = 8.dp), icon = Ph.Out) }
            }
        }
    }
}

@Composable
private fun HistoryBars(name: String) {
    val h = rememberLoad(name) { Ops.history(name, 24) }
    val pts = h.value.orEmpty()
    if (pts.isEmpty()) return
    val max = pts.maxOf { it.memBytes }.coerceAtLeast(1)
    Sub(tr(R.string.h_history_24h, size(max)), Modifier.padding(top = 4.dp), size = 13)
    MemBars(pts.takeLast(24).map { it.memBytes * 100.0 / max }, Modifier.padding(bottom = 8.dp))
}
