package com.orvlabs.helm.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Caps
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.*
import com.orvlabs.helm.ui.theme.H
import kotlinx.coroutines.launch

internal fun platformName(p: String) = when (p) {
    "dokploy" -> "Dokploy"
    "coolify" -> "Coolify"
    else -> tr(R.string.plat_none)
}

// Brand marks are drawn unaltered: Dokploy ships black (light) and white (dark) variants, Coolify one two-tone mark.
@Composable
internal fun PlatformLogo(platform: String, size: Int, desc: String? = null) {
    val m = Modifier.size(size.dp)
    when (platform) {
        "dokploy" -> Image(painterResource(R.drawable.logo_dokploy), desc, m, colorFilter = ColorFilter.tint(if (H.c.dark) Color.White else Color.Black))
        "coolify" -> Image(painterResource(R.drawable.logo_coolify), desc, m)
        else -> Ico(Ph.Server, size = size, tint = H.c.muted, desc = desc)
    }
}

@Composable
internal fun PlatformTiles(caps: Caps?, active: String, modifier: Modifier = Modifier) {
    var how by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val c = H.c
    val shape = RoundedCornerShape(24.dp)
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Max), Arrangement.spacedBy(10.dp)) {
        listOf("dokploy", "coolify", "none").forEach { p ->
            val found = when (p) { "dokploy" -> caps?.dokploy; "coolify" -> caps?.coolify; else -> null }
            val on = p == active
            val tap = {
                if (found == false) how = p
                else if (found == true && !on) { Haptics.fire(Feel.Tick); scope.launch { Repo.choose(p) } }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(shape).background(c.card)
                    .border(if (on) 2.dp else 1.dp, if (on) c.signal else c.line, shape)
                    .press(tap, platformName(p), Role.RadioButton).semantics { selected = on }
                    .padding(horizontal = 8.dp, vertical = 18.dp),
                Arrangement.spacedBy(10.dp), Alignment.CenterHorizontally,
            ) {
                PlatformLogo(p, 40)
                Txt(platformName(p), size = 14, weight = 600, maxLines = 1)
                if (found != null) Pill(tr(if (found) R.string.plat_detected else R.string.plat_off), if (found) Tone.Ok else Tone.Ink)
            }
        }
    }
    how?.let { HowSheet(it) { how = null } }
}

@Composable
internal fun PlatformSwitch(active: String, onPick: (String) -> Unit) {
    val c = H.c
    Row(Modifier.clip(CircleShape).background(c.raise).padding(4.dp), Arrangement.spacedBy(4.dp)) {
        listOf("dokploy", "coolify").forEach { p ->
            val on = p == active
            Box(
                Modifier.size(width = 56.dp, height = 48.dp).clip(CircleShape).background(if (on) c.card else Color.Transparent)
                    .press({ if (!on) { Haptics.fire(Feel.Tick); onPick(p) } }, platformName(p), Role.Tab).semantics { selected = on },
                Alignment.Center,
            ) { PlatformLogo(p, 22) }
        }
    }
}

@Composable
private fun HowSheet(platform: String, onDismiss: () -> Unit) {
    SheetFrame(true, onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlatformLogo(platform, 32)
            H2(tr(R.string.plat_how))
        }
        Sub("/etc/helm-agent/env", Modifier.padding(top = 16.dp), mono = true)
        CopyLine(if (platform == "coolify") "HELM_COOLIFY_TOKEN=" else "HELM_DOKPLOY_KEY=")
        CopyLine("systemctl restart helm-agent")
    }
}

@Composable
private fun CopyLine(text: String) {
    val ctx = LocalContext.current
    val copy = {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Helm", text))
        Haptics.fire(Feel.Confirm)
    }
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(H.c.raise)
            .press(copy, tr(R.string.diag_copy)).padding(horizontal = 14.dp, vertical = 14.dp),
        Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
    ) {
        Txt(text, Modifier.weight(1f), size = 13, mono = true)
        Ico(Ph.Copy, size = 18, tint = H.c.muted)
    }
}
