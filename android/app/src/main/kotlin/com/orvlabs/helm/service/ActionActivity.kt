package com.orvlabs.helm.service

import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.orvlabs.helm.data.Agent
import com.orvlabs.helm.data.ApiError
import com.orvlabs.helm.data.Dokploy
import com.orvlabs.helm.data.Plat
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.parseTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.orvlabs.helm.R
import com.orvlabs.helm.tr

class ActionActivity : FragmentActivity() {
    private val notifId get() = intent.getIntExtra("notifId", 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra("action")
        val auth = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_STRONG
        if (action == null || BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            Notifier.result(this, notifId, tr(R.string.nothing_was_done_set_up_a))
            finish()
            return
        }
        val title = when (action) { "redeploy" -> tr(R.string.redeploy_the_failed_app); "restart" -> tr(R.string.restart_the_container); "panic_off" -> tr(R.string.end_lockdown); "stop_build" -> tr(R.string.stop_build_prompt); "ban24" -> tr(R.string.sh_ban_24h); else -> tr(R.string.unban_this_ip) }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth)
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText(tr(R.string.cancel)) }.build()
        BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lifecycleScope.launch {
                        val msg = perform(action)
                        com.orvlabs.helm.ui.components.Haptics.attach(window.decorView, true)
                        com.orvlabs.helm.ui.components.Haptics.fire(if (msg.startsWith(tr(R.string.it_did_not_work, "").trim())) com.orvlabs.helm.ui.components.Feel.Reject else com.orvlabs.helm.ui.components.Feel.Confirm)
                        Notifier.result(this@ActionActivity, notifId, msg)
                        finish()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = finish()
            },
        ).authenticate(info)
    }

    private suspend fun perform(action: String): String {
        Store.config.filterNotNull().first()
        val sid = intent.getIntExtra("serverId", -1)
        if (sid >= 0 && sid != Store.config.value?.activeId) {
            Store.setActive(sid)
            Store.config.first { it?.activeId == sid }
        }
        return try {
            when (action) {
                "panic_off" -> {
                    Agent.setPanic(0)
                    LiveNotifier.cancel(this, LiveNotifier.LOCKDOWN_ID)
                    tr(R.string.lockdown_ended)
                }
                "stop_build" -> {
                    Plat.cancelDeploy(intent.getStringExtra("kind").orEmpty(), intent.getStringExtra("serviceId").orEmpty())
                    LiveNotifier.cancel(this, notifId)
                    tr(R.string.done_2)
                }
                "redeploy" -> {
                    val d = Plat.deployments().filter { it.status == "error" }.maxByOrNull { parseTime(it.createdAt) ?: 0 }
                        ?: return tr(R.string.no_failed_deploy_was_found_so)
                    Plat.act(d.kind, d.serviceId, "redeploy")
                    tr(R.string.done_the_redeploy_has_started)
                }
                "restart" -> {
                    val text = intent.getStringExtra("text").orEmpty().lowercase()
                    val hint = intent.getStringExtra("container")
                    val c = Dokploy.containers().filter { ct ->
                        val base = ct.name.substringBefore('.').lowercase()
                        ct.name == hint || base.length > 3 && text.contains(base)
                    }.maxByOrNull { it.name.length }
                        ?: return tr(R.string.nothing_was_restarted_helm_could_not)
                    Dokploy.restartContainer(c.containerId)
                    tr(R.string.done_is_restarting, c.name.substringBefore('.'))
                }
                "ban24" -> {
                    val ip = intent.getStringExtra("ip") ?: return tr(R.string.nothing_was_done_the_alert_had)
                    Agent.ban(ip)
                    tr(R.string.is_banned, ip)
                }
                else -> {
                    val ip = intent.getStringExtra("ip") ?: return tr(R.string.nothing_was_done_the_alert_had)
                    Agent.unban(ip)
                    tr(R.string.done_is_unbanned, ip)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            tr(R.string.it_did_not_work, e.message)
        }
    }
}
