"""Export distilbert facet model to ONNX (fp32 + int8) and emit torchao-int8 goldens.

Builds the artifact pair the Android ONNX parity test consumes:
  ml/data/facet_model.onnx        - fp32 distilbert facet ONNX (single file)
  ml/data/facet_model_int8.onnx   - dynamic-quantized int8 ONNX (what would ship)
  ml/data/golden_outputs.json     - torchao int8 (python) logits + decode per query

Golden schema (consumed by android FacetOnnxParityTest):
  {
    "model": str,
    "genres": [27 canonical names],
    "queries": [
      {
        "text": str,
        "input_ids": [64 ints],      # HF-tokenized, pad to 64
        "attention_mask": [64 ints],
        "logits": {
          "genre_logits": [27 floats],
          "has_date_logits": [1], "date_pred": [2],
          "has_runtime_logits": [1], "runtime_pred": [1],
          "has_rating_logits": [1], "rating_pred": [1],
          "popularity_logits": [3],
          "has_name_logits": [1], "has_mood_logits": [1], "has_other_logits": [1],
        },
        "decode": {
          "genre": [str...], "popularity": str,
          "has_date": bool, "has_runtime": bool, "has_rating": bool,
          "has_name": bool, "has_mood": bool, "has_other": bool,
        },
      }
    ]
  }

Usage (from ml/):
  uv run python scripts/07_export_distilbert_onnx_parity.py
"""

import json
import sys
from pathlib import Path

import torch

ROOT = Path(__file__).parent.parent
CKPT = ROOT / "checkpoints" / "distilbert_facets"
OUT = ROOT / "data"
MAX_SEQ_LEN = 64

OUTPUT_NAMES = [
    "genre_logits",
    "has_date_logits",
    "date_pred",
    "has_runtime_logits",
    "runtime_pred",
    "has_rating_logits",
    "rating_pred",
    "popularity_logits",
    "has_name_logits",
    "has_mood_logits",
    "has_other_logits",
]

QUERIES = [
    # canonical demo queries
    "dark psychological thriller from the 80s under 2 hours",
    "critically acclaimed sci-fi under 2 hours",
    "nostalgic 90s comedy hidden gem",
    "mind-bending sci-fi movies high rated",
    "movies with Zhang Ziyi",
    "films directed by derek jarman 1991",
    "something like Christopher Nolan",
    "Joan Chen drama 2004",
    # negation queries (the historically over-broad ones)
    "good 2010s drama no comedy or animation",
    "thriller without horror 90s",
    "drama not for kids 2000s",
    "2010s drama no action or war",
    "2000s romance but no comedy",
    "mystery thriller without comedy or romance 90s",
]


def _load_fp32() -> torch.nn.Module:
    sys.path.insert(0, str(ROOT / "train"))
    from finetune_encoder_facets import MultiHeadFacetModel  # noqa: PLC0415

    ck = torch.load(CKPT / "model.pt", map_location="cpu", weights_only=False)
    m = MultiHeadFacetModel(ck["hf"])
    m.load_state_dict(ck["state_dict"])
    m.eval()
    return m


def _load_int8() -> torch.nn.Module:
    sys.path.insert(0, str(ROOT / "train"))
    from finetune_encoder_facets import MultiHeadFacetModel  # noqa: PLC0415

    ck = torch.load(CKPT / "model_int8.pt", map_location="cpu", weights_only=False)
    m = MultiHeadFacetModel(ck["hf"])
    m.load_state_dict(ck["state_dict"], assign=True)  # AffineQuantizedTensor params
    m.eval()
    return m


def _tokenize(texts: list[str]):
    from transformers import AutoTokenizer  # noqa: PLC0415

    tok = AutoTokenizer.from_pretrained(CKPT)
    enc = tok(
        texts,
        truncation=True,
        max_length=MAX_SEQ_LEN,
        padding="max_length",
        return_tensors="pt",
    )
    return enc["input_ids"], enc["attention_mask"]


def _decode(logits: dict, genres: list[str]) -> dict:
    import numpy as np

    def sigmoid(x: float) -> float:
        return 1.0 / (1.0 + float(np.exp(-x)))

    genre = [
        genres[i]
        for i, v in enumerate(logits["genre_logits"])
        if sigmoid(v) > 0.5
    ]

    def scalar(name: str) -> float:
        v = logits[name]
        return float(v[0]) if isinstance(v, list) else float(v)

    return {
        "genre": genre,
        "popularity": ["none", "niche", "mainstream"][int(np.argmax(logits["popularity_logits"]))],
        "has_date": sigmoid(scalar("has_date_logits")) > 0.5,
        "has_runtime": sigmoid(scalar("has_runtime_logits")) > 0.5,
        "has_rating": sigmoid(scalar("has_rating_logits")) > 0.5,
        "has_name": sigmoid(scalar("has_name_logits")) > 0.5,
        "has_mood": sigmoid(scalar("has_mood_logits")) > 0.5,
        "has_other": sigmoid(scalar("has_other_logits")) > 0.5,
    }


def export_onnx(model: torch.nn.Module, dest: Path, dynamic_seq: bool = True) -> None:
    """Export to ONNX. Fixed-shape (seq=64) is REQUIRED for onnxruntime's int8
    quantizer: the dynamic seq_len axis emits a Range node that breaks its
    symbolic shape inference (AssertionError in _infer_Range). The app always
    pads to MAX_SEQ_LEN=64 anyway, so fixed-shape is what ships."""
    if dynamic_seq:
        dummy_ids = torch.zeros(1, MAX_SEQ_LEN, dtype=torch.long)
        dummy_mask = torch.ones(1, MAX_SEQ_LEN, dtype=torch.long)
        dynamic_axes = {"input_ids": {1: "seq_len"}, "attention_mask": {1: "seq_len"}}
    else:
        dummy_ids = torch.zeros(1, MAX_SEQ_LEN, dtype=torch.long)
        dummy_mask = torch.ones(1, MAX_SEQ_LEN, dtype=torch.long)
        dynamic_axes = None

    torch.onnx.export(
        model,
        (dummy_ids, dummy_mask),
        str(dest),
        input_names=["input_ids", "attention_mask"],
        output_names=OUTPUT_NAMES,
        dynamic_axes=dynamic_axes,
        opset_version=17,
    )
    # inline external weights -> single self-contained file
    import onnx
    from onnx.external_data_helper import load_external_data_for_model

    proto = onnx.load(str(dest))
    load_external_data_for_model(proto, base_dir=str(dest.parent))
    data_file = Path(str(dest) + ".data")
    if data_file.exists():
        data_file.unlink()
    onnx.save(proto, str(dest))
    print(f"  {dest.name}: {dest.stat().st_size / 1e6:.1f} MB")


def main() -> None:
    OUT.mkdir(exist_ok=True)
    sys.path.insert(0, str(ROOT / "train"))
    from finetune_encoder_facets import GENRES  # noqa: PLC0415

    print("Loading fp32 model…")
    fp32 = _load_fp32()
    print("Loading torchao int8 model…")
    int8 = _load_int8()

    ids, mask = _tokenize(QUERIES)

    print("Exporting ONNX…")
    fp32_onnx = OUT / "facet_model.onnx"
    export_onnx(fp32, fp32_onnx)

    print("Generating torchao-int8 goldens…")
    with torch.no_grad():
        pred = int8(ids, mask)

    golden = {
        "model": "distilbert_facets torchao int8",
        "genres": GENRES,
        "queries": [],
    }
    for i, q in enumerate(QUERIES):
        logits = {name: pred[name][i].tolist() for name in OUTPUT_NAMES}
        golden["queries"].append(
            {
                "text": q,
                "input_ids": ids[i].tolist(),
                "attention_mask": mask[i].tolist(),
                "logits": logits,
                "decode": _decode(logits, GENRES),
            }
        )

    dest = OUT / "golden_outputs.json"
    dest.write_text(json.dumps(golden, indent=1))
    print(f"  golden_outputs.json written ({dest.stat().st_size / 1e6:.2f} MB)")

    print("\nSanity decode (torchao int8):")
    for q in golden["queries"]:
        print(f"  {q['text'][:48]:<50} genre={q['decode']['genre']}")

    # Best-effort: dynamic int8 quantize of the exported ONNX. Kept separate so a
    # quantizer hiccup never blocks the goldens above. Requires the fixed-shape
    # export (dynamic seq_len Range node breaks onnxruntime's symbolic shape
    # inference) + quant_pre_process before quantize_dynamic.
    try:
        print("\nDynamic-quantizing ONNX to int8…")
        from onnxruntime.quantization import QuantType, quantize_dynamic  # noqa: PLC0415
        from onnxruntime.quantization.preprocess import quant_pre_process  # noqa: PLC0415

        fixed_onnx = OUT / "facet_model_fixed.onnx"
        export_onnx(fp32, fixed_onnx, dynamic_seq=False)
        prepped = OUT / "facet_model_prepped.onnx"
        quant_pre_process(fixed_onnx, prepped)
        int8_onnx = OUT / "facet_model_int8.onnx"
        quantize_dynamic(prepped, int8_onnx, weight_type=QuantType.QInt8)
        print(f"  facet_model_int8.onnx: {int8_onnx.stat().st_size / 1e6:.1f} MB")
        fixed_onnx.unlink(missing_ok=True)
        prepped.unlink(missing_ok=True)
    except Exception as e:  # noqa: BLE001
        print(f"  [!] int8 quantize skipped ({type(e).__name__}: {e})")

    # fp16 export: half the size of fp32 with identical parity (weights .half()).
    # NOTE: newer torch writes weights to external .data for large models — must
    # inline them back into a single self-contained file.
    try:
        print("\nExporting fp16 ONNX…")
        fp16 = fp32.half()
        fp16_onnx = OUT / "facet_model_fp16.onnx"
        torch.onnx.export(
            fp16,
            (torch.zeros(1, MAX_SEQ_LEN, dtype=torch.long), torch.ones(1, MAX_SEQ_LEN, dtype=torch.long)),
            str(fp16_onnx),
            input_names=["input_ids", "attention_mask"],
            output_names=OUTPUT_NAMES,
            opset_version=17,
        )
        import onnx
        from onnx.external_data_helper import load_external_data_for_model

        proto = onnx.load(str(fp16_onnx))
        load_external_data_for_model(proto, base_dir=str(fp16_onnx.parent))
        onnx.save(proto, str(fp16_onnx))
        data_file = Path(str(fp16_onnx) + ".data")
        data_file.unlink(missing_ok=True)
        print(f"  facet_model_fp16.onnx: {fp16_onnx.stat().st_size / 1e6:.1f} MB")
    except Exception as e:  # noqa: BLE001
        print(f"  [!] fp16 export skipped ({type(e).__name__}: {e})")


if __name__ == "__main__":
    main()
