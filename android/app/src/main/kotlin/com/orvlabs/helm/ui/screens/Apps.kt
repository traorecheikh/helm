package com.orvlabs.helm.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.LogLine
import com.orvlabs.helm.data.Project
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Snapshot
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.ml.GroupedLogs
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.*
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AppsScreen(nav: NavController) {
    val st = Repo.state.collectAsStateWithLifecycle().value
    val snap = st.snap
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var logsId by remember { mutableStateOf<String?>(null) }
    val all = snap?.projects.orEmpty()
    val svcs = all.flatMap { it.services }
    val failing = svcs.count { it.status == "error" }
    val shown = when (tab) {
        1 -> all.filter { p -> p.services.any { it.status == "error" } }
        2 -> all.filter { p -> p.services.any { it.isDb } }
        else -> all
    }
    val lastBy = remember(snap) { snap?.deployments.orEmpty().groupBy { it.serviceId }.mapValues { (_, l) -> l.maxOf { parseTime(it.createdAt) ?: 0 } } }

    Screen {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            H1(tr(R.string.apps), Modifier.weight(1f))
            if (snap?.caps?.dokploy == true && snap.caps.coolify) PlatformSwitch(st.platform) { scope.launch { Repo.choose(it) } }
            else PlatformLogo(st.platform, 28, platformName(st.platform))
        }
        if (snap == null) return@Screen Txt(tr(R.string.loading), Modifier.padding(top = 18.dp), color = H.c.muted)
        Card(Modifier.padding(top = 14.dp), pad = 20) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.Bottom) {
                DotNum("${svcs.count { it.status == "done" }}", 80, color = if (failing > 0) H.c.ink else H.c.ok)
                Txt("/ ${svcs.size}", Modifier.weight(1f).padding(bottom = 6.dp), size = 20, weight = 600, color = H.c.muted)
                if (failing > 0) Pill(tr(R.string.apps_failed, failing), Tone.Signal) else Pill(tr(R.string.status_running), Tone.Ok)
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp).height(10.dp), Arrangement.spacedBy(3.dp)) {
                svcs.sortedBy { when (it.status) { "error" -> 0; "running" -> 1; "done" -> 2; else -> 3 } }.forEach { s ->
                    val t = svcTone(s.status)
                    Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (t == Tone.Ring) H.c.off else toneColor(t)))
                }
            }
        }
        Box(Modifier.padding(top = 16.dp)) { Tabs(listOf(tr(R.string.apps_all), tr(R.string.apps_issues), tr(R.string.apps_data)), tab, { tab = it }) }
        if (shown.isEmpty()) EmptyHint(Ph.Apps, tr(R.string.no_services))
        shown.forEach { p -> ProjectCard(nav, p, lastBy, startOpen = tab != 0 || p.services.any { it.status == "error" }) { logsId = p.projectId } }
    }
    logsId?.let { ProjectLogs(snap, it) { logsId = null } }
}

@Composable
private fun ProjectCard(nav: NavController, p: Project, lastBy: Map<String, Long>, startOpen: Boolean, onLogs: () -> Unit) {
    var open by remember(p.projectId, startOpen) { mutableStateOf(startOpen) }
    val reduce = LocalReduceMotion.current
    val turn by animateFloatAsState(if (open) 90f else 0f, if (reduce) snap() else tween(160), label = "chevron")
    val svcs = p.services
    Card(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).press({ open = !open }, p.name),
            Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(p.name, size = 18, weight = 700, tracking = -0.02f, maxLines = 2)
                Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(5.dp), Alignment.CenterVertically) {
                    svcs.take(12).forEach { Mk(svcTone(it.status)) }
                    if (svcs.size > 12) RightText("+${svcs.size - 12}")
                }
            }
            RightText("${svcs.size}", size = 14)
            Ico(Ph.Next, Modifier.rotate(turn), size = 16, tint = H.c.muted)
        }
        if (!open) return@Card
        Box(Modifier.height(8.dp))
        svcs.forEach { s ->
            Li(
                s.name, rule = true, titleSize = 15,
                left = { Lead { Mk(svcTone(s.status)) } },
                right = {
                    if (s.isDb) Ico(Ph.Database, size = 16, tint = H.c.faint)
                    RightText(lastBy[s.id]?.takeIf { it > 0 }?.let { ago(it) }.orEmpty())
                },
                onClick = { nav.navigate(ServiceR(s.kind, s.id)) },
            )
        }
        if (svcs.isEmpty()) Txt(tr(R.string.no_services), Modifier.padding(top = 8.dp), size = 14, color = H.c.muted)
        else Li(tr(R.string.logs), left = { Lead { Ico(Ph.Scroll, size = 18, tint = H.c.muted) } }, onClick = onLogs)
    }
}

@Composable
private fun Lead(content: @Composable () -> Unit) = Box(Modifier.size(18.dp), Alignment.Center) { content() }

@Composable
private fun ProjectLogs(snap: Snapshot?, id: String, onDismiss: () -> Unit) {
    val p = snap?.projects?.firstOrNull { it.projectId == id }
    val sources = p?.services.orEmpty().mapNotNull { s ->
        snap?.containersOf(s)?.firstOrNull { it.state == "running" }?.let { s to it.name }
    }.take(4).mapIndexed { i, (s, c) -> Triple(s.name.take(7) + if (i > 0) "${i + 1}" else "", s.name, c) }
    val c = H.c
    val tones = listOf(c.ink, c.muted, c.warn, c.ok)
    val colors = sources.mapIndexed { i, s -> s.first to tones[i] }.toMap()
    val lines = remember(id) { mutableStateListOf<LogLine>() }
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var filter by remember { mutableIntStateOf(0) }
    var grouped by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    sources.forEach { (tag, _, container) ->
        LaunchedEffect(container) {
            try {
                Agent.containerLogs(container, 50).collect { l ->
                    lines.add(l.copy(src = tag))
                    if (lines.size > 1 && l.t < lines[lines.size - 2].t) lines.sortBy { it.t }
                    if (lines.size > 1500) lines.removeRange(0, 300)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                err = e.message
            }
        }
    }
    val shown = (if (paused) frozen else lines.toList()).let { l -> if (filter == 0) l else l.filter { it.src == sources.getOrNull(filter - 1)?.first } }

    SheetFrame(true, onDismiss) {
        H2(p?.name ?: tr(R.string.all_logs))
        if (sources.isEmpty()) return@SheetFrame EmptyHint(Ph.Scroll, tr(R.string.svc_no_container))
        if (sources.size > 1) Box(Modifier.padding(top = 12.dp)) { Chips(listOf(tr(R.string.all_2)) + sources.map { it.first }, filter, { filter = it }) }
        err?.let { ErrorLine(it) }
        if (grouped) GroupedLogs(shown, Modifier.height(320.dp), colors) else LogBox(shown, Modifier.padding(top = 12.dp).height(320.dp), follow = !paused, tags = colors)
        Row(Modifier.padding(top = 12.dp), Arrangement.spacedBy(8.dp)) {
            Btn(if (paused) tr(R.string.resume) else tr(R.string.pause), { if (!paused) frozen = lines.toList(); paused = !paused }, Modifier.weight(1f))
            Btn(tr(R.string.ml_grouped), { grouped = !grouped }, Modifier.weight(1f), if (grouped) BtnStyle.Signal else BtnStyle.Plain)
        }
    }
}
