package com.orvlabs.helm.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Outbox {
    private const val TAG = "outbox"
    private const val MAX_AGE_MS = 15 * 60_000L
    private val TARGETS = listOf("ip", "containerId", "applicationId", "composeId")
    private val SAFE = Regex("""dokploy/(application|compose)\.(deploy|redeploy|start|stop)|dokploy/docker\.restartContainer|shield/(ban|unban|allow|panic)|coolify/deploy\?uuid=[\w-]+|coolify/(applications|services|databases)/[\w-]+/(start|stop|restart)""")
    private val COOLIFY_TARGET = Regex("""coolify/(?:deploy\?uuid=|\w+/)([\w-]+)""")

    private val pendingCount = MutableStateFlow(0)
    val pending: StateFlow<Int> = pendingCount
    private lateinit var work: WorkManager

    fun init(ctx: Context) {
        work = WorkManager.getInstance(ctx)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            work.getWorkInfosByTagFlow(TAG).collect { list -> pendingCount.value = list.count { !it.state.isFinished } }
        }
    }

    fun eligible(method: String, path: String) = method == "POST" && SAFE.matches(path)

    // The latest intent per target replaces the older one (ban then unban of one IP, stop then start of one service).
    private fun target(path: String, body: String?) =
        runCatching { body?.let { b -> TARGETS.firstNotNullOfOrNull { AppJson.parseToJsonElement(b).jsonObject[it]?.jsonPrimitive?.content } } }.getOrNull()
            ?: COOLIFY_TARGET.find(path)?.groupValues?.get(1) ?: path

    fun enqueue(base: String, path: String, body: String?) {
        val input = Data.Builder().putString("base", base).putString("path", path).putString("body", body).putLong("at", System.currentTimeMillis()).build()
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork("$TAG:${target(path, body)}", ExistingWorkPolicy.REPLACE, request)
    }

    class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            if (System.currentTimeMillis() - inputData.getLong("at", 0) > MAX_AGE_MS) return Result.failure()
            return try {
                Net.call("POST", inputData.getString("path").orEmpty(), emptyList(), inputData.getString("body"), inputData.getString("base").orEmpty(), queue = false)
                Result.success()
            } catch (e: ApiError) {
                if (e.transient) Result.retry() else Result.failure()
            }
        }
    }
}
