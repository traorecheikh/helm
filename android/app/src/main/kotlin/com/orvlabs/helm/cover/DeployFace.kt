package com.orvlabs.helm.cover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.CheckCircle
import com.adamglin.phosphoricons.fill.RocketLaunch
import com.adamglin.phosphoricons.fill.XCircle
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Deployment
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Btn
import com.orvlabs.helm.ui.components.BtnStyle
import com.orvlabs.helm.ui.components.DotNum
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.components.agoFull
import com.orvlabs.helm.ui.components.clockTimer
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay

/** step: 0..3 from the live log while running, -1 before the first line. */
@Composable
fun DeployFace(d: Deployment?, name: String, step: Int, onRedeploy: () -> Unit) {
    val running = d?.status == "running"
    val failed = d?.status == "error"
    val started = parseTime(d?.startedAt ?: d?.createdAt)
    val finished = parseTime(d?.finishedAt)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(1000) } }
    val reduce = LocalReduceMotion.current
    val sweep by animateFloatAsState(if (running) (step.coerceAtLeast(0) + 0.5f) / 4f else 1f, if (reduce) snap() else tween(900), label = "ring")
    val (accent, word, icon) = when {
        running -> Triple(H.c.ink, tr(R.string.status_building), PhosphorIcons.Fill.RocketLaunch)
        failed -> Triple(H.c.bad, tr(R.string.status_failed), PhosphorIcons.Fill.XCircle)
        else -> Triple(H.c.ok, tr(R.string.deployed, "").substringBefore(" ·"), PhosphorIcons.Fill.CheckCircle)
    }
    val track = H.c.off
    Face(
        PhosphorIcons.Fill.RocketLaunch, tr(R.string.deploys), if (d == null) H.c.muted else accent, 0.44f,
        left = {
            if (d == null) return@Face
            Box(Modifier.fillMaxHeight().aspectRatio(1f), Alignment.Center) {
                Canvas(Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = word }) { ring(sweep, accent, track) }
                if (running) DotNum(clockTimer(((now - (started ?: now)) / 1000).coerceAtLeast(0)), 34) else Ico(icon, size = 56, tint = accent)
            }
        },
    ) {
        Spacer(Modifier.weight(1f))
        if (d == null) {
            Status(PhosphorIcons.Fill.RocketLaunch, H.c.muted, tr(R.string.no_deploys_yet), null)
            Spacer(Modifier.weight(1f))
            return@Face
        }
        Txt(name, size = 22, weight = 600, tracking = -0.02f, lineHeight = 1.15f, maxLines = 2)
        val sub = when {
            failed -> d.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(100) ?: tr(R.string.the_last_deploy_failed)
            running -> null
            else -> finished?.let { agoFull(it) }
        }
        sub?.let { Txt(it, Modifier.padding(top = 6.dp), size = 13, color = H.c.muted, lineHeight = 1.3f, maxLines = 2) }
        if (running) Steps(step, Modifier.padding(top = 16.dp))
        if (failed) Btn(tr(R.string.redeploy_2), onRedeploy, Modifier.padding(top = 12.dp), BtnStyle.Signal)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Steps(step: Int, modifier: Modifier) {
    val names = listOf(R.string.pull, R.string.build, R.string.start, R.string.check)
    val desc = tr(names[step.coerceIn(0, 3)])
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = desc }, Arrangement.spacedBy(6.dp)) {
        names.indices.forEach { i ->
            Box(Modifier.weight(1f).height(6.dp).background(if (i <= step) H.c.ink else H.c.off, CircleShape))
        }
    }
}

@Preview(widthDp = 512, heightDp = 260, showBackground = true, backgroundColor = 0xFF0D0B12)
@Composable
private fun DeployRunningPreview() = CoverPreview {
    DeployFace(Deployment(deploymentId = "d1", status = "running", applicationId = "a1"), "karma-renderer", 1) {}
}

@Preview(widthDp = 512, heightDp = 260, showBackground = true, backgroundColor = 0xFF0D0B12)
@Composable
private fun DeployFailedPreview() = CoverPreview {
    DeployFace(Deployment(deploymentId = "d1", status = "error", applicationId = "a1", errorMessage = "npm ERR! missing script: build"), "karma-renderer", 0) {}
}
