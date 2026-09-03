package com.mofy.app.ui.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * Personal-use-only app (see CLAUDE.md), never distributed via Play Store,
 * so Play policy restrictions on MANAGE_EXTERNAL_STORAGE don't apply. A
 * single one-time grant here (Settings > Apps > Mofy > All files access)
 * replaces every future per-folder SAF "Allow Mofy to access folder?"
 * prompt with direct java.io.File access - see FileBrowserScreen, which
 * this permission is what makes a plain in-app file browser possible
 * instead of the system document picker.
 */
fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

fun requestAllFilesAccessIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
