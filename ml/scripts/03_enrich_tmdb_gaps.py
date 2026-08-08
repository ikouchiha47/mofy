"""Fill the overview-text gap Kaggle's static dataset can't cover: TV
series (zero Kaggle coverage) and 2018+ movies (Kaggle's snapshot is from
2019 and its coverage falls off a cliff starting exactly then - 54% for
2016, 13% for 2017, ~0% from 2018 on).

Async + concurrency-limited against TMDB's real current rate limit
(~40 req/sec informally, per https://developer.themoviedb.org/docs/rate-limiting -
this runs well under that at 12 concurrent requests) and checkpointed to a
SQLite file, one commit per resolved title, so an interrupted or crashed
run resumes instead of restarting - the network in this environment has
shown real transient connect errors, not just TMDB-side 429s.

Input:  ml/data/catalog_titles.parquet (from 02_join_kaggle_overview.py)
Output: ml/data/tmdb_checkpoint.db (table: tmdb_checkpoint(tconst, overview, fetched_at))
"""

import argparse
import asyncio
import os
import sqlite3
import time

import httpx
import polars as pl
from tqdm import tqdm

DATA_DIR = "ml/data"
CHECKPOINT_PATH = f"{DATA_DIR}/tmdb_checkpoint.db"
TMDB_API_KEY = os.environ["TMDB_API_KEY"]
CONCURRENCY = 12
MAX_RETRIES = 5


def open_checkpoint_db() -> sqlite3.Connection:
    con = sqlite3.connect(CHECKPOINT_PATH)
    con.execute("PRAGMA journal_mode=WAL")
    con.execute(
        """CREATE TABLE IF NOT EXISTS tmdb_checkpoint (
            tconst TEXT PRIMARY KEY,
            overview TEXT NOT NULL,
            fetched_at INTEGER NOT NULL
        )""",
    )
    con.commit()
    return con


def load_checkpointed_ids(con: sqlite3.Connection) -> set[str]:
    return {row[0] for row in con.execute("SELECT tconst FROM tmdb_checkpoint")}


async def fetch_one(client: httpx.AsyncClient, tconst: str, sem: asyncio.Semaphore) -> dict:
    async with sem:
        for attempt in range(MAX_RETRIES):
            try:
                resp = await client.get(
                    f"https://api.themoviedb.org/3/find/{tconst}",
                    params={"external_source": "imdb_id", "language": "en-US"},
                    headers={"Authorization": f"Bearer {TMDB_API_KEY}", "accept": "application/json"},
                )
            except httpx.TransportError:
                # Real transient network errors observed in this environment,
                # not just TMDB-side throttling - back off and retry rather
                # than losing the whole run to one flaky connection.
                await asyncio.sleep(2**attempt)
                continue

            if resp.status_code == 429:
                retry_after = float(resp.headers.get("Retry-After", 1))
                await asyncio.sleep(retry_after)
                continue

            resp.raise_for_status()
            data = resp.json()
            results = data.get("movie_results") or data.get("tv_results") or []
            overview = results[0].get("overview", "") if results else ""
            return {"tconst": tconst, "overview": overview}

        # Every retry failed - checkpoint as empty so the title isn't
        # silently dropped from "remaining" on the next resumed run either;
        # a later pass can specifically re-target empty-but-checkpointed rows.
        return {"tconst": tconst, "overview": ""}


async def run(tconsts: list[str], con: sqlite3.Connection) -> None:
    sem = asyncio.Semaphore(CONCURRENCY)
    async with httpx.AsyncClient(timeout=10.0) as client:
        tasks = [fetch_one(client, tconst, sem) for tconst in tconsts]
        for coro in tqdm(asyncio.as_completed(tasks), total=len(tasks)):
            result = await coro
            con.execute(
                "INSERT OR REPLACE INTO tmdb_checkpoint (tconst, overview, fetched_at) VALUES (?, ?, ?)",
                (result["tconst"], result["overview"], int(time.time())),
            )
            con.commit()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Only fetch this many titles this run - for a small validation batch before the full ~17,925-title run.",
    )
    parser.add_argument(
        "--year-min",
        type=int,
        default=2018,
        help="Minimum startYear, applied to both movies and TV series. Default 2018, where Kaggle's own movie coverage falls to ~0%% (TV series have zero Kaggle coverage at any year, but still respect this floor when explicitly narrowed).",
    )
    parser.add_argument("--year-max", type=int, default=None, help="Maximum startYear, inclusive. Default: no cap.")
    parser.add_argument(
        "--type",
        choices=["movie", "tv"],
        default=None,
        help="Restrict to one titleType ('tv' maps to IMDb's 'tvSeries'). Default: both.",
    )
    args = parser.parse_args()

    catalog = pl.read_parquet(f"{DATA_DIR}/catalog_titles.parquet")
    scope = catalog.filter(
        (pl.col("overview") == "") & (pl.col("startYear") >= args.year_min),
    )
    if args.year_max is not None:
        scope = scope.filter(pl.col("startYear") <= args.year_max)
    if args.type is not None:
        title_type = "tvSeries" if args.type == "tv" else args.type
        scope = scope.filter(pl.col("titleType") == title_type)

    con = open_checkpoint_db()
    already_done = load_checkpointed_ids(con)
    remaining = scope.filter(~pl.col("tconst").is_in(already_done))
    print(f"{scope.height} titles in scope, {len(already_done)} already checkpointed, {remaining.height} remaining")

    if remaining.height == 0:
        print("nothing left to fetch")
        con.close()
        return

    tconsts = remaining["tconst"].to_list()
    if args.limit is not None:
        tconsts = tconsts[: args.limit]
        print(f"--limit {args.limit} set - running a validation batch of {len(tconsts)}")

    try:
        asyncio.run(run(tconsts, con))
    finally:
        con.close()


if __name__ == "__main__":
    main()
