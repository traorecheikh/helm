package com.orvlabs.helm.ui.components

import com.orvlabs.helm.R
import com.orvlabs.helm.appLocale
import com.orvlabs.helm.tr
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val zone get() = ZoneId.systemDefault()

fun clock(ms: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(appLocale).format(Instant.ofEpochMilli(ms).atZone(zone))

fun clockSeconds(ms: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(appLocale).format(Instant.ofEpochMilli(ms).atZone(zone))

fun day(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> tr(R.string.today)
        today.minusDays(1) -> tr(R.string.yesterday)
        else -> DateTimeFormatter.ofPattern("d MMM", appLocale).format(d)
    }
}

fun ago(ms: Long?): String {
    if (ms == null) return ""
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 45 -> tr(R.string.time_now)
        s < 3600 -> tr(R.string.time_min, s / 60 + if (s % 60 >= 30) 1 else 0)
        s < 86_400 -> tr(R.string.time_h, s / 3600)
        else -> tr(R.string.time_d, s / 86_400)
    }
}

fun agoFull(ms: Long?): String = if (ms == null) "" else ago(ms).let { if (it == tr(R.string.time_now)) it else tr(R.string.ago_full, it) }

fun duration(startMs: Long?, endMs: Long?): String? {
    if (startMs == null || endMs == null || endMs < startMs) return null
    val s = (endMs - startMs) / 1000
    return if (s < 60) tr(R.string.dur_s, s) else tr(R.string.dur_ms, s / 60, "%02d".format(s % 60))
}

fun clockTimer(seconds: Long): String = "${seconds / 60}:${"%02d".format(seconds % 60)}"

fun gb(bytes: Long): String = NumberFormat.getNumberInstance(appLocale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }.format(bytes / 1_073_741_824.0)

fun size(bytes: Long?): String = when {
    bytes == null -> ""
    bytes >= 1_073_741_824 -> tr(R.string.unit_gb, gb(bytes))
    bytes >= 1_048_576 -> tr(R.string.unit_mb, NumberFormat.getInstance(appLocale).format(bytes / 1_048_576))
    else -> tr(R.string.unit_kb, NumberFormat.getInstance(appLocale).format(bytes / 1024))
}

fun statusWord(raw: String): String = when (raw) {
    "done" -> tr(R.string.status_running)
    "running" -> tr(R.string.status_building)
    "error" -> tr(R.string.status_failed)
    else -> tr(R.string.status_idle)
}

fun statusTone(raw: String) = when (raw) {
    "error" -> Tone.Signal
    "running" -> Tone.Warn
    "idle" -> Tone.Ring
    else -> Tone.Ink
}

fun flag(code: String): String =
    if (code.length != 2 || !code.all { it.isLetter() }) "" else code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

fun soon(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    val d = when (java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone), t.toLocalDate())) {
        0L -> tr(R.string.today).lowercase(appLocale)
        1L -> tr(R.string.tomorrow)
        in 2L..6L -> DateTimeFormatter.ofPattern("EEE", appLocale).format(t)
        else -> DateTimeFormatter.ofPattern("d MMM", appLocale).format(t)
    }
    return "$d ${clock(ms)}"
}

fun pct(v: Double, digits: Int = 0): String =
    tr(R.string.w_pct, NumberFormat.getNumberInstance(appLocale).apply { maximumFractionDigits = digits }.format(v))

fun decimal(v: Double, digits: Int = 1): String =
    NumberFormat.getNumberInstance(appLocale).apply { maximumFractionDigits = digits }.format(v)

fun taskName(name: String): String = name.replace(Regex("""\.\d+\.[a-z0-9]{25}$"""), "")

fun isoMs(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
