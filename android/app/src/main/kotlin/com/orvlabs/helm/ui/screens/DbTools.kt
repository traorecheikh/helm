package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.QueryResult
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun DbTools(container: String, defaultDb: String) {
    var db by remember { mutableStateOf(defaultDb) }
    val load = rememberLoad(container, db) { Agent.dbInfo(container, db) }
    var query by remember { mutableStateOf(false) }
    val c = H.c
    load.error?.let { ErrorLine(it) }
    val i = load.value ?: return
    val busy = i.maxConnections > 0 && i.activeConnections * 100 / i.maxConnections > 80
    if (i.databases.size > 1) {
        val names = i.databases.map { it.name }
        Column(Modifier.padding(top = 12.dp)) { Chips(names, names.indexOf(i.database).coerceAtLeast(0), { db = names[it] }) }
    }
    Card(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp), Alignment.Top) {
            Column(Modifier.weight(1f)) {
                K(tr(R.string.size))
                DotNum(com.orvlabs.helm.ui.components.size(i.sizeBytes), 64, Modifier.padding(top = 6.dp))
            }
            Column(Modifier.weight(1f)) {
                K(tr(R.string.connections))
                Txt(tr(R.string.of, i.activeConnections, i.maxConnections), Modifier.padding(top = 10.dp), size = 20, weight = 700, color = if (busy) c.warn else c.ink, maxLines = 1)
                if (i.maxConnections > 0) Meter(i.activeConnections.toFloat() / i.maxConnections, Modifier.padding(top = 10.dp), if (busy) c.warn else c.ok)
            }
        }
        Row(Modifier.padding(top = 14.dp)) { Pill(i.version.take(40)) }
    }
    if (i.largestTables.isNotEmpty()) {
        val max = i.largestTables.maxOf { it.bytes }.coerceAtLeast(1)
        Card(Modifier.padding(top = 12.dp)) {
            i.largestTables.forEach { t ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Txt(t.name, Modifier.weight(1f, false), size = 13, mono = true, maxLines = 1)
                        RightText(tr(R.string.rows, com.orvlabs.helm.ui.components.size(t.bytes), t.rows))
                    }
                    Meter(t.bytes.toFloat() / max, Modifier.padding(top = 8.dp), c.faint)
                }
            }
        }
    }
    if (query) QueryBox(container, i.database)
    else Btn(tr(R.string.run_a_query), { query = true }, Modifier.padding(top = 12.dp), icon = Ph.Scroll)
}

@Composable
private fun QueryBox(container: String, database: String) {
    var sql by remember { mutableStateOf("SELECT 1") }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val flash = rememberFlash()
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()

    Card(Modifier.padding(top = 12.dp)) {
        Field(tr(R.string.sql), sql, { sql = it }, singleLine = false)
        Btn(if (busy) tr(R.string.running) else tr(R.string.run_query), {
            guard(tr(R.string.run_a_query_on, database)) {
                scope.launch {
                    busy = true
                    flash.run { result = Agent.dbQuery(container, database, sql.trim()) }
                    if (flash.bad) result = null
                    busy = false
                }
            }
        }, Modifier.padding(top = 10.dp), BtnStyle.Ink, enabled = sql.isNotBlank() && !busy)
        flash.Show()
        result?.let { r ->
            r.summary?.let { Txt(it, Modifier.padding(top = 12.dp), size = 13, color = H.c.muted) }
            if (r.truncated) Pill(tr(R.string.svc_truncated), Tone.Warn, Modifier.padding(top = 8.dp))
            Column(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState())) {
                Row { r.columns.forEach { Txt(it, Modifier.width(140.dp).padding(end = 8.dp, bottom = 6.dp), size = 12, mono = true, weight = 500, color = H.c.muted, maxLines = 1) } }
                r.rows.forEach { row ->
                    Row {
                        row.forEach { v ->
                            val text = if (v is JsonNull) "null" else (v as? JsonPrimitive)?.content ?: v.toString()
                            Txt(text, Modifier.width(140.dp).padding(end = 8.dp, bottom = 4.dp), size = 12, mono = true, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
