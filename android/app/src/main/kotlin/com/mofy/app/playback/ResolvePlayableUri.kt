package com.mofy.app.playback

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Resolves a just-picked SAF `content://` uri to something that stays
 * reliably playable later, at Play time, on a different screen - the SAF
 * uri itself often doesn't (confirmed on a real device:
 * DownloadStorageProvider's "raw:" documents reject reads once this
 * picker's callback has returned, regardless of a persisted permission
 * grant). Must be called from the picker's own callback, while whatever
 * access it has is still guaranteed valid.
 *
 * Copies the bytes into app-private storage under a per-file UUID
 * subfolder (not a UUID-prefixed filename - the filename itself needs to
 * stay clean since callers like LinkScreen's ExistingLinkRow derive the
 * displayed name straight from the uri's last path segment) and returns
 * that file:// path. A MediaStore-lookup alternative (matching the picked
 * file to its indexed row, same as VLC-Android does for Downloads/gallery
 * files) was tried first but dropped: reliable in principle, but a row can
 * be temporarily invisible to this app's query while MediaStore marks it
 * "pending" (confirmed on a real device), and its uri ends in an opaque
 * numeric id with no filename at all - both wrong for a personal-use app
 * that needs Play to always work and library rows to show a real name, at
 * the cost of a one-time copy this app's file sizes don't make expensive.
 */
suspend fun resolvePlayableUri(context: Context, uri: Uri, displayName: String?): String =
    withContext(Dispatchers.IO) {
        val destDir = File(context.applicationContext.filesDir, "library_media/${UUID.randomUUID()}")
        destDir.mkdirs()
        val destFile = File(destDir, displayName ?: "video.mp4")
        context.applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read picked file")
        Uri.fromFile(destFile).toString()
    }
