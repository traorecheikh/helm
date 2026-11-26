package com.orvlabs.helm.cover

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Plat
import com.orvlabs.helm.data.Live
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.World
import com.orvlabs.helm.data.parseTime
import com.orvlabs.helm.data.quietNow
import com.orvlabs.helm.tr
import com.orvlabs.helm.ui.components.EveryWhileVisible
import com.orvlabs.helm.ui.components.Feel
import com.orvlabs.helm.ui.components.Haptics
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.components.rememberLoad
import com.orvlabs.helm.ui.components.rememberLogStream
import com.orvlabs.helm.ui.components.rememberRipples
import com.orvlabs.helm.ui.feelEvent
import com.orvlabs.helm.ui.screens.deployStep
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.HelmTheme
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** A deep link the cover could not confirm; the main screen opens it after unfolding. */
object CoverLink {
    var pending: String? = null
    var face = 0
    var deployLink: String? = null

    fun forCurrentFace(): String? = when (face) {
        1 -> "helm://shield"
        2 -> "helm://server"
        3 -> deployLink
        else -> null
    }
}

private const val FACES = 5

@Composable
fun CoverRoot() {
    val cfg by Store.config.collectAsStateWithLifecycle()
    val c = cfg ?: return Box(Modifier.fillMaxSize())
    val activity = LocalActivity.current as ComponentActivity
    val d = LocalDensity.current
    HelmTheme(c.theme) {
        val dark = H.c.dark
        SideEffect {
            val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            activity.enableEdgeToEdge(bars, bars)
        }
        CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.3f))) {
            if (c.onboarded && c.deviceId.isNotEmpty()) Faces() else Box(Modifier.fillMaxSize().background(H.c.bg).padding(24.dp), Alignment.Center) {
                Txt(tr(R.string.cover_unfold_setup), size = 18, weight = 600, align = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Faces() {
    val s by Repo.state.collectAsStateWithLifecycle()
    val cfg by Store.config.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = CoverLink.face) { FACES }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { CoverLink.face = it } }
    var note by remember { mutableStateOf<String?>(null) }
    val intro = remember { Animatable(if (reduce) 1f else 0f) }

    val geo = rememberLoad { Agent.geo() }
    val panic = rememberLoad { Agent.panic() }
    val updates = rememberLoad { Agent.updates() }
    val summary = rememberLoad { Store.readSummary() }
    EveryWhileVisible(15_000) { Repo.refresh() }
    LaunchedEffect(Unit) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(60_000); geo.reload(); panic.reload() } } }
    LaunchedEffect(Unit) {
        Live.events.collect { e ->
            feelEvent(e)
            if (e.type == "ban") geo.reload() else if (e.type == "dokploy") Repo.refresh()
        }
    }
    LaunchedEffect(Unit) {
        if (reduce) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { intro.snapTo(0f); intro.animateTo(1f, tween(760, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) }
    }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.drop(1).collect { Haptics.fire(Feel.Tick) } }
    LaunchedEffect(note) { if (note != null) { delay(6000); note = null } }

    val ctx = LocalContext.current
    val world = remember { World.load(ctx) }
    val ripples = rememberRipples(geo.value)
    val snap = s.snap
    val deploy = snap?.deployments?.maxByOrNull { parseTime(it.createdAt) ?: 0 }
    val logPath = deploy?.logPath?.takeIf { deploy.status == "running" && it.isNotEmpty() }
    val stream = rememberLogStream(logPath) { logPath?.let { Agent.deploymentLogs(it) } }
    val quietUntil = cfg?.takeIf { it.quietOn && quietNow(it.quietStart, it.quietEnd) }?.let { "%02d:%02d".format(it.quietEnd / 60, it.quietEnd % 60) }
    val down = s.uptime?.services?.count { it.current != "up" } ?: snap?.containers?.count { it.state in setOf("restarting", "dead") } ?: 0
    val problems = if (health(s) > 0f) (snap?.let { Repo.problems(it, s.stats).size } ?: 0).coerceAtLeast(1) else 0

    val guard = rememberCoverGuard { uri -> CoverLink.pending = uri; note = tr(R.string.cover_unfold) }

    fun act(ok: String?, feel: Feel, block: suspend () -> Unit) = scope.launch {
        try { block(); Haptics.fire(feel, key = "panic"); note = ok; geo.reload(); panic.reload() } catch (e: CancellationException) { throw e } catch (e: ApiError) { Haptics.fire(Feel.Reject); note = e.message }
    }

    Box(Modifier.fillMaxSize().background(H.c.bg)) {
        VerticalPager(
            pager,
            Modifier.fillMaxSize().graphicsLayer {
                val p = intro.value
                rotationX = (1f - p) * 62f
                transformOrigin = TransformOrigin(0.5f, 1f)
                cameraDistance = 12f * density
                alpha = (p * 1.6f).coerceAtMost(1f)
                scaleX = 0.94f + 0.06f * p; scaleY = scaleX
            },
            beyondViewportPageCount = 0,
        ) { page ->
            when (page) {
                0 -> PulseFace(s)
                1 -> ShieldFace(
                    geo.value, panic.value, world, ripples,
                    onUnban = { ip -> guard(tr(R.string.unban_2, ip), "helm://bans") { act(tr(R.string.is_unbanned, ip), Feel.Confirm) { Agent.unban(ip) } } },
                    onLockdown = { on ->
                        guard(if (on) tr(R.string.lock_down_the_server) else tr(R.string.end_lockdown), "helm://shield") {
                            act(if (on) null else tr(R.string.lockdown_ended), if (on) Feel.Seal else Feel.Recovery) { Agent.setPanic(if (on) 60 else 0) }
                        }
                    },
                )
                2 -> ServerFace(s.stats, active = pager.settledPage == 2)
                3 -> DeployFace(deploy.also { d -> CoverLink.deployLink = d?.let { "helm://deploy?deploymentId=${it.deploymentId}" } }, deploy?.let { snap?.service(it.serviceId)?.name ?: it.title }.orEmpty(), if (stream.lines.isEmpty()) -1 else deployStep(stream.lines)) {
                    val dd = deploy ?: return@DeployFace
                    guard(tr(R.string.redeploy, snap?.service(dd.serviceId)?.name ?: dd.title.orEmpty()), "helm://deploy?deploymentId=${dd.deploymentId}") {
                        act(null, Feel.Confirm) { Plat.act(dd.kind, dd.serviceId, "redeploy"); Repo.refresh() }
                    }
                }
                else -> NightFace(problems, down, quietUntil, summary.value?.night, updates.value)
            }
        }
        Indicator(pager.currentPage, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
        AnimatedVisibility(note != null, Modifier.align(Alignment.BottomCenter).padding(12.dp), enter = fadeIn(), exit = fadeOut()) {
            Txt(
                note.orEmpty(),
                Modifier.fillMaxWidth().background(H.c.ink, RoundedCornerShape(20.dp)).padding(14.dp),
                size = 14, weight = 500, lineHeight = 1.3f, color = H.c.bg,
            )
        }
    }
}

@Composable
private fun Indicator(page: Int, modifier: Modifier) {
    val names = listOf(R.string.pulse, R.string.shield, R.string.server, R.string.deploys, R.string.cover_night)
    val label = tr(R.string.cover_page, tr(names[page]), page + 1, FACES)
    Column(modifier.clearAndSetSemantics { contentDescription = label }, Arrangement.spacedBy(6.dp), Alignment.CenterHorizontally) {
        repeat(FACES) { i ->
            val on = i == page
            Box(Modifier.size(width = 6.dp, height = if (on) 18.dp else 6.dp).background(if (on) H.c.signal else H.c.faint.copy(alpha = 0.45f), CircleShape))
        }
    }
}

/** Same policy as rememberGuard, but a prompt the cover cannot show hands off to the main screen. */
@Composable
private fun rememberCoverGuard(onUnavailable: (String) -> Unit): (String, String, () -> Unit) -> Unit {
    val activity = LocalActivity.current as? FragmentActivity
    val ctx = LocalContext.current
    return remember(activity) {
        { title, uri, onOk ->
            val auth = if (android.os.Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK
            if (Store.config.value?.biometric != true || activity == null || BiometricManager.from(ctx).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
                onOk()
            } else {
                val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth)
                    .apply { if (android.os.Build.VERSION.SDK_INT < 30) setNegativeButtonText(tr(R.string.cancel)) }.build()
                BiometricPrompt(
                    activity, ContextCompat.getMainExecutor(ctx),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                        override fun onAuthenticationError(code: Int, msg: CharSequence) {
                            if (code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) onUnavailable(uri)
                        }
                    },
                ).authenticate(info)
            }
        }
    }
}
