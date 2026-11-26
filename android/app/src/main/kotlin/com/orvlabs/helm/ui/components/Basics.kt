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
import androidx.compose.ui.draw.shadow
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

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 15,
    weight: Int = 400,
    color: Color = H.c.ink,
    mono: Boolean = false,
    family: FontFamily? = null,
    tracking: Float = 0f,
    lineHeight: Float = 0f,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = FontWeight(weight),
            fontFamily = family ?: if (mono) GeistMono else Geist,
            letterSpacing = tracking.em,
            lineHeight = if (lineHeight > 0) lineHeight.em else TextStyle.Default.lineHeight,
            textAlign = align ?: TextAlign.Unspecified,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun H1(text: String, modifier: Modifier = Modifier) =
    Txt(text, modifier.semantics { heading() }, size = 30, weight = 700, tracking = -0.03f, lineHeight = 1.1f)

@Composable
fun H2(text: String, modifier: Modifier = Modifier, color: Color = H.c.ink) =
    Txt(text, modifier, size = 20, weight = 600, tracking = -0.02f, color = color, lineHeight = 1.15f)

@Composable
fun Sub(text: String, modifier: Modifier = Modifier, size: Int = 13, color: Color = H.c.muted, mono: Boolean = false, maxLines: Int = Int.MAX_VALUE) =
    Txt(text, modifier, size = size, color = color, mono = mono, maxLines = maxLines)

@Composable
fun K(text: String, modifier: Modifier = Modifier, color: Color = H.c.muted) =
    Txt(text, modifier, size = 13, weight = 600, color = color)

@Composable
fun DotNum(text: String, size0: Int, modifier: Modifier = Modifier, color: Color = H.c.ink) {
    val size = if (LocalCompact.current) (size0 * 0.6f).toInt() else (size0 * 0.62f).toInt()
    val reduce = com.orvlabs.helm.ui.theme.LocalReduceMotion.current
    if (reduce) return Txt(text, modifier, size = size, color = color, family = Doto, tracking = -0.04f, lineHeight = 1f, weight = 700)
    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            androidx.compose.animation.AnimatedContent(
                ch,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180, i * 30)) +
                        androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180, i * 30)) { rise }) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120, i * 30)) using
                        androidx.compose.animation.SizeTransform(clip = false)
                },
                label = "digit",
            ) { c -> Txt(c.toString(), size = size, color = color, family = Doto, tracking = -0.04f, lineHeight = 1f, weight = 700) }
        }
    }
}

@Composable
fun Ico(v: ImageVector, modifier: Modifier = Modifier, size: Int = 20, tint: Color = H.c.ink, desc: String? = null) {
    Image(
        rememberVectorPainter(v),
        null,
        modifier.size(size.dp).then(if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier),
        colorFilter = ColorFilter.tint(tint),
    )
}

@Composable
fun Rule(modifier: Modifier = Modifier, vertical: Int = 16) {
    Box(modifier.fillMaxWidth().padding(vertical = (vertical / 2).dp).height(1.dp).background(H.c.line))
}

fun Modifier.press(onClick: () -> Unit, label: String? = null, role: Role = Role.Button): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val reduce = com.orvlabs.helm.ui.theme.LocalReduceMotion.current
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.92f else 1f, if (reduce) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f), label = "press")
    alpha(if (pressed) 0.85f else 1f).graphicsLayer { scaleX = scale; scaleY = scale }.clickable(src, null, role = role, onClickLabel = label, onClick = onClick)
}

enum class Tone { Ink, Signal, Warn, Ring, Ok }

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Signal -> H.c.bad
    Tone.Warn -> H.c.warn
    Tone.Ok -> H.c.ok
    Tone.Ring -> H.c.faint
    Tone.Ink -> H.c.ink
}

@Composable
fun toneSoft(tone: Tone): Color = when (tone) {
    Tone.Signal -> H.c.badSoft
    Tone.Warn -> H.c.warnSoft
    Tone.Ok -> H.c.okSoft
    else -> H.c.raise
}

@Composable
fun Mk(tone: Tone = Tone.Ink) {
    val m = Modifier.size(10.dp)
    if (tone == Tone.Ring) Box(m.border(2.dp, H.c.faint, CircleShape)) else Box(m.background(toneColor(tone), CircleShape))
}

@Composable
fun Pill(text: String, tone: Tone = Tone.Ink, modifier: Modifier = Modifier) {
    val fg = if (tone == Tone.Ink) H.c.muted else toneColor(tone)
    Box(modifier.clip(CircleShape).background(toneSoft(tone)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(text, size = 12, weight = 600, color = fg, maxLines = if (text.length <= 10) 1 else 2)
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = H.c.card,
    pad: Int = 16,
    lift: Int = 1,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = H.c
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    val tint = Color(0xFF431532)
    val flat = c.dark || color != c.card
    Column(
        modifier.fillMaxWidth()
            .then(
                if (flat) Modifier else Modifier
                    .shadow((2 * lift).dp, shape, ambientColor = tint.copy(alpha = 0.10f), spotColor = tint.copy(alpha = 0.18f))
                    .shadow((14 * lift).dp, shape, ambientColor = tint.copy(alpha = 0.06f), spotColor = tint.copy(alpha = 0.10f)),
            )
            .clip(shape).background(color, shape)
            .then(if (color == c.card) Modifier.border(1.dp, if (c.dark) Color.White.copy(alpha = 0.06f) else c.ink.copy(alpha = 0.04f), shape) else Modifier)
            .then(if (onClick != null) Modifier.press(onClick) else Modifier)
            .padding(pad.dp),
        content = content,
    )
}

@Composable
fun Li(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    left: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    mono: Boolean = false,
    titleSize: Int = 15,
    rule: Boolean = false,
    subColor: Color = H.c.muted,
    subLines: Int = 2,
) {
    val line = H.c.line
    Row(
        modifier.fillMaxWidth().appear()
            .then(if (onClick != null) Modifier.press(onClick) else Modifier)
            .heightIn(min = 56.dp)
            .drawBehind { if (rule) drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        left?.invoke()
        Column(Modifier.weight(1f)) {
            Txt(title, size = titleSize, weight = 600, mono = mono, maxLines = if (mono) 1 else 2, tracking = if (titleSize > 17) -0.02f else 0f)
            if (sub != null) Sub(sub, color = subColor, maxLines = subLines)
        }
        right?.invoke()
    }
}

@Composable
fun RightText(text: String, color: Color = H.c.faint, size: Int = 12) = Txt(text, size = size, weight = if (size > 12) 600 else 500, mono = false, color = color, maxLines = 1)

@Composable
fun IconTile(v: ImageVector) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(H.c.signalSoft), Alignment.Center) {
        Ico(v, size = 20, tint = H.c.signal)
    }
}

@Composable
fun Stat(label: String, value: String, color: Color = H.c.ink, rule: Boolean = false) {
    val line = H.c.line
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { if (rule) drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(vertical = 12.dp),
        Arrangement.SpaceBetween,
        Alignment.Top,
    ) {
        Txt(label, color = H.c.muted, modifier = Modifier.weight(1f, false).padding(end = 12.dp))
        Txt(value, size = 14, weight = 600, mono = false, color = color, align = TextAlign.End, modifier = Modifier.weight(1f, false))
    }
}

@Composable
fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, badges: Map<Int, Int> = emptyMap()) {
    val c = H.c
    Row(Modifier.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.heightIn(min = 48.dp).press({ if (i != selected) Haptics.fire(Feel.Tick); onSelect(i) }, role = Role.Tab).semantics { this.selected = on },
                Alignment.Center,
            ) {
                Row(
                    Modifier.clip(CircleShape)
                        .background(if (on) c.signal else c.card, CircleShape)
                        .border(1.dp, if (on) c.signal else c.line, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(label, size = 14, weight = 600, color = if (on) c.signalInk else c.muted, maxLines = 1)
                    badges[i]?.takeIf { it > 0 }?.let { Txt("$it", size = 13, weight = 700, color = if (on) c.signalInk else c.bad, modifier = Modifier.padding(start = 6.dp)) }
                }
            }
        }
    }
}

@Composable
fun Tabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = H.c
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(c.raise).padding(4.dp),
        Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape)
                    .background(if (on) c.card else Color.Transparent, CircleShape)
                    .press({ if (!on) Haptics.fire(Feel.Tick); onSelect(i) }, role = Role.Tab).semantics { this.selected = on },
                Alignment.Center,
            ) { Txt(label, size = 14, weight = 600, color = if (on) c.ink else c.muted, maxLines = if (label.length <= 10) 1 else 2, align = TextAlign.Center) }
        }
    }
}
