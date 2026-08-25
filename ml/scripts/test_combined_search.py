"""Combined facet classifier + vector search pipeline test.

DistilBERT extracts structured facets from a raw query; those facets drive
post-filtering and query rewriting before hitting the embeddinggemma-300m
vector index.  This script exercises the full path end-to-end.

Usage (from ml/):
  uv run python scripts/test_combined_search.py
  uv run python scripts/test_combined_search.py --db data/catalog_vec_int8.db
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

import sqlite_vec
import torch
from sentence_transformers import SentenceTransformer
from transformers import AutoTokenizer

# ── paths ────────────────────────────────────────────────────────────────────

CATALOG_DB = "data/catalog.db"
VEC_DB_DEFAULT = "data/catalog_vec_int8.db"
FACET_CKPT = Path("checkpoints/distilbert_facets")
EMBED_MODEL = "google/embeddinggemma-300m"
DIM = 256
K = 8  # candidates to fetch before post-filter

QUERY_TEMPLATE = "task: search result | query: {text}"

# ── facet model (copied from finetune_encoder_facets.py) ─────────────────────

GENRES = [
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime",
    "Documentary", "Drama", "Family", "Fantasy", "History", "Horror",
    "Music", "Musical", "Mystery", "Romance", "Sci-Fi", "Sport",
    "Thriller", "War", "Western", "Reality-TV", "Short", "News",
    "Talk-Show", "Game-Show", "Adult",
]
N_GENRES = len(GENRES)
POP_NAMES = ["none", "niche", "mainstream"]
YEAR_MIN, YEAR_MAX = 1900, 2030
RUNTIME_MAX_CAP = 240.0
RATING_CAP = 10.0

import torch.nn as nn


class MultiHeadFacetModel(nn.Module):
    def __init__(self, encoder_name: str):
        super().__init__()
        from transformers import AutoModel
        self.encoder = AutoModel.from_pretrained(encoder_name)
        hidden = self.encoder.config.hidden_size
        self.dropout = nn.Dropout(0.1)
        self.genre_head = nn.Linear(hidden, N_GENRES)
        self.has_date_head = nn.Linear(hidden, 1)
        self.date_head = nn.Linear(hidden, 2)
        self.has_runtime_head = nn.Linear(hidden, 1)
        self.runtime_head = nn.Linear(hidden, 1)
        self.has_rating_head = nn.Linear(hidden, 1)
        self.rating_head = nn.Linear(hidden, 1)
        self.popularity_head = nn.Linear(hidden, 3)
        self.has_name_head = nn.Linear(hidden, 1)
        self.has_mood_head = nn.Linear(hidden, 1)
        self.has_other_head = nn.Linear(hidden, 1)

    def forward(self, input_ids, attention_mask):
        out = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        h = self.dropout(out.last_hidden_state[:, 0])
        return {
            "genre_logits": self.genre_head(h),
            "has_date_logits": self.has_date_head(h).squeeze(-1),
            "date_pred": self.date_head(h),
            "has_runtime_logits": self.has_runtime_head(h).squeeze(-1),
            "runtime_pred": self.runtime_head(h).squeeze(-1),
            "has_rating_logits": self.has_rating_head(h).squeeze(-1),
            "rating_pred": self.rating_head(h).squeeze(-1),
            "popularity_logits": self.popularity_head(h),
            "has_name_logits": self.has_name_head(h).squeeze(-1),
            "has_mood_logits": self.has_mood_head(h).squeeze(-1),
            "has_other_logits": self.has_other_head(h).squeeze(-1),
        }


def unit_to_year(u: float) -> int:
    return int(round(YEAR_MIN + float(u) * (YEAR_MAX - YEAR_MIN)))


@torch.no_grad()
def decode_facets(pred: dict, genre_thresh: float = 0.4) -> dict:
    genre_prob = torch.sigmoid(pred["genre_logits"])[0]
    genres = [GENRES[j] for j in range(N_GENRES) if genre_prob[j] >= genre_thresh]
    has_date = torch.sigmoid(pred["has_date_logits"])[0].item() >= 0.5
    has_rt = torch.sigmoid(pred["has_runtime_logits"])[0].item() >= 0.5
    has_rat = torch.sigmoid(pred["has_rating_logits"])[0].item() >= 0.5
    has_mood = torch.sigmoid(pred["has_mood_logits"])[0].item() >= 0.5
    pop = POP_NAMES[int(pred["popularity_logits"][0].argmax().item())]
    date_u = torch.sigmoid(pred["date_pred"])[0]
    rt_u = torch.sigmoid(pred["runtime_pred"])[0].item()
    rat_u = torch.sigmoid(pred["rating_pred"])[0].item()

    out: dict = {
        "genre": genres,
        "has_mood": has_mood,
        "popularity": pop,
        "has_date": has_date,
        "has_runtime": has_rt,
        "has_rating": has_rat,
    }
    if has_date:
        yf = unit_to_year(date_u[0].item())
        yt = unit_to_year(date_u[1].item())
        out["year_from"] = min(yf, yt)
        out["year_to"] = max(yf, yt)
    if has_rt:
        out["runtime_max"] = int(round(rt_u * RUNTIME_MAX_CAP))
    if has_rat:
        out["rating_min"] = round(rat_u * RATING_CAP, 1)
    return out


def load_facet_model(ckpt: Path, device):
    ckpt_data = torch.load(ckpt / "model.pt", map_location="cpu", weights_only=False)
    tok = AutoTokenizer.from_pretrained(ckpt)
    model = MultiHeadFacetModel(ckpt_data["hf"]).to(device)
    model.load_state_dict(ckpt_data["state_dict"])
    model.eval()
    return model, tok


def classify(query: str, model, tok, device) -> dict:
    enc = tok(query, return_tensors="pt", truncation=True, max_length=64, padding=True)
    enc = {k: v.to(device) for k, v in enc.items()}
    with torch.no_grad():
        pred = model(enc["input_ids"], enc["attention_mask"])
    return decode_facets(pred)


# ── vector search ─────────────────────────────────────────────────────────────


def open_vec(vec_db: str, catalog_db: str) -> sqlite3.Connection:
    con = sqlite3.connect(vec_db)
    con.enable_load_extension(True)
    sqlite_vec.load(con)
    con.enable_load_extension(False)
    con.execute(f"ATTACH DATABASE '{catalog_db}' AS cat")
    return con


def build_rewritten_query(raw: str, facets: dict) -> str:
    """Append facet signals to the raw query for the embedding model."""
    parts = [raw]
    if facets.get("genre"):
        parts.append("genre: " + ", ".join(facets["genre"]))
    if facets.get("has_date") and "year_from" in facets:
        yf, yt = facets["year_from"], facets["year_to"]
        parts.append(f"year: {yf}" if yf == yt else f"year: {yf}-{yt}")
    if facets.get("popularity") == "niche":
        parts.append("obscure cult")
    return " | ".join(parts)


def search(
    raw_query: str,
    facets: dict,
    embed_model: SentenceTransformer,
    vec_con: sqlite3.Connection,
    k_fetch: int = K,
) -> list[dict]:
    rewritten = build_rewritten_query(raw_query, facets)
    qvec = embed_model.encode(
        QUERY_TEMPLATE.format(text=rewritten), normalize_embeddings=True
    )

    rows = vec_con.execute(
        """SELECT catalog_meta.title,
                  cat.catalog_items.startYear,
                  cat.catalog_items.genres,
                  cat.catalog_items.averageRating,
                  distance
           FROM catalog_vec
           JOIN catalog_meta ON catalog_meta.rowid = catalog_vec.rowid
           JOIN cat.catalog_items ON cat.catalog_items.tconst = catalog_meta.tconst
           WHERE embedding MATCH ? AND k = ?
           ORDER BY distance""",
        (qvec.tobytes(), k_fetch * 3),  # fetch more, post-filter reduces
    ).fetchall()

    results = []
    for title, year, genres, rating, dist in rows:
        # post-filter: year range
        if facets.get("has_date") and year:
            if not (facets["year_from"] - 5 <= int(year) <= facets["year_to"] + 5):
                continue
        # post-filter: rating
        if facets.get("has_rating") and rating:
            try:
                if float(rating) < facets["rating_min"] - 1.0:
                    continue
            except (ValueError, TypeError):
                pass
        results.append({
            "title": title,
            "year": year,
            "genres": genres,
            "rating": rating,
            "score": round(1 - dist, 3),
        })
        if len(results) >= k_fetch:
            break

    return results


# ── test cases ────────────────────────────────────────────────────────────────

# (persona, raw_query, what_correct_looks_like)
TEST_CASES = [
    # Grazer — mood-driven, no explicit constraints
    ("Grazer",        "something moody and tense to watch tonight",
     "thriller/drama, high tension; no comedy"),
    # Grazer — decade + mood
    ("Grazer",        "something like a slow-burn 70s paranoia thriller",
     "1970s, thriller/drama; slow/atmospheric"),
    # Grazer — feel-good
    ("Grazer",        "a feel-good comedy I can watch with my partner",
     "comedy or romance; light uplifting tone"),
    # Completionist — franchise order
    ("Completionist", "all of Christopher Nolan's films",
     "Nolan filmography; various genres, all years"),
    # Completionist — anime series
    ("Completionist", "every Studio Ghibli movie in release order",
     "animation; Ghibli; chronological range"),
    # Discovery Junkie — obscure niche
    ("Discovery Junkie", "something completely obscure I've never heard of, not mainstream",
     "niche/cult; unusual; popularity=niche"),
    # Discovery Junkie — decade + niche
    ("Discovery Junkie", "a hidden gem from the 90s that nobody talks about",
     "1990s; popularity=niche; underrated"),
    # Social Planner — mixed group constraint
    ("Social Planner", "something the whole family can enjoy, nothing scary or violent",
     "family/comedy/animation; not horror/thriller"),
    # Social Planner — date night
    ("Social Planner", "a good date-night movie, romantic but with some tension",
     "romance + thriller/drama mix"),
    # Critic/Analyst — quality filter
    ("Critic/Analyst", "critically acclaimed psychological dramas, rating above 8",
     "Drama; high rating ≥8; psychological depth"),
    # Critic/Analyst — decade + genre + quality
    ("Critic/Analyst", "the best crime films of the 2000s, highly rated",
     "Crime; 2000-2009; rating ≥7"),
    # Binger — what's new in a genre
    ("Binger",        "new sci-fi shows released in the last few years",
     "Sci-Fi; recent years ~2020+"),
    # Hunter — title lookup (model should detect has_name)
    ("Hunter",        "blade runner 2049",
     "has_name=true; Sci-Fi; 2017"),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default=VEC_DB_DEFAULT)
    parser.add_argument("--k", type=int, default=K)
    args = parser.parse_args()

    if not Path(args.db).exists():
        sys.exit(f"Vector DB not found: {args.db}")
    if not FACET_CKPT.exists():
        sys.exit(f"Facet checkpoint not found: {FACET_CKPT}")

    device = (
        torch.device("cuda") if torch.cuda.is_available()
        else torch.device("mps") if torch.backends.mps.is_available()
        else torch.device("cpu")
    )
    print(f"Device: {device}")
    print("Loading facet model...")
    facet_model, tok = load_facet_model(FACET_CKPT, device)
    print("Loading embedding model...")
    embed_model = SentenceTransformer(EMBED_MODEL, device=str(device), truncate_dim=DIM)

    vec_con = open_vec(args.db, CATALOG_DB)
    n = vec_con.execute("SELECT COUNT(*) FROM catalog_vec").fetchone()[0]
    print(f"Vector index: {n} titles  |  DB: {args.db}\n")
    print("=" * 80)

    for persona, query, expected in TEST_CASES:
        facets = classify(query, facet_model, tok, device)
        results = search(query, facets, embed_model, vec_con, k_fetch=args.k)

        print(f"\n[{persona}]  {query!r}")
        print(f"  facets → genre={facets['genre']}  mood={facets['has_mood']}"
              f"  pop={facets['popularity']}", end="")
        if facets.get("has_date"):
            print(f"  date={facets['year_from']}-{facets['year_to']}", end="")
        if facets.get("has_runtime"):
            print(f"  runtime≤{facets['runtime_max']}min", end="")
        if facets.get("has_rating"):
            print(f"  rating≥{facets['rating_min']}", end="")
        if facets.get("has_name"):
            print(f"  [title query]", end="")
        print()
        print(f"  expect → {expected}")
        print(f"  {'score':6s}  {'title':40s}  {'year':6s}  {'genres':30s}  {'⭐':4s}")
        for r in results:
            print(f"  {r['score']:6.3f}  {r['title'][:40]:40s}  {str(r['year'] or '?'):6s}"
                  f"  {str(r['genres'] or ''):30s}  {r['rating'] or '?'}")

    vec_con.close()
    print("\n" + "=" * 80)


if __name__ == "__main__":
    main()
