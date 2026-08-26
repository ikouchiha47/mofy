"""Train MultiHeadFacetModel on Modal (L4 GPU), quantize to int8, download.

Usage (from ml/):
  uv run modal run scripts/modal_train_facets.py
  uv run modal run scripts/modal_train_facets.py --model distilbert --epochs 8
"""

from __future__ import annotations

import re as _re
import tarfile
import tempfile
from pathlib import Path

import modal

# ── image ─────────────────────────────────────────────────────────────────────

# transformers 5.x requires torch>=2.5 (2.4.1 → "PyTorch was not found" and AutoModel dies).
# Pin a CUDA-friendly torch that Modal's mirror serves; leave upper bound loose on torchao.
image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install(
        # transformers 5.x refuses to enable torch if version < 2.5
        "torch==2.5.1",
        "transformers==5.14.1",
        "tokenizers",
        "sentencepiece",
        # 0.7 still has Int8WeightOnlyConfig; 0.18 wants much newer torch
        "torchao==0.7.0",
        "huggingface_hub",
        "safetensors",
        "accelerate",
    )
    .run_commands(
        # pre-bake model weights so training doesn't hit HF at runtime
        "python -c \""
        "import torch; "
        "assert torch.__version__.startswith('2.5'), torch.__version__; "
        "from transformers import AutoTokenizer, AutoModel; "
        "AutoTokenizer.from_pretrained('distilbert-base-uncased'); "
        "AutoModel.from_pretrained('distilbert-base-uncased'); "
        "print('prebake ok', torch.__version__, torch.cuda.is_available())"
        "\""
    )
)

app = modal.App("mofy-train-facets", image=image)

# ── data files to upload ──────────────────────────────────────────────────────

LOCAL_DATA = [
    "data/scaled_examples_batch1.jsonl",
    "data/scaled_examples_batch_1.jsonl",
    "data/scaled_examples_batch_2.jsonl",
    "data/scaled_examples_batch_3.jsonl",
    "data/scaled_examples_batch_4.jsonl",
    "data/grounded_queries.jsonl",
    "data/negation_genre_examples.jsonl",
]

# ── remote function ───────────────────────────────────────────────────────────


@app.function(gpu="L4", timeout=3600, cpu=4, memory=16384)
def train_and_quantize(
    data_tar: bytes,
    train_src: bytes,
    model: str,
    epochs: int,
) -> bytes:
    import io
    import os
    import sys
    import tarfile
    import tempfile

    root = Path(tempfile.mkdtemp())
    (root / "data").mkdir()
    (root / "train").mkdir()
    (root / "checkpoints").mkdir()

    # extract data
    with tarfile.open(fileobj=io.BytesIO(data_tar)) as tar:
        tar.extractall(root)

    # write train script
    (root / "train" / "finetune_encoder_facets.py").write_bytes(train_src)

    os.chdir(root)
    sys.path.insert(0, str(root / "train"))

    from finetune_encoder_facets import DATA_FILES, MODEL_PRESETS, load_labels, train_one
    import argparse

    print(f"Loading labels from {DATA_FILES}...")
    labels = load_labels(DATA_FILES)
    print(f"Loaded {len(labels)} labels")

    args = argparse.Namespace(smoke=False, epochs=epochs, demo=False)
    keys = ["distilbert", "bert-tiny"] if model == "both" else [model]
    for k in keys:
        train_one(k, list(labels), args)

    # quantize (API renamed across torchao versions)
    import torch
    from torchao.quantization import quantize_

    try:
        from torchao.quantization import Int8WeightOnlyConfig

        def _int8_cfg():
            return Int8WeightOnlyConfig()
    except ImportError:
        from torchao.quantization import int8_weight_only  # torchao <=0.7

        def _int8_cfg():
            return int8_weight_only()

    for k in keys:
        ckpt_dir = root / MODEL_PRESETS[k]["out_dir"]
        pt = ckpt_dir / "model.pt"
        if not pt.exists():
            continue
        ckpt = torch.load(pt, map_location="cpu", weights_only=False)
        from finetune_encoder_facets import MultiHeadFacetModel
        m = MultiHeadFacetModel(ckpt["hf"])
        m.load_state_dict(ckpt["state_dict"])
        m.eval()
        quantize_(m, _int8_cfg())
        out = ckpt_dir / "model_int8.pt"
        torch.save({**ckpt, "state_dict": m.state_dict()}, out)
        fp = pt.stat().st_size / 1e6
        i8 = out.stat().st_size / 1e6
        print(f"{k}: {fp:.1f}MB → {i8:.1f}MB int8")

    # pack checkpoints into tar for download
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        tar.add(root / "checkpoints", arcname="checkpoints")
    return buf.getvalue()


# ── local entrypoint ──────────────────────────────────────────────────────────


@app.local_entrypoint()
def main(model: str = "distilbert", epochs: int = 6):
    import io
    import tarfile

    root = Path(__file__).parent.parent

    # pack data files
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for rel in LOCAL_DATA:
            p = root / rel
            if p.exists():
                tar.add(p, arcname=rel)
            else:
                print(f"[WARN] missing: {rel}")
    data_tar = buf.getvalue()
    print(f"Uploading data: {len(data_tar)/1e6:.1f}MB")

    train_src = (root / "train" / "finetune_encoder_facets.py").read_bytes()

    print(f"Launching Modal L4 — model={model} epochs={epochs}")
    result_tar = train_and_quantize.remote(data_tar, train_src, model, epochs)

    # unpack into local checkpoints/
    with tarfile.open(fileobj=io.BytesIO(result_tar)) as tar:
        tar.extractall(root)
    print(f"Downloaded checkpoints to {root / 'checkpoints'}/")

    # show metrics
    import json
    for name in (["distilbert", "bert-tiny"] if model == "both" else [model]):
        mp = root / "checkpoints" / f"{name}_facets" / "metrics.json"
        if mp.exists():
            m = json.loads(mp.read_text())
            print(f"\n{name} metrics:")
            for k, v in m.items():
                if v is not None:
                    print(f"  {k}: {v:.4f}" if isinstance(v, float) else f"  {k}: {v}")
