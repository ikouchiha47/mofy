# ADR 0008: Multi-aspect Query Understanding Layer (on-device SLM + EmbeddingGemma), staged rollout

**Status:** accepted

## Context

Phase 09's Grazer-mode mood/plot search needs to go beyond the single-facet
extraction Track C1 scoped (date/genre/rating/popularity/runtime as
independent hard filters). Real Grazer queries are multi-aspect at once —
temporal, aesthetic/texture, emotional direction, structural constraints,
trope/relational, cognitive load/pacing, familiarity/rewatchability — and
need a unified decomposition layer, not seven separate bolted-on rules.

Two pieces of prior art motivate the shape of this:

- **ColBERT** (Contextualized Late Interaction over BERT, 2020) keeps
  token-level embeddings for query and document instead of one vector each,
  scoring by summing each query token's best match against document tokens
  ("late interaction"). This is more precise for compound queries ("enemies
  to lovers + rainy night + slow burn") because different parts of the
  query can match different parts of the overview independently, at the
  cost of more storage and scoring work than single-vector retrieval.
  Efficient variants (ColBERTv2, PLAID) make this practical at larger
  scale; relevant here as a **later-stage upgrade path** for the semantic
  residual search step, once/if single-vector `vec0` retrieval quality
  turns out to be the bottleneck.
- **RecGPT-Mobile** (Taobao/Alibaba, 2026) runs a lightweight on-device LLM
  (quantized ~0.6B) to turn user behavior/context into an explicit
  natural-language intent used to improve feed ranking in real time,
  entirely on-device, under real mobile latency/battery/storage
  constraints. This is a production-oriented precedent that a small LLM can
  do useful intent understanding on-device for recommendation, not just in
  theory — directly relevant to Mofy's Query Understanding Layer.

## Decision

Build the Query Understanding Layer as diagrammed: a multi-aspect
decomposer (on-device SLM + EmbeddingGemma) sitting in front of a five-step
Ranking & Retrieval pipeline (hard filters → soft boosts from aspects →
semantic residual → personalization → RRF), reading from and feeding the
same Local Library (`catalog.db` + user library + watch positions + likes +
TMDB metadata + embeddings).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            MOBILE APP (Android)                             │
│                                                                             │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │   Hunter    │  │   Grazer     │  │   Resumer    │  │ Archivist / etc. │ │
│  │  Search UI  │  │ Mood Query + │  │ Continue +   │  │ Import / WT /    │ │
│  │             │  │ Home Feed    │  │ Notifications│  │ Binger / Comp.   │ │
│  └──────┬──────┘  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘ │
│         │                │                 │                   │           │
│         └────────────────┼─────────────────┼───────────────────┘           │
│                          │                 │                               │
│                          ▼                 ▼                               │
│               ┌──────────────────────────────────────┐                     │
│               │     Query Understanding Layer        │  ← on-device        │
│               │  (Gemma / small SLM + EmbeddingGemma)│                     │
│               │                                      │                     │
│               │  • Multi-aspect decomposer           │                     │
│               │    - temporal prior (soft)           │                     │
│               │    - aesthetic / texture             │                     │
│               │    - emotional direction (Match↔Cure)│                     │
│               │    - structural constraints          │                     │
│               │    - trope / relational              │                     │
│               │    - cognitive load / pacing          │                     │
│               │    - familiarity / rewatchability    │                     │
│               │  • High-confidence hard filters only │                     │
│               │  • Residual → semantic search        │                     │
│               └──────────────────┬───────────────────┘                     │
│                                  │                                         │
│                                  ▼                                         │
│               ┌──────────────────────────────────────┐                     │
│               │          Ranking & Retrieval         │                     │
│               │                                      │                     │
│               │  1. Hard filters (year, runtime…)    │                     │
│               │  2. Soft boosts from aspects         │                     │
│               │  3. Semantic residual                │                     │
│               │     (FTS4 + vec0 or ColBERT-style)   │                     │
│               │  4. Personalization                  │                     │
│               │     (watch history, likes, aspects)  │                     │
│               │  5. RRF / final ranking              │                     │
│               └──────────────────┬───────────────────┘                     │
│                                  │                                         │
│                                  ▼                                         │
│               ┌──────────────────────────────────────┐                     │
│               │            Local Library             │                     │
│               │  catalog.db + user library +         │                     │
│               │  watch positions + likes +           │                     │
│               │  TMDB metadata + embeddings          │                     │
│               └──────────────────────────────────────┘                     │
│                                                                             │
│  Background: new-episode checks, embedding updates, Watch Together sync    │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Aspect list** (decomposer output, multi-label per query):
- Temporal prior (soft)
- Aesthetic/texture
- Emotional direction (Match vs. Cure — mood-management theory's framing:
  does the user want something that matches their current mood, or
  something to shift it)
- Structural constraints
- Trope/relational
- Cognitive load/pacing
- Familiarity/rewatchability

High-confidence aspects become hard filters (same "deterministic or it
doesn't filter" principle already established for date/genre in Track C1);
everything else is residual text feeding semantic search and soft-boost
ranking, not exclusion.

**Model/tech per layer:**

| Layer | Model / tech | Why |
|---|---|---|
| Query Understanding | EmbeddingGemma + small on-device SLM (0.5-2B quantized, e.g. Gemma 3 270M) | Already in the stack via LiteRT (ADR 0002); emits multi-aspect soft signals + high-confidence hard filters |
| Residual semantic search | FTS4 + vec0 (current) → later optional ColBERT-style multi-vector | One embedding is often too coarse for compound Grazer queries |
| Personalization | Aspect vectors + watch history + like/dislike, starting lightweight | Can grow into multi-view aspect modeling later (Track B7 today, deeper later) |
| Everything else | Pure local (SQLite, filesystem, notifications) | No cloud dependency for the core experience, consistent with the app's no-API-LLM-calls constraint |

**Persona mapping:**
- Hunter → Search UI → optional light query understanding → direct library/torrent path
- Grazer → Mood query → full multi-aspect decomposer → ranking with soft signals + semantic residual
- Resumer → Continue Watching row + adaptive notifications (watch-position table only)
- Archivist → Directory picker → TMDB fuzzy match → library insert (no ranking needed)
- Guest/Watch Together → Room code + local file matching by title/TMDB id + playback sync only
- Binger/Completionist → Lightweight scheduled jobs + collection API + progress tracking on the same library

## Staged rollout (evolution path)

1. **MVP**: Hunter + Archivist + basic Grazer — EmbeddingGemma classification
   + soft date/genre (Track C1's existing scope) + overview semantic search
   (Track B, already built and verified against the real catalog).
2. **Next**: Full multi-aspect decomposer — the seven aspect categories
   above as soft signals, on-device SLM introduced at this stage.
3. **Later**: Optional ColBERT-style residual search, richer aspect-based
   personalization, Binger + Completionist polish.

The on-device SLM is a Stage 2 dependency, not MVP — Stage 1 ships on
infrastructure that already exists and is already verified working.

## Consequences

- Track C1/C2 (structured facets) and Track B (embedding retrieval) remain
  Stage 1's actual implementation surface — this ADR doesn't change their
  scope, it sequences a larger layer on top of them.
- Stage 2 adds a fourth on-device model (alongside EmbeddingGemma, OpenNLP,
  and the catalog TMDB pipeline) — needs its own real-device latency/battery
  benchmark before shipping, same caveat ADR 0002 already flagged for
  EmbeddingGemma.
- Stage 3's ColBERT-style upgrade is explicitly deferred until single-vector
  `vec0` retrieval is shown to be the actual bottleneck, not adopted
  preemptively.
- `docs/tasks/09-recommendation-engine.md` needs a new track for the
  decomposer (Stage 2) and a deferred note for ColBERT (Stage 3) — see
  companion task-doc update.
