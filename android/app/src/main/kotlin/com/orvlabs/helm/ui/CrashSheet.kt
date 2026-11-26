package com.orvlabs.helm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Diag
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.Btn
import com.orvlabs.helm.ui.components.BtnStyle
import com.orvlabs.helm.ui.components.H2
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.SheetFrame
import com.orvlabs.helm.ui.components.Sub

@Composable
fun CrashSheet() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf(Diag.pendingCrash()) }
    val text = report ?: return
    val close = { Diag.clear(); report = null }
    SheetFrame(true, close) {
        H2(tr(R.string.diag_title))
        Sub(
            text.lines().take(6).joinToString("\n"),
            Modifier.padding(top = 12.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            size = 12, mono = true,
        )
        Row(Modifier.padding(top = 20.dp), Arrangement.spacedBy(8.dp)) {
            Btn(tr(R.string.diag_copy), {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Helm", text))
            }, Modifier.weight(1f), BtnStyle.Signal, Ph.Copy)
            Btn(tr(R.string.diag_share), {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }, Modifier.weight(1f))
        }
        Btn(tr(R.string.diag_close), close, Modifier.padding(top = 8.dp))
    }
}
