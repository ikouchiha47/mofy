# Phase 09: Recommendation Engine

**Depends on:** 01, 06, 08

## Goal

Mood-based and plot-based recommendations, ranked by more than raw rating,
fed by the like/dislike signals from Phase 08. See
[[0002-on-device-embeddings-for-recommendations]] for the model/storage
decision behind this phase.

## Requirements (EARS)

- The system SHALL embed free-text queries on-device using EmbeddingGemma-300M
  via LiteRT — no API-based LLM calls of any kind (chat or embedding).
- The system SHALL store catalog title/overview embeddings as plain SQLite
  BLOB columns (float32 vectors), following the AI Edge RAG Library's
  `SqliteVectorStore` pattern — no native SQLite extension required.
- The system SHALL store catalog title/overview text in an FTS5 virtual
  table for keyword/BM25 retrieval as a second signal.
- The system SHALL retrieve nearest-neighbor overviews via a streaming
  top-K min-heap scan (see [[0002-on-device-embeddings-for-recommendations]]
  for the algorithm) rather than materializing the full catalog into
  memory.
- The system SHALL accept a free-text mood query (e.g. "I am feeling quite
  bored", "I am feeling loved", "suggest some thrilling movies", "feeling
  nostalgic") and a free-text plot query (e.g. "a guy loses his memory every
  day and writes notes to himself") through the **same resolution
  mechanism**: embed the query, retrieve nearest-neighbor overviews via the
  top-K scan.
- WHEN a query is resolved via nearest-neighbor retrieval, the system SHALL
  derive per-genre scores by aggregating/normalizing cosine similarity
  across genre tags present in the retrieved set, rather than classifying
  the query into genres via a fixed keyword table.
- The system SHALL rank final results using a combined relevancy score
  (reciprocal rank fusion) across: embedding-similarity rank, FTS5
  keyword-match rank, and derived genre scores — not rating alone.
- The system SHALL incorporate feedback signals from Phase 08 into ranking,
  so liked genres/titles are favored and disliked ones are suppressed.
- The system SHALL NOT require results to be verified as available on
  torrent sites — the user can manually check availability via webview
  (Phase 02).
- The offline catalog-embedding pipeline (`ml/`) and the on-device query
  embedding SHALL use the same embedding model/version so vectors are
  directly comparable.
</content>
