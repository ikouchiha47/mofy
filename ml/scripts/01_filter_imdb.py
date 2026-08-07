"""Filter IMDb non-commercial datasets down to a candidate catalog.

Inputs (download from https://datasets.imdbws.com/ into ml/data/):
    title.basics.tsv.gz
    title.ratings.tsv.gz

Output:
    ml/data/filtered_titles.parquet
"""

import polars as pl

DATA_DIR = "ml/data"
MIN_YEAR = 1990
MIN_RATING = 3.8  # tune once we see the actual distribution


def main() -> None:
    basics = pl.scan_csv(
        f"{DATA_DIR}/title.basics.tsv.gz",
        separator="\t",
        null_values="\\N",
        quote_char=None,
    ).filter(
        pl.col("titleType").is_in(["movie", "tvSeries"]),
        pl.col("startYear").cast(pl.Int32, strict=False) >= MIN_YEAR,
    )

    ratings = pl.scan_csv(
        f"{DATA_DIR}/title.ratings.tsv.gz",
        separator="\t",
        null_values="\\N",
        quote_char=None,
    ).filter(pl.col("averageRating") >= MIN_RATING)

    filtered = basics.join(ratings, on="tconst", how="inner").collect()
    print(f"{filtered.height} titles after filtering")
    filtered.write_parquet(f"{DATA_DIR}/filtered_titles.parquet")


if __name__ == "__main__":
    main()
