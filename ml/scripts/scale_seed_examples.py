"""Scale the 23 hand-written Claude seed examples (seed_examples_batch1.jsonl)
into a much larger training set via gemini-3.6-flash - the "self-instruct"
pattern: a small number of high-quality hand-written examples as few-shot
exemplars, a capable model generates many new varied examples following that
pattern, in controlled batches (not 1000-at-once, to keep quality checkable
and avoid the repetition/collapse failure modes found earlier at scale).

Offline/batch use only (ml/ pipeline) - this never runs on-device. Switched
from qwen3.5:4b to gemini-3.6-flash after a side-by-side comparison showed
Gemini producing consistently cleaner, correctly-labeled output (no typos,
no invented span types, no inappropriate content).
"""

import argparse
import glob
import json
import os
import random
import re

import dspy
from pydantic import BaseModel
from typing import Literal

from batch_lib import RateLimiter
from dedup_lib import SemanticDedup

GEMINI_MODEL = "gemini/gemini-3.6-flash"
SEED_PATH = "data/seed_examples_batch1.jsonl"
OUTPUT_DIR = "data"
BATCH_SIZE = 15  # new examples generated per call - small enough to review, large enough to be efficient

RATE_LIMIT_CALLS = 40  # conservative cap, same margin-under-the-real-limit style as 03_enrich_tmdb_gaps.py
RATE_LIMIT_WINDOW_SECS = 10

# Boundary-case seeds that must always be shown as reference context, since
# random 4-of-23 sampling was found to sometimes omit the exact precedent
# needed to avoid genre-attractor bias reappearing in generated output (e.g.
# "dark and gritty atmosphere" mislabeled genre instead of mood when seed #4
# wasn't sampled).
ALWAYS_INCLUDE_SEED_QUERIES = {
    "dark and twisty psychological thriller",  # mood+genre split precedent
    "feeling nostalgic tonight",  # pure-mood precedent
    "family movie night pick, nothing scary for the kids",  # context-vs-genre precedent
}


class SpanLabel(BaseModel):
    span: str
    type: Literal["title", "genre", "mood", "date", "rating", "popularity", "runtime", "other"]


class GenerateVariations(dspy.Signature):
    """Given example movie/TV search queries (with their correct facet
    labeling and reasoning), generate NEW, DIFFERENT queries in the same
    style and difficulty - not paraphrases of the examples, genuinely new
    queries a real user might type, covering the same personas/situations.
    Each new query must come with its own correct reasoning and span
    labeling, following the same labeling conventions shown in the examples
    (e.g. context phrases like "family movie night" or "date night" are
    mood, not genre; director/actor/language references go to "other";
    ambiguous quality judgments go to rating).

    Time-relative vibe words with no explicit year/decade attached (retro,
    throwback, vintage, dated, contemporary, of-the-moment, nostalgic) are
    mood, not date - same convention as "nostalgic" already established:
    it's a feeling being requested, not a literal release-year filter.
    Explicit years/decades ("90s", "from 2015") are still date. "cult
    classic"/"cult following" is popularity, same family as "hidden gem" -
    it signals a small-but-devoted audience, not a genre or mood."""

    example_queries: str = dspy.InputField(desc="reference examples showing the query style and labeling conventions")
    persona_context: str = dspy.InputField(desc="what kind of user/situation to generate for")
    count: int = dspy.InputField(desc="how many new examples to generate")
    new_examples: list[dict] = dspy.OutputField(
        desc='list of {"query": str, "reasoning": str, "spans": [{"span": str, "type": str}]} objects'
    )


# Grounded in Mofy's actual established personas (docs/adrs/0008), not
# invented ad hoc - only Hunter and Grazer type free-text search queries at
# all (Archivist uses a directory picker, Resumer/Binger/Completionist are
# feed/notification-driven, never query-driven). Within Grazer, split by
# Netflix's own published search-intent research (Lamkhede & Das, SIGIR '19,
# "Challenges in Search on Streaming Services"): real user studies found
# three intent mindsets - Fetch (~75% of searches, one specific title),
# Find (a formulated need but no specific title - e.g. actor-driven, or a
# specific constraint), and Explore (broad, open browsing). Hunter maps
# cleanly to Fetch. The ADR's single "Grazer" bucket was collapsing Find and
# Explore together, which is likely why it kept collapsing into one
# formulaic shape - they're genuinely different query shapes.
PERSONA_CONTEXTS = [
    # Hunter (Fetch) - knows exactly what they want, title-driven
    "Hunter persona (Fetch intent): exact title search, sometimes with a format/quality/year modifier, wants immediate direct retrieval",
    "Hunter persona (Fetch intent): partial or misremembered title, still clearly aiming at one specific known entity",
    # Grazer/Find - formulated need, no specific title in mind, expects the system to narrow down a small set
    "Grazer persona (Find intent): actor or director-driven, willing to watch any of their work, no specific title in mind",
    "Grazer persona (Find intent): a specific hard constraint drives the search (runtime limit, content exclusion for kids, a group's competing preferences) but no title in mind",
    "Grazer persona (Find intent): direct comparison to a known title, wants something with a similar plot mechanic, tone, or structure - not the title itself",
    # Grazer/Explore - broad, open browsing, wants a slate to browse through
    "Grazer persona (Explore intent): solo evening mood browsing, no title in mind, wants an immediate vibe match for how they feel right now",
    "Grazer persona (Explore intent): broad genre or era browsing with no specific constraint, open to a wide slate of results",
    "Grazer persona (Explore intent): wants something obscure/under-the-radar/overlooked, indirect popularity signals, resists simple genre labeling",
]


def load_gemini_api_key() -> str:
    key = os.environ.get("GEMINI_API_KEY")
    if key:
        return key
    creds_path = os.path.expanduser("~/.credentials")
    with open(creds_path) as f:
        for line in f:
            if line.startswith("GEMINI_API_KEY="):
                return line.strip().split("=", 1)[1]
    raise RuntimeError(f"GEMINI_API_KEY not found in env or {creds_path}")


def build_reference_text(seeds: list[dict]) -> str:
    always = [r for r in seeds if r["query"] in ALWAYS_INCLUDE_SEED_QUERIES]
    pool = [r for r in seeds if r["query"] not in ALWAYS_INCLUDE_SEED_QUERIES]
    rest = random.sample(pool, min(2, len(pool)))
    ref = always + rest
    return "\n\n".join(
        f"Query: {r['query']!r}\nReasoning: {r['reasoning']}\nSpans: {json.dumps(r['spans'])}"
        for r in ref
    )


# Below this pool size for a persona, farthest-from-centroid selection isn't
# meaningful yet (too few points to cluster sensibly) - fall back to the
# fixed seed-based reference text (NeMo Curator's "cold-start" fallback).
MIN_POOL_FOR_CENTROID_SELECTION = 30


def build_dynamic_reference_text(
    dedup: SemanticDedup, seeds: list[dict], persona: str, used_references: set[str],
) -> tuple[str, list[str]]:
    """Farthest-from-centroid steering (NeMo Curator's approach): show the
    model atypical examples already generated for this persona, not the
    over-represented centroid phrasing, plus a cross-iteration uniqueness
    filter so the steering set itself doesn't go stale. Falls back to the
    static seed-based reference text when the pool is still too small.
    Returns (reference_text, newly_used_reference_queries)."""
    persona_count = sum(1 for p in dedup.personas if p == persona)
    if persona_count < MIN_POOL_FOR_CENTROID_SELECTION:
        return build_reference_text(seeds), []

    n_clusters = min(5, persona_count // 6)
    farthest = dedup.farthest_from_centroid_examples(persona, n_clusters=n_clusters, used_examples=used_references)
    if not farthest:
        return build_reference_text(seeds), []

    # still anchor on 2 fixed seed examples for the labeling conventions
    # themselves (span types, mood-vs-genre rules) - the dynamic examples
    # are for topical/phrasing diversity, not a replacement for the
    # ground-truth labeling rules.
    always = [r for r in seeds if r["query"] in ALWAYS_INCLUDE_SEED_QUERIES]
    seed_text = "\n\n".join(
        f"Query: {r['query']!r}\nReasoning: {r['reasoning']}\nSpans: {json.dumps(r['spans'])}"
        for r in always
    )
    dynamic_text = (
        "Additional reference queries already generated for this persona - these are the "
        "MOST atypical examples produced so far (farthest from the common pattern), included "
        "to show the range of variation possible. Generate genuinely new queries, not "
        "paraphrases of the examples above or below:\n"
        + "\n".join(f"- {q!r}" for q in farthest)
    )
    return seed_text + "\n\n" + dynamic_text, farthest


def next_output_path() -> str:
    # auto-increments off whatever scaled_examples_batch_N.jsonl files
    # already exist, so each run gets its own file without editing a
    # constant by hand
    existing = glob.glob(f"{OUTPUT_DIR}/scaled_examples_batch_*.jsonl")
    nums = [int(m.group(1)) for f in existing if (m := re.search(r"batch_(\d+)\.jsonl$", f))]
    next_id = max(nums, default=0) + 1
    return f"{OUTPUT_DIR}/scaled_examples_batch_{next_id}.jsonl"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--num-batches", type=int, default=72, help=f"batches to run this call, {BATCH_SIZE} examples each")
    parser.add_argument("--output", default=None, help="output path; defaults to next auto-numbered scaled_examples_batchN.jsonl")
    parser.add_argument("--dedup-threshold", type=float, default=0.85, help="cosine similarity above which a new example is rejected as a near-duplicate")
    parser.add_argument("--persona-filter", default=None, help="only run batches for personas whose description contains this substring (e.g. 'Hunter' or 'Find intent')")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    output_path = args.output or next_output_path()
    num_batches = args.num_batches

    lm = dspy.LM(
        GEMINI_MODEL, api_key=load_gemini_api_key(), temperature=0.8, max_tokens=15000,
        frequency_penalty=0.3, presence_penalty=0.2,  # matches NeMo Curator's anti-repetition config
    )
    dspy.configure(lm=lm)

    seeds = [json.loads(line) for line in open(SEED_PATH)]
    generator = dspy.Predict(GenerateVariations)
    limiter = RateLimiter(RATE_LIMIT_CALLS, RATE_LIMIT_WINDOW_SECS)

    dedup = SemanticDedup(threshold=args.dedup_threshold)
    dedup.load_existing(glob.glob(f"{OUTPUT_DIR}/scaled_examples_batch*.jsonl") + [SEED_PATH])
    used_references: set[str] = set()

    active_personas = (
        [p for p in PERSONA_CONTEXTS if args.persona_filter.lower() in p.lower()]
        if args.persona_filter else PERSONA_CONTEXTS
    )
    if not active_personas:
        raise SystemExit(f"--persona-filter {args.persona_filter!r} matched no personas")
    print(f"Active personas ({len(active_personas)}): {[p[:60] for p in active_personas]}")

    valid_types = {"title", "genre", "mood", "date", "rating", "popularity", "runtime", "other"}
    all_new, rejected_dupes = [], 0
    with open(output_path, "w") as out:
        for i in range(num_batches):
            persona_context = active_personas[i % len(active_personas)]
            example_text, newly_used = build_dynamic_reference_text(dedup, seeds, persona_context, used_references)
            used_references.update(newly_used)

            limiter.wait()
            print(f"\n=== Batch {i+1}/{num_batches}: {persona_context} ===")
            pred = generator(
                example_queries=example_text,
                persona_context=persona_context,
                count=BATCH_SIZE,
            )

            for item in pred.new_examples:
                try:
                    query = item["query"]
                    reasoning = item["reasoning"]
                    spans = item["spans"]
                    if not all(s.get("type") in valid_types for s in spans):
                        print(f"  [SKIP - invalid type] {query!r}")
                        continue

                    is_dup, sim, closest = dedup.check(query, persona_context, spans)
                    if is_dup:
                        rejected_dupes += 1
                        print(f"  [SKIP - near-dup sim={sim:.3f}] {query!r}  (vs {closest!r})")
                        continue

                    record = {"query": query, "reasoning": reasoning, "spans": spans, "persona_context": persona_context}
                    all_new.append(record)
                    dedup.add(query, persona_context, spans)
                    out.write(json.dumps(record) + "\n")
                    out.flush()
                    print(f"  {query!r} -> {[s['type'] for s in spans]}")
                except (KeyError, TypeError) as e:
                    print(f"  [SKIP - malformed] {item}: {e}")

    print(f"\n{len(all_new)} new examples written to {output_path} ({rejected_dupes} near-duplicates rejected)")


if __name__ == "__main__":
    main()
