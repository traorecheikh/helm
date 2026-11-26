package com.orvlabs.helm.ml

import com.orvlabs.helm.R
import com.orvlabs.helm.Str
import com.orvlabs.helm.appLocale
import com.orvlabs.helm.data.Problem
import com.orvlabs.helm.tr
import com.orvlabs.helm.data.PulseState
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class Scope { Week, Day, Any }

data class Usual(val median: Double, val mad: Double, val scope: Scope)

data class Anomaly(val metric: String, val factor: Double, val since: Long, val value: Double, val usual: Usual, val at: Long)

private const val SLOTS = 4
private const val BUCKETS = 168

internal class Series {
    val slots = FloatArray(BUCKETS * SLOTS) { Float.NaN }
    val next = ByteArray(BUCKETS)
    var hour = -1L
    var sum = 0.0
    var n = 0

    fun add(t: Long, v: Double, zone: ZoneId) {
        val h = t / 3600
        if (h < hour) return
        if (h != hour) { commit(zone); hour = h; sum = 0.0; n = 0 }
        sum += v
        n++
    }

    private fun commit(zone: ZoneId) {
        if (n == 0 || hour < 0) return
        val b = bucket(hour * 3600, zone)
        slots[b * SLOTS + next[b]] = (sum / n).toFloat()
        next[b] = ((next[b] + 1) % SLOTS).toByte()
    }

    private fun values(b: Int) = (0 until SLOTS).map { slots[b * SLOTS + it] }.filter { !it.isNaN() }.map { it.toDouble() }

    fun usual(t: Long, zone: ZoneId): Usual? {
        val b = bucket(t, zone)
        val week = values(b)
        if (week.size >= 3) return robust(week, Scope.Week)
        val day = (0 until 7).flatMap { values(it * 24 + b % 24) }
        if (day.size >= 4) return robust(day, Scope.Day)
        val all = (0 until BUCKETS).flatMap(::values)
        return if (all.size >= 8) robust(all, Scope.Any) else null
    }
}

internal fun bucket(t: Long, zone: ZoneId): Int {
    val z = Instant.ofEpochSecond(t).atZone(zone)
    return (z.dayOfWeek.value - 1) * 24 + z.hour
}

private fun median(v: List<Double>): Double = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

private fun robust(v: List<Double>, scope: Scope): Usual {
    val m = median(v)
    return Usual(m, median(v.map { kotlin.math.abs(it - m) }), scope)
}

private data class Rule(val minValue: Double, val minFactor: Double, val floor: Double)

private fun rule(metric: String) = when {
    metric == "cpu" -> Rule(25.0, 2.0, 2.0)
    metric == "ram" -> Rule(60.0, 1.3, 5.0)
    metric == "disk" -> Rule(60.0, 1.15, 5.0)
    else -> Rule(300.0, 2.0, 20.0)
}

class Baselines(private val zone: ZoneId = ZoneId.systemDefault()) {
    internal val series = LinkedHashMap<String, Series>()
    private val since = HashMap<String, Long>()
    private val streak = HashMap<String, Int>()

    fun observe(t: Long, metrics: Map<String, Double>, seeds: Map<String, List<Pair<Long, Double>>> = emptyMap()): List<Anomaly> {
        val out = mutableListOf<Anomaly>()
        for ((metric, value) in metrics) {
            val s = series.getOrPut(metric) { Series().also { fresh -> seeds[metric].orEmpty().sortedBy { it.first }.forEach { fresh.add(it.first, it.second, zone) } } }
            val usual = s.usual(t, zone)
            s.add(t, value, zone)
            val r = rule(metric)
            val base = maxOf(usual?.median ?: 0.0, r.floor)
            val spread = maxOf(1.4826 * (usual?.mad ?: 0.0), base * 0.1, r.floor)
            val flagged = usual != null && value >= r.minValue && value / base >= r.minFactor && (value - base) / spread >= 4
            if (!flagged) { since.remove(metric); streak.remove(metric); continue }
            val first = since.getOrPut(metric) { t }
            val n = (streak[metric] ?: 0) + 1
            streak[metric] = n
            if (n >= 2 && t - first >= 60) out += Anomaly(metric, value / base, first, value, usual!!, t)
        }
        return out.sortedByDescending { it.factor }
    }

    fun write(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(1)
            o.writeInt(series.size)
            series.forEach { (k, s) ->
                o.writeUTF(k); o.writeLong(s.hour); o.writeDouble(s.sum); o.writeInt(s.n)
                s.slots.forEach(o::writeFloat)
                o.write(s.next)
            }
        }
        tmp.renameTo(file)
    }

    fun read(file: File) {
        if (!file.exists()) return
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { i ->
                if (i.readInt() != 1) return
                repeat(i.readInt()) {
                    val k = i.readUTF()
                    val s = Series().apply { hour = i.readLong(); sum = i.readDouble(); n = i.readInt() }
                    for (j in s.slots.indices) s.slots[j] = i.readFloat()
                    i.readFully(s.next)
                    series[k] = s
                }
            }
        }.onFailure { series.clear() }
    }
}

private const val MAX_LATENCY_SERIES = 30

fun metricsOf(s: PulseState): Map<String, Double> {
    val snap = s.snap ?: return emptyMap()
    val out = LinkedHashMap<String, Double>()
    s.stats?.let { out["cpu"] = it.cpuPercent }
    if (snap.health.resources.memTotalBytes > 0) out["ram"] = snap.memPercent
    snap.health.disk.takeIf { it.totalBytes > 0 }?.let { out["disk"] = it.usedBytes * 100.0 / it.totalBytes }
    s.uptime?.services.orEmpty().filter { it.current == "up" }.take(MAX_LATENCY_SERIES).forEach { u -> u.latencyMs?.let { out["lat:${u.name}"] = it } }
    return out
}

private fun seedsOf(s: PulseState): Map<String, List<Pair<Long, Double>>> {
    val st = s.stats ?: return emptyMap()
    val total = st.diskTotalBytes.takeIf { it > 0 }
    return mapOf(
        "ram" to st.memHistory.map { it.t to it.usedPercent },
        "disk" to if (total == null) emptyList() else st.diskHistory.map { it.t to it.usedBytes * 100.0 / total },
    )
}

object AnomalyWatch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _found = MutableStateFlow<List<Anomaly>>(emptyList())
    val found: StateFlow<List<Anomaly>> = _found
    private var started = false
    private var server = -1
    private var model = Baselines()
    private var savedAt = 0L

    private fun file(id: Int) = File(Str.app.filesDir, "ml/anomaly_$id.bin")

    fun start() {
        if (started) return
        started = true
        scope.launch {
            Repo.state.distinctUntilChangedBy { it.snap?.at }.collect { s ->
                val snap = s.snap ?: return@collect
                val id = Store.config.value?.activeId ?: 0
                if (id != server) {
                    if (server >= 0) model.write(file(server))
                    model = Baselines().also { it.read(file(id)) }
                    server = id
                }
                _found.value = model.observe(snap.at / 1000, metricsOf(s), seedsOf(s))
                if (snap.at - savedAt > 10 * 60_000L) { savedAt = snap.at; runCatching { model.write(file(id)) } }
            }
        }
    }
}

private fun Anomaly.name() = when (metric) {
    "cpu" -> tr(R.string.cpu_2)
    "ram" -> tr(R.string.home_ram)
    "disk" -> tr(R.string.disk)
    else -> tr(R.string.ml_ping, metric.removePrefix("lat:"))
}

private fun Anomaly.amount(v: Double) = if (metric.startsWith("lat:")) "${v.roundToInt()} ms" else "${v.roundToInt()}%"

private fun Anomaly.times(): String {
    val r = if (factor >= 10) factor.roundToInt().toDouble() else (factor * 10).roundToInt() / 10.0
    return if (r % 1.0 == 0.0) "${r.toInt()}" else "%.1f".format(appLocale, r)
}

fun Anomaly.chip() = tr(R.string.ml_anom_chip, name(), times())

fun Anomaly.problem(): Problem {
    val whenText = DateTimeFormatter.ofPattern(if (usual.scope == Scope.Week) "EEE HH:00" else "HH:00", appLocale).format(Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault()))
    val sub = if (usual.scope == Scope.Any) tr(R.string.ml_anom_any, amount(usual.median)) else tr(R.string.ml_anom_at, whenText, amount(usual.median))
    return Problem(false, tr(R.string.ml_anom, name(), times()), sub, since * 1000, "server")
}
