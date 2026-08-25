"""Embed catalog with embeddinggemma-300m on Modal (L4 GPU).

Usage (from ml/):
  uv run modal run scripts/modal_embed.py

Uploads catalog.db, runs embedding on an L4, downloads catalog_vec.db.
"""

import modal
import re as _re
from pathlib import Path

def _read_hf_token() -> str:
    creds = (Path.home() / ".credentials").read_text()
    token = next(iter(_re.findall(r'hf_[A-Za-z0-9]+', creds)), None)
    if not token:
        raise SystemExit("HF token not found in ~/.credentials")
    return token

image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install(
        "sentence-transformers",
        "sqlite-vec",
        "torch",
        "huggingface_hub",
        "bitsandbytes",
        "accelerate",
    )
)

app = modal.App("mofy-embed", image=image)

CATALOG_DB = Path("data/catalog.db")
VEC_DB = Path("data/catalog_vec.db")

MODEL_NAME = "google/embeddinggemma-300m"
DIM = 256
DOCUMENT_TEMPLATE = "title: {title} | genre: {genres} | year: {year} | text: {text}"
QUERY_TEMPLATE = "task: search result | query: {text}"


@app.function(gpu="T4", timeout=1800)
def embed(catalog_bytes: bytes, hf_token: str) -> bytes:
    import json
    import os
    import sqlite3
    import tempfile

    import sqlite_vec
    from sentence_transformers import SentenceTransformer
    import torch

    def open_db(path):
        con = sqlite3.connect(path)
        con.enable_load_extension(True)
        sqlite_vec.load(con)
        con.enable_load_extension(False)
        return con

    def pick_top2(candidates):
        ranked = sorted((s for s in candidates if s), key=len, reverse=True)
        return " ".join(ranked[:2])

    # write catalog.db to temp file
    with tempfile.TemporaryDirectory() as tmp:
        cat_path = os.path.join(tmp, "catalog.db")
        with open(cat_path, "wb") as f:
            f.write(catalog_bytes)

        con = open_db(cat_path)
        rows = con.execute("""
            SELECT ci.tconst, ci.title, ci.startYear, ci.genres, ci.overview,
                   COALESCE(cp.plots, '[]') AS plots_json
            FROM catalog_items ci
            LEFT JOIN catalog_plots cp ON cp.tconst = ci.tconst
            ORDER BY ci.numVotes DESC
        """).fetchall()

        docs = []
        for tconst, title, year, genres, overview, plots_json in rows:
            imdb_plots_list = json.loads(plots_json)
            candidates = [(overview or "").strip()] + imdb_plots_list
            combined = pick_top2(candidates)
            if not combined:
                continue
            text = DOCUMENT_TEMPLATE.format(
                title=title,
                genres=genres or "unknown",
                year=str(year) if year else "unknown",
                text=combined,
            )
            docs.append((tconst, title, text))

        print(f"Titles to embed: {len(docs)}")

        os.environ["HF_TOKEN"] = hf_token
        device = "cuda" if torch.cuda.is_available() else "cpu"
        print(f"Device: {device}")
        from transformers import BitsAndBytesConfig
        bnb_config = BitsAndBytesConfig(load_in_8bit=True)
        model = SentenceTransformer(
            MODEL_NAME, device=device, truncate_dim=DIM,
            model_kwargs={"quantization_config": bnb_config},
        )
        vectors = model.encode(
            [d[2] for d in docs],
            normalize_embeddings=True,
            batch_size=32,
            show_progress_bar=True,
        )

        vec_path = os.path.join(tmp, "catalog_vec.db")
        vec_con = sqlite3.connect(vec_path)
        vec_con.enable_load_extension(True)
        sqlite_vec.load(vec_con)
        vec_con.enable_load_extension(False)

        vec_con.execute("DROP TABLE IF EXISTS catalog_vec")
        vec_con.execute("DROP TABLE IF EXISTS catalog_meta")
        vec_con.execute(f"CREATE VIRTUAL TABLE catalog_vec USING vec0(embedding float[{DIM}])")
        vec_con.execute("CREATE TABLE catalog_meta (rowid INTEGER PRIMARY KEY, tconst TEXT, title TEXT)")
        for i, ((tconst, title, _), vec) in enumerate(zip(docs, vectors)):
            vec_con.execute("INSERT INTO catalog_vec (rowid, embedding) VALUES (?, ?)", (i, vec.tobytes()))
            vec_con.execute("INSERT INTO catalog_meta VALUES (?, ?, ?)", (i, tconst, title))
        vec_con.commit()
        vec_con.close()

        size_mb = os.path.getsize(vec_path) / 1e6
        print(f"Written {len(docs)} vectors ({size_mb:.0f}MB)")

        return open(vec_path, "rb").read()


@app.local_entrypoint()
def main(out: str = str(VEC_DB)):
    out_path = Path(out)
    if out_path.exists():
        raise SystemExit(f"ERROR: {out_path} already exists. Pass a different --out path.")
    catalog_bytes = CATALOG_DB.read_bytes()
    print(f"Uploading catalog.db ({len(catalog_bytes)/1e6:.0f}MB)...")
    vec_bytes = embed.remote(catalog_bytes, _read_hf_token())
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(vec_bytes)
    print(f"Done. {out_path} written ({len(vec_bytes)/1e6:.0f}MB)")
