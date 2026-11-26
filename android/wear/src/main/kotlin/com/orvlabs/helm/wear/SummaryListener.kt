package com.orvlabs.helm.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class SummaryListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED || e.dataItem.uri.path != "/summary") continue
            val map = DataMapItem.fromDataItem(e.dataItem).dataMap
            getSharedPreferences("helm_wear", MODE_PRIVATE).edit()
                .putInt("running", map.getInt("running"))
                .putInt("down", map.getInt("down"))
                .putLong("at", map.getLong("at"))
                .apply()
            TileService.getUpdater(this).requestUpdate(SummaryTile::class.java)
        }
    }
}
