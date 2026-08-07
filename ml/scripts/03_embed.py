"""Embed enriched catalog overviews with EmbeddingGemma.

Input:  ml/data/enriched_titles.parquet (from 02_enrich_tmdb.py)
Output: ml/data/embedded_titles.parquet (adds an `embedding` column, list[f32])

Uses the same model as the Android on-device query embedder
(litert-community/embeddinggemma-300m) so vectors are directly comparable.
"""

import polars as pl
from sentence_transformers import SentenceTransformer

DATA_DIR = "ml/data"
MODEL_NAME = "google/embeddinggemma-300m"


def main() -> None:
    titles = pl.read_parquet(f"{DATA_DIR}/enriched_titles.parquet")
    titles = titles.filter(pl.col("overview") != "")

    model = SentenceTransformer(MODEL_NAME)
    embeddings = model.encode(
        titles["overview"].to_list(),
        normalize_embeddings=True,  # so query time is a plain dot product
        show_progress_bar=True,
    )

    titles = titles.with_columns(pl.Series("embedding", embeddings.tolist()))
    titles.write_parquet(f"{DATA_DIR}/embedded_titles.parquet")
    print(f"{titles.height} titles embedded, dim={embeddings.shape[1]}")


if __name__ == "__main__":
    main()
