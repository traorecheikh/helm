package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.DeviceInfo
import com.orvlabs.helm.data.PendingDevice
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Btn
import com.orvlabs.helm.ui.components.BtnStyle
import com.orvlabs.helm.ui.components.Card
import com.orvlabs.helm.ui.components.ConfirmSheet
import com.orvlabs.helm.ui.components.DotNum
import com.orvlabs.helm.ui.components.ErrorLine
import com.orvlabs.helm.ui.components.EveryWhileVisible
import com.orvlabs.helm.ui.components.Feel
import com.orvlabs.helm.ui.components.H2
import com.orvlabs.helm.ui.components.Haptics
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.K
import com.orvlabs.helm.ui.components.Li
import com.orvlabs.helm.ui.components.Mk
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.RightText
import com.orvlabs.helm.ui.components.SheetFrame
import com.orvlabs.helm.ui.components.Tone
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.components.Empty
import com.orvlabs.helm.ui.components.ago
import com.orvlabs.helm.ui.components.clock
import com.orvlabs.helm.ui.components.day
import com.orvlabs.helm.ui.components.rememberGuard
import com.orvlabs.helm.ui.components.rememberLoad
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DevicesSheet(onDismiss: () -> Unit) {
    val load = rememberLoad { Agent.devices() }
    EveryWhileVisible(5_000) { load.reload() }
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var target by remember { mutableStateOf<DeviceInfo?>(null) }
    var approving by remember { mutableStateOf<PendingDevice?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val me = Store.config.collectAsStateWithLifecycle().value?.deviceId
    val data = load.value
    val c = H.c

    fun act(block: suspend () -> Unit) = scope.launch {
        try { block(); load.reload() } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); msg = e.message }
    }

    SheetFrame(true, onDismiss) {
        SheetBody {
            H2(tr(R.string.devices))
            load.error?.let { ErrorLine(it) }
            msg?.let { ErrorLine(it) }
            data?.pending?.forEach { p ->
                Card(Modifier.padding(top = 16.dp), color = c.signalSoft) {
                    Li(p.deviceName, sub = listOfNotNull(p.ip, p.requestedAt?.let { ago(it * 1000) }).joinToString(" · "), left = { Mk(Tone.Warn) })
                    DotNum(p.fingerprint, 34, Modifier.padding(top = 4.dp), color = c.signal)
                    Row(Modifier.padding(top = 14.dp), Arrangement.spacedBy(8.dp)) {
                        Btn(tr(R.string.deny), { act { Agent.deny(p.requestId) } }, Modifier.weight(1f))
                        Btn(tr(R.string.approve), { approving = p }, Modifier.weight(1f), BtnStyle.Signal)
                    }
                }
            }
            data?.devices?.forEach { d ->
                val self = d.deviceId == me
                Li(
                    d.name + if (self) tr(R.string.this_phone) else "",
                    sub = listOfNotNull(d.lastSeenAt?.let { ago(it * 1000) }, d.lastIp).joinToString(" · "),
                    left = { Mk(if (d.role == "owner") Tone.Ok else Tone.Ring) },
                    right = { if (!self) Ico(SI.Trash, size = 20, tint = c.bad, desc = tr(R.string.revoke)) },
                    onClick = if (self) null else ({ target = d }),
                )
            }
        }
    }
    ConfirmSheet(
        target != null, tr(R.string.revoke_2, target?.name.orEmpty()), tr(R.string.this_phone_loses_access_at_once),
        tr(R.string.keep_holding_to_revoke),
        onConfirm = { val d = target; target = null; if (d != null) guard(tr(R.string.revoke_3, d.name)) { act { Agent.revoke(d.deviceId) } } },
        onDismiss = { target = null },
    )
    val a = approving
    ConfirmSheet(
        a != null, tr(R.string.sh_root_title, a?.deviceName.orEmpty()), tr(R.string.sh_root_body, a?.fingerprint.orEmpty()), tr(R.string.sh_root_hold),
        onConfirm = { approving = null; if (a != null) guard(tr(R.string.approve_2, a.deviceName)) { act { Agent.approve(a.requestId) } } },
        onDismiss = { approving = null },
        holdMs = 3000,
    )
}

@Composable
internal fun ActivitySheet(onDismiss: () -> Unit) {
    val load = rememberLoad { Agent.audit().sortedByDescending { it.at } }
    SheetFrame(true, onDismiss) {
        SheetBody {
            H2(tr(R.string.activity_log))
            load.error?.let { ErrorLine(it) }
            if (!load.loading && load.value?.isEmpty() == true) Empty(tr(R.string.nothing_recorded_yet))
            var last = ""
            load.value?.forEach { e ->
                val d = day(e.at * 1000)
                if (d != last) { last = d; K(d, Modifier.padding(top = 16.dp)) }
                Li(
                    e.action.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    sub = listOfNotNull(e.deviceName, e.detail, e.ip).joinToString(" · "),
                    right = { RightText(clock(e.at * 1000)) },
                )
            }
        }
    }
}
