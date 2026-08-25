"""Generate labeled (query, expected_facets) training pairs from real
catalog.db rows via string templates - deterministic, no model calls, no
labeling needed since the answer is known by construction (we picked the
real title/genre/year/rating/numVotes and built the query from them).

This covers the "easy" structured facets (title, genre, date, rating,
popularity) cheaply and at scale. It does NOT cover mood/plot-style residual
text - that still needs the LLM-generation + auto-label + spot-check path,
since mood isn't a literal catalog column.
"""

import json
import random
import sqlite3

DB_PATH = "ml/data/catalog.db"
OUTPUT_PATH = "ml/data/template_training_data.jsonl"
SEED = 42


def decade_phrase(year: int) -> str:
    decade = (year // 10) * 10
    suffix = "00s" if decade % 100 == 0 else f"{decade % 100}s"
    return f"the {suffix}"


def rating_phrase(rating: float) -> str | None:
    if rating >= 8.0:
        return random.choice(["highly rated", "critically acclaimed", "top rated"])
    if rating <= 4.5:
        return random.choice(["poorly rated", "critically panned"])
    return None  # mid-range rating isn't a strong enough signal to phrase naturally


def popularity_phrase(num_votes: int) -> str | None:
    if num_votes < 500:
        return random.choice(["a hidden gem", "an underrated", "an obscure"])
    if num_votes > 100_000:
        return random.choice(["a popular", "a well-known", "a famous"])
    return None


TEMPLATES = [
    # (template_fn, required_row_fields, expected_facet_types)
    lambda r: (f"{decade_phrase(r['startYear'])} {r['genre']} movies", {"date", "genre"}),
    lambda r: (f"{r['genre']} movies from {r['startYear']}", {"genre", "date"}),
    lambda r: (f"{r['title']} {r['startYear']}", {"title", "date"}),
    lambda r: (f"download {r['title']}", {"title", "other"}),
    lambda r: (f"{r['title']}", {"title"}),
]

RATING_TEMPLATE = lambda r, phrase: (f"a {phrase} {r['genre']} film", {"rating", "genre"})
POPULARITY_TEMPLATE = lambda r, phrase: (f"{phrase} {r['genre']} movie", {"popularity", "genre"})
RATING_GENRE_TEMPLATE = lambda r, phrase: (f"{r['genre']} movies with {phrase} reviews", {"genre", "rating"})


def main() -> None:
    random.seed(SEED)
    con = sqlite3.connect(DB_PATH)
    rows = con.execute(
        """SELECT title, genres, startYear, averageRating, numVotes
           FROM catalog_items
           WHERE startYear IS NOT NULL AND genres IS NOT NULL AND genres != ''
           ORDER BY RANDOM() LIMIT 500"""
    ).fetchall()
    con.close()

    examples = []
    for title, genres_csv, year, rating, num_votes in rows:
        genre = genres_csv.split(",")[0]  # primary genre, keeps templates readable
        row = {"title": title, "genre": genre, "startYear": year}

        for template_fn in TEMPLATES:
            query, facets = template_fn(row)
            examples.append({"query": query, "expected": sorted(facets)})

        if rating is not None:
            phrase = rating_phrase(rating)
            if phrase:
                query, facets = RATING_TEMPLATE(row, phrase)
                examples.append({"query": query, "expected": sorted(facets)})
                query, facets = RATING_GENRE_TEMPLATE(row, phrase)
                examples.append({"query": query, "expected": sorted(facets)})

        if num_votes is not None:
            phrase = popularity_phrase(num_votes)
            if phrase:
                query, facets = POPULARITY_TEMPLATE(row, phrase)
                examples.append({"query": query, "expected": sorted(facets)})

    with open(OUTPUT_PATH, "w") as f:
        for ex in examples:
            f.write(json.dumps(ex) + "\n")

    print(f"wrote {len(examples)} template-generated examples to {OUTPUT_PATH}")
    print("\nsample of 15:")
    for ex in random.sample(examples, 15):
        print(f"  {ex['query']!r:55s} -> {ex['expected']}")


if __name__ == "__main__":
    main()
