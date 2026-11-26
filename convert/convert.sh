#!/usr/bin/env bash
# convert.sh [STEP...] - experimentalmachines/LFM3-VL-2B-MobileAgent (merged weights) -> one .litertlm for
# LiteRT-LM (model type fast_vlm), with the screenshot grid the model was trained on: 1280 x 576 px (720 image tokens;
# what train/agent_format.smart_resize gives every 20:9 phone screen) instead of the 512 x 512 of litert-community's
# base-model file. Recipe = hf-to-litertlm lfm3vl_work/convert_lfm3_vl.sh (Apache-2.0, third_party/hf-to-litertlm),
# with the vision re-write and the packer patched for an H x W grid (convert/lfm3vl/).
# Steps: src template vision float quant pack. A finished step leaves $OUT/.done/<step>.
# Env: WEIGHTS (model.safetensors of the merged v4), CTX (default 4096), FORMS (int4 | int8 | f16; default int4),
#      HW (default 1280x576), KEEP_FLOAT=1 keeps the 16 GB float export after packing (to pack more forms later)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
PY=$ROOT/.venv-dec/bin/python; PY_EXPORT=$ROOT/.venv-exp/bin/python
H2L=$ROOT/third_party/hf-to-litertlm; T=$ROOT/convert/lfm3vl
LFM3_REPO=${LFM3_REPO:-$HOME/lfm3-android}   # the research repo (experimentalmachines/lfm3-mobile-agent)
WEIGHTS=${WEIGHTS:-$LFM3_REPO/models/hf_local/LFM3-VL-2B-AndroidAgent-v4/model.safetensors}
CTX=${CTX:-4096}; FORMS=(${FORMS:-int4}); HW=${HW:-1280x576}
NAME=LFM3-VL-2B-MobileAgent
OUT=${OUT:-$ROOT/build/v4_g${HW}_ctx${CTX}}
mkdir -p "$OUT/.done"; LOG=$OUT/recipe.log
export PYTHONHASHSEED=0; unset PYTHONPATH
run() { echo "### $(date +%T) CMD: $*" | tee -a "$LOG"; /usr/bin/time -l "$@" 2>&1 | tee -a "$LOG"; return "${PIPESTATUS[0]}"; }
STEPS=("$@"); [ ${#STEPS[@]} -gt 0 ] || STEPS=(src template vision float quant pack)
for step in "${STEPS[@]}"; do
  [ -f "$OUT/.done/$step" ] && { echo "skip $step"; continue; }
  case $step in
    src)  # the base revision's code and text files (f83d2b0a, the converter's pin; same code as v4's base 23387be)
          # + the merged v4 weights. The folder name "src" is part of the decoder graph's tensor names, as upstream.
      "$PY_EXPORT" convert/fetch_base.py   # build/base_f83, each file checked against convert/base_f83.sha256
      rm -rf "$OUT/src"; mkdir -p "$OUT/src"
      cp build/base_f83/* "$OUT/src/"
      ln -s "$WEIGHTS" "$OUT/src/model.safetensors"
      shasum -a 256 "$OUT/src/tokenizer.json" | grep -q a39eed58008cbcbc09e5b629b81338a654269c64cd13348aaa6090f4db75c7e7 ;;
    template)
      run "$PY" "$H2L/lfm3vl_work/tools/vl_template.py" "$OUT/src/chat_template.jinja" "$OUT/vl_template.jinja"
      shasum -a 256 "$OUT/vl_template.jinja" | grep -q 5011892a164b4d4bc426d50267f064bfe471664b6c8159ce1a5f6baf2ea28db7 ;;
    vision)
      rm -rf "$OUT/tower"
      run "$PY_EXPORT" -B "$T/export_vision.py" --src "$OUT/src" --out "$OUT/tower" --g "$HW" ;;
    float)
      rm -rf "$OUT/dec/float"
      run "$PY" -B "$H2L/lfm3vl_work/tools/vl_decoder.py" float --src "$OUT/src" --template "$OUT/vl_template.jinja" --out "$OUT/dec" --ctx "$CTX" ;;
    quant)
      for form in emb_wi8 "${FORMS[@]}"; do
        rm -f "$OUT/dec/$form.tflite"
        run "$PY" -B "$T/vl_decoder_forms.py" quant --form "$form" --out "$OUT/dec" --ctx "$CTX"
      done ;;
    pack)
      for form in "${FORMS[@]}"; do
        f=${NAME}_${form}_g${HW}_ctx${CTX}; rm -f "$OUT/$f.litertlm"
        run "$PY_EXPORT" -B "$T/build_vl_bundle.py" --name "$f" --out-dir "$OUT" --src "$OUT/src" --template "$OUT/vl_template.jinja" \
          --g "$HW" --model-type fast_vlm --decoder-bundle "$OUT/dec/float/model.litertlm" \
          --embedder-tflite "$OUT/dec/emb_wi8.tflite" --decoder-tflite "$OUT/dec/$form.tflite" \
          --encoder "$OUT/tower/vision_encoder_g${HW}_v2.tflite" --adapter "$OUT/tower/vision_adapter_g${HW}_v2_fp16.tflite" \
          --vision-act fp32
      done
      [ -n "${KEEP_FLOAT:-}" ] || rm -rf "$OUT/dec/float" ;;
    *) echo "unknown step $step"; exit 2 ;;
  esac
  touch "$OUT/.done/$step"
done
echo "CONVERT_DONE ${STEPS[*]}"
