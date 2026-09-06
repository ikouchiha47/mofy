package com.mofy.app.watchtogether

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mofy.app.MainActivity
import com.mofy.app.watchtogether.sync.SyncEngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val NOTIF_CHANNEL = "mofy_watch_together"
private const val NOTIF_ID = 9200

/**
 * ADR 0011 items 2+6: keeps the process (and this device's live
 * WatchTogetherSession WebSockets/data channels) alive independent of
 * which screen - if any - is visible, and independent of the app being
 * backgrounded. Started/stopped by [WatchTogetherSessionManager] itself
 * on 0<->non-zero transitions of its session pool, not by any
 * Activity/Compose code.
 *
 * Also owns the host-side position heartbeat
 * ([WatchTogetherSession.heartbeatTick]) - that call existed already
 * (`SyncEngine.heartbeatTick`) but nothing scheduled it on an interval
 * anywhere in the app before this.
 */
class WatchTogetherForegroundService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification(sessionCountLabel(WatchTogetherSessionManager.sessions.value.size))

        scope.launch {
            WatchTogetherSessionManager.sessions.collectLatest { active ->
                if (active.isEmpty()) {
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                updateNotification(sessionCountLabel(active.size))
            }
        }

        scope.launch {
            while (true) {
                delay(SyncEngineConfig.POSITION_HEARTBEAT_MS)
                WatchTogetherSessionManager.sessions.value.forEach { it.session.heartbeatTick() }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private fun sessionCountLabel(count: Int): String = when (count) {
        0 -> "Watch Together"
        1 -> "Watching together"
        else -> "$count watch parties active"
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildNotification(title: String) =
        NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText("Tap to return")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .build()

    private fun startForegroundWithNotification(title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Watch Together", NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(title), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification(title))
        }
    }

    private fun updateNotification(title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(title))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
