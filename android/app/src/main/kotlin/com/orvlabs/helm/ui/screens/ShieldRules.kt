package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.Allowed
import com.orvlabs.helm.data.OpenPort
import com.orvlabs.helm.data.Security
import com.orvlabs.helm.num
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H

private fun portLabel(p: OpenPort) = (if (p.portEnd > p.port) "${p.port}-${p.portEnd}" else "${p.port}") + "/" + p.proto

@Composable
private fun OnOff(on: Boolean, offTone: Tone = Tone.Signal) = Pill(tr(if (on) R.string.shd_on else R.string.shd_off), if (on) Tone.Ok else offTone)

@Composable
private fun Chevron(open: Boolean, value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (value != null) RightText(value, H.c.ink, 14)
        Ico(Ph.Next, Modifier.rotate(if (open) 90f else 0f), size = 16, tint = H.c.muted)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, on: Boolean, sub: String?, subColor: androidx.compose.ui.graphics.Color, owner: Boolean, onToggle: (Boolean) -> Unit) {
    val row = @Composable { Li(title, sub = sub, subColor = subColor, left = { IconTile(icon) }, right = { if (owner) Toggle(on) else OnOff(on, Tone.Ink) }) }
    if (owner) Box(Modifier.toggleable(on, role = Role.Switch, onValueChange = { Haptics.fire(if (it) Feel.ToggleOn else Feel.ToggleOff); onToggle(it) })) { row() } else row()
}

@Composable
internal fun RulesTab(load: Load<Security>, allowed: List<Allowed>, kit: ShieldKit, onMap: () -> Unit) {
    EveryWhileVisible(30_000) { load.reload() }
    Failed(load)
    val s = load.value ?: return
    var open by remember { mutableStateOf<String?>(null) }
    val c = H.c
    fun fold(k: String) { open = if (open == k) null else k }

    val geo = s.geo
    val low = s.lowTrust
    val lock = s.originLock
    Card(pad = 12) {
        Li(
            tr(R.string.shd_geo), left = { IconTile(Ph.Globe) }, onClick = onMap, sub = geo.error, subColor = c.bad,
            right = {
                if (geo.error != null || (geo.requested > 0 && !geo.active)) Pill(tr(R.string.shd_not_applied), Tone.Signal)
                else if (geo.active) Pill(tr(R.string.shd_n_blocked, geo.requested), Tone.Ok)
                else Pill(tr(R.string.shd_off), Tone.Ink)
            },
        )
        SwitchRow(
            Ph.Gauge, tr(R.string.shd_slow), low.active, low.error ?: if (low.wanted != low.active) tr(R.string.shd_not_applied) else null, c.bad, kit.owner,
        ) { on -> kit.run(tr(R.string.shd_slow)) { Agent.setLowTrust(on) } }
        SwitchRow(
            Ph.Globe, tr(R.string.shd_origin), lock.active,
            lock.error ?: if (lock.wanted != lock.active) tr(R.string.shd_not_applied) else if (lock.unproxied.isNotEmpty()) tr(R.string.shd_unproxied, lock.unproxied.joinToString(", ")) else null,
            if (lock.error != null || lock.wanted != lock.active) c.bad else c.warn, kit.owner,
        ) { on -> if (on) kit.open(Sh.Origin(lock.unproxied)) else kit.run(tr(R.string.shd_origin)) { Agent.setOriginLock(false) } }
        Li(tr(R.string.shd_ssh), left = { IconTile(Ph.Key) }, right = { OnOff(s.sshLock.active) })
        Li(tr(R.string.shd_agent), left = { IconTile(Ph.Devices) }, sub = s.agentPort.error, subColor = c.bad, right = { OnOff(s.agentPort.active) })
    }

    val jails = s.fail2ban.jails
    val cs = s.crowdsec
    val idle = cs.sources.filter { it.read == 0L }.map { it.source }
    Card(pad = 12) {
        Li(
            "fail2ban", left = { IconTile(Ph.Shield) }, sub = s.fail2ban.error, subColor = c.bad,
            right = { Chevron(open == "f2b", num(jails.sumOf { it.banned })) }, onClick = { fold("f2b") },
        )
        if (open == "f2b") jails.filter { it.ips.isNotEmpty() }.forEach { j ->
            K(j.name, Modifier.padding(top = 4.dp, bottom = 4.dp))
            j.ips.forEach { ip -> Li(ip, mono = true, titleSize = 14, left = { Mk(Tone.Signal) }, onClick = { kit.open(Sh.Ip(ip)) }) }
        }
        Li(
            "CrowdSec", left = { IconTile(Ph.Fingerprint) },
            sub = cs.error ?: cs.errors.firstOrNull() ?: if (idle.isNotEmpty()) tr(R.string.shd_cs_idle, idle.joinToString(", ")) else null,
            subColor = c.warn,
            right = { if (cs.installed) Chevron(open == "cs", num(cs.decisions.size)) else Pill(tr(R.string.shd_absent), Tone.Ink) },
            onClick = if (cs.installed) ({ fold("cs") }) else null,
        )
        if (open == "cs") cs.decisions.forEach { d ->
            Li(
                d.ip, mono = true, titleSize = 14, sub = listOf(d.reason, d.duration).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null },
                left = { Mk(Tone.Signal) }, onClick = { kit.open(Sh.Ip(d.ip)) },
            )
        }
        Li(
            tr(R.string.shd_ports), left = { IconTile(Ph.Server) }, sub = s.exposure.error, subColor = c.bad,
            right = { Chevron(open == "ports", num(s.exposure.ports.count { !it.restricted })) }, onClick = { fold("ports") },
        )
        if (open == "ports") s.exposure.ports.forEach { p ->
            Li(
                portLabel(p), mono = true, titleSize = 14, sub = p.owner.ifEmpty { null },
                right = { Pill(tr(if (p.restricted) R.string.shd_restricted else R.string.shd_public), if (p.restricted) Tone.Ok else Tone.Ink) },
            )
        }
        Li(
            tr(R.string.shd_never), left = { IconTile(Ph.Flag) },
            right = { Chevron(open == "allowed", num(allowed.size)) }, onClick = { fold("allowed") },
        )
        if (open == "allowed") allowed.forEach { a -> Li(a.label, titleSize = 14, sub = a.detail.ifEmpty { null }) }
    }
}
