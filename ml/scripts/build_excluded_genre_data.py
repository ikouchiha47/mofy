"""Build excluded_genre training data.

Two operations:
1. Re-label existing negation spans (type=mood/other) → type=excluded_genre
   using a deterministic mapping table.
2. Generate synthetic queries for patterns with no existing coverage:
   - "no A or B"  (pure exclude, no include)
   - "no A and B" (exclude A, include B — scoping ambiguity made explicit)

Output: data/excluded_genre_examples.jsonl
"""

from __future__ import annotations

import json
import random
from itertools import combinations
from pathlib import Path

random.seed(42)

# ── mapping table ─────────────────────────────────────────────────────────────
# span text (lowercased, stripped) → excluded genre list, or None = keep as mood

NEGATION_MAP: dict[str, list[str] | None] = {
    # explicit family/kids exclusion
    "not for kids":                  ["Family", "Animation"],
    "not for children":              ["Family", "Animation"],
    "not suitable for kids":         ["Family", "Animation"],
    "not suitable for children":     ["Family", "Animation"],
    "not appropriate for young kids":["Family", "Animation"],
    "no kids":                       ["Family", "Animation"],
    "no kids content":               ["Family", "Animation"],
    "no kids movies":                ["Family", "Animation"],
    "adults only":                   ["Family", "Animation"],
    "for adults only":               ["Family", "Animation"],
    "suitable for adults":           ["Family", "Animation"],
    "suitable for adult movie night":["Family", "Animation"],
    "for movie night adults only":   ["Family", "Animation"],
    "non-family":                    ["Family"],
    # explicit content exclusion
    "no violence":                   ["Action", "Horror", "War", "Crime"],
    "without violence":              ["Action", "Horror", "War", "Crime"],
    "without violence for teens":    ["Action", "Horror", "War"],
    "no comedy":                     ["Comedy"],
    "non action":                    ["Action"],
    "non-action":                    ["Action"],
    "without heavy drama":           ["Drama"],
    # viewing context only — no genre exclusion signal
    "suitable for couples":          None,
    "suitable for movie night":      None,
    "suitable for group watch":      None,
    "suitable for family night":     None,
    "suitable for date night":       None,
    "safe for date night":           None,
    "appropriate for date night":    None,
    "suitable for quiet evening":    None,
    "suitable for a date night":     None,
    "suitable for teens":            None,
    "suitable for high school":      None,
    "suitable for all ages":         None,
    "suitable for mixed ages":       None,
    "appropriate for teens":         None,
    "appropriate for kids":          None,
    "safe for family movie night":   None,
    "safe for older kids":           None,
    "safe for young kids":           None,
    "mature":                        None,
    "wholesome":                     None,
    # runtime spans accidentally caught
    "not too long":                  None,
    "no longer than 110 mins":       None,
}

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
    ("2020s", {"from": 2020, "to": 2029}),
    ("90s",   {"from": 1990, "to": 1999}),
]

POP_PHRASES = [
    ("hidden gem", None),
    ("obscure",    None),
    ("popular",    None),
    ("blockbuster",None),
    ("under-the-radar", None),
]

# ── step 1: re-label existing data ────────────────────────────────────────────

def relabel(src: Path) -> tuple[list[dict], int]:
    updated, changed = [], 0
    with src.open() as f:
        for line in f:
            obj = json.loads(line)
            new_spans = []
            for sp in obj["spans"]:
                key = sp["span"].lower().strip()
                if key in NEGATION_MAP:
                    excluded = NEGATION_MAP[key]
                    if excluded is not None:
                        sp = {**sp, "type": "excluded_genre", "normalized": excluded}
                        changed += 1
                new_spans.append(sp)
            obj["spans"] = new_spans
            updated.append(obj)
    return updated, changed


# ── step 2: synthetic queries ─────────────────────────────────────────────────

def _genre_span(g: str) -> dict:
    return {"span": g.lower(), "type": "genre", "normalized": [g]}

def _excl_span(genres: list[str]) -> dict:
    label = "no " + " or ".join(g.lower() for g in genres)
    return {"span": label, "type": "excluded_genre", "normalized": genres}

def _date_span(phrase: str, norm: dict) -> dict:
    return {"span": phrase, "type": "date", "normalized": norm}

def _pop_span(phrase: str) -> dict:
    return {"span": phrase, "type": "popularity", "normalized": None}


def gen_pure_exclude() -> list[dict]:
    """Pattern 2: 'no A or B' — exclude only, no include genre."""
    rows = []
    excl_pairs = [
        ["Action", "Horror"],
        ["Comedy", "Animation"],
        ["Horror", "Thriller"],
        ["Action", "War"],
        ["Comedy", "Romance"],
        ["Horror", "Fantasy"],
        ["Action", "Crime"],
        ["Animation", "Family"],
        ["Comedy", "Musical"],
        ["Thriller", "Crime"],
    ]
    templates = [
        lambda ex, dph, dp: (
            f"{dph} drama no {' or '.join(g.lower() for g in ex)}",
            [_date_span(dph, dp), _genre_span("Drama"), _excl_span(ex)],
        ),
        lambda ex, dph, dp: (
            f"drama movies no {' or '.join(g.lower() for g in ex)} {dph}",
            [_genre_span("Drama"), _excl_span(ex), _date_span(dph, dp)],
        ),
        lambda ex, dph, dp: (
            f"good {dph} film without {ex[0].lower()} or {ex[1].lower()}",
            [{"span": "good", "type": "rating", "normalized": None},
             _date_span(dph, dp), _excl_span(ex)],
        ),
        lambda ex, dph, dp: (
            f"drama no {' or '.join(g.lower() for g in ex)}",
            [_genre_span("Drama"), _excl_span(ex)],
        ),
    ]
    for ex in excl_pairs:
        for tmpl in templates:
            dph, dp = random.choice(DECADE_PHRASES)
            query, spans = tmpl(ex, dph, dp)
            rows.append({
                "query": query,
                "reasoning": f"Exclude {ex} genres with no positive genre constraint beyond Drama.",
                "spans": spans,
                "persona_context": "Grazer persona (Find intent): content exclusion constraint, no title in mind",
                "group_id": -1,
                "persona_id": -1,
                "synthetic": True,
            })
    return rows


def gen_exclude_and_include() -> list[dict]:
    """Pattern 3/4: include A, exclude B — both heads fire."""
    combos = [
        (["Drama"],   ["Comedy"]),
        (["Thriller"],["Comedy", "Animation"]),
        (["Drama", "Romance"], ["Horror"]),
        (["Mystery"], ["Action", "Comedy"]),
        (["Drama"],   ["Action", "War"]),
        (["Sci-Fi"],  ["Comedy"]),
        (["Horror"],  ["Comedy", "Romance"]),
        (["Drama"],   ["Animation", "Family"]),
        (["Romance"], ["Horror", "Thriller"]),
        (["Documentary"], ["Comedy"]),
        (["Drama", "History"], ["Action"]),
        (["Mystery", "Thriller"], ["Comedy", "Romance"]),
    ]
    templates = [
        lambda inc, ex, dph, dp: (
            f"{dph} {' '.join(g.lower() for g in inc)} but no {' or '.join(g.lower() for g in ex)}",
            [_date_span(dph, dp)]
            + [_genre_span(g) for g in inc]
            + [_excl_span(ex)],
        ),
        lambda inc, ex, dph, dp: (
            f"good {' '.join(g.lower() for g in inc)} not {' or '.join(g.lower() for g in ex)} {dph}",
            [{"span": "good", "type": "rating", "normalized": None}]
            + [_genre_span(g) for g in inc]
            + [_excl_span(ex)]
            + [_date_span(dph, dp)],
        ),
        lambda inc, ex, dph, dp: (
            f"{' '.join(g.lower() for g in inc)} without {' or '.join(g.lower() for g in ex)} {dph}",
            [_genre_span(g) for g in inc]
            + [_excl_span(ex)]
            + [_date_span(dph, dp)],
        ),
        lambda inc, ex, dph, dp: (
            f"obscure {dph} {' '.join(g.lower() for g in inc)} no {' or '.join(g.lower() for g in ex)}",
            [_pop_span("obscure"), _date_span(dph, dp)]
            + [_genre_span(g) for g in inc]
            + [_excl_span(ex)],
        ),
    ]
    rows = []
    for inc, ex in combos:
        for tmpl in templates:
            dph, dp = random.choice(DECADE_PHRASES)
            query, spans = tmpl(inc, ex, dph, dp)
            rows.append({
                "query": query,
                "reasoning": f"Include {inc}, exclude {ex}.",
                "spans": spans,
                "persona_context": "Grazer persona (Find intent): genre inclusion + exclusion constraint",
                "group_id": -1,
                "persona_id": -1,
                "synthetic": True,
            })
    return rows


def gen_pure_exclude_no_include() -> list[dict]:
    """Edge case: exclude only, no include at all — just date/pop."""
    rows = []
    cases = [
        (["Comedy"],           "something recent not comedy"),
        (["Horror", "Thriller"], "2010s movies without horror or thriller"),
        (["Action"],           "good 2000s film no action"),
        (["Animation", "Family"], "drama not animation or family 90s"),
        (["Comedy", "Romance"], "obscure 2010s without comedy or romance"),
    ]
    for excl, query in cases:
        rows.append({
            "query": query,
            "reasoning": f"Purely excludes {excl} with no positive genre signal.",
            "spans": [_excl_span(excl)],
            "persona_context": "Grazer persona (Find intent): pure exclusion query",
            "group_id": -1,
            "persona_id": -1,
            "synthetic": True,
        })
    return rows


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    src = Path("data/grounded_queries.jsonl")
    out = Path("data/excluded_genre_examples.jsonl")

    print("Re-labeling existing data...")
    relabeled, changed = relabel(src)
    print(f"  {changed} spans converted to excluded_genre")

    synth = gen_pure_exclude() + gen_exclude_and_include() + gen_pure_exclude_no_include()
    print(f"  {len(synth)} synthetic queries generated")

    with out.open("w") as f:
        for obj in relabeled:
            f.write(json.dumps(obj) + "\n")
        for obj in synth:
            f.write(json.dumps(obj) + "\n")

    total = len(relabeled) + len(synth)
    excl_count = sum(
        1 for obj in relabeled + synth
        for sp in obj["spans"] if sp["type"] == "excluded_genre"
    )
    print(f"  Written {total} total rows, {excl_count} excluded_genre spans → {out}")

    # quick sanity: show a few re-labeled examples
    print("\nSample re-labeled:")
    shown = 0
    for obj in relabeled:
        for sp in obj["spans"]:
            if sp["type"] == "excluded_genre":
                print(f"  {obj['query']!r}")
                print(f"    → excluded_genre: {sp['normalized']}")
                shown += 1
                break
        if shown >= 6:
            break

    print("\nSample synthetic (pure exclude):")
    for obj in synth[:3]:
        print(f"  {obj['query']!r}")
        for sp in obj["spans"]:
            print(f"    {sp['type']:15s} {sp['span']!r} → {sp['normalized']}")


if __name__ == "__main__":
    import os
    os.chdir(Path(__file__).parent.parent)
    main()
