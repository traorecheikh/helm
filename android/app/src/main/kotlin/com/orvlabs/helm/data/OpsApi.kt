package com.orvlabs.helm.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable data class WatchChange(val at: Long = 0, val sha: String = "", val added: Int = 0, val removed: Int = 0)
@Serializable data class WatchFile(val path: String = "", val changes: List<WatchChange> = emptyList())
@Serializable data class DiffLine(val type: String = "ctx", val text: String = "", val oldLine: Int? = null, val newLine: Int? = null)
@Serializable data class TimelineItem(val at: Long = 0, val kind: String = "", val title: String = "")
@Serializable data class IncidentRec(val id: String = "", val start: Long = 0, val end: Long? = null, val durationSec: Long = 0, val services: List<String> = emptyList(), val cause: String = "", val timeline: List<TimelineItem> = emptyList())
@Serializable data class DiskItem(val id: String = "", val count: Int = 0, val bytes: Long = 0)
@Serializable data class DiskPreview(val items: List<DiskItem> = emptyList(), val totalBytes: Long = 0)
@Serializable data class DiskCleaned(val freedBytes: Long = 0)
@Serializable data class CertRec(val domain: String = "", val issuer: String = "", val daysLeft: Double? = null, val routed: Boolean = true, val autoRenew: Boolean = false, val renewsInDays: Double? = null)
@Serializable data class DnsRec(val host: String = "", val a: List<String> = emptyList(), val status: String = "", val serverIp: String = "")
@Serializable data class CountryHits(val code: String = "", val count: Int = 0, val hits: Int = 0)
@Serializable data class WebPath(val path: String = "", val count: Long = 0, val avgMs: Double? = null, val p95Ms: Double? = null, val errors5xx: Long = 0)
@Serializable data class WebHost(val host: String = "", val requests: Long = 0, val requestsPerMin: List<Int> = emptyList(), val errors5xx: Long = 0, val errorRate: Double = 0.0, val p50Ms: Double? = null, val p95Ms: Double? = null, val uniqueVisitors: Long = 0, val topPaths: List<WebPath> = emptyList(), val slowestPaths: List<WebPath> = emptyList(), val countries: List<CountryHits> = emptyList())
@Serializable data class EnvHist(val at: Long = 0, val sha: String = "", val changedKeys: List<String> = emptyList())
@Serializable data class EnvDiffRow(val key: String = "", val type: String = "same", val old: String? = null, val new: String? = null)
@Serializable data class MailCheck(val ok: Boolean = false, val value: String? = null, val fix: String? = null, val fixFr: String? = null)
@Serializable data class MailDomain(val name: String = "", val mx: MailCheck = MailCheck(), val spf: MailCheck = MailCheck(), val dmarc: MailCheck = MailCheck(), val dkim: MailCheck = MailCheck())
@Serializable data class MailQueue(val size: Int = 0, val deferred: Int = 0)
@Serializable data class MailEvent(val at: Long = 0, val event: String = "", val ip: String = "", val domain: String = "", val detail: String = "")
@Serializable data class MailActivity(
    val logFound: Boolean = false,
    val lastInboundAt: Long? = null,
    val accepted24h: Int = 0,
    val rejected24h: Int = 0,
    val delivered24h: Int = 0,
    val outboundFailed24h: Int = 0,
    val authBans24h: Int = 0,
    val authFailures24h: Int = 0,
    val recentRejections: List<MailEvent> = emptyList(),
)
@Serializable data class MailInfo(val queue: MailQueue? = null, val domains: List<MailDomain> = emptyList())
@Serializable data class NoteRec(val serviceId: String = "", val text: String = "", val author: String = "", val at: Long = 0)
@Serializable data class ExposedHit(val host: String = "", val path: String = "", val status: Int = 0, val bytes: Long = 0, val lastAt: Long = 0)
@Serializable data class PathHits(val path: String = "", val hits: Int = 0, val statuses: Map<String, Int> = emptyMap())
@Serializable data class ProbeCat(val id: String = "", val nameEn: String = "", val nameFr: String = "", val hits: Int = 0, val ips: Int = 0, val topPaths: List<PathHits> = emptyList(), val topCountries: List<CountryHits> = emptyList(), val exposed: List<ExposedHit> = emptyList())
@Serializable data class Probes(val total: Int = 0, val exposedCount: Int = 0, val categories: List<ProbeCat> = emptyList())
@Serializable data class SecretFinding(val container: String = "", val pattern: String = "", val sample: String = "", val firstSeen: Long = 0, val lastSeen: Long = 0, val count: Int = 0)
@Serializable data class LogwatchSettings(val burst: Int = 20, val enabled: Boolean = true)
@Serializable data class Logwatch(val settings: LogwatchSettings = LogwatchSettings(), val detections: List<Event> = emptyList())
@Serializable data class ScheduleRun(val status: String? = null, val createdAt: String? = null)
@Serializable data class ScheduleJob(val scheduleId: String = "", val name: String = "", val cronExpression: String = "", val enabled: Boolean = true, val timezone: String? = null, val serviceName: String? = null, val projectName: String? = null, val deployments: List<ScheduleRun> = emptyList())
@Serializable data class HistPoint(val t: Long = 0, val cpuPercent: Double = 0.0, val memBytes: Long = 0)

object Ops {
    suspend fun updates(): Updates = apiGet("updates")
    suspend fun installUpdates() = apiPostIgnore("updates/install")
    suspend fun setAutoReboot(on: Boolean): JsonElement = apiPut("updates/settings", buildJsonObject { put("autoReboot", on) })
    suspend fun scheduleReboot(at: Long?) = apiPostIgnore("reboot/schedule", buildJsonObject { if (at == null) put("at", "quiet") else put("at", at) })
    suspend fun postponeReboot() = apiPostIgnore("reboot/cancel", buildJsonObject { put("confirm", "postpone") })
    suspend fun watchdog(): List<WatchFile> = apiGet("watchdog")
    suspend fun diff(path: String, a: String, b: String): List<DiffLine> = apiGet("watchdog/diff", "path" to path, "a" to a, "b" to b)
    suspend fun incidents(days: Int = 30): List<IncidentRec> = apiGet("incidents", "days" to days.toString())
    suspend fun diskPreview(): DiskPreview = apiGet("disk/preview")
    suspend fun diskClean(ids: List<String>): DiskCleaned = Net.call("POST", "disk/clean", emptyList(), buildJsonObject { put("items", JsonArray(ids.map { JsonPrimitive(it) })) }.toString(), slow = true)
        .let { AppJson.decodeFromString(it) }
    suspend fun certs(): List<CertRec> = apiGet("certs")
    suspend fun dns(): List<DnsRec> = apiGet("dns")
    suspend fun web(): List<WebHost> = apiGet("web")
    suspend fun envHistory(kind: String, id: String): List<EnvHist> = apiGet("env/history", "kind" to kind, "id" to id)
    suspend fun envDiff(kind: String, id: String, a: String, b: String, reveal: Boolean): List<EnvDiffRow> =
        apiGet("env/diff", "kind" to kind, "id" to id, "a" to a, "b" to b, "reveal" to if (reveal) "1" else "0")
    suspend fun secrets(): List<SecretFinding> = apiGet("secrets/findings")
    suspend fun mail(): MailInfo = apiGet("mail")
    suspend fun mailActivity(): MailActivity = apiGet("mail/activity")
    suspend fun notes(): List<NoteRec> = apiGet("notes")
    suspend fun addNote(serviceId: String, text: String) = apiPostIgnore("notes", buildJsonObject { put("serviceId", serviceId); put("text", text) })
    suspend fun logwatch(): Logwatch = apiGet("logwatch")
    suspend fun setLogwatch(burst: Int, enabled: Boolean): JsonElement = apiPut("logwatch/settings", buildJsonObject { put("burst", burst); put("enabled", enabled) })
    suspend fun probes(days: Int = 7): Probes = apiGet("probes", "days" to days.toString())
    suspend fun history(name: String, hours: Int = 24): List<HistPoint> = apiGet("containers/history", "name" to name, "hours" to hours.toString())
    suspend fun schedules(): List<ScheduleJob> = apiGet("schedules")
    suspend fun runSchedule(id: String) = apiPostIgnore("dokploy/schedule.runManually", buildJsonObject { put("scheduleId", id) })
    suspend fun rollback(id: String) = apiPostIgnore("dokploy/rollback.rollback", buildJsonObject { put("rollbackId", id) })
}
