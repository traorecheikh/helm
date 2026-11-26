package com.orvlabs.helm.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.orvlabs.helm.R

@Immutable
class HelmColors(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val off: Color,
    val raise: Color,
    val signal: Color,
    val signalInk: Color,
    val signalFill: Color,
    val signalSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val ok: Color,
    val okSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val accent: Color,
    val scrim: Color,
    val dockBar: Color,
    val dockBorder: Color,
    val dockPill: Color,
    val dockPillInk: Color,
    val dockIdle: Color,
)

private val Light = HelmColors(
    dark = false,
    bg = Color(0xFFFAF5F8), card = Color(0xFFFFFFFF), ink = Color(0xFF1C1319), muted = Color(0xFF64565F), faint = Color(0xFF7A6D75),
    line = Color(0xFFE9E0E6), off = Color(0xFFE9E0E6), raise = Color(0xFFF6EDF2),
    signal = Color(0xFFD11D90), signalInk = Color(0xFFFFFFFF), signalFill = Color(0xFFD11D90), signalSoft = Color(0xFFFFE3F5),
    warn = Color(0xFFA54C00), warnSoft = Color(0xFFFFE9CB), ok = Color(0xFF007D50), okSoft = Color(0xFFCFF6E0),
    bad = Color(0xFFBE2323), badSoft = Color(0xFFFFE7E6), accent = Color(0xFFFFD12E),
    scrim = Color(0x661C1319),
    dockBar = Color(0xFFFFFFFF), dockBorder = Color(0xFFE9E0E6), dockPill = Color(0xFFD11D90),
    dockPillInk = Color(0xFFFFFFFF), dockIdle = Color(0xFF7A6D75),
)

private val Dark = HelmColors(
    dark = true,
    bg = Color(0xFF100A0E), card = Color(0xFF1A1217), ink = Color(0xFFF7F2F5), muted = Color(0xFFBEAFB8), faint = Color(0xFF988B93),
    line = Color(0xFF30252C), off = Color(0xFF30252C), raise = Color(0xFF241B21),
    signal = Color(0xFFF278B9), signalInk = Color(0xFF290520), signalFill = Color(0xFFF278B9), signalSoft = Color(0xFF48193B),
    warn = Color(0xFFF7AC4D), warnSoft = Color(0xFF382409), ok = Color(0xFF56D298), okSoft = Color(0xFF0B2E1E),
    bad = Color(0xFFF97770), badSoft = Color(0xFF3F191A), accent = Color(0xFFFFD12E),
    scrim = Color(0x99000000),
    dockBar = Color(0xFF1A1217), dockBorder = Color(0xFF30252C), dockPill = Color(0xFFF278B9),
    dockPillInk = Color(0xFF290520), dockIdle = Color(0xFF988B93),
)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Geist = FontFamily(variable(R.font.schibsted, 400), variable(R.font.schibsted, 500), variable(R.font.schibsted, 600), variable(R.font.schibsted, 700), variable(R.font.schibsted, 900))
val GeistMono = FontFamily(variable(R.font.geist_mono, 400), variable(R.font.geist_mono, 500))
val Doto = Geist

val LocalColors = compositionLocalOf { Light }
val LocalReduceMotion = compositionLocalOf { false }

object H {
    val c: HelmColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
}

@Composable
fun HelmTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val resolver = LocalContext.current.contentResolver
    val reduce = remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    CompositionLocalProvider(
        LocalColors provides if (dark) Dark else Light,
        LocalReduceMotion provides reduce,
        content = content,
    )
}
