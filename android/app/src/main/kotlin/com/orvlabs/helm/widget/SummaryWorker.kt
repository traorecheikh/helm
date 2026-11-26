package com.orvlabs.helm.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orvlabs.helm.data.Repo
import com.orvlabs.helm.data.Store
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class SummaryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = Store.config.filterNotNull().first()
        if (c.onboarded && c.deviceId.isNotEmpty()) {
            Repo.refresh(full = true)
            Widgets.updateAll(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun hasWidget(ctx: Context) = listOf(HelmWidgetReceiver::class.java, MapReceiver::class.java, ServerReceiver::class.java, UptimeReceiver::class.java, DeployReceiver::class.java, NightReceiver::class.java)
            .any { AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty() }

        fun schedule(ctx: Context) {
            if (!hasWidget(ctx)) return
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "widget", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SummaryWorker>(30, TimeUnit.MINUTES).setConstraints(online).build(),
            )
        }

        fun refreshNow(ctx: Context) {
            if (!hasWidget(ctx)) return
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "widget-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SummaryWorker>().setInitialDelay(5, TimeUnit.SECONDS).setConstraints(online).build(),
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork("widget")
        }
    }
}
