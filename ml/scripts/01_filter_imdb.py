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
# IMDb ratings are out of 10, not 5 - 6.58 was the mean of the unfiltered
# 1990+ set, so 3.8 kept nearly everything. Raised to sit above the mean.
MIN_RATING = 6.5
# A high rating from a handful of voters isn't reliable - without this,
# obscure titles with e.g. 3 votes and a 9.0 rating pass right through.
MIN_VOTES = 500


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
    ).filter(
        pl.col("averageRating") >= MIN_RATING,
        pl.col("numVotes") >= MIN_VOTES,
    )

    filtered = basics.join(ratings, on="tconst", how="inner").collect()
    print(f"{filtered.height} titles after filtering")
    filtered.write_parquet(f"{DATA_DIR}/filtered_titles.parquet")


if __name__ == "__main__":
    main()
