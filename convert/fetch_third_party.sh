#!/usr/bin/env bash
# Pinned sources the conversion uses (not committed; Apache-2.0 both):
#   third_party/litert-torch    google-ai-edge/litert-torch @ f61d0b79 (installed editable into .venv-dec)
#   third_party/hf-to-litertlm  john-rocky/hf-to-litertlm @ a395b4967fa9 (the LFM3 decoder exporter and the LFM3-VL recipe)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p third_party
get() {  # get <owner/repo> <dir> <full 40-hex commit>: fetch exactly that commit and check HEAD afterwards
  [[ "$3" =~ ^[0-9a-f]{40}$ ]] || { echo "pin $3 is not a full commit sha"; exit 1; }
  [ -d "third_party/$2/.git" ] || git clone -q "https://github.com/$1.git" "third_party/$2"
  git -C "third_party/$2" fetch -q origin "$3"
  git -C "third_party/$2" checkout -q --detach "$3"
  [ "$(git -C "third_party/$2" rev-parse HEAD)" = "$3" ] || { echo "third_party/$2 is not at $3"; exit 1; }
}
get google-ai-edge/litert-torch litert-torch f61d0b792013300ae7a1ca215d8c2f9de5ef4adf
get john-rocky/hf-to-litertlm hf-to-litertlm a395b4967fa9e879a5de548d83399b4be2487b1e
echo "third_party ready"
