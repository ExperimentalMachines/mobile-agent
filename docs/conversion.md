# Converting MobileAgent v4 to LiteRT-LM

The model is [LFM3-VL-2B-MobileAgent-v4](https://huggingface.co/experimentalmachines/LFM3-VL-2B-MobileAgent-v4) (merged
weights). The output is one `.litertlm` file that runs on the CPU and GPU of any phone with LiteRT-LM. No per-chip
builds are needed. Only an NPU build would need one file per chip, and none is made here.

## The files

All three share the image grid, the vision graphs, the tokenizer, the template and the context. They differ only in the
text decoder's weights.

| File | Decoder weights | Size | sha256 |
|---|---|---|---|
| `LFM3-VL-2B-MobileAgent-v4_int4_g1280x576_ctx4096.litertlm` | int4, blocks of 32 (output head int8) | 2,072,561,915 B | `6008eef76adab667bab7b5322e76d077a3ca8c430d7777427278bf33fc97a816` |
| `LFM3-VL-2B-MobileAgent-v4_int8_g1280x576_ctx4096.litertlm` | int8 per channel | 2,760,755,451 B | `8eba580944ec4627986698392fa235acca02dfc469bf1e00dc6d22288156870d` |
| `LFM3-VL-2B-MobileAgent-v4_f16_g1280x576_ctx4096.litertlm` | fp16 | 4,440,934,651 B | `f7583612e45107c5b2da3793fa954687089e73bfc38f10a92ea63007499698c4` |

| Shared part | Value |
|---|---|
| Model type | `fast_vlm`: one image of fixed size per request |
| Image | **1280 high × 576 wide → 720 image tokens**. This is what training's `smart_resize` (at most 880 × 880 px of area) gives screens of about 20:9 portrait, e.g. 1080 × 2400 and 1440 × 3200. Other shapes are stretched to it, which training never did: Pixel 9 → 1312 × 576, 16:9 → 1152 × 640, landscape → 576 × 1280. The published base-model file uses 512 × 512 |
| Vision encoder / adapter | fp32 weights / fp16 weights (both declared fp32 activations) |
| Token embeddings | int8 |
| Context | 4,096 tokens. A step uses about 1,300 |
| Thinking | On by default. The intent arrives in the `thought` channel, the action JSON in the text |
| Runtime | Tested with LiteRT-LM 0.19.1 only (Python on the Mac, Kotlin on Android). Built with litert-lm-builder 0.18.1 |
| Licence | The weights inherit the LFM Open License v1.0 from LiquidAI/LFM3-VL-2B. None of the files is published yet |

## How it was made

`convert/convert_v4.sh` runs the steps of hf-to-litertlm's LFM3-VL-2B recipe on our weights. Times and memory are
from the int4 build on an Apple M5 Mac (24 GB), one run each:

| Step | What | Time | Peak memory |
|---|---|---|---|
| `src` | Base revision `f83d2b0a` code and text files (`convert/fetch_base.py`, sha256 checked), plus the merged v4 `model.safetensors` | seconds | — |
| `template` | The bundle's chat template (sha256 checked against the recipe) | < 1 s | — |
| `vision` | Vision tower + projector re-written for the fixed 1280 × 576 grid, exported | 15 s | 6.0 GB |
| `float` | fp32 text decoder export (litert-torch @ `f61d0b79`) | 5.6 min | 23.6 GB |
| `quant` | Token embedder int8, then the decoder form: int4 1.4 min, int8 0.4 min, fp16 5.0 min | — | int4 5.0 GB, int8 5.6 GB, fp16 26.5 GB |
| `pack` | `.litertlm` file (litert-lm-builder 0.18.1) | 26–51 s | 7.0 GB |

- **Base revision:** v4's base revision is `23387be`; the converter pins `f83d2b0a`. The model code, tokenizer and chat
  template are byte-identical between the two (checked). Only saved config defaults differ.
- **Grid patch:** three files are patched for an H × W grid (`convert/lfm3vl/NOTICE.md`). LiteRT-LM's `fast_vlm` path
  already takes height and width separately (`image_tensor_height` / `image_tensor_width`).
- **int8 form:** `convert/lfm3vl/vl_decoder_forms.py` adds it. It is the recipe of litert-community's LFM3-1.7B int8
  files.

### Setup

```bash
bash convert/fetch_third_party.sh   # litert-torch @ f61d0b79, hf-to-litertlm @ a395b496 (full SHAs, checked)
bash convert/setup_envs.sh          # .venv-dec / .venv-exp / .venv-run from convert/requirements-*.lock
bash convert/convert_v4.sh          # src template vision float quant pack; FORMS="int4 int8 f16" for all three
```

- **Lock files:** frozen from the environments that built the files. They install and import cleanly in fresh
  environments (checked).
- **Pins beyond the recipe's:** `jax 0.12.0.1` / `jaxlib 0.12.0`, because jax 0.12.1 fails the vision export ("Failed
  to serialize StableHLO to plugin version 1.19.0"). Also `torchvision 0.29.0`, because the model's code imports it.

## Checks

### Vision (`convert/parity/check_vision.py`)

- **What runs:** the exported encoder + adapter, on the Mac **CPU in fp32** (XNNPACK), with the runtime's input (the
  PNG as float(u8)/255).
- **Reference:** the merged v4 model's own image features (Transformers, fp32), on 6 test screenshots.

| Check | Result |
|---|---|
| Patch grid | 80 × 36 on both sides (as in training) |
| Image tokens | 720 on both sides |
| Worst per-token cosine | 0.99999985 |

- **Not checked:** the phone GPU's vision output. Upstream measured this encoder on a GPU at fp16 precision at a
  cosine of 0.244. The files declare fp32 activations for vision, and LiteRT-LM 0.19.1 runs vision in fp32 by
  default. A GPU that ignores that would break the vision silently, so the on-phone parity run below is the check
  that matters.

### Actions: 180 AndroidControl test steps (`convert/parity/`)

**Setup**

| | |
|---|---|
| Set | 120 high-level and 60 low-level steps from 165 official test episodes, chosen by a fixed hash from `experimentalmachines/MobileAgent-SFT-v1` @ `f88a8432`. Ground truth checked against the frozen benchmark file |
| Prompt and image | v4's prompt exactly as the offline benchmark built it, and the screenshot resized as in training (576 × 1280) |
| Score | The offline benchmark's rule: click inside the target box or within 0.14 of the screen, text F1 ≥ 0.5 |
| Runs | All on an Apple M5 Mac. **bf16** = the merged v4 model in Transformers (MPS), how the published AndroidControl numbers were produced. **GGUF** = Q4_K_M + mmproj Q8_0 in llama.cpp `cd959bae9`, the file our AndroidWorld / MobileWorld runs and the earlier phone benchmark used. **LiteRT** = the files above, LiteRT-LM 0.19.1 Python, Mac GPU (WebGPU) |

**Accuracy against ground truth**

| | bf16 | GGUF | LiteRT int4 | LiteRT int8 | LiteRT fp16 |
|---|---|---|---|---|---|
| Step accuracy, all 180 | 0.772 | 0.800 | 0.800 | 0.783 | 0.783 |
| High-level (120) | 0.725 | 0.758 | 0.750 | 0.733 | 0.733 |
| Low-level (60) | 0.867 | 0.883 | 0.900 | 0.883 | 0.883 |

**Agreement with the bf16 model** (same action: same type; clicks within 20 units on the 0–1000 grid)

| | GGUF | LiteRT int4 | LiteRT int8 | LiteRT fp16 |
|---|---|---|---|---|
| Same action | 0.894 | 0.833 | 0.900 | 0.906 |
| Click distance, 90th percentile | 5 | 118 | 3 | 3 |
| Steps right only in bf16 / only in this file | 1 / 6 | 4 / 9 | 3 / 5 | 4 / 6 |

- **What this rules out:** at n = 180 the 95% interval of a paired difference is about ±4.4 points. "No difference
  detected" is the claim, not "the same accuracy".
- **Closeness to the original model:** the int4 file strays further from it than the GGUF does. Its actions match
  bf16 less often, and its clicks land further away. That none of this shows in accuracy on 180 steps doesn't mean it
  never matters.
- **Published numbers for reference:** v4 on the full test split, bf16, was 0.728 high-level and 0.908 low-level.

### The same 180 steps on the phone (app `PARITY` hook)

SM8975 reference phone (QDC session 2, device with 11.5 GB RAM), the app's exact model call: GPU for text and vision,
thinking on, greedy, one engine for all rows, PNGs as on the Mac.

| | LiteRT int4, phone | LiteRT int8, phone |
|---|---|---|
| Step accuracy, all / high / low | 0.794 / 0.742 / 0.900 | 0.783 / 0.733 / 0.883 |
| Same action as bf16 | 0.844 | 0.906 |
| Click distance from bf16, 90th percentile | 103 | 2 |
| Model call per row, median | 4.07 s | 3.63 s |
| Thermal status, start → end (0 none … 3 severe) | 0 → 2 | 1 → 3 |

- **The phone GPU computes what the Mac computes:** the phone's int4 run gives the same action as the Mac's int4 run on
  98.3% of rows (intent text 96.7%). So the vision encoder runs correctly on this Adreno GPU, which answers the open
  question of the vision check above.
- **int8 is the file to use:** it is as close to the original model as the GGUF, and on the phone it was not slower
  than int4 (it ran second, on a hotter phone). The app's default is int8. int4 stays as the 0.7 GB smaller option.
- **fp16 is not worth it:** no closer than int8 (same action 0.906 on the Mac), and 1.7 GB larger.

### Terminal steps ("task complete")

60 high-level steps whose ground truth is `status complete` (separate set, `build_set.py --terminal 60`):

| | GGUF | LiteRT int4 |
|---|---|---|
| Right (`status complete`) | 0.650 | 0.667 |
| Same action as GGUF | — | 0.883 |
| Right only in this file | 2 | 3 |

### One engine across conversations (LiteRT-LM issue #3165)

Issue #3165 is open, and its fix is not in 0.19.1: a new conversation on an engine that already ran one can start
from the earlier conversation's short-convolution state.

- **Test:** the first 30 rows on the Mac, Python, one engine for all rows vs a new engine per row.
- **Actions:** identical on 29 of 30. The one difference is a click 1 unit apart (y 762 vs 763).
- **Full output (intent and action):** identical on 28 of 30. One more intent was worded differently.
- **What this does not cover:** long runs, growing history, or the phone. The app therefore creates a new engine for
  every run (about 4 s once per run). The on-phone step-by-step comparison is in `results/phone/`.

### Notes on the parity runs

- **llama-server prompt cache:** the GGUF run had it on, as the server defaults, and it answered 2 rows from cache.
  Runs from now on turn it off.
- **LiteRT runs:** passed neither a 512-token output limit nor the app's `filter_channel_content_from_kv_cache=false`
  (`run.py` now passes both). Single-turn replies here are 20–30 tokens, so neither changes them.
- **Speed:** Mac numbers are not phone numbers. On the Mac, Metal llama.cpp ran faster (median 2.1 s per row) than
  LiteRT-LM's WebGPU path (6.7 s).
