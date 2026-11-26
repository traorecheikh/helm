package com.orvlabs.helm.service

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.orvlabs.helm.data.Summary

object WearPush {
    private lateinit var app: Context
    private var lastRunning = -1
    private var lastDown = -1
    private var lastAt = 0L

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun push(s: Summary) {
        val now = System.currentTimeMillis()
        if (s.running == lastRunning && s.down == lastDown && now - lastAt < 10 * 60_000) return
        lastRunning = s.running
        lastDown = s.down
        lastAt = now
        val req = PutDataMapRequest.create("/summary").apply {
            dataMap.putInt("running", s.running)
            dataMap.putInt("down", s.down)
            dataMap.putLong("at", now)
        }.asPutDataRequest().setUrgent()
        runCatching { Wearable.getDataClient(app).putDataItem(req) }
    }
}
