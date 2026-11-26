package com.orvlabs.helm.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.data.quietNow
import com.orvlabs.helm.service.ActionActivity
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.clock
import com.orvlabs.helm.ui.components.day
import com.orvlabs.helm.ui.components.duration
import java.text.NumberFormat

class UptimeWidget : HelmGlance() {
    @Composable
    override fun Body(w: W) {
        val s = w.s
        val vals = s?.strip.orEmpty().filterNotNull()
        val pct = if (vals.isEmpty()) null else NumberFormat.getNumberInstance(com.orvlabs.helm.appLocale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(vals.average())
        val badDays = s?.strip.orEmpty().count { it != null && it < 99.95 }
        val down = s?.downNames.orEmpty()
        val sentence = if (s == null) "" else problemLine(w) ?: when {
            down.size == 1 -> tr(R.string.w_down_since, down.first(), w.time(s.at))
            down.size > 1 -> trn(R.plurals.w_n_services_down, down.size)
            badDays > 0 -> tr(R.string.w_pct_over_60, pct ?: "")
            else -> tr(R.string.w_no_downtime_60)
        }
        val alert = when {
            s == null -> null
            down.isNotEmpty() -> tr(R.string.w_down_tag) to 3
            badDays > 0 -> trn(R.plurals.w_n_days_tag, badDays) to 2
            else -> null
        }
        val root = if (s == null) "" else "${tr(R.string.w_eb_uptime)}. $sentence. ${tr(R.string.w_updated_at, w.time(s.at))}"
        Frame(w, root, "helm://uptime") {
            val s2 = w.s ?: return@Frame
            val sc = if (!w.live) w.muted else if (down.isNotEmpty()) w.bad else if (badDays > 0) w.warn else w.ok
            val sw = if (w.oneRow) w.size.width - 28.dp - 80.dp else w.size.width - 28.dp
            val sh = if (w.oneRow) w.size.height - 16.dp else (w.size.height - 28.dp - 60.dp).coerceAtLeast(30.dp)
            val strip = Mask.strip(s2.strip, w.px(sw.coerceAtLeast(60.dp)), w.px(sh), down.isNotEmpty())
            if (w.oneRow) {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.width(72.dp)) { T(pct?.let { tr(R.string.w_pct, it) } ?: "", w, 14, sc, bold = true); T(tr(R.string.w_60_days), w, 11, w.muted) }
                    Stack(strip, sw.coerceAtLeast(60.dp), sh, w, w.good)
                }
            } else {
                Eyebrow(w, tr(R.string.uptime), alert)
                Spacer(GlanceModifier.height(8.dp))
                Stack(strip, sw.coerceAtLeast(60.dp), sh, w, w.good)
                Spacer(GlanceModifier.height(8.dp))
                T(sentence, w, 14, sc, bold = true)
            }
        }
    }
}

class UptimeReceiver : HelmReceiver() {
    override val glanceAppWidget = UptimeWidget()
}

class DeployWidget : HelmGlance() {
    @Composable
    override fun Body(w: W) {
        val s = w.s
        val d = s?.deploy
        val failed = d?.status == "error"
        val t = d?.let { w.time(if (it.finishedAt > 0) it.finishedAt else it.startedAt) }.orEmpty()
        val status = when (d?.status) {
            "done" -> tr(R.string.w_deployed_at_in, t, duration(d.startedAt, d.finishedAt).orEmpty())
            "running" -> tr(R.string.w_running_since, t)
            "error" -> tr(R.string.w_failed_at, t)
            "cancelled" -> tr(R.string.w_cancelled_at, t)
            else -> tr(R.string.w_queued_since, t)
        }
        val glyph = when (d?.status) { "done" -> "●"; "running" -> "○"; "error" -> "◉"; else -> "∙" }
        val col = if (!w.live) w.muted else when (d?.status) { "error" -> w.bad; "done" -> w.ok; "idle" -> w.muted; else -> w.ink }
        val pill = failed && !w.stale && s?.linkDown == null
        val root = if (d == null) "" else tr(R.string.w_last_deploy_a11y, d.service, status) + " " + d.errorLine
        val redeploy = actionStartActivity(
            Intent(w.ctx, ActionActivity::class.java).putExtra("action", "redeploy").putExtra("notifId", 9200)
                .putExtra("serverId", w.cfg?.activeId ?: 0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Frame(w, root, if (d != null) "helm://deploy?deploymentId=${d.deploymentId}" else "helm://pulse") {
            if (d == null) return@Frame T(tr(R.string.w_queued_since, ""), w, 13, w.muted)
            val body: @Composable () -> Unit = {
                Column(GlanceModifier.defaultWeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(glyph, w, if (w.oneRow) 12 else 14, col); Spacer(GlanceModifier.width(8.dp))
                        T(d.service, w, if (w.oneRow) 13 else 16, w.tone, bold = true)
                    }
                    T(status, w, if (w.oneRow) 11 else 13, col, lines = 1)
                    if (!w.oneRow && failed && d.errorLine.isNotEmpty()) T(d.errorLine, w, 12, w.muted)
                }
            }
            if (!w.oneRow) { Eyebrow(w, tr(R.string.deploys), null, time = false); Spacer(GlanceModifier.height(8.dp)) }
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = if (w.oneRow) Alignment.CenterVertically else Alignment.Bottom) {
                body()
                if (pill) {
                    Spacer(GlanceModifier.width(12.dp))
                    Box(
                        GlanceModifier.height(if (w.oneRow) 40.dp else 48.dp).background(w.signalFill).cornerRadius(24.dp).padding(horizontal = 18.dp)
                            .semantics { contentDescription = tr(R.string.w_redeploy_a11y, d.service) }.clickable(redeploy),
                        contentAlignment = Alignment.Center,
                    ) { T(tr(R.string.w_redeploy), w, 14, w.signalInk, bold = true) }
                }
            }
        }
    }
}

class DeployReceiver : HelmReceiver() {
    override val glanceAppWidget = DeployWidget()
}

class NightWidget : HelmGlance() {
    @Composable
    override fun Body(w: W) {
        val s = w.s
        val n = s?.night
        val cfg = w.cfg
        val quietNow = cfg != null && cfg.quietOn && quietNow(cfg.quietStart, cfg.quietEnd)
        val endText = cfg?.let { "%02d:%02d".format(it.quietEnd / 60, it.quietEnd % 60) }.orEmpty()
        val still = n?.stillDown
        val calm = n != null && n.downs.isEmpty() && n.failed == 0 && still == null
        val eyebrow = if (quietNow) tr(R.string.surf_quiet_until, endText) else if (cfg?.quietOn == false) tr(R.string.surf_last12) else tr(R.string.overnight)
        val (sentence, second, alert) = when {
            n == null -> Triple("", "", null)
            problemLine(w) != null -> Triple(problemLine(w)!!, "", null)
            still != null -> Triple(tr(R.string.down_still, still), "", tr(R.string.w_down_tag) to 3)
            n.downs.isNotEmpty() -> Triple(tr(R.string.w_one_incident_resolved), n.downs.first(), trn(R.plurals.w_n_incident_tag, n.downs.size) to 2)
            n.failed > 0 -> Triple(tr(R.string.w_build_failed_night), tr(R.string.nothing_went_down), trn(R.plurals.w_n_build_failed_tag, n.failed) to 2)
            quietNow -> Triple(tr(R.string.w_all_quiet_watch), tr(R.string.w_only_outages_wake), null)
            else -> Triple(tr(R.string.w_quiet_night), n.text, null)
        }
        val root = if (n == null) "" else "$eyebrow. $sentence $second"
        Frame(w, root, "helm://overnight") {
            val n2 = w.s?.night ?: return@Frame T(tr(R.string.open_helm_to_set_up_this), w, 13, lines = 2)
            val sc = when (alert?.second) { 3 -> w.bad; 2 -> w.warn; else -> w.good }
            val slots = if (w.narrow) 16 else 32
            val hw = if (w.oneRow) w.size.width - 28.dp - 130.dp else w.size.width - 28.dp
            val hz = Mask.horizon(n2.marks, slots, w.px(hw.coerceAtLeast(60.dp)), w.px(12.dp), quietNow)
            if (w.oneRow) {
                Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Box(GlanceModifier.width(120.dp)) { T(sentence, w, 14, sc, bold = true) }
                    Stack(hz, hw.coerceAtLeast(60.dp), 12.dp, w, w.good)
                }
            } else {
                Row(GlanceModifier.fillMaxWidth()) {
                    Box(GlanceModifier.defaultWeight()) { Eyebrow(w, eyebrow, alert, time = false) }
                }
                Spacer(GlanceModifier.height(8.dp))
                Stack(hz, hw.coerceAtLeast(60.dp), 12.dp, w, w.good)
                Spacer(GlanceModifier.height(10.dp))
                T(sentence, w, 16, sc, bold = true, lines = 2)
                if (second.isNotEmpty() && alert != null) T(second, w, 12, w.muted, lines = 2)
                s?.nextReboot?.let { T(tr(R.string.w_reboot_next, "${day(it * 1000)} ${clock(it * 1000)}"), w, 12, w.muted) }
            }
        }
    }
}

class NightReceiver : HelmReceiver() {
    override val glanceAppWidget = NightWidget()
}
