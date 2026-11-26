package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Container
import com.orvlabs.helm.data.Detail
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.data.Plat
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.Svc
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.*
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.launch

@Composable
fun ServiceScreen(nav: NavController, kind: String, id: String) {
    val st = Repo.state.collectAsStateWithLifecycle().value
    val snap = st.snap
    val dokploy = st.platform == "dokploy"
    val svc = snap?.service(id)
    val detail = rememberLoad(kind, id) { Plat.one(kind, id) }
    val d = detail.value
    val name = svc?.name ?: d?.name.orEmpty()
    val containers = snap?.containersOf(svc ?: d?.let { Svc(it.name, it.appName) }).orEmpty()
    val status = svc?.status ?: d?.let { it.applicationStatus ?: it.composeStatus } ?: "idle"
    val isDb = kind != "application" && kind != "compose"
    val owner = Store.config.collectAsStateWithLifecycle().value?.owner == true
    val tabs = when {
        !isDb && dokploy -> listOf(tr(R.string.deploys), tr(R.string.logs), tr(R.string.env), tr(R.string.svc_config))
        !isDb -> listOf(tr(R.string.deploys), tr(R.string.logs), tr(R.string.svc_config))
        kind == "postgres" && dokploy -> listOf(tr(R.string.svc_data), tr(R.string.backups))
        else -> emptyList()
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val lastDeploy = snap?.deployments.orEmpty().filter { it.serviceId == id }.maxOfOrNull { parseTime(it.createdAt) ?: 0 }?.takeIf { it > 0 }

    Screen(back = snap?.projectOf(id)?.name ?: tr(R.string.apps), onBack = { nav.popBackStack() }, dock = false) {
        Hero(nav, kind, id, name, status, isDb, d, containers, lastDeploy)
        if (tabs.isNotEmpty()) Box(Modifier.padding(top = 16.dp)) { Tabs(tabs, tab, { tab = it }) }
        Column(Modifier.padding(top = 12.dp)) {
            val container = (containers.firstOrNull { it.state == "running" } ?: containers.firstOrNull())?.name
            if (isDb && !dokploy) LogsTab(container)
            else if (isDb) {
                if (tab == 0) DataTab(kind, svc, detail, container, owner)
                else if (svc != null) BackupsTab(svc, detail, owner)
            } else when (tab) {
                0 -> DeploysTab(nav, kind, id)
                1 -> LogsTab(container)
                2 -> if (dokploy) EnvTab(kind, id, detail) else ConfigTab(nav, kind, id, name, detail, containers)
                else -> ConfigTab(nav, kind, id, name, detail, containers)
            }
        }
    }
}

@Composable
private fun Hero(
    nav: NavController, kind: String, id: String, name: String, status: String, isDb: Boolean,
    d: Detail?, containers: List<Container>, lastDeploy: Long?,
) {
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    val flash = rememberFlash()
    var stop by remember { mutableStateOf(false) }
    val tone = svcTone(status)

    fun act(block: suspend () -> Unit) = scope.launch { flash.run { block(); Haptics.confirmLater() } }

    Card(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(toneSoft(tone)), Alignment.Center) {
                Ico(if (isDb) Ph.Database else Ph.Cube, size = 28, tint = toneColor(if (tone == Tone.Ring) Tone.Ink else tone))
            }
            Column(Modifier.weight(1f)) {
                H2(name)
                Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                    Pill(statusWord(status), tone)
                    if (isDb && kind == "postgres" && d != null) {
                        if (d.backups.isEmpty()) Pill(tr(R.string.svc_no_backup), Tone.Warn) else Pill(tr(R.string.svc_backup), Tone.Ok)
                    }
                    containers.take(6).forEach { Mk(if (it.state == "running") Tone.Ok else Tone.Warn) }
                }
            }
            if (lastDeploy != null) RightText(ago(lastDeploy))
        }
        if (isDb) return@Card
        Box(Modifier.padding(top = 20.dp)) {
            SlideToAct(tr(R.string.slide_to_redeploy), {
                guard(tr(R.string.redeploy, name)) {
                    act {
                        val t = System.currentTimeMillis()
                        Plat.act(kind, id, "redeploy")
                        nav.navigate(DeployR(kind = kind, id = id, after = t))
                    }
                }
            })
        }
        Row(Modifier.padding(top = 8.dp), Arrangement.spacedBy(8.dp)) {
            Btn(tr(R.string.restart), {
                guard(tr(R.string.restart_2, name)) {
                    act {
                        Plat.restart(kind, id, containers)
                        flash.say(if (containers.isEmpty() && Plat == Dokploy) tr(R.string.svc_no_container) else trn(R.plurals.containers_restarted, containers.size.coerceAtLeast(1)))
                    }
                }
            }, Modifier.weight(1f))
            if (status == "idle") Btn(tr(R.string.start), { guard(tr(R.string.start_2, name)) { act { Plat.act(kind, id, "start"); flash.say(tr(R.string.starting, name)) } } }, Modifier.weight(1f))
            else Btn(tr(R.string.stop), { stop = true }, Modifier.weight(1f), BtnStyle.Danger)
        }
        flash.Show()
    }
    ConfirmSheet(
        stop, tr(R.string.stop_2, name), tr(R.string.svc_stop_line), tr(R.string.keep_holding_to_stop),
        onConfirm = { stop = false; guard(tr(R.string.stop_3, name)) { act { Plat.act(kind, id, "stop"); flash.say(tr(R.string.is_stopping, name)) } } },
        onDismiss = { stop = false },
    )
}
