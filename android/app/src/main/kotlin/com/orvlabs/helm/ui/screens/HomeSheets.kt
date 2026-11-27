package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.orvlabs.helm.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.produceState
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.alertKey
import com.orvlabs.helm.ml.Triage
import com.orvlabs.helm.ml.TriageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.orvlabs.helm.BuildConfig
import com.orvlabs.helm.data.AppJson
import com.orvlabs.helm.data.Net
import java.io.IOException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import com.orvlabs.helm.data.Config
import com.orvlabs.helm.data.Event
import com.orvlabs.helm.data.Ops
import com.orvlabs.helm.data.Overnight
import com.orvlabs.helm.data.Report
import com.orvlabs.helm.data.isFailure
import com.orvlabs.helm.data.messageText
import com.orvlabs.helm.data.reasonText
import com.orvlabs.helm.data.titleText
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.DeployR
import com.orvlabs.helm.ui.SettingsR
import com.orvlabs.helm.ui.ShieldR
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H

fun eventTitle(e: Event) = if (e.type == "ban") tr(R.string.banned_2, e.ip) else e.titleText().orEmpty().ifEmpty { e.kind.orEmpty().replace('_', ' ') }
fun eventSub(e: Event) = (if (e.type == "ban") e.reasonText() else e.messageText()).orEmpty()

@Composable
internal fun ServersSheet(visible: Boolean, cfg: Config?, onClose: () -> Unit, onAdd: () -> Unit, onPick: (Int) -> Unit) {
    SheetFrame(visible, onClose) {
        H2(tr(R.string.switch_server))
        cfg?.servers?.forEach { sv ->
            val active = sv.id == cfg.activeId
            Li(
                sv.name, left = { Mk(if (active) Tone.Ok else Tone.Ring) },
                right = { if (active) Ico(Ph.Check, size = 18, tint = H.c.ok) },
                onClick = { if (active) onClose() else onPick(sv.id) },
            )
        }
        Li(tr(R.string.add_a_server), left = { IconTile(Ph.Plus) }, onClick = onAdd)
    }
}

@Composable
internal fun AlertsSheet(visible: Boolean, nav: NavController, onClose: () -> Unit) {
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) seen = true }
    SheetFrame(visible, onClose) { if (seen) AlertsBody(nav, onClose) }
}

@Composable
private fun AlertsBody(nav: NavController, close: () -> Unit) {
    val load = rememberLoad { Agent.history().sortedByDescending { it.at } }
    val night = rememberLoad { Overnight.build() }
    var tab by remember { mutableIntStateOf(0) }
    val all = load.value.orEmpty()
    val shown = when (tab) {
        1 -> all.filter { it.isFailure() }
        2 -> all.filter { it.type == "dokploy" && it.kind in setOf("deploy", "build_error") }
        3 -> all.filter { it.type == "ban" }
        else -> all
    }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
        H2(tr(R.string.alerts))
        night.error?.let { ErrorLine(it) }
        night.value?.let { Night(it) }
        Chips(
            listOf(tr(R.string.all_2), tr(R.string.failures), tr(R.string.deploys), tr(R.string.shield), tr(R.string.h_tab_incidents)),
            tab, { tab = it }, mapOf(1 to all.count { it.isFailure() }),
        )
        if (tab == 4) return@Column Incidents()
        when {
            load.error != null -> ErrorLine(load.error)
            load.loading -> Sub(tr(R.string.loading), Modifier.padding(top = 18.dp))
            shown.isEmpty() -> Row(Modifier.padding(top = 18.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                Ico(Ph.Bell, size = 20, tint = H.c.muted)
                Sub(tr(R.string.home_empty), size = 14)
            }
        }
        val triage = produceState<Triage?>(null) { value = withContext(Dispatchers.IO) { TriageStore.model } }.value
        val alerts = Store.config.collectAsStateWithLifecycle().value?.alerts
        LaunchedEffect(triage, all) { if (triage?.shown(all) == true) TriageStore.save() }
        var expanded by remember { mutableStateOf(setOf<String>()) }
        shown.groupBy { day(it.at * 1000) }.forEach { (d, events) ->
            K(d, Modifier.padding(top = 14.dp))
            val (quiet, loud) = if (triage == null) emptyList<Event>() to events else events.partition { triage.quiet(it, alerts != null && it.alertKey() !in alerts) }
            (triage?.order(loud) ?: loud).forEach { EventRow(it, nav, close, triage) }
            if (quiet.isEmpty()) return@forEach
            if (d in expanded) quiet.forEach { EventRow(it, nav, close, triage) }
            else Li(
                trn(R.plurals.ml_quiet, quiet.size), titleSize = 14, left = { Mk(Tone.Ring) },
                right = { Ico(Ph.Next, Modifier.rotate(90f), size = 14, tint = H.c.muted) },
                onClick = { expanded = expanded + d; triage?.opened(quiet); TriageStore.save() },
            )
        }
    }
}

@Composable
private fun EventRow(e: Event, nav: NavController, close: () -> Unit, triage: Triage?) {
    val go: (() -> Unit)? = when {
        e.kind == "build_error" -> ({ nav.navigate(DeployR(failed = true)) })
        e.type == "ban" && e.ip != null -> ({ nav.navigate(ShieldR(ip = e.ip)) })
        e.kind == "pair_request" -> ({ nav.navigate(SettingsR) })
        else -> null
    }
    Li(
        eventTitle(e), sub = eventSub(e),
        left = { Mk(if (e.isFailure()) Tone.Signal else Tone.Ink) },
        right = { RightText(clock(e.at * 1000)) },
        onClick = go?.let { { triage?.opened(listOf(e)); TriageStore.save(); close(); it() } },
    )
}

@Composable
private fun Night(r: Report) {
    val c = H.c
    Card(Modifier.padding(top = 14.dp), color = c.raise) {
        K(tr(R.string.since, day(r.since), clock(r.since)))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), Arrangement.spacedBy(12.dp)) {
            listOf(R.string.deploys to r.deploys, R.string.failed_deploys to r.failed, R.string.new_bans to r.bans).forEach { (label, n) ->
                Column(Modifier.weight(1f)) {
                    Txt("$n", size = 26, weight = 700, color = if (label == R.string.failed_deploys && n > 0) c.bad else c.ink)
                    Sub(tr(label), size = 12)
                }
            }
        }
        r.downs.forEach { Row(Modifier.padding(top = 10.dp), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) { Mk(Tone.Signal); Sub(it, size = 14, color = c.ink) } }
        r.diskDays?.let { Pill("${tr(R.string.disk)} · ${trn(R.plurals.full_in_about, it)}", Tone.Warn, Modifier.padding(top = 10.dp)) }
        if (r.downs.isEmpty()) Pill(tr(R.string.nothing_went_down), Tone.Ok, Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun Incidents() {
    val load = rememberLoad { Ops.incidents(30) }
    var open by remember { mutableStateOf<String?>(null) }
    Failed(load)
    if (load.value?.isEmpty() == true) Sub(tr(R.string.h_inc_empty), Modifier.padding(top = 18.dp), size = 14)
    load.value?.sortedByDescending { it.start }?.forEach { i ->
        val ongoing = i.end == null
        Li(
            i.services.joinToString(", ").ifEmpty { i.id },
            sub = "${day(i.start * 1000)} ${clock(i.start * 1000)} · " + if (ongoing) tr(R.string.h_inc_open) else duration(i.start * 1000, i.end!! * 1000).orEmpty(),
            subColor = if (ongoing) H.c.bad else H.c.muted, left = { Mk(if (ongoing) Tone.Signal else Tone.Ink) }, onClick = { open = if (open == i.id) null else i.id },
        )
        if (open == i.id) {
            if (i.cause.isNotEmpty()) Sub(tr(R.string.h_inc_cause, i.cause), Modifier.padding(start = 16.dp, bottom = 6.dp))
            i.timeline.forEach { t -> Li(t.title, Modifier.padding(start = 16.dp), titleSize = 13, right = { RightText(clock(t.at * 1000)) }) }
        }
    }
}

private var releaseChecked = false
private var newerTag: String? = null

private fun isNewer(tag: String, current: String): Boolean {
    val a = tag.removePrefix("v").split('.').map { it.toIntOrNull() ?: return false }
    val b = current.split('.').map { it.toIntOrNull() ?: return false }
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

private suspend fun newerRelease(): String? = try {
    runInterruptible(Dispatchers.IO) {
        val req = Request.Builder().url("https://api.github.com/repos/traorecheikh/helm/releases/latest").header("Accept", "application/vnd.github+json").build()
        Net.client.newCall(req).execute().use { r ->
            if (r.isSuccessful) AppJson.parseToJsonElement(r.body.string()).jsonObject["tag_name"]?.jsonPrimitive?.content else null
        }
    }?.takeIf { isNewer(it, BuildConfig.VERSION_NAME) }
} catch (e: IOException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

@Composable
internal fun HomeUpdate(modifier: Modifier) {
    var tag by remember { mutableStateOf(newerTag) }
    LaunchedEffect(Unit) {
        if (releaseChecked) return@LaunchedEffect
        releaseChecked = true
        newerTag = newerRelease()
        tag = newerTag
    }
    val t = tag ?: return
    val ctx = LocalContext.current
    val open: () -> Unit = {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/traorecheikh/helm/releases/latest/download/helm.apk".toUri()))
        } catch (e: ActivityNotFoundException) {
        }
    }
    Banner(tr(R.string.home_update, t.removePrefix("v")), Ph.Download, modifier.press(open))
}

@Composable
internal fun HomeMail() {
    val load = rememberLoad { Ops.mailActivity() }
    EveryWhileVisible(60_000) { load.reload() }
    var open by remember { mutableStateOf(false) }
    load.error?.let { ErrorLine(it) }
    val m = load.value ?: return
    val c = H.c
    val last = m.lastInboundAt
    val stale = last == null || System.currentTimeMillis() - last * 1000 > 6 * 3_600_000L
    val tone = when {
        m.logFound && m.outboundFailed24h > 0 -> Tone.Signal
        !m.logFound || stale || m.rejected24h > m.accepted24h -> Tone.Warn
        else -> Tone.Ok
    }
    Card(Modifier.appear(), onClick = { open = !open }, pad = 14) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Mk(tone)
            Txt(tr(R.string.mail), Modifier.weight(1f), size = 15, weight = 600)
            if (m.logFound) RightText(last?.let { ago(it * 1000) } ?: tr(R.string.ml_never))
            Ico(Ph.Next, Modifier.rotate(if (open) -90f else 90f), size = 14, tint = c.muted)
        }
        if (!open) return@Card
        if (!m.logFound) return@Card Sub(tr(R.string.ml_no_log), Modifier.padding(top = 8.dp), color = c.warn)
        Stat(tr(R.string.ml_last_inbound), last?.let { agoFull(it * 1000) } ?: tr(R.string.ml_never), if (stale) c.warn else c.ink)
        Stat(tr(R.string.ml_inbound), tr(R.string.ml_inbound_v, m.accepted24h, m.rejected24h), if (m.rejected24h > m.accepted24h) c.warn else c.ink)
        Stat(tr(R.string.ml_outbound), tr(R.string.ml_outbound_v, m.delivered24h, m.outboundFailed24h), if (m.outboundFailed24h > 0) c.bad else c.ink)
        Stat(tr(R.string.ml_auth), tr(R.string.ml_auth_v, m.authBans24h, m.authFailures24h))
        m.recentRejections.take(3).forEach { e ->
            Li(
                e.domain.ifEmpty { e.ip }, mono = true, titleSize = 13,
                sub = listOf(e.event.removePrefix("smtp."), e.ip).filter { it.isNotEmpty() }.joinToString(" · "),
                right = { RightText(ago(e.at * 1000)) },
            )
        }
    }
}
