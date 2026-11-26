package com.orvlabs.helm.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.width
import com.orvlabs.helm.R
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn

/** Fleet: how many containers run, and which one needs you. */
class HelmWidget : HelmGlance() {
    @Composable
    override fun Body(w: W) {
        val s = w.s
        val problem = problemLine(w)
        val sentence = if (s == null) "" else problem ?: when {
            s.down > 0 -> tr(R.string.w_down, s.down)
            s.building != null -> tr(R.string.w_all_running_building, s.building)
            else -> tr(R.string.w_all_running)
        }
        val alert = when {
            s == null -> null
            s.down > 0 -> tr(R.string.w_n_down_tag, s.down) to 3
            s.building != null -> trn(R.plurals.w_n_building, 1) to 1
            else -> null
        }
        val root = if (s == null) "" else "${tr(R.string.w_eb_helm)}. $sentence. ${tr(R.string.w_updated_at, w.time(s.at))}"
        Frame(w, root, "helm://pulse") {
            val s2 = w.s ?: return@Frame
            val state = if (!w.live) w.muted else if (s2.down > 0) w.bad else w.ok
            val cap = if (w.oneRow) 26.dp else if (w.narrow) 36.dp else 44.dp
            val num = Mask.number(w.ctx, if (s2.running > 9999) "${s2.running / 1000}K" else "${s2.running}", w.px(cap).toFloat())
            val numW = (num.width / w.density).dp
            val gridW = w.size.width - 28.dp - numW - 8.dp
            val gridH = if (w.oneRow) w.size.height - 16.dp else cap
            val grid = if (gridW > 28.dp) Mask.grid(s2.dots, w.px(gridW), w.px(gridH)) else null
            if (w.oneRow) {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Mark(num, numW, cap, state)
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) { T(sentence, w, 13, if (s2.down > 0) state else w.tone, bold = true) }
                    if (grid != null && !w.narrow) Stack(grid, gridW, gridH, w, w.good)
                }
            } else {
                Eyebrow(w, tr(R.string.w_eb_helm), alert)
                Spacer(GlanceModifier.defaultWeight())
                Row(verticalAlignment = Alignment.Bottom) {
                    Mark(num, numW, cap, state)
                    Spacer(GlanceModifier.width(8.dp))
                    if (grid != null) Stack(grid, gridW, gridH, w, w.good)
                }
                Spacer(GlanceModifier.height(6.dp))
                T(sentence, w, 14, if (s2.down > 0) state else w.tone, bold = true, lines = 2)
                if (!w.narrow && s2.downNames.isNotEmpty() && w.size.height > 120.dp) T(s2.downNames.joinToString(", "), w, 12, w.muted)
            }
        }
    }
}

class HelmWidgetReceiver : HelmReceiver() {
    override val glanceAppWidget = HelmWidget()
}
