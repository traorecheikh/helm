package com.orvlabs.helm.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.orvlabs.helm.R
import com.orvlabs.helm.ui.components.Btn
import com.orvlabs.helm.ui.components.BtnStyle
import com.orvlabs.helm.ui.components.DotNum
import com.orvlabs.helm.ui.components.Ico
import com.orvlabs.helm.ui.components.Ph
import com.orvlabs.helm.ui.components.Txt
import com.orvlabs.helm.ui.theme.H
import com.orvlabs.helm.tr

object AppLock {
    private const val GRACE_MS = 60_000L
    val locked = mutableStateOf(true)
    private var backgroundAt = 0L

    fun wentToBackground() { backgroundAt = System.currentTimeMillis() }

    fun cameBack() {
        if (backgroundAt != 0L && System.currentTimeMillis() - backgroundAt > GRACE_MS) locked.value = true
    }

    fun canLock(activity: FragmentActivity) =
        BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(tr(R.string.lock_prompt_title))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked.value = false }
        }).authenticate(info)
    }
}

@Composable
fun LockGate(activity: FragmentActivity, enabled: Boolean, content: @Composable () -> Unit) {
    val testBypass = com.orvlabs.helm.BuildConfig.DEBUG &&
        android.provider.Settings.Global.getString(activity.contentResolver, "helm_nolock") == "1"
    if (!enabled || testBypass || !AppLock.locked.value || !AppLock.canLock(activity)) {
        content()
        return
    }
    LaunchedEffect(Unit) { AppLock.prompt(activity) }
    val c = H.c
    Column(
        Modifier.fillMaxSize().background(c.bg).padding(32.dp),
        Arrangement.Center, Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(140.dp).clip(CircleShape).background(c.signalSoft), Alignment.Center) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(c.signal), Alignment.Center) { Ico(Ph.Fingerprint, size = 46, tint = c.signalInk) }
        }
        DotNum("HELM", 56, Modifier.padding(top = 32.dp))
        Txt(tr(R.string.lock_title), Modifier.padding(top = 8.dp), size = 16, weight = 600, color = c.muted)
        Btn(tr(R.string.lock_unlock), { AppLock.prompt(activity) }, Modifier.padding(top = 32.dp), BtnStyle.Signal)
    }
}
