package com.orvlabs.helm.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.net.toUri
import com.orvlabs.helm.MainActivity
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.Summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.orvlabs.helm.tr

class HelmTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        scope.launch {
            val cfg = Store.config.filterNotNull().first()
            if (!cfg.onboarded || cfg.deviceId.isEmpty()) return@launch show(null)
            show(Store.readSummary())
            Repo.refresh()
            Store.readSummary()?.let { show(it) }
        }
    }

    override fun onStopListening() {
        scope.coroutineContext.cancelChildren()
    }

    private fun show(s: Summary?) {
        val tile = qsTile ?: return
        tile.label = tr(R.string.helm_2)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.subtitle = when {
            s == null -> tr(R.string.open_helm)
            s.down > 0 -> tr(R.string.down_2, s.down)
            else -> tr(R.string.running_2, s.running)
        }
        tile.state = if (s != null && s.down == 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(Intent.ACTION_VIEW, "helm://pulse".toUri(), this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
