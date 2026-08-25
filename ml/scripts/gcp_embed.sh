#!/usr/bin/env bash
# Spin up a GCP T4 VM, run embedding, rsync catalog_vec.db back, shut down.
# Usage (from ml/):
#   bash scripts/gcp_embed.sh
# Prerequisites: gcloud authed, Compute API enabled, HF token in ~/.credentials

set -euo pipefail

PROJECT="gen-lang-client-0927307851"
ZONE="asia-south1-b"
VM="mofy-embed"
MACHINE="g2-standard-4"
GPU="nvidia-l4"
GPU_COUNT=1
DISK_GB=100
IMAGE_FAMILY="pytorch-2-9-cu129-ubuntu-2204-nvidia-580"  # Deep Learning VM with CUDA + PyTorch pre-installed
IMAGE_PROJECT="deeplearning-platform-release"

HF_TOKEN=$(grep -o 'hf_[A-Za-z0-9]*' ~/.credentials 2>/dev/null | head -1)
if [[ -z "$HF_TOKEN" ]]; then
  echo "[ERROR] HF token not found in ~/.credentials"
  exit 1
fi

echo "=== Creating VM $VM in $ZONE ==="
gcloud compute instances create "$VM" \
  --project="$PROJECT" \
  --zone="$ZONE" \
  --machine-type="$MACHINE" \
  --maintenance-policy=TERMINATE \
  --restart-on-failure \
  --image-family="$IMAGE_FAMILY" \
  --image-project="$IMAGE_PROJECT" \
  --boot-disk-size="${DISK_GB}GB" \
  --boot-disk-type=pd-ssd \
  --metadata="install-nvidia-driver=True" \
  --scopes=default

echo "=== Waiting for SSH ==="
sleep 30
for i in {1..10}; do
  gcloud compute ssh "$VM" --zone="$ZONE" --project="$PROJECT" \
    --command="echo ready" 2>/dev/null && break
  echo "  attempt $i/10, retrying in 10s..."
  sleep 10
done

echo "=== Rsyncing ml/ and data/catalog.db ==="
# Run from repo root
cd "$(dirname "$0")/../.."

gcloud compute scp --zone="$ZONE" --project="$PROJECT" --recurse \
  ml/ "$VM":~/mofy-ml/

gcloud compute scp --zone="$ZONE" --project="$PROJECT" \
  ml/data/catalog.db "$VM":~/mofy-ml/data/catalog.db

echo "=== Installing deps and running embedding ==="
gcloud compute ssh "$VM" --zone="$ZONE" --project="$PROJECT" -- bash -s <<EOF
set -euo pipefail
cd ~/mofy-ml

# Install uv
curl -LsSf https://astral.sh/uv/install.sh | sh
export PATH="\$HOME/.local/bin:\$PATH"

# Install Python deps
uv sync --frozen

# Run embedding
HF_TOKEN="$HF_TOKEN" uv run python scripts/phase09_embed_enriched.py
EOF

echo "=== Pulling catalog_vec.db ==="
gcloud compute scp --zone="$ZONE" --project="$PROJECT" \
  "$VM":~/mofy-ml/data/catalog_vec.db ml/data/catalog_vec.db

echo "=== Deleting VM ==="
gcloud compute instances delete "$VM" --zone="$ZONE" --project="$PROJECT" --quiet

echo "=== Done. catalog_vec.db written to ml/data/catalog_vec.db ==="
