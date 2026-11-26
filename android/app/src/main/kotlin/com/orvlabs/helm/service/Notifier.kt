package com.orvlabs.helm.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.orvlabs.helm.MainActivity
import com.orvlabs.helm.R
import com.orvlabs.helm.data.Event
import com.orvlabs.helm.data.Store
import com.orvlabs.helm.data.isFailure
import com.orvlabs.helm.data.isPairRequest
import com.orvlabs.helm.data.quietNow
import com.orvlabs.helm.tr
import com.orvlabs.helm.data.titleText
import com.orvlabs.helm.data.messageText
import com.orvlabs.helm.data.reasonText

@SuppressLint("MissingPermission")
object Notifier {
    private const val WATCH = "watch"
    private const val FAILURES = "failures"
    private const val ACTIVITY = "activity"
    private const val SHIELD = "shield"
    private const val OVERNIGHT = "overnight"
    private const val MORNING = "morning"
    private const val OVERNIGHT_ID = 9001
    val LOUD = setOf("service_down", "container_died", "oom", "server_unreachable", "docker_restarted", "reboot_ineffective", "new_login_place", "pair_request", "panic_on", "secret_leak", "vuln_critical", "autofix_failed")
    private const val SUMMARY_ID = 9000

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        LiveNotifier.channels(nm)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(WATCH, tr(R.string.watching), NotificationManager.IMPORTANCE_MIN).apply {
                    description = tr(R.string.shown_while_helm_listens_for_alerts)
                    setShowBadge(false)
                },
                NotificationChannel(FAILURES, tr(R.string.failures), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = tr(R.string.failed_builds_failed_backups_memory_and)
                },
                NotificationChannel(ACTIVITY, tr(R.string.activity), NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr(R.string.deploys_backups_and_cleanups)
                },
                NotificationChannel(SHIELD, tr(R.string.shield), NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr(R.string.new_ip_bans)
                },
                NotificationChannel(OVERNIGHT, tr(R.string.overnight), NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr(R.string.everything_that_happened_during_quiet_hours)
                },
                NotificationChannel(MORNING, tr(R.string.morning_report), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = tr(R.string.one_summary_each_morning)
                },
            ),
        )
    }

    internal fun open(ctx: Context, uri: String, code: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri(), ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun confirm(ctx: Context, action: String, e: Event, code: Int, serverId: Int): PendingIntent {
        val intent = Intent(ctx, ActionActivity::class.java)
            .putExtra("action", action).putExtra("notifId", code).putExtra("serverId", serverId).putExtra("ip", e.ip)
            .putExtra("text", "${e.title.orEmpty()} ${e.message.orEmpty()} ${e.titleFr.orEmpty()} ${e.messageFr.orEmpty()}").putExtra("container", e.container)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, code + action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun NotificationCompat.Builder.confirmAction(ctx: Context, label: String, action: String, e: Event, code: Int, serverId: Int) = addAction(
        NotificationCompat.Action.Builder(0, label, confirm(ctx, action, e, code, serverId)).setAuthenticationRequired(true).build(),
    )

    fun result(ctx: Context, notifId: Int, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            notifId,
            NotificationCompat.Builder(ctx, ACTIVITY)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(tr(R.string.helm_2)).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setTimeoutAfter(20_000)
                .setContentIntent(open(ctx, "helm://pulse", notifId)).build(),
        )
    }

    fun watching(ctx: Context, text: String? = null): Notification {
        val host = Store.config.value?.host.orEmpty()
        return NotificationCompat.Builder(ctx, WATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text ?: tr(R.string.helm_is_watching, host))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open(ctx, "helm://pulse", 1))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun post(ctx: Context, e: Event, serverId: Int = 0, serverName: String? = null) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val code = "$serverId:${e.id}".hashCode()
        val cfg = Store.config.value
        val silent = cfg != null && cfg.quietOn && quietNow(cfg.quietStart, cfg.quietEnd) && e.kind !in LOUD
        val isBan = e.type == "ban"
        val isThreat = e.kind == "threat" || e.kind == "threat_digest"
        val channel = when {
            silent -> OVERNIGHT
            isBan || isThreat -> SHIELD
            e.isFailure() -> FAILURES
            else -> ACTIVITY
        }
        val body = (if (isBan) e.reasonText() else e.messageText()).orEmpty()
        val detail = listOfNotNull(body.ifBlank { null }, e.container, e.ip.takeIf { !isBan }).joinToString("\n")
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (isBan) tr(R.string.ip_banned, e.ip) else e.titleText().orEmpty())
            .setContentText(body)
            .setSubText(serverName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setWhen(e.at * 1000)
            .setAutoCancel(true)
            .apply { if (e.isFailure()) setColor(0xFFBE2323.toInt()) }
        when {
            isBan -> {
                b.setGroup("bans").setContentIntent(open(ctx, "helm://bans", code))
                if (e.ip != null) b.confirmAction(ctx, tr(R.string.unban_3), "unban", e, code, serverId)
                    .addAction(NotificationCompat.Action.Builder(0, tr(R.string.sh_ban_perm), open(ctx, "helm://ip/${e.ip}", code + 1)).build())
                val lines = nm.activeNotifications.filter { it.notification.group == "bans" && it.id != SUMMARY_ID }
                    .mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }
                val inbox = NotificationCompat.InboxStyle().setSummaryText(tr(R.string.new_ips_banned))
                (lines + tr(R.string.ip_banned, e.ip)).distinct().takeLast(6).forEach(inbox::addLine)
                nm.notify(
                    SUMMARY_ID,
                    NotificationCompat.Builder(ctx, SHIELD)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(tr(R.string.shield))
                        .setContentText(tr(R.string.new_ips_banned))
                        .setStyle(inbox)
                        .setGroup("bans")
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .setContentIntent(open(ctx, "helm://bans", SUMMARY_ID))
                        .build(),
                )
            }
            e.isPairRequest() -> b.setContentIntent(open(ctx, "helm://devices", code))
            isThreat -> {
                b.setContentIntent(open(ctx, "helm://live", code))
                if (e.ip != null) b.confirmAction(ctx, tr(R.string.sh_ban_24h), "ban24", e, code, serverId)
                    .addAction(NotificationCompat.Action.Builder(0, tr(R.string.sh_ban_perm), open(ctx, "helm://ip/${e.ip}", code + 1)).build())
            }
            e.kind == "build_error" -> {
                val log = open(ctx, "helm://deploy?failed=true", code)
                b.setContentIntent(log)
                    .addAction(NotificationCompat.Action.Builder(0, tr(R.string.read_the_log), log).build())
                    .confirmAction(ctx, tr(R.string.redeploy_2), "redeploy", e, code, serverId)
            }
            else -> {
                b.setContentIntent(open(ctx, "helm://alerts", code))
                if (e.kind in setOf("container_died", "oom", "unhealthy", "service_down")) b.confirmAction(ctx, tr(R.string.restart), "restart", e, code, serverId)
            }
        }
        if (com.orvlabs.helm.ui.components.Haptics.resumed) b.setSilent(true)
        if (silent) {
            b.setSilent(true).setGroup("overnight")
            nm.notify(
                OVERNIGHT_ID,
                NotificationCompat.Builder(ctx, OVERNIGHT).setSmallIcon(R.drawable.ic_notification).setContentTitle(tr(R.string.overnight))
                    .setContentText(tr(R.string.quiet_hours_events_are_collected_here)).setGroup("overnight").setGroupSummary(true).setSilent(true)
                    .setContentIntent(open(ctx, "helm://overnight", OVERNIGHT_ID)).build(),
            )
        }
        nm.notify(code, b.build())
    }

    fun lostAccess(ctx: Context) {
        NotificationManagerCompat.from(ctx).notify(
            9005,
            NotificationCompat.Builder(ctx, FAILURES).setSmallIcon(R.drawable.ic_notification).setContentTitle(tr(R.string.helm_lost_access))
                .setContentText(tr(R.string.helm_lost_access_to_the_server)).setStyle(NotificationCompat.BigTextStyle()).setAutoCancel(true).setContentIntent(open(ctx, "helm://pulse", 9005)).build(),
        )
    }

    fun morning(ctx: Context, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        nm.notify(
            9002,
            NotificationCompat.Builder(ctx, MORNING).setSmallIcon(R.drawable.ic_notification).setContentTitle(tr(R.string.good_morning))
                .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                .setContentIntent(open(ctx, "helm://overnight", 9002)).build(),
        )
    }
}
