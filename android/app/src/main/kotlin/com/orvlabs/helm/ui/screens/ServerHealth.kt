package com.orvlabs.helm.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.*
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion

internal enum class Sec(val short: Int, val title: Int, val icon: ImageVector) {
    Updates(R.string.srv_updates, R.string.h_updates, Ph.Download),
    Certs(R.string.srv_certs, R.string.h_certs, Ph.Key),
    Dns(R.string.h_dns, R.string.h_dns, Ph.Globe),
    Mail(R.string.h_mail, R.string.h_mail, Ph.Bell),
    Web(R.string.srv_web, R.string.h_web, Ph.Pulse),
    Secrets(R.string.srv_secrets, R.string.h_secrets, Ph.Fingerprint),
    Logs(R.string.srv_logs, R.string.h_logwatch, Ph.Scroll),
    Jobs(R.string.srv_jobs, R.string.h_schedules, Ph.Refresh),
    Files(R.string.srv_files, R.string.h_watchdog, Ph.Edit),
    Disk(R.string.disk, R.string.h_disk, Ph.Broom),
    Docker(R.string.srv_docker, R.string.maintenance, Ph.Cube),
    Backups(R.string.backups, R.string.backups, Ph.Database),
    Audit(R.string.aud_short, R.string.aud_title, Ph.Shield),
}

internal class Checks(
    val updates: Load<Updates>, val files: Load<List<WatchFile>>, val disk: Load<DiskPreview>, val certs: Load<List<CertRec>>,
    val dns: Load<List<DnsRec>>, val web: Load<List<WebHost>>, val mail: Load<MailInfo>, val secrets: Load<List<SecretFinding>>,
    val logs: Load<Logwatch>, val jobs: Load<List<ScheduleJob>>, val dests: Load<List<Destination>>,
    val vulns: Load<Vulns>,
)

private typealias Vt = Pair<String, Tone>

private enum class St { Wait, Fail, Empty, Data }

private class Item(val sec: Sec, val value: String, val tone: Tone, val st: St)

private fun <T> Load<T>.item(sec: Sec, f: (T) -> Vt): Item {
    val v = value ?: return Item(sec, "", Tone.Ring, if (error != null) St.Fail else St.Wait)
    val (text, tone) = f(v)
    return Item(sec, text, tone, if (text == "–") St.Empty else St.Data)
}

private val OK get() = tr(R.string.ok_word)

private fun updatesVt(u: Updates): Vt {
    val sec = u.pending.count { it.group == "security" }
    val at = u.scheduledReboot ?: u.nextReboot?.takeIf { u.rebootRequired }
    val tone = if (sec > 0 || (u.rebootRequired && at == null)) Tone.Warn else if (u.pending.isNotEmpty() || u.rebootRequired) Tone.Ink else Tone.Ok
    return when {
        u.rebootRequired -> tr(R.string.reboot)
        u.pending.isEmpty() -> OK
        else -> "${u.pending.size}"
    } to tone
}

private fun certsVt(list: List<CertRec>): Vt {
    val first = list.filter { it.daysLeft != null }.minByOrNull { it.daysLeft!! } ?: return "–" to Tone.Ring
    val days = first.daysLeft!!.toInt()
    return tr(R.string.time_d, days.coerceAtLeast(0)) to (if (days < 7) Tone.Signal else if (days < 21) Tone.Warn else Tone.Ok)
}

private fun dnsVt(list: List<DnsRec>): Vt {
    val missing = list.count { it.status !in setOf("server", "proxied", "elsewhere") }
    val elsewhere = list.count { it.status == "elsewhere" }
    return when {
        list.isEmpty() -> "–" to Tone.Ring
        missing > 0 -> "$missing" to Tone.Signal
        elsewhere > 0 -> "$elsewhere" to Tone.Warn
        else -> OK to Tone.Ok
    }
}

private fun webVt(hosts: List<WebHost>): Vt {
    val req = hosts.sumOf { it.requests }
    if (req == 0L) return "–" to Tone.Ring
    val worst = hosts.filter { it.requests >= 20 }.maxByOrNull { it.errorRate }
    if (worst != null && worst.errorRate > 0.05) return pct(worst.errorRate * 100, 1) to Tone.Warn
    return pct(hosts.sumOf { it.errors5xx } * 100.0 / req, 1) to Tone.Ok
}

private fun mailVt(m: MailInfo): Vt {
    val bad = m.domains.count { it.failing().isNotEmpty() }
    val deferred = m.queue?.deferred ?: 0
    return when {
        bad > 0 -> "$bad" to Tone.Warn
        deferred > 0 -> "$deferred" to Tone.Warn
        m.domains.isEmpty() -> "–" to Tone.Ring
        else -> OK to Tone.Ok
    }
}

private fun logsVt(l: Logwatch): Vt {
    if (!l.settings.enabled) return tr(R.string.srv_off) to Tone.Ring
    val recent = l.detections.count { it.at * 1000 > System.currentTimeMillis() - 86_400_000 }
    return if (recent > 0) "$recent" to Tone.Warn else OK to Tone.Ok
}

private fun jobsVt(jobs: List<ScheduleJob>): Vt {
    if (jobs.isEmpty()) return "–" to Tone.Ring
    val failed = jobs.count { it.enabled && it.lastRun()?.status == "error" }
    return if (failed > 0) "$failed" to Tone.Warn else "${jobs.size}" to Tone.Ok
}

private fun filesVt(files: List<WatchFile>): Vt {
    val n = files.sumOf { it.changes.size }
    return if (n == 0) OK to Tone.Ok else "$n" to Tone.Ink
}

private fun diskVt(d: DiskPreview): Vt {
    val total = d.totalBytes.takeIf { it > 0 } ?: d.items.sumOf { it.bytes }
    return if (total < 200L * 1_048_576) OK to Tone.Ok else size(total) to Tone.Ink
}

private fun rank(t: Tone) = when (t) { Tone.Signal -> 0; Tone.Warn -> 1; else -> 2 }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HealthTab(host: HostInfo?, snap: Snapshot?) {
    val ck = Checks(
        rememberLoad { Ops.updates() }, rememberLoad { Ops.watchdog() }, rememberLoad { Ops.diskPreview() }, rememberLoad { Ops.certs() },
        rememberLoad { Ops.dns() }, rememberLoad { Ops.web() }, rememberLoad { Ops.mail() }, rememberLoad { Ops.secrets() },
        rememberLoad { Ops.logwatch() }, rememberLoad { Ops.schedules() }, rememberLoad { if (dokployActive) Dokploy.destinations() else emptyList() },
        rememberLoad { VulnsApi.get() },
    )
    var shown by remember { mutableStateOf(Sec.Updates) }
    var visible by remember { mutableStateOf(false) }
    val items = Sec.entries.filter { it != Sec.Backups || dokployActive }.map { sec ->
        when (sec) {
            Sec.Updates -> ck.updates.item(sec, ::updatesVt)
            Sec.Certs -> ck.certs.item(sec, ::certsVt)
            Sec.Dns -> ck.dns.item(sec, ::dnsVt)
            Sec.Mail -> ck.mail.item(sec, ::mailVt)
            Sec.Web -> ck.web.item(sec, ::webVt)
            Sec.Secrets -> ck.secrets.item(sec) { if (it.isEmpty()) OK to Tone.Ok else "${it.size}" to Tone.Signal }
            Sec.Logs -> ck.logs.item(sec, ::logsVt)
            Sec.Jobs -> ck.jobs.item(sec, ::jobsVt)
            Sec.Files -> ck.files.item(sec, ::filesVt)
            Sec.Disk -> ck.disk.item(sec, ::diskVt)
            Sec.Docker -> host?.dockerDisk?.let { Item(sec, size(it.imagesBytes + it.buildCacheBytes), Tone.Ink, St.Data) } ?: Item(sec, "", Tone.Ring, St.Wait)
            Sec.Backups -> ck.dests.item(sec) { if (it.isEmpty()) "0" to Tone.Warn else "${it.size}" to Tone.Ok }
            Sec.Audit -> ck.vulns.item(sec, ::vulnsVt)
        }
    }
    val open = { sec: Sec -> shown = sec; visible = true }
    val attention = items.filter { it.st == St.Data && it.tone in setOf(Tone.Signal, Tone.Warn) }.sortedBy { rank(it.tone) }
    val calm = items.filter { it.st == St.Data && it !in attention }
    val failed = items.filter { it.st == St.Fail }
    val waiting = items.any { it.st == St.Wait }

    if (attention.isNotEmpty()) {
        attention.forEach { AttentionCard(it) { open(it.sec) } }
    }
    if (calm.isNotEmpty() || failed.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth().appear(), Arrangement.spacedBy(8.dp), Arrangement.spacedBy(0.dp)) {
            (calm + failed).forEach { StatusChip(it) { open(it.sec) } }
        }
    }
    if (waiting) {
        if (attention.isEmpty() && calm.isEmpty() && failed.isEmpty()) repeat(3) { Skeleton(Modifier.padding(bottom = 10.dp).height(68.dp), RoundedCornerShape(24.dp)) }
        else FlowRow(Modifier.padding(top = 8.dp), Arrangement.spacedBy(8.dp), Arrangement.spacedBy(8.dp)) {
            repeat(2) { Skeleton(Modifier.size(width = 96.dp, height = 40.dp), CircleShape) }
        }
    }

    Detail(visible, tr(shown.title), { visible = false }) {
        when (shown) {
            Sec.Updates -> UpdatesBody(ck.updates)
            Sec.Certs -> CertsBody(ck.certs)
            Sec.Dns -> DnsBody(ck.dns)
            Sec.Mail -> MailBody(ck.mail)
            Sec.Web -> WebBody(ck.web)
            Sec.Secrets -> SecretsBody(ck.secrets)
            Sec.Logs -> LogsBody(ck.logs)
            Sec.Jobs -> JobsBody(ck.jobs)
            Sec.Files -> FilesBody(ck.files)
            Sec.Disk -> DiskBody(ck.disk)
            Sec.Docker -> DockerBody(host?.dockerDisk)
            Sec.Backups -> BackupsBody(ck.dests, snap)
            Sec.Audit -> VulnsBody(ck.vulns)
        }
    }
}

@Composable
private fun AttentionCard(it: Item, onClick: () -> Unit) {
    Card(Modifier.padding(bottom = 10.dp).appear(), onClick, pad = 14) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(toneSoft(it.tone)), Alignment.Center) { Ico(it.sec.icon, size = 20, tint = toneColor(it.tone)) }
            Txt(tr(it.sec.short), Modifier.weight(1f), size = 16, weight = 600, maxLines = 2)
            Txt(it.value, size = 18, weight = 700, color = toneColor(it.tone))
            Mk(it.tone)
        }
    }
}

@Composable
private fun StatusChip(it: Item, onClick: () -> Unit) {
    val c = H.c
    Box(Modifier.heightIn(min = 48.dp).press(onClick), Alignment.Center) {
        Row(
            Modifier.clip(CircleShape).background(c.card, CircleShape).border(1.dp, c.line, CircleShape).padding(horizontal = 14.dp, vertical = 9.dp),
            Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
        ) {
            Mk(if (it.st == St.Fail) Tone.Ring else it.tone)
            Txt(tr(it.sec.short), size = 14, weight = 600, maxLines = 2)
            if (it.value.isNotEmpty() && it.value != OK) Txt(it.value, size = 13, weight = 600, color = c.muted)
        }
    }
}

@Composable
internal fun Skeleton(modifier: Modifier, shape: androidx.compose.ui.graphics.Shape) {
    val c = H.c
    val a = if (LocalReduceMotion.current) 0.6f
    else rememberInfiniteTransition(label = "sk").animateFloat(0.35f, 0.85f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a").value
    Box(modifier.fillMaxWidth().clip(shape).background(c.line.copy(alpha = a)))
}
