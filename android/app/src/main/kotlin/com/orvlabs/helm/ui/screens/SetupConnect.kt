package com.orvlabs.helm.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.DEMO_DEVICE
import com.orvlabs.helm.data.DEMO_URL
import com.orvlabs.helm.data.Device
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Btn
import com.orvlabs.helm.ui.components.BtnStyle
import com.orvlabs.helm.ui.components.DotNum
import com.orvlabs.helm.ui.components.ErrorLine
import com.orvlabs.helm.ui.components.Feel
import com.orvlabs.helm.ui.components.Field
import com.orvlabs.helm.ui.components.H1
import com.orvlabs.helm.ui.components.Haptics
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.Sub
import com.orvlabs.helm.ui.components.clearLoadCache
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

suspend fun switchServer(id: Int) {
    if (Store.config.value?.activeId != id) {
        Store.setActive(id)
        Store.config.first { it?.activeId == id }
    }
    Repo.reset()
    clearLoadCache()
    Repo.restore()
}

@Composable
private fun Badge(icon: ImageVector) {
    Box(Modifier.size(88.dp).clip(CircleShape).background(H.c.signalSoft), Alignment.Center) { Ico(icon, size = 40, tint = H.c.signal) }
}

@Composable
internal fun ConnectBody(phase: Int, onPhase: (Int) -> Unit, serverId: Int, adding: Boolean, onDone: () -> Unit) {
    var url by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (adding) "https://" else Store.config.value?.url ?: "https://") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var requestId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var fingerprint by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val base = url.trim().trimEnd('/')

    suspend fun finish(deviceId: String, role: String, address: String = base) {
        Store.savePaired(serverId, address, deviceId, role)
        Store.config.first { it?.deviceId == deviceId }
        onDone()
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                Haptics.fire(Feel.Reject)
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun check() = run {
        if (!base.startsWith("https://")) {
            error = tr(R.string.helm_only_connects_over_https_start)
            return@run
        }
        val health = Agent.health(base)
        if (!health.ok) {
            error = tr(R.string.that_address_does_not_look_like)
            return@run
        }
        if (health.claimed) {
            val r = Agent.requestPairing(serverId, base)
            requestId = r.requestId
            fingerprint = r.fingerprint ?: Device.fingerprint(serverId)
            onPhase(2)
        } else {
            onPhase(1)
        }
    }

    fun demo() = run { finish(DEMO_DEVICE, "owner", DEMO_URL) }

    fun claim() = run {
        try {
            val r = Agent.claim(serverId, base, code.trim())
            finish(r.deviceId, r.role)
        } catch (e: ApiError) {
            Haptics.fire(Feel.Reject)
            if (e.kind != ApiError.Kind.Auth && e.kind != ApiError.Kind.Other) throw e
            error = tr(R.string.that_pairing_code_is_not_right)
        }
    }

    BackHandler(phase > 0) { onPhase(0) }
    LaunchedEffect(phase, requestId) {
        if (phase != 2) return@LaunchedEffect
        while (true) {
            try {
                val st = Agent.pairStatus(base, requestId)
                error = null
                if (st.status == "approved" && st.deviceId != null) {
                    finish(st.deviceId, "member")
                    return@LaunchedEffect
                }
                if (st.status == "denied") {
                    error = tr(R.string.the_owner_declined_this_request_ask)
                    onPhase(0)
                    return@LaunchedEffect
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiError) {
            }
            delay(3000)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        when (phase) {
            0 -> {
                Badge(Ph.Server)
                H1(tr(R.string.setup_connect), Modifier.padding(top = 20.dp))
                Field(tr(R.string.setup_address), url, { url = it }, Modifier.padding(top = 24.dp), error = error != null, keyboard = KeyboardType.Uri)
                error?.let { ErrorLine(it) }
                Btn(if (busy) tr(R.string.checking) else if (error != null) tr(R.string.try_again) else tr(R.string.check_connection), ::check, Modifier.padding(top = 24.dp), BtnStyle.Signal, enabled = !busy)
                if (Store.config.value?.servers.orEmpty().none { it.demo }) Btn(tr(R.string.demo_try), ::demo, Modifier.padding(top = 8.dp), enabled = !busy)
            }
            1 -> {
                Badge(Ph.Key)
                H1(tr(R.string.setup_pair), Modifier.padding(top = 20.dp))
                Sub(
                    "ssh root@server cat /etc/helm-agent/pair-code",
                    Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(H.c.raise).padding(14.dp),
                    color = H.c.ink, mono = true,
                )
                Field(tr(R.string.pairing_code), code, { code = it }, Modifier.padding(top = 16.dp), error = error != null)
                error?.let { ErrorLine(it) }
                Btn(if (busy) tr(R.string.pairing) else tr(R.string.pair_this_phone), ::claim, Modifier.padding(top = 24.dp), BtnStyle.Signal, enabled = !busy && code.isNotBlank())
            }
            else -> {
                Badge(Ph.Phone)
                H1(tr(R.string.setup_wait), Modifier.padding(top = 20.dp))
                DotNum(fingerprint, 56, Modifier.padding(top = 28.dp), color = H.c.signal)
                error?.let { ErrorLine(it) }
            }
        }
    }
}
