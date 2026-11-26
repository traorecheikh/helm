package com.orvlabs.helm.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.Str
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.HelmTheme

/** Progress ring: [sweep] of the circle in [color] over [track]. */
fun DrawScope.ring(sweep: Float, color: Color, track: Color) {
    val w = 14.dp.toPx()
    val inset = w / 2 + 4.dp.toPx()
    val tl = Offset(inset, inset)
    val arc = Size(size.width - 2 * inset, size.height - 2 * inset)
    drawArc(track, 0f, 360f, false, tl, arc, style = Stroke(w))
    if (sweep > 0f) drawArc(color, -90f, 360f * sweep.coerceAtMost(1f), false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
}

/** Landscape frame for the 512 x 260 cover: one big shape on the left, one status and its action on the right. */
@Composable
fun Face(icon: ImageVector, title: String, tint: Color, split: Float, left: @Composable BoxScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 28.dp, top = 16.dp, bottom = 22.dp), Arrangement.spacedBy(20.dp)) {
        Box(Modifier.weight(split).fillMaxHeight(), Alignment.Center, content = left)
        Column(Modifier.weight(1f - split).fillMaxHeight()) {
            Box(Modifier.size(28.dp).background(H.c.raise, CircleShape).semantics(mergeDescendants = true) { heading(); contentDescription = title }, Alignment.Center) {
                Ico(icon, size = 16, tint = tint)
            }
            right()
        }
    }
}

/** Status is never colour only: icon + a few words, tinted. Sentences wrap, never ellipsize. */
@Composable
fun Status(icon: ImageVector, tint: Color, title: String, sub: String?, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Ico(icon, Modifier.padding(top = 2.dp), size = 22, tint = tint)
            Txt(title, size = 20, weight = 600, tracking = -0.02f, lineHeight = 1.15f)
        }
        sub?.let { Txt(it, Modifier.padding(top = 6.dp), size = 13, color = H.c.muted, lineHeight = 1.3f) }
    }
}

@Composable
fun CoverPreview(content: @Composable () -> Unit) {
    Str.app = LocalContext.current
    HelmTheme("dark") { Box(Modifier.fillMaxSize().background(H.c.bg)) { content() } }
}
