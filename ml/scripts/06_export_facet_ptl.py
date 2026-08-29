"""Export bert_mini_facets/model_int8.pt to TorchScript mobile (.ptl) for Android.

Outputs:
  ml/data/facet_model.ptl     - TorchScript mobile model (input_ids + attention_mask → logits)
  ml/data/facet_vocab.txt     - WordPiece vocab for on-device tokenization
"""
import json
import sys
from pathlib import Path

import torch

ROOT = Path(__file__).parent.parent
CKPT = ROOT / "checkpoints" / "distilbert_facets"
OUT_DIR = ROOT / "data"

MAX_SEQ_LEN = 64  # queries are short; smaller = faster inference on-device


def load_model():
    sys.path.insert(0, str(ROOT / "train"))
    from finetune_encoder_facets import MultiHeadFacetModel  # noqa: PLC0415

    # model_int8.pt is {"hf": encoder_name, "state_dict": torchao-quantized, "genres": [...], ...}
    # Requires torchao==0.7.0 (the version used on Modal to quantize).
    ckpt = torch.load(CKPT / "model_int8.pt", map_location="cpu", weights_only=False)
    model = MultiHeadFacetModel(encoder_name=ckpt["hf"])
    # model_int8.pt state_dict contains AffineQuantizedTensor (torchao 0.7.0 from Modal).
    # Torchao version mismatch prevents direct load; dequantize to fp32 first.
    sd = {k: v.dequantize() if hasattr(v, "dequantize") else v
          for k, v in ckpt["state_dict"].items()}
    model.load_state_dict(sd)
    model.eval()
    return model


def export_ptl(model: torch.nn.Module, dest: Path) -> None:
    dummy_ids = torch.zeros(1, MAX_SEQ_LEN, dtype=torch.long)
    dummy_mask = torch.ones(1, MAX_SEQ_LEN, dtype=torch.long)

    traced = torch.jit.trace(model, (dummy_ids, dummy_mask), strict=False)
    traced._save_for_lite_interpreter(str(dest))
    print(f"Exported PTL model → {dest} ({dest.stat().st_size / 1_048_576:.1f} MB)")


def export_vocab(dest: Path) -> None:
    tok = json.loads((CKPT / "tokenizer.json").read_text())
    vocab: dict = tok["model"]["vocab"]
    tokens = sorted(vocab.items(), key=lambda kv: kv[1])
    dest.write_text("\n".join(t for t, _ in tokens) + "\n")
    print(f"Exported vocab ({len(tokens)} tokens) → {dest}")


if __name__ == "__main__":
    OUT_DIR.mkdir(exist_ok=True)

    print("Loading model…")
    model = load_model()

    print("Exporting TorchScript mobile (.ptl)…")
    export_ptl(model, OUT_DIR / "facet_model.ptl")

    print("Exporting vocab…")
    export_vocab(OUT_DIR / "facet_vocab.txt")

    print("Done.")
