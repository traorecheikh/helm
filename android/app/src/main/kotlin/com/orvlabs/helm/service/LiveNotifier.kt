package com.orvlabs.helm.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.bundleOf
import com.orvlabs.helm.R
import com.orvlabs.helm.tr

class Tracked(
    val nid: Int, val name: String, val sub: String, val step: Int, val startedAt: Long,
    val deploymentId: String, val kind: String, val serviceId: String, val serverId: Int, val reconnecting: Boolean = false,
)

@SuppressLint("MissingPermission")
object LiveNotifier {
    const val LIVE = "live"
    const val LOCKDOWN_ID = 9003
    const val REBOOT_ID = 9004

    fun channels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(LIVE, tr(R.string.live_progress), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = tr(R.string.live_progress_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun ink(ctx: Context): Int =
        if (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) 0xFFB69FFE.toInt() else 0xFF5B32E6.toInt()

    private fun stepText(step: Int) = tr(listOf(R.string.deploy_step_pull, R.string.deploy_step_build, R.string.deploy_step_start, R.string.deploy_step_check)[step.coerceIn(0, 3)])

    fun id(serverId: Int, key: String) = "$serverId:$key".hashCode()

    private fun act(ctx: Context, action: String, t: Tracked): PendingIntent = PendingIntent.getActivity(
        ctx, t.nid + action.hashCode(),
        Intent(ctx, ActionActivity::class.java).putExtra("action", action).putExtra("notifId", t.nid).putExtra("serverId", t.serverId)
            .putExtra("kind", t.kind).putExtra("serviceId", t.serviceId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun deployLive(ctx: Context, t: Tracked): Notification {
        val ink = ink(ctx)
        val waiting = t.step < 0
        val progress = if (waiting) 0 else t.step * 25 + 12
        val segs = if (waiting) listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(ink)) else List(4) { NotificationCompat.ProgressStyle.Segment(25).setColor(ink) }
        val style = NotificationCompat.ProgressStyle().setStyledByProgress(true).setProgressIndeterminate(waiting).setProgressSegments(segs).setProgress(progress)
        val log = Notifier.open(ctx, "helm://deploy?deploymentId=${t.deploymentId}", t.nid)
        val public = NotificationCompat.Builder(ctx, LIVE).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.deploy_public_title)).setUsesChronometer(true).setWhen(t.startedAt).build()
        return NotificationCompat.Builder(ctx, LIVE).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(t.name).setSubText(t.sub)
            .setContentText(if (t.reconnecting) tr(R.string.deploy_reconnecting) else if (waiting) tr(R.string.waiting_for_dokploy_to_start_the) else stepText(t.step))
            .setStyle(style).setProgress(100, progress, waiting)
            .setOngoing(true).setOnlyAlertOnce(true).setRequestPromotedOngoing(true).setShortCriticalText(null)
            .setUsesChronometer(true).setWhen(t.startedAt).setShowWhen(true).setColor(ink)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(log)
            .addAction(0, tr(R.string.read_the_log), log)
            .addAction(NotificationCompat.Action.Builder(0, tr(R.string.stop_build), act(ctx, "stop_build", t)).setAuthenticationRequired(true).build())
            .addExtras(bundleOf("deploymentId" to t.deploymentId, "kind" to t.kind, "serviceId" to t.serviceId, "startedAt" to t.startedAt))
            .build()
    }

    fun deployDone(ctx: Context, t: Tracked, duration: String, finishedAt: Long) {
        NotificationManagerCompat.from(ctx).notify(
            t.nid,
            NotificationCompat.Builder(ctx, "activity").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.deploy_done_title, t.name)).setContentText(tr(R.string.deploy_done_text, duration)).setStyle(NotificationCompat.BigTextStyle())
                .setSubText(t.sub).setWhen(finishedAt).setAutoCancel(true).setTimeoutAfter(10 * 60_000L).setColor(ink(ctx))
                .setContentIntent(Notifier.open(ctx, "helm://deploy?deploymentId=${t.deploymentId}", t.nid)).build(),
        )
    }

    fun deployLost(ctx: Context, t: Tracked) {
        NotificationManagerCompat.from(ctx).notify(
            t.nid,
            NotificationCompat.Builder(ctx, "activity").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.deploy_lost_title)).setContentText(tr(R.string.deploy_lost_text)).setStyle(NotificationCompat.BigTextStyle()).setAutoCancel(true)
                .setContentIntent(Notifier.open(ctx, "helm://deploy?deploymentId=${t.deploymentId}", t.nid)).build(),
        )
    }

    fun lockdown(ctx: Context, until: Long?) {
        val b = NotificationCompat.Builder(ctx, LIVE).setSmallIcon(R.drawable.ic_lockdown)
            .setContentTitle(tr(R.string.lockdown_is_on))
            .setContentText(if (until != null) tr(R.string.panic_until, com.orvlabs.helm.ui.components.clock(until * 1000)) else tr(R.string.panic_until_end))
            .setStyle(NotificationCompat.BigTextStyle())
            .setOngoing(true).setOnlyAlertOnce(true).setRequestPromotedOngoing(true).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(ink(ctx)).setContentIntent(Notifier.open(ctx, "helm://shield", LOCKDOWN_ID))
            .addAction(
                NotificationCompat.Action.Builder(
                    0, tr(R.string.end_now),
                    PendingIntent.getActivity(
                        ctx, LOCKDOWN_ID, Intent(ctx, ActionActivity::class.java).putExtra("action", "panic_off").putExtra("notifId", LOCKDOWN_ID).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).setAuthenticationRequired(true).build(),
            )
        if (until != null) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(until * 1000)
            b.setTimeoutAfter((until * 1000 - System.currentTimeMillis()).coerceAtLeast(0) + 60_000)
        } else {
            b.setShowWhen(false).setShortCriticalText("")
        }
        NotificationManagerCompat.from(ctx).notify(LOCKDOWN_ID, b.build())
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    fun reboot(ctx: Context, atMs: Long, overdue: Boolean, quiet: Boolean) {
        val time = com.orvlabs.helm.ui.components.clock(atMs)
        val b = NotificationCompat.Builder(ctx, if (quiet) "overnight" else "activity").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (overdue) tr(R.string.reboot_overdue, time) else tr(R.string.reboot_title, time))
            .setContentText(if (overdue) tr(R.string.reboot_overdue_text) else tr(R.string.reboot_text))
            .setStyle(NotificationCompat.BigTextStyle())
            .setSilent(true).setColor(ink(ctx)).setContentIntent(Notifier.open(ctx, "helm://server", REBOOT_ID))
        if (overdue) b.setAutoCancel(true) else b.setOngoing(true).setUsesChronometer(true).setChronometerCountDown(true).setWhen(atMs).setTimeoutAfter(31 * 60_000L)
        NotificationManagerCompat.from(ctx).notify(REBOOT_ID, b.build())
    }
}
