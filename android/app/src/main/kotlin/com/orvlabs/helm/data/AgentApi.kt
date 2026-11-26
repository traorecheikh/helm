package com.orvlabs.helm.data

import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val FAILURE_SYSTEM = setOf("container_died", "oom", "unhealthy", "service_down", "cert_expiring", "disk_high", "pair_request", "new_login_place", "panic_on", "config_changed", "docker_restarted", "secret_leak", "log_crash", "log_error_burst", "network_exhausting", "cron_late", "updates_failed", "reboot_ineffective", "reboot_final", "exposure", "mail_silent", "mail_rejects", "vuln_critical", "autofix_failed")

fun Event.alertKey() = when {
    type == "ban" || kind == "threat" || kind == "threat_digest" -> "ban"
    type == "system" && kind == "geo_changed" -> "activity"
    type == "system" -> if (kind in FAILURE_SYSTEM) "server" else "activity"
    kind == "build_error" -> "build"
    kind == "threshold" || kind == "restart" -> "server"
    kind == "backup_error" -> "backup"
    else -> "activity"
}

fun Event.isFailure() = when (type) {
    "system" -> kind in FAILURE_SYSTEM
    "dokploy" -> kind in setOf("build_error", "backup_error", "threshold", "restart")
    else -> false
}

fun Event.isPairRequest() = type == "system" && kind == "pair_request"

object Agent {
    val configured get() = Store.config.value?.deviceId?.isNotEmpty() == true
    val deviceName: String get() = Build.MODEL

    suspend fun health(url: String): AgentHealth = apiGet("health", base = url)

    suspend fun claim(id: Int, url: String, code: String): PairClaim = apiPost(
        "pair/claim",
        buildJsonObject { put("code", code); put("publicKey", Device.publicKey(id)); put("deviceName", deviceName) },
        url,
    )

    suspend fun requestPairing(id: Int, url: String): PairRequest = apiPost(
        "pair/request",
        buildJsonObject { put("publicKey", Device.publicKey(id)); put("deviceName", deviceName) },
        url,
    )

    suspend fun pairStatus(url: String, requestId: String): PairStatus = apiGet("pair/status", "requestId" to requestId, base = url)

    suspend fun stats(): Stats = apiGet("stats")
    suspend fun capabilities(): Caps = apiGet("capabilities")
    suspend fun shield(): Shield = apiGet("shield")
    suspend fun ip(ip: String): IpDetail = apiGet("shield/ip/$ip")
    suspend fun history(): List<Event> = apiGet("events/history", "limit" to "100")
    suspend fun history300(): List<Event> = apiGet("events/history", "limit" to "300")
    suspend fun uptime(days: Int = 60): Uptime = apiGet("uptime", "days" to days.toString())
    suspend fun containers(): List<AgentContainer> = apiGet("containers")
    suspend fun dbInfo(container: String, database: String): DbInfo =
        if (database.isBlank()) apiGet("db/$container") else apiGet("db/$container", "database" to database)

    suspend fun dbQuery(container: String, database: String, sql: String): QueryResult =
        apiPost("db/$container/query", buildJsonObject { put("sql", sql); put("database", database) })

    suspend fun geo(): Geo = apiGet("geo")

    suspend fun setBlock(countries: Collection<String>, continents: Collection<String>) = apiPostIgnore(
        "geo/block",
        buildJsonObject {
            put("countries", kotlinx.serialization.json.JsonArray(countries.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("continents", kotlinx.serialization.json.JsonArray(continents.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        },
    )

    suspend fun ban(ip: String, duration: String = "24h", ownNetwork: Boolean = false): BanState = apiPost(
        "shield/ban",
        buildJsonObject { put("ip", ip); put("duration", duration); put("confirmOwnNetwork", ownNetwork); put("confirmPermanent", duration == "permanent") },
    )
    suspend fun live(): List<Attacker> = apiGet("security/live")
    suspend fun security(): Security = apiGet("security")
    suspend fun securityLog(): List<SecEvent> = apiGet("security/log")
    suspend fun setLowTrust(on: Boolean): LowTrust = apiPost("geo/lowtrust", buildJsonObject { put("enabled", on) })
    suspend fun setOriginLock(on: Boolean): OriginLock = apiPost("shield/originlock", buildJsonObject { put("enabled", on) })

    suspend fun updates(): Updates = apiGet("updates")

    suspend fun logins(): List<Login> = apiGet("logins")
    suspend fun panic(): Panic = apiGet("shield/panic")
    suspend fun setPanic(minutes: Int): Panic = apiPost("shield/panic", buildJsonObject { put("minutes", minutes) })
    suspend fun host(): HostInfo = apiGet("host")
    suspend fun devices(): Devices = apiGet("devices")
    suspend fun audit(): List<AuditEntry> = apiGet("audit", "limit" to "100")

    suspend fun unban(ip: String): BanState = apiPost("shield/unban", buildJsonObject { put("ip", ip) })
    suspend fun allow(ip: String): BanState = apiPost("shield/allow", buildJsonObject { put("ip", ip) })
    suspend fun approve(requestId: String) = apiPostIgnore("devices/approve", buildJsonObject { put("requestId", requestId) })
    suspend fun deny(requestId: String) = apiPostIgnore("devices/deny", buildJsonObject { put("requestId", requestId) })
    suspend fun revoke(deviceId: String) = apiPostIgnore("devices/revoke", buildJsonObject { put("deviceId", deviceId) })

    fun events(server: Server? = null): Flow<SseEvent> = Net.sse("events", server = server)

    fun decodeEvent(e: SseEvent): Event? = runCatching {
        when (e.type) {
            "dokploy", "ban", "system" -> AppJson.decodeFromString<Event>(e.data).copy(type = e.type)
            else -> null
        }
    }.getOrNull()

    private fun lines(flow: Flow<SseEvent>): Flow<LogLine> = flow
        .filter { it.type == "line" }
        .map { AppJson.decodeFromString<LogLine>(it.data) }

    fun containerLogs(name: String, tail: Int = 200) = lines(Net.sse("logs/container/$name", listOf("tail" to tail.toString())))
    fun deploymentLogs(logPath: String) = lines(Net.sse("logs/deployment", listOf("path" to logPath)))
}
