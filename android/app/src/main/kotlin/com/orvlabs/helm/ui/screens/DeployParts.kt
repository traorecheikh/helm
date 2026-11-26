package com.orvlabs.helm.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.orvlabs.helm.R
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Deployment
import com.orvlabs.helm.data.Detail
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.data.Plat
import com.orvlabs.helm.data.dokployActive
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.*
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val HASH = Regex("[0-9a-f]{40}")
private val GITHUB = Regex("github\\.com[:/]([^/]+)/([^/]+?)(\\.git)?/?$")
private val BRANCH = Regex("^[a-zA-Z0-9._\\-/#]+$")

internal fun Deployment.hash() = HASH.find(description.orEmpty())?.value
private fun time(d: Deployment) = parseTime(d.createdAt) ?: 0
private fun firstLine(s: String?) = s.orEmpty().lineSequence().firstOrNull().orEmpty()

internal fun deployTone(status: String?) = when (status) { "error" -> Tone.Signal; "running" -> Tone.Warn; "done" -> Tone.Ok; else -> Tone.Ring }
internal fun deployWord(status: String?) = when (status) { "error" -> tr(R.string.status_failed); "running" -> tr(R.string.status_building); "done" -> tr(R.string.done_2); else -> tr(R.string.queued) }
internal fun deployIcon(status: String?): ImageVector = when (status) { "error" -> Ph.Close; "running" -> Ph.Rocket; "done" -> Ph.Check; else -> Ph.History }

private fun githubRepo(d: Detail?): String? {
    if (d == null) return null
    if (d.sourceType == "github" && !d.owner.isNullOrBlank() && !d.repository.isNullOrBlank()) return "${d.owner}/${d.repository}"
    return d.customGitUrl?.let { GITHUB.find(it)?.let { m -> "${m.groupValues[1]}/${m.groupValues[2]}" } }
}

@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp), Arrangement.spacedBy(10.dp), Alignment.CenterHorizontally) {
        Ico(Ph.Rocket, size = 32, tint = H.c.faint)
        Txt(text, size = 15, weight = 600, color = H.c.muted)
    }
}

@Composable
fun DeploysTab(nav: NavController, kind: String, id: String) {
    val load = rememberLoad(kind, id) { Plat.deployments(kind, id).sortedByDescending { parseTime(it.createdAt) ?: 0 } }
    val list = load.value.orEmpty()
    val detail = rememberLoad(kind, id) { Plat.one(kind, id) }.value
    Column(Modifier.padding(top = 6.dp), Arrangement.spacedBy(10.dp)) {
        load.error?.let { ErrorLine(it) }
        if (dokployActive) DeployBranch(nav, kind, id, detail)
        if (list.any { it.status == "done" }) Card { DeployDiff(list, detail) }
        if (!load.loading && list.isEmpty() && load.error == null) EmptyNote(tr(R.string.no_deploys_yet))
        if (list.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                K(tr(R.string.all_deploys))
                if (dokployActive && detail != null && detail.rollbackActive != true) Pill(tr(R.string.dep_rollback_off))
            }
        }
        list.forEach { d -> DeployCard(d) { nav.navigate(DeployR(deploymentId = d.deploymentId)) } }
    }
}

@Composable
private fun DeployCard(d: Deployment, onClick: () -> Unit) {
    val tone = deployTone(d.status)
    var message by remember { mutableStateOf(false) }
    val full = listOfNotNull(d.title, d.description).joinToString("\n\n").trim()
    val long = full.length > 70 || full.contains('\n')
    Card(onClick = onClick, pad = 14) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(toneSoft(tone)), Alignment.Center) {
                Ico(deployIcon(d.status), size = 18, tint = toneColor(tone), desc = deployWord(d.status))
            }
            Column(Modifier.weight(1f)) {
                Txt(firstLine(d.title).ifEmpty { deployWord(d.status) }, weight = 600, maxLines = 3)
                val sub = listOfNotNull(d.hash()?.take(7) ?: firstLine(d.description).takeIf { it.isNotEmpty() }, duration(parseTime(d.startedAt), parseTime(d.finishedAt))).joinToString(" \u00b7 ")
                if (sub.isNotEmpty()) Sub(sub, mono = true, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                RightText(ago(parseTime(d.createdAt)))
                if (long) Box(Modifier.size(40.dp).press({ message = true }, tr(R.string.dep_message)), Alignment.CenterEnd) {
                    Ico(Ph.Scroll, size = 18, tint = H.c.faint)
                }
            }
        }
    }
    SheetFrame(message, { message = false }) {
        Txt(full, size = 15)
        d.hash()?.let { Txt(it.take(7), Modifier.padding(top = 12.dp), size = 13, color = H.c.faint, mono = true) }
    }
}

@Composable
fun DeployBranch(nav: NavController, kind: String, id: String, detail: Detail?) {
    var open by remember { mutableStateOf(false) }
    var branch by remember(detail) { mutableStateOf(detail?.branch.orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }
    val guard = rememberGuard()
    val scope = rememberCoroutineScope()
    Column {
        Btn(tr(R.string.deploy_another_branch), { open = true }, icon = Ph.Rocket)
        SheetFrame(open, { open = false }) {
            Column(Modifier.imePadding()) {
                Field(tr(R.string.branch), branch, { branch = it; err = null }, error = err != null)
                err?.let { ErrorLine(it) }
                Sub(tr(R.string.dep_branch_note), Modifier.padding(top = 8.dp))
                Btn(tr(R.string.deploy_this_branch), {
                    val b = branch.trim()
                    if (!BRANCH.matches(b)) { err = tr(R.string.a_branch_name_uses_letters_digits); return@Btn }
                    guard(tr(R.string.deploy_branch, b)) {
                        scope.launch {
                            try {
                                val t = System.currentTimeMillis()
                                Dokploy.setBranch(kind, id, b)
                                Dokploy.act(kind, id, "deploy")
                                open = false
                                nav.navigate(DeployR(kind = kind, id = id, after = t))
                            } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); err = e.message }
                        }
                    }
                }, Modifier.padding(top = 16.dp), BtnStyle.Ink, enabled = branch.isNotBlank())
            }
        }
    }
}

@Composable
internal fun DeployDiff(list: List<Deployment>, detail: Detail?, modifier: Modifier = Modifier) {
    val done = list.filter { it.status == "done" }.sortedByDescending(::time)
    val live = done.firstOrNull() ?: return
    val prev = done.getOrNull(1)
    val ctx = LocalContext.current
    val lh = live.hash()
    val ph = prev?.hash()
    Column(modifier, Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
            if (prev != null) {
                Version(prev, tr(R.string.previous), Tone.Ring, Modifier.weight(1f))
                Ico(Ph.Go, size = 16, tint = H.c.faint)
            }
            Version(live, tr(R.string.live), Tone.Ok, Modifier.weight(1f))
        }
        if (prev != null && lh != null && lh == ph) Pill(tr(R.string.dep_same))
        val repo = githubRepo(detail)
        if (repo != null && lh != null && ph != null && lh != ph) {
            Btn(tr(R.string.dep_compare), {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/$repo/compare/${ph.take(7)}...${lh.take(7)}".toUri()))
            }, icon = Ph.Out)
        }
    }
}

@Composable
private fun Version(d: Deployment, label: String, tone: Tone, modifier: Modifier) {
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Mk(tone)
            K(label, color = toneColor(tone))
        }
        Txt(d.hash()?.take(7) ?: tr(R.string.no_hash), Modifier.padding(top = 6.dp), size = 16, weight = 600, mono = true, maxLines = 1)
        Sub(firstLine(d.title), maxLines = 3)
        Sub(agoFull(time(d)), size = 12, color = H.c.faint, maxLines = 1)
    }
}
