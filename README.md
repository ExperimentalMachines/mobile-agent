# Mobile Agent

An on-device phone agent: [LFM3-VL-2B-MobileAgent-v4](https://huggingface.co/experimentalmachines/LFM3-VL-2B-MobileAgent-v4)
running with Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) on the phone's GPU. You type a goal. The
app screenshots the screen, asks the model for one action, performs it, and repeats until the model says the task is
done. Nothing leaves the phone.

- **Name and logo:** working name and placeholder mark. Branding comes once the model has proven fast and good enough.
- **Research:** the model and every benchmark result:
  [experimentalmachines/lfm3-mobile-agent](https://github.com/experimentalmachines/lfm3-mobile-agent).

## Status (2026-11-23)

| Question | Answer |
|---|---|
| One file for every phone? | Yes. One `.litertlm` (2.07 GB) runs on the CPU and GPU of any phone. Only an NPU build would need one file per chip (not made) |
| Same accuracy as the benchmarked model? | Yes. On 180 AndroidControl test steps it scores **0.800**, the same as the GGUF the published numbers came from (8 steps right only in each) |
| Same screenshot handling as training? | Yes. A fixed 1280 × 576 grid (720 image tokens), what training gave every 20:9 screen. The vision part matches the original model at cosine ≥ 0.9999999 |
| How fast on a phone? | **2.46 s per model call** (median of 10) on a Snapdragon SM8975 reference phone's GPU. llama.cpp on the same phone model's CPU took 28–39 s per step |
| Does the agent work on the phone? | Two short tasks done end to end: "Open the Clock app" (2 steps) and "Start the stopwatch" (4 steps) |

## Speed on the phone

Snapdragon SM8975 reference phone (Qualcomm Device Cloud), Android 17, LiteRT-LM 0.19.1. A 1440 × 3200 screenshot
resized to 576 × 1280, 1,310 prompt tokens.

| Setting | Model call (median) | First token | Prefill | Decode |
|---|---|---|---|---|
| GPU (text and vision), one engine | **2.46 s** | 1.10 s | 1,213 tok/s | 43.3 tok/s |
| CPU (text and vision) | 10.8–14.0 s (2 calls) | 6.8–7.3 s | ~189 tok/s | ~13 tok/s |
| llama.cpp CPU, same phone model, earlier benchmark | 27.7–39.2 s per step | — | — | — |

- **Image encoding:** about 0.8 s of each GPU call.
- **Engine load:** 4.3 s with a warm cache (8.7 s on the first load after install).
- **A step outside the model:** screenshot about 0.2 s and resize about 0.1–0.4 s. Then the app waits for the screen to
  settle (at least 1 s).
- **LiteRT-LM default turned off:** `filterChannelContentFromKvCache` added about 3.5 s to every call, so the app sets
  it to `false`.

## Layout

| Folder | What |
|---|---|
| [`android/`](android/) | The app: permissions, controls, safety guard, logs, adb hooks ([README](android/README.md)) |
| [`convert/`](convert/) | Model conversion to `.litertlm` and the parity checks |
| [`docs/conversion.md`](docs/conversion.md) | How the file was made and checked, with every number |

## Open items

- **Not yet run on a real phone:** typing, scrolling, long press, Back/Home and `answer`, plus a longer task set. Each
  has unit tests.
- **Permission prompts on Android 17:** they hide their content from accessibility services. The app asks the user
  through the notification instead of tapping them.
- **Model file not published:** the `.litertlm` is not on Hugging Face yet. On the test phone, the Hugging Face CDN
  dropped downloads after ~2.5 MB, so the file went over adb.
- **NPU builds:** not made. A per-chip build would be the next speed step once LiteRT supports LFM3 on the NPU.
