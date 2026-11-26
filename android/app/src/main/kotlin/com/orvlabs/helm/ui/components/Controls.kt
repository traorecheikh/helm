package com.orvlabs.helm.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.SheetDetent
import com.composeunstyled.Scrim
import com.composeunstyled.Sheet
import com.composeunstyled.Thumb
import com.composeunstyled.Track
import com.composeunstyled.UnstyledModalBottomSheet
import com.composeunstyled.UnstyledSwitch
import com.composeunstyled.rememberModalBottomSheetState
import com.orvlabs.helm.ui.theme.GeistMono
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import com.orvlabs.helm.R
import com.orvlabs.helm.tr
import kotlinx.coroutines.launch

enum class BtnStyle { Plain, Ink, Signal, Danger }

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Plain,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = H.c
    val (bg, fg, border) = when (style) {
        BtnStyle.Ink -> Triple(c.ink, c.bg, c.ink)
        BtnStyle.Signal -> Triple(c.signalFill, c.signalInk, c.signalFill)
        BtnStyle.Danger -> Triple(c.badSoft, c.bad, c.badSoft)
        BtnStyle.Plain -> Triple(c.raise, c.ink, c.raise)
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 54.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape).background(bg).border(1.dp, border, CircleShape)
            .then(if (enabled) Modifier.press(onClick) else Modifier)
            .padding(horizontal = 20.dp),
        Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        Alignment.CenterVertically,
    ) {
        if (icon != null) Ico(icon, size = 18, tint = fg)
        Txt(text, weight = 600, color = fg, align = TextAlign.Center)
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
    secret: Boolean = false,
    error: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onSubmit: (() -> Unit)? = null,
    singleLine: Boolean = true,
) {
    val c = H.c
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth()
            .background(c.card, RoundedCornerShape(18.dp))
            .border(if (focused) 2.dp else 1.dp, if (error) c.bad else if (focused) c.signal else c.line, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        K(label, color = if (error) c.bad else c.muted)
        BasicTextField(
            value, onChange,
            Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 24.dp)
                .semantics { contentDescription = label }
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(
                color = c.ink, fontSize = 15.sp,
                fontFamily = if (mono) GeistMono else com.orvlabs.helm.ui.theme.Geist,
            ),
            cursorBrush = SolidColor(c.signal),
            singleLine = singleLine,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false,
                imeAction = if (onSubmit != null) androidx.compose.ui.text.input.ImeAction.Go else androidx.compose.ui.text.input.ImeAction.Default),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onSubmit?.invoke() }),
        )
    }
}

@Composable
fun Toggle(checked: Boolean) {
    val c = H.c
    UnstyledSwitch(checked, null, Modifier.size(46.dp, 28.dp)) {
        Track(Modifier.fillMaxSize().clip(CircleShape).background(if (checked) c.signal else c.off).padding(3.dp)) {
            Thumb(Modifier.size(22.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.toggleable(checked, role = Role.Switch, onValueChange = { Haptics.fire(if (it) Feel.ToggleOn else Feel.ToggleOff); onChange(it) })) {
        Li(title, sub = sub, right = { Toggle(checked) })
    }
}

@Composable
private fun rememberBlink(): State<Float> {
    if (LocalReduceMotion.current) return remember { mutableStateOf(1f) }
    return rememberInfiniteTransition(label = "blink").animateFloat(
        1f, 0.35f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "a",
    )
}

enum class Dot { Ink, Signal, Off }

@Composable
fun DotGrid(dots: List<Dot>, cols: Int, modifier: Modifier = Modifier, size: Int = 11, gap: Int = 6, dim: Boolean = false) {
    val c = H.c
    val blink = if (Dot.Signal in dots) rememberBlink() else null
    val rows = (dots.size + cols - 1) / cols
    Canvas(modifier.size((cols * size + (cols - 1) * gap).dp, (rows * size + (rows - 1) * gap).dp)) {
        val d = size.dp.toPx()
        val g = gap.dp.toPx()
        dots.forEachIndexed { i, k ->
            val col = when (k) { Dot.Ink -> c.ok; Dot.Signal -> c.bad.copy(alpha = blink?.value ?: 1f); Dot.Off -> c.off }
            drawCircle(if (dim) col.copy(alpha = col.alpha * 0.45f) else col, d / 2, Offset((i % cols) * (d + g) + d / 2, (i / cols) * (d + g) + d / 2))
        }
    }
}

@Composable
fun Seg(percent: Double, warn: Boolean = false, modifier: Modifier = Modifier, desc: String) {
    val c = H.c
    val reduce = LocalReduceMotion.current
    val shown by androidx.compose.animation.core.animateFloatAsState(percent.toFloat(), if (reduce) androidx.compose.animation.core.snap() else tween(300), label = "seg")
    Canvas(modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = desc }) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(c.raise, Offset.Zero, size, r)
        val w = size.width * (shown / 100f).coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(if (warn) c.warn else c.signal, Offset.Zero, Size(w.coerceAtLeast(size.height), size.height), r)
    }
}

@Composable
fun MemBars(values: List<Double>, modifier: Modifier = Modifier) {
    val c = H.c
    val reduce = LocalReduceMotion.current
    val shownValues = values.map { androidx.compose.animation.core.animateFloatAsState(it.toFloat(), if (reduce) androidx.compose.animation.core.snap() else tween(300), label = "bar").value.toDouble() }
    Canvas(modifier.fillMaxWidth().height(90.dp)) {
        val slots = 24
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (slots - 1)) / slots
        val offset = slots - shownValues.size.coerceAtMost(slots)
        for (i in 0 until offset) {
            drawRoundRect(c.off, Offset(i * (w + gap), size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
        shownValues.takeLast(slots).forEachIndexed { j, v ->
            val i = j + offset
            val h = (size.height * v / 100).toFloat().coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (v > 85) c.warn else c.signal,
                Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2),
            )
        }
    }
}

@Composable
fun Banner(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(H.c.signalSoft).padding(horizontal = 16.dp, vertical = 12.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
    ) {
        if (icon != null) Ico(icon, size = 18, tint = H.c.signal)
        Txt(text, size = 14, weight = 600, color = H.c.signal)
    }
}

@Composable
fun Notice(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(H.c.badSoft).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Txt(title, weight = 600, color = H.c.bad)
        Sub(body, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = H.c.signal) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(H.c.raise)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun Failed(load: Load<*>) {
    val e = load.error ?: return
    ErrorLine(e, Modifier.padding(top = 12.dp))
    Btn(tr(R.string.try_again), load.reload, Modifier.padding(top = 12.dp), icon = Ph.Refresh)
}

@Composable
fun Empty(title: String, body: String? = null) {
    Column(Modifier.padding(top = 16.dp)) {
        Txt(title, size = 16, weight = 600)
        if (body != null) Sub(body, Modifier.padding(top = 6.dp), size = 14)
    }
}

@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 6.dp), Arrangement.spacedBy(6.dp), Alignment.Top) {
        Ico(Ph.Warning, Modifier.padding(top = 2.dp), size = 14, tint = H.c.bad)
        Txt(text, size = 13, color = H.c.bad)
    }
}
