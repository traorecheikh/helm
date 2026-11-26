package com.orvlabs.helm.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import com.orvlabs.helm.R
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn

@Serializable
data class Snapshot(
    val at: Long,
    val projects: List<Project>,
    val health: Health,
    val containers: List<Container>,
    val deployments: List<Deployment>,
    val destinations: Int,
    val caps: Caps = Caps(),
) {
    fun service(id: String) = projects.flatMap { it.services }.firstOrNull { it.id == id }
    fun projectOf(id: String) = projects.firstOrNull { p -> p.services.any { it.id == id } }
    fun containersOf(svc: Svc?) = svc?.appName?.let { app -> containers.filter { it.appName == app || it.name.startsWith(app) } }.orEmpty()
    val memPercent get() = health.resources.run { if (memTotalBytes > 0) memUsedBytes * 100.0 / memTotalBytes else 0.0 }
}

enum class Link { Live, Offline, Unreachable, Auth }

data class PulseState(
    val snap: Snapshot? = null,
    val stats: Stats? = null,
    val uptime: Uptime? = null,
    val pending: Int = 0,
    val link: Link = Link.Live,
    val loading: Boolean = true,
    val error: String? = null,
    val failingSince: Long? = null,
    val chosen: String? = null,
) {
    val platform get() = (snap?.caps ?: Caps()).pick(chosen)
}

data class Problem(val severe: Boolean, val title: String, val sub: String, val at: Long?, val target: String, val deploymentId: String = "")

@Serializable
data class LastDeploy(val deploymentId: String = "", val service: String = "", val status: String = "", val title: String = "", val errorLine: String = "", val startedAt: Long = 0, val finishedAt: Long = 0)

@Serializable
data class NightSummary(val since: Long = 0, val end: Long = 0, val deploys: Int = 0, val failed: Int = 0, val bans: Int = 0, val downs: List<String> = emptyList(), val stillDown: String? = null, val marks: List<Int> = emptyList(), val text: String = "")

@Serializable
data class Summary(
    val at: Long, val running: Int, val dots: List<Int>, val down: Int, val strip: List<Double?>,
    val downNames: List<String> = emptyList(), val building: String? = null,
    val cpu: Double = 0.0, val memUsed: Long = 0, val memTotal: Long = 0, val diskUsed: Long = 0, val diskTotal: Long = 0,
    val diskFullInDays: Double? = null, val hostname: String = "",
    val deploy: LastDeploy? = null, val failed24h: Int = 0,
    val geo: List<GeoCountry> = emptyList(), val totalBans: Int = 0, val lockdownUntil: Long? = null,
    val night: NightSummary? = null, val nextReboot: Long? = null,
    val linkDown: String? = null, val linkSince: Long? = null,
) {
    companion object {
        /** dots: 0 running, 1 failing, 2 stopped, 3 building */
        fun from(snap: Snapshot, uptime: Uptime?, stats: Stats?): Summary {
            val building = snap.projects.flatMap { it.services }.filter { it.status == "running" }
            val apps = building.mapNotNull { it.appName }
            val dots = snap.containers.map { c ->
                when {
                    apps.any { c.name.startsWith(it) } -> 3
                    c.state == "running" -> 0
                    c.state in setOf("restarting", "dead", "paused") -> 1
                    else -> 2
                }
            }.sortedBy { when (it) { 1 -> 0; 3 -> 1; 0 -> 2; else -> 3 } }
            val downNames = uptime?.services?.filter { it.current != "up" }?.map { it.name }
                ?: snap.containers.filter { it.state in setOf("restarting", "dead") }.map { it.name.substringBefore('.') }
            val strip = uptime?.services.orEmpty().flatMap { it.days }.groupBy { it.date }.toSortedMap()
                .map { (_, d) -> d.mapNotNull { it.upPercent }.takeIf { it.isNotEmpty() }?.average() }.takeLast(60)
            val last = snap.deployments.maxByOrNull { parseTime(it.createdAt) ?: 0 }
            return Summary(
                snap.at, snap.containers.count { it.state == "running" }, dots, downNames.size, strip,
                downNames.take(3), building.firstOrNull()?.name,
                stats?.cpuPercent ?: 0.0, snap.health.resources.memUsedBytes, snap.health.resources.memTotalBytes,
                snap.health.disk.usedBytes, snap.health.disk.totalBytes, stats?.diskFullInDays, stats?.hostname.orEmpty(),
                last?.let { LastDeploy(it.deploymentId, snap.service(it.serviceId)?.name ?: it.title.orEmpty(), it.status.orEmpty(), it.title.orEmpty(), it.errorMessage?.lineSequence()?.firstOrNull { l -> l.isNotBlank() }.orEmpty(), parseTime(it.startedAt) ?: 0, parseTime(it.finishedAt) ?: 0) },
                snap.deployments.count { it.status == "error" && (parseTime(it.createdAt) ?: 0) > System.currentTimeMillis() - 86_400_000L },
            )
        }
    }
}

object Repo {
    private val _state = MutableStateFlow(PulseState())
    val state: StateFlow<PulseState> = _state

    suspend fun restore() {
        val cached = Store.readCache()?.let { runCatching { AppJson.decodeFromString<Snapshot>(it) }.getOrNull() } ?: return
        _state.update { if (it.snap == null) it.copy(snap = cached) else it }
    }

    fun reset() {
        _state.value = PulseState()
    }

    suspend fun choose(platform: String) {
        _state.update { it.copy(chosen = platform) }
        refresh()
    }

    suspend fun refresh(full: Boolean = false) {
        try {
            val old = _state.value.snap
            val caps = runCatching { Agent.capabilities() }.getOrElse { old?.caps ?: Caps() }
            val platform = caps.pick(_state.value.chosen)
            val api: Platform? = when (platform) { "dokploy" -> Dokploy; "coolify" -> Coolify; else -> null }
            val dokploy = api == Dokploy
            val snap = coroutineScope {
                val projects = async { api?.projects().orEmpty() }
                val stats = async { runCatching { Agent.stats() }.let { if (dokploy) it.getOrNull() else it.getOrThrow() } }
                val health = async {
                    when {
                        !dokploy -> stats.await()?.let { Health(resources = Resources(it.memTotalBytes, it.memUsedBytes, it.cpuCount), disk = DiskInfo(it.diskTotalBytes, it.diskUsedBytes)) } ?: Health()
                        old == null -> Dokploy.health()
                        else -> runCatching { Dokploy.health() }.getOrDefault(old.health)
                    }
                }
                val containers = async {
                    when {
                        dokploy -> if (old == null) Dokploy.containers() else runCatching { Dokploy.containers() }.getOrDefault(old.containers)
                        !caps.docker -> emptyList()
                        else -> runCatching { Agent.containers().map { Container(it.name, it.name, it.image, state = it.state, status = it.health.orEmpty(), appName = it.appName) } }
                            .getOrElse { old?.containers.orEmpty() }
                    }
                }
                val deployments = async { if (api == null) emptyList() else runCatching { api.deployments() }.getOrElse { old?.deployments.orEmpty() } }
                val destinations = async { if (dokploy) runCatching { Dokploy.destinations().size }.getOrElse { old?.destinations ?: 1 } else 0 }
                val uptime = async { runCatching { Agent.uptime() }.getOrNull() }
                val pending = async { if (Store.config.value?.owner == true) runCatching { Agent.devices().pending.size }.getOrNull() else null }
                _state.update { it.copy(stats = stats.await() ?: it.stats, uptime = uptime.await() ?: it.uptime, pending = pending.await() ?: it.pending) }
                Snapshot(
                    at = System.currentTimeMillis(),
                    projects = projects.await(),
                    health = health.await(),
                    containers = containers.await(),
                    deployments = deployments.await().sortedByDescending { parseTime(it.createdAt) ?: 0 }.take(60),
                    destinations = destinations.await(),
                    caps = caps,
                )
            }
            if (_state.value.link == Link.Unreachable) com.orvlabs.helm.ui.components.Haptics.fire(com.orvlabs.helm.ui.components.Feel.Recovery, key = "server")
            _state.update { it.copy(snap = snap, link = Link.Live, loading = false, error = null, failingSince = null) }
            Store.writeCache(AppJson.encodeToString(snap))
            val prev = Store.readSummary()
            var summary = Summary.from(snap, _state.value.uptime, _state.value.stats).let { n ->
                if (prev == null) n else n.copy(geo = prev.geo, totalBans = prev.totalBans, lockdownUntil = prev.lockdownUntil, night = prev.night, nextReboot = prev.nextReboot)
            }
            if (full) {
                val geo = runCatching { Agent.geo() }.getOrNull()
                val panic = runCatching { Agent.panic() }.getOrNull()
                val night = runCatching { Overnight.build() }.getOrNull()
                val upd = runCatching { Agent.updates() }.getOrNull()
                summary = summary.copy(
                    geo = geo?.countries?.filter { it.attempts24h > 0 || it.bans > 0 } ?: summary.geo, totalBans = geo?.totalBans ?: summary.totalBans,
                    lockdownUntil = if (panic?.active == true) panic.until ?: 0L else null, night = night?.let { Overnight.summary(it) } ?: summary.night,
                    nextReboot = upd?.nextReboot,
                )
            }
            Store.writeSummary(AppJson.encodeToString(summary))
            com.orvlabs.helm.service.WearPush.push(summary)
        } catch (e: ApiError) {
            val since = _state.value.failingSince ?: System.currentTimeMillis()
            val transient = (e.kind == ApiError.Kind.Unreachable || e.kind == ApiError.Kind.Offline || e.kind == ApiError.Kind.Server) &&
                System.currentTimeMillis() - since < 30_000
            if (transient) {
                _state.update { it.copy(loading = it.snap == null, failingSince = since) }
                return
            }
            if (e.kind == ApiError.Kind.Unreachable && _state.value.link != Link.Unreachable) com.orvlabs.helm.ui.components.Haptics.fire(com.orvlabs.helm.ui.components.Feel.Failure, key = "server")
            Store.readSummary()?.let { Store.writeSummary(AppJson.encodeToString(it.copy(linkDown = if (e.kind == ApiError.Kind.Auth || e.kind == ApiError.Kind.Revoked) "auth" else if (e.kind == ApiError.Kind.Unreachable) "unreachable" else null, linkSince = it.linkSince ?: System.currentTimeMillis()))) }
            _state.update {
                it.copy(
                    link = when (e.kind) {
                        ApiError.Kind.Offline -> Link.Offline
                        ApiError.Kind.Unreachable -> Link.Unreachable
                        ApiError.Kind.Auth, ApiError.Kind.Revoked -> Link.Auth
                        else -> Link.Live
                    },
                    loading = false,
                    error = e.message,
                    failingSince = it.failingSince ?: System.currentTimeMillis(),
                )
            }
        }
    }

    fun problems(s: Snapshot, stats: Stats? = null): List<Problem> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Problem>()
        s.deployments
            .filter { it.serviceId.isNotEmpty() }
            .groupBy { it.serviceId }
            .forEach { (id, list) ->
                val last = list.maxByOrNull { parseTime(it.createdAt) ?: 0 } ?: return@forEach
                val at = parseTime(last.createdAt) ?: return@forEach
                if (last.status == "error" && now - at < 24 * 3_600_000L) {
                    out += Problem(
                        true,
                        tr(R.string.build_failed_2, s.service(id)?.name ?: last.title.orEmpty()),
                        last.errorMessage?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: tr(R.string.the_last_deploy_failed),
                        at, "deploy", last.deploymentId,
                    )
                }
            }
        if (s.memPercent > 85 && s.health.resources.memTotalBytes > 0) {
            val free = s.health.resources.memTotalBytes - s.health.resources.memUsedBytes
            out += Problem(false, tr(R.string.only_mb_of_ram_free, free / 1_048_576), tr(R.string.memory_at, s.memPercent.toInt()), null, "server")
        }
        stats?.diskFullInDays?.takeIf { it < 14 }?.let {
            out += Problem(it < 7, trn(R.plurals.disk_full_about, it.toInt().coerceAtLeast(1)), tr(R.string.at_the_current_growth_rate), null, "server")
        }
        if (s.destinations == 0 && s.caps.dokploy) {
            out += Problem(true, tr(R.string.no_backups_leave_this_server), tr(R.string.no_backup_destination_in_dokploy), null, "backups")
        }
        return out.sortedByDescending { it.at ?: 0 }
    }
}
