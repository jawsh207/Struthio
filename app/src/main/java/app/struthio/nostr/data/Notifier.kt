package app.struthio.nostr.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.struthio.nostr.MainActivity
import app.struthio.nostr.R
import app.struthio.nostr.StruthioApp
import app.struthio.nostr.core.Nip19
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Scheduled checks through the platform's JobScheduler: no push service and no open connection. */
object Notifier {
    const val DEFAULT_MINUTES = 15
    val INTERVALS = listOf(15, 30, 60, 180, 0)

    private const val JOB_ID = 1
    private const val ACTIVITY = "activity"
    private const val MESSAGES = "messages"
    private const val MAX_SHOWN = 5
    private const val SUMMARY_ID = 100
    private const val MESSAGES_ID = 101

    fun schedule(context: Context, minutes: Int) {
        // Notifications must never get in the way of the account itself.
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (minutes <= 0) {
                scheduler.cancel(JOB_ID)
                return
            }
            val interval = minutes * 60_000L
            val pending = scheduler.getPendingJob(JOB_ID)
            if (pending?.intervalMillis == interval) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, CheckService::class.java))
                    .setPeriodic(interval)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        } catch (e: RuntimeException) {
            Log.w("Zino", "could not schedule checks", e)
        }
    }

    private fun channels(manager: NotificationManager) {
        manager.createNotificationChannel(NotificationChannel(ACTIVITY, "Activity", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(MESSAGES, "Private messages", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(context: Context, id: Int, target: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            target?.let { data = Uri.parse("nostr:" + Nip19.note(it)) }
        }
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun builder(context: Context, channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setAutoCancel(true)

    /** Message notifications carry no sender and no text: only the app shows those. */
    fun post(context: Context, alerts: List<Alert>, messages: Int, name: (String) -> String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        channels(manager)
        if (messages > 0) {
            val text = if (messages == 1) "New private message" else "$messages new private messages"
            manager.notify(
                MESSAGES_ID,
                builder(context, MESSAGES).setContentTitle(text).setContentIntent(open(context, MESSAGES_ID, null)).build(),
            )
        }
        if (alerts.size > MAX_SHOWN) {
            manager.notify(
                SUMMARY_ID,
                builder(context, ACTIVITY)
                    .setContentTitle("${alerts.size} new notifications")
                    .setContentText(alerts.first().headline(name(alerts.first().actor)))
                    .setContentIntent(open(context, SUMMARY_ID, null))
                    .build(),
            )
            return
        }
        for (alert in alerts) {
            val id = alert.event.id.hashCode() and 0x7fffffff or 0x1000
            manager.notify(
                id,
                builder(context, ACTIVITY)
                    .setContentTitle(alert.headline(name(alert.actor)))
                    .setContentText(alert.detail.take(200))
                    .setContentIntent(open(context, id, alert.target))
                    .build(),
            )
        }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
}

class CheckService : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val core = (application as ZinoApp).core
        work = core.scope.launch {
            try {
                withTimeout(CHECK_LIMIT_MS) { core.backgroundCheck() }
            } catch (_: Exception) {
                // A missed check is simply picked up by the next one, which starts from the same point.
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private companion object {
        const val CHECK_LIMIT_MS = 40_000L
    }
}
