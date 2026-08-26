"""Generate synthetic negation genre training data.

Queries contain explicit genre words after negation triggers ("no action or war").
The negated genres are labeled as genre spans so the model learns they are present
in the query. Post-processing handles include vs exclude direction.

Output: data/negation_genre_examples.jsonl
"""

from __future__ import annotations

import json
import random
from pathlib import Path

random.seed(42)

GENRES = [
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
    "Music", "Musical", "Mystery", "Romance", "Sci-Fi", "Sport",
    "Thriller", "War", "Western",
]

DECADE_PHRASES = [
    ("1990s", {"from": 1990, "to": 1999}),
    ("2000s", {"from": 2000, "to": 2009}),
    ("2010s", {"from": 2010, "to": 2019}),
    ("90s",   {"from": 1990, "to": 1999}),
]

RATING_PHRASES = [
    ("good",               None),
    ("well-reviewed",      7.17),
    ("highly rated",       8.17),
    ("critically acclaimed", 8.17),
]


def _genre_span(g: str) -> dict:
    return {"span": g.lower(), "type": "genre", "normalized": [g]}


def _date_span(phrase: str, norm: dict) -> dict:
    return {"span": phrase, "type": "date", "normalized": norm}


def _rating_span(phrase: str, norm) -> dict:
    return {"span": phrase, "type": "rating", "normalized": norm}


def _negation_span(genres: list[str], trigger: str = "no") -> tuple[str, list[dict]]:
    """Returns (span_text, list_of_genre_spans) for negated genres."""
    if len(genres) == 1:
        text = f"{trigger} {genres[0].lower()}"
    else:
        text = trigger + " " + " or ".join(g.lower() for g in genres)
    spans = [_genre_span(g) for g in genres]
    return text, spans


# ── pattern: [date] [incl_genre] no [excl_genre] ─────────────────────────────

INCLUDE_EXCLUDE_COMBOS = [
    (["Drama"],             ["Action"]),
    (["Drama"],             ["Comedy"]),
    (["Drama"],             ["Action", "War"]),
    (["Drama"],             ["Comedy", "Animation"]),
    (["Thriller"],          ["Comedy"]),
    (["Thriller"],          ["Horror"]),
    (["Romance"],           ["Comedy"]),
    (["Romance"],           ["Horror", "Thriller"]),
    (["Mystery", "Thriller"], ["Comedy", "Romance"]),
    (["Drama", "Romance"],  ["Horror"]),
    (["Sci-Fi"],            ["Comedy"]),
    (["Horror"],            ["Comedy"]),
    (["Drama"],             ["Animation", "Family"]),
    (["Documentary"],       ["Comedy"]),
    (["Drama", "History"],  ["Action"]),
]

TRIGGERS = ["no", "without", "not"]


def gen_include_exclude() -> list[dict]:
    rows = []
    for inc, exc in INCLUDE_EXCLUDE_COMBOS:
        for _ in range(3):
            dph, dp = random.choice(DECADE_PHRASES)
            rph, rnorm = random.choice(RATING_PHRASES)
            trigger = random.choice(TRIGGERS)
            neg_text, neg_spans = _negation_span(exc, trigger)

            inc_str = " ".join(g.lower() for g in inc)
            query = f"{rph} {dph} {inc_str} {neg_text}"
            spans = (
                [_rating_span(rph, rnorm), _date_span(dph, dp)]
                + [_genre_span(g) for g in inc]
                + neg_spans
            )
            rows.append({
                "query": query,
                "reasoning": f"Include {inc}, exclude {exc} (labeled as genre for detection).",
                "spans": spans,
                "persona_context": "Grazer persona (Find intent): genre inclusion + exclusion",
                "group_id": -1,
                "persona_id": -1,
                "synthetic": True,
            })
    return rows


# ── pattern: [date] no [excl_genre] only (no positive include genre) ─────────

PURE_EXCLUDE = [
    ["Action", "Horror"],
    ["Comedy"],
    ["Action", "War"],
    ["Comedy", "Animation"],
    ["Horror", "Thriller"],
    ["Animation", "Family"],
]


def gen_pure_exclude() -> list[dict]:
    rows = []
    for exc in PURE_EXCLUDE:
        for _ in range(2):
            dph, dp = random.choice(DECADE_PHRASES)
            trigger = random.choice(TRIGGERS)
            neg_text, neg_spans = _negation_span(exc, trigger)
            query = f"{dph} movies {neg_text}"
            spans = [_date_span(dph, dp)] + neg_spans
            rows.append({
                "query": query,
                "reasoning": f"Pure exclusion of {exc}, no positive genre constraint.",
                "spans": spans,
                "persona_context": "Grazer persona (Find intent): pure exclusion",
                "group_id": -1,
                "persona_id": -1,
                "synthetic": True,
            })
    return rows


def main():
    out = Path("data/negation_genre_examples.jsonl")

    rows = gen_include_exclude() + gen_pure_exclude()
    random.shuffle(rows)

    with out.open("w") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")

    print(f"Written {len(rows)} rows → {out}")
    print("\nSamples:")
    for r in rows[:5]:
        print(f"  {r['query']!r}")
        for sp in r["spans"]:
            print(f"    [{sp['type']}] {sp['span']!r} → {sp['normalized']}")


if __name__ == "__main__":
    import os
    os.chdir(Path(__file__).parent.parent)
    main()
