# Converting MobileAgent v4 to LiteRT-LM

The model is [LFM3-VL-2B-MobileAgent-v4](https://huggingface.co/experimentalmachines/LFM3-VL-2B-MobileAgent-v4) (merged
weights). The output is one `.litertlm` file that runs on the CPU and GPU of any phone with LiteRT-LM. No per-chip
builds are needed. Only an NPU build would need one file per chip, and none is made here.

## The file

| | |
|---|---|
| Name | `LFM3-VL-2B-MobileAgent-v4_int4_g1280x576_ctx4096.litertlm` |
| Size | 2,072,561,915 bytes |
| sha256 | `6008eef76adab667bab7b5322e76d077a3ca8c430d7777427278bf33fc97a816` |
| Model type | `fast_vlm`: one image of fixed size per request |
| Image | **1280 high × 576 wide → 720 image tokens**. This is the size training gave every 20:9 screen (`smart_resize`, at most 880 × 880 px of area). The published base-model file uses 512 × 512 |
| Text decoder | int4 weights in blocks of 32 (symmetric min-max). Output head int8 per channel, token embeddings int8 |
| Vision encoder / adapter | fp32 weights / fp16 weights (both declared fp32 activations) |
| Context | 4,096 tokens. A step uses about 1,300 |
| Thinking | On by default. The intent arrives in the `thought` channel, the action JSON in the text |
| Runtime | LiteRT-LM 0.18.1 or newer. Tested with 0.19.1 (Python on the Mac, Kotlin on Android) |

## How it was made

`convert/convert_v4.sh` runs the steps of hf-to-litertlm's LFM3-VL-2B recipe on our weights:

| Step | What | Time on an M5 Mac (24 GB) | Peak memory |
|---|---|---|---|
| `src` | Base revision `f83d2b0a` code and text files + the merged v4 `model.safetensors` | seconds | — |
| `template` | The bundle's chat template (sha256 checked against the recipe) | seconds | — |
| `vision` | Vision tower + projector re-written for the fixed 1280 × 576 grid, exported | 1 min | 6.0 GB |
| `float` | fp32 text decoder export (litert-torch @ `f61d0b79`) | 5.6 min | 23.6 GB |
| `quant` | Token embedder int8, decoder int4 | 1.8 min | 5.0 GB |
| `pack` | `.litertlm` file (litert-lm-builder 0.18.1) | 1 min | 7.0 GB |

- **Code and model:** v4's base revision is `23387be`; the converter pins `f83d2b0a`. The model code, tokenizer and
  chat template are byte-identical between the two (checked). Only the saved config defaults differ.
- **Grid change:** three files are patched for an H × W grid (`convert/lfm3vl/NOTICE.md`). LiteRT-LM's `fast_vlm`
  path already takes height and width separately (`image_tensor_height` / `image_tensor_width`).

### Setup

```bash
bash convert/fetch_third_party.sh   # litert-torch @ f61d0b79, hf-to-litertlm @ a395b49
bash convert/setup_envs.sh          # .venv-dec (decoder) and .venv-exp (vision + packing), pinned
bash convert/convert_v4.sh          # src template vision float quant pack
```

- **Pins:** the two environments follow the recipe's pins.
- **Additional pins:** `jax 0.12.0.1` / `jaxlib 0.12.0` and `torchvision 0.29.0`. The recipe does not pin them, and
  jax 0.12.1 fails the vision export ("Failed to serialize StableHLO to plugin version 1.19.0").

## Checks

### Vision (`convert/parity/check_vision.py`)

The exported encoder + adapter run on the CPU with the runtime's input (PNG as float(u8)/255). They are compared with
the merged v4 model's own image features (Transformers, fp32) on 6 test screenshots.

| Check | Result |
|---|---|
| Patch grid | 80 × 36 on both sides (as in training) |
| Image tokens | 720 on both sides |
| Worst per-token cosine | **0.9999999** (pass at 0.999) |

### Actions against the GGUF (`convert/parity/`)

- **Test set:** 180 AndroidControl test steps (official test episodes: 120 high-level, 60 low-level), chosen by a
  fixed hash.
- **Prompt and screenshot:** v4's prompt exactly as the offline benchmark built it, and the screenshot resized as in
  training (576 × 1280).
- **Reference:** the GGUF every published v4 number came from (llama.cpp `cd959bae9`, Q4_K_M + mmproj Q8_0, evaluation
  flags).
- **Score:** the offline benchmark's rule (click inside the target box or within 0.14 of the screen).

| | GGUF (llama.cpp) | LiteRT-LM file |
|---|---|---|
| Step accuracy, all 180 | **0.800** | **0.800** |
| High-level (120) | 0.758 | 0.750 |
| Low-level (60) | 0.883 | 0.900 |
| Action type right | 0.856 | 0.850 |
| Replies that parse | 1.000 | 1.000 |

- **Paired:** 8 steps are right only in the GGUF and 8 only in the LiteRT file. No difference.
- **Same action:** 82% of steps (same type; clicks within 20 units on the 0–1000 grid). Click distance median 0,
  90th percentile 75 units. The rest are where two different 4-bit quantizations pick differently.
- **Reference point:** v4's published offline numbers on the full test split are 0.728 high-level and 0.908 low-level.

### One engine for a whole run

LiteRT-LM issue #3165 (not fixed in 0.19.1): a new conversation on an engine that already ran one can start from the
earlier conversation's short-convolution state. Checked on the first 30 rows:

- **Agreement:** a fresh engine per step and one shared engine gave identical output on **29 of 30**.
- **The one difference:** one unit on a click (y 762 vs 763).

So the app keeps one engine. Engine creation took about 2 s on the Mac with a warm cache.
