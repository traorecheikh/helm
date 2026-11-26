package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.BackupFile
import com.orvlabs.helm.data.Destination
import com.orvlabs.helm.data.Detail
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.data.Svc
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.launch

@Composable
internal fun BackupsTab(svc: Svc, detail: Load<Detail>, owner: Boolean) {
    val dests = rememberLoad { Dokploy.destinations() }
    val files = rememberLoad(dests.value) {
        dests.value.orEmpty().flatMap { d -> runCatching { Dokploy.backupFiles(d.destinationId) }.getOrDefault(emptyList()) }
            .filter { !it.isDir }.sortedByDescending { parseTime(it.modTime) ?: 0 }.take(20)
    }
    var adding by remember { mutableStateOf(false) }
    dests.error?.let { ErrorLine(it) }
    Card { DbBackupRow(svc, detail, dests.value?.firstOrNull(), files.value.orEmpty(), owner) }
    dests.value?.takeIf { it.isNotEmpty() }?.let { list ->
        Card(Modifier.padding(top = 12.dp)) {
            list.forEachIndexed { i, d ->
                Li(d.name, sub = d.bucket, left = { Ico(Ph.Database, size = 20, tint = H.c.muted) }, rule = i < list.lastIndex)
            }
        }
    }
    if (!owner) return
    if (adding) Card(Modifier.padding(top = 12.dp)) { AddDestinationForm { adding = false; dests.reload() } }
    else Btn(tr(R.string.svc_add_destination), { adding = true }, Modifier.padding(top = 12.dp), icon = Ph.Plus)
}

@Composable
private fun AddDestinationForm(onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var access by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val flash = rememberFlash()
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    val ready = listOf(name, access, secret, bucket, region, endpoint).all { it.isNotBlank() }

    Field(tr(R.string.name), name, { name = it }, mono = false)
    Field(tr(R.string.access_key), access, { access = it }, Modifier.padding(top = 8.dp), secret = true)
    Field(tr(R.string.secret_key), secret, { secret = it }, Modifier.padding(top = 8.dp), secret = true)
    Field(tr(R.string.bucket), bucket, { bucket = it }, Modifier.padding(top = 8.dp))
    Field(tr(R.string.region), region, { region = it }, Modifier.padding(top = 8.dp))
    Field(tr(R.string.svc_endpoint), endpoint, { endpoint = it }, Modifier.padding(top = 8.dp), keyboard = KeyboardType.Uri)
    flash.Show()
    Row(Modifier.padding(top = 14.dp), Arrangement.spacedBy(8.dp)) {
        Btn(tr(R.string.cancel), onDone, Modifier.weight(1f))
        Btn(
            if (busy) tr(R.string.testing) else tr(R.string.test_and_save),
            {
                guard(tr(R.string.add_backup_destination)) {
                    scope.launch {
                        busy = true
                        flash.run {
                            Dokploy.testDestination(name.trim(), access.trim(), secret.trim(), bucket.trim(), region.trim(), endpoint.trim())
                            Dokploy.createDestination(name.trim(), access.trim(), secret.trim(), bucket.trim(), region.trim(), endpoint.trim())
                            onDone()
                        }
                        busy = false
                    }
                }
            },
            Modifier.weight(1f), BtnStyle.Ink, enabled = ready && !busy,
        )
    }
}

@Composable
private fun DbBackupRow(svc: Svc, detail: Load<Detail>, dest: Destination?, files: List<BackupFile>, owner: Boolean) {
    val d = detail.value
    val b = d?.backups?.firstOrNull()
    val app = svc.appName.orEmpty()
    val last = files.filter { app.isNotEmpty() && ((it.path ?: it.name).contains(app)) }.maxOfOrNull { parseTime(it.modTime) ?: 0 }?.takeIf { it > 0 }
    val flash = rememberFlash()
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()

    fun run(label: String, ok: String, block: suspend () -> Unit) = guard(label) {
        scope.launch { flash.run(ok) { block(); detail.reload() } }
    }

    val sub = if (b == null) tr(R.string.svc_off) else last?.let { agoFull(it) } ?: tr(R.string.no_file_yet)
    if (owner) {
        ToggleRow(svc.name, sub, b != null) { on ->
            when {
                d == null -> Unit
                on && dest == null -> flash.say(tr(R.string.add_a_backup_destination_first))
                on -> run(tr(R.string.turn_on_backups_for, svc.name), tr(R.string.daily_backup_is_on)) { Dokploy.createBackup(svc.id, app, dest!!.destinationId, d.databaseName ?: svc.name) }
                b != null -> run(tr(R.string.turn_off_backups_for, svc.name), tr(R.string.daily_backup_is_off)) { Dokploy.removeBackup(b.backupId) }
            }
        }
    } else {
        Li(svc.name, sub = sub, right = { Pill(if (b != null) tr(R.string.svc_backup) else tr(R.string.svc_no_backup), if (b != null) Tone.Ok else Tone.Warn) })
    }
    if (b != null) Btn(tr(R.string.back_up_now_2), { run(tr(R.string.back_up, svc.name), tr(R.string.backup_started)) { Dokploy.backupNow(b.backupId) } }, Modifier.padding(top = 6.dp), BtnStyle.Ink)
    flash.Show()
}
