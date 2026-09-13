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
import com.mofy.app.data.models.ModelIntegrityRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloadService"
private const val NOTIF_CHANNEL = "mofy_model_download"

/** Thrown when a download passes the byte-copy loop but fails validation (truncated, wrong content, not a valid model). These are NOT resume-worthy — we delete the partial so the next attempt starts fresh. */
private class DownloadValidationException(message: String) : Exception(message)

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

            // Validate HTTP response code on the normal (non-resume) path too.
            // Only 200 (OK) or 206 (Partial Content) are acceptable.
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw DownloadValidationException("HTTP $responseCode")
            }

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

            // Validate byte count matches Content-Length (if server provided one).
            // A premature EOF would exit the read loop cleanly but leave us short.
            if (conn.contentLengthLong > 0 && (downloaded - startOffset) != conn.contentLengthLong) {
                throw DownloadValidationException("Truncated download: got ${downloaded - startOffset} of ${conn.contentLengthLong} bytes")
            }
            if (total > 0 && downloaded != total) {
                throw DownloadValidationException("Byte count mismatch: expected $total, got $downloaded")
            }

            // Validate the downloaded body before finalizing: reject HTML/error pages,
            // empty files, and files that aren't valid TFLite/ONNX models.
            validateDownloadedBody(tmp, conn.contentType)

            // Deterministic integrity check: expected size + SHA-256 (authoritative
            // corruption detection, replaces reliance on load-time failure).
            val expected = ModelIntegrityRegistry.expectedFor(dest.name)
            if (expected != null && !ModelIntegrityRegistry.verify(tmp)) {
                throw DownloadValidationException(
                    "Integrity check failed for ${dest.name} (expected ${expected.sizeBytes} bytes)",
                )
            }

            // Delete destination first — renameTo fails on some filesystems if dest exists.
            dest.delete()
            if (!tmp.renameTo(dest)) {
                throw DownloadValidationException("Could not finalize download")
            }

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
            // If validation failed, the file is content-invalid — delete both tmp and dest
            // so we don't resume a corrupt partial. Other exceptions keep tmp for resume.
            if (e is DownloadValidationException) {
                tmp.delete()
                dest.delete()
            }
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

    /**
     * Validates that the downloaded file is a genuine model file, not an HTML
     * error page or a truncated/corrupt binary. Called after the copy loop but
     * before the file is renamed into place and marked COMPLETE.
     *
     * Failure modes this catches (all observed in the wild):
     * - GitHub releases returns a 200 with an HTML "file not found" page when
     *   the asset is missing (no 404 because the *page* exists).
     * - Network proxy/captive portal injects an HTML login page.
     * - Premature EOF leaves a truncated .tflite/.onnx that passes exists()
     *   but fails interpreter creation with "does not encode a valid model".
     */
    private fun validateDownloadedBody(file: File, responseContentType: String?) {
        if (!file.exists() || file.length() == 0L) {
            throw DownloadValidationException("Downloaded file is empty")
        }
        val header = ByteArray(8)
        file.inputStream().use { it.read(header) }

        // Detect HTML/error pages: first non-whitespace byte is '<' or
        // Content-Type says text/html.
        val firstNonWs = header.firstOrNull { it.toInt() !in 0x09..0x0D && it.toInt() != 0x20 } // skip \t \n \r space
        if (firstNonWs?.toInt() == 0x3C || // '<'
            responseContentType?.lowercase()?.contains("text/html") == true) {
            throw DownloadValidationException("Server returned a web page, not a model file")
        }

        val destName = file.name.lowercase()
        if (destName.endsWith(".tflite")) {
            // FlatBuffer file identifier at offset 4..7: "TFL3" (0x54 0x46 0x4C 0x33)
            if (file.length() <= 8 ||
                header[4].toInt() != 0x54 || header[5].toInt() != 0x46 || header[6].toInt() != 0x4C || header[7].toInt() != 0x33) {
                throw DownloadValidationException("Downloaded file is not a valid TensorFlow Lite model")
            }
        } else if (destName.endsWith(".onnx")) {
            // ONNX (protobuf) — first byte is field 1 (ir_version) = 0x08
            if (header[0].toInt() != 0x08) {
                throw DownloadValidationException("Downloaded file is not a valid ONNX model")
            }
        }
        // Other file types (tokenizer.json, vocab.txt) — no magic bytes to check,
        // but the HTML/empty checks above still apply.
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
