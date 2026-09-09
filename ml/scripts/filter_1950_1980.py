"""Filter IMDb non-commercial datasets down to movies from 1950-1980.

Separate from 01_filter_imdb.py (which hardcodes MIN_YEAR=1990 for the main
catalog) - this is a one-off pull for a vintage-movies pass, kept apart so it
doesn't disturb the main filtered_titles.parquet pipeline.

Inputs (already present in ml/data/):
    title.basics.tsv.gz
    title.ratings.tsv.gz

Output:
    ml/data/vintage_1950_1980.json
"""

import json

import polars as pl

DATA_DIR = "ml/data"
MIN_YEAR = 1950
MAX_YEAR = 1980
# Vote threshold kept lower than the main pipeline's 500 - older titles
# accumulate far fewer IMDb votes than modern ones, so the same bar would
# throw away a lot of legitimate classics. Rating raised to 7.5 to keep this
# list to genuinely well-regarded titles.
MIN_RATING = 7.4
MIN_VOTES = 1000


def main() -> None:
    basics = pl.scan_csv(
        f"{DATA_DIR}/title.basics.tsv.gz",
        separator="\t",
        null_values="\\N",
        quote_char=None,
    ).filter(
        pl.col("titleType") == "movie",
        pl.col("startYear").cast(pl.Int32, strict=False) >= MIN_YEAR,
        pl.col("startYear").cast(pl.Int32, strict=False) <= MAX_YEAR,
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

    filtered = (
        basics.select(["tconst", "primaryTitle", "originalTitle", "startYear",
                        "runtimeMinutes", "genres"])
        .join(ratings, on="tconst", how="inner")
    ).collect().sort(["startYear", "primaryTitle"])

    print(f"{filtered.height} titles after filtering ({MIN_YEAR}-{MAX_YEAR})")
    records = filtered.to_dicts()
    with open(f"{DATA_DIR}/vintage_1950_1980.json", "w", encoding="utf-8") as f:
        json.dump(records, f, indent=2)
    print(f"wrote {DATA_DIR}/vintage_1950_1980.json")


if __name__ == "__main__":
    main()
