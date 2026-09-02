package com.mofy.app.data.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mofy.app.data.library.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADR 0010 tasks 3-5 real-device smoke test - runs in-process (same uid as
 * the app), so ModelDownloadService's exported=false doesn't block it the
 * way an external `adb shell am start-service` call is (correctly) blocked.
 * Downloads a small real file over HTTPS through the actual foreground
 * service + repository path, not a fake/mock - proves the Service starts,
 * writes progress to the DAO, completes, and the WakeLock/notification
 * lifecycle doesn't crash or hang.
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloadSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realForegroundServiceDownloadCompletesAndUpdatesDao() = runBlocking<Unit> {
        val dao = AppDatabase.get(context).modelDownloadDao()
        val repo = ModelDownloadRepository(context, dao)
        val dest = File(context.filesDir, "smoke-test-readme.txt")
        dest.delete()

        val ok = repo.ensureDownloaded(
            modelKey = "smoke-test",
            url = "https://raw.githubusercontent.com/octocat/Hello-World/master/README",
            dest = dest,
            title = "Smoke test download",
        )

        assertTrue("expected ensureDownloaded to report success", ok)
        assertTrue("expected downloaded file to exist", dest.exists())
        assertTrue("expected downloaded file to be non-empty", dest.length() > 0)

        val state = dao.get("smoke-test")
        assertEquals(ModelDownloadStatus.COMPLETE.name, state?.status)

        dest.delete()
    }
}
