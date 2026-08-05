# Tasks: Phase 09 — Recommendation Engine

See `docs/RALPH.md` for how to run this list,
`docs/phases/09-recommendation-engine.md` for requirements, and
`docs/adrs/0002-on-device-embeddings-for-recommendations.md` for the
model/storage/algorithm decision.

- [ ] Integrate EmbeddingGemma-300M `.tflite` (litert-community) via LiteRT
      + DJL tokenizer for on-device query embedding
- [ ] Implement plain-SQLite BLOB-column vector table (title/overview
      embeddings), matching AI Edge RAG Library's `SqliteVectorStore`
      pattern
- [ ] Implement FTS5 virtual table for title/overview keyword search
- [ ] Implement streaming top-K min-heap cosine-similarity scan (see ADR
      0002 algorithm) against the vector table
- [ ] Implement unified mood/plot query resolution: embed query -> top-K
      scan -> derive per-genre scores from retrieved set (aggregate/
      normalize cosine similarity per genre tag)
- [ ] Implement RRF combination across: embedding-similarity rank, FTS5
      keyword-match rank, derived genre scores
- [ ] Integrate Phase 08 feedback signals into ranking (favor liked,
      suppress disliked)
- [ ] Benchmark on-device embedding + scan latency on a real Android device
</content>
