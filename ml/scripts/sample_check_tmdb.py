"""Randomly sample a few titles per year from vintage_1950_1980.json and check
whether TMDB's /find endpoint has a match for each (coverage spot-check before
committing to a full enrichment pass).

Input:  ml/data/vintage_1950_1980.json
Output: prints a per-year hit/miss table; writes ml/data/vintage_tmdb_sample.json
"""

import asyncio
import json
import os
import random
from collections import defaultdict

import httpx

DATA_DIR = "ml/data"
TMDB_API_KEY = os.environ["TMDB_API_KEY"]
SAMPLES_PER_YEAR = 3
SEED = 42


async def check_one(client: httpx.AsyncClient, tconst: str) -> dict | None:
    resp = await client.get(
        f"https://api.themoviedb.org/3/find/{tconst}",
        params={"external_source": "imdb_id", "language": "en-US"},
        headers={"Authorization": f"Bearer {TMDB_API_KEY}", "accept": "application/json"},
    )
    resp.raise_for_status()
    data = resp.json()
    results = data.get("movie_results") or []
    return results[0] if results else None


async def main() -> None:
    with open(f"{DATA_DIR}/vintage_1950_1980.json", encoding="utf-8") as f:
        titles = json.load(f)

    by_year = defaultdict(list)
    for t in titles:
        by_year[t["startYear"]].append(t)

    rng = random.Random(SEED)
    sample = []
    for year in sorted(by_year):
        pool = by_year[year]
        sample += rng.sample(pool, min(SAMPLES_PER_YEAR, len(pool)))

    print(f"sampling {len(sample)} titles across {len(by_year)} years")

    results = []
    async with httpx.AsyncClient(timeout=15.0) as client:
        for t in sample:
            try:
                match = await check_one(client, t["tconst"])
            except Exception as e:
                match = None
                print(f"  ERROR {t['tconst']} ({t['primaryTitle']}): {e}")
            found = match is not None
            results.append({
                "tconst": t["tconst"],
                "primaryTitle": t["primaryTitle"],
                "startYear": t["startYear"],
                "tmdb_found": found,
                "tmdb_title": match.get("title") if match else None,
                "tmdb_overview": (match.get("overview") or "")[:200] if match else None,
            })
            print(f"  {t['startYear']} {t['tconst']} {t['primaryTitle']!r}: "
                  f"{'FOUND' if found else 'missing'}")
            await asyncio.sleep(0.05)

    hit = sum(r["tmdb_found"] for r in results)
    print(f"\n{hit}/{len(results)} sampled titles found on TMDB ({hit/len(results)*100:.1f}%)")

    with open(f"{DATA_DIR}/vintage_tmdb_sample.json", "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"wrote {DATA_DIR}/vintage_tmdb_sample.json")


if __name__ == "__main__":
    asyncio.run(main())
