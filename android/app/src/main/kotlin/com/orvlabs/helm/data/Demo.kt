package com.orvlabs.helm.data

import com.orvlabs.helm.Str
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val DEMO_URL = "https://demo.helm.app/helm"
const val DEMO_DEVICE = "demo"

/**
 * Canned answers for the demo server: assets/demo/<path with '/' as '_'>.json.
 * Whole-value placeholders move with the clock: "{{ts-90s}}" epoch seconds, "{{iso-2h}}" ISO instant, "{{day-3d}}" date.
 * "{{status:<id>:<default>}}" follows the in-memory redeploy state; "{{name}}" "{{appName}}" "{{slug}}" "{{id}}" "{{container}}" are per-request.
 */
object Demo {
    private const val DEPLOY_MS = 18_000L
    private const val YOU = "192.0.2.10"
    private val COUNTRIES = listOf("RU", "CN", "BR", "US", "DE", "IN", "NL")
    private val deploys: MutableMap<String, Pair<String, Long>> = ConcurrentHashMap()
    private val added: MutableMap<String, Ban> = ConcurrentHashMap()
    private val removed = ConcurrentHashMap.newKeySet<String>()
    private val stamp = Regex(""""\{\{(ts|iso|day)([-+])(\d+)([smhd])\}\}"""")
    private val status = Regex("""\{\{status:([^:}]+):(\w+)\}\}""")
    private val liveLog = Regex("""live-(\d+)\.log""")

    fun owns(base: String) = base.startsWith(DEMO_URL)
    private val active get() = Store.config.value?.url == DEMO_URL

    fun fill(raw: String, now: Long = System.currentTimeMillis()): String = raw
        .replace(stamp) { m ->
            val (kind, sign, n, unit) = m.destructured
            val sec = n.toLong() * when (unit) { "m" -> 60; "h" -> 3600; "d" -> 86_400; else -> 1 }
            val at = now + if (sign == "-") -sec * 1000 else sec * 1000
            when (kind) {
                "ts" -> (at / 1000).toString()
                "iso" -> "\"${Instant.ofEpochMilli(at)}\""
                else -> "\"${LocalDate.ofInstant(Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault())}\""
            }
        }
        .replace(status) { m ->
            val (id, default) = m.destructured
            val started = deploys[id]?.second
            when {
                started == null -> default
                now - started < DEPLOY_MS -> "running"
                default == "idle" -> "done"
                else -> default
            }
        }

    private fun asset(name: String): String? =
        runCatching { Str.app.assets.open("demo/$name.json").bufferedReader().use { it.readText() } }.getOrNull()

    private fun key(path: String) = path
        .replace(Regex("^db/[^/]+"), "db")
        .replace(Regex("^shield/ip/.*"), "shield/ip")
        .replace(Regex("domain\\.by\\w+"), "domain")
        .replace('/', '_')

    private fun countryOf(ip: String) = COUNTRIES[(ip.substringAfterLast('.').toIntOrNull() ?: 0) % COUNTRIES.size]

    suspend fun call(method: String, path: String, query: List<Pair<String, String>>, body: String?): String =
        withContext(Dispatchers.IO) {
            if (method == "GET") read(path, query.firstOrNull()?.second.orEmpty()) else write(path, body)
        }

    private fun projects() = AppJson.decodeFromString<List<Project>>(fill(asset("dokploy_project.all")!!))

    private fun service(id: String) = projects().flatMap { it.services }.firstOrNull { it.id == id }

    private fun read(path: String, arg: String): String {
        if (path.startsWith("dokploy/deployment.")) return deployments(arg)
        val vars = mutableMapOf("id" to arg, "container" to path.removePrefix("db/").substringBefore('/'))
        if (path.startsWith("dokploy/") && arg.isNotEmpty()) service(arg)?.let {
            vars += mapOf("name" to it.name, "appName" to it.appName.orEmpty(), "slug" to it.appName.orEmpty().substringBeforeLast('-'), "status" to it.status)
        }
        val raw = asset(key(path)) ?: throw ApiError("No demo data for $path", ApiError.Kind.Other)
        var text = vars.entries.fold(fill(raw)) { t, (k, v) -> t.replace("{{$k}}", v) }
        text = when (key(path)) {
            "shield" -> AppJson.decodeFromString<Shield>(text).let { s ->
                val gone = s.recent.count { it.ip in removed }
                AppJson.encodeToString(s.copy(
                    total = s.total + added.size - gone,
                    bySource = s.bySource + ("manual" to (s.bySource["manual"] ?: 0) + added.size),
                    recent = added.values.toList() + s.recent.filter { it.ip !in removed },
                ))
            }
            "security_live" -> AppJson.encodeToString(AppJson.decodeFromString<List<Attacker>>(text).map { it.copy(banned = it.ip in added || (it.banned && it.ip !in removed)) })
            "shield_ip" -> {
                val ip = path.substringAfterLast('/')
                val live = AppJson.decodeFromString<List<Attacker>>(fill(asset("security_live")!!)).firstOrNull { it.ip == ip }
                val ban = added[ip] ?: AppJson.decodeFromString<Shield>(fill(asset("shield")!!)).recent.firstOrNull { it.ip == ip && ip !in removed }
                AppJson.encodeToString(AppJson.decodeFromString<IpDetail>(text).copy(
                    ip = ip, country = live?.country ?: ban?.country ?: countryOf(ip), banned = ban != null,
                    bannedAt = ban?.bannedAt, probablyYou = ip == YOU, reason = ban?.reason ?: "Probing for exposed files",
                ))
            }
            else -> text
        }
        return text
    }

    private fun deployments(id: String): String {
        val all = AppJson.parseToJsonElement(fill(asset("dokploy_deployment.allCentralized")!!)).jsonArray
        val own = if (id.isEmpty()) all else all.filter { it.jsonObject.run { (get("applicationId") ?: get("composeId"))?.jsonPrimitive?.content == id } }
        val live = deploys.entries.filter { id.isEmpty() || it.key == id }.mapNotNull { (svc, run) ->
            val s = service(svc) ?: return@mapNotNull null
            val (kind, at) = run
            val done = System.currentTimeMillis() - at >= DEPLOY_MS
            buildJsonObject {
                put("deploymentId", "demo-live-$at")
                put("title", "Redeploy from Helm")
                put("description", "Manual redeploy of ${s.name}")
                put("status", if (done) "done" else "running")
                put("logPath", "demo/deploy/live-$at.log")
                put(if (kind == "compose") "composeId" else "applicationId", svc)
                put("createdAt", Instant.ofEpochMilli(at).toString())
                put("startedAt", Instant.ofEpochMilli(at + 1000).toString())
                if (done) put("finishedAt", Instant.ofEpochMilli(at + DEPLOY_MS).toString())
            }
        }
        return JsonArray(live + own).toString()
    }

    private fun write(path: String, body: String?): String {
        val args = body?.let { runCatching { AppJson.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        Regex("""^dokploy/(\w+)\.(?:re)?deploy$""").find(path)?.let { m ->
            args.values.firstOrNull()?.jsonPrimitive?.content?.let { deploys[it] = m.groupValues[1] to System.currentTimeMillis() }
        }
        val ip = args["ip"]?.jsonPrimitive?.content
        if (path == "shield/ban" && ip != null) ban(ip, "Banned from Helm", null, "manual")
        if (path == "shield/unban" && ip != null) { added.remove(ip); removed += ip }
        return asset(key(path))?.let { fill(it) } ?: "{}"
    }

    private fun ban(ip: String, reason: String, reasonFr: String?, source: String) {
        removed -= ip
        added[ip] = Ban(ip, source, reason, countryOf(ip), System.currentTimeMillis() / 1000, reasonFr)
    }

    fun sse(path: String, query: List<Pair<String, String>>): Flow<SseEvent> = flow {
        when (path) {
            "events" -> events()
            "logs/deployment" -> deployLog(query.firstOrNull()?.second.orEmpty())
            else -> containerLog(query.firstOrNull { it.first == "tail" }?.second?.toIntOrNull() ?: 200)
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<SseEvent>.events() {
        val script = AppJson.decodeFromString<List<Event>>(fill(asset("events")!!))
        emit(SseEvent("open", ""))
        var n = 0
        delay(8_000)
        while (true) {
            if (active) {
                val e = script[n++ % script.size]
                if (e.type == "ban" && e.ip != null) ban(e.ip, e.reason.orEmpty(), e.reasonFr, "web")
                val now = System.currentTimeMillis()
                emit(SseEvent(e.type, AppJson.encodeToString(e.copy(id = "demo-$now", at = now / 1000))))
            }
            delay(20_000)
        }
    }

    private fun lines(name: String) = AppJson.decodeFromString<List<LogLine>>(asset(name)!!)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<SseEvent>.line(l: LogLine, ago: Long = 0) =
        emit(SseEvent("line", AppJson.encodeToString(l.copy(t = Instant.now().minusSeconds(ago).toString()))))

    private suspend fun kotlinx.coroutines.flow.FlowCollector<SseEvent>.deployLog(logPath: String) {
        val lines = lines("logs_deployment")
        val building = logPath.endsWith("building.log")
        val startedAt = liveLog.find(logPath)?.groupValues?.get(1)?.toLong()
        val pace = when {
            building -> 900L
            startedAt != null -> (startedAt + DEPLOY_MS - System.currentTimeMillis()).coerceAtLeast(0) / lines.size
            else -> 0L
        }
        val shown = if (building) lines.dropLast(2) else lines
        shown.forEachIndexed { i, l ->
            if (pace > 0) delay(pace)
            line(l, if (pace > 0) 0 else (shown.size - i).toLong())
        }
        if (building) awaitCancellation()
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<SseEvent>.containerLog(tail: Int) {
        val lines = lines("logs_container")
        lines.takeLast(tail).forEachIndexed { i, l -> line(l, (lines.size - i) * 3L) }
        var n = 0
        while (true) {
            delay(3_000)
            line(lines[1 + n++ % (lines.size - 1)])
        }
    }
}
