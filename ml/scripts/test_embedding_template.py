"""Prototype ADR 0002/0007's embedding approach end-to-end against sqlite-vec.

Builds a `vec0` table (not a plain BLOB column, not a parquet file) from a
sample of ml/data/catalog.db, using the exact document template Track B2
specifies: `"title: {title} | text: {overview}"`. Then queries it with
Track B3's query template (`"task: search result | query: {text}"`) using
two kinds of free-text queries:

  1. a title-shaped query ("the dark knight") — can title search work
     through the same embedding, or does it need FTS instead?
  2. a plot/mood-shaped query ("a guy loses his memory every day and
     writes notes to himself") — the primary use case the template is
     designed for.

This validates two things the ml/scripts/04_export_sqlite.py path skipped:
whether the single asymmetric document embedding is dual-purpose (title +
overview recall) or whether title matching still needs FTS4 as a separate
signal (which is what ADR 0002/0007 already assume via RRF - this script
checks whether that assumption is necessary or just cheap insurance).

Input:  ml/data/catalog.db (catalog_items)
Output: ml/data/embedding_template_probe.db (throwaway vec0 table, mirrors
        the schema Track B1 will use on-device) + printed retrieval results
"""

import sqlite3

import sqlite_vec
from sentence_transformers import SentenceTransformer

CATALOG_DB = "ml/data/catalog.db"
PROBE_DB = "ml/data/embedding_template_probe.db"
MODEL_NAME = "google/embeddinggemma-300m"
SAMPLE_SIZE = 3000  # titles with non-empty overview, enough to be a real retrieval test
DIM = 768

DOCUMENT_TEMPLATE = "title: {title} | text: {overview}"
QUERY_TEMPLATE = "task: search result | query: {text}"

TEST_QUERIES = [
    ("title-shaped", "the dark knight"),
    ("title-shaped", "toy story"),
    ("plot-shaped", "a guy loses his memory every day and writes notes to himself"),
    ("mood-shaped", "feeling nostalgic"),
    ("mood-shaped", "something thrilling and tense"),
]


def open_vec_db(path: str) -> sqlite3.Connection:
    con = sqlite3.connect(path)
    con.enable_load_extension(True)
    sqlite_vec.load(con)
    con.enable_load_extension(False)
    return con


def main() -> None:
    catalog_con = sqlite3.connect(CATALOG_DB)
    rows = catalog_con.execute(
        f"""SELECT tconst, title, overview FROM catalog_items
            WHERE overview != '' ORDER BY numVotes DESC LIMIT {SAMPLE_SIZE}"""
    ).fetchall()
    catalog_con.close()
    print(f"loaded {len(rows)} titles with overview text (highest numVotes first)")

    model = SentenceTransformer(MODEL_NAME)

    documents = [DOCUMENT_TEMPLATE.format(title=title, overview=overview) for _, title, overview in rows]
    doc_vectors = model.encode(documents, normalize_embeddings=True, show_progress_bar=True)

    con = open_vec_db(PROBE_DB)
    con.execute("DROP TABLE IF EXISTS catalog_vec")
    con.execute(f"CREATE VIRTUAL TABLE catalog_vec USING vec0(embedding float[{DIM}])")
    con.execute("DROP TABLE IF EXISTS catalog_meta")
    con.execute("CREATE TABLE catalog_meta (rowid INTEGER PRIMARY KEY, tconst TEXT, title TEXT)")

    for i, ((tconst, title, _overview), vector) in enumerate(zip(rows, doc_vectors)):
        con.execute("INSERT INTO catalog_vec (rowid, embedding) VALUES (?, ?)", (i, vector.tobytes()))
        con.execute("INSERT INTO catalog_meta (rowid, tconst, title) VALUES (?, ?, ?)", (i, tconst, title))
    con.commit()
    print(f"wrote {len(rows)} vectors into vec0 table at {PROBE_DB}")

    query_texts = [QUERY_TEMPLATE.format(text=q) for _, q in TEST_QUERIES]
    query_vectors = model.encode(query_texts, normalize_embeddings=True)

    for (kind, raw_query), qvec in zip(TEST_QUERIES, query_vectors):
        print(f"\n[{kind}] query: {raw_query!r}")
        results = con.execute(
            """SELECT catalog_meta.title, distance
               FROM catalog_vec
               JOIN catalog_meta ON catalog_meta.rowid = catalog_vec.rowid
               WHERE embedding MATCH ? AND k = 5
               ORDER BY distance""",
            (qvec.tobytes(),),
        ).fetchall()
        for title, distance in results:
            print(f"    {1 - distance:.4f}  {title}")

    con.close()


if __name__ == "__main__":
    main()
