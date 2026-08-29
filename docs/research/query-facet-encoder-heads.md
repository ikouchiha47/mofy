# Query facet classifier: multi-head encoder (what worked)

**Status:** working prototype (DistilBERT). Informs ADR 0008 Stage-2 query
understanding and Track C1 structured facets.

**Code:** `ml/train/finetune_encoder_facets.py`  
**Checkpoints:** `ml/checkpoints/distilbert_facets/`, `ml/checkpoints/bert_tiny_facets/`  
**Data:** `ml/data/scaled_examples_*.jsonl` + `grounded_queries.jsonl` (~4.2k labeled queries)

---

## Goal

Turn a free-text search query into a **fixed, always-valid structure** for
hard filters + residual semantic search:

```
"dark psychological thriller from the 80s under 2 hours"
→ genre=[Thriller,…]  date=1980–1989  runtime_max=120  mood=yes  …
```

Not “generate JSON.” Correctness + predictable schema.

---

## Architecture (heads)

One shared encoder reads the query once. Each **head** is a small linear
layer on the CLS vector that answers one slot:

```
"dark psychological thriller from the 80s under 2 hours"
                    │
                    ▼
         ┌──────────────────┐
         │  DistilBERT /    │  ← shared encoder
         │  BERT-tiny       │     one CLS vector
         └────────┬─────────┘
                  │
     ┌────────────┼────────────┬─────────────┬──────────────┐
     ▼            ▼            ▼             ▼              ▼
  genre head   date head   runtime head  rating head   popularity head
  multi-label  has + years has + minutes has + min     none|niche|main
     │
     └─ title / mood / other  (binary presence)
```

| Head | Output | Loss |
|------|--------|------|
| genre | 27 multi-label (catalog genres) | BCE + pos_weight |
| date | has_date + year_from/to | BCE + masked MSE |
| runtime | has_runtime + max minutes | BCE + masked MSE |
| rating | has_rating + rating_min | BCE + masked MSE |
| popularity | none / niche / mainstream | CE |
| title, mood, other | yes/no | BCE |

Structure is **defined by the network**, not by a parser. Every inference
returns the same keys.

---

## What we tried before (failed)

| Approach | Model | Result |
|----------|-------|--------|
| Seq2seq → **JSON** spans | flan-t5-small | Broken. T5 SentencePiece maps `{`/`}` → `<unk>`. Model emits garbage like `s"pan:…type:mod`. |
| Seq2seq → **TANL** `[ span \| type \| norm ]` | flan-t5-small | Code switched to TANL; checkpoint still JSON-shaped. Still free-form generation + brittle parse. |
| Few-shot JSON / binary labels | Gemma 270M, Qwen 0.5B | Unreliable open generation; binary “which facets?” only solves presence, not values. |
| DSPy CoT span typing | Qwen variants | Parse errors, invents types. |

**Lesson:** small generative models are bad at freestyle structure. They are
fine at **classification into a fixed schema**.

Low CE loss on T5 did **not** mean usable output — always measure after a
real decoder/parser (or avoid generation entirely).

---

## What worked

**Encoder + multi-head classification** on the existing span-labeled jsonl.

Training (AWS `c5.2xlarge`, CPU, 8 epochs DistilBERT / 12 bert-tiny):

1. Map each example’s `spans[]` → fixed label tensor (genre multi-hot,
   date range, runtime_max, rating_min, popularity class, binary flags).
2. Genre aliases when `normalized` is null (`sci-fi` → Sci-Fi, etc.).
3. Decade phrases when date norm missing (`90s` → 1990–1999).
4. Genre `pos_weight` (capped 20×) so Drama doesn’t dominate rare genres.
5. Regression losses **masked** to examples where the slot is present.

### Val metrics (n=422)

| Metric | DistilBERT | BERT-tiny (L2-H128) |
|--------|------------|---------------------|
| genre F1 | **0.85** | 0.38 |
| popularity acc | 0.93 | 0.91 |
| has_date F1 | 0.99 | 0.98 |
| has_runtime F1 | 0.95 | 0.92 |
| has_rating F1 | 0.91 | 0.86 |
| has_mood F1 | 0.94 | 0.89 |
| has_title F1 | 0.75 | 0.85 |
| has_other F1 | 0.80 | 0.80 |
| date MAE (years) | ~5.5 | ~7 |
| runtime MAE (min) | ~14 | ~16 |

**Ship DistilBERT for quality.** BERT-tiny is too small for multi-label genre
(presence slots OK; genre collapses / over-fires).

### Demo shape (DistilBERT)

```
nostalgic 90s comedy hidden gem
→ genre=[Comedy] has_date has_mood popularity=niche

critically acclaimed sci-fi under 2 hours
→ genre=[Sci-Fi,…] has_runtime has_rating

download inception 2010 1080p
→ has_title has_other has_date
```

Always valid JSON-like dict from `decode_batch` — no string parse step.

---

## Weak spots

1. **Exact years** — decades often land nearby (e.g. “80s” → ~1999–2004, not
   1980–1989). MAE ~5 years. Prefer regex/rules for `\b\d{2,4}s?\b` and
   explicit years; use the head as backup.
2. **Genre bleed** — occasional extra Drama; rare genres still harder.
3. **Title** — only ~7% of train data; F1 ~0.75. Better as FTS/catalog match
   than model-only.
4. **Mood is presence-only** — open mood text still goes to embeddings
   (residual), not a closed mood vocab yet.
5. **Runtime/rating regression** coarse with few positives (runtime ~5% of data).
6. **No on-device path yet** — PyTorch fp32 checkpoints only (see size below).

---

## Size & quantization

| Artifact | Params | Disk (fp32 `.pt`) | Quantized? |
|----------|--------|-------------------|------------|
| DistilBERT facets | 66.4M | **~253 MB** | **No** |
| BERT-tiny facets | 4.4M | **~17 MB** | **No** |

Current checkpoints are **full fp32** state dicts (`model.pt` + tokenizer).
Not int8/ONNX/LiteRT yet.

### Do we need to quantize?

**Yes, before Android ship** if using DistilBERT:

| Target | Approx size | Notes |
|--------|-------------|--------|
| fp32 PyTorch (now) | ~250 MB | Dev only |
| fp16 / bf16 weights | ~125 MB | Easy win |
| int8 dynamic / weight-only | **~60–70 MB** | Same ballpark as old T5 int8 plan |
| int8 + ONNX or LiteRT | ~60 MB + runtime | Real on-device path |
| BERT-tiny int8 | **~5–8 MB** | Tiny, but genre quality not good enough yet |

EmbeddingGemma is already on-device (ADR 0002). A second ~60–70 MB int8
encoder is plausible; 250 MB fp32 is not.

**Recommendation:** keep DistilBERT as the quality model; add int8 (torchao
or ONNX Runtime) + LiteRT/ONNX export before app integration. Don’t ship
bert-tiny for genre until quality improves (more data or distillation from
DistilBERT).

---

## How to run

```bash
cd ml
# stats only
uv run python train/finetune_encoder_facets.py --data-only

# train (local MPS/CPU or AWS c5)
uv run python train/finetune_encoder_facets.py --model distilbert --epochs 8 --demo
uv run python train/finetune_encoder_facets.py --model bert-tiny --epochs 12 --demo

# inference demo from checkpoint
uv run python train/finetune_encoder_facets.py \
  --checkpoint checkpoints/distilbert_facets --demo
```

AWS: existing `c5.2xlarge` pattern in `ml/train/aws_setup.sh` (point at
`finetune_encoder_facets.py` instead of T5). HF token from `~/.credentials`
(`HF_TOKEN=`).

---

## Fit in the product

- **Hard filters:** genre, date, runtime, rating when head confidence high.
- **Soft / residual:** mood text + leftover query → EmbeddingGemma + FTS
  (existing Track B).
- **Title / other:** catalog FTS and format tokens (`1080p`, `download`) more
  than the encoder alone.

Aligns with ADR 0008: high-confidence structured facets + residual semantic
search — without depending on an on-device generative SLM for JSON.
