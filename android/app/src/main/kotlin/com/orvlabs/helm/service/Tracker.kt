package com.orvlabs.helm.service

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.DeployStart
import com.orvlabs.helm.data.Plat
import com.orvlabs.helm.data.Event
import com.orvlabs.helm.data.LogLine
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.data.quietNow
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Feel
import com.orvlabs.helm.ui.components.Haptics
import com.orvlabs.helm.ui.components.duration
import com.orvlabs.helm.ui.screens.deployStep
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Tracker {
    private val recent = ConcurrentHashMap<String, Long>()
    private var overdueFor = 0L

    fun covered(e: Event): Boolean {
        val text = "${e.title} ${e.message}"
        return recent.any { (n, t) -> System.currentTimeMillis() - t < 300_000 && text.contains(n, true) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun track(ctx: Context, d: DeployStart) {
        val nm = NotificationManagerCompat.from(ctx)
        val snap = Repo.state.value.snap
        val name = snap?.service(d.id)?.name ?: d.id
        val project = snap?.projectOf(d.id)?.name.orEmpty()
        val nid = LiveNotifier.id(d.serverId, "live:${d.id}:${d.at}")
        var step = -1
        var reconnecting = false
        var posted = false
        var dismissed = false
        fun post(depId: String) {
            if (dismissed) return
            if (posted && nm.activeNotifications.none { it.id == nid }) { dismissed = true; return }
            nm.notify(nid, LiveNotifier.deployLive(ctx, Tracked(nid, name, project, step, d.at, depId, d.kind, d.id, d.serverId, reconnecting)))
            posted = true
        }
        post("")
        val lines = mutableListOf<LogLine>()
        var logJob: Job? = null
        var lastOk = System.currentTimeMillis()
        coroutineScope {
            while (System.currentTimeMillis() - d.at < 30 * 60_000L) {
                try {
                    val dep = Plat.deployments(d.kind, d.id).filter { (parseTime(it.createdAt) ?: 0) >= d.at - 5000 }.maxByOrNull { parseTime(it.createdAt) ?: 0 }
                    lastOk = System.currentTimeMillis()
                    if (reconnecting) { reconnecting = false; post(dep?.deploymentId.orEmpty()) }
                    if (dep != null) {
                        if (logJob == null && !dep.logPath.isNullOrEmpty()) {
                            step = 0
                            post(dep.deploymentId)
                            logJob = launch {
                                try {
                                    Agent.deploymentLogs(dep.logPath).collect { l ->
                                        lines += l
                                        val s = deployStep(lines)
                                        if (s != step) { step = s; post(dep.deploymentId) }
                                    }
                                } catch (e: ApiError) { }
                            }
                        }
                        val started = parseTime(dep.startedAt) ?: d.at
                        val ended = parseTime(dep.finishedAt) ?: System.currentTimeMillis()
                        val dur = duration(started, ended).orEmpty()
                        when (dep.status) {
                            "done" -> {
                                logJob?.cancel()
                                recent[name] = System.currentTimeMillis()
                                nm.cancel(nid)
                                if (!dismissed) LiveNotifier.deployDone(ctx, Tracked(nid, name, project, 3, d.at, dep.deploymentId, d.kind, d.id, d.serverId), dur, ended)
                                Haptics.fire(Feel.Confirm)
                                return@coroutineScope
                            }
                            "error" -> {
                                logJob?.cancel()
                                recent[name] = System.currentTimeMillis()
                                nm.cancel(nid)
                                val st = listOf(R.string.pull, R.string.build, R.string.start, R.string.check)[step.coerceIn(0, 3)]
                                val msg = dep.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }
                                    ?: tr(if (step < 2) R.string.deploy_failed_build else R.string.deploy_failed_start, tr(st), dur)
                                Notifier.post(
                                    ctx,
                                    Event(
                                        id = "deploy:${dep.deploymentId}", type = "dokploy", kind = "build_error",
                                        title = if (step < 2) tr(R.string.build_failed_2, name) else tr(R.string.deploy_failed_2, name),
                                        message = msg, at = ended / 1000,
                                    ),
                                    d.serverId,
                                )
                                return@coroutineScope
                            }
                        }
                    }
                } catch (e: ApiError) {
                    val gap = System.currentTimeMillis() - lastOk
                    if (gap > 30_000 && !reconnecting) { reconnecting = true; post("") }
                    if (gap > 10 * 60_000L) break
                }
                delay(3000)
            }
            logJob?.cancel()
            nm.cancel(nid)
            if (!dismissed) LiveNotifier.deployLost(ctx, Tracked(nid, name, project, step, d.at, "", d.kind, d.id, d.serverId))
        }
    }

    suspend fun lockdown(ctx: Context) {
        val p = runCatching { Agent.panic() }.getOrNull() ?: return
        if (p.active) LiveNotifier.lockdown(ctx, p.until) else LiveNotifier.cancel(ctx, LiveNotifier.LOCKDOWN_ID)
    }

    suspend fun reboot(ctx: Context) {
        val u = runCatching { Agent.updates() }.getOrNull() ?: return
        val at = u.nextReboot?.times(1000)
        val delta = at?.minus(System.currentTimeMillis())
        val cfg = Store.config.value
        val quiet = cfg != null && cfg.quietOn && quietNow(cfg.quietStart, cfg.quietEnd)
        when {
            at == null || delta == null || delta < 0 -> LiveNotifier.cancel(ctx, LiveNotifier.REBOOT_ID)
            delta <= 30 * 60_000L -> LiveNotifier.reboot(ctx, at, false, quiet)
            u.final && delta <= 2 * 3_600_000L && overdueFor != at -> { overdueFor = at; LiveNotifier.reboot(ctx, at, true, quiet) }
        }
    }
}
