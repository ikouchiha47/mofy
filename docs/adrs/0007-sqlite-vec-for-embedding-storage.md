# ADR 0007: sqlite-vec (vec0) for embedding storage, superseding ADR 0002's plain-BLOB vector store

**Status:** accepted

## Context

ADR 0002 chose plain SQLite BLOB columns + a hand-rolled streaming top-K
min-heap scan for Phase 09's embedding vector store, explicitly rejecting
`sqlite-vec`'s `vec0` virtual table because "Room's default
`SupportSQLiteOpenHelper` doesn't support [loading a native SQLite
extension]... would need a custom driver" — judged not worth the
integration risk at the time.

Since then, Phase 06's local library layer already built exactly that
custom driver: `AppDatabase.kt` loads `sqlite-vec` (`libvec0` /
`sqlite3_vec_init`) via a custom `SupportSQLiteOpenHelper`, for other
native-extension needs (see `docs/research/native-sqlite-extensions-android.md`).
The specific obstacle ADR 0002 cited to reject `sqlite-vec` no longer
applies — the driver work is already paid for.

## Decision

Use `sqlite-vec`'s `vec0` virtual table as Phase 09's embedding vector
store, instead of ADR 0002's plain BLOB-column table + hand-rolled
streaming top-K min-heap scan.

- Catalog title/overview embeddings (float32, EmbeddingGemma-300M's
  768-dim output — unchanged from ADR 0002) are stored in a `vec0` virtual
  table, one row per library item.
- Nearest-neighbor retrieval for mood/plot queries uses `vec0`'s built-in
  `MATCH`/distance-ordered query support instead of a hand-rolled
  streaming min-heap scan in Kotlin.
- Genre-score derivation (aggregate/normalize cosine similarity per genre
  tag across the retrieved top-K set) is unchanged from ADR 0002 — this
  ADR only replaces the storage/retrieval mechanism, not the
  retrieval-then-aggregate scoring approach.
- FTS4 keyword search (`LibrarySearchEntity`, already built) is unchanged
  and continues to feed the RRF ranking alongside the new vec0-based
  embedding signal.
- Everything else in ADR 0002 stands: EmbeddingGemma-300M via LiteRT +
  DJL tokenizer, on-device only, no API-based LLM/embedding calls, same
  model version between the `ml/` offline pipeline and on-device query
  embedding.

## Alternatives considered

- **Keep ADR 0002's plain BLOB + hand-rolled scan** — rejected: now that
  the native-extension-loading driver already exists in this codebase for
  other purposes, reimplementing top-K cosine similarity by hand is extra
  code solving a problem `vec0` already solves natively, with no
  remaining integration-risk justification.
- **On-device FAISS/ANN index** — still rejected, same reasoning as ADR
  0002: unnecessary complexity at personal-library scale (hundreds to low
  thousands of titles); `vec0`'s exact/brute-force-equivalent search is
  fast enough at this scale without approximate-nearest-neighbor indexing.

## Consequences

- Phase 06/09's vector table becomes a `vec0` virtual table rather than a
  plain Room entity with a BLOB column — schema and DAO code differ from
  what ADR 0002/the Phase 09 task list originally sketched; those docs
  need updating alongside this ADR.
- One less hand-written algorithm (the streaming top-K min-heap scan) to
  maintain and unit-test; `vec0`'s query planner does that work.
- Both `sqlite-vec` extensions now loaded via the same custom driver path
  established in Phase 06 — no additional native-extension-loading risk
  introduced beyond what's already shipping.
- ADR 0002 remains accepted for everything except the vector-store
  mechanism, which this ADR supersedes.
</content>
