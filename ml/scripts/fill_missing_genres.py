"""Backfill genre for the 95 movies in catalog.db whose genres field is
NULL/empty in the raw IMDb source (confirmed via title.basics.tsv.gz - it's
a real upstream gap, not something our pipeline dropped). Small, one-off
job - reuses TMDB's /find endpoint (same lookup as 03_enrich_tmdb_gaps.py)
but with the RateLimiter/Checkpoint pattern from batch_lib.py instead of
that script's async/concurrency approach, since 95 titles doesn't need it.

Input:  data/catalog.db (catalog_items.genres IS NULL/empty)
Output: writes directly back into catalog_items.genres
"""

import json
import os
import sqlite3
import sys
import time
from pathlib import Path

import dspy
import httpx

sys.path.insert(0, str(Path(__file__).parent))
from batch_lib import RateLimiter, Checkpoint

OLLAMA_MODEL = "ollama_chat/qwen3.5:4b"


class ClassifyGenreFromOverview(dspy.Signature):
    """Given a movie's plot overview (no title - reason only from the
    actual plot content described, not word-association with a title),
    classify 1-3 genres from the fixed vocabulary that genuinely fit the
    plot. Only use genres from the provided vocabulary list - do not invent
    new genre names.

    Example:
    overview: "A young woman discovers she can speak to the dead after a
    car accident, and must solve her own murder before the killer strikes
    again."
    genres: ["Mystery", "Thriller"]

    Example:
    overview: "Two rival street food vendors in Mumbai fall for each other
    while competing to win the city's biggest cooking contest."
    genres: ["Comedy", "Romance"]"""

    overview: str = dspy.InputField()
    genre_vocabulary: str = dspy.InputField(desc="comma-separated list of valid genre names to choose from")
    genres: list[str] = dspy.OutputField(desc="1-3 genres from genre_vocabulary that fit the plot")

TMDB_API_KEY = os.environ["TMDB_API_KEY"]
CATALOG_DB = "data/catalog.db"
CHECKPOINT_DB = "data/fill_missing_genres_checkpoint.db"
RATE_LIMIT_CALLS = 40
RATE_LIMIT_WINDOW_SECS = 10
MAX_RETRIES = 5  # this environment has shown real transient connect errors, not just TMDB-side 429s - same as 03_enrich_tmdb_gaps.py


def tmdb_get(client: httpx.Client, url: str, params: dict) -> dict:
    for attempt in range(MAX_RETRIES):
        try:
            resp = client.get(url, params=params, headers={"Authorization": f"Bearer {TMDB_API_KEY}", "accept": "application/json"})
        except httpx.TransportError:
            time.sleep(2**attempt)
            continue
        if resp.status_code == 429:
            time.sleep(float(resp.headers.get("Retry-After", 1)))
            continue
        resp.raise_for_status()
        return resp.json()
    raise RuntimeError(f"exhausted {MAX_RETRIES} retries for {url}")


# TMDB's genre names don't all match this catalog's existing IMDb-derived
# vocabulary (confirmed via analyze_genre_correlation.py's real frequency
# table) - inserting TMDB's name verbatim would silently diverge these
# backfilled titles from every other title's naming, breaking exact-match
# genre grouping/filtering for them specifically.
TMDB_TO_CATALOG_GENRE = {"Science Fiction": "Sci-Fi"}


def fetch_tmdb_genre_map(client: httpx.Client) -> dict[int, str]:
    data = tmdb_get(client, "https://api.themoviedb.org/3/genre/movie/list", {"language": "en-US"})
    return {g["id"]: TMDB_TO_CATALOG_GENRE.get(g["name"], g["name"]) for g in data["genres"]}


def find_genres(client: httpx.Client, tconst: str, genre_map: dict[int, str]) -> list[str] | None:
    data = tmdb_get(client, f"https://api.themoviedb.org/3/find/{tconst}", {"external_source": "imdb_id", "language": "en-US"})
    results = data.get("movie_results") or []
    if not results:
        return None
    genre_ids = results[0].get("genre_ids", [])
    return [genre_map[gid] for gid in genre_ids if gid in genre_map]


def main() -> None:
    con = sqlite3.connect(CATALOG_DB)
    missing = [row[0] for row in con.execute(
        "SELECT tconst FROM catalog_items WHERE (genres IS NULL OR genres = '') AND titleType = 'movie'"
    )]
    print(f"{len(missing)} movies with missing genre")

    checkpoint = Checkpoint(CHECKPOINT_DB, table="fill_missing_genres")
    done = checkpoint.done()
    pending = [t for t in missing if t not in done]
    print(f"{len(pending)} pending this run")

    filled, no_result, errors = 0, 0, 0
    if pending:
        limiter = RateLimiter(RATE_LIMIT_CALLS, RATE_LIMIT_WINDOW_SECS)
        with httpx.Client(timeout=10.0) as client:
            limiter.wait()
            genre_map = fetch_tmdb_genre_map(client)
            print(f"loaded {len(genre_map)} TMDB genre names")

            for tconst in pending:
                limiter.wait()
                try:
                    genres = find_genres(client, tconst, genre_map)
                except (httpx.HTTPStatusError, RuntimeError) as e:
                    errors += 1
                    print(f"  [ERROR] {tconst}: {e}")
                    continue

                if not genres:
                    no_result += 1
                    print(f"  [NO MATCH] {tconst}")
                    checkpoint.save(tconst, None)
                    continue

                genres_str = ",".join(genres)
                con.execute("UPDATE catalog_items SET genres = ? WHERE tconst = ?", (genres_str, tconst))
                con.commit()
                checkpoint.save(tconst, genres_str)
                filled += 1
                print(f"  {tconst} -> {genres_str}")
    else:
        print("TMDB stage: nothing pending, skipping to LLM fallback")

    print(f"\nTMDB stage: {filled} filled, {no_result} no TMDB match, {errors} errors")

    # Fallback for titles TMDB itself doesn't have (too obscure/new for
    # either source) but that do have a usable overview - same
    # docstring-examples technique already validated for the mood-tag
    # generator, constrained to this catalog's own real genre vocabulary
    # rather than letting the model invent genre names.
    still_missing = con.execute(
        "SELECT tconst, overview FROM catalog_items WHERE (genres IS NULL OR genres = '') AND titleType = 'movie'"
    ).fetchall()
    fallback_candidates = [(t, o) for t, o in still_missing if o and len(o.strip()) >= 30]
    print(f"\n{len(still_missing)} still missing genre, {len(fallback_candidates)} have a usable overview for LLM fallback")

    if fallback_candidates:
        known_genres = sorted({g for (row,) in con.execute("SELECT DISTINCT genres FROM catalog_items WHERE genres IS NOT NULL AND genres != ''") for g in row.split(",")})
        genre_vocab_str = ", ".join(known_genres)

        lm = dspy.LM(OLLAMA_MODEL, api_base="http://localhost:11434", api_key="", temperature=0.3, max_tokens=300, think=False)
        dspy.configure(lm=lm)
        classifier = dspy.Predict(ClassifyGenreFromOverview)

        llm_filled = 0
        for tconst, overview in fallback_candidates:
            pred = classifier(overview=overview[:500], genre_vocabulary=genre_vocab_str)
            valid = [g for g in pred.genres if g in known_genres]
            if not valid:
                print(f"  [NO VALID GENRE] {tconst}: raw={pred.genres}")
                continue
            genres_str = ",".join(valid)
            con.execute("UPDATE catalog_items SET genres = ? WHERE tconst = ?", (genres_str, tconst))
            con.commit()
            llm_filled += 1
            print(f"  {tconst} -> {genres_str} (LLM-derived from overview)")

        print(f"\nLLM fallback stage: {llm_filled} filled")

    con.close()


if __name__ == "__main__":
    main()
