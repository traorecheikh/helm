package com.orvlabs.helm.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.service.WatchService
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Btn
import com.orvlabs.helm.ui.components.BtnStyle
import com.orvlabs.helm.ui.components.Card
import com.orvlabs.helm.ui.components.DotNum
import com.orvlabs.helm.ui.components.H1
import com.orvlabs.helm.ui.components.rememberLoad
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.Screen
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

@Composable
fun SetupFlow() {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var phase by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(step > 0) { step-- }
    when (step) {
        0 -> Welcome { step = 1 }
        1 -> Screen(back = tr(R.string.back), onBack = { if (phase > 0) phase = 0 else step = 0 }, dock = false) {
            StepDots(1)
            ConnectBody(phase, { phase = it }, serverId = 0, adding = false) { step = 2 }
        }
        2 -> PlatformStep(onBack = { step = 1 }) { step = 3 }
        else -> AlertsStep(onBack = { step = 2 })
    }
}

@Composable
private fun PlatformStep(onBack: () -> Unit, onNext: () -> Unit) {
    val caps = rememberLoad { runCatching { Agent.capabilities() }.getOrNull() }.value
    val chosen = Repo.state.collectAsStateWithLifecycle().value.chosen
    Screen(back = tr(R.string.back), onBack = onBack, dock = false) {
        StepDots(2)
        H1(tr(R.string.plat_title), Modifier.padding(top = 20.dp))
        PlatformTiles(caps, caps?.pick(chosen).orEmpty(), Modifier.padding(top = 24.dp))
        Btn(tr(R.string.plat_next), onNext, Modifier.padding(top = 24.dp), BtnStyle.Signal)
    }
}

@Composable
private fun StepDots(at: Int) {
    val reduce = LocalReduceMotion.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics {}, Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(4) { i ->
            val w by animateDpAsState(if (i == at) 28.dp else 8.dp, if (reduce) snap() else tween(200), label = "dot")
            Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(if (i <= at) H.c.signal else H.c.line))
        }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val c = H.c
    Screen(dock = false, scroll = false) {
        StepDots(0)
        Card(Modifier.weight(1f).padding(top = 16.dp), color = c.signal, pad = 28) {
            DotNum(tr(R.string.helm), 72, color = c.signalInk)
            Txt(tr(R.string.your_dokploy_nin_your_pocket), Modifier.padding(top = 20.dp), size = 30, weight = 700, color = c.signalInk, tracking = -0.03f, lineHeight = 1.1f)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(Ph.Rocket, Ph.Shield, Ph.Server, Ph.Bell).forEach {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(c.signalInk.copy(alpha = 0.18f)), Alignment.Center) { Ico(it, size = 26, tint = c.signalInk) }
                }
            }
        }
        Btn(tr(R.string.setup_start), onNext, Modifier.padding(top = 16.dp), BtnStyle.Signal)
    }
}

@Composable
private fun AlertsStep(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = H.c
    var on by remember { mutableStateOf(Store.config.value?.alerts.orEmpty()) }
    val scope = rememberCoroutineScope()
    fun next() = scope.launch {
        Store.setAlerts(on)
        Store.setOnboarded(true)
        WatchService.start(ctx)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }

    Screen(back = tr(R.string.back), onBack = onBack, dock = false) {
        StepDots(3)
        Box(Modifier.padding(top = 16.dp).size(88.dp).clip(CircleShape).background(c.signalSoft), Alignment.Center) { Ico(Ph.Bell, size = 40, tint = c.signal) }
        RowsCard(Modifier.padding(top = 20.dp)) {
            val rows = alertRows()
            rows.forEachIndexed { i, (k, icon, title) ->
                SwitchRow(title, k in on, icon, rule = i < rows.lastIndex) { on = if (it) on + k else on - k }
            }
        }
        Btn(
            tr(R.string.allow_notifications),
            { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else next() },
            Modifier.padding(top = 24.dp),
            BtnStyle.Signal,
        )
        Btn(tr(R.string.not_now), { next() }, Modifier.padding(top = 8.dp))
    }
}
