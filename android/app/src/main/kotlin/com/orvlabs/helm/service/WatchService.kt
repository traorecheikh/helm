package com.orvlabs.helm.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Live
import com.orvlabs.helm.data.Server
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.alertKey
import com.orvlabs.helm.data.isPairRequest
import com.orvlabs.helm.widget.SummaryWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.orvlabs.helm.R
import com.orvlabs.helm.tr

class WatchService : LifecycleService() {
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(this, ID, Notifier.watching(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!started) {
            started = true
            lifecycleScope.launch {
                launch { Live.deploys.collect { d -> launch { Tracker.track(this@WatchService, d) } } }
                launch { while (true) { Tracker.reboot(this@WatchService); delay(120_000) } }
                Store.config.filterNotNull()
                    .map { c -> if (c.onboarded) c.servers.filter { it.deviceId.isNotEmpty() } else emptyList() }
                    .distinctUntilChanged()
                    .collectLatest { servers ->
                        if (servers.isEmpty()) stopSelf()
                        else coroutineScope { servers.forEach { s -> launch { listen(s, servers.size > 1) } } }
                    }
            }
        }
        return START_STICKY
    }

    private suspend fun listen(server: Server, multi: Boolean) {
        val seen = ArrayDeque<String>()
        var backoff = 2_000L
        val nm = getSystemService(NotificationManager::class.java)
        while (true) {
            try {
                Agent.events(server).collect { raw ->
                    if (raw.type == "open") {
                        backoff = 2_000L
                        Tracker.lockdown(this)
                        nm.notify(ID, Notifier.watching(this))
                        return@collect
                    }
                    val e = Agent.decodeEvent(raw) ?: return@collect
                    if ("${server.id}:${e.id}" in seen) return@collect
                    seen.addLast("${server.id}:${e.id}")
                    if (seen.size > 200) seen.removeFirst()
                    SummaryWorker.refreshNow(this)
                    Live.events.tryEmit(e)
                    if (e.kind == "panic_on" || e.kind == "panic_off") Tracker.lockdown(this)
                    if (e.kind in setOf("build_error", "deploy") && Tracker.covered(e)) return@collect
                    if (e.isPairRequest() || Store.config.value?.alerts?.contains(e.alertKey()) == true) Notifier.post(this, e, server.id, if (multi) server.name else null)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: ApiError) {
                if (e.kind == ApiError.Kind.Auth) {
                    nm.notify(ID, Notifier.watching(this, tr(R.string.helm_lost_access_to_the_server)))
                    Notifier.lostAccess(this)
                    return
                }
                nm.notify(ID, Notifier.watching(this, tr(R.string.reconnecting_to_the_server)))
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    companion object {
        private const val ID = 1

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                val c = Store.config.filterNotNull().first()
                if (c.onboarded && c.deviceId.isNotEmpty()) WatchService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}
