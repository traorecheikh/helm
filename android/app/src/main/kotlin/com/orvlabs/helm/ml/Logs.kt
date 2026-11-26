package com.orvlabs.helm.ml

import com.orvlabs.helm.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.K
import com.orvlabs.helm.ui.components.LogBox
import com.orvlabs.helm.data.LogLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Cluster(val template: String, val count: Int, val first: String, val last: String, val src: String = "")

private const val MASK = "<*>"
private const val MAX_CLUSTERS = 400
private val STAMP = Regex("""^\[?\d{4}-\d{2}-\d{2}[T ][\d:.,]+(Z|[+-]\d{2}:?\d{2})?]?\s*""")
private val VARIABLE = Regex("""(?i)^(.*\d.*|[0-9a-f]{8,}|[\w.+-]+@[\w.-]+|https?://\S+|"[^"]{24,}")$""")

private fun tokens(line: String): List<String> =
    line.replace(STAMP, "").take(400).split(' ', '\t').filter { it.isNotEmpty() }.take(48).map { if (VARIABLE.matches(it)) MASK else it }

private class Group(val tokens: MutableList<String>, var count: Int, val first: String, var last: String, val src: String)

fun mine(lines: List<LogLine>, threshold: Double = 0.5): List<Cluster> {
    val tree = HashMap<String, MutableList<Group>>()
    var total = 0
    for (l in lines) {
        val t = tokens(l.line)
        if (t.isEmpty()) continue
        val key = "${l.src}|${t.size}:${t.first().takeIf { it != MASK }.orEmpty()}"
        val bucket = tree.getOrPut(key) { mutableListOf() }
        val match = bucket.maxByOrNull { g -> g.tokens.indices.count { g.tokens[it] == t[it] || g.tokens[it] == MASK } }
        val similar = match != null && match.tokens.indices.count { match.tokens[it] == t[it] }.toDouble() / t.size >= threshold
        if (similar) {
            for (i in t.indices) if (match!!.tokens[i] != t[i]) match.tokens[i] = MASK
            match!!.count++
            match.last = l.t
        } else if (total < MAX_CLUSTERS) {
            bucket += Group(t.toMutableList(), 1, l.t, l.t, l.src)
            total++
        }
    }
    return tree.values.flatten().map { Cluster(it.tokens.joinToString(" "), it.count, it.first, it.last, it.src) }.sortedByDescending { it.count }
}

enum class Fix(val label: Int) { Redeploy(R.string.ml_fix_redeploy), Env(R.string.ml_fix_env), Resources(R.string.ml_fix_resources), Server(R.string.ml_fix_server) }

enum class CauseKind(val label: Int, val fix: Fix?) {
    Oom(R.string.ml_c_oom, Fix.Resources),
    MissingEnv(R.string.ml_c_env, Fix.Env),
    PortInUse(R.string.ml_c_port, Fix.Redeploy),
    Dependency(R.string.ml_c_deps, Fix.Redeploy),
    Build(R.string.ml_c_build, null),
    DiskFull(R.string.ml_c_disk, Fix.Server),
    Registry(R.string.ml_c_registry, null),
    HealthTimeout(R.string.ml_c_health, Fix.Env),
}

data class Cause(val kind: CauseKind, val confidence: Double, val evidence: String)

private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

private val SIGNS: Map<CauseKind, List<Pair<Regex, Int>>> = mapOf(
    CauseKind.Oom to listOf(
        r("""out of memory|cannot allocate memory|\benomem\b""") to 3, r("""oomkilled|exit(ed with)? code:? 137|signal: killed""") to 4,
        r("""javascript heap out of memory|allocation failed""") to 4, r("""\bkilled\b""") to 1,
    ),
    CauseKind.MissingEnv to listOf(
        r("""(environment variable|env var)\S*\s.*(not set|missing|required|undefined|is not defined)""") to 4,
        r("""invalid environment variables|missing required (env|environment)""") to 4,
        Regex("""\b[A-Z][A-Z0-9_]{2,}\b (is |was )?(not set|not defined|missing|required|undefined)""") to 3,
        Regex("""KeyError: '[A-Z][A-Z0-9_]+'|process\.env\.[A-Z][A-Z0-9_]+""") to 3, r("""must be set|must be defined""") to 2,
    ),
    CauseKind.PortInUse to listOf(r("""address already in use|eaddrinuse|port is already allocated""") to 5, r("""port \d+ (is )?already in use""") to 4),
    CauseKind.Dependency to listOf(
        r("""could not resolve dependenc|\beresolve\b|no matching version|err_pnpm|lockfile.*(out of date|not up to date)|frozen-lockfile""") to 4,
        r("""could not find a version that satisfies|no matching distribution|unknown revision|cannot find module""") to 3,
        r("""npm err!|yarn err|pip.*error|etimedout|econnreset|eai_again""") to 2, r("""failed to fetch|network error""") to 1,
    ),
    CauseKind.Build to listOf(
        r("""error ts\d+|error\[e\d+]|failed to compile|compilation failed|cannot find symbol""") to 4,
        r("""syntaxerror|type error|module not found: error|unresolved reference""") to 3,
        r("""\.(ts|tsx|js|jsx|go|rs|java|kt|py):\d+""") to 2, r("""build failed|did not complete successfully|exit code: 1\b""") to 1,
    ),
    CauseKind.DiskFull to listOf(r("""no space left on device|\benospc\b""") to 6, r("""disk quota exceeded""") to 4),
    CauseKind.Registry to listOf(
        r("""unauthorized: authentication required|denied: requested access|pull access denied|toomanyrequests""") to 4,
        r("""manifest unknown|repository does not exist|could not read username|permission denied \(publickey\)""") to 3,
        r("""authentication failed""") to 2,
    ),
    CauseKind.HealthTimeout to listOf(
        r("""health.?check.*(fail|time.?out|timed out)|container .* is unhealthy""") to 4,
        r("""\bunhealthy\b|timed out waiting|did not (become|converge)""") to 3, r("""deadline exceeded""") to 2,
    ),
)

fun failureCause(lines: List<String>, errorMessage: String? = null): Cause? {
    val all = lines.takeLast(1500) + listOfNotNull(errorMessage)
    val tailFrom = all.size - 40
    val score = HashMap<CauseKind, Double>()
    val proof = HashMap<CauseKind, Pair<Double, String>>()
    all.forEachIndexed { i, line ->
        val boost = if (i >= tailFrom) 1.5 else 1.0
        SIGNS.forEach { (kind, signs) ->
            val w = signs.filter { it.first.containsMatchIn(line) }.maxOfOrNull { it.second } ?: return@forEach
            val s = w * boost
            score.merge(kind, s, Double::plus)
            if (s > (proof[kind]?.first ?: 0.0)) proof[kind] = s to line.trim().take(160)
        }
    }
    val ranked = score.entries.map { it.key to minOf(it.value, 12.0) }.sortedByDescending { it.second }
    val (kind, top) = ranked.firstOrNull() ?: return null
    if (top < 3.0) return null
    val second = ranked.getOrNull(1)?.second ?: 0.0
    val confidence = (top / (top + second)) * minOf(1.0, top / 6.0)
    if (confidence < 0.35) return null
    return Cause(kind, confidence, proof[kind]?.second.orEmpty())
}

@Composable
fun rememberCause(lines: List<LogLine>?, error: String?): Cause? {
    val size = lines?.size
    return produceState<Cause?>(null, size, error) {
        val text = lines?.map { it.line } ?: return@produceState run { value = null }
        value = withContext(Dispatchers.Default) { failureCause(text, error) }
    }.value
}

@Composable
fun GroupedLogs(lines: List<LogLine>, modifier: Modifier, tags: Map<String, Color> = emptyMap()) {
    val size = lines.size
    val clusters = produceState(emptyList<Cluster>(), size) {
        val copy = lines.toList()
        value = withContext(Dispatchers.Default) { mine(copy) }
    }.value
    K(trn(R.plurals.ml_distinct, clusters.size), Modifier.padding(top = 12.dp))
    LogBox(clusters.map { LogLine(it.last, "${it.count}× ${it.template}", it.src) }, modifier.padding(top = 8.dp), follow = false, tags = tags)
    if (clusters.isNotEmpty()) ExplainButton(Modifier.padding(top = 12.dp)) { explainLogs(clusters) }
}
