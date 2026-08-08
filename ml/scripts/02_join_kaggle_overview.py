"""Join overview text from Kaggle's "The Movies Dataset" into the filtered
IMDb catalog, where available - a free, one-time, zero-API-call source for
a subset of titles, in place of live TMDB calls (see docs/research/ for
why: movies-only, ~2019 cutoff, so TV series and 2018+ titles won't match).

Input:
    ml/data/filtered_titles.parquet          (from 01_filter_imdb.py)
    ml/data/kaggle_movies/movies_metadata.csv (movies_metadata.csv + links.csv
                                                extracted from Kaggle's
                                                rounakbanik/the-movies-dataset
                                                archive.zip)
Output:
    ml/data/catalog_titles.parquet - filtered_titles.parquet's columns plus
    `overview` (empty string where no match, not null - matches the rest of
    the pipeline's FTS4-style "no NULLs" convention).
"""

import polars as pl

DATA_DIR = "ml/data"


def main() -> None:
    filtered = pl.read_parquet(f"{DATA_DIR}/filtered_titles.parquet")

    kaggle = pl.read_csv(
        f"{DATA_DIR}/kaggle_movies/movies_metadata.csv",
        columns=["imdb_id", "overview"],
        schema_overrides={"imdb_id": pl.String},
        ignore_errors=True,
    ).filter(
        pl.col("imdb_id").is_not_null(),
        pl.col("overview").is_not_null(),
        pl.col("overview") != "",
    ).unique(subset=["imdb_id"])

    catalog = filtered.join(kaggle, left_on="tconst", right_on="imdb_id", how="left").with_columns(
        pl.col("overview").fill_null(""),
    )
    matched = catalog.filter(pl.col("overview") != "").height
    print(f"{matched} / {catalog.height} titles have overview text ({matched / catalog.height * 100:.1f}%)")
    catalog.write_parquet(f"{DATA_DIR}/catalog_titles.parquet")


if __name__ == "__main__":
    main()
