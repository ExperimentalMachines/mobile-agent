# Mobile Agent

An on-device phone agent: [LFM3-VL-2B-MobileAgent](https://huggingface.co/experimentalmachines/LFM3-VL-2B-MobileAgent)
running with Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) on the phone's GPU. You type a goal. The
app screenshots the screen, asks the model for one action, performs it, and repeats until the model says the task is
done. Nothing leaves the phone: the app has no internet permission.

- **Name and logo:** working name and placeholder mark. Branding comes once the model has proven fast and good enough.
- **Research:** the model and every benchmark result:
  [experimentalmachines/lfm3-mobile-agent](https://github.com/experimentalmachines/lfm3-mobile-agent).

## Status (2026-11-23)

| Question | Answer |
|---|---|
| One file for every phone? | **For CPU and GPU, by design**: LiteRT-LM needs no per-chip build. Tested so far on one phone model only (Snapdragon SM8975, two sessions). NPU builds (one per chip) were not made |
| Which file? | **int8** decoder (2.76 GB) is the default, published at [LFM3-VL-2B-MobileAgent-LiteRT](https://huggingface.co/experimentalmachines/LFM3-VL-2B-MobileAgent-LiteRT). int4 (2.07 GB) is smaller but further from the original model. fp16 (4.44 GB) is no better than int8 |
| As accurate as the benchmarked model? | **No difference detected** on 180 AndroidControl test steps: bf16 original 0.772, GGUF 0.800, int8 on the phone 0.783. int8 on the phone vs bf16: +1.1 points, 95% interval −2.0 to +4.2 |
| Same decisions as the original model? | int8 on the phone picks the **same action as the bf16 model on 90.6%** of steps (GGUF 89.4%, int4 84.4%). Its clicks land within 2 units of bf16's (90th percentile, 0–1000 grid) |
| Same screenshots as training? | Yes for screens whose training resize is 1280 × 576 (most 20:9 portrait phones, e.g. 1080 × 2400 and 1440 × 3200). Other shapes get stretched, and the app warns |
| How fast on a phone? | **About 2.5–3.6 s per model call** on the GPU of a Snapdragon SM8975 reference phone, depending on file and heat (table below) |
| Does the agent finish real tasks? | **Rarely so far: 3 of 11 on the phone** (int8), plus 1 of 7 earlier (int4). Each task ran once and was judged by us. Every action works when executed directly (table below) |

## Speed

Snapdragon SM8975 reference phone (Qualcomm Device Cloud, two sessions), Android 17, LiteRT-LM 0.19.1. A screenshot
resized to 576 × 1280 and a prompt of about 1,300 tokens.

| What | File | Phone | n | Model call | Notes |
|---|---|---|---|---|---|
| BENCH, cold phone | int4 | 15.5 GB | 10 | **2.46 s** median | First token 1.10 s after the 0.8 s vision encoding; decode 43 tok/s |
| 180 parity rows | int4 | 11.5 GB | 180 | 4.07 s median | Thermal status 0 → 2 |
| 180 parity rows | int8 | 11.5 GB | 180 | 3.63 s median | Ran right after the int4 rows; thermal status 1 → 3 |
| BENCH, warm phone | int8 | 11.5 GB | 10 | 3.53 s median | Clocks throttled: GPU capped at 636 of 1,450 MHz; decode 24 tok/s |
| BENCH, CPU only | int4 | 15.5 GB | 2 | 10.8 s, 14.0 s | |
| llama.cpp CPU (earlier benchmark) | GGUF Q4_K_M | 11.5 GB | 5 | 36.9 s on the phone | Same phone model, different day; about 1,080 prompt tokens |

- **Like-for-like comparison (CPU vs CPU):** LiteRT-LM took 10.8–14.0 s per call, llama.cpp 25.6–36.9 s.
- **Not a controlled comparison:** the CPU rows differ in phone, session, thread count and clock speed. The GPU rows
  are not comparable with them. int8 vs int4 speed is not measured cleanly: the int4 parity run slowed from 2.6 s to
  4.2 s per row as the phone heated, and int8 ran after it.
- **A full agent step adds about 2–3 s:** screenshot, resize, action, and waiting for the screen to settle.
- **LiteRT-LM default turned off:** `filterChannelContentFromKvCache` added about 3.5 s to every call, so the app turns
  it off.

## Agent on the phone

Model-driven tasks, greedy, one model call per step. Full table: [`results/phone/tasks_2026-11-23.md`](results/phone/tasks_2026-11-23.md).

| Task | int8, phone `b3ba6e19` | Cause of a miss |
|---|---|---|
| Alarm for 7:30 AM | success (9 steps) | int4 on the other phone made 7:30 PM |
| Chrome search (typing) | success (4 steps) | From a loaded page it loops on `wait` |
| Settings → About phone (scrolling) | success (4 steps) | — |
| 5-minute timer | fail | Sets 5 seconds |
| New contact | fail | Keeps tapping "Add an account" (the guard asks every time) |
| "What Android version?" / "Model name?" | fail | Answers at step 1 without looking ("Android 10", "Snapdragon") |
| Settings, then Home | fail | Reaches Home at step 2, never says done |
| Message draft | fail, unsafe then | Tapped **Send** without a guard question. Nothing was sent (no SIM). Fixed: the guard now reads the app under a tap, not the keyboard |
| Gallery album | fail | `wait` loop |
| Sound Recorder list | unclear | — |

- **What the misses look like:** they are consistent with the model's known weaknesses from the research benchmarks:
  `wait` loops, ending too early or too late, and answering without looking. Not shown yet: whether the original
  model would make the same choices on these screens. int8 differs from bf16 on 9.4% of single steps, which adds up
  over a 9-step task.
- **What would help:** the loop guards (G1/G2) that the research repo tested. Here they only log; they do not re-ask.

## Layout

| Folder | What |
|---|---|
| [`android/`](android/) | The app: permissions, controls, safety guard, privacy, logs, debug-only adb hooks ([README](android/README.md)) |
| [`convert/`](convert/) | Model conversion to `.litertlm`, pinned environments, parity tools |
| [`docs/conversion.md`](docs/conversion.md) | How the files were made and checked, with every number |
| [`results/`](results/) | Raw outputs behind the numbers |
| [`reviews/`](reviews/) | Adversarial review 1: prompt, report, and what was done about each finding |

## Open items

- **Task success** is the main gap. Next: turn on the loop guards, add a longer task set, and run AndroidWorld-style
  tasks on the phone.
- **Guard gaps** (review 2):
  - Labels are English only.
  - The "Enter sends" rule matches package-name fragments, so it misses e.g. Gmail and Messenger.
  - Confirm dialogs ("OK", "Discard") pass, because the dialog's text is not read.
  - The Send fix is untested below Android 13, where it falls back to window bounds.
  - Apps drawn without accessibility nodes get a question before every tap.
- **Screen change between screenshot and action:** the guard's re-check starts after the model call. A non-risky tap
  can land on a different element than the model saw if the app changed the screen during the 2.5–4 s call.
- **Debug build:** its adb hooks include `EXEC`, which acts without the guard. Use the release build outside testing.
- **Not exercised on the phone:** the password and delete-alarm guard tests, and redaction. The model never reached
  those screens. Unit tests only.
- **Only int8 is published** ([Hugging Face](https://huggingface.co/experimentalmachines/LFM3-VL-2B-MobileAgent-LiteRT), LFM Open License v1.0). int4 and fp16 are rebuilt with
  `convert/convert.sh`.
- **NPU builds** are not made.
