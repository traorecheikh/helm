package com.orvlabs.helm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orvlabs.helm.R
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.data.Plat
import com.orvlabs.helm.data.dokployActive
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.SavedAction
import com.orvlabs.helm.data.Snapshot
import com.orvlabs.helm.data.Step
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Btn
import com.orvlabs.helm.ui.components.BtnStyle
import com.orvlabs.helm.ui.components.Chips
import com.orvlabs.helm.ui.components.ErrorLine
import com.orvlabs.helm.ui.components.Feel
import com.orvlabs.helm.ui.components.Field
import com.orvlabs.helm.ui.components.H2
import com.orvlabs.helm.ui.components.Haptics
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.IconTile
import com.orvlabs.helm.ui.components.K
import com.orvlabs.helm.ui.components.Li
import com.orvlabs.helm.ui.components.Mk
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.SheetFrame
import com.orvlabs.helm.ui.components.Tone
import com.orvlabs.helm.ui.components.press
import com.orvlabs.helm.ui.components.rememberGuard
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val TYPES get() = listOf("redeploy" to tr(R.string.redeploy_2), "restart" to tr(R.string.restart), "start" to tr(R.string.start), "stop" to tr(R.string.stop), "traefik" to tr(R.string.reload_traefik), "clean" to tr(R.string.clean_docker)).take(if (dokployActive) 6 else 4)

private suspend fun exec(s: Step, snap: Snapshot?) {
    when (s.type) {
        "redeploy", "start", "stop" -> Plat.act(s.kind, s.id, s.type)
        "restart" -> {
            val cs = snap?.containersOf(snap.service(s.id)).orEmpty()
            if (cs.isEmpty() && dokployActive) throw ApiError(tr(R.string.no_container_found_for, s.name), ApiError.Kind.Other)
            Plat.restart(s.kind, s.id, cs)
        }
        "traefik" -> Dokploy.reloadTraefik()
        else -> Dokploy.cleanDocker()
    }
}

private enum class Run { Wait, Going, Done, Fail }

@Composable
private fun RoundBtn(icon: ImageVector, desc: String, tint: Color, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).press(onClick, desc), Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(bg), Alignment.Center) { Ico(icon, size = 20, tint = tint, desc = desc) }
    }
}

@Composable
internal fun SavedSection() {
    val snap = Repo.state.collectAsStateWithLifecycle().value.snap
    var list by remember { mutableStateOf<List<SavedAction>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<SavedAction?>(null) }
    val states = remember { mutableStateListOf<Pair<Run, String>>() }
    val scope = rememberCoroutineScope()
    val guard = rememberGuard()
    val c = H.c
    LaunchedEffect(Unit) { list = Store.readSaved() }

    fun run(a: SavedAction) = guard(tr(R.string.run, a.name)) {
        running = a
        states.clear()
        states.addAll(a.steps.map { Run.Wait to "" })
        scope.launch {
            for ((i, st) in a.steps.withIndex()) {
                states[i] = Run.Going to ""
                try {
                    exec(st, snap)
                    states[i] = Run.Done to ""
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    Haptics.fire(Feel.Reject)
                    states[i] = Run.Fail to e.message.orEmpty()
                    break
                }
            }
        }
    }

    K(tr(R.string.saved_actions), Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp))
    RowsCard {
        list.forEach { a ->
            Li(
                a.name, sub = a.steps.joinToString(" › ") { it.label }, rule = true,
                right = {
                    RoundBtn(SI.Play, tr(R.string.run_2), c.signalInk, c.signal) { run(a) }
                    RoundBtn(SI.Trash, tr(R.string.remove), c.bad, c.badSoft) { scope.launch { list = list - a; Store.writeSaved(list) } }
                },
            )
        }
        Li(tr(R.string.new_saved_action), left = { IconTile(Ph.Plus) }, onClick = { editing = true })
    }

    if (editing) SheetFrame(true, { editing = false }) {
        SheetBody {
            Editor(snap) { new ->
                editing = false
                if (new != null) scope.launch { list = list + new; Store.writeSaved(list) }
            }
        }
    }
    running?.let { a ->
        SheetFrame(true, { running = null }) {
            SheetBody {
                H2(a.name)
                a.steps.forEachIndexed { i, st ->
                    val (state, msg) = states.getOrElse(i) { Run.Wait to "" }
                    Li(st.label, sub = null, left = { Mk(when (state) { Run.Done -> Tone.Ok; Run.Fail -> Tone.Signal; Run.Going -> Tone.Warn; Run.Wait -> Tone.Ring }) })
                    if (state == Run.Fail) ErrorLine(msg)
                }
            }
        }
    }
}

@Composable
private fun Editor(snap: Snapshot?, done: (SavedAction?) -> Unit) {
    var name by remember { mutableStateOf("") }
    val steps = remember { mutableStateListOf<Step>() }
    var type by remember { mutableIntStateOf(0) }
    val (t, _) = TYPES[type]
    val services = snap?.projects.orEmpty().flatMap { it.services }.filter { !it.isDb }

    H2(tr(R.string.new_saved_action))
    Field(tr(R.string.name), name, { name = it }, Modifier.padding(top = 14.dp), mono = false)
    if (steps.isNotEmpty()) K(tr(R.string.set_steps), Modifier.padding(top = 18.dp))
    steps.forEachIndexed { i, s ->
        Li("${i + 1}. ${s.label}", right = { Ico(Ph.Close, size = 18, tint = H.c.muted, desc = tr(R.string.remove)) }, onClick = { steps.removeAt(i) })
    }
    Box(Modifier.padding(top = 14.dp)) { Chips(TYPES.map { it.second }, type, { type = it }) }
    if (t in setOf("traefik", "clean")) Btn(tr(R.string.add, TYPES[type].second), { steps += Step(t) }, Modifier.padding(top = 8.dp), icon = Ph.Plus)
    else services.forEach { s -> Li(s.name, left = { IconTile(Ph.Plus) }, onClick = { steps += Step(t, s.kind, s.id, s.name) }) }
    Row(Modifier.padding(top = 20.dp), Arrangement.spacedBy(8.dp)) {
        Btn(tr(R.string.cancel), { done(null) }, Modifier.weight(1f))
        Btn(tr(R.string.save), { done(SavedAction(name.trim(), steps.toList())) }, Modifier.weight(1f), BtnStyle.Signal, enabled = name.isNotBlank() && steps.isNotEmpty())
    }
}
