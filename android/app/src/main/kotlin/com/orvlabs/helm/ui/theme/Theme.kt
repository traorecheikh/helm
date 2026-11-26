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
    bg = Color(0xFFF6F5FB), card = Color(0xFFFFFFFF), ink = Color(0xFF17141E), muted = Color(0xFF5B5966), faint = Color(0xFF726F7C),
    line = Color(0xFFE2E0EA), off = Color(0xFFE2E0EA), raise = Color(0xFFEFEEF7),
    signal = Color(0xFF5B32E6), signalInk = Color(0xFFFFFFFF), signalFill = Color(0xFF5B32E6), signalSoft = Color(0xFFE7E4FF),
    warn = Color(0xFFA54C00), warnSoft = Color(0xFFFFE9CB), ok = Color(0xFF007D50), okSoft = Color(0xFFCFF6E0),
    bad = Color(0xFFBE2323), badSoft = Color(0xFFFFE7E6), accent = Color(0xFFFFD12E),
    scrim = Color(0x6617141E),
    dockBar = Color(0xFFFFFFFF), dockBorder = Color(0xFFE2E0EA), dockPill = Color(0xFF5B32E6),
    dockPillInk = Color(0xFFFFFFFF), dockIdle = Color(0xFF726F7C),
)

private val Dark = HelmColors(
    dark = true,
    bg = Color(0xFF0D0B12), card = Color(0xFF15141B), ink = Color(0xFFF4F3F8), muted = Color(0xFFB5B2C0), faint = Color(0xFF908D9A),
    line = Color(0xFF292732), off = Color(0xFF292732), raise = Color(0xFF1F1C26),
    signal = Color(0xFFB69FFE), signalInk = Color(0xFF180E44), signalFill = Color(0xFFB69FFE), signalSoft = Color(0xFF2E274E),
    warn = Color(0xFFF7AC4D), warnSoft = Color(0xFF382409), ok = Color(0xFF56D298), okSoft = Color(0xFF0B2E1E),
    bad = Color(0xFFF97770), badSoft = Color(0xFF3F191A), accent = Color(0xFFFFD12E),
    scrim = Color(0x99000000),
    dockBar = Color(0xFF15141B), dockBorder = Color(0xFF292732), dockPill = Color(0xFFB69FFE),
    dockPillInk = Color(0xFF180E44), dockIdle = Color(0xFF908D9A),
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
