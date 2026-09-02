package com.mofy.app.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mofy.app.data.library.AppDatabase
import com.mofy.app.data.models.ModelDownloadState
import com.mofy.app.data.models.ModelDownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloadService"
private const val NOTIF_CHANNEL = "mofy_model_download"

/**
 * ADR 0010 tasks 3+4: plain foreground Service (mirrors LibreTorrent's
 * pattern - verified against real projects in ADR 0010's Context, not
 * WorkManager) that survives screen-lock/backgrounding for large model
 * downloads. Moves HttpModelDownloader.downloadWithProgress's byte-copy
 * loop here, adding: startForeground() immediately, a partial WakeLock
 * held only for the transfer's duration, per-tick progress written to
 * ModelDownloadDao (not just the notification), and HTTP Range-based
 * resume if a prior .tmp file exists.
 */
class ModelDownloadService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val EXTRA_MODEL_KEY = "model_key"
        const val EXTRA_URL = "url"
        const val EXTRA_DEST_PATH = "dest_path"
        const val EXTRA_TITLE = "title"
        private const val NOTIF_ID_BASE = 9100
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelKey = intent?.getStringExtra(EXTRA_MODEL_KEY)
        val url = intent?.getStringExtra(EXTRA_URL)
        val destPath = intent?.getStringExtra(EXTRA_DEST_PATH)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Mofy download"
        if (modelKey == null || url == null || destPath == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notifId = NOTIF_ID_BASE + modelKey.hashCode().mod(1000)
        startForegroundWithNotification(notifId, title)
        acquireWakeLock()

        scope.launch {
            val dao = AppDatabase.get(applicationContext).modelDownloadDao()
            try {
                runDownload(modelKey, url, File(destPath), title, notifId, dao)
            } finally {
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        releaseWakeLock()
    }

    private suspend fun runDownload(
        modelKey: String,
        url: String,
        dest: File,
        title: String,
        notifId: Int,
        dao: com.mofy.app.data.models.ModelDownloadDao,
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
            .setContentText("Starting download…")

        val tmp = File(dest.parent, "${dest.name}.tmp")
        val resumeFrom = if (tmp.exists()) tmp.length() else 0L

        dao.upsert(
            ModelDownloadState(
                modelKey = modelKey,
                status = ModelDownloadStatus.DOWNLOADING.name,
                url = url,
                bytesDownloaded = resumeFrom,
                bytesTotal = 0L,
                destPath = dest.absolutePath,
                lastErrorMessage = null,
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )

        try {
            val conn = URL(resolveRedirect(url)).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 300_000
            conn.instanceFollowRedirects = true
            if (resumeFrom > 0L) conn.setRequestProperty("Range", "bytes=$resumeFrom-")

            val supportsResume = resumeFrom > 0L && run {
                conn.connect()
                conn.responseCode == HttpURLConnection.HTTP_PARTIAL
            }
            // Server didn't honor Range (no 206) - start over from zero.
            val startOffset = if (supportsResume) resumeFrom else 0L
            if (!supportsResume && tmp.exists()) tmp.delete()

            val total = (conn.contentLengthLong.coerceAtLeast(0L)) + startOffset
            var downloaded = startOffset
            var lastPct = -1
            // Notification updates are throttled independently of DAO writes:
            // Android rate-limits/sheds notify() calls per app (confirmed on
            // device - "Package enqueue rate is 5.63... Shedding" - once that
            // happens, even the final "Download complete" update can be
            // dropped, leaving the tray stuck at a mid-transfer percentage
            // forever). DAO writes aren't subject to that limit and stay on
            // every 1% tick so Settings' progress stays fine-grained.
            var lastNotifyAtMs = 0L
            val notifyIntervalMs = 400L

            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp, supportsResume).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                dao.upsert(
                                    ModelDownloadState(
                                        modelKey = modelKey,
                                        status = ModelDownloadStatus.DOWNLOADING.name,
                                        url = url,
                                        bytesDownloaded = downloaded,
                                        bytesTotal = total,
                                        destPath = dest.absolutePath,
                                        lastErrorMessage = null,
                                        updatedAtEpochMillis = System.currentTimeMillis(),
                                    ),
                                )
                                val now = System.currentTimeMillis()
                                if (now - lastNotifyAtMs >= notifyIntervalMs) {
                                    lastNotifyAtMs = now
                                    val dlMB = downloaded / 1_048_576
                                    val totalMB = total / 1_048_576
                                    nm.notify(
                                        notifId,
                                        builder.setProgress(100, pct, false).setContentText("$dlMB MB / $totalMB MB").build(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            tmp.renameTo(dest)

            dao.upsert(
                ModelDownloadState(
                    modelKey = modelKey,
                    status = ModelDownloadStatus.COMPLETE.name,
                    url = url,
                    bytesDownloaded = downloaded,
                    bytesTotal = total,
                    destPath = dest.absolutePath,
                    lastErrorMessage = null,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            nm.notify(
                notifId,
                builder.setOngoing(false).setProgress(0, 0, false)
                    .setContentText("Download complete")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .build(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $modelKey", e)
            dao.upsert(
                ModelDownloadState(
                    modelKey = modelKey,
                    status = ModelDownloadStatus.FAILED.name,
                    url = url,
                    bytesDownloaded = resumeFrom,
                    bytesTotal = 0L,
                    destPath = dest.absolutePath,
                    lastErrorMessage = e.message ?: e.javaClass.simpleName,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            nm.cancel(notifId)
            // tmp is deliberately kept (not deleted) - a partial file is what
            // resume-on-retry needs; only a full success renames it away.
        }
    }

    private fun resolveRedirect(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            conn.connect()
            val loc = conn.getHeaderField("Location")
            if (conn.responseCode in 300..399 && loc != null) loc else url
        } finally {
            conn.disconnect()
        }
    }

    private fun startForegroundWithNotification(notifId: Int, title: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Mofy Model Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifId, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mofy:model-download").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L) // 30 min safety cap - never held indefinitely even if release() is missed
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
