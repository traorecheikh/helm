package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.countryName
import com.orvlabs.helm.data.*
import com.orvlabs.helm.isFrench
import com.orvlabs.helm.num
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun MailDomain.failing() = listOf("MX" to mx, "SPF" to spf, "DKIM" to dkim, "DMARC" to dmarc).filter { !it.second.ok }.map { it.first }

internal fun ScheduleJob.lastRun(): ScheduleRun? = deployments.maxByOrNull { it.createdAt.orEmpty() }

@Composable
internal fun CertsBody(load: Load<List<CertRec>>) {
    val c = H.c
    Failed(load)
    if (load.value?.isEmpty() == true) Empty(tr(R.string.hs_cert_none))
    load.value?.sortedBy { it.daysLeft ?: 9999.0 }?.forEach { cr ->
        val d = cr.daysLeft?.toInt() ?: 0
        val tone = if (d < 7) Tone.Signal else if (d < 21) Tone.Warn else Tone.Ok
        SrvRow(
            cr.domain, subLines = 2,
            sub = listOfNotNull(cr.issuer.ifEmpty { null }, tr(if (cr.autoRenew) R.string.h_cert_auto else R.string.h_cert_manual), if (!cr.routed) tr(R.string.h_cert_unrouted) else null).joinToString(" · "),
            left = { Mk(tone) }, right = { Pill(trn(R.plurals.h_cert_days, d), tone) },
        )
    }
}

@Composable
internal fun DnsBody(load: Load<List<DnsRec>>) {
    Failed(load)
    if (load.value?.isEmpty() == true) Empty(tr(R.string.hs_dns_none))
    load.value?.sortedBy { if (it.status in setOf("server", "proxied")) 1 else 0 }?.forEach { d ->
        val (word, tone) = when (d.status) {
            "server" -> R.string.h_dns_server to Tone.Ok
            "proxied" -> R.string.h_dns_proxied to Tone.Ok
            "elsewhere" -> R.string.h_dns_elsewhere to Tone.Warn
            else -> R.string.h_dns_missing to Tone.Signal
        }
        SrvRow(d.host, sub = tr(word) + d.a.firstOrNull()?.let { " · $it" }.orEmpty(), left = { Mk(tone) })
    }
}

@Composable
internal fun MailBody(load: Load<MailInfo>) {
    val c = H.c
    Failed(load)
    val m = load.value ?: return
    m.queue?.let { q -> Stat(tr(R.string.mail_queue), tr(R.string.h_mail_queue, q.size, q.deferred), if (q.deferred > 0) c.warn else c.ink) }
    if (m.domains.isEmpty()) Empty(tr(R.string.hs_mail_none))
    m.domains.sortedByDescending { it.failing().size }.forEach { d ->
        val checks = listOf("MX" to d.mx, "SPF" to d.spf, "DKIM" to d.dkim, "DMARC" to d.dmarc)
        K(d.name, Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(6.dp)) { checks.forEach { (n, ck) -> Pill(n, if (ck.ok) Tone.Ok else Tone.Warn) } }
        checks.filter { !it.second.ok }.forEach { (n, ck) ->
            Sub(tr(R.string.h_mail_fix, (if (isFrench) ck.fixFr else ck.fix) ?: ck.value.orEmpty()), Modifier.padding(top = 6.dp), color = c.ink)
        }
    }
}

@Composable
internal fun WebBody(load: Load<List<WebHost>>) {
    var open by remember { mutableStateOf<String?>(null) }
    val c = H.c
    Failed(load)
    val hosts = load.value ?: return
    if (hosts.sumOf { it.requests } == 0L) return Empty(tr(R.string.hs_web_none))
    hosts.sortedByDescending { it.requests }.forEach { h ->
        val expanded = open == h.host
        val bad = h.errorRate > 0.05
        SrvRow(
            h.host, sub = tr(R.string.h_web_sub, num(h.requests), pct(h.errorRate * 100, 1)), subColor = if (bad) c.warn else c.muted,
            left = { Mk(if (bad) Tone.Warn else Tone.Ok) },
            right = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    h.p95Ms?.let { RightText(ms(it), c.ink, 14) }
                    Ico(Ph.Next, Modifier.rotate(if (expanded) 90f else 0f), size = 16, tint = c.muted)
                }
            },
            onClick = { open = if (expanded) null else h.host },
        )
        if (expanded) HostDetail(h)
    }
}

private fun ms(v: Double?) = v?.let { tr(R.string.web_ms, num(it.toInt())) } ?: "–"

@Composable
private fun HostDetail(h: WebHost) {
    Column(Modifier.padding(start = 22.dp, bottom = 12.dp)) {
        Stat(tr(R.string.web_p50), ms(h.p50Ms))
        Stat(tr(R.string.web_p95), ms(h.p95Ms))
        Stat(tr(R.string.web_visitors), num(h.uniqueVisitors))
        Stat(tr(R.string.web_errors), num(h.errors5xx))
        if (h.topPaths.isNotEmpty()) K(tr(R.string.web_top_paths), Modifier.padding(top = 10.dp, bottom = 4.dp))
        h.topPaths.take(5).forEach { PathRow(it.path, num(it.count)) }
        if (h.slowestPaths.isNotEmpty()) K(tr(R.string.web_slow_paths), Modifier.padding(top = 10.dp, bottom = 4.dp))
        h.slowestPaths.take(5).forEach { PathRow(it.path, ms(it.p95Ms ?: it.avgMs)) }
        if (h.countries.isNotEmpty()) K(tr(R.string.web_countries), Modifier.padding(top = 10.dp, bottom = 4.dp))
        h.countries.take(5).forEach { PathRow("${flag(it.code)}  ${countryName(it.code, it.code)}", num(it.count.coerceAtLeast(it.hits)), mono = false) }
    }
}

@Composable
private fun PathRow(label: String, value: String, mono: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        Txt(label, Modifier.weight(1f), size = if (mono) 13 else 14, mono = mono, maxLines = 2)
        RightText(value, H.c.ink, 13)
    }
}

private fun patternLabel(key: String) = tr(
    when (key) {
        "aws_key" -> R.string.sec_aws_key
        "private_key" -> R.string.sec_private_key
        "stripe_live" -> R.string.sec_stripe
        "github_token" -> R.string.sec_github
        "slack_token" -> R.string.sec_slack
        "jwt" -> R.string.sec_jwt
        else -> R.string.sec_generic
    },
)

@Composable
internal fun SecretsBody(load: Load<List<SecretFinding>>) {
    Failed(load)
    val list = load.value ?: return
    if (list.isEmpty()) return Empty(tr(R.string.h_secrets_none))
    list.sortedByDescending { it.lastSeen }.forEach { f ->
        SrvRow(
            patternLabel(f.pattern), subLines = 2,
            sub = tr(R.string.sec_sub, taskName(f.container), trn(R.plurals.times_n, f.count), agoFull(f.lastSeen * 1000)),
            left = { Mk(Tone.Signal) }, right = { RightText(f.sample, H.c.ink, 13) },
        )
    }
}

@Composable
internal fun LogsBody(load: Load<Logwatch>) {
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    Failed(load)
    val lw = load.value ?: return
    val s = lw.settings
    ToggleRow(tr(R.string.h_lw_enabled), tr(R.string.h_lw_burst, s.burst), s.enabled) { on ->
        guard(tr(R.string.h_logwatch)) { scope.launch { try { Ops.setLogwatch(s.burst, on); load.reload() } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject) } } }
    }
    if (lw.detections.isEmpty()) return Empty(tr(R.string.h_lw_none))
    lw.detections.forEach { Detection(it) }
}

@Composable
private fun Detection(e: Event) {
    var open by remember { mutableStateOf(false) }
    val c = H.c
    Column(Modifier.fillMaxWidth().press({ open = !open }).padding(vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Mk(Tone.Warn)
            Txt(e.titleText().orEmpty(), Modifier.weight(1f), size = 15, weight = 500, maxLines = 2)
            RightText(ago(e.at * 1000), c.muted, 13)
        }
        e.messageText()?.takeIf { it.isNotBlank() }?.let {
            Txt(it, Modifier.padding(start = 22.dp, top = 4.dp), size = 12, mono = true, color = c.muted, maxLines = if (open) 12 else 2)
        }
    }
}

@Composable
internal fun JobsBody(load: Load<List<ScheduleJob>>) {
    val c = H.c
    Failed(load)
    val jobs = load.value ?: return
    if (jobs.isEmpty()) return Empty(tr(R.string.h_sched_none))
    jobs.groupBy { it.projectName.orEmpty() }.toSortedMap().forEach { (project, list) ->
        K(project.ifEmpty { tr(R.string.mem_no_project) }, Modifier.padding(top = 14.dp))
        list.sortedBy { it.name.lowercase() }.forEach { j ->
            val run = j.lastRun()
            val failed = run?.status == "error"
            SrvRow(
                j.name.ifEmpty { j.serviceName.orEmpty() }, subLines = 2,
                sub = listOfNotNull(j.serviceName, j.cronExpression + (j.timezone?.let { " ($it)" } ?: "")).joinToString(" · "),
                left = { Mk(if (!j.enabled) Tone.Ring else if (failed) Tone.Warn else Tone.Ok) },
                right = {
                    RightText(
                        when {
                            !j.enabled -> tr(R.string.sched_paused)
                            run == null -> tr(R.string.sched_never)
                            failed -> tr(R.string.sched_failed, agoFull(isoMs(run.createdAt)))
                            else -> agoFull(isoMs(run.createdAt))
                        },
                        if (failed) c.warn else c.ink, 13,
                    )
                },
            )
        }
    }
}
