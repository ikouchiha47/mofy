"""Tests for facet model decode_batch using the trained checkpoint.

Run from ml/:
  uv run pytest tests/test_facet_decode.py -v
"""

import pytest
import torch

CHECKPOINT = "checkpoints/distilbert_facets"


@pytest.fixture(scope="module")
def model_and_tok():
    from transformers import AutoTokenizer
    from train.finetune_encoder_facets import MultiHeadFacetModel

    ckpt = torch.load(f"{CHECKPOINT}/model.pt", map_location="cpu", weights_only=False)
    model = MultiHeadFacetModel(ckpt["hf"])
    model.load_state_dict(ckpt["state_dict"], strict=False)
    model.eval()
    tok = AutoTokenizer.from_pretrained(CHECKPOINT)
    return model, tok


def _run(model_and_tok, queries: list[str]) -> list[dict]:
    from train.finetune_encoder_facets import decode_batch, DEFAULT_GENRE_THRESH

    model, tok = model_and_tok
    enc = tok(queries, return_tensors="pt", truncation=True, max_length=64, padding=True)
    enc = {k: v for k, v in enc.items() if k != "token_type_ids"}
    with torch.no_grad():
        pred = model(**enc)
    results = decode_batch(queries, pred, genre_thresh=DEFAULT_GENRE_THRESH)
    for q, r in zip(queries, results):
        print(f"\n  Q: {q!r}")
        print(f"     genre={r['genre']}  excluded={r['excluded_genre']}  has_name={r['has_name']}  has_date={r['has_date']}")
    return results


# ── genre head ────────────────────────────────────────────────────────────────

class TestGenre:
    def test_scifi_query_returns_scifi(self, model_and_tok):
        out = _run(model_and_tok, ["critically acclaimed sci-fi under 2 hours"])[0]
        assert "Sci-Fi" in out["genre"]

    def test_thriller_query_returns_thriller(self, model_and_tok):
        out = _run(model_and_tok, ["dark psychological thriller from the 80s under 2 hours"])[0]
        assert "Thriller" in out["genre"]

    def test_comedy_query_returns_comedy(self, model_and_tok):
        out = _run(model_and_tok, ["nostalgic 90s comedy hidden gem"])[0]
        assert "Comedy" in out["genre"]

    def test_scifi_does_not_include_comedy(self, model_and_tok):
        # verify threshold is 0.5 — noise genres (0.3-0.47) must not appear
        out = _run(model_and_tok, ["mind-bending sci-fi movies high rated"])[0]
        assert "Comedy" not in out["genre"]

    def test_scifi_does_not_include_action(self, model_and_tok):
        out = _run(model_and_tok, ["mind-bending sci-fi movies high rated"])[0]
        assert "Action" not in out["genre"]


# ── excluded_genre (rule-based) ───────────────────────────────────────────────

class TestExcludedGenre:
    def test_no_action_or_war(self, model_and_tok):
        out = _run(model_and_tok, ["2010s drama no action or war"])[0]
        assert "Action" in out["excluded_genre"]
        assert "War" in out["excluded_genre"]

    def test_without_horror(self, model_and_tok):
        out = _run(model_and_tok, ["thriller without horror 90s"])[0]
        assert "Horror" in out["excluded_genre"]

    def test_no_comedy_or_animation(self, model_and_tok):
        out = _run(model_and_tok, ["good 2010s drama no comedy or animation"])[0]
        assert "Comedy" in out["excluded_genre"]
        assert "Animation" in out["excluded_genre"]

    def test_excluded_not_in_genre(self, model_and_tok):
        # excluded genres must be removed from include list
        out = _run(model_and_tok, ["2010s drama no action or war"])[0]
        assert "Action" not in out["genre"]
        assert "War" not in out["genre"]

    def test_no_negation_means_empty_excluded(self, model_and_tok):
        out = _run(model_and_tok, ["dark psychological thriller from the 80s"])[0]
        assert out["excluded_genre"] == []

    def test_excluded_only_literal_genre_words(self, model_and_tok):
        # "not for kids" has no genre word after "not" — must return []
        out = _run(model_and_tok, ["drama not for kids 2000s"])[0]
        assert out["excluded_genre"] == []

    def test_not_x_but_y_excludes_x(self, model_and_tok):
        # "but" stops negation scan — only action excluded, romance included
        out = _run(model_and_tok, ["not action but romance 90s"])[0]
        assert "Action" in out["excluded_genre"]
        assert "Action" not in out["genre"]

    def test_not_x_but_y_includes_y(self, model_and_tok):
        out = _run(model_and_tok, ["not action but romance 90s"])[0]
        assert "Romance" not in out["excluded_genre"]

    def test_not_x_but_y_drama(self, model_and_tok):
        out = _run(model_and_tok, ["drama not comedy but thriller 2000s"])[0]
        assert "Comedy" in out["excluded_genre"]
        assert "Comedy" not in out["genre"]
        assert "Thriller" not in out["excluded_genre"]


# ── has_name head ─────────────────────────────────────────────────────────────

class TestHasName:
    def test_zhang_ziyi(self, model_and_tok):
        out = _run(model_and_tok, ["movies with Zhang Ziyi"])[0]
        assert out["has_name"] is True

    def test_derek_jarman(self, model_and_tok):
        out = _run(model_and_tok, ["films directed by derek jarman 1991"])[0]
        assert out["has_name"] is True

    def test_christopher_nolan(self, model_and_tok):
        out = _run(model_and_tok, ["something like Christopher Nolan"])[0]
        assert out["has_name"] is True

    def test_no_name_in_generic_query(self, model_and_tok):
        out = _run(model_and_tok, ["good 90s drama"])[0]
        assert out["has_name"] is False


# ── has_date head ─────────────────────────────────────────────────────────────

class TestHasDate:
    def test_decade_detected(self, model_and_tok):
        out = _run(model_and_tok, ["nostalgic 90s comedy hidden gem"])[0]
        assert out["has_date"] is True

    def test_year_detected(self, model_and_tok):
        out = _run(model_and_tok, ["films directed by derek jarman 1991"])[0]
        assert out["has_date"] is True

    def test_no_date_in_generic(self, model_and_tok):
        out = _run(model_and_tok, ["critically acclaimed sci-fi under 2 hours"])[0]
        assert out["has_date"] is False


# ── has_runtime head ──────────────────────────────────────────────────────────

class TestHasRuntime:
    def test_runtime_detected(self, model_and_tok):
        out = _run(model_and_tok, ["sci-fi under 2 hours"])[0]
        assert out["has_runtime"] is True

    def test_no_runtime_without_mention(self, model_and_tok):
        out = _run(model_and_tok, ["nostalgic 90s comedy hidden gem"])[0]
        assert out["has_runtime"] is False


# ── has_rating head ───────────────────────────────────────────────────────────

class TestHasRating:
    def test_must_watch(self, model_and_tok):
        out = _run(model_and_tok, ["what are some must-watch dramas from the 90s"])[0]
        assert out["has_rating"] is True

    def test_top_films(self, model_and_tok):
        out = _run(model_and_tok, ["show me top sci-fi films from the 2010s"])[0]
        assert out["has_rating"] is True

    def test_no_rating_in_plain_query(self, model_and_tok):
        out = _run(model_and_tok, ["2000s drama nothing fancy"])[0]
        assert out["has_rating"] is False


# ── has_mood head ─────────────────────────────────────────────────────────────

class TestHasMood:
    def test_cozy(self, model_and_tok):
        out = _run(model_and_tok, ["something cozy to watch on a rainy day"])[0]
        assert out["has_mood"] is True

    def test_uplifting(self, model_and_tok):
        out = _run(model_and_tok, ["uplifting sports movie from the 2000s"])[0]
        assert out["has_mood"] is True

    def test_dark_brooding(self, model_and_tok):
        out = _run(model_and_tok, ["dark brooding thriller 80s"])[0]
        assert out["has_mood"] is True

    def test_no_mood_plain(self, model_and_tok):
        out = _run(model_and_tok, ["2010s drama"])[0]
        assert out["has_mood"] is False


# ── popularity head ───────────────────────────────────────────────────────────

class TestPopularity:
    def test_hidden_gem(self, model_and_tok):
        out = _run(model_and_tok, ["hidden gem romance from the 90s nobody talks about"])[0]
        assert out["popularity"] == "niche"

    def test_under_the_radar(self, model_and_tok):
        out = _run(model_and_tok, ["under the radar sci-fi from 2000s"])[0]
        assert out["popularity"] == "niche"

    def test_blockbuster(self, model_and_tok):
        out = _run(model_and_tok, ["big budget action blockbuster 2010s"])[0]
        assert out["popularity"] == "mainstream"

    def test_mainstream(self, model_and_tok):
        out = _run(model_and_tok, ["something mainstream 2015 drama"])[0]
        assert out["popularity"] == "mainstream"

    def test_no_qualifier(self, model_and_tok):
        out = _run(model_and_tok, ["2010s drama"])[0]
        assert out["popularity"] == "none"


# ── has_other head ────────────────────────────────────────────────────────────

class TestHasOther:
    def test_french_new_wave(self, model_and_tok):
        out = _run(model_and_tok, ["french new wave drama 1960s"])[0]
        assert out["has_other"] is True

    def test_set_in_tokyo(self, model_and_tok):
        out = _run(model_and_tok, ["drama set in tokyo 2000s"])[0]
        assert out["has_other"] is True

    def test_no_other_in_generic(self, model_and_tok):
        out = _run(model_and_tok, ["90s comedy"])[0]
        assert out["has_other"] is False
