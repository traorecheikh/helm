package com.orvlabs.helm.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orvlabs.helm.data.Overnight
import com.orvlabs.helm.data.Store
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class MorningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = Store.config.filterNotNull().first()
        if (c.onboarded && c.deviceId.isNotEmpty() && c.morningAt >= 0) {
            Notifier.morning(applicationContext, Overnight.build().text)
            com.orvlabs.helm.widget.Widgets.updateAll(applicationContext)
            schedule(applicationContext, c.morningAt, replace = true)
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, minutes: Int, replace: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (minutes < 0) return run { wm.cancelUniqueWork("morning"); Unit }
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            var at = LocalDate.now(zone).atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
            if (at <= now + 60_000) at += 86_400_000L
            wm.enqueueUniqueWork(
                "morning", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MorningWorker>().setInitialDelay(at - now, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
            )
        }
    }
}
