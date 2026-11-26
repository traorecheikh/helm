package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Attacker
import com.orvlabs.helm.data.Ban
import com.orvlabs.helm.data.Live
import com.orvlabs.helm.data.Panic
import com.orvlabs.helm.data.SecEvent
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.reasonText
import com.orvlabs.helm.ml.Level
import com.orvlabs.helm.ml.ShieldView
import com.orvlabs.helm.ml.VerdictPill
import com.orvlabs.helm.ml.rememberShieldView
import com.orvlabs.helm.ml.trustedIps
import com.orvlabs.helm.num
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface Sh {
    data class Ip(val ip: String) : Sh
    data class Country(val code: String) : Sh
    data class Continent(val name: String) : Sh
    data class Origin(val unproxied: List<String>) : Sh
    data object Lock : Sh
}

internal class ShieldKit(
    val owner: Boolean,
    val open: (Sh) -> Unit,
    val close: () -> Unit,
    val refresh: () -> Unit,
    private val exec: (String, Feel?, suspend () -> Unit) -> Unit,
) {
    fun run(label: String, feel: Feel? = null, block: suspend () -> Unit) = exec(label, feel, block)
}

private val IP_OK = Regex("^[0-9a-fA-F:.]{2,45}$")

private enum class Mode { Safe, Probing, Attack, Locked }

@Composable
fun ShieldScreen(nav: NavController, ip: String) {
    val owner = Store.config.collectAsStateWithLifecycle().value?.owner == true
    val shield = rememberLoad { Agent.shield() }
    val live = rememberLoad { Agent.live() }
    val panic = rememberLoad(owner) { if (owner) Agent.panic() else Panic() }
    val geo = rememberLoad { Agent.geo() }
    val security = rememberLoad { Agent.security() }
    val devices = rememberLoad(owner) { if (owner) Agent.devices() else null }
    val logins = rememberLoad(owner) { if (owner) Agent.logins() else emptyList() }
    val trusted = remember(devices.value, logins.value) { trustedIps(devices.value, logins.value.orEmpty(), System.currentTimeMillis() / 1000) }
    EveryWhileVisible(10_000) { live.reload(); panic.reload() }
    EveryWhileVisible(30_000) { shield.reload() }
    LaunchedEffect(Unit) { Live.events.collect { if (it.type == "ban" || it.kind == "threat") { live.reload(); shield.reload() } } }

    var tab by rememberSaveable { mutableStateOf(0) }
    var sheet by remember { mutableStateOf<Sh?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    LaunchedEffect(ip) { if (IP_OK.matches(ip)) sheet = Sh.Ip(ip) }

    val refresh = { shield.reload(); live.reload(); panic.reload(); geo.reload(); security.reload() }
    val kit = ShieldKit(owner, { sheet = it }, { sheet = null }, refresh) { label, feel, block ->
        guard(label) {
            scope.launch {
                try {
                    block(); err = null
                    if (feel != null) Haptics.fire(feel, key = "shield")
                } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); err = e.message }
                refresh()
            }
        }
    }

    val locked = (panic.value ?: security.value?.panic)
    val view = rememberShieldView(live.value, shield.value?.recent.orEmpty(), trusted)
    val threats = view?.read?.threats ?: 0
    val mode = when {
        locked?.active == true -> Mode.Locked
        view?.read?.level == Level.Attack -> Mode.Attack
        view?.read?.level == Level.Probing -> Mode.Probing
        else -> Mode.Safe
    }

    Screen {
        Failed(shield)
        shield.value?.let { s ->
            Hero(
                mode, threats, s.total, locked?.until, owner,
                onLock = { kit.open(Sh.Lock) },
                onEnd = { kit.run(tr(R.string.shd_end), Feel.Recovery) { Agent.setPanic(0) } },
            )
        }
        err?.let { ErrorLine(it) }
        Box(Modifier.padding(top = 16.dp)) {
            Tabs(listOf(tr(R.string.shd_t_live), tr(R.string.shd_t_bans), tr(R.string.shd_t_map), tr(R.string.shd_t_log), tr(R.string.shd_t_rules)), tab, { tab = it })
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> LiveTab(live, view, kit)
                1 -> BansTab(shield.value?.recent.orEmpty(), view, kit)
                2 -> MapTab(geo, kit)
                3 -> LogTab(kit)
                else -> RulesTab(security, shield.value?.allowed.orEmpty(), kit) { tab = 2 }
            }
        }
    }
    ShieldSheets(sheet, kit, live.value, geo.value, trusted)
}

@Composable
private fun Hero(mode: Mode, threats: Int, bans: Int, until: Long?, owner: Boolean, onLock: () -> Unit, onEnd: () -> Unit) {
    val c = H.c
    val (bg, fg) = when (mode) {
        Mode.Safe -> c.okSoft to c.ok
        Mode.Probing -> c.warnSoft to c.warn
        Mode.Attack -> c.badSoft to c.bad
        Mode.Locked -> c.signalFill to c.signalInk
    }
    Card(color = bg, pad = 20) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(fg.copy(alpha = 0.14f)), Alignment.Center) {
                Ico(if (mode == Mode.Attack || mode == Mode.Probing) Ph.Warning else Ph.ShieldFill, size = 30, tint = fg)
            }
            Column {
                H2(tr(when (mode) { Mode.Safe -> R.string.shd_safe; Mode.Probing -> R.string.ml_probing; Mode.Attack -> R.string.shd_attack; Mode.Locked -> R.string.shd_locked }), color = fg)
                if (mode == Mode.Locked && until != null) Txt(tr(R.string.shd_until, clock(until * 1000)), size = 14, color = fg.copy(alpha = 0.85f))
            }
        }
        Row(Modifier.padding(top = 20.dp), Arrangement.spacedBy(36.dp)) {
            HeroNum(threats, R.string.shd_threats, fg)
            HeroNum(bans, R.string.shd_t_bans, fg)
        }
        if (owner) {
            if (mode == Mode.Locked) Btn(tr(R.string.shd_end), onEnd, Modifier.padding(top = 20.dp), BtnStyle.Ink)
            else Btn(tr(R.string.shd_lock), onLock, Modifier.padding(top = 20.dp), BtnStyle.Signal, Ph.Shield)
        }
    }
}

@Composable
private fun HeroNum(n: Int, label: Int, color: Color) {
    Column {
        DotNum(num(n), 64, color = color)
        Txt(tr(label), size = 12, weight = 600, color = color.copy(alpha = 0.8f))
    }
}

@Composable
internal fun ShieldFlag(code: String?) {
    val f = flag(code.orEmpty())
    Box(Modifier.size(32.dp), Alignment.Center) { if (f.isEmpty()) Mk(Tone.Ring) else Txt(f, size = 24) }
}

@Composable
private fun Quiet() {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(H.c.okSoft), Alignment.Center) { Ico(Ph.Check, size = 28, tint = H.c.ok) }
        Txt(tr(R.string.shd_quiet), weight = 600, color = H.c.muted)
    }
}

@Composable
private fun LiveTab(load: Load<List<Attacker>>, view: ShieldView?, kit: ShieldKit) {
    Failed(load)
    val list = load.value ?: return
    if (list.isEmpty()) return Quiet()
    Card(pad = 12) {
        list.forEach { a ->
            val v = view?.verdicts?.get(a.ip)
            val crowd = view?.read?.campaigns?.get(a.ip)?.let { tr(R.string.ml_campaign, it) }
            Li(
                a.ip, mono = true, titleSize = 14,
                sub = listOfNotNull(v?.line?.ifEmpty { null } ?: "${a.hits}×", crowd, ago(a.lastSeen * 1000)).joinToString(" · "),
                left = { ShieldFlag(a.country) }, right = { VerdictPill(v, a.banned) },
                onClick = { kit.open(Sh.Ip(a.ip)) },
            )
        }
    }
}

@Composable
private fun BansTab(bans: List<Ban>, view: ShieldView?, kit: ShieldKit) {
    var ip by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val c = H.c
    val go = { if (IP_OK.matches(ip.trim())) kit.open(Sh.Ip(ip.trim())) else bad = true }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Field(tr(R.string.shd_ip), ip, { ip = it; bad = false }, Modifier.weight(1f), error = bad, keyboard = KeyboardType.Uri, onSubmit = go)
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(c.signalFill).then(if (ip.isBlank()) Modifier else Modifier.press(go, tr(R.string.shd_lookup))),
            Alignment.Center,
        ) { Ico(Ph.Search, size = 22, tint = c.signalInk) }
    }
    if (bad) ErrorLine(tr(R.string.shd_bad_ip))
    if (bans.isEmpty()) return Quiet()
    Card(pad = 12) {
        bans.sortedByDescending { it.bannedAt }.forEach { b ->
            val v = view?.bans?.get(b.ip)
            val line = v?.line?.ifEmpty { null } ?: b.reasonText().takeIf { b.source != "web" && it.isNotEmpty() }
            Li(
                b.ip, mono = true, titleSize = 14, sub = listOfNotNull(line, ago(b.bannedAt * 1000)).joinToString(" · "),
                left = { ShieldFlag(b.country) }, right = { VerdictPill(v) },
                onClick = { kit.open(Sh.Ip(b.ip)) },
            )
        }
    }
}

@Composable
private fun <T> ByDay(items: List<T>, at: (T) -> Long, row: @Composable (T) -> Unit) {
    items.groupBy { day(at(it) * 1000) }.forEach { (d, rows) ->
        K(d, Modifier.padding(top = 8.dp))
        Card(pad = 12) { rows.forEach { row(it) } }
    }
}

private fun eventLine(e: SecEvent): String = when (e.source) {
    "web" -> tr(R.string.shd_web_line, e.count, e.count429, e.what)
    "helm" -> listOfNotNull(e.what, e.by).joinToString(" · ")
    else -> e.what
}

@Composable
private fun LogTab(kit: ShieldKit) {
    val log = rememberLoad { Agent.securityLog() }
    val logins = rememberLoad(kit.owner) { if (kit.owner) Agent.logins().sortedByDescending { it.at } else emptyList() }
    EveryWhileVisible(30_000) { log.reload() }
    val keys = listOfNotNull(null, "logins".takeIf { kit.owner }, "fail2ban", "crowdsec", "web", "helm")
    val labels = keys.map {
        when (it) {
            null -> tr(R.string.shd_all)
            "logins" -> tr(R.string.shd_signins)
            "fail2ban" -> "fail2ban"
            "crowdsec" -> "CrowdSec"
            "web" -> tr(R.string.shd_web)
            else -> "Helm"
        }
    }
    var f by rememberSaveable { mutableStateOf(0) }
    val key = keys.getOrNull(f)
    Chips(labels, f, { f = it })
    if (key == "logins") {
        Failed(logins)
        val list = logins.value ?: return
        if (list.isEmpty()) return Quiet()
        ByDay(list, { it.at }) { l ->
            Li(
                tr(R.string.shd_via, l.user, l.method), titleSize = 14,
                sub = "${l.ip} · ${if (l.kind == "ssh") "SSH" else tr(R.string.shd_app)}${if (l.count > 1) " · ${l.count}×" else ""}",
                left = { ShieldFlag(l.country) }, right = { RightText(clock(l.at * 1000)) },
                onClick = { kit.open(Sh.Ip(l.ip)) },
            )
        }
        return
    }
    Failed(log)
    val list = log.value.orEmpty().filter { key == null || it.source == key }
    if (!log.loading && log.error == null && list.isEmpty()) return Quiet()
    ByDay(list, { it.at }) { e ->
        Li(
            e.ip.ifEmpty { e.source }, mono = e.ip.isNotEmpty(), titleSize = 14,
            sub = listOf(e.source, eventLine(e)).filter { it.isNotEmpty() }.joinToString(" · "),
            left = { Mk(if (e.source == "helm") Tone.Ink else Tone.Signal) }, right = { RightText(clock(e.at * 1000)) },
            onClick = if (e.ip.isNotEmpty() && e.source != "helm") ({ kit.open(Sh.Ip(e.ip)) }) else null,
        )
    }
}
