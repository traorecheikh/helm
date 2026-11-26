package com.orvlabs.helm.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NavBackStackEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.ui.components.Dock
import com.orvlabs.helm.ui.screens.*
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.ui.theme.HelmTheme
import com.orvlabs.helm.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable object HomeR
@Serializable object AppsR
@Serializable data class ShieldR(val ip: String = "")
@Serializable object ServerR
@Serializable object SettingsR
@Serializable object SearchR
@Serializable data class ServiceR(val kind: String, val id: String)
@Serializable data class DeployR(
    val deploymentId: String = "",
    val kind: String = "",
    val id: String = "",
    val after: Long = 0,
    val failed: Boolean = false,
)

private fun routeFor(uri: android.net.Uri): Any? = when (uri.host) {
    "live", "pulse", "alerts", "overnight" -> HomeR
    "shield", "bans", "map" -> ShieldR()
    "ip" -> ShieldR(uri.getQueryParameter("ip").orEmpty())
    "server", "uptime" -> ServerR
    "devices" -> SettingsR
    "deploy" -> DeployR(
        uri.getQueryParameter("deploymentId").orEmpty(),
        uri.getQueryParameter("kind").orEmpty(),
        uri.getQueryParameter("id").orEmpty(),
        uri.getQueryParameter("after")?.toLongOrNull() ?: 0,
        uri.getQueryParameter("failed") == "true",
    )
    else -> null
}

@Composable
fun HelmRoot() {
    val cfg by Store.config.collectAsStateWithLifecycle()
    val c = cfg ?: return Box(Modifier.fillMaxSize().background(Color(0xFF000000)))
    HelmTheme(c.theme) {
        val dark = H.c.dark
        val activity = LocalActivity.current as ComponentActivity
        SideEffect {
            val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            activity.enableEdgeToEdge(style, style)
        }
        val conf = androidx.compose.ui.platform.LocalConfiguration.current
        val compact = conf.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE || conf.screenHeightDp < 480
        androidx.compose.runtime.CompositionLocalProvider(com.orvlabs.helm.ui.components.LocalCompact provides compact) {
            if (c.onboarded && c.deviceId.isNotEmpty()) MainNav() else SetupFlow()
            CrashSheet()
        }
    }
}

fun feelEvent(e: com.orvlabs.helm.data.Event) {
    val key = e.container ?: "svc"
    when (e.kind) {
        "build_error", "service_down", "container_died", "oom" -> com.orvlabs.helm.ui.components.Haptics.fire(com.orvlabs.helm.ui.components.Feel.Failure, key = key)
        "unhealthy", "disk_high", "cert_expiring", "panic_on" -> com.orvlabs.helm.ui.components.Haptics.fire(com.orvlabs.helm.ui.components.Feel.Attention, key = e.kind)
        "service_up", "panic_off" -> com.orvlabs.helm.ui.components.Haptics.fire(com.orvlabs.helm.ui.components.Feel.Recovery, key = if (e.kind == "panic_off") "panic" else key)
    }
}

fun NavController.goTab(route: Any) = navigate(route) {
    popUpTo(HomeR) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private var linked: Intent? = null

private fun NavController.openLink(uri: android.net.Uri?) {
    val r = uri?.let(::routeFor) ?: return
    navigate(r) { popUpTo(HomeR); launchSingleTop = true }
}

@Composable
private fun MainNav() {
    val nav = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    val reduce = LocalReduceMotion.current
    DisposableEffect(nav) {
        val l = Consumer<Intent> { i -> linked = i; nav.openLink(i.data) }
        activity.addOnNewIntentListener(l)
        onDispose { activity.removeOnNewIntentListener(l) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val i = activity.intent
        if (i !== linked && i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) { linked = i; nav.openLink(i.data) }
        com.orvlabs.helm.cover.CoverLink.pending?.let {
            com.orvlabs.helm.cover.CoverLink.pending = null
            nav.openLink(it.toUri())
        }
        com.orvlabs.helm.data.Live.events.collect(::feelEvent)
    }
    val dest = nav.currentBackStackEntryAsState().value?.destination
    val apps by androidx.compose.runtime.remember { com.orvlabs.helm.data.Repo.state.map { it.platform != "none" } }.collectAsStateWithLifecycle(true)
    fun on(vararg r: kotlin.reflect.KClass<*>) = r.any { dest?.hasRoute(it) == true }
    val tabIndex = when {
        on(HomeR::class) -> 0
        on(AppsR::class, ServiceR::class) -> 1
        on(ShieldR::class) -> 2
        else -> 3
    }
    val dockVisible = dest != null && !on(SearchR::class, DeployR::class, ServiceR::class, SettingsR::class)
    val slide = with(LocalDensity.current) { 12.dp.roundToPx() }
    val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun NavBackStackEntry.isTab() = listOf(HomeR::class, AppsR::class, ShieldR::class, ServerR::class).any { destination.hasRoute(it) }
    fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSwitch() = initialState.isTab() && targetState.isTab()

    Box(Modifier.fillMaxSize()) {
        NavHost(
            nav, HomeR,
            enterTransition = {
                if (reduce) EnterTransition.None
                else if (tabSwitch()) fadeIn(tween(160))
                else fadeIn(tween(220, easing = ease)) + slideInHorizontally(tween(220, easing = ease)) { slide }
            },
            exitTransition = { if (reduce) ExitTransition.None else fadeOut(tween(if (tabSwitch()) 120 else 160)) },
            popEnterTransition = {
                if (reduce) EnterTransition.None
                else fadeIn(tween(220, easing = ease)) + slideInHorizontally(tween(220, easing = ease)) { if (tabSwitch()) 0 else -slide }
            },
            popExitTransition = {
                if (reduce) ExitTransition.None
                else fadeOut(tween(160)) + slideOutHorizontally(tween(220, easing = ease)) { if (tabSwitch()) 0 else slide }
            },
        ) { graph(nav) }
        if (dockVisible) {
            val bg = H.c.bg
            val compact = com.orvlabs.helm.ui.components.LocalCompact.current
            Box(Modifier.align(if (compact) Alignment.BottomEnd else Alignment.BottomCenter).then(if (compact) Modifier.widthIn(max = 420.dp) else Modifier.fillMaxWidth())) {
                Box(Modifier.fillMaxWidth().height(if (compact) 0.dp else 72.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, bg.copy(alpha = 0.7f)))))
                Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = if (compact) 8.dp else 20.dp)) {
                    Dock(
                        tabIndex, emptySet(),
                        { nav.goTab(listOf(HomeR, AppsR, ShieldR(), ServerR)[it]) },
                        { nav.navigate(SearchR) { launchSingleTop = true } },
                        apps,
                    )
                }
            }
        }
    }
}

private fun NavGraphBuilder.graph(nav: NavController) {
    composable<HomeR> { HomeScreen(nav) }
    composable<AppsR> { AppsScreen(nav) }
    composable<ShieldR> { ShieldScreen(nav, it.toRoute<ShieldR>().ip) }
    composable<ServerR> { ServerScreen(nav) }
    composable<SettingsR> { SettingsScreen(nav) }
    composable<SearchR> { SearchScreen(nav) }
    composable<ServiceR> { it.toRoute<ServiceR>().let { r -> ServiceScreen(nav, r.kind, r.id) } }
    composable<DeployR> { DeployScreen(nav, it.toRoute<DeployR>()) }
}
