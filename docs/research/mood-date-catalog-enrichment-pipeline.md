# Mood + date catalog enrichment and dual-path retrieval

**Status:** design notes (not an ADR yet)  
**Related:** [[0002-on-device-embeddings-for-recommendations]], [[0007-sqlite-vec-for-embedding-storage]], [[0008-multi-aspect-query-understanding-layer]], Phase 09  
**Constraint:** no API-based LLM calls in the app; offline teacher LM (e.g. local Qwen3.5 via Ollama) is OK for catalog build only.

## Problem

Grazer queries mix structured constraints and fuzzy vibe language:

| Query | Structured | Fuzzy |
|---|---|---|
| `90s highly rated action` | date, rating, genre | — |
| `feeling nostalgic` | — | mood |
| `something dark from the 2000s` | date | mood |
| `a guy loses his memory every day…` | — | plot residual |

Public datasets of search-box → facet labels are effectively missing. Emotion lexicons (NRC VAD) and diary emotion corpora are the wrong domain. MovieLens Tag Genome is useful as **movie-side** tag vocabulary, not as query labels.

**Strategy:** move the hard labeling offline onto the catalog; keep on-device path = rules/NER for structured fields + EmbeddingGemma for mood/plot.

## What is *not* this pipeline

- Not reinforcement learning (no rewards/policies).
- Not shipping a generative SLM on-device for MVP mood tagging.
- Not training on raw emotion-diary sentences or VAD random words.

Names that *do* apply: offline multi-label tagging, optional supervised head, dual-path retrieval (enriched doc sim + mood-bank sim), rule/NER date extraction.

---

## Fixed vocabularies

### Mood list `MOODS` (~80–120)

Exhaustive, closed set. Sources to build it (once, curated):

1. Persona / eval fixtures (`feeling nostalgic`, `cozy`, `dark and gritty`, …)
2. Hand aesthetic list (`noir`, `cyberpunk`, `cottagecore`, …)
3. Filtered MovieLens Tag Genome tags (keep vibe/tone; drop `airplane`, cast names, pure objects)

Teacher LM and classifiers may **only** emit labels from this set.

### Date is not a free vocab — it is structured

Catalog already has `startYear`. Date on the **query** side is extracted as a predicate, not as a mood-like tag.

Supported query shapes (non-exhaustive):

| Surface form | Normalized predicate |
|---|---|
| `2010`, `released in 1999` | `year == 2010` / `year == 1999` |
| `90s`, `the nineties`, `from the 2000s` | `year ∈ [1990, 1999]` etc. |
| `pre-2000`, `after 2015` | open range |
| `80s or 90s` | union of ranges |

**Do not** ask embeddings to “understand” years as hard filters (EmbeddingGemma misfiles mood vs date in probes). Date = rules / OpenNLP date NER / small regex lexicon (Track C1).

Optional soft temporal prior (ADR 0008): if confidence is low, boost rather than hard-filter. High-confidence parses stay hard filters.

---

## Architecture overview

```
OFFLINE (laptop)
  catalog.db
      │
      ├─ teacher LM (Qwen3.5+) ──► mood labels on stratified sample
      ├─ EmbeddingGemma         ──► weak moods on rest; mood bank vecs; doc vecs
      ├─ optional linear head   ──► trained on teacher labels, applied to all
      └─ startYear (already)    ──► no LM needed for date on documents
      │
      ▼
  catalog_with_moods.db
      • movie_moods(movie_id, mood, weight)
      • doc_vec (path A: title|genres|moods|overview)
      • mood_bank.vecs (one vector per MOODS entry)
      • structured columns unchanged (startYear, genres, rating, …)

ON-DEVICE (Android)
  query
      ├─ rules/NER  → date / genre / rating / popularity / runtime facets
      ├─ strip facets → residual text
      ├─ embed residual → path A (doc_vec) + path B (mood bank → movie weights)
      └─ RRF(+ FTS) under hard filters (incl. date ranges)
```

Path **A**: single enriched document embedding.  
Path **B**: query ↔ mood-bank similarity, then movies scored by stored mood weights.  
**Date**: hard (or soft) filter from structured extraction — not from mood bank.

---

## Offline pipeline (pseudocode)

```text
MOODS = load_list("mood_vocab.txt")          # closed, ~80–120
TEACHER = Ollama("qwen3.5:4b")               # or larger; offline only
EMBED   = EmbeddingGemma300M()               # same checkpoint as on-device

# ----------------------------------------------------------
# 0. Embed mood bank (path B), once per vocab version
# ----------------------------------------------------------
mood_vecs = {}
for m in MOODS:
    mood_vecs[m] = EMBED.encode(f"movie mood: {m}")   # fixed template
save("mood_bank.vecs", mood_vecs)

# ----------------------------------------------------------
# 1. Teacher labels stratified sample (gold-ish moods)
# ----------------------------------------------------------
def teacher_label(movie) -> list[str]:
    prompt = """
    Assign moods from ALLOWED_MOODS only.

    ALLOWED_MOODS = {MOODS}
    Title / Year / Genres / Overview = ...

    Pick 3–7 moods that truly fit. No invented labels.
    JSON: {"moods": [...], "confidence": 0.0-1.0}
    """
    data = parse_json(TEACHER.generate(prompt, temperature=0.2))
    return unique([m for m in data.moods if m in MOODS])

movies = load_catalog("catalog.db")   # prefer non-empty overview
sample = stratified_sample(movies, n=5000, by=["primary_genre", "decade"])

labels = {}  # id -> {moods, source, conf}
for movie in sample:
    moods = teacher_label(movie)
    if len(moods) >= 2:
        labels[movie.id] = {"moods": moods, "source": "teacher", "conf": 1.0}

# optional: human spot-check ~200 rows → source = "human"

# ----------------------------------------------------------
# 2. Weak moods for the rest via embedding (no LM)
# ----------------------------------------------------------
def embed_doc(movie):
    return EMBED.encode_document(
        f"title: {movie.title} | genres: {movie.genres} | text: {movie.overview}"
    )

def weak_label(movie, top_k=5, min_score=0.35) -> list[str]:
    doc = embed_doc(movie)
    ranked = sorted(
        ((m, cosine(doc, mood_vecs[m])) for m in MOODS),
        key=lambda kv: -kv[1],
    )
    return [m for m, s in ranked[:top_k] if s >= min_score]

for movie in movies:
    if movie.id in labels:
        continue
    moods = weak_label(movie)
    if moods:
        labels[movie.id] = {"moods": moods, "source": "embed_weak", "conf": 0.5}

# ----------------------------------------------------------
# 3. Optional: multi-label head on teacher subset
#    (supervised learning — not RL)
# ----------------------------------------------------------
# Data size ballpark: ~2k min, ~5k sweet spot, ~10k comfortable
# ~50–200 positives per mood label; freeze embedder; sigmoid |MOODS|

def train_mood_head(teacher_rows):
    X, Y = [], []
    for row in teacher_rows:
        movie = get(row.id)
        X.append(embed_doc(movie))                 # frozen 768-d
        Y.append(multi_hot(row.moods, MOODS))
    head = LinearOrMLP(in=768, out=len(MOODS))
    train(head, X, Y, loss="bce")
    save("mood_head.pt", head)
    return head

head = train_mood_head(...) if teacher_count >= 2000 else None

# ----------------------------------------------------------
# 4. Final per-movie moods + index payloads (A + B)
#    Date on documents = startYear already in catalog (no labeling)
# ----------------------------------------------------------
def final_moods(movie) -> list[str]:
    if movie.id in labels and labels[movie.id].source in ("human", "teacher"):
        return labels[movie.id].moods
    if head is not None:
        probs = sigmoid(head(embed_doc(movie)))
        picked = [m for m, p in zip(MOODS, probs) if p >= 0.4]
        if 2 <= len(picked) <= 8:
            return picked
    return weak_label(movie)

for movie in movies:
    moods = final_moods(movie)

    # Path A — enriched single vector (moods in text; year optional cue only)
    doc_text = (
        f"title: {movie.title} | year: {movie.startYear} | "
        f"genres: {movie.genres} | moods: {', '.join(moods)} | "
        f"text: {movie.overview}"
    )
    doc_vec = EMBED.encode_document(doc_text)

    # Path B — sparse weights over mood bank
    mood_weights = {m: 1.0 for m in moods}   # or head probs / cosines

    write_row(
        movie_id=movie.id,
        startYear=movie.startYear,           # structured date field
        genres=movie.genres,
        moods=moods,
        mood_weights=mood_weights,
        doc_vec=doc_vec,
    )
```

### New titles later

Same assignment stack — still not RL:

1. Prefer head if trained, else teacher once, else weak embed.  
2. Re-embed path-A `doc_text`.  
3. Upsert `movie_moods` + `doc_vec`.  
4. `startYear` comes from metadata ingest (IMDb/TMDB), never from the mood model.

---

## Query-side: date + mood together

### Facet extraction (on-device, deterministic first)

```text
def extract_facets(query) -> Facets:
    date = date_rules_or_opennlp(query)     # ranges / year equality
    genre = lexicon_match(query, genre_list)
    rating = phrases like "highly rated", "acclaimed"
    popularity = "hidden gem", "underrated", ...
    runtime = "short", "quick watch", ...
    # mood is NOT forced into a brittle lexicon-only path
    return Facets(date=date, genre=genre, ...)

def residual_text(query, facets) -> str:
    # strip spans consumed by high-confidence structured facets
    # leave mood/plot language for embeddings
    ...
```

Date examples → predicates:

```text
"90s"              -> Range(1990, 1999)
"from the 2000s"   -> Range(2000, 2009)
"2012"             -> Eq(2012)
"something nostalgic from the 2000s"
                   -> date=Range(2000,2009), residual≈"something nostalgic"
```

### Dual-path retrieval + date filter

```text
def resolve(query, k=50):
    facets = extract_facets(query)
    residual = residual_text(query, facets) or query
    qv = EMBED.encode_query(residual)

    # Path A: enriched overview(+moods) vectors
    hits_a = vec0_search(qv, k=k)

    # Path B: query → mood bank → movies by mood_weights
    mood_sims = {m: cosine(qv, mood_vecs[m]) for m in MOODS}
    top_moods = top_n(mood_sims, n=5)
    hits_b = score_movies_by_mood_overlap(top_moods, k=k)

    hits_fts = fts(query)

    ranked = rrf([hits_a, hits_b, hits_fts])

    # Date (and other structured facets) as hard filters when confident
    if facets.date is high_confidence:
        ranked = [h for h in ranked if facets.date.matches(h.startYear)]
    elif facets.date is soft_prior:
        ranked = boost_by_year(ranked, facets.date)

    if facets.genre: ...
    return ranked
```

**Critical split:**  
- **Date** → filter/boost on `startYear`  
- **Mood** → paths A/B on residual text  
Never collapse “nostalgic” into a year bucket via embeddings alone.

---

## Optional: synthetic query training data (teacher)

Used only if training a small query→facet model later. Templates cover date/genre/rating without an LM.

```text
query_train = []

# Structured (no LM) — labels known by construction
for row in sample_catalog(n=3000):
    query_train += [
        (f"{decade_phrase(row.year)} {row.genre} movies",
         spans=[date, genre]),
        (f"{row.genre} movies from {row.year}",
         spans=[genre, date]),
        (f"highly rated {row.genre} from the {decade_phrase(row.year)}",
         spans=[rating, genre, date]),
    ]

# Mood paraphrases (teacher)
for seed in MOODS + persona_mood_seeds:
    for q in teacher_paraphrase_movie_queries(seed):  # 3–5 short search strings
        query_train.append((q, spans=[mood]))

# Mixed mood + date (teacher or templates)
for row in sample_catalog(n=500):
    m = random.choice(final_moods(row) or MOODS)
    q = f"something {m} from the {decade_phrase(row.year)}"
    query_train.append((q, spans=[mood, date]))

save_jsonl("query_spans.jsonl", query_train)
```

MVP can skip the query classifier entirely: **rules for date/genre/… + embeddings for residual**.

---

## Dataset size (if training the movie→mood head)

| Regime | Labeled movies (teacher/human) |
|---|---|
| Probe | 200–500 |
| Minimum usable | ~2k |
| Sweet spot | **~5k** stratified |
| Comfortable | ~8–10k |

Roughly 50–200 positives per mood. Rare moods: merge or drop until supported. Full catalog (~32k) is **inferred**, not hand-labeled.

Query facet data (if ever): ~2–5k queries, mostly templates + mood paraphrases.

---

## Storage sketch

```text
mood_vocab(mood TEXT PRIMARY KEY)

movie_moods(
  movie_id TEXT,
  mood TEXT REFERENCES mood_vocab,
  weight REAL,
  source TEXT,          -- human | teacher | head | embed_weak
  PRIMARY KEY (movie_id, mood)
)

-- existing / planned
catalog_items(..., startYear INTEGER, genres TEXT, ...)
vec0 / embedding table: doc_vec from enriched document text
mood_bank: |MOODS| vectors, bundled asset or table
```

Date never needs a parallel “date tag” table; `startYear` is the field.

---

## Stage order

1. Freeze `MOODS` vocab (fixtures + aesthetics + filtered Tag Genome).  
2. Embed mood bank with EmbeddingGemma.  
3. Teacher-label ~1k probe; manual quality pass.  
4. Scale teacher to ~5k stratified by genre/decade.  
5. Weak-label remainder with embedding similarity.  
6. Optional: train mood head on teacher set; relabel full catalog.  
7. Write `movie_moods` + enriched `doc_vec` (include year in doc text as soft cue only).  
8. On-device: date/genre/… rules + residual embed + RRF under date filters.  
9. Optional later: query span model; on-device SLM (ADR 0008 Stage 2).

---

## Rejected / weak data sources (for this pipeline)

| Source | Why weak here |
|---|---|
| NRC VAD as mood seeds | Affect norms, not search language; random sample is junk |
| Emotion diary corpora (CARER-style) | Writer emotion ≠ desired film vibe; wrong domain |
| `movies-nl2json-15m` | Not a usable public dataset (unavailable / unverified) |
| movie-search-ml20 | Known-item “what’s that title?” plots; not facet search |
| Tag Genome folds/predictions | Paper CV artifacts; use `scores/*` + tag strings only |

Useful: Tag Genome **scores** for vocab + optional weak movie–tag priors; catalog templates for date/genre query text; teacher LM closed-vocab labeling.

---

## Success checks

- Date queries (`90s`, `2010`, `from the 2000s`) never depend on mood bank alone.  
- `feeling nostalgic` does not become a year hard-filter.  
- `something dark from the 2000s` → date range AND dark-ish residual hits.  
- New catalog titles get moods without RL: head or embed or one-shot teacher.  
- On-device artifact budget stays dominated by EmbeddingGemma (≤500MB class), not a second generative LM for MVP.

---

## Open choices (confirm before ADR)

- Hard vs soft date when parse confidence is medium.  
- Exact `MOODS` size and naming (align with UI chips or keep invisible).  
- Whether path-A doc text includes `year:` (soft cue) or omits it to avoid date bleed into vectors.  
- Teacher model size (4b vs larger) vs label budget.  
- Promote this doc to ADR once probe metrics on ~1k teacher labels look good.
