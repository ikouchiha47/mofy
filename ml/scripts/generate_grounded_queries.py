"""Grounded query-facet training data generation: instead of a free-floating
persona description (which collapses into template phrasing at scale - see
scale_seed_examples.py's near-duplicate audit), ground each generated query
in 2-3 real catalog titles sharing a genre. Real overview/rating/popularity
data is inherently diverse across thousands of titles, so it forces the
model to react to something concrete instead of running dry on an abstract
persona and reaching for the same stock phrases.

Deliberately excludes:
  - title (never shown to the model - same convention as the mood-tag
    generator, so generated queries reflect real content signal rather than
    title word-association).
  - crew/cast data (not in catalog.db; out of scope here per direct
    instruction - handled separately via NLP/filters, not this pipeline).
  - reusing the qwen3.5 mood-tag generator's output (per direct instruction,
    keep this pipeline independent for now).

Tests locally via qwen3.5:4b (free to iterate on) before any decision to
compare against the Gemini free-floating-persona approach.

Input:  data/catalog.db (catalog_items)
Output: data/grounded_queries.jsonl
"""

import argparse
import json
import random
import re
import sqlite3
import sys
from collections import Counter, defaultdict
from datetime import datetime
from itertools import combinations
from pathlib import Path
from typing import Literal

import dspy
from pydantic import BaseModel

sys.path.insert(0, str(Path(__file__).parent))
from batch_lib import RateLimiter, Checkpoint
from dedup_lib import SemanticDedup
from scale_seed_examples import load_gemini_api_key

OLLAMA_MODEL = "ollama_chat/qwen3.5:4b"
GEMINI_MODEL = "gemini/gemini-3.6-flash"
CATALOG_DB = "data/catalog.db"
OUTPUT_PATH = "data/grounded_queries.jsonl"
REJECTED_PATH = "data/grounded_queries_rejected.jsonl"
CHECKPOINT_DB = "data/grounded_queries_checkpoint.db"
TITLES_PER_GROUP = 3
MIN_OVERVIEW_LEN = 30  # 31/20102 catalog overviews are shorter than this - too thin to ground a query in
CATALOG_ROW_LIMIT = 20000  # ~full catalog with a non-empty overview (20102 total) - was capped at 3000
RATE_LIMIT_CALLS = 40
RATE_LIMIT_WINDOW_SECS = 10

# Same catalog-derived percentiles used in test_mood_tag_generation.py -
# numVotes buckets grounded in this catalog's actual distribution, not
# arbitrary round numbers.
CATALOG_VOTES_P25 = 1230
CATALOG_VOTES_P75 = 14142
CATALOG_VOTES_P90 = 81970
CATALOG_MEAN_RATING = 7.17

# Personas that don't need crew data - excludes the actor/director-driven
# Grazer/Find situations from scale_seed_examples.py's list, since this
# pipeline has no cast/crew signal to ground them in.
GROUNDED_PERSONAS = [
    "Hunter persona (Fetch intent): exact title search, sometimes with a format/quality/year modifier, wants immediate direct retrieval",
    "Grazer persona (Find intent): a specific hard constraint drives the search (runtime limit, content exclusion for kids, a group's competing preferences) but no title in mind",
    "Grazer persona (Explore intent): solo evening mood browsing, no title in mind, wants an immediate vibe match for how they feel right now",
    "Grazer persona (Explore intent): broad genre or era browsing with no specific constraint, open to a wide slate of results",
    "Grazer persona (Explore intent): wants something obscure/under-the-radar/overlooked, indirect popularity signals, resists simple genre labeling",
    "Grazer persona (Find intent): wants something short, under 90 minutes, quick lunch-break or commute watch - runtime is the primary constraint",
    "Grazer persona (Find intent): needs a long immersive watch, 2.5+ hours, epic or extended runtime - will not consider anything short",
]


def rating_vocab(rating: float) -> str:
    if rating >= CATALOG_MEAN_RATING + 1.0:
        return "critically acclaimed, highly rated, must-watch, top-rated"
    if rating >= CATALOG_MEAN_RATING:
        return "well-reviewed, solid, good"
    if rating > 0:
        return "mixed reviews, divisive, so-so"
    return "(no rating signal)"


# Reverse lookup for the same tiers - rating is fundamentally a numeric
# filter, so the threshold shouldn't come from the model's own ad hoc guess
# per call (which varies call to call for the same phrase and was causing
# retrievability false-negatives). Deterministic keyword match instead.
RATING_TIER_1 = ["critically acclaimed", "highly rated", "must-watch", "must watch", "top-rated", "top rated", "top ", "acclaimed", "high rating", "highly reviewed"]
RATING_TIER_2 = ["well-reviewed", "well reviewed", "solid", "good ratings", "good reviews", "well received", "good rating", "good "]
RATING_TIER_3 = ["mixed reviews", "divisive", "so-so", "so so"]


def normalize_rating_span(span_text: str) -> float | None:
    text = span_text.lower()
    if any(k in text for k in RATING_TIER_1):
        return CATALOG_MEAN_RATING + 1.0
    if any(k in text for k in RATING_TIER_2):
        return CATALOG_MEAN_RATING
    if any(k in text for k in RATING_TIER_3):
        return None  # divisive/mixed isn't a lower-bound filter
    return None  # unrecognized phrase - don't force a filter the model didn't clearly express


_WHITESPACE_RE = re.compile(r"\s+")


def clean_overview(text: str) -> str:
    """16/20102 catalog overviews contain a literal NBSP (\\xa0) and 77
    contain embedded newlines/carriage returns - both render as harmless
    whitespace when printed but are real distinct code points in the raw
    string, which can end up baked into a generated query's span text
    (an exact-substring match against a query built from this text would
    then need the same odd whitespace to match). Normalize all whitespace
    variants to plain spaces and collapse runs."""
    return _WHITESPACE_RE.sub(" ", text).strip()


def popularity_vocab(num_votes: int) -> str:
    if num_votes >= CATALOG_VOTES_P90:
        return "blockbuster, mainstream hit, everyone's seen it, well-known"
    if num_votes >= CATALOG_VOTES_P75:
        return "popular, widely watched"
    if num_votes <= CATALOG_VOTES_P25:
        return "hidden gem, under-the-radar, obscure, cult following, deep cut"
    return "(no strong popularity signal)"


def date_vocab(year: int, current_year: int) -> str:
    age = current_year - year
    decade = f"{(year // 10) * 10}s"
    if age <= 2:
        return f"{year}, recent, new release, {decade}"
    if age <= 6:
        return f"{year}, {decade}, a few years old"
    if age <= 15:
        return f"{year}, {decade}, nostalgic, throwback, retro"
    return f"{year}, {decade}, vintage, classic, old-school"


class SpanLabel(BaseModel):
    span: str
    type: Literal["title", "genre", "mood", "date", "rating", "popularity", "runtime", "other"]


class GenerateGroundedQueries(dspy.Signature):
    """Given 2-3 real catalog entries (genre, year, rating tier, popularity
    tier, overview - deliberately no titles) and a persona/situation,
    generate MULTIPLE (3-8) realistic, genuinely DIFFERENT search queries a
    user with that persona would type that could plausibly retrieve one of
    these entries via genre/mood/date/rating/popularity signals - not a
    plot summary, real short search queries. Vary phrasing and which facets
    each query touches - don't produce near-repeats of each other. The
    vocabulary hints show real language grounded in this catalog's actual
    data distribution for each field - use them as inspiration for natural
    phrasing, not a checklist to include verbatim.

    Do not mention any title. Label every span in each query with its
    correct facet type (title, genre, mood, date, rating, popularity,
    runtime, other) - the same 8-category vocabulary used throughout this
    project. Context phrases (e.g. "family movie night") are mood, not
    genre. Time-relative vibe words (retro, nostalgic, throwback) with no
    explicit year/decade are mood. "cult classic"/"hidden gem" is
    popularity.

    Each span also gets a "normalized" value where the facet type has a
    deterministic underlying value the model should extract explicitly
    rather than leaving to be re-parsed later:
      - date: {"from": <year>, "to": <year>} (a single year -> from==to)
      - rating: a float 0-10 lower bound implied by the phrase (e.g.
        "highly rated" -> 7.5, "critically acclaimed" -> 8.0)
      - runtime: {"max_minutes": int} or {"min_minutes": int}
      - genre: list[str] of the canonical genre name(s) the span refers to
        (e.g. span "drama thriller" -> normalized ["Drama", "Thriller"]; a
        single-genre span still gets a one-item list, e.g. "sci-fi" ->
        ["Sci-Fi"]) - always a list even when the span text names multiple
        genres in one phrase, since it must be usable as a direct filter
        value.
      - title/mood/popularity/other: null (no deterministic value, the
        span text itself is the value)"""

    catalog_entries: str = dspy.InputField(desc="2-3 real catalog entries: genre, year, rating tier, popularity tier, overview")
    persona_context: str = dspy.InputField()
    field_vocabulary_hints: str = dspy.InputField(desc="real vocabulary grounded in this catalog's data, per queryable field")
    count: int = dspy.InputField(desc="how many distinct queries to generate")
    new_examples: list[dict] = dspy.OutputField(
        desc='list of {"query": str, "reasoning": str, "spans": [{"span": str, "type": str, "normalized": <value or null>}]} objects'
    )


def format_entry(row: dict, current_year: int) -> str:
    return (
        f"genre: {row['genres']}\n"
        f"year: {row['startYear']}\n"
        f"rating vocabulary: {rating_vocab(row['averageRating'])}\n"
        f"popularity vocabulary: {popularity_vocab(row['numVotes'])}\n"
        f"date vocabulary: {date_vocab(row['startYear'], current_year)}\n"
        f"overview: {row['overview'][:500]}"
    )


def check_retrievability(spans: list[dict], group_tconsts: set[str], con: sqlite3.Connection) -> tuple[bool | None, int]:
    """qwen's closing-the-loop idea: build a SQL filter from this query's
    own structured facets and check whether the group's real source
    title(s) actually come back. Only genre/date/rating are hard-filtered
    here (runtime has no catalog column yet; popularity is deliberately a
    soft boost, not a hard filter, per the mood-tag generator's own
    percentile-bucket reasoning) - a query with only mood/other spans has
    nothing structured to check, so returns (None, -1) meaning "not
    applicable" rather than pass/fail."""
    where, params = [], []
    for s in spans:
        t, norm = s.get("type"), s.get("normalized")
        if t == "genre" and isinstance(norm, list) and norm:
            # normalized is the model's own list[str] of canonical genre
            # names for this span (see GenerateGroundedQueries docstring) -
            # catalog_items.genres is comma-separated with no spaces, so
            # each genre name is checked independently rather than matching
            # the raw span text as one literal substring.
            for g in norm:
                where.append("genres LIKE ?")
                params.append(f"%{g}%")
        elif t == "date" and isinstance(norm, dict) and "from" in norm and "to" in norm:
            where.append("startYear BETWEEN ? AND ?")
            params.extend([norm["from"], norm["to"]])
        elif t == "rating" and isinstance(norm, (int, float)):
            where.append("averageRating >= ?")
            params.append(norm)

    if not where:
        return None, -1

    sql = f"SELECT tconst FROM catalog_items WHERE {' AND '.join(where)}"
    try:
        matched = {row[0] for row in con.execute(sql, params)}
    except sqlite3.OperationalError:
        return None, -1
    return bool(matched & group_tconsts), len(matched)


GENRE_RELATED_THRESHOLD = 0.08  # roughly the natural cutoff in analyze_genre_correlation.py's real output - Crime<->Mystery (0.146) and Crime<->Thriller (0.152) clear it, Drama<->Mystery (0.076) doesn't
RELATED_GENRE_FRACTION = 0.3  # share of each primary-genre pool topped up with titles from jaccard-related genres
FAR_GROUP_FRACTION = 0.1  # share of total groups deliberately built from jaccard=0 genre pairs, as an edge-case stress test


def compute_genre_jaccard(rows: list[dict]) -> tuple[dict[frozenset, float], Counter]:
    """Same computation as analyze_genre_correlation.py, reused here instead
    of hardcoding its printed numbers - real co-occurrence, not a hand-typed
    map that goes stale as the catalog changes."""
    genre_counts: Counter = Counter()
    pair_counts: Counter = Counter()
    for r in rows:
        genres = sorted(set(r["genres"].split(",")))
        genre_counts.update(genres)
        pair_counts.update(combinations(genres, 2))

    jaccard = {}
    for (a, b), joint in pair_counts.items():
        union = genre_counts[a] + genre_counts[b] - joint
        jaccard[frozenset((a, b))] = joint / union
    return jaccard, genre_counts


def related_genres(genre: str, jaccard: dict[frozenset, float], all_genres: list[str]) -> list[str]:
    scored = [
        (jaccard.get(frozenset((genre, g)), 0.0), g)
        for g in all_genres if g != genre
    ]
    scored.sort(reverse=True)
    return [g for score, g in scored if score >= GENRE_RELATED_THRESHOLD]


def group_by_genre(rows: list[dict]) -> list[list[dict]]:
    jaccard, genre_counts = compute_genre_jaccard(rows)
    all_genres = list(genre_counts.keys())

    by_primary = defaultdict(list)
    for r in rows:
        by_primary[r["genres"].split(",")[0]].append(r)

    groups = []
    for genre, items in by_primary.items():
        # top up the same-genre pool with titles from jaccard-related
        # genres (e.g. some Mystery titles mixed into a Crime pool) - real
        # multi-genre co-occurrence, not an artificial same-primary-only bucket
        pool = list(items)
        related = related_genres(genre, jaccard, all_genres)
        if related:
            related_pool = [r for rg in related[:3] for r in by_primary.get(rg, [])[:20]]
            random.shuffle(related_pool)
            pool.extend(related_pool[: int(len(items) * RELATED_GENRE_FRACTION)])

        random.shuffle(pool)
        for i in range(0, len(pool), TITLES_PER_GROUP):
            chunk = pool[i : i + TITLES_PER_GROUP]
            if len(chunk) >= 2:
                groups.append(chunk)

    # deliberately construct some genuinely unrelated-genre groups (jaccard
    # == 0, e.g. Western + Documentary) as an edge-case stress test for
    # whether the model can handle eclectic multi-genre combinations
    # without collapsing into one of the two genres.
    far_pairs = [
        (a, b) for a, b in combinations(all_genres, 2)
        if jaccard.get(frozenset((a, b)), 0.0) == 0.0
    ]
    for _ in range(int(len(groups) * FAR_GROUP_FRACTION)):
        if not far_pairs:
            break
        a, b = random.choice(far_pairs)
        a_items, b_items = by_primary.get(a, []), by_primary.get(b, [])
        if a_items and b_items:
            chunk = random.sample(a_items, min(2, len(a_items))) + random.sample(b_items, min(1, len(b_items)))
            groups.append(chunk[:TITLES_PER_GROUP])

    return groups


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=200, help="max (group, persona) calls to process this run - each call yields 3-8 queries")
    parser.add_argument("--dedup-threshold", type=float, default=0.85)
    parser.add_argument("--min-count", type=int, default=3)
    parser.add_argument("--max-count", type=int, default=8)
    parser.add_argument("--model", choices=["qwen", "gemini"], default="qwen")
    parser.add_argument("--refetch", action="store_true", help="ignore checkpoint state and reprocess (group, persona) combos even if already done - for regenerating old batches with a since-improved pipeline")
    args = parser.parse_args()

    if args.model == "gemini":
        lm = dspy.LM(GEMINI_MODEL, api_key=load_gemini_api_key(), temperature=0.7, max_tokens=8000)
    else:
        lm = dspy.LM(OLLAMA_MODEL, api_base="http://localhost:11434", api_key="", temperature=0.7, max_tokens=2000, think=False)
    dspy.configure(lm=lm)

    con = sqlite3.connect(CATALOG_DB)
    con.row_factory = sqlite3.Row
    raw_rows = [dict(r) for r in con.execute(
        "SELECT tconst, title, genres, startYear, overview, numVotes, averageRating FROM catalog_items "
        "WHERE overview != '' AND titleType = 'movie' ORDER BY numVotes DESC LIMIT ?",
        (CATALOG_ROW_LIMIT,),
    )]
    # kept open for the per-example retrievability check against the real catalog

    rows = []
    for r in raw_rows:
        if not r.get("genres"):
            continue
        r["overview"] = clean_overview(r["overview"])
        if len(r["overview"]) >= MIN_OVERVIEW_LEN:
            rows.append(r)
    print(f"{len(raw_rows)} titles fetched, {len(rows)} kept after overview cleanup + length filter (>= {MIN_OVERVIEW_LEN} chars) + genre presence check")

    groups = group_by_genre(rows)
    print(f"{len(rows)} titles -> {len(groups)} same-genre groups of {TITLES_PER_GROUP}")

    checkpoint = Checkpoint(CHECKPOINT_DB, table="grounded_queries")
    done = checkpoint.done()

    combos = [(g_idx, p_idx) for g_idx in range(len(groups)) for p_idx in range(len(GROUNDED_PERSONAS))]
    pending = [c for c in combos if args.refetch or f"{c[0]}:{c[1]}" not in done][: args.limit]
    print(f"{len(pending)} pending (group, persona) calls this run (--limit={args.limit}), {args.min_count}-{args.max_count} queries each")

    dedup = SemanticDedup(threshold=args.dedup_threshold)
    generator = dspy.Predict(GenerateGroundedQueries)
    limiter = RateLimiter(RATE_LIMIT_CALLS, RATE_LIMIT_WINDOW_SECS)
    current_year = datetime.now().year
    valid_types = {"title", "genre", "mood", "date", "rating", "popularity", "runtime", "other"}

    processed, rejected_dupes, errors, not_retrievable = 0, 0, 0, 0
    with open(OUTPUT_PATH, "a") as out, open(REJECTED_PATH, "a") as rejected_out:
        for g_idx, p_idx in pending:
            group = groups[g_idx]
            group_tconsts = {r["tconst"] for r in group}
            persona = GROUNDED_PERSONAS[p_idx]
            entries_text = "\n\n".join(format_entry(r, current_year) for r in group)
            vocab_hints = (
                f"rating: {rating_vocab(CATALOG_MEAN_RATING)}\n"
                f"popularity: {popularity_vocab(CATALOG_VOTES_P25)} / {popularity_vocab(CATALOG_VOTES_P90)}\n"
                f"date: {date_vocab(current_year - 5, current_year)} / {date_vocab(current_year - 20, current_year)}"
            )
            count = random.randint(args.min_count, args.max_count)

            limiter.wait()
            print(f"\n=== group {g_idx} ({group[0]['genres'].split(',')[0]}) x {persona[:50]}... (count={count}) ===")
            try:
                pred = generator(
                    catalog_entries=entries_text, persona_context=persona,
                    field_vocabulary_hints=vocab_hints, count=count,
                )
                checkpoint.save(f"{g_idx}:{p_idx}", True)

                for item in pred.new_examples:
                    try:
                        query, reasoning, spans = item["query"], item["reasoning"], item["spans"]
                        if not all(s.get("type") in valid_types for s in spans):
                            print(f"  [SKIP - invalid type] {query!r}")
                            rejected_out.write(json.dumps({
                                "query": query, "reasoning": reasoning, "spans": spans, "persona_context": persona,
                                "group_id": g_idx, "persona_id": p_idx, "reject_reason": "invalid_type",
                            }) + "\n")
                            rejected_out.flush()
                            continue

                        # override the model's own ad hoc rating threshold with a
                        # deterministic lookup against the same tiers used to build
                        # the vocabulary hints - rating is a numeric filter, not
                        # something that should vary call to call for the same phrase
                        for s in spans:
                            if s.get("type") == "rating":
                                s["normalized"] = normalize_rating_span(s["span"])

                        base = {
                            "query": query, "reasoning": reasoning, "spans": spans,
                            "persona_context": persona, "group_id": g_idx, "persona_id": p_idx,
                        }

                        is_dup, sim, closest = dedup.check(query, persona, spans)
                        if is_dup:
                            rejected_dupes += 1
                            print(f"  [SKIP - near-dup sim={sim:.3f}] {query!r} (vs {closest!r})")
                            rejected_out.write(json.dumps({**base, "reject_reason": "near_dup", "similarity": sim, "closest_match": closest}) + "\n")
                            rejected_out.flush()
                            continue

                        retrievable, matched_count = check_retrievability(spans, group_tconsts, con)
                        if retrievable is False:
                            not_retrievable += 1
                            print(f"  [SKIP - not retrievable, {matched_count} other matches] {query!r}")
                            rejected_out.write(json.dumps({**base, "reject_reason": "not_retrievable", "retrieval_match_count": matched_count}) + "\n")
                            rejected_out.flush()
                            continue

                        record = {**base, "retrievable": retrievable, "retrieval_match_count": matched_count}
                        out.write(json.dumps(record) + "\n")
                        out.flush()
                        dedup.add(query, persona, spans)
                        processed += 1
                        print(f"  {query!r} -> {[s['type'] for s in spans]} (retrievable={retrievable}, matches={matched_count})")
                    except (KeyError, TypeError) as e:
                        print(f"  [SKIP - malformed] {item}: {e}")
                        rejected_out.write(json.dumps({"raw_item": item, "reject_reason": "malformed", "error": str(e), "group_id": g_idx, "persona_id": p_idx}) + "\n")
                        rejected_out.flush()
            except Exception as e:
                errors += 1
                print(f"  [ERROR] group={g_idx} persona={p_idx}: {type(e).__name__}: {e}")

    con.close()
    print(f"\n{processed} written, {rejected_dupes} near-dups rejected, {not_retrievable} not-retrievable rejected, {errors} errors")


if __name__ == "__main__":
    main()
