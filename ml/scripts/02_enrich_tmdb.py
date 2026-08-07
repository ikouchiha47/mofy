"""Enrich filtered IMDb titles with TMDB overview/genres/poster via imdb_id lookup.

Input:  ml/data/filtered_titles.parquet (from 01_filter_imdb.py)
Output: ml/data/enriched_titles.parquet
"""

import os

import httpx
import polars as pl
from tqdm import tqdm

DATA_DIR = "ml/data"
TMDB_API_KEY = os.environ["TMDB_API_KEY"]
BASE_URL = "https://api.themoviedb.org/3/find/{imdb_id}"


def find_by_imdb_id(client: httpx.Client, imdb_id: str) -> dict | None:
    resp = client.get(
        BASE_URL.format(imdb_id=imdb_id),
        params={"external_source": "imdb_id", "language": "en-US"},
        headers={
            "Authorization": f"Bearer {TMDB_API_KEY}",
            "accept": "application/json",
        },
    )
    resp.raise_for_status()
    data = resp.json()
    results = data.get("movie_results") or data.get("tv_results") or []
    return results[0] if results else None


def main() -> None:
    titles = pl.read_parquet(f"{DATA_DIR}/filtered_titles.parquet")
    rows = []

    with httpx.Client(timeout=10.0) as client:
        for tconst in tqdm(titles["tconst"], total=titles.height):
            match = find_by_imdb_id(client, tconst)
            if match is None:
                continue
            rows.append(
                {
                    "tconst": tconst,
                    "tmdb_id": match["id"],
                    "overview": match.get("overview", ""),
                    "genre_ids": match.get("genre_ids", []),
                    "poster_path": match.get("poster_path"),
                }
            )

    enriched = pl.DataFrame(rows)
    enriched.write_parquet(f"{DATA_DIR}/enriched_titles.parquet")
    print(f"{enriched.height} titles enriched")


if __name__ == "__main__":
    main()
