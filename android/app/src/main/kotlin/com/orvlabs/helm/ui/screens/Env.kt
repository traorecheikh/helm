package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Detail
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.data.Ops
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.launch

private val SECRET = Regex("KEY|SECRET|TOKEN|PASS|PRIVATE|CREDENTIAL|DATABASE_URL|DSN", RegexOption.IGNORE_CASE)
private fun isEnvLine(l: String) = l.isNotBlank() && !l.trimStart().startsWith("#") && '=' in l

@Composable
internal fun EnvTab(kind: String, id: String, load: Load<Detail>) {
    val detail = load.value
    var lines by remember(detail) { mutableStateOf(detail?.env.orEmpty().lines()) }
    var revealed by remember { mutableStateOf(setOf<Int>()) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val flash = rememberFlash()
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()

    fun save() {
        val i = editing ?: return
        val next = lines.toMutableList()
        val line = "${key.trim()}=$value"
        if (i < 0) next += line else next[i] = line
        scope.launch {
            flash.run(tr(R.string.svc_saved_redeploy)) {
                Dokploy.saveEnv(kind, id, next.joinToString("\n"), detail!!)
                lines = next
                editing = null
            }
        }
    }

    @Composable
    fun Editor() {
        Column(Modifier.padding(vertical = 8.dp)) {
            Field(tr(R.string.name), key, { key = it })
            Field(tr(R.string.value), value, { value = it }, Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp), Arrangement.spacedBy(8.dp)) {
                Btn(tr(R.string.cancel), { editing = null }, Modifier.weight(1f))
                Btn(tr(R.string.save), ::save, Modifier.weight(1f), BtnStyle.Ink, enabled = key.isNotBlank())
            }
        }
    }

    load.error?.let { ErrorLine(it) }
    val vars = lines.withIndex().filter { isEnvLine(it.value) }
    if (detail != null && vars.isEmpty() && editing == null) EmptyHint(Ph.Key, tr(R.string.svc_no_vars))
    if (vars.isNotEmpty() || editing == -1) {
        Card {
            vars.forEachIndexed { n, (i, l) ->
                if (editing == i) return@forEachIndexed Editor()
                val k = l.substringBefore('=')
                val secret = SECRET.containsMatchIn(k)
                val hidden = secret && i !in revealed
                Li(
                    k, mono = true, titleSize = 13, rule = n < vars.lastIndex || editing == -1,
                    sub = if (hidden) "••••••••••••" else l.substringAfter('='),
                    right = {
                        if (secret) IconAction(if (hidden) Ph.Eye else Ph.EyeOff, if (hidden) tr(R.string.reveal, k) else tr(R.string.hide, k)) {
                            if (hidden) guard(tr(R.string.reveal, k)) { revealed = revealed + i } else revealed = revealed - i
                        }
                        if (!hidden) IconAction(Ph.Edit, tr(R.string.edit, k)) { key = k; value = l.substringAfter('='); editing = i }
                    },
                )
            }
            if (editing == -1) Editor()
        }
    }
    if (detail != null && editing == null) {
        Btn(tr(R.string.add_variable), { key = ""; value = ""; editing = -1 }, Modifier.padding(top = 12.dp), icon = Ph.Plus)
    }
    flash.Show()
    EnvHistory(kind, id)
}

@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).press(onClick, label), Alignment.Center) { Ico(icon, size = 18, tint = H.c.muted) }
}

@Composable
private fun EnvHistory(kind: String, id: String) {
    val load = rememberLoad(kind, id) { Ops.envHistory(kind, id) }
    val list = load.value.orEmpty()
    if (list.isEmpty()) return
    var open by remember { mutableStateOf<Int?>(null) }
    Card(Modifier.padding(top = 12.dp)) {
        list.forEachIndexed { i, h ->
            Li(
                "${day(h.at * 1000)} ${clock(h.at * 1000)}", sub = h.changedKeys.take(3).joinToString(", "), titleSize = 14,
                left = { Ico(Ph.History, size = 20, tint = H.c.muted) },
                right = { Pill("${h.changedKeys.size}") },
                rule = i < list.lastIndex || open == i,
                onClick = { open = if (open == i) null else i },
            )
            if (open == i) EnvDiff(kind, id, list.getOrNull(i + 1)?.sha.orEmpty(), h.sha)
        }
    }
}

@Composable
private fun EnvDiff(kind: String, id: String, a: String, b: String) {
    var reveal by remember { mutableStateOf(false) }
    val guard = rememberGuard()
    val load = rememberLoad(kind, id, a, b, reveal) { Ops.envDiff(kind, id, a, b, reveal) }
    load.error?.let { ErrorLine(it) }
    load.value?.filter { it.type != "same" }?.forEach { r ->
        Li(
            r.key, mono = true, titleSize = 13,
            sub = listOfNotNull(r.old?.let { "- $it" }, r.new?.let { "+ $it" }).joinToString("  "),
            left = { Mk(when (r.type) { "added" -> Tone.Ok; "removed" -> Tone.Ring; else -> Tone.Warn }) },
        )
    }
    if (!reveal) Btn(tr(R.string.svc_show_values), { guard(tr(R.string.svc_show_values)) { reveal = true } }, Modifier.padding(bottom = 8.dp), icon = Ph.Eye)
}
