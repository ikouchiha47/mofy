"""Export the catalog into a single SQLite file the Android app bundles.

Structured-fields-only version - no embeddings (Phase 09's embedding
model isn't wired into Android yet, see docs/research/, so building
vectors now would be idle work). Once that phase starts, use
phase09_enrich_tmdb.py + phase09_embed.py + phase09_export_with_vectors.py
instead, which add the vector table on top of this.

Input:  ml/data/catalog_titles.parquet (from 02_join_kaggle_overview.py)
        ml/data/tmdb_checkpoint.db (from 03_enrich_tmdb_gaps.py, optional -
        merged in for whatever it's covered so far; safe to re-run this
        export at any point mid-enrichment)
Output: ml/data/catalog.db

  - `catalog_items`: structured fields (title, genres, year, rating,
    numVotes) for genre/rating/year filtering and sorting - Discover-style
    browsing, no text search needed.
  - `catalog_fts`: FTS4 virtual table (title, overview) for keyword search
    over the subset of titles that have overview text - same pattern as
    the Android app's own library_search table (see LibrarySearchEntity.kt).
"""

import sqlite3
from pathlib import Path

import polars as pl

DATA_DIR = "ml/data"


def main() -> None:
    titles = pl.read_parquet(f"{DATA_DIR}/catalog_titles.parquet")

    checkpoint_path = f"{DATA_DIR}/tmdb_checkpoint.db"
    if Path(checkpoint_path).exists():
        checkpoint_con = sqlite3.connect(checkpoint_path)
        checkpoint = pl.read_database("SELECT tconst, overview FROM tmdb_checkpoint", checkpoint_con)
        checkpoint_con.close()
        titles = titles.join(checkpoint, on="tconst", how="left", suffix="_tmdb").with_columns(
            pl.when(pl.col("overview") == "")
            .then(pl.col("overview_tmdb").fill_null(""))
            .otherwise(pl.col("overview"))
            .alias("overview"),
        ).drop("overview_tmdb")
        covered = titles.filter(pl.col("overview") != "").height
        print(f"merged TMDB checkpoint - {covered} / {titles.height} titles now have overview text")

    con = sqlite3.connect(f"{DATA_DIR}/catalog.db")
    con.execute("""
        CREATE TABLE IF NOT EXISTS catalog_items (
            tconst TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            titleType TEXT NOT NULL,
            startYear INTEGER,
            genres TEXT,
            averageRating REAL,
            numVotes INTEGER,
            overview TEXT NOT NULL DEFAULT ''
        )
    """)
    con.execute("""
        CREATE VIRTUAL TABLE IF NOT EXISTS catalog_fts
        USING fts4(tconst, title, overview)
    """)

    for row in titles.iter_rows(named=True):
        con.execute(
            """INSERT OR REPLACE INTO catalog_items
               (tconst, title, titleType, startYear, genres, averageRating, numVotes, overview)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                row["tconst"],
                row["primaryTitle"],
                row["titleType"],
                row["startYear"],
                row["genres"],
                row["averageRating"],
                row["numVotes"],
                row["overview"],
            ),
        )
        con.execute(
            "INSERT INTO catalog_fts (tconst, title, overview) VALUES (?, ?, ?)",
            (row["tconst"], row["primaryTitle"], row["overview"]),
        )

    con.commit()
    con.close()
    print(f"wrote {titles.height} rows to {DATA_DIR}/catalog.db")


if __name__ == "__main__":
    main()
