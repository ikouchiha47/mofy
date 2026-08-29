"""Export bert_mini_facets/model_int8.pt to ONNX for Android inference.

Outputs:
  ml/data/facet_model.onnx    - ONNX model (input_ids + attention_mask → logits)
  ml/data/facet_vocab.txt     - WordPiece vocab for BertTokenizer on Android
"""
import json
import sys
from pathlib import Path

import torch

ROOT = Path(__file__).parent.parent
CKPT = ROOT / "checkpoints" / "bert_mini_facets"
OUT_DIR = ROOT / "data"

MAX_SEQ_LEN = 64  # queries are short; smaller = faster inference on-device


def load_model():
    sys.path.insert(0, str(ROOT / "train"))
    from finetune_encoder_facets import MultiHeadFacetModel  # noqa: PLC0415

    # Load the int8 quantized checkpoint
    model = MultiHeadFacetModel(encoder_name="google/bert_uncased_L-4_H-256_A-4")
    state = torch.load(CKPT / "model_int8.pt", map_location="cpu", weights_only=False)
    model.load_state_dict(state, strict=False)
    model.eval()
    return model


def export_onnx(model: torch.nn.Module, dest: Path) -> None:
    dummy_ids = torch.zeros(1, MAX_SEQ_LEN, dtype=torch.long)
    dummy_mask = torch.ones(1, MAX_SEQ_LEN, dtype=torch.long)

    torch.onnx.export(
        model,
        (dummy_ids, dummy_mask),
        str(dest),
        input_names=["input_ids", "attention_mask"],
        output_names=[
            "genre_logits", "has_date", "has_runtime", "has_rating",
            "has_name", "has_mood", "has_other", "popularity",
        ],
        dynamic_axes={
            "input_ids": {1: "seq_len"},
            "attention_mask": {1: "seq_len"},
        },
        opset_version=17,
    )
    # Merge external weights back into a single self-contained .onnx file
    # so Android can load it from assets without needing a sidecar .data file.
    import onnx
    from onnx.external_data_helper import convert_model_to_external_data, load_external_data_for_model
    data_file = Path(str(dest) + ".data")
    if data_file.exists():
        model_proto = onnx.load(str(dest))
        load_external_data_for_model(model_proto, data_dir=str(dest.parent))
        # Re-save as single file (all weights inlined)
        onnx.save(model_proto, str(dest))
        data_file.unlink(missing_ok=True)
    mb = dest.stat().st_size / 1_048_576
    print(f"Exported ONNX model → {dest} ({mb:.1f} MB)")


def export_vocab(dest: Path) -> None:
    tok = json.loads((CKPT / "tokenizer.json").read_text())
    vocab: dict = tok["model"]["vocab"]
    # Sort by token id, write one token per line (standard BERT vocab.txt format)
    tokens = sorted(vocab.items(), key=lambda kv: kv[1])
    dest.write_text("\n".join(t for t, _ in tokens) + "\n")
    print(f"Exported vocab ({len(tokens)} tokens) → {dest}")


if __name__ == "__main__":
    OUT_DIR.mkdir(exist_ok=True)

    print("Loading model…")
    model = load_model()

    print("Exporting ONNX…")
    export_onnx(model, OUT_DIR / "facet_model.onnx")

    print("Exporting vocab…")
    export_vocab(OUT_DIR / "facet_vocab.txt")

    print("Done.")
