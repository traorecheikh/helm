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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orvlabs.helm.R
import com.orvlabs.helm.data.*
import com.orvlabs.helm.tr
import com.orvlabs.helm.trn
import com.orvlabs.helm.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun BackupsBody(dests: Load<List<Destination>>, snap: Snapshot?) {
    val files = rememberLoad(dests.value) {
        dests.value.orEmpty().flatMap { d -> runCatching { Dokploy.backupFiles(d.destinationId) }.getOrDefault(emptyList()) }
            .filter { !it.isDir }.sortedByDescending { parseTime(it.modTime) ?: 0 }.take(20)
    }
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val owner = Store.config.collectAsStateWithLifecycle().value?.owner == true

    fun backupAll() = guard(tr(R.string.back_up_all_databases)) {
        scope.launch {
            try {
                val dbs = snap?.projects.orEmpty().flatMap { it.services }.filter { it.kind == "postgres" }
                var n = 0
                dbs.forEach { s -> Dokploy.one("postgres", s.id).backups.forEach { Dokploy.backupNow(it.backupId); n++ } }
                msg = (if (n == 0) tr(R.string.no_postgres_database_has_a_backup) else trn(R.plurals.backups_started, n)) to false
            } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); msg = (e.message ?: "") to true }
        }
    }

    dests.error?.let { ErrorLine(it) }
    if (dests.value?.isEmpty() == true) SrvRow(tr(R.string.no_backup_destination_is_set_up), left = { Mk(Tone.Warn) })
    dests.value?.forEach { d -> SrvRow(d.name, sub = listOfNotNull(d.provider, d.bucket).joinToString(" · "), left = { IconTile(Ph.Database) }) }
    files.value?.takeIf { it.isNotEmpty() }?.let { list ->
        K(tr(R.string.latest_files), Modifier.padding(top = 12.dp))
        list.forEach { f -> SrvRow(f.name, mono = true, titleSize = 13, sub = size(f.size), left = { Mk() }, right = { RightText(ago(parseTime(f.modTime))) }) }
    }
    if (owner) {
        if (adding) AddDestination { adding = false; dests.reload() }
        else Btn(tr(R.string.add_destination), { adding = true }, Modifier.padding(top = 12.dp), icon = Ph.Plus)
    }
    if (!dests.value.isNullOrEmpty()) Btn(tr(R.string.back_up_all_databases_now), ::backupAll, Modifier.padding(top = 16.dp), BtnStyle.Ink)
    Result(msg)
}

@Composable
private fun AddDestination(onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var access by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    val ready = listOf(name, access, secret, bucket, region, endpoint).all { it.isNotBlank() }

    Field(tr(R.string.name), name, { name = it }, Modifier.padding(top = 12.dp), mono = false)
    Field(tr(R.string.access_key), access, { access = it }, Modifier.padding(top = 8.dp), secret = true)
    Field(tr(R.string.secret_key), secret, { secret = it }, Modifier.padding(top = 8.dp), secret = true)
    Field(tr(R.string.bucket), bucket, { bucket = it }, Modifier.padding(top = 8.dp))
    Field(tr(R.string.region), region, { region = it }, Modifier.padding(top = 8.dp))
    Field(tr(R.string.endpoint), endpoint, { endpoint = it }, Modifier.padding(top = 8.dp), keyboard = KeyboardType.Uri)
    err?.let { ErrorLine(it) }
    Row(Modifier.padding(top = 14.dp), Arrangement.spacedBy(8.dp)) {
        Btn(tr(R.string.cancel), onDone, Modifier.weight(1f))
        Btn(
            if (busy) tr(R.string.testing) else tr(R.string.test_and_save),
            {
                guard(tr(R.string.add_backup_destination)) {
                    scope.launch {
                        busy = true
                        err = null
                        try {
                            Dokploy.testDestination(name.trim(), access.trim(), secret.trim(), bucket.trim(), region.trim(), endpoint.trim())
                            Dokploy.createDestination(name.trim(), access.trim(), secret.trim(), bucket.trim(), region.trim(), endpoint.trim())
                            onDone()
                        } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); err = e.message } finally { busy = false }
                    }
                }
            },
            Modifier.weight(1f), BtnStyle.Ink, enabled = ready && !busy,
        )
    }
}
