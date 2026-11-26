package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.*
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun vulnsVt(v: Vulns): Pair<String, Tone> = when {
    !v.installed -> tr(R.string.srv_off) to Tone.Ring
    v.at == null -> "–" to Tone.Ring
    v.fixable.critical > 0 -> "${v.fixable.critical}" to Tone.Signal
    v.fixable.high > 0 -> "${v.fixable.high}" to Tone.Warn
    else -> tr(R.string.ok_word) to Tone.Ok
}

@Composable
internal fun HomeVulns() {
    val load = rememberLoad { VulnsApi.get() }
    EveryWhileVisible(300_000) { load.reload() }
    var open by remember { mutableStateOf(false) }
    val v = load.value ?: return
    val n = v.fixable.critical + v.fixable.high
    if (n == 0) return
    val tone = if (v.fixable.critical > 0) Tone.Signal else Tone.Warn
    Card(Modifier.appear(), onClick = { open = true }, pad = 14) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            SevRing(v.fixable)
            Txt(
                if (v.fixable.critical > 0) trn(R.plurals.aud_critical, v.fixable.critical) else trn(R.plurals.aud_high, v.fixable.high),
                Modifier.weight(1f), size = 16, weight = 600, color = toneColor(tone), maxLines = 2,
            )
            Ico(Ph.Next, size = 16, tint = H.c.muted)
        }
    }
    Detail(open, tr(R.string.aud_title), { open = false }) { VulnsBody(load) }
}

@Composable
private fun SevRing(s: SevCounts) {
    val c = H.c
    val total = (s.critical + s.high).coerceAtLeast(1)
    Canvas(Modifier.size(40.dp).semantics { contentDescription = "${trn(R.plurals.aud_critical, s.critical)}, ${trn(R.plurals.aud_high, s.high)}" }) {
        val sw = 6.dp.toPx()
        val d = size.minDimension - sw
        val tl = Offset(sw / 2, sw / 2)
        val crit = 360f * s.critical / total
        drawArc(c.warn, -90f + crit, 360f - crit, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Butt))
        if (crit > 0f) drawArc(c.bad, -90f, crit, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Butt))
    }
}

@Composable
private fun SevBar(s: SevCounts) {
    val c = H.c
    val total = (s.critical + s.high).toFloat()
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(if (total == 0f) c.ok else c.warn, Offset.Zero, size, r)
        if (s.critical > 0) drawRoundRect(c.bad, Offset.Zero, Size(size.width * s.critical / total, size.height), r)
    }
}

@Composable
private fun Trend(days: List<VulnDay>) {
    val c = H.c
    val shown = days.takeLast(30)
    val max = (shown.maxOfOrNull { it.critical + it.high } ?: 0).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = tr(R.string.aud_trend) }) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * 29) / 30
        shown.forEachIndexed { j, d ->
            val x = (30 - shown.size + j) * (w + gap)
            val hHigh = size.height * (d.critical + d.high) / max
            val hCrit = size.height * d.critical / max
            drawRoundRect(if (d.high + d.critical == 0) c.off else c.warn, Offset(x, size.height - hHigh.coerceAtLeast(2f)), Size(w, hHigh.coerceAtLeast(2f)), CornerRadius(w / 2))
            if (hCrit > 0) drawRoundRect(c.bad, Offset(x, size.height - hCrit), Size(w, hCrit), CornerRadius(w / 2))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VulnsBody(load: Load<Vulns>) {
    EveryWhileVisible(20_000) { load.reload() }
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var open by remember { mutableStateOf(setOf<String>()) }
    val owner = Store.config.value?.owner == true

    fun run(label: String, ok: String? = null, block: suspend () -> Unit) = guard(label) {
        scope.launch {
            try { block(); msg = ok?.let { it to false }; load.reload(); Haptics.confirmLater() } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); msg = (e.message ?: "") to true }
        }
    }

    Failed(load)
    val v = load.value ?: return
    if (!v.installed) return Empty(tr(R.string.aud_missing))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(trn(R.plurals.aud_critical, v.counts.critical), if (v.counts.critical > 0) Tone.Signal else Tone.Ink)
        Pill(trn(R.plurals.aud_high, v.counts.high), if (v.counts.high > 0) Tone.Warn else Tone.Ink)
        if (v.running) Pill(tr(R.string.aud_scanning))
        if (v.autofixOff) Pill(tr(R.string.aud_auto_off), Tone.Warn)
    }
    Box(Modifier.padding(top = 12.dp)) { SevBar(v.counts) }
    v.at?.let { Stat(tr(R.string.aud_last_run), agoFull(it * 1000)) }
    v.dbError?.let { ErrorLine(tr(R.string.aud_db_error), Modifier.padding(top = 8.dp)) }
    if (v.history.size > 1) {
        K(tr(R.string.aud_trend), Modifier.padding(top = 12.dp))
        Box(Modifier.padding(top = 8.dp)) { Trend(v.history) }
    }
    if (owner && !v.running) Btn(tr(R.string.aud_scan), { run(tr(R.string.aud_scan), tr(R.string.aud_scan_started)) { VulnsApi.scan() } }, Modifier.padding(top = 14.dp), BtnStyle.Ink, icon = Ph.Refresh)
    Result(msg)

    val targets = v.targets.filter { it.counts.critical + it.counts.high > 0 || it.error != null }.sortedWith(compareBy({ -it.counts.critical }, { -it.counts.high }))
    if (v.at != null && targets.isEmpty()) Empty(tr(R.string.aud_none))
    targets.forEach { t ->
        val id = t.kind + t.target
        val title = if (t.kind == "host") tr(R.string.aud_host) else t.service?.name ?: t.target
        SrvRow(
            title, Modifier.padding(top = 8.dp), sub = if (t.kind == "host") null else t.target, subLines = 1,
            left = { Mk(if (t.error != null) Tone.Ring else if (t.counts.critical > 0) Tone.Signal else Tone.Warn) },
            right = { Pill("${t.fixable.critical + t.fixable.high}/${t.counts.critical + t.counts.high}", if (t.fixable.critical > 0) Tone.Signal else Tone.Warn) },
            onClick = { open = if (id in open) open - id else open + id },
        )
        if (id in open) TargetBody(t, v.autofix.firstOrNull { it.key == t.service?.key }, owner) { label, ok, block -> run(label, ok, block) }
    }
}

@Composable
private fun TargetBody(t: VulnTarget, fix: Autofix?, owner: Boolean, run: (String, String?, suspend () -> Unit) -> Unit) {
    val c = H.c
    t.error?.let { ErrorLine(it, Modifier.padding(bottom = 6.dp)) }
    t.findings.forEach { f ->
        SrvRow(
            f.pkg, Modifier.padding(start = 12.dp), mono = true, titleSize = 13, sub = "${f.id} · ${f.installed}" + (f.fixed?.let { " → $it" } ?: ""), subLines = 2,
            left = { Pill(if (f.severity == "critical") "C" else "H", if (f.severity == "critical") Tone.Signal else Tone.Warn) },
            right = { if (f.fixable) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Mk(Tone.Ok); RightText(tr(R.string.aud_fix)) } },
        )
    }
    if (!owner) return
    if (t.kind == "host") return Box(Modifier.padding(top = 8.dp)) { HoldButton(tr(R.string.aud_patch_hold), 1200) { run(tr(R.string.aud_patch), null) { Ops.installUpdates() } } }
    val s = t.service ?: return
    if (t.database) return Stat(tr(R.string.aud_auto), tr(R.string.aud_db), c.muted)
    Box(Modifier.padding(top = 8.dp)) { HoldButton(tr(R.string.aud_redeploy_hold), 1200) { run(tr(R.string.redeploy_2), null) { VulnsApi.redeploy(s) } } }
    var arming by remember { mutableStateOf(false) }
    ToggleRow(tr(R.string.aud_auto), lastFix(fix?.last), fix?.enabled == true) { on ->
        if (on) arming = true else run(tr(R.string.aud_auto), null) { VulnsApi.setAutofix(s, false, false) }
    }
    if (arming) HoldButton(tr(if (t.stateful) R.string.aud_auto_hold_data else R.string.aud_auto_hold), 1500) {
        arming = false
        run(tr(R.string.aud_auto), null) { VulnsApi.setAutofix(s, true, t.stateful) }
    }
}

private fun lastFix(r: AutofixRun?): String {
    r ?: return ""
    val word = when {
        r.result == "done" -> tr(R.string.aud_done)
        r.result.startsWith("failed") -> tr(R.string.aud_failed)
        r.result.startsWith("skipped") -> tr(R.string.aud_skipped)
        else -> tr(R.string.aud_scanning)
    }
    return "$word · ${ago(r.at * 1000)}"
}
