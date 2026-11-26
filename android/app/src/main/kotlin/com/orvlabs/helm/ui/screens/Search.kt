package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.AppsR
import com.orvlabs.helm.ui.DeployR
import com.orvlabs.helm.ui.HomeR
import com.orvlabs.helm.ui.ServerR
import com.orvlabs.helm.ui.ServiceR
import com.orvlabs.helm.ui.SettingsR
import com.orvlabs.helm.ui.ShieldR
import com.orvlabs.helm.ui.components.Card
import com.orvlabs.helm.ui.components.Field
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.IconTile
import com.orvlabs.helm.ui.components.K
import com.orvlabs.helm.ui.components.Li
import com.orvlabs.helm.ui.components.Mk
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.Screen
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.components.ago
import com.orvlabs.helm.ui.components.isoMs
import com.orvlabs.helm.ui.components.statusTone
import com.orvlabs.helm.ui.goTab
import com.orvlabs.helm.ui.theme.H

private class Cmd(val label: String, val icon: ImageVector, val keys: String, val go: NavController.() -> Unit)

private fun commands(apps: Boolean) = listOfNotNull(
    if (apps) Cmd(tr(R.string.tab_apps), Ph.Apps, "apps projects") { goTab(AppsR) } else null,
    Cmd(tr(R.string.shield), Ph.Shield, "shield security ban") { goTab(ShieldR()) },
    Cmd(tr(R.string.attack_map), Ph.Globe, "map attack country") { goTab(ShieldR()) },
    Cmd(tr(R.string.tab_server), Ph.Server, "server uptime memory disk") { goTab(ServerR) },
    Cmd(tr(R.string.maintenance), Ph.Broom, "maintenance clean docker traefik") { goTab(ServerR) },
    Cmd(tr(R.string.saved_actions), Ph.Rocket, "saved actions chain") { navigate(SettingsR) },
    Cmd(tr(R.string.settings), Ph.Gear, "settings devices activity theme language lock alerts") { navigate(SettingsR) },
)

@Composable
fun SearchScreen(nav: NavController) {
    val st = Repo.state.collectAsStateWithLifecycle().value
    val snap = st.snap
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val term = q.trim().lowercase()
    val all = commands(st.platform != "none")

    Screen(back = tr(R.string.back), onBack = { nav.popBackStack() }, dock = false) {
        Field(tr(R.string.find_hint), q, { q = it }, Modifier.focusRequester(focus), mono = false)
        if (term.isEmpty()) {
            all.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), Arrangement.spacedBy(12.dp)) {
                    pair.forEach { Tile(it, Modifier.weight(1f)) { it.go(nav) } }
                    if (pair.size == 1) Column(Modifier.weight(1f)) {}
                }
            }
            return@Screen
        }
        val projects = snap?.projects.orEmpty().filter { it.name.lowercase().contains(term) }.take(6)
        val services = snap?.projects.orEmpty().flatMap { it.services }.filter { it.name.lowercase().contains(term) }.take(6)
        val deploys = snap?.deployments.orEmpty()
            .filter { d -> snap?.service(d.serviceId)?.name?.lowercase()?.contains(term) == true }
            .sortedByDescending { it.createdAt.orEmpty() }.take(3)
        val cmds = all.filter { it.label.lowercase().contains(term) || it.keys.contains(term) }

        Group(tr(R.string.apps), projects.isNotEmpty()) {
            projects.forEach { p -> Li(p.name, left = { IconTile(Ph.Apps) }, onClick = { nav.goTab(AppsR) }) }
        }
        Group(tr(R.string.services_2), services.isNotEmpty()) {
            services.forEach { s ->
                Li(s.name, left = { IconTile(if (s.isDb) Ph.Database else Ph.Cube) }, right = { Mk(statusTone(s.status)) },
                    onClick = { nav.navigate(ServiceR(s.kind, s.id)) })
            }
        }
        Group(tr(R.string.find_deploys), deploys.isNotEmpty()) {
            deploys.forEach { d ->
                Li(snap?.service(d.serviceId)?.name.orEmpty(), sub = ago(isoMs(d.createdAt)), left = { IconTile(Ph.Rocket) },
                    right = { Mk(statusTone(d.status ?: "idle")) },
                    onClick = { nav.navigate(DeployR(d.deploymentId, d.kind, d.serviceId)) })
            }
        }
        Group(tr(R.string.actions), cmds.isNotEmpty()) {
            cmds.forEach { cmd -> Li(cmd.label, left = { IconTile(cmd.icon) }, onClick = { cmd.go(nav) }) }
        }
        if (projects.isEmpty() && services.isEmpty() && deploys.isEmpty() && cmds.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
                Ico(Ph.Search, size = 32, tint = H.c.faint)
                Txt(tr(R.string.find_none), weight = 600, color = H.c.muted)
            }
        }
    }
}

@Composable
private fun Group(title: String, show: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (!show) return
    K(title, Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp))
    RowsCard(content = content)
}

@Composable
private fun Tile(cmd: Cmd, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier, onClick = onClick) {
        IconTile(cmd.icon)
        Txt(cmd.label, Modifier.padding(top = 12.dp), size = 16, weight = 600, maxLines = 2)
    }
}
