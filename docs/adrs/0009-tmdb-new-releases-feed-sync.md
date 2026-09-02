# ADR 0009: Periodic TMDB New/Upcoming Feed Sync

**Status:** proposed

## Context

The user wants Discover/Home to surface newly-released and upcoming
movies/TV without polling TMDB on every app open, refreshed roughly every
two weeks via a background job.

TMDB's `/movie/{movie_id}/latest` endpoint (the one initially proposed)
is **not** a feed — it returns the single most-recently-*created* movie ID
in TMDB's own database (an internal/testing endpoint for finding the
current upper ID bound), not a curated new/upcoming list. Verified against
TMDB's docs and forum threads: the actual feed endpoints are
`/movie/upcoming`, `/movie/now_playing`, `/tv/on_the_air`, and
`/tv/airing_today` — each requires an explicit `region` query param, or
`upcoming` and `now_playing` return near-duplicate lists (a documented,
frequently-reported gotcha). Rate limit is a soft ~40 req/s IP-based
ceiling — irrelevant at single-device, once-per-2-weeks scale, but 429
handling should exist from the start since it's the most common complaint
against TMDB integrations in comparable open-source projects (Overseerr,
Ombi).

The existing catalog is two **bundled, static, read-only** assets copied
from `assets/` to internal storage on first run (`CatalogDatabase.kt` →
`catalog.db`, opened `OPEN_READONLY`; `VecDatabase.kt` → `catalog_vec.db`,
a `vec0` table) — both explicitly documented as "static shipped data with
no migrations, writes, or entity mapping needed." Writing synced TMDB
titles directly into these files fights that design (asset-recopy-on-update
semantics, no migration path, no FTS/vec0 write path exercised anywhere
else in the codebase). Synced titles instead belong in new, writable
tables inside `AppDatabase` (Room; already loads the `sqlite-vec` native
extension for other purposes per ADR 0007), queried alongside the bundled
catalog rather than merged into it.

A periodic, small (~1-2 API pages), deferrable network job run every two
weeks is the case WorkManager's own documentation actually describes it
for — unlike the earlier (retracted) suggestion to use it for large
in-progress torrent/model downloads, this is not a case where any
comparable open-source project needed a foreground Service instead.

## Decision

Add a `PeriodicWorkRequest` (`~14` day interval, network-constrained) that
fetches TMDB's upcoming/now-playing/on-the-air/airing-today pages
(region-scoped), upserts new titles into new Room-managed tables (separate
from the bundled `catalog.db`/`catalog_vec.db`), and refreshes a Home
"New & Upcoming" row plus a Discover filter sourced from those tables.

## Schema tooling (adopted alongside this ADR)

`AppDatabase` currently has `exportSchema = false` — no versioned schema
history exists today. Verified against Room's own docs/testing library
rather than assumed: Room's `exportSchema=true` writes a JSON snapshot per
`version` to `app/schemas/<version>.json`, git-diffable the same way
Rails' `schema.rb` is; `androidx.room:room-testing`'s `MigrationTestHelper`
then runs a `Migration` against a prior snapshot and asserts the result.
This is Room's built-in equivalent of go-migrate's up/verify flow, not a
new dependency. **Enable `exportSchema = true` and add
`androidx.room:room-testing` as `testImplementation` as part of this ADR's
task 3**, since it's the first schema version bump since the gap was
noticed.

SQLDelight's numbered `.sqm` migration files +
`verifySqlDelightMigration` Gradle task are the closer analog to
go-migrate specifically, but adopting it means replacing Room project-wide
— `AppDatabase` already has a custom `BundledSQLiteDriver` loading
`sqlite-vec`/`spellfix1` native extensions that would need separate
verification under SQLDelight's driver model. Out of scope for this ADR;
worth its own future ADR if broader migration tooling is wanted.

## New tables

All three live in the existing Room-managed `mofy.db` (`AppDatabase`), not
the bundled `catalog.db`/`catalog_vec.db` assets (see Context).

```kotlin
// data/catalog/SyncedCatalogItem.kt
@Entity(
    tableName = "synced_catalog_items",
    indices = [Index(value = ["tmdbId", "mediaType"], unique = true)],
)
data class SyncedCatalogItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tmdbId: Int,
    val mediaType: String,           // "movie" | "tv"
    val title: String,
    val overview: String,
    val posterUrl: String?,
    val releaseDate: String?,        // ISO 8601 date, nullable (TBA titles)
    val genres: String?,             // comma-separated, mirrors CatalogItem
    val kind: String,                // UPCOMING | NOW_PLAYING | ON_AIR | AIRING_TODAY
    val firstSeenEpochMillis: Long,
)
```

```kotlin
// data/catalog/SyncedCatalogSearchEntity.kt
// Manually synced (delete+reinsert on write), same pattern as
// LibrarySearchEntity - Room's @Fts4 contentEntity linkage requires a
// Long/Int source PK, and while synced_catalog_items.id qualifies, mirror
// the existing manual-sync pattern for consistency rather than mixing
// both linkage styles in one codebase.
@Fts4
@Entity(tableName = "synced_catalog_search")
data class SyncedCatalogSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Int? = null,
    val itemId: Long,   // synced_catalog_items.id
    val title: String,
    val overview: String,
)
```

```sql
-- Not a Room @Entity - Room can't model vec0's typed virtual-table columns.
-- Created via a raw Migration.execSQL(...), same native extension already
-- loaded on AppDatabase's own driver (see AppDatabase.kt's addExtension
-- calls) - no new driver wiring needed, unlike catalog_vec.db which needed
-- its own separate BundledSQLiteDriver.
CREATE VIRTUAL TABLE synced_catalog_vec USING vec0(
  embedding float[768]
);
-- rowid of this table = synced_catalog_items.id (written explicitly on
-- insert, joined the same way VecDatabase.knn() joins catalog_vec to
-- catalog_meta today).
```

## Task list

1. Enable `exportSchema = true` on `AppDatabase`; add
   `androidx.room:room-testing` (`testImplementation`) to
   `android/app/build.gradle.kts`.
2. Add `SyncedCatalogItem`, `SyncedCatalogSearchEntity` entities (above);
   add a `Migration` (current version → next) with `execSQL` for both
   Room-managed tables plus the raw `synced_catalog_vec` virtual table;
   register all in `AppDatabase`'s `entities=[...]` and bump `version`.
   Write one `MigrationTestHelper` test asserting the migration applies
   cleanly.
3. Add `SyncedCatalogDao` (insert/upsert by `(tmdbId, mediaType)`, query
   by `kind`/recency for Home, paging query for Discover) +
   `SyncedCatalogSearchDao` (manual FTS sync, mirrors `LibraryDao`'s
   existing delete+reinsert pattern) + a small `SyncedCatalogVecDao`
   (raw `execSQL`/`prepare` insert + KNN query, mirrors `VecDatabase.knn()`).
4. Add `TmdbApi.kt` endpoints: `GET /movie/upcoming`, `GET /movie/now_playing`,
   `GET /tv/on_the_air`, `GET /tv/airing_today` (all take `region`, `page`).
5. Add 429/`Retry-After`-aware retry wrapper in `TmdbRepository.kt` around
   the four new calls (exponential backoff, max 3 attempts, skip-and-log
   on exhaustion — never crash the caller).
6. Add `SyncedCatalogRepository`: fetches all four feed kinds via 4/5,
   dedupes/upserts into 3's DAOs, generates embeddings for new rows' overview
   text via the existing `OnDeviceEmbedder` (ADR 0002/0007), writes to the
   vec table.
7. Add `androidx.work:work-runtime-ktx` dependency; add `CatalogSyncWorker`
   (`CoroutineWorker`) calling 6; register as
   `PeriodicWorkRequestBuilder<CatalogSyncWorker>(14, TimeUnit.DAYS)` with
   `NetworkType.CONNECTED`, enqueued via
   `enqueueUniquePeriodicWork(..., ExistingPeriodicWorkPolicy.KEEP)` from
   `MofyApplication.onCreate()`.
8. Add a "Refresh new releases now" action in `SettingsScreen.kt` (one-off
   `WorkRequest` sharing `CatalogSyncWorker`) for testing without waiting
   two weeks.
9. Add Home "New & Upcoming" row in `HomeScreen.kt`, querying 3's DAO.
10. Add a "New & Upcoming" sort/filter option to `CatalogSort.kt` +
    `CatalogPagingSource.kt`/`DiscoverScreen.kt`.

## Task change entries

Each entry is scoped to be independently reviewable and implementable
without needing to read the rest of this ADR — file paths, exact
signatures, and the existing convention each task must match are called
out explicitly so a smaller model can produce code consistent with the
rest of the codebase.

### Task 1 — Enable schema export + migration testing

**Files:** `android/app/build.gradle.kts`

**Current state:** `AppDatabase.kt`'s `@Database` annotation has
`exportSchema = false`. No `androidx.room:room-testing` dependency exists.

**Change:**
- In `android/app/build.gradle.kts`, add under `dependencies { ... }`
  (near the existing `testImplementation` block):
  ```kotlin
  testImplementation("androidx.room:room-testing:2.8.1")
  ```
  (match the exact Room version already used for `room-runtime`/`room-ktx`
  in this file — do not introduce a different Room version.)
- Add to the `android { }` block so exported schemas land in a
  git-tracked location:
  ```kotlin
  ksp {
      arg("room.schemaLocation", "$projectDir/schemas")
  }
  ```
  (this project already uses `ksp` for Room's annotation processor — see
  the `com.google.devtools.ksp` plugin block — don't add `kapt`.)

**Depends on:** nothing.

**Acceptance criteria:** `./gradlew assembleDebug` produces
`android/app/schemas/com.mofy.app.data.library.AppDatabase/<version>.json`
for the current schema version. No behavior change yet — this task only
turns on the mechanism task 2 will rely on.

---

### Task 2 — New entities, migration, schema bump

**Files:** new `data/catalog/SyncedCatalogItem.kt`, new
`data/catalog/SyncedCatalogSearchEntity.kt`, `data/library/AppDatabase.kt`,
new `androidTest`/`test` migration test file.

**Current state:** `AppDatabase.kt`'s `@Database(entities = [...], version = 15, exportSchema = false)`
(see file for the full entity list). `LibrarySearchEntity.kt` is the
existing FTS4 convention to mirror (manual sync, `@Fts4` + `@Entity`,
`rowid` as `Int?` primary key with `@ColumnInfo(name = "rowid")`).

**Change:**
- Create `SyncedCatalogItem` and `SyncedCatalogSearchEntity` exactly as
  specified in **New tables** above, in `data/catalog/` (not
  `data/library/` — this is catalog data, matching where `CatalogItem`
  already lives, even though the table itself is Room-managed unlike
  `CatalogItem`).
- Add both to `AppDatabase`'s `entities = [...]` list.
- Bump `version = 15` → `version = 16`.
- Add a `Migration(15, 16)` object (new file
  `data/library/Migrations.kt`, since none exists yet) with `execSQL` for:
  the two Room entity tables' `CREATE TABLE`/`CREATE VIRTUAL TABLE`
  (Room's KSP output gives you the exact DDL — copy it from the generated
  schema JSON from task 1 rather than hand-writing it, to guarantee it
  matches what Room expects), plus the raw
  `CREATE VIRTUAL TABLE synced_catalog_vec USING vec0(embedding float[768]);`
  statement.
  ```kotlin
  object Migrations {
      val MIGRATION_15_16 = object : Migration(15, 16) {
          override fun migrate(db: SupportSQLiteDatabase) {
              db.execSQL("CREATE TABLE IF NOT EXISTS `synced_catalog_items` (...)")
              db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `synced_catalog_search` USING fts4(...)")
              db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `synced_catalog_vec` USING vec0(embedding float[768])")
          }
      }
  }
  ```
- Register it: `Room.databaseBuilder(...).addMigrations(Migrations.MIGRATION_15_16)...`
  in `AppDatabase.get()` — note this project currently relies on
  `.fallbackToDestructiveMigration(true)` with no explicit migrations
  anywhere; adding a real `Migration` here is a deliberate deviation
  because this ADR's whole point is exercising versioned schema history,
  not destructive resets. Keep `fallbackToDestructiveMigration(true)` as
  the fallback for any *other* future version jump that skips writing a
  migration, but this specific 15→16 step must use the real `Migration`.

**Depends on:** Task 1 (need the exported v15 schema JSON to write
accurate DDL).

**Acceptance criteria:** a `MigrationTestHelper`-based test
(`androidTest/.../AppDatabaseMigrationTest.kt`) that creates a v15
database, runs `MIGRATION_15_16`, and asserts it succeeds without
throwing and that `synced_catalog_items`/`synced_catalog_search`/
`synced_catalog_vec` exist in the resulting schema (query
`sqlite_master`). `./gradlew testDebugUnitTest` and the new instrumented
test both pass.

---

### Task 3 — DAOs

**Files:** new `data/catalog/SyncedCatalogDao.kt`, new
`data/catalog/SyncedCatalogSearchDao.kt`, new
`data/catalog/SyncedCatalogVecDao.kt`, `data/library/AppDatabase.kt`.

**Current state:** `GenreDao.kt` is the simplest real convention to
mirror for a plain upsert DAO (`@Insert(onConflict = OnConflictStrategy.REPLACE)`).
`LibraryDao.kt`'s `reindexSearch` (around line 155, `@Transaction`,
delete-then-insert into the FTS table) is the convention for keeping a
manually-synced FTS table consistent. `VecDatabase.kt`'s `knn()` is the
convention for raw vec0 `prepare`/`bindBlob`/`step` querying — but that
class talks to the separate bundled `catalog_vec.db` file; this task's
`SyncedCatalogVecDao` instead runs against `AppDatabase`'s own already-open
connection (it already loads `libvec0` on its driver, see
`AppDatabase.kt`'s `addExtension` calls) — do not open a second SQLite
connection to `mofy.db`.

**Change:**
```kotlin
// data/catalog/SyncedCatalogDao.kt
@Dao
interface SyncedCatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SyncedCatalogItem>): List<Long> // returns rowids, needed by SyncedCatalogVecDao

    @Query("SELECT * FROM synced_catalog_items WHERE kind = :kind ORDER BY firstSeenEpochMillis DESC LIMIT :limit")
    suspend fun recentByKind(kind: String, limit: Int): List<SyncedCatalogItem>

    @Query("SELECT * FROM synced_catalog_items ORDER BY firstSeenEpochMillis DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<SyncedCatalogItem> // task 10 wires this into paging; keyset-vs-offset choice left to task 10 to match CatalogPagingSource's existing keyset convention if adopted there

    @Query("SELECT id FROM synced_catalog_items WHERE tmdbId = :tmdbId AND mediaType = :mediaType")
    suspend fun findId(tmdbId: Int, mediaType: String): Long?
}
```
```kotlin
// data/catalog/SyncedCatalogSearchDao.kt — mirror LibraryDao.reindexSearch's pattern exactly
@Dao
interface SyncedCatalogSearchDao {
    @Insert
    suspend fun insertSearchRow(row: SyncedCatalogSearchEntity)

    @Query("DELETE FROM synced_catalog_search WHERE itemId = :itemId")
    suspend fun deleteSearchRow(itemId: Long)

    @Transaction
    suspend fun reindex(itemId: Long, title: String, overview: String) {
        deleteSearchRow(itemId)
        insertSearchRow(SyncedCatalogSearchEntity(itemId = itemId, title = title, overview = overview))
    }
}
```
`SyncedCatalogVecDao` is not a Room `@Dao` interface (Room can't generate
queries against a `vec0` virtual table's typed columns) — write it as a
plain class taking the `SupportSQLiteDatabase`/driver handle, with an
`insert(itemId: Long, embedding: FloatArray)` and a
`knn(embedding: FloatArray, k: Int): List<Long>` (returns
`synced_catalog_items.id`s), following `VecDatabase.knn()`'s exact
`prepare`/`bindBlob`/`step`/`close` structure including its `toBlob()`
helper — do not reimplement blob conversion differently.

- Add `abstract fun syncedCatalogDao(): SyncedCatalogDao` (and the search
  DAO) to `AppDatabase`.

**Depends on:** Task 2.

**Acceptance criteria:** a unit/instrumented test inserting one
`SyncedCatalogItem`, confirming `recentByKind` returns it, confirming
`reindex` produces exactly one FTS row queryable via
`synced_catalog_search MATCH`, and confirming `SyncedCatalogVecDao.knn`
returns the inserted item's id for its own embedding (self-match sanity
check).

---

### Task 4 — TMDB feed endpoints

**Files:** `data/tmdb/TmdbApi.kt`, `data/tmdb/TmdbModels.kt`.

**Current state:** `TmdbApi.kt` is a flat Retrofit interface, one
`@GET`/`suspend fun` per endpoint, grouped with a comment banner per
section (see the "Detail endpoints" comment). `TmdbResultDto` in
`TmdbModels.kt` already has every field these new endpoints return
(`title`/`name`, `overview`, `poster_path`, `release_date`/`first_air_date`,
`genre_ids`) — reuse `TmdbSearchResponse`/`TmdbResultDto` as the response
type, do not create new DTOs.

**Change:** append to `TmdbApi.kt`, matching the existing style exactly
(no trailing period in doc comments, same parameter style as
`findByImdbId`'s optional query param):
```kotlin
@GET("movie/upcoming")
suspend fun upcomingMovies(@Query("region") region: String, @Query("page") page: Int = 1): TmdbSearchResponse

@GET("movie/now_playing")
suspend fun nowPlayingMovies(@Query("region") region: String, @Query("page") page: Int = 1): TmdbSearchResponse

@GET("tv/on_the_air")
suspend fun onTheAirTv(@Query("page") page: Int = 1): TmdbSearchResponse // TMDB's tv/on_the_air has no region param

@GET("tv/airing_today")
suspend fun airingTodayTv(@Query("page") page: Int = 1): TmdbSearchResponse
```
(Verify the `tv/on_the_air`/`tv/airing_today` region-param behavior
against TMDB's current API reference before implementing — the ADR's
Context section notes `region` matters for the two `movie/*` endpoints;
confirm whether TMDB's TV feed endpoints accept/require it too rather
than assuming symmetry with the movie endpoints.)

**Depends on:** nothing.

**Acceptance criteria:** a `TmdbApi` instrumented/unit test (or manual
`TmdbClient.api.upcomingMovies("US")` call in a debug build) returns a
non-empty `results` list.

---

### Task 5 — Retry/backoff wrapper

**Files:** `data/tmdb/TmdbRepository.kt`.

**Current state:** `TmdbRepository`'s private `safeCall` already catches
`retrofit2.HttpException` and wraps it as `TmdbResult.Failure(TmdbError.Http(e.code()))`
— it does not currently retry anything. Every existing public method
(`searchMovies`, `getMovieDetail`, etc.) is a one-line `safeCall { ... }`
call — match that shape.

**Change:** add a second private wrapper that retries specifically on 429,
reusing `safeCall`'s existing exception mapping rather than duplicating it:
```kotlin
private suspend fun <T> safeCallWithRetry(
    maxAttempts: Int = 3,
    block: suspend () -> T,
): TmdbResult<T> {
    var lastResult: TmdbResult<T>
    var attempt = 0
    while (true) {
        lastResult = safeCall(block)
        val failure = lastResult as? TmdbResult.Failure
        val isRateLimited = failure?.error is TmdbError.Http && (failure.error as TmdbError.Http).code == 429
        attempt++
        if (!isRateLimited || attempt >= maxAttempts) return lastResult
        kotlinx.coroutines.delay(1000L * (1L shl (attempt - 1))) // 1s, 2s, 4s
    }
}

suspend fun upcomingMovies(region: String): TmdbResult<List<MediaResult>> =
    safeCallWithRetry { api.upcomingMovies(region).results.map { it.toMediaResult(MediaType.MOVIE) } }

suspend fun nowPlayingMovies(region: String): TmdbResult<List<MediaResult>> =
    safeCallWithRetry { api.nowPlayingMovies(region).results.map { it.toMediaResult(MediaType.MOVIE) } }

suspend fun onTheAirTv(): TmdbResult<List<MediaResult>> =
    safeCallWithRetry { api.onTheAirTv().results.map { it.toMediaResult(MediaType.TV) } }

suspend fun airingTodayTv(): TmdbResult<List<MediaResult>> =
    safeCallWithRetry { api.airingTodayTv().results.map { it.toMediaResult(MediaType.TV) } }
```
(TMDB's actual `Retry-After` header handling — reading it off
`retrofit2.HttpException.response()?.headers()` instead of a fixed
backoff schedule — is a real improvement but not required for a
correct v1; the fixed 1s/2s/4s schedule above is the acceptance bar,
reading the header is a nice-to-have the implementer may add without
changing this task's shape.)

**Depends on:** Task 4.

**Acceptance criteria:** a unit test mocking `TmdbApi` to throw a 429
`HttpException` twice then succeed confirms `upcomingMovies` returns
`TmdbResult.Success` after 3 attempts; a test mocking a persistent 429
confirms it returns `TmdbResult.Failure` after exactly `maxAttempts`
tries, not more.

---

### Task 6 — SyncedCatalogRepository

**Files:** new `data/catalog/SyncedCatalogRepository.kt`.

**Current state:** `GenreRepository.ensureSynced()`/`sync()` (in
`data/tmdb/GenreRepository.kt`) is the closest existing convention for "a
repository that fetches from `TmdbApi`/`TmdbRepository` and upserts into a
DAO" — mirror its constructor-injected-DAO-plus-default-api style.
`OnDeviceEmbedder` (in `search/OnDeviceEmbedder.kt`) is the existing
convention for generating an embedding from text — check its current
public method signature before wiring task 3's `SyncedCatalogVecDao` to
it, rather than assuming a signature here.

**Verified against a real `now_playing` response** (sample fetched
2026-09-02): `id`, `title`, `overview`, `poster_path`, `release_date`,
`genre_ids` map directly onto `TmdbResultDto` as-is — no new DTO fields
needed. Two things the sample surfaces that this task must account for:
- `total_pages`/`total_results` on a single `now_playing` call were 228
  and 4553 — TMDB's window is theatrical-release-based, not
  "recently added," and is far too large to paginate in full each cycle.
  **Fetch only `page = 1` per feed per sync** (TMDB pages are sorted by
  popularity/date depending on endpoint — page 1 is the highest-signal
  slice) — do not loop pages 1..total_pages.
- The response includes fields `TmdbResultDto` doesn't model (e.g.
  `softcore`) — harmless no-op given `TmdbClient`'s `Json` is configured
  `ignoreUnknownKeys=true`; don't add fields to `TmdbResultDto` just to
  cover every field TMDB happens to send.

**Change:**
```kotlin
class SyncedCatalogRepository(
    private val tmdb: TmdbRepository = TmdbRepository(),
    private val dao: SyncedCatalogDao,
    private val searchDao: SyncedCatalogSearchDao,
    private val vecDao: SyncedCatalogVecDao,
    private val embedder: OnDeviceEmbedder,
) {
    suspend fun sync(region: String) {
        val feeds = listOf(
            "UPCOMING" to tmdb.upcomingMovies(region),
            "NOW_PLAYING" to tmdb.nowPlayingMovies(region),
            "ON_AIR" to tmdb.onTheAirTv(),
            "AIRING_TODAY" to tmdb.airingTodayTv(),
        )
        for ((kind, result) in feeds) {
            if (result !is TmdbResult.Success) continue // logged failure already handled by safeCallWithRetry's caller; skip this kind, don't fail the whole sync
            for (media in result.data) {
                val existingId = dao.findId(media.id, media.mediaType.name.lowercase())
                if (existingId != null) continue // dedupe: never re-embed/re-fetch already-synced titles
                val item = SyncedCatalogItem(/* map MediaResult -> SyncedCatalogItem fields */)
                val ids = dao.upsertAll(listOf(item))
                val newId = ids.first()
                searchDao.reindex(newId, item.title, item.overview)
                val embedding = embedder.embed(item.overview) // confirm actual OnDeviceEmbedder method name/signature before implementing
                vecDao.insert(newId, embedding)
            }
        }
    }
}
```

**Depends on:** Task 3, Task 5.

**Acceptance criteria:** a test with a fake `TmdbRepository` returning two
known titles confirms both land in `SyncedCatalogDao`, both are
FTS-searchable, and both have a KNN-queryable embedding; running `sync()`
twice with the same fake data confirms no duplicate rows (dedupe works).

---

### Task 7 — Periodic WorkManager job

**Files:** `android/app/build.gradle.kts`, new
`workers/CatalogSyncWorker.kt` (new package — no `workers/` package
exists yet; this is the first `Worker` in the codebase), `MofyApplication.kt`.

**Current state:** No `androidx.work` dependency exists in
`build.gradle.kts`. `MofyApplication.onCreate()`'s existing pattern is
`applicationScope.launch { someRepository.ensureSynced() }` for
app-launch-time sync — the periodic job is a different mechanism
(`WorkManager`, not `applicationScope.launch`), don't conflate the two.

**Change:**
- Add to `android/app/build.gradle.kts` dependencies:
  ```kotlin
  implementation("androidx.work:work-runtime-ktx:2.10.0")
  ```
- New file:
  ```kotlin
  package com.mofy.app.workers

  class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
      override suspend fun doWork(): Result {
          val database = AppDatabase.get(applicationContext)
          val repository = SyncedCatalogRepository(
              dao = database.syncedCatalogDao(),
              searchDao = database.syncedCatalogSearchDao(),
              vecDao = SyncedCatalogVecDao(database.openHelper /* or however task 3 exposes the raw handle */),
              embedder = OnDeviceEmbedder(applicationContext), // confirm actual constructor
          )
          val region = java.util.Locale.getDefault().country.ifEmpty { "US" }
          return try {
              repository.sync(region)
              Result.success()
          } catch (e: Exception) {
              Result.retry()
          }
      }
  }
  ```
- In `MofyApplication.onCreate()`, after the existing
  `applicationScope.launch { ... }` calls:
  ```kotlin
  val syncRequest = PeriodicWorkRequestBuilder<CatalogSyncWorker>(14, TimeUnit.DAYS)
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .build()
  WorkManager.getInstance(this).enqueueUniquePeriodicWork(
      "catalog_sync",
      ExistingPeriodicWorkPolicy.KEEP,
      syncRequest,
  )
  ```

**Depends on:** Task 6.

**Acceptance criteria:** app launches without crashing; `adb shell dumpsys jobscheduler`
(or WorkManager's own `WorkManager.getInstance(context).getWorkInfosForUniqueWork("catalog_sync")`)
shows the periodic work enqueued with `ENQUEUED` state. Manually running
`adb shell cmd jobscheduler run -f <package> <job-id>` (or task 8's manual
trigger) executes `doWork()` and populates `synced_catalog_items` from a
real device with network access.

---

### Task 8 — Manual "refresh now" trigger

**Files:** `ui/settings/SettingsScreen.kt`.

**Current state:** `SettingsScreen.kt` is currently a two-line stub
(`Text("Settings")` in a centered `Box`) — this is the first real content
added to it, not a modification of existing settings rows, so there's no
existing row/list convention in this specific file to match; use a plain
`Button`/`Column` with the project's existing button-shape convention
(`shape = MaterialTheme.shapes.small` — see `CLAUDE.md`'s Design guide,
Material3's `Button` ignores the theme's shape override otherwise).

**Change:**
```kotlin
@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        Button(
            onClick = {
                val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "catalog_sync_manual",
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            },
            shape = MaterialTheme.shapes.small,
        ) {
            Text("Refresh new releases now")
        }
    }
}
```

**Depends on:** Task 7.

**Acceptance criteria:** tapping the button in a debug build triggers a
visible `synced_catalog_items` row count increase (check via
`adb shell run-as com.mofy.app sqlite3 databases/mofy.db "select count(*) from synced_catalog_items"`
or a temporary debug log) without waiting for the 14-day schedule.

---

### Task 9 — Home "New & Upcoming" row

**Files:** `ui/home/HomeScreen.kt`.

**Current state:** `HomeScreen.kt`'s existing rows (`popular`,
`newReleases`, per-genre sections) follow the pattern:
`remember { mutableStateOf<List<CatalogItem>>(emptyList()) }` +
`LaunchedEffect(catalogRepository, posterVersion) { ... = catalogRepository.xxx(6) }`
+ a `LazyRow` rendering poster cards (see the file's full body past the
excerpt already read for this ADR — read the rest of `HomeScreen.kt`
before implementing to copy the exact poster-card composable it already
uses, don't invent a new card layout).

**Change:** add a `syncedCatalogDao: SyncedCatalogDao? = null` parameter
to `HomeScreen`'s signature (matching the existing optional-DAO-parameter
style already used for `libraryDao`/`watchProgressDao`), a
`newAndUpcoming` state var following the same `LaunchedEffect` pattern
calling `syncedCatalogDao.recentByKind("UPCOMING", 6)` (or a combined
query across all four `kind` values — implementer's call, note it here
rather than deciding silently), and a `LazyRow` section using the same
poster-card composable as the existing `popular`/`newReleases` rows,
titled "New & Upcoming".

**Depends on:** Task 3.

**Acceptance criteria:** with test data seeded in `synced_catalog_items`,
Home renders a "New & Upcoming" row with tappable poster cards; with no
synced data, the row does not render (matches existing empty-section
behavior elsewhere on Home — verify by reading how `genreSections`
already handles an empty result before implementing).

---

### Task 10 — Discover surfacing

**Files:** `data/catalog/CatalogSort.kt`, `data/catalog/CatalogPagingSource.kt`
or a new sibling paging source, `ui/discover/DiscoverScreen.kt`.

**Current state:** `CatalogSort` is a 3-value enum
(`MOST_VOTED`/`HIGHEST_RATED`/`NEWEST`) each with a `column: String` used
directly in `CatalogPagingSource`'s raw SQL `ORDER BY`. `CatalogPagingSource`
pages the bundled read-only `catalog.db` via `SQLiteDatabase.rawQuery`
with keyset pagination — it does not (and structurally cannot, different
`SQLiteDatabase` instance) also page `AppDatabase`'s `synced_catalog_items`
table in the same query.

**Change:** decide (and document the choice inline in code comments,
since this ADR deliberately leaves it open) between (a) a **separate**
`SyncedCatalogPagingSource` returned only when a new "New & Upcoming"
`CatalogSort`/filter value is selected in Discover, not merged with the
bundled-catalog paging source, or (b) unioning results client-side. Given
`CatalogPagingSource` structurally can't cross databases in one query,
**(a) is the simpler, lower-risk default** — implement that unless a
concrete reason emerges to do otherwise:
- Add a `NEW_AND_UPCOMING` case to a new filter enum (not `CatalogSort`,
  since this is a distinct-source filter, not a sort order on the same
  source — reuse `CatalogSort`'s existing sort values within this filtered
  set once selected, don't design a fourth sort column).
- New `SyncedCatalogPagingSource : PagingSource<Int, SyncedCatalogItem>`
  using plain `LIMIT`/`OFFSET` via `SyncedCatalogDao.page()` (task 3) —
  offset paging is acceptable here unlike the bundled catalog, since this
  table is small (dozens to low hundreds of rows, not IMDb's full dataset).
- `DiscoverScreen.kt`: when the new filter is active, construct a
  `Pager` over `SyncedCatalogPagingSource` instead of
  `CatalogPagingSource` — read the file's existing `Pager`/filter-state
  wiring before implementing to match its exact state-hoisting pattern.

**Depends on:** Task 3.

**Acceptance criteria:** selecting "New & Upcoming" in Discover's filter
sheet shows synced titles, paginating correctly past the first page with
seeded test data (≥ 2 pages worth); switching back to a normal sort
returns to the bundled-catalog behavior unchanged (regression check).

## Task DAG

Node numbers match the Task list above.

```
 1 ──► 2 ──┬─► 3 ──► 6 ──► 7 ──► 8
           │         ▲
 4 ──► 5 ──┘         │
                      │
                      └─► 9
                      └─► 10
```

- **1 → 2**: schema tooling must land before the first real schema bump
  (task 2) so the new version is captured in an exported snapshot from the
  start, not retrofitted later.
- **2 → 3**: DAOs need the entities/migration from 2 to compile against.
- **4 → 5**: the retry wrapper (5) wraps the endpoints added in 4.
- **3, 5 → 6**: the repository (6) needs both the DAOs (3) and the
  rate-limit-safe API calls (5).
- **6 → 7 → 8**: the worker (7) calls the repository (6); the manual
  Settings trigger (8) shares the worker class from 7.
- **3 → 9, 3 → 10**: Home (9) and Discover (10) both read from the DAOs in
  3 and have no dependency on each other — can be done in parallel once 3
  lands, independent of the worker/sync path (6-8).

## Alternatives considered

- **Write synced titles into the bundled `catalog.db`/`catalog_vec.db`
  directly** — rejected: both are documented static assets re-copied from
  `assets/` with no migration path; mixing runtime writes into an asset
  that gets silently replaced (or not) on app updates is fragile and
  contradicts their existing "read-only" design intent.
- **Trakt.tv calendars instead of TMDB** — rejected: requires OAuth for a
  single-device app with no clear capability gain over TMDB for pure
  discovery; no comparable open-source project (Jellyfin/Stremio/Overseerr)
  uses it as a primary catalog/discovery source.
- **On-demand fetch (no background job)** — rejected per explicit user
  request for a periodic worker; also would put a TMDB round-trip on
  Home's/Discover's critical load path.

## Consequences

- New `androidx.work` dependency, new Room migration (version bump on
  `AppDatabase`, currently v15 with `fallbackToDestructiveMigration` — pre-
  release, so a destructive bump is acceptable per existing project
  practice).
- Two parallel "catalog" read paths (bundled static + synced writable)
  until/unless a future ADR unifies them — Discover/Home query code needs
  to know about both.
- Embedding generation for every synced title's overview runs on-device
  (existing EmbeddingGemma pipeline) each sync cycle for new rows only —
  volume is small (at most a few dozen new titles per 2-week window), no
  new performance concern.
- Exact TMDB `region` value (device locale vs. a hardcoded region vs. a
  Settings-configurable one) is left to implementation — a real product
  decision, not decided by this ADR.
