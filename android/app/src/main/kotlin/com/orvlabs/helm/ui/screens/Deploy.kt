package com.orvlabs.helm.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Deployment
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.data.Plat
import com.orvlabs.helm.data.dokployActive
import com.orvlabs.helm.data.LogLine
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.ml.Cause
import com.orvlabs.helm.ml.ExplainButton
import com.orvlabs.helm.ml.explainDeploy
import com.orvlabs.helm.ml.Fix
import com.orvlabs.helm.ml.rememberCause
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.*
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import com.orvlabs.helm.widget.IncidentCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val STEPS get() = listOf(tr(R.string.pull), tr(R.string.build), tr(R.string.start), tr(R.string.check))
private val KEYWORDS = listOf(
    listOf("clon", "pull", "fetch", "initializing", "checkout", "commit"),
    listOf("build", "step ", "run ", "install", "nixpacks", "buildpack", "compil", "docker"),
    listOf("deploying", "service", "stack", "container", "starting", "creating"),
    listOf("health", "completed", "success", "deployed", "done"),
)

fun deployStep(lines: List<LogLine>): Int {
    var p = 0
    for (l in lines) {
        val t = l.line.lowercase()
        for (i in KEYWORDS.indices) if (i == p + 1 && KEYWORDS[i].any { it in t }) p = i
    }
    return p
}

@Composable
fun DeployScreen(nav: NavController, r: DeployR) {
    val guard = rememberGuard()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap = Repo.state.collectAsStateWithLifecycle().value.snap
    var dep by remember { mutableStateOf<Deployment?>(null) }
    var missing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var diff by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    EveryWhileVisible(3000, r) {
        if (dep != null && dep?.status != "running") return@EveryWhileVisible
        try {
            fun t(d: Deployment) = parseTime(d.createdAt) ?: 0
            dep = when {
                r.deploymentId.isNotEmpty() -> Plat.deployment(r.deploymentId)
                r.id.isNotEmpty() -> Plat.deployments(r.kind, r.id).filter { t(it) >= r.after - 5000 }.maxByOrNull(::t)
                r.failed -> Plat.deployments().filter { it.status == "error" }.maxByOrNull(::t)
                else -> null
            }
            missing = dep == null && r.id.isEmpty()
            err = null
        } catch (e: ApiError) {
            err = e.message
        }
    }

    val d = dep
    var prevStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(d?.status) {
        val st = d?.status
        if (prevStatus == "running" && st == "error") Haptics.fire(Feel.Failure, key = d?.deploymentId)
        if (prevStatus == "running" && st == "done") Haptics.fire(Feel.Confirm)
        prevStatus = st
    }
    val svc = d?.let { snap?.service(it.serviceId) } ?: snap?.service(r.id)
    val name = svc?.name ?: d?.title.orEmpty()
    val stream = rememberLogStream(d?.logPath) { d?.logPath?.takeIf { it.isNotEmpty() }?.let { Agent.deploymentLogs(it) } }
    val logLines = d?.log ?: stream.lines
    val running = (d == null && !missing && r.id.isNotEmpty()) || d?.status == "running"
    val siblings = rememberLoad(d?.serviceId) { if (d == null || d.serviceId.isEmpty()) emptyList() else Plat.deployments(d.kind, d.serviceId) }
    val siblingDetail = rememberLoad(d?.serviceId) { if (d == null || d.serviceId.isEmpty()) null else Plat.one(d.kind, d.serviceId) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(1000) } }

    fun redeploy() = guard(tr(R.string.redeploy, name)) {
        scope.launch {
            try {
                val t = System.currentTimeMillis()
                Plat.act(d!!.kind, d.serviceId, "redeploy")
                nav.navigate(DeployR(kind = d.kind, id = d.serviceId, after = t)) { popUpTo<DeployR> { inclusive = true } }
            } catch (e: CancellationException) { throw e } catch (e: ApiError) { err = e.message }
        }
    }

    val stopKind = d?.kind ?: r.kind
    val stopId = d?.serviceId ?: r.id
    val status = if (running) "running" else d?.status
    val failed = status == "error"
    val cause = rememberCause(if (failed) logLines else null, d?.errorMessage)
    var fix by remember { mutableStateOf<Fix?>(null) }
    var lastFix by remember { mutableStateOf(Fix.Env) }
    val canFix = { f: Fix -> d != null && (f == Fix.Redeploy || f == Fix.Server || dokployActive) && (f != Fix.Resources || d.kind == "application") && (f == Fix.Server || d.serviceId.isNotEmpty()) }
    val back = if (running) tr(R.string.leave_it_keeps_running) else svc?.let { snap?.projectOf(it.id)?.name } ?: tr(R.string.back)

    Screen(back = back, onBack = { nav.popBackStack() }, dock = false, scroll = false) {
        if (d == null && !running) {
            if (err != null) ErrorLine(err.orEmpty()) else EmptyNote(tr(R.string.nothing_to_show), Modifier.padding(top = 40.dp))
            return@Screen
        }
        val step = if (status == "done") 3 else deployStep(logLines)
        val startMs = parseTime(d?.startedAt ?: d?.createdAt) ?: r.after.takeIf { it > 0 } ?: now.takeIf { running }
        val endMs = if (running) now else parseTime(d?.finishedAt)
        val secs = if (startMs != null && endMs != null) ((endMs - startMs) / 1000).coerceAtLeast(0) else null
        val errLine = d?.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: tr(R.string.the_deploy_failed)
        val stepLabel = when {
            status == "done" -> agoFull(parseTime(d?.finishedAt))
            running && d?.logPath.isNullOrEmpty() && d?.log == null -> tr(R.string.queued)
            else -> STEPS[step]
        }
        DeployHero(
            name.ifEmpty { d?.title.orEmpty() }, status, step, secs?.let(::clockTimer) ?: "--", stepLabel,
            if (failed) errLine else null,
            if (failed) {
                {
                    val s0 = parseTime(d?.startedAt)
                    val e0 = parseTime(d?.finishedAt)
                    val unknown = tr(R.string.unknown)
                    IncidentCard.share(
                        ctx, tr(R.string.build_failed, name),
                        listOf(tr(R.string.started) to (s0?.let { "${day(it)} ${clock(it)}" } ?: unknown), tr(R.string.ended) to (e0?.let { "${day(it)} ${clock(it)}" } ?: unknown), tr(R.string.duration) to (duration(s0, e0) ?: unknown)),
                        errLine,
                    )
                }
            } else null,
            cause, canFix,
        ) { f ->
            when (f) {
                Fix.Redeploy -> redeploy()
                Fix.Server -> nav.navigate(ServerR)
                else -> { lastFix = f; fix = f }
            }
        }
        if (failed) {
            val up = snap?.containersOf(svc)?.any { it.state == "running" }
            val lastDone = siblings.value.orEmpty().filter { it.status == "done" }.maxByOrNull { parseTime(it.createdAt) ?: 0 }
            val checking = tr(R.string.dep_checking) to Tone.Ring
            val (line, tone) = when {
                snap == null -> checking
                up == true && step < 2 -> tr(R.string.dep_live_hash, lastDone?.hash()?.take(7) ?: tr(R.string.previous)) to Tone.Ok
                up == false -> tr(R.string.not_running_word, name) to Tone.Signal
                else -> checking
            }
            val open: (() -> Unit)? = if (lastDone != null) ({ diff = true }) else null
            Card(Modifier.padding(top = 10.dp), onClick = open, pad = 14) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mk(tone)
                    Txt(line, Modifier.weight(1f), size = 14, weight = 600, maxLines = 2)
                    if (open != null) Ico(Ph.Next, size = 16, tint = H.c.faint)
                }
            }
        }
        LogBox(logLines, Modifier.weight(1f).padding(top = 12.dp), follow = status != "done")
        stream.error?.let { ErrorLine(it) }
        err?.let { ErrorLine(it) }
        if (failed) {
            val lines = remember(logLines.size) { logLines.map { it.line } }
            ExplainButton(Modifier.padding(top = 12.dp)) { explainDeploy(cause, lines, d?.errorMessage) }
            Btn(tr(R.string.redeploy_2), ::redeploy, Modifier.padding(top = 12.dp), BtnStyle.Signal, icon = Ph.Refresh)
        }
        else if (running && stopId.isNotEmpty()) Btn(tr(R.string.dep_stop), { stop = true }, Modifier.padding(top = 12.dp), BtnStyle.Danger)

        SheetFrame(diff, { diff = false }) { DeployDiff(siblings.value.orEmpty(), siblingDetail.value) }
        if (d != null && d.serviceId.isNotEmpty()) SheetFrame(fix != null, { fix = null }) {
            val load = rememberLoad(d.kind, d.serviceId) { Dokploy.one(d.kind, d.serviceId) }
            if (lastFix == Fix.Resources) ResourcesCard(nav, d.serviceId, name, load) else EnvTab(d.kind, d.serviceId, load)
        }
        ConfirmSheet(
            stop, tr(R.string.dep_stop_title), tr(R.string.dep_stop_body), tr(R.string.dep_stop_hold),
            onConfirm = {
                stop = false
                guard(tr(R.string.dep_stop_title)) {
                    scope.launch {
                        try { Plat.cancelDeploy(stopKind, stopId) } catch (e: CancellationException) { throw e } catch (e: ApiError) { err = e.message }
                    }
                }
            },
            onDismiss = { stop = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeployHero(
    name: String, status: String?, step: Int, time: String, label: String, errLine: String?, onShare: (() -> Unit)?,
    cause: Cause?, canFix: (Fix) -> Boolean, onFix: (Fix) -> Unit,
) {
    val tone = deployTone(status)
    val color = toneColor(tone)
    Card(color = toneSoft(tone), pad = 18) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
            Txt(name, Modifier.weight(1f), size = 20, weight = 600, tracking = -0.02f, maxLines = 2)
            Mk(tone)
            Txt(deployWord(status), size = 13, weight = 700, color = color, maxLines = 1)
            if (onShare != null) Box(Modifier.size(48.dp).press(onShare, tr(R.string.share)), Alignment.Center) { Ico(Ph.Out, size = 20) }
        }
        Row(Modifier.padding(top = 14.dp), Arrangement.spacedBy(20.dp), Alignment.CenterVertically) {
            StepRing(step, status, Modifier.size(92.dp))
            Column {
                DotNum(time, 80)
                Txt(label, Modifier.padding(top = 4.dp), size = 14, weight = 600, color = color, maxLines = 2)
            }
        }
        if (errLine != null) Txt(errLine, Modifier.padding(top = 14.dp), size = 12, weight = 500, mono = true, color = H.c.bad, maxLines = 3)
        if (cause != null) FlowRow(Modifier.padding(top = 12.dp).appear(), Arrangement.spacedBy(8.dp), Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Pill("${tr(cause.kind.label)} · ${(cause.confidence * 100).roundToInt()}%", Tone.Signal)
            cause.kind.fix?.takeIf(canFix)?.let { f -> FixChip(tr(f.label)) { onFix(f) } }
        }
    }
}

@Composable
private fun FixChip(label: String, onClick: () -> Unit) {
    val c = H.c
    Box(Modifier.heightIn(min = 48.dp).press(onClick, label), Alignment.Center) {
        Row(Modifier.clip(CircleShape).background(c.signalFill).padding(horizontal = 14.dp, vertical = 8.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            Ico(Ph.Go, size = 14, tint = c.signalInk)
            Txt(label, size = 13, weight = 600, color = c.signalInk, maxLines = 1)
        }
    }
}

@Composable
private fun StepRing(step: Int, status: String?, modifier: Modifier) {
    val color = toneColor(deployTone(status))
    val live = status == "running"
    val pulse: State<Float> =
        if (live && !LocalReduceMotion.current) rememberInfiniteTransition(label = "ring").animateFloat(1f, 0.3f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
        else remember { mutableStateOf(1f) }
    Box(modifier, Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 9.dp.toPx()
            val gap = 22f
            repeat(4) { i ->
                val alpha = when { i > step -> 0.18f; live && i == step -> pulse.value; else -> 1f }
                drawArc(
                    color.copy(alpha = alpha), -90f + i * 90f + gap / 2, 90f - gap, false,
                    Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        Ico(deployIcon(status), size = 30, tint = color)
    }
}
