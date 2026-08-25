# mofy-ml: offline catalog-building pipeline

Builds `catalog.db`, a bundled SQLite catalog of IMDb titles (genre/rating/
year/votes, plus overview text where available) for a future Discover/
recommendation surface. Structured-fields-only for now - see "Deferred:
embeddings" below for why.

## Pipeline

```
01_filter_imdb.py          IMDb non-commercial datasets -> filtered_titles.parquet
02_join_kaggle_overview.py + Kaggle overview text (free, static)  -> catalog_titles.parquet
03_enrich_tmdb_gaps.py     + live TMDB calls for what Kaggle missed (rate-limited, resumable)
04_export_sqlite.py        -> catalog.db (bundled by the Android app)
```

Run from the repo root: `uv run --project ml python ml/scripts/01_filter_imdb.py`, etc.

### 01_filter_imdb.py

Downloads (manually, into `ml/data/`) and filters `title.basics.tsv.gz` +
`title.ratings.tsv.gz` from https://datasets.imdbws.com/. Current
thresholds: `startYear >= 1990`, `averageRating >= 6.5`, `numVotes >= 500`
(IMDb ratings are out of 10, not 5 - 6.5 sits above the ~6.58 mean of the
unfiltered post-1990 set; the vote floor exists because a handful of
voters can produce an unreliably high rating otherwise). Produces
`filtered_titles.parquet` - **31,785 titles** as of 2026-08-09 (21,308
movies, 10,477 TV series).

### 02_join_kaggle_overview.py

Joins in overview text from Kaggle's ["The Movies Dataset"](https://www.kaggle.com/datasets/rounakbanik/the-movies-dataset)
(`movies_metadata.csv`, manually downloaded - requires a Kaggle account,
extract into `ml/data/kaggle_movies/`). Free, zero API calls, but capped:
it's **movies-only** (no TV series at all) and was scraped in 2019, so
coverage falls off a cliff exactly there - 54% for 2016, 13% for 2017,
~0% from 2018 on. Baseline coverage after this step: **9,452 / 31,785
(29.7%)**.

### 03_enrich_tmdb_gaps.py

Fills what Kaggle can't: TV series (zero Kaggle coverage regardless of
year) and 2018+ movies, via live TMDB `/find?external_source=imdb_id`
calls. Async, concurrency-limited (12 concurrent - TMDB's real current
ceiling is ~40 req/sec informally per
https://developer.themoviedb.org/docs/rate-limiting, not the old 40-per-
10-seconds limit disabled in Dec 2019), and checkpointed to
`ml/data/tmdb_checkpoint.db` (one commit per resolved title) so an
interrupted run resumes instead of restarting. Retries transient network
errors (observed in this environment) separately from TMDB's own `429`
throttling.

Scope flags: `--year-min` (default 2018), `--year-max` (default none),
`--limit` (validation batches before committing to a full run).

**Run so far**:
- `--year-min 2020 --year-max 2026` (9,197 titles, ~6 min, 94.0% hit rate)
- `--year-min 2018 --year-max 2019 --type movie` (1,973 titles, ~75s, closed
  out the rest of the 2018+ movie gap)

11,225 titles checkpointed total, 94.9% hit rate, zero errors across both
runs. **Not yet run**: pre-2020 TV series (~10,450 titles) - deliberately
deferred, not a current priority (movies are the focus right now). Re-run
with `--type tv` whenever that's picked back up; the checkpoint means
already-fetched titles won't be re-requested.

### 04_export_sqlite.py

Merges `catalog_titles.parquet` (Kaggle) with `tmdb_checkpoint.db`
(whatever's been enriched so far - safe to re-run this at any point, even
mid-enrichment) and writes `catalog.db`:

- `catalog_items` - structured fields for every title (title, genres,
  year, rating, numVotes) - works for **all 31,785 titles today**,
  regardless of overview coverage. Genre/rating/year filtering and Discover-
  style browsing don't need overview text at all.
- `catalog_fts` - FTS4 keyword search over title + overview, for the
  subset with overview text (currently 18,153 / 31,785 = 57.1%).

## Current state (2026-08-09)

31,785 titles total. 20,102 (63.2%) have overview text - effectively all
movies at this point, plus whatever TV series Kaggle happened to catch;
the rest have full structured metadata (genre/rating/year/votes) but an
empty overview. Remaining gap is almost entirely pre-2020 TV series
(~10,450 titles), deliberately deferred - see "Deferred" below.

## Data sources considered and rejected

| Source | Why not |
|---|---|
| Stanford "Large Movie Review Dataset" (`imdb_reviews` on TFDS / Kaggle `IMDB Dataset.csv`) | Sentiment-labeled user reviews, not plot summaries - and critically, **no title/IMDb ID column at all**. Reviews are deliberately anonymized per movie for the sentiment-classification task they were built for; there's nothing to join against `tconst`. |
| `Pablinho/movies-dataset` (Hugging Face, ~9,848 rows) | TMDB-sourced but **no stable ID** (just `Title`/`Release_Date`) - would require fuzzy title+year matching, unreliable for a data pipeline. Also small and skews toward popular 2020s titles. |
| DBpedia (SPARQL, `dbo:imdbId` + `rdfs:comment`) | Has real IMDb-ID linkage in principle, but the public endpoint hit a genuine `503 "License has expired"` error during evaluation - a real, apparently-recurring reliability problem, not a one-off. An ID lookup also resolved to the wrong film's redirect page on one test. Snapshot-based (periodic Wikipedia dumps), not live, so recency is also questionable. |

TMDB (`03_enrich_tmdb_gaps.py`) and Kaggle's static export remain the two
real, legitimate sources - see above.

## Deferred: embeddings / vector search

`phase09_embed.py` and `phase09_export_with_vectors.py` exist but aren't
run - they need the EmbeddingGemma model actually wired into the Android
app first (see `docs/adrs/0002-on-device-embeddings-for-recommendations.md`),
which hasn't been built yet. Building catalog vectors now would be idle
work with no consumer. `phase09_enrich_tmdb.py` doesn't exist separately
any more - `03_enrich_tmdb_gaps.py` *is* the TMDB enrichment step, just
scoped to fill Kaggle's gaps rather than enrich the whole catalog from
scratch, and it's already been run (partially) since it turned out to be
~30 minutes of work, not the ~20+ hours a stale rate-limit assumption
originally implied.
