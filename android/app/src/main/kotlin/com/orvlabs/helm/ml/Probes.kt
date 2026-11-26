package com.orvlabs.helm.ml

import com.orvlabs.helm.R
import com.orvlabs.helm.data.Attacker
import com.orvlabs.helm.data.Ban
import com.orvlabs.helm.data.Devices
import com.orvlabs.helm.data.Login
import com.orvlabs.helm.data.IpDetail
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Mk
import com.orvlabs.helm.ui.components.Pill
import com.orvlabs.helm.ui.components.Tone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class Family(val label: Int) {
    Ssh(R.string.ml_f_ssh), Secrets(R.string.ml_f_secrets), Php(R.string.ml_f_php), Vuln(R.string.ml_f_vuln), Brute(R.string.ml_f_brute),
    Flood(R.string.ml_f_flood), Repeat(R.string.ml_f_repeat), Noise(R.string.ml_f_noise), Visitor(R.string.ml_f_visitor), You(R.string.ml_f_you),
}

enum class Severity(val tone: Tone) { Noise(Tone.Ink), Low(Tone.Warn), Real(Tone.Signal) }

enum class Level { Calm, Probing, Attack }

data class Probe(
    val ip: String,
    val source: String = "",
    val reason: String = "",
    val country: String = "",
    val paths: Map<String, Int> = emptyMap(),
    val lines: List<String> = emptyList(),
    val hits: Int = 0,
    val statuses: Map<String, Int> = emptyMap(),
    val flags: List<String> = emptyList(),
    val you: Boolean = false,
    val banned: Boolean = false,
    val at: Long = 0,
    val agent: String = "",
)

data class Verdict(val family: Family, val severity: Severity, val line: String, val doubt: Boolean = false) {
    val trusted get() = family == Family.You
    val tone get() = if (family == Family.You) (if (doubt) Tone.Warn else Tone.Ok) else severity.tone
}

data class ShieldRead(val level: Level, val threats: Int, val campaigns: Map<String, Int>)

private val SECRETS = Regex("""(?i)/\.(env|git|aws|ssh|svn|hg|ds_store|htpasswd|npmrc|docker|vscode)|/(config|secrets?|credentials)\.(json|ya?ml|php|js)|/id_rsa|\.(sql|bak|old)$|/backup""")
private val PHP = Regex("""(?i)wp-|wordpress|xmlrpc|\.php\b|phpmyadmin|/pma\b""")
private val VULN = Regex("""(?i)cgi-bin|actuator|server-status|boaform|hnap1|/owa/|/debug/pprof|/(admin|identities|ops|kratos)(/|$)|/solr|/vendor/|/console|/manager/html|/geoserver|/druid|/telescope|/_ignition|/autodiscover|/ecp/|/jenkins|\.\./|%2e%2e|/etc/passwd|/bin/sh|/shell|/v1/(models|chat|completions)|/v2/_catalog|/api/v1/(pods|namespaces)""")
private val SCANNER_UA = Regex("""(?i)zgrab|masscan|nmap|nuclei|sqlmap|nikto|gobuster|dirbuster|ffuf|wpscan|censys|l9explore|^$""")
private val REQUEST = Regex("""\b(?:GET|POST|HEAD|PUT|DELETE|OPTIONS|PATCH|PROPFIND|CONNECT)\s+"?([^\s"]+)""")
private val JSON_PATH = Regex(""""RequestPath"\s*:\s*"([^"]+)"""")
private val STATUS = Regex("""(?:"DownstreamStatus"\s*:\s*|"\s+|\s{2,})(\d{3})\b""")
private val SSH_FAIL = Regex("""(?i)failed password|invalid user|authentication failure|connection closed by (authenticating|invalid) user|maximum authentication attempts|did not receive identification|banner exchange""")
private val SSH_OK = Regex("""^\S+ \S+ sshd\[\d+]: Accepted (password|publickey|keyboard-interactive) """)
private val LEADING_INT = Regex("""^\s*(\d+)""")

internal fun pathOf(line: String): String? {
    val raw = JSON_PATH.find(line)?.groupValues?.get(1) ?: REQUEST.find(line)?.groupValues?.get(1) ?: return null
    val path = if (raw.startsWith("/")) raw else raw.indexOf('/').let { if (it < 0) "/" else raw.substring(it) }
    return path.substringBefore('?').take(80)
}

private fun suspect(path: String) = SECRETS.containsMatchIn(path) || PHP.containsMatchIn(path) || VULN.containsMatchIn(path)

private fun pathFamily(paths: Map<String, Int>): Family? {
    val score = mutableMapOf<Family, Int>()
    paths.forEach { (p, n) ->
        when {
            SECRETS.containsMatchIn(p) -> score.merge(Family.Secrets, n, Int::plus)
            PHP.containsMatchIn(p) -> score.merge(Family.Php, n, Int::plus)
            VULN.containsMatchIn(p) -> score.merge(Family.Vuln, n, Int::plus)
        }
    }
    return score.maxByOrNull { it.value }?.key
}

fun classify(p: Probe): Verdict {
    val paths = p.paths.toMutableMap()
    val statuses = p.statuses.toMutableMap()
    var leaks = 0
    p.lines.forEach { l ->
        val path = pathOf(l) ?: return@forEach
        paths.merge(path, 1, Int::plus)
        val code = STATUS.find(l)?.groupValues?.get(1) ?: return@forEach
        if (p.statuses.isEmpty()) statuses.merge(code, 1, Int::plus)
        if (code.startsWith("2") && suspect(path)) leaks++
    }
    val sshFails = p.lines.count { SSH_FAIL.containsMatchIn(it) }
    val sshOk = p.lines.any { SSH_OK.containsMatchIn(it) }
    val stated = LEADING_INT.find(p.reason)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val src = p.source.lowercase()
    val flags = p.flags.joinToString(" ").lowercase()
    val total = statuses.values.sum()
    val refused = total - statuses.filterKeys { it.startsWith("2") || it.startsWith("3") }.values.sum()
    val misses = statuses["404"] ?: 0
    val enumerating = paths.size >= 8 && misses * 2 >= total
    val family = when {
        p.you || sshOk -> Family.You
        else -> pathFamily(paths) ?: when {
            src == "ssh" || src.startsWith("sshd") || "sshd" in flags || "ssh-bf" in flags || sshFails > 0 -> Family.Ssh
            src == "recidive" || src == "repeat" -> Family.Repeat
            src in setOf("mail", "login", "identity-abuse") -> Family.Brute
            src == "http-404-flood" || p.hits >= 300 -> Family.Flood
            src == "traefik-scan" || "http-probing" in flags || "sensitive-files" in flags || enumerating ||
                (refused > 0 && p.lines.isEmpty() && p.paths.isNotEmpty() && SCANNER_UA.containsMatchIn(p.agent)) -> Family.Vuln
            total > 0 && refused == 0 -> Family.Visitor
            else -> Family.Noise
        }
    }
    val suspectHits = paths.filterKeys(::suspect)
    val count = when (family) {
        Family.Ssh -> maxOf(stated, sshFails, if (paths.isEmpty()) p.hits else 0)
        Family.Secrets, Family.Php, Family.Vuln -> suspectHits.values.sum().takeIf { it > 0 } ?: maxOf(p.hits, paths.values.sum())
        else -> maxOf(stated, p.hits, paths.values.sum())
    }
    val allSuspect = paths.isNotEmpty() && paths.keys.all(::suspect)
    val real = when (family) {
        Family.You, Family.Noise, Family.Visitor -> false
        Family.Ssh -> count >= 100
        Family.Brute -> count >= 50
        Family.Flood -> !p.banned && p.hits >= 1000
        Family.Repeat -> true
        else -> leaks > 0 || (allSuspect && statuses.filterKeys { it.startsWith("2") }.values.sum() > 0)
    }
    val severity = when {
        family == Family.You || family == Family.Noise || family == Family.Visitor -> Severity.Noise
        real -> Severity.Real
        else -> Severity.Low
    }
    val doubt = family == Family.You && !p.you && sshFails >= 10
    return Verdict(family, severity, oneLiner(family, suspectHits.ifEmpty { paths }, count), doubt)
}

private fun oneLiner(family: Family, paths: Map<String, Int>, count: Int): String {
    val times = if (count > 0) " · $count×" else ""
    if (family == Family.Ssh) return "SSH$times"
    val top = paths.maxByOrNull { it.value }?.key ?: return if (count > 0) "$count×" else ""
    val short = if (top.length > 28) top.take(27) + "…" else top
    val more = if (paths.size > 1) " +${paths.size - 1}" else ""
    return "$short$more$times"
}

private fun prefix(ip: String, v4: Int, v6: Int): String =
    if (':' in ip) ip.split(':').take(v6).joinToString(":") else ip.split('.').take(v4).joinToString(".")

private fun jaccard(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

fun campaigns(list: List<Probe>, verdicts: List<Verdict>, windowSec: Long = 1800): IntArray {
    val parent = IntArray(list.size) { it }
    fun root(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
    val keys = list.map { it.paths.keys.filter(::suspect).toSet().ifEmpty { it.paths.keys } }
    for (i in list.indices) for (j in i + 1 until list.size) {
        val a = list[i]
        val b = list[j]
        val same = verdicts[i].family == verdicts[j].family
        val link = prefix(a.ip, 3, 3) == prefix(b.ip, 3, 3) ||
            (same && a.country.isNotEmpty() && a.country == b.country && prefix(a.ip, 2, 2) == prefix(b.ip, 2, 2)) ||
            (same && abs(a.at - b.at) <= windowSec && jaccard(keys[i], keys[j]) >= 0.5)
        if (link) parent[root(i)] = root(j)
    }
    return IntArray(list.size) { root(it) }
}

fun readShield(live: List<Probe>): ShieldRead {
    val judged = live.filter { !it.banned }.map { it to classify(it) }.filter { it.second.severity != Severity.Noise }
    val threats = judged.map { it.first }
    val roots = campaigns(threats, judged.map { it.second })
    val sizes = roots.toList().groupingBy { it }.eachCount()
    val level = when {
        judged.any { it.second.severity == Severity.Real } || sizes.size >= 3 -> Level.Attack
        threats.isNotEmpty() -> Level.Probing
        else -> Level.Calm
    }
    val campaign = threats.indices.associate { threats[it].ip to (sizes[roots[it]] ?: 1) }.filterValues { it > 1 }
    return ShieldRead(level, threats.size, campaign)
}

fun trustedIps(devices: Devices?, logins: List<Login>, nowSec: Long): Set<String> =
    (devices?.devices.orEmpty().mapNotNull { it.lastIp } + logins.filter { nowSec - it.at <= 30 * 86_400L }.map { it.ip }).filter { it.isNotBlank() }.toSet()

fun Attacker.probe(trusted: Set<String> = emptySet()) = Probe(
    ip, "", "", country, topPaths.associate { it.path to it.count }, emptyList(),
    hits, statuses, flags, probablyYou || ip in trusted, banned, lastSeen, agent,
)

fun Ban.probe(live: Attacker? = null, trusted: Set<String> = emptySet()) = Probe(
    ip, jail.ifEmpty { source }, reason, country.orEmpty(),
    live?.takeIf { source == "web" }?.topPaths?.associate { it.path to it.count }.orEmpty(), emptyList(),
    live?.hits ?: 0, live?.statuses.orEmpty(), live?.flags.orEmpty(), live?.probablyYou == true || ip in trusted, true, bannedAt,
)

fun IpDetail.probe(live: Attacker?, trusted: Set<String> = emptySet()) = Probe(
    ip, source, reason.orEmpty(), country.orEmpty(), live?.topPaths?.associate { it.path to it.count }.orEmpty(), webRequests + sshLines + evidence,
    maxOf(webRequestCount, live?.hits ?: 0), live?.statuses.orEmpty(), live?.flags.orEmpty(),
    probablyYou || live?.probablyYou == true || ip in trusted, banned, bannedAt ?: 0, live?.agent.orEmpty(),
)

class ShieldView(val read: ShieldRead, val verdicts: Map<String, Verdict>, val bans: Map<String, Verdict>)

@Composable
fun rememberShieldView(live: List<Attacker>?, bans: List<Ban>, trusted: Set<String>): ShieldView? =
    produceState<ShieldView?>(null, live, bans, trusted) {
        value = withContext(Dispatchers.Default) {
            val list = live.orEmpty()
            val byIp = list.associateBy { it.ip }
            ShieldView(
                readShield(list.map { it.probe(trusted) }),
                list.associate { it.ip to classify(it.probe(trusted)) },
                bans.associate { it.ip to classify(it.probe(byIp[it.ip], trusted)) },
            )
        }
    }.value

@Composable
fun rememberVerdict(d: IpDetail?, live: Attacker?, trusted: Set<String>): Verdict? =
    produceState<Verdict?>(null, d, live, trusted) { value = d?.let { withContext(Dispatchers.Default) { classify(it.probe(live, trusted)) } } }.value

@Composable
fun VerdictPill(v: Verdict?, banned: Boolean = false) {
    if (v == null) return Mk(Tone.Ring)
    Pill(tr(v.family.label), if (banned && !v.trusted) Tone.Ink else v.tone)
}
