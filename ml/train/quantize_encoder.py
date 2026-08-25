"""Quantize trained encoder facet models to int8 using torchao.

Loads model.pt from each checkpoint dir, applies Int8WeightOnlyConfig,
saves model_int8.pt alongside. Tokenizer files are already there.

Usage (from ml/):
  uv run python train/quantize_encoder.py
"""

from pathlib import Path
import torch
from torchao.quantization import quantize_, Int8WeightOnlyConfig

CHECKPOINTS = [
    Path("checkpoints/bert_mini_facets"),
    Path("checkpoints/distilbert_facets"),
]


def quantize_checkpoint(ckpt_dir: Path) -> None:
    pt_path = ckpt_dir / "model.pt"
    if not pt_path.exists():
        print(f"[SKIP] {ckpt_dir} — model.pt not found")
        return

    ckpt = torch.load(pt_path, map_location="cpu", weights_only=False)
    import sys; sys.path.insert(0, str(Path(__file__).parent))
    from finetune_encoder_facets import MultiHeadFacetModel
    model = MultiHeadFacetModel(ckpt["hf"])
    model.load_state_dict(ckpt["state_dict"])
    model.eval()

    fp32_size = pt_path.stat().st_size / 1e6

    quantize_(model, Int8WeightOnlyConfig())

    out_path = ckpt_dir / "model_int8.pt"
    torch.save({**ckpt, "state_dict": model.state_dict()}, out_path)

    int8_size = out_path.stat().st_size / 1e6
    print(f"{ckpt_dir.name}: {fp32_size:.1f}MB → {int8_size:.1f}MB ({int8_size/fp32_size*100:.0f}%)")


def main():
    for ckpt_dir in CHECKPOINTS:
        quantize_checkpoint(ckpt_dir)


if __name__ == "__main__":
    main()
