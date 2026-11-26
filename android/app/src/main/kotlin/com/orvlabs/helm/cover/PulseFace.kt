package com.orvlabs.helm.cover

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.MoonStars
import com.adamglin.phosphoricons.fill.Pulse
import com.adamglin.phosphoricons.fill.WarningCircle
import com.adamglin.phosphoricons.fill.WifiSlash
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Container
import com.orvlabs.helm.data.Health
import com.orvlabs.helm.data.Link
import com.orvlabs.helm.data.NightSummary
import com.orvlabs.helm.data.PulseState
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Snapshot
import com.orvlabs.helm.data.Updates
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.DotNum
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.components.clock
import com.orvlabs.helm.ui.components.day
import com.orvlabs.helm.ui.theme.H

/** 0 calm, 0.5 something to watch, 1 something needs a hand now. */
fun health(s: PulseState): Float {
    val snap = s.snap ?: return 0f
    if (s.link == Link.Unreachable) return 1f
    val failing = snap.projects.flatMap { it.services }.any { it.status == "error" }
    val dead = snap.containers.any { it.state in setOf("restarting", "dead") }
    val problems = Repo.problems(snap, s.stats)
    if (failing || dead || problems.any { it.severe }) return 1f
    return if (problems.isNotEmpty() || s.link == Link.Offline) 0.5f else 0f
}

@Composable
fun PulseFace(s: PulseState) {
    val snap = s.snap
    val h = health(s)
    val tint = stateColor(h)
    val down = s.link == Link.Unreachable
    Face(
        PhosphorIcons.Fill.Pulse, tr(R.string.pulse), tint, 0.5f,
        left = {
            if (snap == null) return@Face
            val running = snap.containers.count { it.state == "running" }
            val track = H.c.off
            Canvas(Modifier.fillMaxHeight().aspectRatio(1f).alpha(if (down) 0.45f else 1f)) { ring(running / snap.containers.size.coerceAtLeast(1).toFloat(), tint, track) }
            DotNum("$running", if (running > 99) 80 else 110, Modifier.alpha(if (down) 0.45f else 1f))
        },
    ) {
        Spacer(Modifier.weight(1f))
        if (snap == null) {
            Status(PhosphorIcons.Fill.Pulse, H.c.muted, if (s.loading) tr(R.string.loading_your_server) else tr(R.string.your_server_is_not_answering), null)
        } else {
            val failing = snap.projects.flatMap { it.services }.filter { it.status == "error" }
            val problems = Repo.problems(snap, s.stats)
            when {
                down -> Status(PhosphorIcons.Fill.WarningCircle, tint, tr(R.string.your_server_is_not_answering), null)
                s.link == Link.Offline -> Status(PhosphorIcons.Fill.WifiSlash, tint, tr(R.string.no_internet_on_this_phone_showing, clock(snap.at)), null)
                failing.isNotEmpty() -> Status(
                    PhosphorIcons.Fill.WarningCircle, tint,
                    if (failing.size > 1) tr(R.string.failing_more, failing.first().name, failing.size - 1) else tr(R.string.failing_one, failing.first().name), null,
                )
                problems.isNotEmpty() -> Status(PhosphorIcons.Fill.WarningCircle, tint, trn(R.plurals.cover_need_you, problems.size), null)
                else -> Status(PhosphorIcons.Fill.CheckCircle, tint, tr(R.string.all_running), null)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Two sentences at most, most actionable first. */
@Composable
fun NightFace(needs: Int, down: Int, quietUntil: String?, night: NightSummary?, updates: Updates?) {
    val quiet = needs == 0 && down == 0
    val tint = if (quiet) H.c.ok else H.c.warn
    val lines = buildList {
        if (down > 0) add(trn(R.plurals.cover_n_down, down))
        add(if (needs > 0) trn(R.plurals.cover_need_you, needs) else tr(R.string.nothing_needs_you_right_now))
        (updates?.nextReboot ?: updates?.scheduledReboot)?.let {
            add(tr(R.string.w_reboot_next, "${day(it * 1000)} ${clock(it * 1000)}") + "." + if (updates?.final == true) " " + tr(R.string.cover_reboot_final) else "")
        }
        quietUntil?.let { add(tr(R.string.cover_quiet_until, it)) }
        night?.let { add(tr(R.string.cover_overnight, trn(R.plurals.cover_n_deploys, it.deploys), trn(R.plurals.cover_n_bans, it.bans))) }
    }.take(2)
    Face(
        PhosphorIcons.Fill.MoonStars, tr(R.string.cover_night), tint, 0.46f,
        left = {
            Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
                Ico(if (quiet) PhosphorIcons.Fill.MoonStars else PhosphorIcons.Fill.WarningCircle, Modifier.size(80.dp).background(if (quiet) H.c.okSoft else H.c.warnSoft, CircleShape).padding(20.dp), size = 40, tint = tint)
                Txt(if (quiet) tr(R.string.cover_all_quiet) else tr(R.string.cover_not_quiet), size = 28, weight = 700, tracking = -0.03f, lineHeight = 1.05f)
            }
        },
    ) {
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
            lines.forEach { Txt(it, size = 15, weight = 500, lineHeight = 1.3f) }
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun sample(states: List<String>) = Snapshot(
    at = 1_763_500_000_000, projects = emptyList(), health = Health(), deployments = emptyList(), destinations = 1,
    containers = states.mapIndexed { i, st -> Container(containerId = "c$i", name = "app$i", state = st) },
)

@Preview(widthDp = 512, heightDp = 260, showBackground = true, backgroundColor = 0xFF100A0E)
@Composable
private fun PulseCalmPreview() = CoverPreview {
    PulseFace(PulseState(snap = sample(List(35) { "running" }), loading = false))
}

@Preview(widthDp = 512, heightDp = 260, showBackground = true, backgroundColor = 0xFF100A0E)
@Composable
private fun PulseDownPreview() = CoverPreview {
    PulseFace(PulseState(snap = sample(List(30) { "running" } + "dead" + "restarting"), loading = false))
}

@Preview(widthDp = 512, heightDp = 260, showBackground = true, backgroundColor = 0xFF100A0E)
@Composable
private fun NightPreview() = CoverPreview {
    NightFace(0, 0, "07:00", NightSummary(deploys = 2, bans = 41), Updates(nextReboot = 1_763_600_000))
}
