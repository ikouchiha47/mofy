package com.mofy.app.data.models

import android.util.Log
import java.io.File
import kotlin.runCatching

private const val TAG = "ModelFileCleanup"

object ModelFileCleanup {

    /**
     * Deletes any "*.tmp" in [dir] whose base name (the part before ".tmp")
     * is not in [keepNames]. At init time the only tmp that can legitimately
     * belong to a current download is "<expectedFile>.tmp", so anything else
     * is an orphan from a renamed/older model.
     */
    fun deleteOrphanTmpFiles(dir: File, keepNames: Set<String>) {
        if (!dir.exists() || !dir.isDirectory) return
        val keepTmpNames = keepNames.map { "${it}.tmp" }.toSet()
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name.endsWith(".tmp") && name !in keepTmpNames) {
                runCatching { file.delete() }.fold(
                    { success ->
                        if (success) Log.i(TAG, "Deleted orphan tmp: $name")
                        else Log.w(TAG, "Failed to delete orphan tmp: $name")
                    },
                    { e -> Log.w(TAG, "Exception deleting orphan tmp $name", e) }
                )
            }
        }
    }

    /**
     * Deletes files in [dir] whose name satisfies [matches] but is not in
     * [keepNames] - e.g. a leftover final file from a previous filename.
     */
    fun deleteStaleFiles(dir: File, keepNames: Set<String>, matches: (String) -> Boolean) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (matches(name) && name !in keepNames) {
                runCatching { file.delete() }.fold(
                    { success ->
                        if (success) Log.i(TAG, "Deleted stale file: $name")
                        else Log.w(TAG, "Failed to delete stale file: $name")
                    },
                    { e -> Log.w(TAG, "Exception deleting stale file $name", e) }
                )
            }
        }
    }
}