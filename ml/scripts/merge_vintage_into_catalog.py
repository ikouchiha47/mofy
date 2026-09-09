"""Insert the 1950-1980 vintage titles into catalog_items and merge their IMDb
plots into catalog_plots (or imdb_plots.jsonl, if catalog_plots isn't built
yet) - the last manual step before re-running phase09_embed_enriched.py and
05_prepare_android_asset.py to pick them up.

Input:
    ml/data/vintage_1950_1980.json   (tconst, primaryTitle, startYear,
                                       runtimeMinutes, genres, averageRating,
                                       numVotes, overview)
    ml/data/vintage_imdb_plots.jsonl (imdbID, plots, synopsis)
Output: rows written into ml/data/catalog.db (catalog_items, catalog_plots)
"""

import json
import sqlite3
import unicodedata
import re

DATA_DIR = "ml/data"
_MULTI_SPACE = re.compile(r"[ \t\xa0​‌‍  　]+")


def clean_text(s: str | None) -> str:
    """Matches 04_export_sqlite.py's clean_text - same normalization for
    every overview in catalog_items regardless of which script wrote it."""
    if not s:
        return ""
    s = unicodedata.normalize("NFKC", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Cf")
    s = _MULTI_SPACE.sub(" ", s)
    return s.strip()


def main() -> None:
    with open(f"{DATA_DIR}/vintage_1950_1980.json", encoding="utf-8") as f:
        titles = json.load(f)

    plots_by_id: dict[str, list[str]] = {}
    with open(f"{DATA_DIR}/vintage_imdb_plots.jsonl", encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            plot_list = [p.strip() for p in (r.get("plots") or []) if p.strip()]
            if r.get("synopsis"):
                plot_list.append(r["synopsis"].strip())
            if r.get("imdbID") and plot_list:
                plots_by_id[r["imdbID"]] = plot_list

    con = sqlite3.connect(f"{DATA_DIR}/catalog.db")
    has_plots_table = con.execute(
        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='catalog_plots'"
    ).fetchone()[0]
    if not has_plots_table:
        con.execute("""
            CREATE TABLE catalog_plots (
                tconst TEXT PRIMARY KEY,
                plots  TEXT NOT NULL DEFAULT '[]'
            )
        """)

    inserted, plot_rows = 0, 0
    for t in titles:
        con.execute(
            """INSERT OR REPLACE INTO catalog_items
               (tconst, title, titleType, startYear, runtimeMinutes, genres, averageRating, numVotes, overview)
               VALUES (?, 'movie', ?, ?, ?, ?, ?, ?, ?)""",
            (
                t["tconst"],
                t["primaryTitle"],
                t["startYear"],
                t.get("runtimeMinutes"),
                t.get("genres"),
                t.get("averageRating"),
                t.get("numVotes"),
                clean_text(t.get("overview")),
            ),
        )
        inserted += 1
        plots = plots_by_id.get(t["tconst"])
        if plots:
            con.execute(
                "INSERT OR REPLACE INTO catalog_plots (tconst, plots) VALUES (?, ?)",
                (t["tconst"], json.dumps(plots)),
            )
            plot_rows += 1

    con.commit()
    total_items = con.execute("SELECT COUNT(*) FROM catalog_items").fetchone()[0]
    total_plots = con.execute("SELECT COUNT(*) FROM catalog_plots").fetchone()[0]
    con.close()

    print(f"upserted {inserted} catalog_items rows, {plot_rows} catalog_plots rows")
    print(f"catalog.db now has {total_items} catalog_items, {total_plots} catalog_plots")


if __name__ == "__main__":
    main()
