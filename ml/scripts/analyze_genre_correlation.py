"""Check how TMDB/IMDb genres co-occur in the actual catalog.

Answers: is `genres` effectively independent tags, or do certain pairs
co-occur so often that Track C's genre-broadening step (docs/tasks/
09-recommendation-engine.md) needs to account for it separately from the
embedding-based relatedness lookup?

Input:  ml/data/catalog.db (catalog_items.genres, comma-separated TEXT)
Output: printed co-occurrence table, no files written.
"""

import sqlite3
from collections import Counter
from itertools import combinations

DB_PATH = "ml/data/catalog.db"


def main() -> None:
    con = sqlite3.connect(DB_PATH)
    rows = con.execute(
        "SELECT genres FROM catalog_items WHERE genres IS NOT NULL AND genres != ''"
    ).fetchall()
    con.close()

    genre_counts: Counter[str] = Counter()
    pair_counts: Counter[tuple[str, str]] = Counter()

    for (genres_csv,) in rows:
        genres = sorted(set(genres_csv.split(",")))
        genre_counts.update(genres)
        pair_counts.update(combinations(genres, 2))

    total = len(rows)
    print(f"{total} titles with genres, {len(genre_counts)} distinct genres\n")

    print("genre frequency:")
    for genre, count in genre_counts.most_common():
        print(f"  {genre:15s} {count:6d}  ({count / total:.1%})")

    print("\ntop co-occurring pairs (by Jaccard similarity, min 30 joint titles):")
    scored = []
    for (a, b), joint in pair_counts.items():
        union = genre_counts[a] + genre_counts[b] - joint
        jaccard = joint / union
        if joint >= 30:
            scored.append((jaccard, joint, a, b))
    scored.sort(reverse=True)
    for jaccard, joint, a, b in scored[:25]:
        # conditional probabilities in both directions - co-occurrence isn't symmetric
        p_b_given_a = joint / genre_counts[a]
        p_a_given_b = joint / genre_counts[b]
        print(
            f"  {a:12s} <-> {b:12s}  jaccard={jaccard:.3f}  joint={joint:5d}"
            f"  P({b}|{a})={p_b_given_a:.2f}  P({a}|{b})={p_a_given_b:.2f}"
        )


if __name__ == "__main__":
    main()
