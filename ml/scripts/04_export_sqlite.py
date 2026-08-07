"""Export the embedded catalog into a single SQLite file the Android app bundles.

Input:  ml/data/embedded_titles.parquet (from 03_embed.py)
Output: ml/data/catalog.db

Schema matches what Phase 06 (docs/phases/06-local-library.md) expects:
  - `catalog_vectors`: plain BLOB-column vector table (id, tconst, embedding)
  - `catalog_fts`: FTS5 virtual table (tconst, title, overview) for keyword search
"""

import sqlite3
import struct

import polars as pl

DATA_DIR = "ml/data"


def encode_floats(vector: list[float]) -> bytes:
    return struct.pack(f"{len(vector)}f", *vector)


def main() -> None:
    titles = pl.read_parquet(f"{DATA_DIR}/embedded_titles.parquet")

    con = sqlite3.connect(f"{DATA_DIR}/catalog.db")
    con.execute("""
        CREATE TABLE IF NOT EXISTS catalog_vectors (
            tconst TEXT PRIMARY KEY,
            tmdb_id INTEGER,
            embedding BLOB NOT NULL
        )
    """)
    con.execute("""
        CREATE VIRTUAL TABLE IF NOT EXISTS catalog_fts
        USING fts5(tconst UNINDEXED, title, overview)
    """)

    for row in titles.iter_rows(named=True):
        con.execute(
            "INSERT OR REPLACE INTO catalog_vectors (tconst, tmdb_id, embedding) VALUES (?, ?, ?)",
            (row["tconst"], row["tmdb_id"], encode_floats(row["embedding"])),
        )
        con.execute(
            "INSERT OR REPLACE INTO catalog_fts (tconst, title, overview) VALUES (?, ?, ?)",
            (row["tconst"], row.get("primaryTitle", ""), row["overview"]),
        )

    con.commit()
    con.close()
    print(f"wrote {titles.height} rows to {DATA_DIR}/catalog.db")


if __name__ == "__main__":
    main()
