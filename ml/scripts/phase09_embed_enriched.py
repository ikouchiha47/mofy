"""Embed catalog with enriched document template and write vec0 table to catalog.db.

Schema changes:
  - Creates catalog_plots(tconst, plots TEXT) — JSON array of IMDB plot strings
  - Updates catalog_items.overview where it is empty or shorter than the best IMDB plot

Document template (per title):
  "title: {title} | genre: {genres} | year: {year} | text: {combined_overview}"

  combined_overview = top-2 longest texts from [tmdb_overview, imdb_plot_0, imdb_plot_1, ...]
  joined with " " — gives the model richer signal without ballooning token count.

Usage:
  uv run python scripts/phase09_embed_enriched.py
"""

import json
import sqlite3
from pathlib import Path

import sqlite_vec
from sentence_transformers import SentenceTransformer

CATALOG_DB = "data/catalog.db"
VEC_DB = "data/catalog_vec.db"
IMDB_PLOTS = "data/imdb_plots.jsonl"
MODEL_NAME = "google/embeddinggemma-300m"
DIM = 256  # MRL truncation: 768 → 256, 3x smaller, officially supported

DOCUMENT_TEMPLATE = "title: {title} | genre: {genres} | year: {year} | text: {text}"
QUERY_TEMPLATE = "task: search result | query: {text}"


def load_imdb_plots() -> dict[str, list[str]]:
    """tconst -> list of plot strings (all of them, caller picks)"""
    plots: dict[str, list[str]] = {}
    p = Path(IMDB_PLOTS)
    if not p.exists():
        print(f"[WARN] {IMDB_PLOTS} not found, skipping IMDB plots")
        return plots
    with open(p) as f:
        for line in f:
            r = json.loads(line)
            imdb_id = r.get("imdbID")
            plot_list = [p.strip() for p in (r.get("plots") or []) if p.strip()]
            if imdb_id and plot_list:
                plots[imdb_id] = plot_list
    print(f"Loaded IMDB plots for {len(plots)} titles")
    return plots


def open_catalog(path: str) -> sqlite3.Connection:
    con = sqlite3.connect(path)
    con.enable_load_extension(True)
    sqlite_vec.load(con)
    con.enable_load_extension(False)
    return con


def pick_top2(candidates: list[str]) -> str:
    """Pick the 2 longest non-empty strings and join them."""
    ranked = sorted((s for s in candidates if s), key=len, reverse=True)
    return " ".join(ranked[:2])


def main() -> None:
    con = open_catalog(CATALOG_DB)

    # 1. create and populate catalog_plots (skip if already done)
    already_populated = con.execute(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='catalog_plots'"
    ).fetchone()[0]

    if not already_populated:
        imdb_plots = load_imdb_plots()
        con.execute("""
            CREATE TABLE catalog_plots (
                tconst TEXT PRIMARY KEY,
                plots  TEXT NOT NULL DEFAULT '[]'
            )
        """)
        con.executemany(
            "INSERT OR REPLACE INTO catalog_plots (tconst, plots) VALUES (?, ?)",
            [(tconst, json.dumps(plots)) for tconst, plots in imdb_plots.items()],
        )
        con.commit()
        print(f"  {len(imdb_plots)} rows written to catalog_plots")

        # 2. update catalog_items.overview where empty or shorter than best IMDB plot
        rows = con.execute("SELECT tconst, overview FROM catalog_items").fetchall()
        updates = []
        for tconst, tmdb_overview in rows:
            tmdb = (tmdb_overview or "").strip()
            best_imdb = max(imdb_plots.get(tconst, [""]), key=len)
            if not tmdb or (best_imdb and len(best_imdb) > len(tmdb)):
                updates.append((best_imdb, tconst))
        if updates:
            con.executemany("UPDATE catalog_items SET overview=? WHERE tconst=?", updates)
            con.commit()
            print(f"  Updated {len(updates)} catalog_items.overview entries with better IMDB text")
    else:
        print("catalog_plots already populated, skipping prefill")

    # 4. build enriched documents — pick top-2 longest from [tmdb, imdb_0, imdb_1, ...]
    # join catalog_items with catalog_plots so we don't need imdb_plots.jsonl at runtime
    rows = con.execute("""
        SELECT ci.tconst, ci.title, ci.startYear, ci.genres, ci.overview,
               COALESCE(cp.plots, '[]') AS plots_json
        FROM catalog_items ci
        LEFT JOIN catalog_plots cp ON cp.tconst = ci.tconst
        ORDER BY ci.numVotes DESC
    """).fetchall()

    docs = []
    for tconst, title, year, genres, overview, plots_json in rows:
        imdb_plots_list = json.loads(plots_json)
        candidates = [(overview or "").strip()] + imdb_plots_list
        combined = pick_top2(candidates)
        if not combined:
            continue
        year_str = str(year) if year else "unknown"
        genres_str = genres if genres else "unknown"
        text = DOCUMENT_TEMPLATE.format(
            title=title, genres=genres_str, year=year_str, text=combined
        )
        docs.append((tconst, title, text))

    print(f"\nTitles to embed: {len(docs)} (skipped {len(rows) - len(docs)} with no text)")

    # 5. embed
    device = "cuda" if __import__("torch").cuda.is_available() else "cpu"
    print(f"Embedding device: {device}")
    model = SentenceTransformer(MODEL_NAME, device=device, truncate_dim=DIM)
    vectors = model.encode(
        [d[2] for d in docs],
        normalize_embeddings=True,
        batch_size=256,
        show_progress_bar=True,
    )

    # 6. write vec0 table to separate catalog_vec.db
    vec_con = sqlite3.connect(VEC_DB)
    vec_con.enable_load_extension(True)
    sqlite_vec.load(vec_con)
    vec_con.enable_load_extension(False)

    vec_con.execute("DROP TABLE IF EXISTS catalog_vec")
    vec_con.execute("DROP TABLE IF EXISTS catalog_meta")
    vec_con.execute(f"CREATE VIRTUAL TABLE catalog_vec USING vec0(embedding float[{DIM}])")
    vec_con.execute(
        "CREATE TABLE catalog_meta (rowid INTEGER PRIMARY KEY, tconst TEXT, title TEXT)"
    )
    for i, ((tconst, title, _), vec) in enumerate(zip(docs, vectors)):
        vec_con.execute("INSERT INTO catalog_vec (rowid, embedding) VALUES (?, ?)", (i, vec.tobytes()))
        vec_con.execute("INSERT INTO catalog_meta VALUES (?, ?, ?)", (i, tconst, title))
    vec_con.commit()
    vec_con.close()
    import os
    size_mb = os.path.getsize(VEC_DB) / 1e6
    print(f"Wrote {len(docs)} vectors to {VEC_DB} ({size_mb:.0f}MB)")

    # 7. sanity queries — open vec_con fresh, attach catalog.db for metadata join
    vec_con = sqlite3.connect(VEC_DB)
    vec_con.enable_load_extension(True)
    sqlite_vec.load(vec_con)
    vec_con.enable_load_extension(False)
    vec_con.execute(f"ATTACH DATABASE '{CATALOG_DB}' AS cat")

    test_queries = [
        ("decade",  "dark thriller from the 1980s"),
        ("decade",  "horror movies from the 70s"),
        ("mood",    "dark and brooding psychological"),
        ("mood",    "feel-good uplifting family"),
        ("mixed",   "dark sci-fi from the 90s"),
        ("runtime", "something short under 90 minutes"),
    ]
    print()
    for kind, q in test_queries:
        qvec = model.encode(QUERY_TEMPLATE.format(text=q), normalize_embeddings=True)
        results = vec_con.execute(
            """SELECT catalog_meta.title, cat.catalog_items.startYear, cat.catalog_items.genres, distance
               FROM catalog_vec
               JOIN catalog_meta ON catalog_meta.rowid = catalog_vec.rowid
               JOIN cat.catalog_items ON cat.catalog_items.tconst = catalog_meta.tconst
               WHERE embedding MATCH ? AND k = 5
               ORDER BY distance""",
            (qvec.tobytes(),),
        ).fetchall()
        print(f"[{kind}] {q!r}")
        for title, year, genres, dist in results:
            print(f"  {1-dist:.3f}  {title} ({year} | {genres})")
        print()

    vec_con.close()
    con.close()


if __name__ == "__main__":
    main()
