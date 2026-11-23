#!/usr/bin/env bash
# Two Python 3.12 environments for the LiteRT-LM conversion, pinned as in hf-to-litertlm's LFM3-VL-2B entry.
#   .venv-dec : text decoder export + quantization (litert-torch editable at f61d0b79)
#   .venv-exp : vision graphs, .litertlm packing, section check
set -euo pipefail
cd "$(dirname "$0")/.."
uv venv -q -p 3.12 .venv-dec
uv pip install -q -p .venv-dec/bin/python "torch==2.14.0" "transformers==5.20.2" "ai-edge-quantizer-nightly==0.10.0.dev20261023" \
  "ai-edge-litert-nightly==2.4.0.dev20261022" "litert-converter==0.6.0.dev20261022" "litert-lm-builder==0.18.1" \
  "numpy==2.5.4" "safetensors==0.8.0" "tokenizers==0.23.3" pillow
uv pip install -q -p .venv-dec/bin/python --no-deps -e third_party/litert-torch
uv venv -q -p 3.12 .venv-exp
uv pip install -q -p .venv-exp/bin/python "litert-torch==0.10.0" "litert-converter==0.5.0" "ai-edge-litert==2.3.0" \
  "ai-edge-quantizer==0.10.0" "torch==2.14.1" "transformers==5.14.1" "litert-lm-builder==0.18.1" "numpy==2.5.4" \
  "safetensors==0.8.0" "tokenizers==0.22.2" pillow
echo envs ready
