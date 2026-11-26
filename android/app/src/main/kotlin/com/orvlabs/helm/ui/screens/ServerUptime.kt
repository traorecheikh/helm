package com.orvlabs.helm.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.*
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import com.orvlabs.helm.widget.IncidentCard

@Composable
private fun DayBars(days: List<UptimeDay>) {
    val c = H.c
    val reduce = LocalReduceMotion.current
    val slots = 60
    val sorted = days.sortedBy { it.date }.takeLast(slots)
    val offset = slots - sorted.size
    val reveal = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (reveal.value < 1f) reveal.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 60f)) }
    val withData = sorted.count { it.upPercent != null }
    Canvas(
        Modifier.fillMaxWidth().height(30.dp).semantics {
            contentDescription = tr(R.string.last_days_days_with_data_with, slots, withData, sorted.count { (it.upPercent ?: 100.0) < 100.0 }, slots - withData)
        },
    ) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (slots - 1)) / slots
        val tail = 0.35f
        repeat(slots) { i ->
            val p = sorted.getOrNull(i - offset)?.upPercent
            val col = when {
                p == null -> c.off
                p >= 100.0 -> c.ok
                p >= 99.0 -> c.warn
                else -> c.bad
            }
            val grow = ((reveal.value * (1f + tail) - i / slots.toFloat()) / tail).coerceIn(0f, 1f)
            if (p == null) {
                drawCircle(col, w * 0.45f * grow, Offset(i * (w + gap) + w / 2, size.height / 2))
                return@repeat
            }
            val h = size.height * grow
            if (h > 0f) drawRoundRect(col, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}

fun friendlyName(s: UptimeService, snap: Snapshot?): String {
    if (s.kind == "http") return s.name
    val hit = snap?.projects?.firstNotNullOfOrNull { p ->
        p.services.firstOrNull { v -> v.appName != null && (s.name == v.appName || s.name.startsWith(v.appName)) }?.let { p to it }
    }
    return hit?.let { (p, v) -> "${p.name} · ${v.name}" } ?: s.name
}

private fun word(s: UptimeService) = when (s.current) { "up" -> tr(R.string.up); "down" -> tr(R.string.down); else -> tr(R.string.degraded) }
private fun tone(s: UptimeService) = when (s.current) { "up" -> Tone.Ok; "down" -> Tone.Signal; else -> Tone.Warn }

@Composable
private fun ServiceRows(list: List<UptimeService>, snap: Snapshot?, onPick: (UptimeService) -> Unit) {
    Card(Modifier.appear(), pad = 18) {
        list.forEachIndexed { i, svc ->
            val t = tone(svc)
            Column(Modifier.padding(top = if (i == 0) 0.dp else 10.dp).press({ onPick(svc) })) {
                SrvRow(
                    friendlyName(svc, snap).replace(".", ".\u200B"), titleSize = 16, left = { Mk(t) },
                    right = { RightText(svc.uptimePercent?.let { "%.2f%%".format(it) } ?: tr(R.string.no_data), if (t == Tone.Ok) H.c.ink else toneColor(t), 14) },
                )
                DayBars(svc.days)
            }
        }
    }
}

@Composable
internal fun UptimeTab(snap: Snapshot?) {
    val load = rememberLoad { Agent.uptime(60) }
    EveryWhileVisible(30_000) { load.reload() }
    var sel by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    var allSheet by remember { mutableStateOf(false) }
    val all = load.value?.services.orEmpty()
    val worstFirst = all.sortedWith(compareBy({ if (it.current == "down") 0 else if (it.current == "up") 2 else 1 }, { it.uptimePercent ?: 100.0 }))
    val s = all.firstOrNull { it.name == sel }

    Failed(load)
    if (load.value == null && load.error == null) repeat(2) { Skeleton(Modifier.padding(bottom = 12.dp).height(110.dp), RoundedCornerShape(24.dp)) }
    if (worstFirst.isNotEmpty()) ServiceRows(worstFirst.take(5), snap) { sel = it.name; visible = true }
    if (worstFirst.size > 5) Btn(tr(R.string.srv_all, worstFirst.size), { allSheet = true }, Modifier.padding(top = 12.dp))
    if (load.value?.services?.isEmpty() == true) Empty(tr(R.string.no_service_is_monitored_yet))
    K(tr(R.string.incidents), Modifier.padding(top = 16.dp, bottom = 4.dp))
    IncidentsList(3)

    Detail(allSheet, tr(R.string.uptime), { allSheet = false }) {
        listOf(tr(R.string.websites) to worstFirst.filter { it.kind == "http" }, tr(R.string.services_2) to worstFirst.filter { it.kind != "http" }).forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            K(title, Modifier.padding(top = 4.dp, bottom = 8.dp))
            ServiceRows(list, snap) { allSheet = false; sel = it.name; visible = true }
        }
        K(tr(R.string.incidents), Modifier.padding(top = 16.dp, bottom = 4.dp))
        IncidentsList()
    }
    Detail(visible, s?.let { friendlyName(it, snap) } ?: "", { visible = false }) { s?.let { UptimeDetail(it) } }
}

@Composable
private fun UptimeDetail(s: UptimeService) {
    val ctx = LocalContext.current
    val c = H.c
    Pill(word(s), tone(s))
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) { DayBars(s.days) }
    Stat(tr(R.string.uptime_60_days), s.uptimePercent?.let { "%.2f%%".format(it) } ?: tr(R.string.no_data))
    s.latencyMs?.let { Stat(tr(R.string.response_time), tr(R.string.ms, it.toInt())) }
    s.certExpiresAt?.let {
        val days = (it * 1000 - System.currentTimeMillis()) / 86_400_000
        Stat(tr(R.string.certificate_expires), if (days < 0) tr(R.string.expired) else tr(R.string.in_days, days), if (days < 14) c.warn else c.ink)
    }
    if (s.incidents.isEmpty()) Empty(tr(R.string.no_incident_in_this_period))
    s.incidents.sortedByDescending { it.start }.forEach { i ->
        val end = i.end?.times(1000)
        SrvRow(
            i.reason.ifEmpty { tr(R.string.down) },
            sub = "${day(i.start * 1000)} ${clock(i.start * 1000)}" + (end?.let { e -> " · " + (duration(i.start * 1000, e) ?: "") } ?: tr(R.string.still_down)),
            left = { Mk(if (end == null) Tone.Signal else Tone.Warn) },
            right = { Ico(Ph.Out, size = 18, tint = c.muted, desc = tr(R.string.share)) },
            onClick = {
                val dur = duration(i.start * 1000, end ?: System.currentTimeMillis()) ?: ""
                IncidentCard.share(
                    ctx, tr(R.string.was_down, s.name),
                    listOf(tr(R.string.started) to "${day(i.start * 1000)} ${clock(i.start * 1000)}", tr(R.string.ended) to (end?.let { "${day(it)} ${clock(it)}" } ?: tr(R.string.still_down_2)), tr(R.string.duration) to dur),
                    tr(R.string.share_down_summary, s.name, dur) + (i.reason.takeIf { it.isNotEmpty() }?.let { " " + tr(R.string.reason_label, it) } ?: ""),
                )
            },
        )
    }
}

@Composable
fun IncidentsList(limit: Int = Int.MAX_VALUE) {
    val load = rememberLoad { Ops.incidents(30) }
    var open by remember { mutableStateOf<String?>(null) }
    Failed(load)
    if (load.value?.isEmpty() == true) Empty(tr(R.string.h_inc_empty))
    load.value?.sortedByDescending { it.start }?.take(limit)?.forEach { i ->
        val ongoing = i.end == null
        SrvRow(
            i.services.joinToString(", ").ifEmpty { i.id },
            sub = "${day(i.start * 1000)} ${clock(i.start * 1000)} · " + if (ongoing) tr(R.string.h_inc_open) else duration(i.start * 1000, i.end!! * 1000).orEmpty(),
            subColor = if (ongoing) H.c.bad else H.c.muted, left = { Mk(if (ongoing) Tone.Signal else Tone.Ink) }, onClick = { open = if (open == i.id) null else i.id },
        )
        if (open == i.id) {
            if (i.cause.isNotEmpty()) Sub(tr(R.string.h_inc_cause, i.cause), Modifier.padding(start = 22.dp, bottom = 6.dp), size = 13)
            i.timeline.forEach { t -> SrvRow(t.title, Modifier.padding(start = 22.dp), titleSize = 13, right = { RightText(clock(t.at * 1000)) }) }
        }
    }
}
