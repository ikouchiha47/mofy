"""Copy catalog.db to Android assets and rebuild catalog_fts with pick_top2 text.

pick_top2 selects the two longest texts from [overview, imdb_plot_0, imdb_plot_1, ...]
so the FTS index covers more searchable text than overview alone.

Run from ml/:
  uv run python scripts/05_prepare_android_asset.py
"""

from __future__ import annotations

import json
import shutil
import sqlite3
import zlib
from pathlib import Path

SRC = Path("data/catalog.db")
DEST = Path("../android/app/src/main/assets/catalog.db")


def pick_top2(candidates: list[str]) -> str:
    ranked = sorted((s.strip() for s in candidates if s and s.strip()), key=len, reverse=True)
    return " ".join(ranked[:2])


def rebuild_fts(con: sqlite3.Connection) -> None:
    """Rebuild catalog_fts as a contentless FTS4 table.

    Contentless FTS4 (content="") stores only the inverted index, not a copy
    of the documents — saves ~30MB vs a content table. We never retrieve text
    from the FTS table itself (the UI reads catalog_items/catalog_plots), so
    this is fine. Only MATCH queries are needed.
    """
    print("Rebuilding catalog_fts (contentless, pick_top2 text)...")
    con.execute("DROP TABLE IF EXISTS catalog_fts")
    con.execute(
        "CREATE VIRTUAL TABLE catalog_fts USING fts4(content='', tconst, title, overview)"
    )

    rows = con.execute(
        "SELECT ci.tconst, ci.title, ci.overview, COALESCE(cp.plots, '[]') AS plots_json "
        "FROM catalog_items ci LEFT JOIN catalog_plots cp ON cp.tconst = ci.tconst"
    ).fetchall()

    inserts = []
    for rowid, (tconst, title, overview, plots_json) in enumerate(rows, start=1):
        try:
            plots = json.loads(plots_json) if plots_json else []
        except Exception:
            plots = []
        candidates = [(overview or "")] + [p for p in plots if isinstance(p, str)]
        text = pick_top2(candidates)
        inserts.append((rowid, tconst, title, text))

    con.executemany(
        "INSERT INTO catalog_fts (rowid, tconst, title, overview) VALUES (?, ?, ?, ?)", inserts
    )
    con.commit()
    print(f"  Rebuilt FTS for {len(inserts)} rows")


def compress_plots(con: sqlite3.Connection) -> None:
    """Rewrite catalog_plots.plots from JSON TEXT to zlib-compressed BLOB."""
    print("Compressing catalog_plots...")
    # Add a new column for the blob, migrate, drop old
    try:
        con.execute("ALTER TABLE catalog_plots ADD COLUMN plots_gz BLOB")
    except sqlite3.OperationalError:
        pass  # already has it

    rows = con.execute("SELECT tconst, plots FROM catalog_plots WHERE plots_gz IS NULL").fetchall()
    updates = []
    for tconst, plots_text in rows:
        blob = zlib.compress((plots_text or "[]").encode(), level=9)
        updates.append((blob, tconst))
    con.executemany("UPDATE catalog_plots SET plots_gz = ? WHERE tconst = ?", updates)

    # Clear the raw text column to reclaim space
    con.execute("UPDATE catalog_plots SET plots = ''")
    con.commit()
    raw_kb = sum(len(p) for _, p in rows)
    gz_kb = sum(len(b) for b, _ in updates)
    print(f"  Compressed {len(rows)} rows: {raw_kb/1e6:.1f}MB → {gz_kb/1e6:.1f}MB")


def main() -> None:
    if not SRC.exists():
        raise FileNotFoundError(f"Source not found: {SRC}")

    DEST.parent.mkdir(parents=True, exist_ok=True)
    print(f"Copying {SRC} → {DEST} ({SRC.stat().st_size / 1e6:.1f}MB)...")
    shutil.copy2(SRC, DEST)

    with sqlite3.connect(DEST) as con:
        rebuild_fts(con)
        compress_plots(con)
        # VACUUM to reclaim space after clearing raw plots text
        print("Running VACUUM...")
        con.execute("VACUUM")

    print(f"Done. Asset size: {DEST.stat().st_size / 1e6:.1f}MB")


if __name__ == "__main__":
    import os
    os.chdir(Path(__file__).parent.parent)
    main()
