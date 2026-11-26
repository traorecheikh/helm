package com.orvlabs.helm.cover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.fill.HardDrives
import com.adamglin.phosphoricons.fill.WarningCircle
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Stats
import com.orvlabs.helm.num
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.DotNum
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion

private fun pct(used: Long, total: Long) = if (total > 0) used * 100f / total else 0f

/** active: true when this face is the settled page, so the bars fill up on arrival. */
@Composable
fun ServerFace(stats: Stats?, active: Boolean) {
    val days = stats?.diskFullInDays
    val soon = days != null && days < 30
    Face(
        PhosphorIcons.Fill.HardDrives, tr(R.string.server), H.c.ink, 0.56f,
        left = {
            if (stats == null) return@Face
            Row(Modifier.fillMaxSize(), Arrangement.SpaceEvenly) {
                Bar(tr(R.string.cpu_2), stats.cpuPercent.toFloat(), active, warnAt = 90f)
                Bar(tr(R.string.memory), pct(stats.memUsedBytes, stats.memTotalBytes), active)
                Bar(tr(R.string.disk), pct(stats.diskUsedBytes, stats.diskTotalBytes), active)
            }
        },
    ) {
        Spacer(Modifier.weight(1f))
        if (stats == null) {
            Status(PhosphorIcons.Fill.HardDrives, H.c.muted, tr(R.string.loading_your_server), null)
            Spacer(Modifier.weight(1f))
            return@Face
        }
        if (stats.hostname.isNotEmpty()) Txt(stats.hostname, size = 20, weight = 600, tracking = -0.02f)
        if (days != null && soon) {
            val tint = if (days < 7) H.c.bad else H.c.warn
            Row(Modifier.padding(top = 6.dp), Arrangement.spacedBy(6.dp), Alignment.Top) {
                Ico(PhosphorIcons.Fill.WarningCircle, Modifier.padding(top = 2.dp), size = 14, tint = tint)
                Txt(trn(R.plurals.full_in_about_rate, days.toInt().coerceAtLeast(1)), size = 14, weight = 500, color = tint, lineHeight = 1.3f)
            }
        } else {
            Txt(tr(R.string.up_days, num(stats.uptimeSeconds / 86_400)), Modifier.padding(top = 6.dp), size = 14, color = H.c.muted)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Bar(label: String, value: Float, active: Boolean, warnAt: Float = 85f) {
    val reduce = LocalReduceMotion.current
    val shown by animateFloatAsState(if (active || reduce) value.coerceIn(0f, 100f) else 0f, if (reduce) snap() else tween(900, delayMillis = 120), label = "bar")
    val hot = value >= warnAt
    val fill = if (hot) H.c.warn else H.c.ok
    val track = H.c.raise
    val word = "$label ${value.toInt()}%" + if (hot) " · " + tr(R.string.cover_high) else ""
    Column(Modifier.fillMaxHeight().clearAndSetSemantics { contentDescription = word }, horizontalAlignment = Alignment.CenterHorizontally) {
        DotNum("${shown.toInt()}", 36)
        Canvas(Modifier.padding(vertical = 8.dp).width(40.dp).weight(1f)) {
            val r = CornerRadius(size.width / 2)
            drawRoundRect(track, cornerRadius = r)
            val h = (size.height * shown / 100f).coerceAtLeast(if (shown > 0f) size.width else 0f)
            if (h > 0f) drawRoundRect(fill, Offset(0f, size.height - h), Size(size.width, h), r)
        }
        Txt(label, size = 13, weight = 600, color = H.c.muted, maxLines = 1)
    }
}

@Preview(widthDp = 512, heightDp = 260, showBackground = true, backgroundColor = 0xFF0D0B12)
@Composable
private fun ServerPreview() = CoverPreview {
    ServerFace(
        Stats(cpuPercent = 23.0, cpuCount = 4, memUsedBytes = 6_200_000_000, memTotalBytes = 8_000_000_000, diskUsedBytes = 71_000_000_000, diskTotalBytes = 80_000_000_000, uptimeSeconds = 1_100_000, hostname = "orv-1", diskFullInDays = 12.0),
        active = true,
    )
}
