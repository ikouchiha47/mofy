#!/usr/bin/env bash
# Run this on a fresh g4dn.xlarge (Ubuntu 22.04 Deep Learning AMI) after ssh-ing in.
# The Deep Learning AMI already has CUDA, PyTorch, and conda - skip driver install.
set -euo pipefail

# 1. install uv
curl -LsSf https://astral.sh/uv/install.sh | sh
source "$HOME/.local/bin/env"

# 2. sync project (run from ml/ on local machine first):
#    rsync -avz --exclude='.venv' --exclude='data/catalog.db' \
#      ml/ ubuntu@<ip>:~/mofy-ml/
# Then on instance:
cd ~/mofy-ml

# 3. install deps (uv picks up pyproject.toml)
uv sync

# 4. verify torch
uv run python -c "import torch; print('CUDA:', torch.cuda.is_available())"

# 5. hard kill after 2h regardless - prevents runaway billing if training hangs
(sleep 7200 && sudo shutdown -h now) &
WATCHDOG_PID=$!

# run training
uv run python train/finetune_t5.py --demo 2>&1 | tee /tmp/t5_train.log

# training done - cancel watchdog
kill $WATCHDOG_PID 2>/dev/null
echo "Training done."

# push checkpoints back to the machine that launched this instance
# LOCAL_HOST must be set before running: export LOCAL_HOST=user@your-mac-ip
if [[ -n "${LOCAL_HOST:-}" ]]; then
    echo "Syncing checkpoints back to $LOCAL_HOST..."
    rsync -avz --timeout=120 checkpoints/ "${LOCAL_HOST}:~/mofy-ml-checkpoints/"
    echo "Sync done."
else
    echo "LOCAL_HOST not set - skipping rsync. Fetch manually before instance terminates:"
    echo "  rsync -avz -e 'ssh -i ~/.ssh/gac_id_ed25519' ubuntu@<ip>:~/mofy-ml/checkpoints/ ml/checkpoints/"
fi

echo "Shutting down in 60s."
sleep 60
sudo shutdown -h now
