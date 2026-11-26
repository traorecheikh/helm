package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.continentName
import com.orvlabs.helm.countryName
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.Geo
import com.orvlabs.helm.data.Live
import com.orvlabs.helm.data.World
import com.orvlabs.helm.data.heatLevels
import com.orvlabs.helm.data.individuallyBlocked
import com.orvlabs.helm.data.score
import com.orvlabs.helm.num
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H

private fun mapDesc(g: Geo): String {
    val top = g.countries.sortedByDescending { it.score() }.filter { it.score() > 0 }.take(3)
    return if (top.isEmpty()) tr(R.string.shd_quiet) else tr(R.string.shd_map_desc, top.joinToString(", ") { "${countryName(it.code, it.name)} ${it.attempts24h}" })
}

@Composable
internal fun MapTab(load: Load<Geo>, kit: ShieldKit) {
    val ctx = LocalContext.current
    val world = remember { World.load(ctx) }
    EveryWhileVisible(15_000) { load.reload() }
    LaunchedEffect(Unit) { Live.events.collect { if (it.type == "ban" || it.kind == "geo_changed") load.reload() } }
    Failed(load)
    val geo = load.value ?: return
    val ripples = rememberRipples(geo)
    val c = H.c
    val blocked = individuallyBlocked(geo)
    val a = geo.applied

    Card(pad = 12) {
        WorldMap(
            world, remember(geo) { heatLevels(geo) }, geo.countries.filter { it.blocked }.map { it.code }.toSet(), "SN", ripples,
            mapDesc(geo), onTap = { kit.open(Sh.Country(it)) },
        )
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp), Arrangement.SpaceBetween, Alignment.Top) {
            Column {
                DotNum(num(geo.countries.sumOf { it.attempts24h }), 64)
                Txt(tr(R.string.shd_attempts), size = 12, weight = 600, color = c.muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                if (a != null && a.requested > 0 && !a.active) Pill(tr(R.string.shd_not_applied), Tone.Signal)
                else if (a != null && a.active) Pill(tr(R.string.shd_n_blocked, a.requested), Tone.Ok)
                if (!geo.zonesLoaded) Pill(tr(R.string.shd_zones), Tone.Warn)
            }
        }
    }

    Row(Modifier.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
        geo.continents.forEach { k ->
            val name = continentName(k.name)
            Box(
                Modifier.heightIn(min = 48.dp).then(
                    if (!kit.owner) Modifier else Modifier.press({
                        if (k.blocked) kit.run(tr(R.string.shd_unblock, name)) { Agent.setBlock(blocked, shieldContinents(geo) - k.name) }
                        else kit.open(Sh.Continent(k.name))
                    }, role = Role.Button),
                ),
                Alignment.Center,
            ) {
                Txt(
                    "$name · ${k.bans}", size = 14, weight = 600, color = if (k.blocked) c.signalInk else c.muted, maxLines = 1,
                    modifier = Modifier.clip(CircleShape).background(if (k.blocked) c.signal else c.raise, CircleShape).padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }

    val names = blocked.map { flag(it).ifEmpty { it } } + geo.continents.filter { it.blocked }.map { continentName(it.name) }
    if (names.isNotEmpty()) Card(pad = 4) {
        Li(
            names.joinToString("  "), left = { Ico(Ph.Shield, size = 20, tint = c.signal) },
            right = { if (kit.owner) RightText(tr(R.string.shd_unblock_all), c.signal, 13) },
            onClick = if (kit.owner) ({ kit.run(tr(R.string.shd_unblock_all)) { Agent.setBlock(emptyList(), emptyList()) } }) else null,
        )
    }

    val top = geo.countries.filter { it.score() > 0 }.sortedByDescending { it.score() }.take(8)
    if (top.isEmpty()) return
    val max = top.first().score().toFloat()
    Card(pad = 12) {
        top.forEach { ct ->
            val frac = ct.score() / max
            Li(
                countryName(ct.code, ct.name), left = { ShieldFlag(ct.code) },
                right = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ct.attempts24h > 0) RightText(num(ct.attempts24h), c.muted, 14)
                        if (ct.bans > 0) Pill(num(ct.bans), Tone.Signal)
                    }
                },
                onClick = { kit.open(Sh.Country(ct.code)) },
            )
            Meter(frac.coerceAtLeast(0.03f), Modifier.padding(start = 44.dp, bottom = 4.dp), if (frac > 0.67f) c.bad else if (frac > 0.34f) c.warn else c.muted)
        }
    }
}
