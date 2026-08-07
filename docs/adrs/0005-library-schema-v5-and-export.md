# ADR 0005: Library Schema v5 (Decoupled Identity, Multi-Version Links) and Versioned Export

**Status:** accepted

## Context

ADR 0004 (Detail's Link/Play/Watch Together, manual entry, TMDB field sync)
exposed two real gaps in the schema described in earlier sessions:

1. `library_items.key = "${mediaType}_${tmdbId}"` doubles as both the
   primary key and a derived value from two fields that are no longer
   stable - `tmdbId` doesn't exist for manual (no-TMDB-match) entries, and
   `mediaType` is now user-correctable after creation via Detail's chips.
   A primary key built from fields that can be null or change isn't valid.
2. `library_downloads`'s dedup index (`libraryItemKey`, `infoHash`) doesn't
   dedupe manual name+link entries, because `infoHash` is `NULL` for them
   and SQLite treats every `NULL` as distinct from every other `NULL`.

Separately, a "what needs to change at scale" discussion concluded that for
a single-user, local-only, personal app, row-count scale is a non-issue
(a curated library realistically stays in the low thousands of rows even
after years) - the real long-horizon risks are data durability
(`fallbackToDestructiveMigration`, `allowBackup=false`) and platform churn
(SAF URI permissions going stale). Of those, this ADR resolves the export
concern now (explicitly requested); migrations and FK/cascade behavior are
explicitly deferred/rejected, see Decision below.

## Decision

### `library_items`: decouple identity from tmdbId/mediaType

```sql
CREATE TABLE library_items (
  id TEXT PRIMARY KEY,              -- synthetic (UUID) - replaces the old
                                     -- "${mediaType}_${tmdbId}" key
  tmdbId INTEGER,                   -- nullable - absent for manual entries
  mediaType TEXT,                   -- nullable - unset until Browse-locked
                                     -- or set via Detail's chips
  title TEXT NOT NULL,
  overview TEXT NOT NULL,
  posterPath TEXT,                  -- TMDB relative path
  localPosterUri TEXT,              -- uploaded image (manual entries)
  posterSource TEXT NOT NULL,       -- TMDB | UPLOADED | NONE
  year TEXT,
  genreIds TEXT NOT NULL,           -- TMDB ids, comma-joined
  genresManual TEXT,                -- free-text, manual entries only
  voteAverage REAL NOT NULL,
  runtime INTEGER,                  -- optional TMDB detail field
  tagline TEXT,                     -- optional TMDB detail field
  source TEXT NOT NULL,             -- SAVED | DOWNLOADED | IMPORTED | MANUAL
  addedAtEpochMillis INTEGER NOT NULL,
  detailSyncedAtEpochMillis INTEGER -- drives the "Sync info" self-heal (ADR 0004)
);

CREATE UNIQUE INDEX idx_library_items_tmdb ON library_items(tmdbId, mediaType);
```

`(tmdbId, mediaType)` stays a **plain** unique index, not a partial/filtered
one - Room doesn't support filtered unique indexes cleanly, and it doesn't
need to: SQLite already treats `NULL` as distinct from everything else, so
manual entries (`tmdbId = NULL`) never collide with each other or with real
TMDB rows.

### Conflict resolution: merge, never blind `REPLACE`

`OnConflictStrategy.REPLACE` on `library_items` would delete the row that
violates the unique index and insert a new one - since nothing else in this
schema uses FK constraints (see below), that would silently orphan any
`library_downloads`/`library_links` rows pointing at the deleted `id`.
Instead:

- Saving a TMDB-backed match **queries for an existing row by
  `(tmdbId, mediaType)` first**. If found, update that row's fields in
  place (same `id`) rather than inserting a new one - downloads/links
  stay attached.
- The same merge path is how a manual entry gets **matched to TMDB
  later** (a future "Search TMDB" action from an existing manual item) -
  it updates `tmdbId`/`mediaType` on the existing row, it never creates a
  second row for the same title.

### `library_downloads`: fix manual-entry dedup

```sql
ALTER TABLE library_downloads ADD COLUMN dedupeKey TEXT NOT NULL;
-- dedupeKey = infoHash when present, else sha256(name + ":" + uri)

DROP INDEX ...(libraryItemKey, infoHash);
CREATE UNIQUE INDEX idx_downloads_dedupe ON library_downloads(libraryItemKey, dedupeKey);
```

Manual entries now dedupe the same way magnet entries do - `dedupeKey` is
never null, so `IGNORE`-on-conflict works uniformly regardless of
`resourceType`.

### `library_links`: new table, multiple versions, one active

```sql
CREATE TABLE library_links (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  libraryItemKey TEXT NOT NULL,     -- references library_items.id, no FK (see below)
  label TEXT,                       -- e.g. "1080p" - user-entered or guessed from filename
  movieUri TEXT NOT NULL,
  subtitleUri TEXT,
  subtitle2Uri TEXT,
  isActive INTEGER NOT NULL,        -- 0/1 - Play always reads the active row
  linkedAtEpochMillis INTEGER NOT NULL
);

CREATE INDEX idx_links_item ON library_links(libraryItemKey);
```

You can link multiple copies of the same title (e.g. a 1080p and a 4K rip)
without conflict. Only one is ever `isActive` per item - setting a new
link active flips the others off in the same transaction, application-side
(same pattern as `ConfirmMatchUiState.magnetMatchId` being the single
source of truth for "which result is the magnet's match").

### No foreign keys, no cascade deletes - ever

Explicit, standing decision, not just "not yet": `libraryItemKey` columns
on `library_downloads` and `library_links` stay plain string columns with
no `@ForeignKey` constraint, no `onDelete = CASCADE`. Any cleanup of
orphaned rows (if an item is ever deleted) is application code's job, not
the database's. This matches the project's existing SQL conventions and is
a deliberate simplicity choice for a single-user local database - not
revisited by future ADRs unless explicitly reopened.

Concretely: `LibraryDao` gets one `@Transaction`-wrapped
`deleteLibraryItem(id)` that deletes the item plus its `library_downloads`
and `library_links` rows together, rather than leaving cleanup scattered
across call sites or relying on the DB to enforce it. FK/cascade earns its
keep when multiple write paths could delete a parent without knowing about
its children, or when the DB needs to enforce integrity because the app
layer can't be trusted - neither applies to a single-user, single-writer
local database with one delete entry point.

### Migration strategy: unchanged for now

`fallbackToDestructiveMigration(true)` stays as-is through this schema
change. Moving to real `Migration` objects (required once there's real
data worth preserving across an update) is explicitly deferred - a
separate decision to make later, not part of this ADR.

### Versioned export/import

New, independent of the DB schema/migration story above - the export
format has its own version number so future app versions can read old
exports even if the live DB schema has moved on.

- A manual **"Export library"** action (Settings) writes a single JSON
  file via Android's Storage Access Framework `CreateDocument` picker -
  the user chooses the destination, which can be a Google Drive-backed
  folder if the Drive app is installed (SAF surfaces any installed
  `DocumentsProvider`, including Drive) - **no direct Google Drive API
  integration needed or wanted**, keeps this fully personal-use/offline
  by default.
- Export payload:
  ```json
  {
    "exportVersion": 1,
    "exportedAtEpochMillis": 0,
    "libraryItems": [ ... ],
    "libraryDownloads": [ ... ],
    "libraryLinks": [ ... ]
  }
  ```
  `genres` and `sites` are deliberately excluded - genres self-heal from
  TMDB on any fresh install (`GenreRepository.ensureSynced`), and sites
  reseed from `SiteCatalog.defaultSites` (user-added/edited sites are
  arguably worth including in a later export version, but are left out of
  v1 to keep the first cut small - the export's own `exportVersion` field
  is exactly what allows adding that later without breaking old exports).
- **Import** reads `exportVersion`, dispatches to a version-specific
  parser, and upserts through the same merge-on-conflict path as a normal
  TMDB save (by `(tmdbId, mediaType)` when present, otherwise by matching
  manual entries on title - exact matching strategy for manual-entry
  import conflicts is left to implementation time, not fully specified
  here).
- This is a manual, user-triggered, point-in-time snapshot - not automatic
  sync, not scheduled backup. Matches "keep my curated content for later,
  maybe push it to Drive myself" rather than building any always-on backup
  infrastructure.

## Alternatives considered

- **Keep `tmdbId`/`mediaType` as part of the primary key, add a separate
  `manualId` fallback** - rejected: two different identity schemes for the
  same table is more special-casing than one synthetic id used uniformly.
- **`OnConflictStrategy.REPLACE` for `library_items` upserts** (current
  behavior before this ADR) - rejected once `library_links`/downloads
  exist, since REPLACE's delete-then-insert would orphan them.
- **FK constraints with `onDelete = CASCADE`** - explicitly rejected per
  user direction; not revisited.
- **Real `Migration` objects starting now** - deferred per user direction
  ("we will see about migration later"); destructive fallback stays until
  that's explicitly revisited.
- **Automatic cloud sync (Drive API, etc.)** - rejected for the same reason
  the app has no LLM API calls and isn't distributed via app stores:
  keep it local-first and personal; SAF's document-picker already reaches
  Drive without the app needing to know Drive exists.

## Consequences

- `LibraryDao`/`LibraryItem`/`LibraryDownload` all need rewriting for the
  new column set - `key` becomes `id`, `tmdbId`/`mediaType` become
  nullable, `toLibraryItem()`/`toMediaResult()` mappers need to handle the
  null cases.
- `MainActivity`'s `ROUTE_DETAIL = "detail/{key}"` and every navigation
  call sites using `item.key` need renaming to `item.id` - mechanical but
  touches several files (`HomeScreen`, `MainActivity`, `DetailScreen`).
- Every current `insert`/`upsert` call site for `library_items` needs to
  switch from blind upsert to the query-then-merge pattern described above.
- `library_downloads.infoHash`-based dedup calls (`magnetInfoHash()`)
  need a sibling `dedupeKey()` helper for the manual-entry (name+uri) case.
- Export/import needs a new `ExportRepository` (or similar) and a Settings
  screen entry - Settings is currently a placeholder stub (flagged as a
  gap in an earlier session), this is the first real feature landing there.
- Since destructive migration stays, this schema change itself will wipe
  any existing local data on the next `installDebug` - acceptable now
  (pre-release, no real data yet), but worth remembering this is the last
  "free" schema change before migrations start actually mattering.
