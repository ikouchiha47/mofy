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

import re
import sqlite3
import unicodedata
from pathlib import Path

import polars as pl

_MULTI_SPACE = re.compile(r"[ \t\xa0​‌‍  　]+")


def clean_text(s: str | None) -> str:
    if not s:
        return ""
    # unicode normalize: collapse composed chars, then strip unicode categories
    # that are invisible (Cf = format chars like zero-width joiners)
    s = unicodedata.normalize("NFKC", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Cf")
    s = _MULTI_SPACE.sub(" ", s)
    return s.strip()

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
        # clean after merge so both sources go through the same normalization
        titles = titles.with_columns(
            pl.col("overview").map_elements(clean_text, return_dtype=pl.String)
        )
        covered = titles.filter(pl.col("overview") != "").height
        print(f"merged TMDB checkpoint - {covered} / {titles.height} titles now have overview text")

    con = sqlite3.connect(f"{DATA_DIR}/catalog.db")
    con.execute("""
        CREATE TABLE IF NOT EXISTS catalog_items (
            tconst TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            titleType TEXT NOT NULL,
            startYear INTEGER,
            runtimeMinutes INTEGER,
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
               (tconst, title, titleType, startYear, runtimeMinutes, genres, averageRating, numVotes, overview)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                row["tconst"],
                row["primaryTitle"],
                row["titleType"],
                row["startYear"],
                row.get("runtimeMinutes"),
                row["genres"],
                row["averageRating"],
                row["numVotes"],
                clean_text(row["overview"]),
            ),
        )
        con.execute(
            "INSERT INTO catalog_fts (tconst, title, overview) VALUES (?, ?, ?)",
            (row["tconst"], row["primaryTitle"], clean_text(row["overview"])),
        )

    con.commit()
    con.close()
    print(f"wrote {titles.height} rows to {DATA_DIR}/catalog.db")


if __name__ == "__main__":
    main()
