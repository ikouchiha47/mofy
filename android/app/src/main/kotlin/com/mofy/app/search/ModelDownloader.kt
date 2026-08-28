package com.mofy.app.search

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

interface ModelDownloader {
    /** Download `url` to `dest`, no progress reporting. */
    fun download(url: String, dest: File)

    /** Download `url` to `dest` with a system notification showing MB progress. */
    fun downloadWithProgress(url: String, dest: File, title: String)
}

class HttpModelDownloader(
    private val context: Context,
    private val channelId: String,
    private val notifId: Int,
    private val hfToken: String? = null,
) : ModelDownloader {

    private val nm by lazy { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    private fun HttpURLConnection.applyHfAuth() {
        hfToken?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
    }

    override fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.applyHfAuth()
        try {
            conn.inputStream.use { it.copyTo(dest.outputStream()) }
        } catch (e: Exception) {
            dest.delete(); throw e
        } finally {
            conn.disconnect()
        }
    }

    override fun downloadWithProgress(url: String, dest: File, title: String) {
        ensureChannel()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, true)
            .setContentText("Starting download…")
        nm.notify(notifId, builder.build())

        val resolvedUrl = resolveRedirect(url)
        Log.i("HttpModelDownloader", "Downloading from resolved URL (${resolvedUrl.take(80)}…)")

        val conn = URL(resolvedUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 300_000
        conn.instanceFollowRedirects = true
        conn.applyHfAuth()
        val tmp = File(dest.parent, "${dest.name}.tmp")
        try {
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            var downloaded = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                val dlMB = downloaded / 1_048_576
                                val totalMB = total / 1_048_576
                                Log.i("HttpModelDownloader", "$title: $dlMB MB / $totalMB MB ($pct%)")
                                nm.notify(notifId, builder
                                    .setProgress(100, pct, false)
                                    .setContentText("$dlMB MB / $totalMB MB")
                                    .build())
                            }
                        }
                    }
                }
            }
            tmp.renameTo(dest)
            Log.i("HttpModelDownloader", "Download complete: ${dest.length() / 1_048_576} MB")
            nm.notify(notifId, builder
                .setOngoing(false).setProgress(0, 0, false)
                .setContentText("Download complete")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build())
        } catch (e: Exception) {
            tmp.delete()
            nm.cancel(notifId)
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun cancelNotif() = nm.cancel(notifId)

    private fun ensureChannel() {
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Mofy Downloads", NotificationManager.IMPORTANCE_LOW),
            )
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
}
