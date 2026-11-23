#!/usr/bin/env bash
# Pinned sources the conversion uses (not committed; Apache-2.0 both):
#   third_party/litert-torch    google-ai-edge/litert-torch @ f61d0b79 (installed editable into .venv-dec)
#   third_party/hf-to-litertlm  john-rocky/hf-to-litertlm @ a395b49 (the LFM3 decoder exporter and the LFM3-VL recipe)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party
get() { [ -d "third_party/$2" ] || git clone -q "https://github.com/$1.git" "third_party/$2"; git -C "third_party/$2" checkout -q "$3"; }
get google-ai-edge/litert-torch litert-torch f61d0b792013300ae7a1ca215d8c2f9de5ef4adf
get john-rocky/hf-to-litertlm hf-to-litertlm a395b49
echo "third_party ready"
