package com.mofy.app.data.library

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADR 0009 task 2 acceptance: the 15 -> 16 migration must apply cleanly and
 * create the three synced-catalog tables (two Room entities + the raw vec0
 * virtual table). Runs on-device (MigrationTestHelper reads the exported
 * schema snapshots from the androidTest assets - see build.gradle.kts's
 * androidTest.assets.srcDirs("$projectDir/schemas")).
 *
 * The helper's driver must load the sqlite-vec native extension - the
 * migration executes `CREATE VIRTUAL TABLE ... vec0(...)`, which fails with
 * "no such module: vec0" otherwise (same extension AppDatabase's own driver
 * loads). BundledSQLiteDriver now implements the new androidx.sqlite
 * SQLiteDriver API, so the helper is built with the File + SQLiteDriver +
 * KClass constructor.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDbName = "migration-test"

    private val driver = BundledSQLiteDriver().apply {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        addExtension("$nativeLibDir/libvec0", "sqlite3_vec_init")
    }

    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        File(context.getDatabasePath(testDbName).path),
        driver,
        AppDatabase::class,
    )

    @Test
    fun migration15To16_createsSyncedTables() {
        try {
            helper.createDatabase(15)
            val db = helper.runMigrationsAndValidate(16, listOf(Migrations.MIGRATION_15_16))

            val tables = mutableListOf<String>()
            val stmt = db.prepare(
                "SELECT name FROM sqlite_master WHERE name IN " +
                    "('synced_catalog_items', 'synced_catalog_search', 'synced_catalog_vec')",
            )
            try {
                while (stmt.step()) tables += stmt.getText(0)
            } finally {
                stmt.close()
            }
            assertTrue("synced_catalog_items missing, got $tables", "synced_catalog_items" in tables)
            assertTrue("synced_catalog_search missing, got $tables", "synced_catalog_search" in tables)
            assertTrue("synced_catalog_vec missing, got $tables", "synced_catalog_vec" in tables)
        } finally {
            context.deleteDatabase(testDbName)
        }
    }

    /** ADR 0010 task 1 acceptance: the 16 -> 17 migration creates model_download_state. */
    @Test
    fun migration16To17_createsModelDownloadState() {
        val dbName = "migration-test-16-17"
        val helper16to17 = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            File(context.getDatabasePath(dbName).path),
            driver,
            AppDatabase::class,
        )
        try {
            helper16to17.createDatabase(16)
            val db = helper16to17.runMigrationsAndValidate(17, listOf(Migrations.MIGRATION_16_17))

            val tables = mutableListOf<String>()
            val stmt = db.prepare(
                "SELECT name FROM sqlite_master WHERE name = 'model_download_state'",
            )
            try {
                while (stmt.step()) tables += stmt.getText(0)
            } finally {
                stmt.close()
            }
            assertTrue("model_download_state missing, got $tables", "model_download_state" in tables)
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}