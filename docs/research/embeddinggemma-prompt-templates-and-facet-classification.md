# Research: EmbeddingGemma prompt templates, and can embeddings do query facet classification?

**Status:** research, informs Track B2/B3 (embedding templates, confirmed) and
Track C1 (facet extraction, informs the "why OpenNLP not embeddings" decision
in `docs/tasks/09-recommendation-engine.md`).

## Why this exists

Track B2/B3 committed to specific prompt strings (`title: {} | text: {}` for
documents, `task: search result | query: {}` for queries) citing "verified
against the model card." This doc records the actual verification - what the
model card says, and more importantly what the model's own shipped config
says, since docs can drift from what a release actually ships. It also
records a follow-up experiment: since EmbeddingGemma supports a
`Classification` prompt, could that replace or complement Track C1's planned
OpenNLP-based facet extraction (routing query fragments like "nostalgic",
"2012", "action" to the right structured-filter type)?

## Part 1: the prompt templates are literal, fixed, shipped strings - not a customizable format

Confirmed two ways:

**The model card** (`ai.google.dev/gemma/docs/embeddinggemma/model_card`)
documents these exact strings per use case:

| Use case | Query prompt | Document prompt |
|---|---|---|
| Retrieval (search) - what Phase 09 uses | `task: search result \| query: {content}` | `title: {title \| "none"} \| text: {content}` |
| Question answering | `task: question answering \| query: {content}` | - |
| Fact verification | `task: fact checking \| query: {content}` | - |
| Classification | `task: classification \| query: {content}` | - |
| Clustering | `task: clustering \| query: {content}` | - |
| Semantic similarity | `task: sentence similarity \| query: {content}` | - |
| Code retrieval | `task: code retrieval \| query: {content}` | `retrieval_document` (code blocks) |

**The model's own `config_sentence_transformers.json`** (pulled from the
local HF cache, since the gated repo blocks anonymous fetch - this is the
literal file `sentence-transformers` loads at runtime, not a doc paraphrase):

```json
"prompts": {
  "query": "task: search result | query: ",
  "document": "title: none | text: ",
  "Clustering": "task: clustering | query: ",
  "Classification": "task: classification | query: ",
  "InstructionRetrieval": "task: code retrieval | query: ",
  "PairClassification": "task: sentence similarity | query: ",
  "Retrieval-document": "title: none | text: ",
  "STS": "task: sentence similarity | query: ",
  "Summarization": "task: summarization | query: "
},
"default_prompt_name": null
```

**What this means concretely:** `title:`/`text:`/`task:`/`query:` are not
descriptive labels you could rename (e.g. `{title}: {text}` instead of
`title: {title} | text: {text}`) - they're fixed prefix strings the model was
fine-tuned against, keyed by name in its own config and applied automatically
by `model.encode_query(...)` / `model.encode_document(...)`. The document
default is literally the word `none` for the title slot; Phase 09's B2 task
already correctly overrides that with the real catalog title per the model
card's guidance, rather than using the default.

**Empirical confirmation** (`ml/scripts/test_embedding_template.py`, run
against 3000 real catalog titles via a real `vec0` table): with the correct
templates, title queries ("the dark knight") correctly rank the exact title
first, and the plot query "a guy loses his memory every day and writes notes
to himself" ranks **Memento** #1. This is the retrieval-tuned prompt pair
working as documented, not just documented.

## Part 2: can the `Classification` prompt route query fragments to facet types?

Track C1 (`docs/tasks/09-recommendation-engine.md`) already decided to use
Apache OpenNLP (NER + POS) for facet extraction - detecting which part of a
query is a date, genre, rating phrase, etc. - specifically because "embedding
models reason poorly about numbers/dates from text." Before treating that as
settled, this probe tested it directly: EmbeddingGemma ships a
`task: classification | query: {}` prompt, so it's fair to ask whether a
small set of hand-written anchor phrases per facet type, embedded with that
prompt, could act as a nearest-neighbor facet-type classifier for arbitrary
query fragments - no OpenNLP model download needed if so.

**Method:** 6 facet-type buckets (date, genre, rating, popularity, runtime,
mood), 2-3 hand-written anchor phrases each, all embedded with the
`Classification` prompt. 15 test query fragments embedded the same way,
classified by nearest anchor (cosine similarity).

**Results** (`/tmp/facet_probe.py`, not checked in - throwaway probe):

| term | predicted | notes |
|---|---|---|
| `2012` | date | correct |
| `the nineties` | date | correct |
| `back in '95` | date | correct |
| `90s` | date | correct |
| `action` | genre | correct, but weak margin (0.632 vs 0.628 runner-up) |
| `sci-fi` | genre | correct, strong margin |
| `romantic comedy` | genre | correct, strong margin |
| `highly rated` | rating | correct |
| `acclaimed` | rating | correct |
| `hidden gem` | popularity | correct |
| `popular` | popularity | correct |
| `underrated` | popularity | correct |
| `quick watch` | runtime | correct |
| **`nostalgic`** | **date** (0.730) | **wrong** - mood bucket never wins |
| **`thrilling`** | **popularity** (0.665) | **wrong** - mood bucket never wins |

**Finding: date/genre/rating/popularity/runtime route correctly and with
decent margins, but the mood bucket never wins for genuinely mood-shaped
terms - they get absorbed into date or popularity instead.** "nostalgic"
lands closer to the date anchors than to the mood anchors, because
nostalgia is semantically entangled with "the past" - which is exactly the
same entanglement a human would flag as ambiguous, not a model bug. This is
worth noting because it's the literal case the user asked about earlier in
this conversation ("nostalgic should be tagged as a date") - that intuition
turns out to be empirically what the embedding space does, not a confused
premise.

**Why this doesn't replace OpenNLP for Track C1, but is useful alongside it:**

- For **genre bucket routing** specifically, this reuses infrastructure
  Track C1 already planned to build anyway (the "conceptual relatedness"
  genre-embedding lookup in the "Genre relatedness" section) - no new work,
  same embedding call.
- For **date extraction**, this probe only shows the model can tell "this
  fragment smells date-like" - it does not extract the actual year/decade
  value ("2012" -> `YearRange(2012, 2012)`, "the nineties" -> `YearRange(1990,
  1999)`). That mapping still needs real NER span-finding (OpenNLP) or
  hand-written parsing; embeddings give a similarity score, not a decoded
  value. This confirms the *existing* task doc's stated rationale ("embedding
  models reason poorly about numbers/dates from text") rather than
  overturning it.
- The **mood-vs-date confusion is a real risk for Track C1's design**: if a
  future version of facet extraction leans on embedding-based bucket
  classification instead of OpenNLP's NER (which finds literal date spans,
  not semantic date-adjacency), mood queries like "nostalgic" or "feeling
  old-school" would risk being misfiled as date filters and hard-filtered out
  of the semantic search entirely - a worse failure mode than leaving them as
  unclassified "semantic residue" (which Track C1's design already accounts
  for). This is an argument *for* keeping OpenNLP's literal NER as the
  date/quality-phrase detector, and only using embedding-based routing for
  the genre bucket, where Track C1 already planned to use it.

## Open questions

- The probe's anchor phrases were hand-written and untested at scale (15
  terms, 6 buckets) - not a substitute for Track C1's planned test suite
  ("known query string -> expected extracted predicate(s)").
- Worth re-running this probe with the real `en-ner-date.bin` OpenNLP
  detections as a comparison baseline once Track C1's spike exists, rather
  than just the embedding-only numbers here.
