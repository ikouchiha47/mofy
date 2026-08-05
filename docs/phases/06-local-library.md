# Phase 06: Local Library + Metadata Storage

**Depends on:** 03, 05

## Goal

The merge point for both content-acquisition paths (torrent download and
manual import): a persistent local store of library items with their
matched TMDB metadata.

## Requirements (EARS)

- The system SHALL persist library items locally, each with: title, TMDB
  id, media type (movie/tv), genres, overview, poster path, file path(s),
  and source (downloaded vs. imported).
- WHEN a download (Phase 03) completes, the system SHALL create or update a
  library entry for it.
- WHEN a manual import (Phase 05) is confirmed, the system SHALL create or
  update a library entry for it.
- The system SHALL allow a library entry to exist without a local file
  present yet (e.g. a to-watch/collection placeholder used by Phase 11).
- WHEN the user removes an item from the library, the system SHALL record
  it as a "not-interested"/dislike signal rather than only deleting it
  silently.
- The system SHALL provide a plain SQLite BLOB-column table for storing
  catalog title/overview embeddings (float32 vectors), and a separate FTS5
  virtual table for title/overview keyword search — both accessible through
  a normal `SupportSQLiteOpenHelper`/Room setup, with no native
  SQLite-extension loading required (see
  [[0002-on-device-embeddings-for-recommendations]]). These tables are
  consumed by Phase 09.
</content>
