package com.orvlabs.helm.ui.components

import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.StaleProbe
import com.orvlabs.helm.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.orvlabs.helm.R
import com.orvlabs.helm.tr

class Load<T>(val value: T?, val error: String?, val loading: Boolean, val reload: () -> Unit, val stale: Boolean = false)

private val loadCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
private val loadedAt = java.util.concurrent.ConcurrentHashMap<Any, Long>()
private const val FRESH_MS = 10_000L
val firstLoads = androidx.compose.runtime.mutableIntStateOf(0)

fun clearLoadCache() { loadCache.clear(); loadedAt.clear() }

@Composable
fun <T> rememberLoad(vararg keys: Any?, fetch: suspend () -> T): Load<T> {
    val cacheKey = "${fetch.javaClass.name}:${currentCompositeKeyHash}:${keys.joinToString("|")}"
    @Suppress("UNCHECKED_CAST")
    var value by remember(*keys) { mutableStateOf(loadCache[cacheKey] as T?) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var loading by remember(*keys) { mutableStateOf(value == null) }
    var tick by remember(*keys) { mutableIntStateOf(0) }
    var busy by remember(*keys) { mutableStateOf(false) }
    var stale by remember(*keys) { mutableStateOf(false) }
    LaunchedEffect(*keys, tick) {
        if (tick == 0 && value != null && SystemClock.elapsedRealtime() - (loadedAt[cacheKey] ?: 0L) < FRESH_MS) return@LaunchedEffect
        busy = true
        if (value == null) { loading = true; error = null }
        val counted = value == null
        if (counted) firstLoads.intValue++
        try {
            var retried = false
            val probe = StaleProbe()
            var fresh: T
            while (true) {
                try { fresh = withContext(probe) { fetch() }; break } catch (e: ApiError) {
                    if (value != null || retried || e.kind == ApiError.Kind.Auth || e.kind == ApiError.Kind.Revoked || e.kind == ApiError.Kind.Offline) throw e
                    retried = true
                    delay(600)
                }
            }
            value = fresh
            stale = probe.stale
            if (fresh != null) { loadCache[cacheKey] = fresh; loadedAt[cacheKey] = SystemClock.elapsedRealtime() }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (value == null) error = e.message else stale = true
        } finally {
            busy = false
            loading = false
            if (counted) firstLoads.intValue--
        }
    }
    // Polling must not cancel a slow request in flight, or the first answer never lands.
    return Load(value, error, loading, { if (!busy) tick++ }, stale)
}

@Composable
fun EveryWhileVisible(periodMs: Long, vararg keys: Any?, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val site = currentCompositeKeyHash
    LaunchedEffect(*keys) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val wait = periodMs - (SystemClock.elapsedRealtime() - (loadedAt[site] ?: 0L))
                if (wait > 0) delay(wait)
                loadedAt[site] = SystemClock.elapsedRealtime()
                block()
                delay(periodMs)
            }
        }
    }
}

@Composable
fun rememberGuard(): (String, () -> Unit) -> Unit {
    val activity = LocalActivity.current as? FragmentActivity
    val ctx = LocalContext.current
    return remember(activity) {
        { title, onOk ->
            val enabled = Store.config.value?.let { it.biometric && !it.demo } == true
            val auth = if (android.os.Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK
            if (!enabled || activity == null || BiometricManager.from(ctx).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
                onOk()
            } else {
                val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth)
                    .apply { if (android.os.Build.VERSION.SDK_INT < 30) setNegativeButtonText(tr(R.string.cancel)) }
                    .build()
                BiometricPrompt(
                    activity, ContextCompat.getMainExecutor(ctx),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                    },
                ).authenticate(info)
            }
        }
    }
}
