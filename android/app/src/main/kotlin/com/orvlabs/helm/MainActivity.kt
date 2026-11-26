package com.orvlabs.helm

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.service.MorningWorker
import com.orvlabs.helm.service.WatchService
import com.orvlabs.helm.ui.HelmRoot
import com.orvlabs.helm.widget.SummaryWorker

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            val cfg by Store.config.collectAsState()
            if (onCover(conf.smallestScreenWidthDp)) com.orvlabs.helm.cover.CoverRoot()
            else com.orvlabs.helm.ui.LockGate(this, cfg?.appLock != false && cfg?.onboarded == true) { HelmRoot() }
        }
    }

    private fun onCover(smallestDp: Int) =
        androidx.core.content.ContextCompat.getDisplayOrDefault(this).displayId != android.view.Display.DEFAULT_DISPLAY || smallestDp < 300

    private var foldWatch: android.hardware.display.DisplayManager.DisplayListener? = null

    override fun onNewIntent(intent: android.content.Intent) {
        setIntent(intent)
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.orvlabs.helm.ui.components.Haptics.attach(window.decorView, true)
        if (onCover(resources.configuration.smallestScreenWidthDp)) watchUnfold()
    }

    override fun onPause() {
        com.orvlabs.helm.ui.components.Haptics.resumed = false
        if (foldWatch != null) {
            val main = getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.state
            android.util.Log.i("HelmCover", "paused on cover, main display state=$main")
            if (main != null && main != android.view.Display.STATE_OFF) moveToMainScreen()
            else android.os.Handler(mainLooper).postDelayed({ stopWatchingUnfold() }, 4000)
        }
        super.onPause()
    }

    // Samsung does not carry cover apps to the main screen; reopen there while still in the foreground.
    private fun watchUnfold() {
        if (foldWatch != null) return
        val displays = getSystemService(android.hardware.display.DisplayManager::class.java) ?: return
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) {
                val state = displays.getDisplay(id)?.state
                android.util.Log.i("HelmCover", "display $id changed, state=$state")
                if (id == android.view.Display.DEFAULT_DISPLAY && state == android.view.Display.STATE_ON) moveToMainScreen()
            }
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
        }
        displays.registerDisplayListener(listener, android.os.Handler(mainLooper))
        foldWatch = listener
    }

    private fun stopWatchingUnfold() {
        val listener = foldWatch ?: return
        getSystemService(android.hardware.display.DisplayManager::class.java)?.unregisterDisplayListener(listener)
        foldWatch = null
    }

    private fun moveToMainScreen() {
        stopWatchingUnfold()
        val cover = com.orvlabs.helm.cover.CoverLink
        if (cover.pending == null) cover.pending = cover.forCurrentFace()
        val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
        val relaunch = android.content.Intent(this, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        runCatching { startActivity(relaunch, options.toBundle()) }
            .onSuccess { android.util.Log.i("HelmCover", "relaunched on main screen"); finishAndRemoveTask() }
            .onFailure { android.util.Log.w("HelmCover", "relaunch refused: $it") }
    }

    override fun onStop() {
        com.orvlabs.helm.ui.AppLock.wentToBackground()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        com.orvlabs.helm.ui.AppLock.cameBack()
        com.orvlabs.helm.service.Notifier.channels(this)
        val c = Store.config.value
        if (c != null && c.onboarded && c.deviceId.isNotEmpty()) {
            WatchService.start(this)
            SummaryWorker.schedule(this)
            MorningWorker.schedule(this, c.morningAt, replace = false)
        }
    }
}
