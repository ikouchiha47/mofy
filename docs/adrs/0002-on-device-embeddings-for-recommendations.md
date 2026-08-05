# ADR 0002: On-Device Embeddings for Recommendations (EmbeddingGemma + plain-SQLite vector store, no LLM API calls)

**Status:** accepted

## Context

Phase 09's recommendation engine needs to resolve free-text mood queries
("I am feeling quite bored", "feeling nostalgic") and free-text plot
queries ("a guy loses his memory every day and writes notes to himself")
against a local catalog of titles, producing multi-genre scores from
multiple combined signals — without any API-based LLM calls (explicit
constraint from the user, no exceptions).

A hand-coded mood-keyword-to-genre mapping table was considered and
rejected: natural-language mood sentences don't reduce reliably to a fixed
keyword table, and the failure modes are silent (wrong genre, no error).

## Decision

- Use **EmbeddingGemma-300M** (Google, MTEB-leading open embedding model
  under 500M params) as the sentence-embedding model, run via **LiteRT**
  (Google's on-device inference runtime) using the ready-made `.tflite`
  artifact at `litert-community/embeddinggemma-300m` on Hugging Face.
  Tokenization on-device via Deep Java Library (DJL), matching the model's
  reference Android integration path and Google's own **AI Edge RAG
  Library**, which is built around this exact model.
- The same model (via its `sentence-transformers`-compatible checkpoint) is
  used offline in the `ml/` Python pipeline to embed the catalog's overview
  text, so on-device query vectors and offline catalog vectors are
  comparable.
- Store embeddings as **plain SQLite BLOB columns** (float32 arrays, ~3KB
  per 768-dim vector), following the same pattern as the AI Edge RAG
  Library's own `SqliteVectorStore` reference implementation — a normal
  `SQLiteOpenHelper`-backed table, no native extension loading required.
  Store title/overview text in a separate **FTS5** virtual table (a
  standard, bundled SQLite feature, no extension needed) for keyword/BM25
  retrieval as a second signal.
- Retrieval against the vector table is an **exact brute-force scan**,
  implemented as a **streaming top-K min-heap** rather than materializing
  the whole catalog into memory — see Algorithm below. At personal-library
  scale (low thousands of vectors, tens of MB) this is fast and simple; no
  ANN index needed.
- Mood and plot queries are resolved through the **same mechanism**:
  embed the query, retrieve nearest-neighbor overviews via the streaming
  top-K scan, and **derive per-genre scores from the retrieved set**
  (aggregate/normalize cosine similarity across each genre tag present in
  the top-N matches), rather than classifying the query into genres
  directly. This is what produces "multiple genres with scores from
  different signals" without a keyword table or an LLM call — the genre
  scores emerge from retrieval.
- Both the embedding-derived genre scores and the FTS5 keyword signal feed
  the RRF ranking already planned in Phase 09.

## Algorithm: streaming top-K cosine similarity

Avoids loading the full catalog into memory at once. Peak memory is
`O(k)` (the heap) plus one decoded row, not `O(catalog size)`.

```
function topKSimilar(queryVector, k, cursor):
    heap = MinHeap(capacity = k)   # ordered by similarity score, smallest on top

    while cursor.moveToNext():
        row = cursor.currentRow()
        vector = decodeFloatBlob(row.embeddingBlob)
        score = cosineSimilarity(queryVector, vector)

        if heap.size < k:
            heap.push((score, row.id, row.genres))
        else if score > heap.peekMin().score:
            heap.popMin()
            heap.push((score, row.id, row.genres))
        # else: discard immediately, row never enters the heap

    return heap.drainSortedDescending()   # k best matches, ranked
```

- `cosineSimilarity` is a plain dot-product over unit-normalized vectors
  (normalize once at embed time so query time is just a dot product).
- `k` should be sized for what the UI actually needs to rank against (e.g.
  50), not for the final displayed result count — the caller trims further
  after RRF combination with the FTS5 signal.
- Genre-score derivation runs over the same drained top-K set: for each
  genre tag appearing across those `k` items, sum (or average) the
  similarity scores of items carrying that tag, then normalize.

## Alternatives considered

- **Cloud embedding API** (OpenAI/Voyage/etc.) — rejected: no API-based LLM
  calls, full stop, per explicit user constraint.
- **LLM chat call to expand/classify mood queries** — rejected for the same
  reason; also unnecessary once retrieval-then-aggregate genre scoring
  works directly off embeddings.
- **Hand-coded mood → genre keyword table** — rejected: doesn't generalize
  to arbitrary natural language, fails silently.
- **MiniLM (`all-MiniLM-L6-v2`) via ONNX Runtime Mobile** — considered, but
  EmbeddingGemma-300M outperforms it on MTEB and has a first-party Android
  deployment path (LiteRT + ready `.tflite` + DJL tokenizer + Google AI Edge
  Gallery reference apps) rather than a DIY ONNX export/conversion.
- **`sqlite-vec` extension (`vec0` virtual table)** — considered, but it
  requires loading a native SQLite extension, which Room's default
  `SupportSQLiteOpenHelper` doesn't support (would need a custom driver).
  Google's own AI Edge RAG Library sidesteps this with a plain
  `SQLiteOpenHelper` + BLOB-column vector store, which does the same
  brute-force job with less integration risk, since we're already bringing
  in that library for EmbeddingGemma inference.
- **On-device FAISS/ANN index** — rejected: unnecessary complexity at
  personal-library scale (hundreds to low thousands of titles); a streaming
  brute-force top-K scan is fast enough and simpler to reason about.

## Consequences

- Phase 06's local library layer owns two plain SQLite structures: an
  FTS5 table (title/overview text) and a BLOB-column vector table
  (embeddings) — both usable through a normal `SupportSQLiteOpenHelper`/Room
  setup, no custom native-extension-loading driver needed.
- The `ml/` Python pipeline and the Android app must stay on the same
  embedding model/version — if the model is ever upgraded, all cached
  catalog vectors need re-embedding, not just new ones.
- No network dependency and no per-query cost for recommendation queries —
  everything after the initial catalog build runs fully offline/on-device.
- Worth a real-device latency benchmark once building Phase 09 — Google's
  published sub-25ms figure is an EdgeTPU benchmark, not a guarantee on
  arbitrary Android phone CPUs.
</content>
