package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.continentName
import com.orvlabs.helm.countryName
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Attacker
import com.orvlabs.helm.data.BanState
import com.orvlabs.helm.data.Geo
import com.orvlabs.helm.data.LogLine
import com.orvlabs.helm.data.individuallyBlocked
import com.orvlabs.helm.data.reasonText
import com.orvlabs.helm.ml.VerdictPill
import com.orvlabs.helm.ml.rememberVerdict
import com.orvlabs.helm.num
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun shieldContinents(g: Geo) = g.continents.filter { it.blocked }.map { it.name }.toSet()

private fun source(s: String) = when (s) {
    "ssh" -> "SSH"
    "web" -> "Web"
    "mail" -> "Mail"
    "manual" -> tr(R.string.shd_manual)
    else -> s
}

@Composable
internal fun ShieldSheets(sheet: Sh?, kit: ShieldKit, live: List<Attacker>?, geo: Geo?, trusted: Set<String>) {
    var last by remember { mutableStateOf<Sh?>(null) }
    if (sheet != null) last = sheet
    SheetFrame(sheet != null, kit.close) {
        when (val k = last) {
            is Sh.Ip -> IpBody(k.ip, live?.firstOrNull { it.ip == k.ip }, kit, trusted)
            is Sh.Country -> if (geo != null) CountryBody(k.code, geo, kit)
            is Sh.Continent -> if (geo != null) {
                val n = geo.countries.filter { it.continent == k.name }.sumOf { it.attempts24h }
                val name = continentName(k.name)
                ShieldConfirm(
                    tr(R.string.shd_block_t, name), tr(R.string.shd_cont_line, geo.continents.firstOrNull { it.name == k.name }?.countries ?: 0, num(n)),
                    tr(R.string.shd_hold_block), 1200,
                    onConfirm = { kit.close(); kit.run(tr(R.string.shd_block_l, name)) { Agent.setBlock(individuallyBlocked(geo), shieldContinents(geo) + k.name) } },
                    onCancel = kit.close,
                )
            }
            is Sh.Origin -> ShieldConfirm(
                tr(R.string.shd_origin_t),
                if (k.unproxied.isEmpty()) tr(R.string.shd_origin_ok) else tr(R.string.shd_origin_warn, k.unproxied.joinToString(", ")),
                tr(R.string.shd_hold_on), 3000,
                onConfirm = { kit.close(); kit.run(tr(R.string.shd_origin)) { Agent.setOriginLock(true) } },
                onCancel = kit.close,
            )
            Sh.Lock -> LockBody(kit)
            null -> Unit
        }
    }
}

@Composable
private fun ShieldConfirm(
    title: String, line: String, hold: String, holdMs: Int, onConfirm: () -> Unit, onCancel: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    H2(title)
    Sub(line, Modifier.padding(top = 8.dp), size = 14)
    extra?.invoke()
    Box(Modifier.height(20.dp))
    HoldButton(hold, holdMs, onConfirm)
    Box(Modifier.height(8.dp))
    Btn(tr(R.string.cancel), onCancel)
}

@Composable
private fun LockBody(kit: ShieldKit) {
    val choices = listOf(tr(R.string.shd_30m) to 30, tr(R.string.shd_1h) to 60, tr(R.string.shd_3h) to 180)
    var choice by remember { mutableIntStateOf(1) }
    ShieldConfirm(
        tr(R.string.shd_lock_t), tr(R.string.shd_lock_line), tr(R.string.shd_hold_3s), 3000,
        onConfirm = { kit.close(); kit.run(tr(R.string.shd_lock), Feel.Seal) { Agent.setPanic(choices[choice].second) } },
        onCancel = kit.close,
        extra = { Box(Modifier.padding(top = 16.dp)) { Chips(choices.map { it.first }, choice, { choice = it }) } },
    )
}

@Composable
private fun CountryBody(code: String, geo: Geo, kit: ShieldKit) {
    val country = geo.countries.firstOrNull { it.code == code } ?: return
    val name = countryName(country.code, country.name)
    val continentBlocked = geo.continents.firstOrNull { it.name == country.continent }?.blocked == true
    val countries = individuallyBlocked(geo)
    val continents = shieldContinents(geo)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(flag(country.code), size = 36)
        Column {
            H2(name)
            Pill(continentName(country.continent), Tone.Ink, Modifier.padding(top = 4.dp))
        }
    }
    Row(Modifier.padding(top = 18.dp), Arrangement.spacedBy(36.dp)) {
        Column { DotNum(num(country.attempts24h), 64); Txt(tr(R.string.shd_attempts), size = 12, weight = 600, color = H.c.muted) }
        Column { DotNum(num(country.bans), 64); Txt(tr(R.string.shd_t_bans), size = 12, weight = 600, color = H.c.muted) }
    }
    val recent = geo.recent.filter { it.country == country.code }.take(4)
    if (recent.isNotEmpty()) Column(Modifier.padding(top = 8.dp)) {
        recent.forEach { r ->
            Li(r.ip, mono = true, titleSize = 14, sub = source(r.source), right = { RightText(ago(r.at * 1000)) }, onClick = { kit.open(Sh.Ip(r.ip)) })
        }
    }
    if (!kit.owner) return
    Box(Modifier.padding(top = 12.dp)) {
        when {
            country.isProtected || country.code == "SN" -> Pill(tr(R.string.shd_home), Tone.Ok)
            continentBlocked -> Pill(tr(R.string.shd_with_continent, continentName(country.continent)), Tone.Warn)
            country.blocked -> Btn(tr(R.string.shd_unblock, name), {
                kit.close()
                kit.run(tr(R.string.shd_unblock, name)) { Agent.setBlock(countries - country.code, continents) }
            }, style = BtnStyle.Ink)
            else -> Column {
                HoldButton(tr(R.string.shd_hold_c, country.code), 1200) {
                    kit.close()
                    kit.run(tr(R.string.shd_block_l, name)) { Agent.setBlock(countries + country.code, continents) }
                }
                Sub(tr(R.string.shd_phones_ok), Modifier.padding(top = 8.dp), size = 12)
            }
        }
    }
}

private enum class Ask { Unban, Perm, You }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IpBody(ip: String, a: Attacker?, kit: ShieldKit, trusted: Set<String>) {
    val load = rememberLoad(ip) { Agent.ip(ip) }
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    var ask by remember(ip) { mutableStateOf<Ask?>(null) }
    var res by remember(ip) { mutableStateOf<BanState?>(null) }
    var err by remember(ip) { mutableStateOf<String?>(null) }
    val d = load.value
    val verdict = rememberVerdict(d, a, trusted)
    val you = d?.probablyYou == true || a?.probablyYou == true || verdict?.trusted == true

    fun act(label: String, call: suspend () -> BanState) = guard(label) {
        scope.launch {
            try { res = call(); err = null } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); err = e.message }
            load.reload()
            kit.refresh()
        }
    }

    when (ask) {
        Ask.Unban -> return ShieldConfirm(
            tr(R.string.shd_unban_t, ip), tr(R.string.shd_unban_line), tr(R.string.shd_hold_unban), 1200,
            onConfirm = { ask = null; act(tr(R.string.shd_unban_l, ip)) { Agent.unban(ip) } }, onCancel = { ask = null },
        )
        Ask.Perm, Ask.You -> return ShieldConfirm(
            if (ask == Ask.Perm) tr(R.string.shd_perm_t, ip) else tr(R.string.shd_you_t),
            listOfNotNull(if (ask == Ask.Perm) tr(R.string.shd_perm_line) else null, if (you) tr(R.string.shd_you_line) else null).joinToString("\n"),
            tr(R.string.shd_hold_ban), 3000,
            onConfirm = {
                val perm = ask == Ask.Perm
                ask = null
                act(tr(R.string.shd_ban_l, ip)) { Agent.ban(ip, if (perm) "permanent" else "24h", ownNetwork = true) }
            },
            onCancel = { ask = null },
        )
        null -> Unit
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(flag(d?.country.orEmpty()), size = 34)
        Txt(ip, size = 22, weight = 600, mono = true, tracking = -0.02f, maxLines = 1)
    }
    Failed(load)
    if (d != null) {
        FlowRow(Modifier.padding(top = 14.dp), Arrangement.spacedBy(8.dp), Arrangement.spacedBy(8.dp)) {
            when {
                d.banned -> Pill(tr(R.string.shd_banned), Tone.Signal)
                d.allowed -> Pill(tr(R.string.shd_allowed), Tone.Ok)
                d.onBlocklist -> Pill(tr(R.string.shd_blocklist), Tone.Warn)
                else -> Pill(tr(R.string.shd_open), Tone.Ink)
            }
            if (d.banned && !d.source.isNullOrEmpty()) Pill(source(d.source), Tone.Ink)
            if (verdict != null && !verdict.trusted) VerdictPill(verdict)
            if (you) Pill(tr(R.string.ml_you_q), Tone.Warn)
            d.bannedAt?.let { Pill(ago(it * 1000), Tone.Ink) }
            if (d.webRequestCount > 0) Pill("${num(d.webRequestCount)} web", Tone.Ink)
            a?.statuses?.entries?.sortedByDescending { it.value }?.take(4)?.forEach { Pill("${it.key}×${it.value}", Tone.Ink) }
        }
        (verdict?.line?.ifEmpty { null } ?: d.reasonText()?.takeIf { d.source != "web" && it.isNotEmpty() })?.let { Sub(it, Modifier.padding(top = 10.dp), size = 14, mono = true, maxLines = 2) }
        a?.topPaths?.take(3)?.forEach { p -> Txt("${p.count}× ${p.path}", Modifier.padding(top = 4.dp), size = 12, mono = true, color = H.c.muted, maxLines = 1) }
        a?.flags?.forEach { Txt(it, Modifier.padding(top = 4.dp), size = 12, color = H.c.warn, maxLines = 2) }
        val lines = d.webRequests + d.sshLines + d.evidence
        if (lines.isNotEmpty()) LogBox(lines.map { LogLine("", it) }, Modifier.padding(top = 14.dp).height(150.dp), follow = false)
        if (kit.owner) {
            if (!d.banned && !d.allowed) {
                Btn(tr(R.string.shd_ban_24h), {
                    if (you) ask = Ask.You else act(tr(R.string.shd_ban_l, ip)) { Agent.ban(ip) }
                }, Modifier.padding(top = 16.dp), BtnStyle.Danger)
            }
            if (!d.allowed || d.banned) Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(8.dp)) {
                if (d.banned) Btn(tr(R.string.shd_unban), { ask = Ask.Unban }, Modifier.weight(1f), BtnStyle.Ink)
                else Btn(tr(R.string.shd_ban_perm), { ask = Ask.Perm }, Modifier.weight(1f))
                if (!d.allowed) Btn(tr(R.string.shd_allow), { act(tr(R.string.shd_allow_l, ip)) { Agent.allow(ip) } }, Modifier.weight(1f))
            }
            if (!d.allowed) Sub(tr(R.string.shd_allow_ssh), Modifier.padding(top = 8.dp), size = 12)
        }
    }
    res?.let { r ->
        FlowRow(Modifier.padding(top = 14.dp), Arrangement.spacedBy(8.dp), Arrangement.spacedBy(8.dp)) {
            Pill(tr(R.string.shd_firewall), if (r.firewall) Tone.Ok else Tone.Warn)
            if (r.jails.isNotEmpty()) Pill(r.jails.joinToString(", "), Tone.Ok)
            r.crowdsec?.let { Pill("CrowdSec", if (it) Tone.Ok else Tone.Warn) }
        }
    }
    err?.let { ErrorLine(it) }
}
