package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Detail
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.*
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.launch

private fun mbOf(raw: String?): String = raw?.toLongOrNull()?.let { if (it >= 1_000_000) "${it / 1_048_576}" else "$it" } ?: raw.orEmpty()
private fun coresOf(raw: String?): String = raw?.toDoubleOrNull()?.let { if (it >= 1_000_000) "%.2f".format(it / 1e9).trimEnd('0').trimEnd('.') else "$it" } ?: raw.orEmpty()

@Composable
internal fun ResourcesCard(nav: NavController, id: String, name: String, load: Load<Detail>) {
    val d = load.value ?: return
    var open by remember { mutableStateOf(false) }
    var replicas by remember(d) { mutableIntStateOf(d.replicas ?: 1) }
    var mem by remember(d) { mutableStateOf(mbOf(d.memoryLimit)) }
    var cpu by remember(d) { mutableStateOf(coresOf(d.cpuLimit)) }
    var saved by remember { mutableStateOf(false) }
    val flash = rememberFlash()
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    val c = H.c

    Card(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().press({ open = !open }, tr(R.string.resources)), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            Figure(tr(R.string.replicas), "${d.replicas ?: 1}")
            Figure(tr(R.string.svc_memory), mbOf(d.memoryLimit).let { if (it.isEmpty() || it == "0") "∞" else tr(R.string.unit_mb, it) })
            Figure(tr(R.string.svc_cpu), coresOf(d.cpuLimit).let { if (it.isEmpty() || it == "0") "∞" else it })
            Ico(Ph.Next, size = 16, tint = c.muted)
        }
        if (!open) return@Card
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Txt(tr(R.string.replicas), color = c.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).press({ replicas = (replicas - 1).coerceAtLeast(0) }, tr(R.string.one_replica_fewer)), Alignment.Center) { Txt("-", size = 22) }
                Txt("$replicas", mono = true, weight = 500)
                Box(Modifier.size(48.dp).press({ replicas = (replicas + 1).coerceAtMost(5) }, tr(R.string.one_replica_more)), Alignment.Center) { Txt("+", size = 22) }
            }
        }
        if (replicas == 0) Pill(tr(R.string.svc_zero), Tone.Warn)
        Field(tr(R.string.svc_memory_mb), mem, { mem = it.filter(Char::isDigit) }, Modifier.padding(top = 8.dp), keyboard = KeyboardType.Number)
        Field(tr(R.string.svc_cpu_cores), cpu, { cpu = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.padding(top = 8.dp), keyboard = KeyboardType.Decimal)
        Btn(tr(R.string.save), {
            guard(tr(R.string.change_resources_of, name)) {
                scope.launch {
                    flash.run(tr(R.string.svc_saved_redeploy)) {
                        Dokploy.setResources(
                            id, replicas,
                            mem.toLongOrNull()?.let { (it * 1_048_576L).toString() },
                            cpu.toDoubleOrNull()?.let { (it * 1e9).toLong().toString() },
                        )
                        saved = true
                        load.reload()
                    }
                }
            }
        }, Modifier.padding(top = 12.dp), BtnStyle.Ink)
        if (saved) {
            Btn(tr(R.string.apply_now_redeploy), {
                guard(tr(R.string.redeploy, name)) {
                    scope.launch {
                        flash.run {
                            val t = System.currentTimeMillis()
                            Dokploy.act("application", id, "redeploy")
                            nav.navigate(DeployR(kind = "application", id = id, after = t))
                        }
                    }
                }
            }, Modifier.padding(top = 8.dp))
        }
        flash.Show()
    }
}

@Composable
private fun RowScope.Figure(label: String, value: String) {
    Column(Modifier.weight(1f)) {
        Txt(value, size = 22, weight = 700, maxLines = 1)
        K(label)
    }
}
