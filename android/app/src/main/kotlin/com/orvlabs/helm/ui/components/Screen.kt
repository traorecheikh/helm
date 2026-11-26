package com.orvlabs.helm.ui.components  import androidx.compose.animation.core.animateFloat

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.orvlabs.helm.ui.theme.Doto
import com.orvlabs.helm.ui.theme.Geist
import com.orvlabs.helm.ui.theme.GeistMono
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.R
import com.orvlabs.helm.tr

@Composable
fun Back(label: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(top = 4.dp).size(48.dp).press(onClick, tr(R.string.back_to, label)),
        Alignment.CenterStart,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(H.c.card).border(1.dp, H.c.line, CircleShape), Alignment.Center) {
            Ico(Ph.Back, size = 18)
        }
    }
}

@Composable
fun Screen(
    modifier: Modifier = Modifier,
    back: String? = null,
    onBack: () -> Unit = {},
    dock: Boolean = true,
    scroll: Boolean = true,
    bg: Color = H.c.bg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp)
            .then(modifier),
    ) {
        if (back != null) Back(back, onBack) else Box(Modifier.height(12.dp))
        LoadingLine(firstLoads.intValue > 0)
        androidx.compose.runtime.CompositionLocalProvider(LocalStagger provides remember { Stagger() }) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .imePadding()
                .padding(PaddingValues(bottom = if (dock) (if (LocalCompact.current) 72.dp else 104.dp) else 16.dp)),
            content = content,
        )
        }
    }
}

@Composable
private fun LoadingLine(active: Boolean) {
    val c = H.c
    if (!active) return
    val still = androidx.compose.ui.platform.LocalContext.current.let {
        android.provider.Settings.Global.getFloat(it.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "load")
    val x by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(1100)), label = "x")
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics { contentDescription = tr(R.string.loading_2) }, Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(2.dp)) {
            drawRect(c.line)
            val w = size.width * 0.28f
            drawRect(c.signal, Offset(if (still) 0f else (size.width + w) * x - w, 0f), androidx.compose.ui.geometry.Size(w, size.height))
        }
        Sub(tr(R.string.loading), size = 12, mono = true)
    }
}
