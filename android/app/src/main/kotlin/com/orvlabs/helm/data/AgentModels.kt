package com.orvlabs.helm.data

import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.orvlabs.helm.isFrench

@Serializable data class DiskPoint(val t: Long = 0, val usedBytes: Long = 0)
@Serializable data class MemPoint(val t: Long = 0, val usedPercent: Double = 0.0)

@Serializable
data class Stats(
    val cpuPercent: Double = 0.0,
    val cpuCount: Int = 0,
    val memUsedBytes: Long = 0,
    val memTotalBytes: Long = 0,
    val diskUsedBytes: Long = 0,
    val diskTotalBytes: Long = 0,
    val uptimeSeconds: Long = 0,
    val hostname: String = "",
    val memHistory: List<MemPoint> = emptyList(),
    val diskHistory: List<DiskPoint> = emptyList(),
    val diskFullInDays: Double? = null,
)

@Serializable data class Ban(
    val ip: String = "",
    val source: String = "",
    val reason: String = "",
    val country: String? = null,
    val bannedAt: Long = 0,
    val reasonFr: String? = null,
    val jail: String = "",
)

@Serializable data class Allowed(val label: String = "", val detail: String = "")

@Serializable
data class Shield(
    val total: Int = 0,
    val bySource: Map<String, Int> = emptyMap(),
    val blocklistRanges: Int = 0,
    val recent: List<Ban> = emptyList(),
    val allowed: List<Allowed> = emptyList(),
)

@Serializable
data class Login(
    val at: Long = 0,
    val firstAt: Long? = null,
    val kind: String = "ssh",
    val user: String = "",
    val method: String = "",
    val ip: String = "",
    val country: String? = null,
    val count: Int = 1,
)

@Serializable data class DbName(val name: String = "", val sizeBytes: Long = 0)
@Serializable data class DbTable(val name: String = "", val bytes: Long = 0, val rows: Long = 0)

@Serializable
data class DbInfo(
    val container: String = "",
    val database: String = "",
    val databases: List<DbName> = emptyList(),
    val sizeBytes: Long = 0,
    val activeConnections: Int = 0,
    val maxConnections: Int = 0,
    val version: String = "",
    val largestTables: List<DbTable> = emptyList(),
)

@Serializable
data class QueryResult(
    val columns: List<String> = emptyList(),
    val rows: List<List<kotlinx.serialization.json.JsonElement>> = emptyList(),
    val truncated: Boolean = false,
    val summary: String? = null,
)

@Serializable
data class GeoCountry(
    val code: String = "",
    val name: String = "",
    val continent: String = "",
    val bans: Int = 0,
    val attempts24h: Int = 0,
    val blocked: Boolean = false,
    @kotlinx.serialization.SerialName("protected") val isProtected: Boolean = false,
)

@Serializable data class GeoContinent(val name: String = "", val blocked: Boolean = false, val countries: Int = 0, val bans: Int = 0)
@Serializable data class GeoRecent(val ip: String = "", val country: String? = null, val source: String = "", val at: Long = 0)

@Serializable
data class Geo(
    val countries: List<GeoCountry> = emptyList(),
    val continents: List<GeoContinent> = emptyList(),
    val recent: List<GeoRecent> = emptyList(),
    val blockedCountries: List<String> = emptyList(),
    val totalBans: Int = 0,
    val zonesLoaded: Boolean = true,
    val yourCountry: String? = null,
    val applied: GeoApplied? = null,
)

@Serializable data class GeoApplied(val active: Boolean = false, val ranges: Int = 0, val requested: Int = 0, val error: String? = null)
@Serializable data class LowTrust(val wanted: Boolean = false, val active: Boolean = false, val error: String? = null)
@Serializable data class OriginLock(val wanted: Boolean = false, val active: Boolean = false, val unproxied: List<String> = emptyList(), val error: String? = null)
@Serializable data class SshLock(val active: Boolean = false, val entries: Int = 0)
@Serializable data class AgentPort(val allowed: List<String> = emptyList(), val active: Boolean = false, val error: String? = null)
@Serializable data class Jail(val name: String = "", val banned: Int = 0, val totalBanned: Int = 0, val failing: Int = 0, val ips: List<String> = emptyList())
@Serializable data class Jails(val jails: List<Jail> = emptyList(), val error: String? = null)
@Serializable data class CsDecision(val ip: String = "", val type: String = "", val origin: String = "", val reason: String = "", val duration: String = "", val country: String = "")
@Serializable data class CsBouncer(val name: String = "", val type: String = "", val lastPull: String = "", val revoked: Boolean = false)
@Serializable data class CsSource(val source: String = "", val read: Long = 0, val parsed: Long = 0, val unparsed: Long = 0)
@Serializable data class CrowdSec(
    val installed: Boolean = false,
    val decisions: List<CsDecision> = emptyList(),
    val bouncers: List<CsBouncer> = emptyList(),
    val sources: List<CsSource> = emptyList(),
    val errors: List<String> = emptyList(),
    val error: String? = null,
)
@Serializable data class OpenPort(val port: Int = 0, val portEnd: Int = 0, val proto: String = "", val bind: String = "", val owner: String = "", val via: String = "", val restricted: Boolean = false)
@Serializable data class Exposure(val ports: List<OpenPort> = emptyList(), val error: String? = null)
@Serializable data class Security(
    val geo: GeoApplied = GeoApplied(),
    val lowTrust: LowTrust = LowTrust(),
    val originLock: OriginLock = OriginLock(),
    val panic: Panic = Panic(),
    val sshLock: SshLock = SshLock(),
    val agentPort: AgentPort = AgentPort(),
    val fail2ban: Jails = Jails(),
    val crowdsec: CrowdSec = CrowdSec(),
    val exposure: Exposure = Exposure(),
    val accessLog: String = "",
)
@Serializable data class SecEvent(val at: Long = 0, val source: String = "", val ip: String = "", val country: String = "", val what: String = "", val count: Int = 1, val count429: Int = 0, val by: String? = null)
@Serializable data class HitPath(val path: String = "", val count: Int = 0)
@Serializable data class Attacker(
    val ip: String = "",
    val country: String = "",
    val hits: Int = 0,
    val statuses: Map<String, Int> = emptyMap(),
    val topPaths: List<HitPath> = emptyList(),
    val host: String = "",
    val agent: String = "",
    val lastSeen: Long = 0,
    val banned: Boolean = false,
    val flags: List<String> = emptyList(),
    val probablyYou: Boolean = false,
)
@Serializable data class BanState(val ip: String = "", val jails: List<String> = emptyList(), val firewall: Boolean = false, val crowdsec: Boolean? = null)

@Serializable data class Panic(val active: Boolean = false, val until: Long? = null)

@Serializable
data class IpDetail(
    val ip: String = "",
    val source: String = "",
    val country: String? = null,
    val banned: Boolean = false,
    val onBlocklist: Boolean = false,
    val allowed: Boolean = false,
    val reason: String? = null,
    val reasonFr: String? = null,
    val bannedAt: Long? = null,
    val evidence: List<String> = emptyList(),
    val webRequests: List<String> = emptyList(),
    val webRequestCount: Int = 0,
    val sshLines: List<String> = emptyList(),
    val probablyYou: Boolean = false,
)

@Serializable
data class Event(
    val id: String = "",
    val type: String = "dokploy",
    val kind: String? = null,
    val title: String? = null,
    val message: String? = null,
    val ip: String? = null,
    val source: String? = null,
    val reason: String? = null,
    val container: String? = null,
    val titleFr: String? = null,
    val messageFr: String? = null,
    val reasonFr: String? = null,
    val at: Long = 0,
)

fun Event.titleText(): String? = if (isFrench) titleFr ?: title else title
fun Event.messageText(): String? = if (isFrench) messageFr ?: message else message
fun Event.reasonText(): String? = if (isFrench) reasonFr ?: reason else reason
fun Ban.reasonText(): String = (if (isFrench) reasonFr ?: reason else reason)
fun IpDetail.reasonText(): String? = if (isFrench) reasonFr ?: reason else reason

@Serializable data class LogLine(val t: String = "", val line: String = "", val src: String = "")
@Serializable data class AgentHealth(val ok: Boolean = false, val version: String = "", val claimed: Boolean = false)
@Serializable data class PairClaim(val deviceId: String = "", val role: String = "member")
@Serializable data class PairRequest(val requestId: String = "", val fingerprint: String? = null)
@Serializable data class PairStatus(val status: String = "pending", val deviceId: String? = null)

@Serializable data class UptimeDay(val date: String = "", val upPercent: Double? = null, val downMinutes: Int = 0)
@Serializable data class Incident(val start: Long = 0, val end: Long? = null, val reason: String = "")

@Serializable
data class UptimeService(
    val name: String = "",
    val kind: String = "container",
    val target: String? = null,
    val current: String = "up",
    val uptimePercent: Double? = null,
    val days: List<UptimeDay> = emptyList(),
    val incidents: List<Incident> = emptyList(),
    val latencyMs: Double? = null,
    val certExpiresAt: Long? = null,
)

@Serializable data class Uptime(val services: List<UptimeService> = emptyList(), val since: Long = 0)

@Serializable
data class AgentContainer(
    val name: String = "",
    val service: String? = null,
    val image: String = "",
    val state: String = "",
    val health: String? = null,
    val cpuPercent: Double = 0.0,
    val memBytes: Long = 0,
    val memLimitBytes: Long? = null,
    val restarts: Int = 0,
    val startedAt: String? = null,
    val appName: String? = null,
    val history: List<Long> = emptyList(),
)

@Serializable data class Proc(val name: String = "", val cpuPercent: Double = 0.0, val memBytes: Long = 0)
@Serializable
data class DockerDisk(val imagesBytes: Long = 0, val containersBytes: Long = 0, val volumesBytes: Long = 0, val buildCacheBytes: Long = 0)

@Serializable
data class HostInfo(
    val load: List<Double> = emptyList(),
    val netRxBytesPerSec: Double = 0.0,
    val netTxBytesPerSec: Double = 0.0,
    val topProcesses: List<Proc> = emptyList(),
    val pendingUpdates: Int = 0,
    val securityUpdates: Int = 0,
    val rebootRequired: Boolean = false,
    val failedSshLogins24h: Int = 0,
    val dockerDisk: DockerDisk? = null,
)

@Serializable
data class DeviceInfo(
    val deviceId: String = "",
    val name: String = "",
    val role: String = "member",
    val addedAt: Long? = null,
    val lastSeenAt: Long? = null,
    val lastIp: String? = null,
)

@Serializable
data class PendingDevice(
    val requestId: String = "",
    val deviceName: String = "",
    val ip: String? = null,
    val requestedAt: Long? = null,
    val fingerprint: String = "",
)

@Serializable data class Devices(val devices: List<DeviceInfo> = emptyList(), val pending: List<PendingDevice> = emptyList())

@Serializable
data class AuditEntry(
    val at: Long = 0,
    val deviceName: String? = null,
    val action: String = "",
    val detail: String? = null,
    val ip: String? = null,
)


@Serializable data class UpdatePkg(val name: String = "", val from: String = "", val to: String = "", val group: String = "system")

@Serializable
data class Updates(
    val pending: List<UpdatePkg> = emptyList(),
    val held: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    val rebootRequired: Boolean = false,
    val rebootReason: String? = null,
    val rebootReasonFr: String? = null,
    val runningKernel: String? = null,
    val nextReboot: Long? = null,
    val scheduledReboot: Long? = null,
    val lastReboot: RebootRun? = null,
    val autoReboot: Boolean = false,
    val learning: Boolean = false,
    val learningDays: Int = 0,
    val requiredSince: Long? = null,
    val postponements: Int = 0,
    val deadline: Long? = null,
    val `final`: Boolean = false,
    val unattended: Unattended? = null,
    val kernelPending: Boolean = false,
    val microcodePending: Boolean = false,
)

@Serializable data class RebootRun(val at: Long = 0, val result: String = "")
