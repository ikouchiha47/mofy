"""Multi-head encoder facet classifier (DistilBERT / BERT-tiny).

Replaces free-form seq2seq (JSON/TANL) with a fixed output schema:

  query → encoder → CLS vector → independent heads
    - genre: multi-label over canonical catalog genres
    - date: has_date + year_from + year_to
    - runtime: has_runtime + runtime_max_minutes
    - rating: has_rating + rating_min
    - popularity: none | niche | mainstream
    - title / mood / other: binary presence

No generated text → structure is always valid. Correctness = per-slot metrics.

Usage (from ml/):
  uv run python train/finetune_encoder_facets.py --data-only
  uv run python train/finetune_encoder_facets.py --model distilbert --epochs 5
  uv run python train/finetune_encoder_facets.py --model bert-tiny --epochs 8
  uv run python train/finetune_encoder_facets.py --model both --epochs 5 --demo
  uv run python train/finetune_encoder_facets.py --checkpoint checkpoints/distilbert_facets --demo
"""

from __future__ import annotations

import argparse
import json
import random
import re
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset

# ---------------------------------------------------------------------------
# Paths / constants
# ---------------------------------------------------------------------------

DATA_FILES = [
    "data/scaled_examples_batch1.jsonl",
    "data/scaled_examples_batch_1.jsonl",
    "data/scaled_examples_batch_2.jsonl",
    "data/scaled_examples_batch_3.jsonl",
    "data/scaled_examples_batch_4.jsonl",
    "data/grounded_queries.jsonl",
]

# Canonical genres from catalog.db (order is stable label index).
GENRES = [
    "Action",
    "Adventure",
    "Animation",
    "Biography",
    "Comedy",
    "Crime",
    "Documentary",
    "Drama",
    "Family",
    "Fantasy",
    "History",
    "Horror",
    "Music",
    "Musical",
    "Mystery",
    "Romance",
    "Sci-Fi",
    "Sport",
    "Thriller",
    "War",
    "Western",
    "Reality-TV",
    "Short",
    "News",
    "Talk-Show",
    "Game-Show",
    "Adult",
]
GENRE_TO_IDX = {g: i for i, g in enumerate(GENRES)}
N_GENRES = len(GENRES)

# Map free-text genre spans (when normalized is null) → canonical names.
GENRE_ALIASES: list[tuple[re.Pattern[str], list[str]]] = [
    (re.compile(r"\bsci[- ]?fi\b|\bscience fiction\b", re.I), ["Sci-Fi"]),
    (re.compile(r"\bromantic comedy\b|\brom[- ]?com\b", re.I), ["Romance", "Comedy"]),
    (re.compile(r"\bneo[- ]?noir\b", re.I), ["Crime", "Thriller"]),
    (re.compile(r"\bpsychological horror\b", re.I), ["Horror"]),
    (re.compile(r"\bpsychological thriller\b", re.I), ["Thriller"]),
    (re.compile(r"\bcrime dramas?\b", re.I), ["Crime", "Drama"]),
    (re.compile(r"\bindie dramas?\b", re.I), ["Drama"]),
    (re.compile(r"\banimated\b|\banimation\b|\banime\b", re.I), ["Animation"]),
    (re.compile(r"\bdocumentar", re.I), ["Documentary"]),
    (re.compile(r"\bcomed", re.I), ["Comedy"]),
    (re.compile(r"\bhorrors?\b", re.I), ["Horror"]),
    (re.compile(r"\bthrillers?\b|\bsuspense\b", re.I), ["Thriller"]),
    (re.compile(r"\baction\b", re.I), ["Action"]),
    (re.compile(r"\bdramas?\b", re.I), ["Drama"]),
    (re.compile(r"\bromance\b|\bromantic\b", re.I), ["Romance"]),
    (re.compile(r"\bmystery\b", re.I), ["Mystery"]),
    (re.compile(r"\bfantasy\b", re.I), ["Fantasy"]),
    (re.compile(r"\bwar\b|\bwartime\b", re.I), ["War"]),
    (re.compile(r"\bhistory\b|\bhistorical\b|\bperiod\b", re.I), ["History"]),
    (re.compile(r"\bfamily\b", re.I), ["Family"]),
    (re.compile(r"\bwestern\b", re.I), ["Western"]),
    (re.compile(r"\bcrime\b", re.I), ["Crime"]),
    (re.compile(r"\bmusic(al)?\b", re.I), ["Music"]),
    (re.compile(r"\bsport\b", re.I), ["Sport"]),
    (re.compile(r"\bheist\b", re.I), ["Crime", "Thriller"]),
    (re.compile(r"\bsuperhero\b", re.I), ["Action", "Adventure"]),
]

POP_NICHE = re.compile(
    r"hidden gem|under[- ]?the[- ]?radar|obscure|deep cut|underrated|"
    r"overlooked|lesser known|cult|indie|slept on|forgotten|unheralded|"
    r"underappreciated|niche",
    re.I,
)
POP_MAIN = re.compile(
    r"\bpopular\b|blockbuster|mainstream|well[- ]?known|widely watched|"
    r"famous|hit\b",
    re.I,
)

# Decade / year phrases when normalized date is missing.
DECADE_RE = re.compile(
    r"\b(?:the\s+)?(?:(?P<d2>\d{2})|(?P<d4>\d{4}))s\b|"
    r"\b(?P<y4>19\d{2}|20\d{2})\b|"
    r"\b'(?P<y2>\d{2})\b",
    re.I,
)

YEAR_MIN, YEAR_MAX = 1900, 2030
RUNTIME_MAX_CAP = 240.0  # normalize runtime targets into [0, 1]
RATING_CAP = 10.0

POP_NONE, POP_NICHE_I, POP_MAIN_I = 0, 1, 2
POP_NAMES = ["none", "niche", "mainstream"]

MODEL_PRESETS = {
    "distilbert": {
        "hf": "distilbert-base-uncased",
        "out_dir": Path("checkpoints/distilbert_facets"),
        "max_len": 64,
        "batch": 32,
        "lr": 3e-5,
    },
    "bert-tiny": {
        # google L-2 H-128 — same size class as prajjwal1/bert-tiny, but ships a
        # modern tokenizer.json that loads cleanly on transformers 5.x
        "hf": "google/bert_uncased_L-2_H-128_A-2",
        "out_dir": Path("checkpoints/bert_tiny_facets"),
        "max_len": 64,
        "batch": 64,
        "lr": 5e-5,
    },
    "bert-mini": {
        # google L-4 H-256_A-4 — "BERT-mini" family, good quality/size tradeoff
        "hf": "google/bert_uncased_L-4_H-256_A-4",
        "out_dir": Path("checkpoints/bert_mini_facets"),
        "max_len": 64,
        "batch": 64,
        "lr": 5e-5,
    },
}


# ---------------------------------------------------------------------------
# Label building
# ---------------------------------------------------------------------------


def _canon_genre(name: str) -> str | None:
    n = name.strip()
    if n in GENRE_TO_IDX:
        return n
    # case-insensitive exact
    for g in GENRES:
        if g.lower() == n.lower():
            return g
    # light cleanup
    n2 = n.replace("Science Fiction", "Sci-Fi").replace("SciFi", "Sci-Fi")
    if n2 in GENRE_TO_IDX:
        return n2
    return None


def genres_from_span_text(text: str) -> list[str]:
    found: list[str] = []
    for pat, gens in GENRE_ALIASES:
        if pat.search(text):
            for g in gens:
                if g not in found:
                    found.append(g)
    return found


def parse_date_from_text(text: str) -> tuple[int, int] | None:
    """Best-effort year range from span/query text when normalized is missing."""
    t = text.lower()
    # explicit decades like 90s / 1980s / 2010s
    m = re.search(r"\b(?:the\s+)?(\d{2})s\b", t)
    if m and "19" not in m.group(0) and "20" not in m.group(0):
        d = int(m.group(1))
        # 00s–20s → 2000s; 30s–90s → 1900s
        century = 2000 if d <= 20 else 1900
        start = century + d
        return start, start + 9
    m = re.search(r"\b(?:the\s+)?((?:19|20)\d{2})s\b", t)
    if m:
        start = int(m.group(1))
        return start, start + 9
    m = re.search(r"\b((?:19|20)\d{2})\b", t)
    if m:
        y = int(m.group(1))
        return y, y
    m = re.search(r"'(\d{2})\b", t)
    if m:
        d = int(m.group(1))
        y = 2000 + d if d <= 30 else 1900 + d
        return y, y
    return None


def popularity_class(span_text: str) -> int:
    if POP_NICHE.search(span_text):
        return POP_NICHE_I
    if POP_MAIN.search(span_text):
        return POP_MAIN_I
    # unknown popularity phrase → treat as niche-ish signal present
    return POP_NICHE_I


@dataclass
class FacetLabel:
    query: str
    genre: list[float] = field(default_factory=lambda: [0.0] * N_GENRES)
    has_date: float = 0.0
    year_from: float = 0.0  # absolute year (0 if none)
    year_to: float = 0.0
    has_runtime: float = 0.0
    runtime_max: float = 0.0  # minutes
    has_rating: float = 0.0
    rating_min: float = 0.0
    popularity: int = POP_NONE  # class id
    has_title: float = 0.0
    has_mood: float = 0.0
    has_other: float = 0.0

    def as_dict(self) -> dict:
        genres = [GENRES[i] for i, v in enumerate(self.genre) if v >= 0.5]
        out: dict = {
            "genre": genres,
            "has_date": bool(self.has_date >= 0.5),
            "has_runtime": bool(self.has_runtime >= 0.5),
            "has_rating": bool(self.has_rating >= 0.5),
            "popularity": POP_NAMES[self.popularity],
            "has_title": bool(self.has_title >= 0.5),
            "has_mood": bool(self.has_mood >= 0.5),
            "has_other": bool(self.has_other >= 0.5),
        }
        if out["has_date"]:
            out["year_from"] = int(round(self.year_from))
            out["year_to"] = int(round(self.year_to))
        if out["has_runtime"]:
            out["runtime_max"] = int(round(self.runtime_max))
        if out["has_rating"]:
            out["rating_min"] = round(self.rating_min, 2)
        return out


def spans_to_label(query: str, spans: list[dict]) -> FacetLabel:
    lab = FacetLabel(query=query)
    for s in spans:
        if not isinstance(s, dict):
            continue
        stype = (s.get("type") or "").strip().lower()
        span_text = str(s.get("span") or "")
        nv = s.get("normalized", None)

        if stype == "genre":
            gens: list[str] = []
            if isinstance(nv, list):
                for g in nv:
                    cg = _canon_genre(str(g))
                    if cg:
                        gens.append(cg)
            if not gens:
                gens = genres_from_span_text(span_text)
            for g in gens:
                lab.genre[GENRE_TO_IDX[g]] = 1.0

        elif stype == "date":
            lab.has_date = 1.0
            if isinstance(nv, dict) and "from" in nv and "to" in nv:
                lab.year_from = float(nv["from"])
                lab.year_to = float(nv["to"])
            else:
                parsed = parse_date_from_text(span_text) or parse_date_from_text(query)
                if parsed:
                    lab.year_from, lab.year_to = float(parsed[0]), float(parsed[1])
                else:
                    # presence only; years unknown → leave 0 but has_date=1
                    pass

        elif stype == "runtime":
            lab.has_runtime = 1.0
            if isinstance(nv, dict):
                if "max_minutes" in nv:
                    lab.runtime_max = float(nv["max_minutes"])
                elif "min_minutes" in nv:
                    # store as max proxy for now (schema is runtime_max-focused)
                    lab.runtime_max = float(nv["min_minutes"])
            else:
                m = re.search(r"(\d+)\s*(?:min|minutes|mins)\b", span_text, re.I)
                if m:
                    lab.runtime_max = float(m.group(1))
                elif re.search(r"\b2 hours?\b|\bunder 2\b|\bmax 2\b", span_text, re.I):
                    lab.runtime_max = 120.0
                elif re.search(r"\b90\b", span_text):
                    lab.runtime_max = 90.0

        elif stype == "rating":
            lab.has_rating = 1.0
            if isinstance(nv, (int, float)):
                lab.rating_min = float(nv)
            else:
                # soft default for qualitative rating phrases
                lab.rating_min = 7.0

        elif stype == "popularity":
            lab.popularity = popularity_class(span_text)

        elif stype == "title":
            lab.has_title = 1.0

        elif stype == "mood":
            lab.has_mood = 1.0

        elif stype == "other":
            lab.has_other = 1.0

    return lab


def load_labels(paths: list[str]) -> list[FacetLabel]:
    out: list[FacetLabel] = []
    for path in paths:
        p = Path(path)
        if not p.exists():
            print(f"  [SKIP] {path}")
            continue
        n = 0
        with open(p) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rec = json.loads(line)
                except json.JSONDecodeError:
                    continue
                query = (rec.get("query") or "").strip()
                spans = rec.get("spans") or []
                if not query or not isinstance(spans, list) or not spans:
                    continue
                out.append(spans_to_label(query, spans))
                n += 1
        print(f"  {path}: {n}")
    return out


# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------


class MultiHeadFacetModel(nn.Module):
    """Encoder + fixed schema heads. Always emits the same structure."""

    def __init__(self, encoder_name: str):
        super().__init__()
        from transformers import AutoModel

        self.encoder = AutoModel.from_pretrained(encoder_name)
        hidden = self.encoder.config.hidden_size
        self.dropout = nn.Dropout(0.1)

        self.genre_head = nn.Linear(hidden, N_GENRES)
        self.has_date_head = nn.Linear(hidden, 1)
        self.date_head = nn.Linear(hidden, 2)  # from, to (normalized)
        self.has_runtime_head = nn.Linear(hidden, 1)
        self.runtime_head = nn.Linear(hidden, 1)
        self.has_rating_head = nn.Linear(hidden, 1)
        self.rating_head = nn.Linear(hidden, 1)
        self.popularity_head = nn.Linear(hidden, 3)
        self.has_title_head = nn.Linear(hidden, 1)
        self.has_mood_head = nn.Linear(hidden, 1)
        self.has_other_head = nn.Linear(hidden, 1)

    def forward(self, input_ids, attention_mask):
        out = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        # CLS / first token
        cls = out.last_hidden_state[:, 0]
        h = self.dropout(cls)
        return {
            "genre_logits": self.genre_head(h),
            "has_date_logits": self.has_date_head(h).squeeze(-1),
            "date_pred": self.date_head(h),  # sigmoid; sigmoid later for years
            "has_runtime_logits": self.has_runtime_head(h).squeeze(-1),
            "runtime_pred": self.runtime_head(h).squeeze(-1),
            "has_rating_logits": self.has_rating_head(h).squeeze(-1),
            "rating_pred": self.rating_head(h).squeeze(-1),
            "popularity_logits": self.popularity_head(h),
            "has_title_logits": self.has_title_head(h).squeeze(-1),
            "has_mood_logits": self.has_mood_head(h).squeeze(-1),
            "has_other_logits": self.has_other_head(h).squeeze(-1),
        }


def year_to_unit(y: float) -> float:
    if y <= 0:
        return 0.0
    return max(0.0, min(1.0, (y - YEAR_MIN) / (YEAR_MAX - YEAR_MIN)))


def unit_to_year(u: float) -> int:
    y = YEAR_MIN + float(u) * (YEAR_MAX - YEAR_MIN)
    return int(round(max(YEAR_MIN, min(YEAR_MAX, y))))


# ---------------------------------------------------------------------------
# Dataset
# ---------------------------------------------------------------------------


class FacetDataset(Dataset):
    def __init__(self, labels: list[FacetLabel], tokenizer, max_len: int):
        self.labels = labels
        self.tok = tokenizer
        self.max_len = max_len

    def __len__(self):
        return len(self.labels)

    def __getitem__(self, idx):
        lab = self.labels[idx]
        enc = self.tok(
            lab.query,
            truncation=True,
            max_length=self.max_len,
            padding="max_length",
            return_tensors="pt",
        )
        item = {
            "input_ids": enc["input_ids"].squeeze(0),
            "attention_mask": enc["attention_mask"].squeeze(0),
            "genre": torch.tensor(lab.genre, dtype=torch.float),
            "has_date": torch.tensor(lab.has_date, dtype=torch.float),
            "year_from_u": torch.tensor(year_to_unit(lab.year_from), dtype=torch.float),
            "year_to_u": torch.tensor(year_to_unit(lab.year_to), dtype=torch.float),
            "has_runtime": torch.tensor(lab.has_runtime, dtype=torch.float),
            "runtime_u": torch.tensor(
                min(lab.runtime_max / RUNTIME_MAX_CAP, 1.0) if lab.runtime_max else 0.0,
                dtype=torch.float,
            ),
            "has_rating": torch.tensor(lab.has_rating, dtype=torch.float),
            "rating_u": torch.tensor(
                min(lab.rating_min / RATING_CAP, 1.0) if lab.rating_min else 0.0,
                dtype=torch.float,
            ),
            "popularity": torch.tensor(lab.popularity, dtype=torch.long),
            "has_title": torch.tensor(lab.has_title, dtype=torch.float),
            "has_mood": torch.tensor(lab.has_mood, dtype=torch.float),
            "has_other": torch.tensor(lab.has_other, dtype=torch.float),
        }
        return item


# ---------------------------------------------------------------------------
# Loss / metrics / decode
# ---------------------------------------------------------------------------


def compute_loss(
    pred: dict,
    batch: dict,
    device,
    genre_pos_weight: torch.Tensor | None = None,
    date_weight: float = 3.0,
) -> torch.Tensor:
    # per-genre pos_weight counters Drama collapse on rare genres
    if genre_pos_weight is not None:
        bce_genre = nn.BCEWithLogitsLoss(pos_weight=genre_pos_weight.to(device))
    else:
        bce_genre = nn.BCEWithLogitsLoss()
    bce = nn.BCEWithLogitsLoss()
    ce = nn.CrossEntropyLoss()
    mse = nn.MSELoss(reduction="none")

    loss = bce_genre(pred["genre_logits"], batch["genre"])
    loss = loss + bce(pred["has_date_logits"], batch["has_date"])
    loss = loss + bce(pred["has_runtime_logits"], batch["has_runtime"])
    loss = loss + bce(pred["has_rating_logits"], batch["has_rating"])
    # title is rare (~7%) — upweight presence
    loss = loss + 2.0 * bce(pred["has_title_logits"], batch["has_title"])
    loss = loss + bce(pred["has_mood_logits"], batch["has_mood"])
    loss = loss + bce(pred["has_other_logits"], batch["has_other"])
    loss = loss + ce(pred["popularity_logits"], batch["popularity"])

    # regression only where the slot is present (mask); date upweighted
    date_target = torch.stack([batch["year_from_u"], batch["year_to_u"]], dim=-1)
    date_err = mse(torch.sigmoid(pred["date_pred"]), date_target).mean(dim=-1)
    date_mask = batch["has_date"]
    if date_mask.sum() > 0:
        loss = loss + date_weight * (date_err * date_mask).sum() / date_mask.sum().clamp(min=1)

    rt_err = mse(torch.sigmoid(pred["runtime_pred"]), batch["runtime_u"])
    rt_mask = batch["has_runtime"]
    if rt_mask.sum() > 0:
        loss = loss + (rt_err * rt_mask).sum() / rt_mask.sum().clamp(min=1)

    rat_err = mse(torch.sigmoid(pred["rating_pred"]), batch["rating_u"])
    rat_mask = batch["has_rating"]
    if rat_mask.sum() > 0:
        loss = loss + (rat_err * rat_mask).sum() / rat_mask.sum().clamp(min=1)

    return loss


def genre_pos_weights(labels: list[FacetLabel]) -> torch.Tensor:
    """pos_weight_i = neg_i / pos_i, capped so rare genres still train."""
    pos = torch.zeros(N_GENRES)
    for lab in labels:
        for i, v in enumerate(lab.genre):
            if v >= 0.5:
                pos[i] += 1
    n = float(len(labels))
    neg = n - pos
    w = torch.ones(N_GENRES)
    for i in range(N_GENRES):
        if pos[i] > 0:
            w[i] = min(20.0, float(neg[i] / pos[i]))
        else:
            w[i] = 1.0
    return w


@torch.no_grad()
def decode_batch(pred: dict, genre_thresh: float = 0.5, bin_thresh: float = 0.5) -> list[dict]:
    genre_prob = torch.sigmoid(pred["genre_logits"])
    has_date = torch.sigmoid(pred["has_date_logits"]) >= bin_thresh
    has_rt = torch.sigmoid(pred["has_runtime_logits"]) >= bin_thresh
    has_rat = torch.sigmoid(pred["has_rating_logits"]) >= bin_thresh
    has_title = torch.sigmoid(pred["has_title_logits"]) >= bin_thresh
    has_mood = torch.sigmoid(pred["has_mood_logits"]) >= bin_thresh
    has_other = torch.sigmoid(pred["has_other_logits"]) >= bin_thresh
    pop = pred["popularity_logits"].argmax(dim=-1)
    date_u = torch.sigmoid(pred["date_pred"])
    rt_u = torch.sigmoid(pred["runtime_pred"])
    rat_u = torch.sigmoid(pred["rating_pred"])

    bsz = genre_prob.size(0)
    results = []
    for i in range(bsz):
        genres = [GENRES[j] for j in range(N_GENRES) if genre_prob[i, j] >= genre_thresh]
        d: dict = {
            "genre": genres,
            "has_date": bool(has_date[i].item()),
            "has_runtime": bool(has_rt[i].item()),
            "has_rating": bool(has_rat[i].item()),
            "popularity": POP_NAMES[int(pop[i].item())],
            "has_title": bool(has_title[i].item()),
            "has_mood": bool(has_mood[i].item()),
            "has_other": bool(has_other[i].item()),
        }
        if d["has_date"]:
            d["year_from"] = unit_to_year(date_u[i, 0].item())
            d["year_to"] = unit_to_year(date_u[i, 1].item())
            if d["year_to"] < d["year_from"]:
                d["year_from"], d["year_to"] = d["year_to"], d["year_from"]
        if d["has_runtime"]:
            d["runtime_max"] = int(round(rt_u[i].item() * RUNTIME_MAX_CAP))
        if d["has_rating"]:
            d["rating_min"] = round(rat_u[i].item() * RATING_CAP, 2)
        results.append(d)
    return results


def _f1(tp, fp, fn) -> float:
    if tp == 0 and fp == 0 and fn == 0:
        return 1.0
    p = tp / (tp + fp) if (tp + fp) else 0.0
    r = tp / (tp + fn) if (tp + fn) else 0.0
    return 2 * p * r / (p + r) if (p + r) else 0.0


@torch.no_grad()
def evaluate(model, loader, device, genre_pos_weight: torch.Tensor | None = None) -> dict:
    model.eval()
    # binary slots
    bin_keys = [
        ("has_date", "has_date_logits"),
        ("has_runtime", "has_runtime_logits"),
        ("has_rating", "has_rating_logits"),
        ("has_title", "has_title_logits"),
        ("has_mood", "has_mood_logits"),
        ("has_other", "has_other_logits"),
    ]
    bin_stats = {k: Counter() for k, _ in bin_keys}
    genre_tp = genre_fp = genre_fn = 0
    pop_correct = pop_total = 0
    date_abs = []
    runtime_abs = []
    rating_abs = []
    n = 0
    loss_sum = 0.0

    for batch in loader:
        batch = {k: v.to(device) for k, v in batch.items()}
        pred = model(batch["input_ids"], batch["attention_mask"])
        loss_sum += compute_loss(pred, batch, device, genre_pos_weight=genre_pos_weight).item()
        decoded = decode_batch(pred)
        bsz = batch["input_ids"].size(0)
        n += bsz

        gold_genre = batch["genre"].cpu()
        for i in range(bsz):
            # genre multi-label micro
            for j in range(N_GENRES):
                g = gold_genre[i, j].item() >= 0.5
                p = j < len(GENRES) and GENRES[j] in decoded[i]["genre"]
                if g and p:
                    genre_tp += 1
                elif p and not g:
                    genre_fp += 1
                elif g and not p:
                    genre_fn += 1

            # binary
            gold_bin = {
                "has_date": batch["has_date"][i].item() >= 0.5,
                "has_runtime": batch["has_runtime"][i].item() >= 0.5,
                "has_rating": batch["has_rating"][i].item() >= 0.5,
                "has_title": batch["has_title"][i].item() >= 0.5,
                "has_mood": batch["has_mood"][i].item() >= 0.5,
                "has_other": batch["has_other"][i].item() >= 0.5,
            }
            for k, _ in bin_keys:
                g, p = gold_bin[k], decoded[i][k]
                if g and p:
                    bin_stats[k]["tp"] += 1
                elif p and not g:
                    bin_stats[k]["fp"] += 1
                elif g and not p:
                    bin_stats[k]["fn"] += 1
                else:
                    bin_stats[k]["tn"] += 1

            # popularity
            pop_total += 1
            gold_pop = POP_NAMES[int(batch["popularity"][i].item())]
            if decoded[i]["popularity"] == gold_pop:
                pop_correct += 1

            # continuous errors when gold present
            if gold_bin["has_date"] and decoded[i].get("has_date"):
                gf = unit_to_year(batch["year_from_u"][i].item())
                gt = unit_to_year(batch["year_to_u"][i].item())
                date_abs.append(
                    (abs(decoded[i]["year_from"] - gf) + abs(decoded[i]["year_to"] - gt)) / 2
                )
            if gold_bin["has_runtime"] and decoded[i].get("has_runtime"):
                gr = batch["runtime_u"][i].item() * RUNTIME_MAX_CAP
                runtime_abs.append(abs(decoded[i]["runtime_max"] - gr))
            if gold_bin["has_rating"] and decoded[i].get("has_rating"):
                gr = batch["rating_u"][i].item() * RATING_CAP
                rating_abs.append(abs(decoded[i]["rating_min"] - gr))

    metrics = {
        "loss": loss_sum / max(len(loader), 1),
        "genre_f1": _f1(genre_tp, genre_fp, genre_fn),
        "popularity_acc": pop_correct / max(pop_total, 1),
        "date_mae_years": sum(date_abs) / len(date_abs) if date_abs else None,
        "runtime_mae_min": sum(runtime_abs) / len(runtime_abs) if runtime_abs else None,
        "rating_mae": sum(rating_abs) / len(rating_abs) if rating_abs else None,
        "n": n,
    }
    for k, st in bin_stats.items():
        metrics[f"{k}_f1"] = _f1(st["tp"], st["fp"], st["fn"])
    return metrics


def print_metrics(tag: str, m: dict):
    print(f"\n=== {tag} (n={m['n']}) ===")
    print(f"  loss={m['loss']:.4f}")
    print(f"  genre_f1={m['genre_f1']:.3f}  popularity_acc={m['popularity_acc']:.3f}")
    for k in ("has_date", "has_runtime", "has_rating", "has_title", "has_mood", "has_other"):
        print(f"  {k}_f1={m[f'{k}_f1']:.3f}")
    if m["date_mae_years"] is not None:
        print(f"  date_mae_years={m['date_mae_years']:.1f}")
    if m["runtime_mae_min"] is not None:
        print(f"  runtime_mae_min={m['runtime_mae_min']:.1f}")
    if m["rating_mae"] is not None:
        print(f"  rating_mae={m['rating_mae']:.2f}")


# ---------------------------------------------------------------------------
# Train / infer
# ---------------------------------------------------------------------------


def data_stats(labels: list[FacetLabel]):
    print(f"Total labels: {len(labels)}")
    gcount = Counter()
    for lab in labels:
        for i, v in enumerate(lab.genre):
            if v >= 0.5:
                gcount[GENRES[i]] += 1
    print("Genre positives:", dict(gcount.most_common()))
    print(
        "Presence rates:",
        {
            "date": sum(1 for l in labels if l.has_date) / len(labels),
            "runtime": sum(1 for l in labels if l.has_runtime) / len(labels),
            "rating": sum(1 for l in labels if l.has_rating) / len(labels),
            "title": sum(1 for l in labels if l.has_title) / len(labels),
            "mood": sum(1 for l in labels if l.has_mood) / len(labels),
            "other": sum(1 for l in labels if l.has_other) / len(labels),
            "pop_niche": sum(1 for l in labels if l.popularity == POP_NICHE_I) / len(labels),
            "pop_main": sum(1 for l in labels if l.popularity == POP_MAIN_I) / len(labels),
        },
    )
    print("Sample decoded labels:")
    for lab in labels[:5]:
        print(f"  Q: {lab.query!r}")
        print(f"     → {lab.as_dict()}")


def train_one(model_key: str, labels: list[FacetLabel], args) -> Path:
    from transformers import AutoTokenizer

    preset = MODEL_PRESETS[model_key]
    device = (
        torch.device("cuda")
        if torch.cuda.is_available()
        else torch.device("mps")
        if torch.backends.mps.is_available()
        else torch.device("cpu")
    )
    print(f"\n----- training {model_key} ({preset['hf']}) on {device} -----")

    random.shuffle(labels)
    if args.smoke:
        labels = labels[:400]
    split = max(1, int(len(labels) * 0.9))
    train_l, val_l = labels[:split], labels[split:]
    print(f"train={len(train_l)} val={len(val_l)}")

    tok = AutoTokenizer.from_pretrained(preset["hf"])
    train_ds = FacetDataset(train_l, tok, preset["max_len"])
    val_ds = FacetDataset(val_l, tok, preset["max_len"])
    train_loader = DataLoader(train_ds, batch_size=preset["batch"], shuffle=True)
    val_loader = DataLoader(val_ds, batch_size=preset["batch"] * 2, shuffle=False)

    model = MultiHeadFacetModel(preset["hf"]).to(device)
    opt = torch.optim.AdamW(model.parameters(), lr=preset["lr"], weight_decay=0.01)
    epochs = 2 if args.smoke else args.epochs
    g_w = genre_pos_weights(train_l)
    print("genre pos_weight (top):", {GENRES[i]: f"{g_w[i]:.1f}" for i in g_w.argsort(descending=True)[:8].tolist()})

    best_score = -1.0
    best_path = preset["out_dir"]
    best_path.mkdir(parents=True, exist_ok=True)

    for epoch in range(1, epochs + 1):
        model.train()
        running = 0.0
        for step, batch in enumerate(train_loader, 1):
            batch = {k: v.to(device) for k, v in batch.items()}
            pred = model(batch["input_ids"], batch["attention_mask"])
            loss = compute_loss(pred, batch, device, genre_pos_weight=g_w)
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            running += loss.item()
            if step % 50 == 0:
                print(f"  epoch {epoch} step {step}/{len(train_loader)} loss={running/step:.4f}")

        metrics = evaluate(model, val_loader, device, genre_pos_weight=g_w)
        print_metrics(f"{model_key} epoch {epoch} val", metrics)
        # composite score: genre f1 + mean binary f1 + pop acc
        bin_f1s = [
            metrics[f"{k}_f1"]
            for k in ("has_date", "has_runtime", "has_rating", "has_title", "has_mood", "has_other")
        ]
        score = metrics["genre_f1"] + metrics["popularity_acc"] + sum(bin_f1s) / len(bin_f1s)
        if score > best_score:
            best_score = score
            torch.save(
                {
                    "model_key": model_key,
                    "hf": preset["hf"],
                    "state_dict": model.state_dict(),
                    "genres": GENRES,
                    "metrics": metrics,
                },
                best_path / "model.pt",
            )
            tok.save_pretrained(best_path)
            with open(best_path / "metrics.json", "w") as f:
                json.dump({k: (float(v) if isinstance(v, (int, float)) else v) for k, v in metrics.items()}, f, indent=2)
            print(f"  saved best → {best_path} (score={score:.3f})")

    return best_path


@torch.no_grad()
def demo(checkpoint: Path, queries: list[str]):
    from transformers import AutoTokenizer

    ckpt = torch.load(checkpoint / "model.pt", map_location="cpu", weights_only=False)
    device = (
        torch.device("cuda")
        if torch.cuda.is_available()
        else torch.device("mps")
        if torch.backends.mps.is_available()
        else torch.device("cpu")
    )
    tok = AutoTokenizer.from_pretrained(checkpoint)
    model = MultiHeadFacetModel(ckpt["hf"]).to(device)
    model.load_state_dict(ckpt["state_dict"])
    model.eval()

    print(f"\n=== demo ({checkpoint}) ===")
    for q in queries:
        enc = tok(q, return_tensors="pt", truncation=True, max_length=64, padding=True)
        enc = {k: v.to(device) for k, v in enc.items()}
        pred = model(enc["input_ids"], enc["attention_mask"])
        out = decode_batch(pred)[0]
        print(f"\n  Q: {q}")
        print(f"  → {json.dumps(out, ensure_ascii=False)}")


DEMO_QUERIES = [
    "dark psychological thriller from the 80s under 2 hours",
    "something chill for a Friday night",
    "critically acclaimed sci-fi under 2 hours",
    "nostalgic 90s comedy hidden gem",
    "download inception 2010 1080p",
    "good 90s war dramas max 2 hours long",
    "mind-bending sci-fi movies high rated",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data-only", action="store_true")
    parser.add_argument("--model", choices=["distilbert", "bert-tiny", "bert-mini", "both"], default="both")
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--smoke", action="store_true", help="tiny run to verify pipeline")
    parser.add_argument("--demo", action="store_true")
    parser.add_argument("--checkpoint", type=Path, help="run demo only on this checkpoint")
    args = parser.parse_args()

    # run from ml/
    root = Path(__file__).resolve().parent.parent
    import os

    os.chdir(root)

    if args.checkpoint:
        demo(args.checkpoint, DEMO_QUERIES)
        return

    print("Loading examples → fixed schema labels...")
    labels = load_labels(DATA_FILES)
    if not labels:
        raise SystemExit("No labels built.")
    data_stats(labels)
    if args.data_only:
        return

    keys = ["distilbert", "bert-tiny"] if args.model == "both" else [args.model]
    paths = []
    for k in keys:
        # fresh copy so shuffle in train_one doesn't share mutation oddly across models
        paths.append(train_one(k, list(labels), args))

    if args.demo:
        for p in paths:
            demo(p, DEMO_QUERIES)


if __name__ == "__main__":
    main()
