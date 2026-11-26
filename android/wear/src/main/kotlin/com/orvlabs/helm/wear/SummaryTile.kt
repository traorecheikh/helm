package com.orvlabs.helm.wear

import androidx.wear.protolayout.ColorBuilders.argb
import com.orvlabs.helm.wear.R
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class SummaryTile : TileService() {
    private fun text(s: String, size: Float, color: Int, bold: Boolean = true) =
        Text.Builder().setText(s).setFontStyle(
            FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build(),
        ).build()

    private fun pill(s: String, ink: Int, soft: Int): LayoutElement = Box.Builder().setModifiers(
        Modifiers.Builder()
            .setBackground(Background.Builder().setColor(argb(soft)).setCorner(Corner.Builder().setRadius(dp(50f)).build()).build())
            .setPadding(Padding.Builder().setStart(dp(14f)).setEnd(dp(14f)).setTop(dp(5f)).setBottom(dp(5f)).build())
            .build(),
    ).addContent(text(s, 16f, ink)).build()

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences("helm_wear", MODE_PRIVATE)
        val running = prefs.getInt("running", -1)
        val down = prefs.getInt("down", 0)
        val column = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        if (running < 0) {
            column.addContent(text(getString(R.string.open_helm_on_phone), 14f, MUTED, bold = false))
        } else if (down > 0) {
            column.addContent(text("$running", 56f, BAD))
            column.addContent(pill(getString(R.string.down_count, down), BAD, BAD_SOFT))
        } else {
            column.addContent(text("$running", 56f, OK))
            column.addContent(pill(getString(R.string.all_running), OK, OK_SOFT))
        }
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setFreshnessIntervalMillis(15 * 60 * 1000L)
            .setTileTimeline(Timeline.fromLayoutElement(column.build()))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())

    private companion object {
        const val OK = 0xFF56D298.toInt()
        const val OK_SOFT = 0xFF0B2E1E.toInt()
        const val BAD = 0xFFF97770.toInt()
        const val BAD_SOFT = 0xFF3F191A.toInt()
        const val MUTED = 0xFFB5B2C0.toInt()
    }
}
