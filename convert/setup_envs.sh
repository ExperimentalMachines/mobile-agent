#!/usr/bin/env bash
# The three Python 3.12 environments, installed from the exact versions they had when the published file was built
# (convert/requirements-*.lock, frozen 2026-11-23 on an Apple M5 Mac; macOS arm64 wheels):
#   .venv-dec : text decoder export + quantization; litert-torch installed editable from third_party at f61d0b79
#   .venv-exp : vision graphs, .litertlm packing
#   .venv-run : the parity runs (LiteRT-LM 0.19.1 Python API, requests for llama-server)
# Beyond hf-to-litertlm's own pins: jax 0.12.0.1 / jaxlib 0.12.0 (jax 0.12.1 fails the vision export: "Failed to
# serialize StableHLO to plugin version 1.19.0"), torchvision 0.29.0 (the model's remote code imports it), and the
# litert-torch dependencies that the editable --no-deps install leaves out.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d third_party/litert-torch ] || bash convert/fetch_third_party.sh
for e in dec exp run; do
  uv venv -q -p 3.12 ".venv-$e"
  uv pip install -q -p ".venv-$e/bin/python" -r "convert/requirements-$e.lock"
done
uv pip install -q -p .venv-dec/bin/python --no-deps -e third_party/litert-torch
echo envs ready
