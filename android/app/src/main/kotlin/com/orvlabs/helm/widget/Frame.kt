package com.orvlabs.helm.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as FixedColor
import com.orvlabs.helm.MainActivity
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.Summary
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.clock
import kotlin.math.roundToInt

private fun cp(l: Long, d: Long, theme: String): androidx.glance.unit.ColorProvider = when (theme) {
    "light" -> FixedColor(Color(l))
    "dark" -> FixedColor(Color(d))
    else -> ColorProvider(day = Color(l), night = Color(d))
}

class W(val ctx: Context, val s: Summary?, val theme: String, val size: DpSize, val cfg: com.orvlabs.helm.data.Config? = null) {
    val bg = cp(0xFFFFFFFF, 0xFF15141B, theme)
    val ink = cp(0xFF17141E, 0xFFF4F3F8, theme)
    val muted = cp(0xFF5B5966, 0xFFB5B2C0, theme)
    val faint = cp(0xFF726F7C, 0xFF908D9A, theme)
    val warn = cp(0xFFA54C00, 0xFFF7AC4D, theme)
    val ok = cp(0xFF007D50, 0xFF56D298, theme)
    val bad = cp(0xFFBE2323, 0xFFF97770, theme)
    val signalFill = cp(0xFF5B32E6, 0xFFB69FFE, theme)
    val signalInk = cp(0xFFFFFFFF, 0xFF180E44, theme)
    val off = cp(0xFFE2E0EA, 0xFF292732, theme)
    val narrow get() = size.width < 200.dp
    val oneRow get() = size.height < 100.dp
    val density = ctx.resources.displayMetrics.density
    fun px(d: Dp) = (d.value * density).roundToInt().coerceAtLeast(1)
    val stale get() = s != null && System.currentTimeMillis() - s.at >= 45 * 60_000L
    val live get() = !stale && s?.linkDown == null
    val tone get() = if (live) ink else muted
    val good get() = if (live) ok else muted
    fun color(k: Int) = when (k) { 0 -> muted; 1 -> ink; 2 -> warn; else -> bad }
    fun time(ms: Long) = clock(ms)
}

@Composable
fun T(text: String, w: W, size: Int = 13, color: androidx.glance.unit.ColorProvider = w.ink, bold: Boolean = false, lines: Int = 1, mod: GlanceModifier = GlanceModifier) =
    Text(
        text, mod,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontFamily = FontFamily.SansSerif),
        maxLines = lines,
    )

@Composable
fun Mark(b: Bitmap?, w: Dp, h: Dp, tint: androidx.glance.unit.ColorProvider) {
    if (b != null) Image(ImageProvider(b), null, GlanceModifier.size(w, h), ContentScale.Fit, colorFilter = ColorFilter.tint(tint))
}

@Composable
fun Stack(l: Layers, w: Dp, h: Dp, c: W, inkTone: androidx.glance.unit.ColorProvider = c.ink) {
    Box(GlanceModifier.size(w, h)) {
        Mark(l.off, w, h, c.off); Mark(l.muted, w, h, c.muted); Mark(l.ink, w, h, inkTone); Mark(l.warn, w, h, c.warn); Mark(l.signal, w, h, c.bad)
    }
}

fun link(ctx: Context, uri: String) = actionStartActivity(Intent(Intent.ACTION_VIEW, uri.toUri(), ctx, MainActivity::class.java))

/** tag priority: stale, link problem, widget alert (text, tone 0..3), time. */
@Composable
fun Eyebrow(w: W, label: String, alert: Pair<String, Int>?, time: Boolean = true) {
    val s = w.s
    val tag: Pair<String, androidx.glance.unit.ColorProvider>? = when {
        s == null -> null
        w.stale -> tr(R.string.w_seen_at, w.time(s.at)) to w.muted
        s.linkDown == "unreachable" -> tr(R.string.w_unreachable) to w.bad
        s.linkDown == "auth" -> tr(R.string.w_sign_in_again) to w.warn
        alert != null -> alert.first to w.color(alert.second)
        time -> w.time(s.at) to w.faint
        else -> null
    }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(label, w, 12, w.muted, bold = true)
        Spacer(GlanceModifier.defaultWeight())
        tag?.let { T(it.first, w, 12, it.second, bold = true) }
    }
}

@Composable
fun Frame(w: W, root: String, uri: String, content: @Composable ColumnScope.() -> Unit) {
    val pad = if (w.narrow) 12.dp else 14.dp
    Box(
        GlanceModifier.fillMaxSize().appWidgetBackground().background(w.bg).cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .semantics { contentDescription = root }.clickable(link(w.ctx, uri)).padding(horizontal = pad, vertical = if (w.oneRow) 8.dp else pad),
    ) {
        if (w.s == null) T(tr(R.string.open_helm_to_set_up_this), w, 14, lines = 2)
        else Column(GlanceModifier.fillMaxSize(), content = content)
    }
}

/** shared sentence for link problems; null when the data is fine. */
fun problemLine(w: W): String? {
    val s = w.s ?: return null
    return when {
        s.linkDown == "auth" -> tr(R.string.w_needs_sign_in)
        s.linkDown == "unreachable" -> tr(R.string.w_server_silent, w.time(s.linkSince ?: s.at))
        w.stale -> tr(R.string.w_no_news_since, w.time(s.at))
        else -> null
    }
}

abstract class HelmGlance : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = Store.readSummary()
        val theme = Store.config.value?.theme ?: "system"
        val cfg = Store.config.value
        provideContent { Body(W(context, s, theme, LocalSize.current, cfg)) }
    }

    @Composable
    abstract fun Body(w: W)
}

abstract class HelmReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        SummaryWorker.schedule(context)
        SummaryWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        if (!SummaryWorker.hasWidget(context)) SummaryWorker.cancel(context)
    }
}

object Widgets {
    suspend fun updateAll(ctx: Context) {
        listOf(HelmWidget(), MapWidget(), ServerWidget(), UptimeWidget(), DeployWidget(), NightWidget()).forEach { runCatching { it.updateAll(ctx) } }
    }
}
