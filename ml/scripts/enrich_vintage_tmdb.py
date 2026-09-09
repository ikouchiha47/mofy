"""Fetch TMDB overview text for every title in vintage_1950_1980.json (same
/find-by-imdb-id pattern as 03_enrich_tmdb_gaps.py, but a plain one-shot run -
1,318 titles doesn't need checkpointing).

Input:  ml/data/vintage_1950_1980.json (from filter_1950_1980.py)
Output: overwrites ml/data/vintage_1950_1980.json, adding an `overview` field
        (empty string where TMDB has no match)
"""

import asyncio
import json
import os

import httpx

DATA_DIR = "ml/data"
TMDB_API_KEY = os.environ["TMDB_API_KEY"]
CONCURRENCY = 12
MAX_RETRIES = 5


async def fetch_one(client: httpx.AsyncClient, tconst: str, sem: asyncio.Semaphore) -> str:
    async with sem:
        for attempt in range(MAX_RETRIES):
            try:
                resp = await client.get(
                    f"https://api.themoviedb.org/3/find/{tconst}",
                    params={"external_source": "imdb_id", "language": "en-US"},
                    headers={"Authorization": f"Bearer {TMDB_API_KEY}", "accept": "application/json"},
                )
            except httpx.TransportError:
                await asyncio.sleep(2**attempt)
                continue
            if resp.status_code == 429:
                retry_after = float(resp.headers.get("Retry-After", 1))
                await asyncio.sleep(retry_after)
                continue
            resp.raise_for_status()
            results = resp.json().get("movie_results") or []
            return (results[0].get("overview") or "") if results else ""
        return ""


async def main() -> None:
    with open(f"{DATA_DIR}/vintage_1950_1980.json", encoding="utf-8") as f:
        titles = json.load(f)

    sem = asyncio.Semaphore(CONCURRENCY)
    async with httpx.AsyncClient(timeout=15.0) as client:
        overviews = await asyncio.gather(*[fetch_one(client, t["tconst"], sem) for t in titles])

    matched = 0
    for t, overview in zip(titles, overviews):
        t["overview"] = overview
        if overview:
            matched += 1

    print(f"{matched} / {len(titles)} titles matched on TMDB")
    with open(f"{DATA_DIR}/vintage_1950_1980.json", "w", encoding="utf-8") as f:
        json.dump(titles, f, indent=2)
    print(f"wrote {DATA_DIR}/vintage_1950_1980.json")


if __name__ == "__main__":
    asyncio.run(main())
