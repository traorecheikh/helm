package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Container
import com.orvlabs.helm.data.Detail
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.data.dokployActive
import com.orvlabs.helm.data.Ops
import com.orvlabs.helm.ml.GroupedLogs
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun svcTone(raw: String) = when (raw) {
    "done" -> Tone.Ok
    "running" -> Tone.Warn
    "error" -> Tone.Signal
    else -> Tone.Ring
}

internal class Flash {
    var text by mutableStateOf<String?>(null)
    var bad by mutableStateOf(false)

    suspend fun run(ok: String? = null, block: suspend () -> Unit) {
        text = null
        try {
            block()
            if (ok != null) { text = ok; bad = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            Haptics.fire(Feel.Reject)
            text = e.message
            bad = true
        }
    }

    fun say(s: String) { text = s; bad = false }

    @Composable
    fun Show() {
        val t = text ?: return
        if (bad) ErrorLine(t, Modifier.padding(top = 8.dp)) else Banner(t, Ph.Check, Modifier.padding(top = 12.dp))
    }
}

@Composable
internal fun rememberFlash() = remember { Flash() }

@Composable
internal fun EmptyHint(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), Arrangement.spacedBy(10.dp), Alignment.CenterHorizontally) {
        Ico(icon, size = 32, tint = H.c.faint)
        Txt(text, size = 14, color = H.c.muted)
    }
}

@Composable
internal fun LogsTab(container: String?) {
    if (container == null) return EmptyHint(Ph.Scroll, tr(R.string.svc_no_container))
    val stream = rememberLogStream(container) { Agent.containerLogs(container) }
    var filter by remember { mutableIntStateOf(0) }
    val errors = stream.lines.count { logLevel(it.line) == 2 }
    val lines = when (filter) {
        1 -> stream.lines.filter { logLevel(it.line) == 2 }
        2 -> stream.lines.filter { logLevel(it.line) >= 1 }
        else -> stream.lines
    }
    Chips(listOf(if (stream.live) tr(R.string.live) else tr(R.string.stopped), tr(R.string.errors), tr(R.string.svc_warnings), tr(R.string.ml_grouped)), filter, { filter = it }, mapOf(1 to errors))
    stream.error?.let { ErrorLine(it) }
    if (filter == 3) return GroupedLogs(stream.lines, Modifier.height(480.dp))
    LogBox(lines, Modifier.padding(top = 12.dp).height(480.dp), follow = filter == 0)
}

@Composable
internal fun ConfigTab(nav: NavController, kind: String, id: String, name: String, detail: Load<Detail>, containers: List<Container>) {
    if (dokployActive) {
        Domains(kind, id)
        if (kind == "application") ResourcesCard(nav, id, name, detail)
    }
    val raw = containers.joinToString(",") { it.ports }
    val public = Regex("""->(\d+)/""").findAll(raw).map { it.groupValues[1].toInt() }.toSortedSet()
    val internal = Regex("""(?:^|,\s*)(\d+)/(?:tcp|udp)""").findAll(raw).map { it.groupValues[1].toInt() }.filter { it !in public }.toSortedSet()
    val images = containers.map { it.image }.distinct()
    if (public.isNotEmpty() || internal.isNotEmpty() || images.isNotEmpty()) {
        Card(Modifier.padding(top = 12.dp)) {
            if (public.isNotEmpty()) Stat(tr(R.string.public_ports), public.joinToString("  "), rule = internal.isNotEmpty() || images.isNotEmpty())
            if (internal.isNotEmpty()) Stat(tr(R.string.internal_ports), internal.joinToString("  "), rule = images.isNotEmpty())
            images.forEachIndexed { i, img -> Stat(tr(R.string.image), img.substringAfterLast('/'), rule = i < images.lastIndex) }
        }
    }
    Notes(id)
}

@Composable
private fun Domains(kind: String, id: String) {
    val load = rememberLoad(kind, id) { Dokploy.domains(kind, id) }
    load.error?.let { ErrorLine(it) }
    val list = load.value.orEmpty()
    if (list.isEmpty()) {
        if (!load.loading && load.error == null) EmptyHint(Ph.Globe, tr(R.string.svc_no_domain))
        return
    }
    Card {
        list.forEachIndexed { i, d ->
            val cert = when (d.certificateType) { "letsencrypt" -> tr(R.string.let_s_encrypt); "custom" -> tr(R.string.custom); else -> tr(R.string.no_certificate) }
            val https = d.https == true
            Li(
                d.host + d.path.orEmpty().takeIf { it != "/" }.orEmpty(),
                sub = cert + (d.port?.let { tr(R.string.port_suffix, it) } ?: ""),
                left = { Ico(Ph.Globe, size = 20, tint = H.c.muted) },
                right = { Pill(if (https) "HTTPS" else "HTTP", if (https) Tone.Ok else Tone.Warn) },
                rule = i < list.lastIndex,
            )
        }
    }
}

@Composable
private fun Notes(id: String) {
    val load = rememberLoad(id) { Ops.notes().filter { it.serviceId == id } }
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val notes = load.value.orEmpty()
    Card(Modifier.padding(top = 12.dp)) {
        Li(
            tr(R.string.svc_notes), left = { Ico(Ph.Edit, size = 20, tint = H.c.muted) },
            right = { RightText("${notes.size}", size = 14); Ico(Ph.Next, size = 16, tint = H.c.muted) },
            onClick = { open = !open },
        )
        if (!open) return@Card
        notes.forEach { n -> Li(n.text, sub = "${n.author} · ${day(n.at * 1000)} ${clock(n.at * 1000)}") }
        Field(tr(R.string.svc_notes), text, { text = it }, Modifier.padding(top = 8.dp), mono = false, singleLine = false)
        Btn(tr(R.string.svc_add), {
            scope.launch {
                try { Ops.addNote(id, text.trim()); text = ""; load.reload(); Haptics.confirmLater() } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject) }
            }
        }, Modifier.padding(top = 8.dp), enabled = text.isNotBlank())
    }
}
