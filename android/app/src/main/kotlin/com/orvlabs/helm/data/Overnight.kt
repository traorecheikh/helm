package com.orvlabs.helm.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import com.orvlabs.helm.R
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn

fun quietNow(start: Int, end: Int): Boolean {
    val t = LocalTime.now().let { it.hour * 60 + it.minute }
    return if (start <= end) t in start until end else t >= start || t < end
}

private fun lastQuietStartMs(start: Int): Long {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = LocalDate.now(zone).atStartOfDay(zone).plusMinutes(start.toLong()).toInstant().toEpochMilli()
    return if (today <= now) today else today - 86_400_000L
}

data class Report(
    val since: Long,
    val deploys: Int,
    val failed: Int,
    val bans: Int,
    val downs: List<String>,
    val diskDays: Int?,
    val marks: List<Int> = emptyList(),
    val end: Long = 0,
    val stillDown: String? = null,
) {
    val text: String
        get() = buildList {
            add(if (deploys > 0) trn(R.plurals.deploys_failed, deploys, failed) else trn(R.plurals.deploys_n, 0))
            add(trn(R.plurals.new_bans_n, bans))
            if (downs.isNotEmpty()) add(downs.joinToString(". "))
            diskDays?.let { add(trn(R.plurals.disk_full_about, it)) }
        }.joinToString(". ").replace("..", ".") + "."
}

object Overnight {
    fun summary(r: Report) = NightSummary(r.since, r.end, r.deploys, r.failed, r.bans, r.downs, r.stillDown, r.marks, r.text)

    suspend fun build(): Report {
        val cfg = Store.config.value
        val since = if (cfg != null && cfg.quietOn) lastQuietStartMs(cfg.quietStart) else System.currentTimeMillis() - 12 * 3_600_000L
        val events = runCatching { Agent.history300().filter { it.at * 1000 >= since } }.getOrDefault(emptyList())
        val uptime = runCatching { Agent.uptime(1) }.getOrNull()
        val stats = runCatching { Agent.stats() }.getOrNull()
        val downs = uptime?.services.orEmpty().flatMap { s ->
            s.incidents.filter { it.start * 1000 >= since }.map { i ->
                val end = i.end?.times(1000) ?: System.currentTimeMillis()
                val mins = ((end - i.start * 1000) / 60_000).coerceAtLeast(1)
                if (i.end == null) tr(R.string.down_still, s.name) else tr(R.string.down_for, s.name, mins)
            }
        }
        val disk = stats?.diskFullInDays?.let { it.toInt().coerceAtLeast(1) }
        val now = System.currentTimeMillis()
        val end = if (cfg != null && cfg.quietOn && quietNow(cfg.quietStart, cfg.quietEnd)) now else (now).coerceAtLeast(since + 1)
        val slot = ((end - since) / 32).coerceAtLeast(1)
        val marks = IntArray(32)
        fun at(ms: Long) = ((ms - since) / slot).toInt().coerceIn(0, 31)
        events.forEach { e -> if (e.type == "dokploy" && e.kind == "deploy") marks[at(e.at * 1000)] = maxOf(marks[at(e.at * 1000)], 1) else if (e.kind == "build_error") marks[at(e.at * 1000)] = 2 }
        var stillDown: String? = null
        uptime?.services.orEmpty().forEach { s -> s.incidents.filter { it.start * 1000 >= since }.forEach { i -> for (k in at(i.start * 1000)..at(i.end?.times(1000) ?: end)) marks[k] = if (i.end == null) 3 else maxOf(marks[k], 2); if (i.end == null) stillDown = s.name } }
        val marksList = marks.toList().let { m -> if (end == now && cfg?.quietOn == true) m.take(at(now) + 1) else m }
        return Report(
            since,
            events.count { it.type == "dokploy" && it.kind in setOf("deploy", "build_error") },
            events.count { it.kind == "build_error" },
            events.count { it.type == "ban" },
            downs, disk, marksList, end, stillDown,
        )
    }
}
