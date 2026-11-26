package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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
import com.orvlabs.helm.data.*
import com.orvlabs.helm.isFrench
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun Result(msg: Pair<String, Boolean>?) {
    val (text, bad) = msg ?: return
    if (bad) ErrorLine(text, Modifier.padding(top = 12.dp)) else Banner(text, Ph.Check, Modifier.padding(top = 12.dp))
}

@Composable
private fun HoldRow(title: String, sub: String, icon: ImageVector, holdLabel: String, onDone: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    SrvRow(title, sub = sub, subLines = 2, left = { IconTile(icon) }, onClick = { armed = !armed })
    if (armed) {
        HoldButton(holdLabel, 1200) { armed = false; onDone() }
        Box(Modifier.height(8.dp))
    }
}

@Composable
internal fun UpdatesBody(load: Load<Updates>) {
    EveryWhileVisible(30_000) { load.reload() }
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val c = H.c

    fun run(label: String, ok: String? = null, block: suspend () -> Unit) = guard(label) {
        scope.launch {
            try { block(); msg = ok?.let { it to false }; load.reload(); Haptics.confirmLater() } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); msg = (e.message ?: "") to true }
        }
    }

    Failed(load)
    val u = load.value ?: return
    if (u.rebootRequired) SrvRow(tr(R.string.reboot), sub = (if (isFrench) u.rebootReasonFr else u.rebootReason) ?: tr(R.string.h_reboot_required), subLines = 2, left = { Mk(Tone.Warn) })
    else if (u.kernelPending) SrvRow(tr(R.string.reboot), sub = tr(R.string.aud_kernel), left = { Mk(Tone.Warn) })
    u.unattended?.let { a ->
        K(tr(R.string.aud_patching), Modifier.padding(top = 12.dp))
        Stat(tr(R.string.aud_patching), tr(if (a.enabled) R.string.aud_on else R.string.aud_off), if (a.enabled) c.ok else c.warn)
        a.lastRun?.let { Stat(tr(R.string.aud_last_run), agoFull(it * 1000), if (a.ok == false) c.bad else c.ink) }
        a.error?.let { ErrorLine(it) }
        if (a.keptBack.isNotEmpty()) Stat(tr(R.string.aud_kept), a.keptBack.take(3).joinToString(" "), c.warn)
    }
    if (u.pending.isEmpty()) Empty(tr(R.string.h_up_none))
    else {
        listOf("security" to R.string.h_security, "system" to R.string.h_system, "held" to R.string.h_held).forEach { (g, label) ->
            val list = u.pending.filter { it.group == g }
            if (list.isNotEmpty()) K(tr(label), Modifier.padding(top = 12.dp))
            list.forEach { p -> SrvRow(p.name, mono = true, titleSize = 13, sub = "${p.from} → ${p.to}", left = { Mk(if (g == "security") Tone.Warn else Tone.Ink) }) }
        }
        Btn(tr(R.string.h_install), { run(tr(R.string.h_install), tr(R.string.h_install_started)) { Ops.installUpdates() } }, Modifier.padding(top = 14.dp), BtnStyle.Ink)
    }
    K(tr(R.string.reboot), Modifier.padding(top = 18.dp))
    (u.scheduledReboot ?: u.nextReboot)?.let { Stat(tr(R.string.up_next_reboot), soon(it * 1000), if (u.rebootRequired) c.warn else c.ink) }
    u.lastReboot?.takeIf { it.at > 0 }?.let { Stat(tr(R.string.h_last_reboot), agoFull(it.at * 1000)) }
    u.runningKernel?.let { Stat(tr(R.string.h_kernel), it) }
    ToggleRow(tr(R.string.h_auto_reboot), if (u.learning) tr(R.string.h_learning, u.learningDays) else "", u.autoReboot) { on -> run(tr(R.string.h_auto_reboot)) { Ops.setAutoReboot(on) } }
    if (u.rebootRequired) HoldRow(tr(R.string.h_reboot_quiet), tr(R.string.h_reboot_body), Ph.Refresh, tr(R.string.keep_holding_to_reload)) { run(tr(R.string.h_reboot_q)) { Ops.scheduleReboot(null) } }
    if (u.rebootRequired && (u.final || u.postponements > 0)) Btn(tr(R.string.h_postpone), { run(tr(R.string.h_postpone)) { Ops.postponeReboot() } }, Modifier.padding(top = 8.dp), BtnStyle.Danger)
    Result(msg)
}

@Composable
internal fun DiskBody(load: Load<DiskPreview>) {
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val items = load.value?.items.orEmpty()
    val sel = picked ?: items.map { it.id }.toSet()
    fun label(id: String) = tr(when (id) { "build_cache" -> R.string.h_build_cache; "dangling_images" -> R.string.h_dangling; "unused_images" -> R.string.h_unused_images; else -> R.string.h_stopped })

    Failed(load)
    load.value?.let { Txt(tr(R.string.h_disk_total, size(items.filter { i -> i.id in sel }.sumOf { i -> i.bytes })), size = 17, weight = 600) }
    items.forEach { i ->
        ToggleRow(label(i.id), trn(R.plurals.disk_items, i.count) + " · " + size(i.bytes), i.id in sel) { on -> picked = if (on) sel + i.id else sel - i.id }
    }
    Btn(
        if (busy) tr(R.string.h_cleaning) else tr(R.string.h_clean_selected),
        {
            guard(tr(R.string.h_disk)) {
                scope.launch {
                    busy = true
                    try { val r = Ops.diskClean(sel.toList()); msg = tr(R.string.h_freed, size(r.freedBytes)) to false; load.reload(); Haptics.confirmLater() }
                    catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); msg = (e.message ?: "") to true } finally { busy = false }
                }
            }
        },
        Modifier.padding(top = 14.dp), BtnStyle.Ink, enabled = !busy && sel.isNotEmpty(),
    )
    Result(msg)
}

@Composable
internal fun FilesBody(load: Load<List<WatchFile>>) {
    var open by remember { mutableStateOf(setOf<String>()) }
    var diff by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    Failed(load)
    diff?.let { (path, a, b) -> return DiffView(path, a, b) { diff = null } }
    if (load.value?.all { it.changes.isEmpty() } == true) Empty(tr(R.string.h_wd_empty))
    load.value?.filter { it.changes.isNotEmpty() }?.forEach { f ->
        SrvRow(f.path, mono = true, titleSize = 13, sub = trn(R.plurals.h_wd_changes, f.changes.size) + " · " + agoFull(f.changes.maxOf { it.at } * 1000), onClick = { open = if (f.path in open) open - f.path else open + f.path })
        if (f.path in open) f.changes.forEachIndexed { i, ch ->
            SrvRow(
                "${day(ch.at * 1000)} ${clock(ch.at * 1000)}", Modifier.padding(start = 16.dp), sub = "+${ch.added} -${ch.removed} · ${ch.sha.take(7)}",
                onClick = { diff = Triple(f.path, f.changes.getOrNull(i + 1)?.sha.orEmpty(), ch.sha) },
            )
        }
    }
}

@Composable
private fun DiffView(path: String, a: String, b: String, onBack: () -> Unit) {
    val load = rememberLoad(path, a, b) { Ops.diff(path, a, b) }
    val c = H.c
    Row(Modifier.press(onBack, tr(R.string.back)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(48.dp), Alignment.CenterStart) { Ico(Ph.Back, size = 18) }
        Txt(path, size = 12, mono = true, color = c.muted, maxLines = 3)
    }
    Failed(load)
    load.value?.take(400)?.forEach { l ->
        val (p, col) = when (l.type) { "add" -> "+" to c.ok; "del" -> "-" to c.bad; "hunk" -> "@" to c.muted; else -> " " to c.muted }
        Txt("$p ${l.text}", Modifier.padding(top = 1.dp), size = 12, mono = true, color = col)
    }
}

@Composable
internal fun DockerBody(disk: DockerDisk?) {
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    fun run(label: String, block: suspend () -> Unit) {
        guard(label) {
            scope.launch {
                try { block(); msg = tr(R.string.done, label) to false } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); msg = (e.message ?: "") to true }
            }
        }
    }

    if (dokployActive) {
        val version = rememberLoad { Dokploy.version() }
        HoldRow(tr(R.string.clean_docker), tr(R.string.removes_unused_images_and_build_cache), Ph.Broom, tr(R.string.keep_holding_to_clean)) { run(tr(R.string.clean_docker)) { Dokploy.cleanDocker() } }
        HoldRow(tr(R.string.reload_traefik), tr(R.string.reloads_the_web_proxy_every_site), Ph.Refresh, tr(R.string.keep_holding_to_reload)) { run(tr(R.string.reload_traefik)) { Dokploy.reloadTraefik() } }
        SrvRow(tr(R.string.dokploy_version), sub = version.value ?: version.error ?: tr(R.string.checking), left = { IconTile(Ph.Download) })
    }
    Result(msg)
    disk?.let {
        K(tr(R.string.docker_disk), Modifier.padding(top = 16.dp))
        Stat(tr(R.string.images), size(it.imagesBytes))
        Stat(tr(R.string.containers), size(it.containersBytes))
        Stat(tr(R.string.volumes), size(it.volumesBytes))
        Stat(tr(R.string.build_cache), size(it.buildCacheBytes))
    }
}
