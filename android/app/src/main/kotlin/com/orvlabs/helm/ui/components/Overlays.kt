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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
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
import kotlinx.coroutines.launch
import com.orvlabs.helm.R
import com.orvlabs.helm.tr

@Composable
fun SlideToAct(label: String, onDone: () -> Unit, modifier: Modifier = Modifier, fill: Color = H.c.ink, ink: Color = H.c.bg) {
    val c = H.c
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var x by remember { mutableFloatStateOf(0f) }
    var step by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(60.dp).clip(CircleShape).background(fill)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label) { Haptics.fire(Feel.Commit); onDone(); true }
            },
        Alignment.CenterStart,
    ) {
        val max = with(density) { (maxWidth - 60.dp).toPx() }
        Txt(label, Modifier.fillMaxWidth(), weight = 500, color = ink, align = TextAlign.Center)
        Txt("›››", Modifier.align(Alignment.CenterEnd).padding(end = 22.dp), size = 13, mono = true, color = ink.copy(alpha = 0.45f))
        Box(
            Modifier.offset { IntOffset(x.roundToInt() + 5.dp.roundToPx(), 0) }
                .size(50.dp).clip(CircleShape).background(c.signalFill)
                .draggable(
                    rememberDraggableState {
                        x = (x + it).coerceIn(0f, max)
                        val s = (x / max * 10).toInt().coerceIn(0, 10)
                        if (s != step && s in 1..7) Haptics.fire(Feel.Progress, s / 10f)
                        if ((s >= 8) != (step >= 8)) Haptics.fire(if (s >= 8) Feel.ThresholdIn else Feel.ThresholdOut)
                        step = s
                    },
                    Orientation.Horizontal,
                    onDragStopped = {
                        if (x >= max * 0.8f) { Haptics.fire(Feel.Commit); onDone() }
                        if (reduce) x = 0f else scope.launch { animate(x, 0f) { v, _ -> x = v } }
                    },
                ),
            Alignment.Center,
        ) { Ico(Ph.Go, size = 20, tint = c.signalInk) }
    }
}

@Composable
fun HoldButton(label: String, holdMs: Int, onConfirm: () -> Unit) {
    val c = H.c
    val scope = rememberCoroutineScope()
    var p by remember { mutableFloatStateOf(0f) }
    val confirm by rememberUpdatedState(onConfirm)
    Box(
        Modifier.fillMaxWidth().height(60.dp).clip(CircleShape).border(1.5.dp, c.bad, CircleShape)
            .semantics(mergeDescendants = true) { role = Role.Button; onClick(label) { confirm(); true } }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    Haptics.fire(Feel.Progress, 0f)
                    val job: Job = scope.launch {
                        var last = 0
                        val n = if (holdMs >= 3000) 6 else 4
                        animate(0f, 1f, animationSpec = tween(holdMs, easing = LinearEasing)) { v, _ ->
                            p = v
                            val i = (v * n).toInt()
                            if (i > last && i < n) Haptics.fire(Feel.Progress, v)
                            last = i
                        }
                        Haptics.fire(Feel.Commit)
                        confirm()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    p = 0f
                })
            },
        Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(p).background(c.bad.copy(alpha = 0.16f)))
        Txt(label, Modifier.padding(horizontal = 24.dp), weight = 600, color = c.bad, maxLines = 1)
    }
}

@Composable
fun SheetFrame(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = H.c
    val state = rememberModalBottomSheetState(initialDetent = SheetDetent.Hidden)
    LaunchedEffect(visible) { state.animateTo(if (visible) SheetDetent.FullyExpanded else SheetDetent.Hidden) }
    UnstyledModalBottomSheet(state, onDismiss = onDismiss, overlay = { Scrim(scrimColor = c.scrim) }) {
        Sheet(Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 26.dp)) {
            Column {
                Box(Modifier.align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).background(c.line, CircleShape))
                Box(Modifier.height(18.dp))
                content()
            }
        }
    }
}

@Composable
fun ConfirmSheet(
    visible: Boolean,
    title: String,
    body: String,
    holdLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    holdMs: Int = 1200,
    extra: (@Composable () -> Unit)? = null,
) {
    SheetFrame(visible, onDismiss) {
        H2(title)
        Sub(body, Modifier.padding(top = 8.dp), size = 14)
        extra?.invoke()
        Box(Modifier.height(20.dp))
        HoldButton(holdLabel, holdMs, onConfirm)
        Box(Modifier.height(8.dp))
        Btn(tr(R.string.cancel), onDismiss)
    }
}

@Composable
fun Dock(current: Int, pips: Set<Int>, onTab: (Int) -> Unit, onSearch: () -> Unit, apps: Boolean = true) {
    val c = H.c
    val compact = LocalCompact.current
    val bh = if (compact) 48.dp else 60.dp
    val ph = if (compact) 40.dp else 48.dp
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val tabs = listOf(tr(R.string.tab_home) to (Ph.Home to Ph.HomeFill), tr(R.string.tab_apps) to (Ph.Apps to Ph.AppsFill), tr(R.string.shield) to (Ph.Shield to Ph.ShieldFill), tr(R.string.tab_server) to (Ph.Server to Ph.ServerFill))
    val pos = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val spec = if (reduce) androidx.compose.animation.core.snap<Float>() else spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    val px by androidx.compose.animation.core.animateFloatAsState(pos[current]?.first ?: 0f, spec, label = "pillX")
    val pw by androidx.compose.animation.core.animateFloatAsState(pos[current]?.second ?: 0f, spec, label = "pillW")
    Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 14.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(bh).shadow(10.dp, CircleShape, ambientColor = c.ink.copy(alpha = 0.12f), spotColor = c.ink.copy(alpha = 0.12f)).clip(CircleShape).background(c.dockBar).border(1.dp, c.dockBorder, CircleShape)) {
            Box(
                Modifier.align(Alignment.CenterStart).offset { IntOffset(px.roundToInt(), 0) }
                    .width(with(density) { pw.toDp() }).height(ph).clip(CircleShape).background(c.dockPill),
            )
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                tabs.forEachIndexed { i, (label, icons) ->
                    if (i == 1 && !apps) return@forEachIndexed
                    val on = i == current
                    val tint by androidx.compose.animation.animateColorAsState(if (on) c.dockPillInk else c.dockIdle, tween(if (reduce) 0 else 160), label = "tint")
                    Row(
                        (if (on) Modifier else Modifier.weight(1f)).height(ph).defaultMinSize(minWidth = 48.dp).clip(CircleShape)
                            .onPlaced { pos[i] = it.positionInParent().x to it.size.width.toFloat() }
                            .press({ if (i != current) Haptics.fire(Feel.Tick); onTab(i) }, role = Role.Tab).semantics { selected = on; contentDescription = label + if (i in pips) tr(R.string.needs_attention) else "" }
                            .padding(horizontal = if (on) 14.dp else 0.dp),
                        Arrangement.Center, Alignment.CenterVertically,
                    ) {
                        Box {
                            androidx.compose.animation.Crossfade(on, animationSpec = tween(if (reduce) 0 else 160), label = "icon") { isOn ->
                                Ico(if (isOn) icons.second else icons.first, size = 22, tint = tint)
                            }
                            if (!on && i in pips) Box(Modifier.align(Alignment.TopEnd).offset(4.dp, (-3).dp).size(8.dp).background(c.bad, CircleShape))
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            on,
                            enter = androidx.compose.animation.fadeIn(tween(if (reduce) 0 else 160)) + androidx.compose.animation.expandHorizontally(tween(if (reduce) 0 else 220)),
                            exit = androidx.compose.animation.fadeOut(tween(if (reduce) 0 else 100)) + androidx.compose.animation.shrinkHorizontally(tween(if (reduce) 0 else 220)),
                        ) { Txt(label, Modifier.padding(start = 6.dp), size = 13, weight = 600, color = tint, maxLines = 1) }
                    }
                }
            }
        }
        Box(
            Modifier.size(bh).clip(CircleShape).background(c.signalFill).press(onSearch, tr(R.string.search_and_commands)),
            Alignment.Center,
        ) { Ico(Ph.Search, size = 24, tint = c.signalInk) }
    }
}
