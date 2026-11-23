# Mobile Agent (Android app)

An on-device phone agent. You type a goal; the app takes a screenshot, asks the fine-tuned LFM3-VL-2B
("MobileAgent v4") for one action, performs it, and repeats until the model says it is done. The model runs
with Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) on the phone's GPU. Nothing leaves the phone.

- Working name and placeholder mark (a screen outline with a tap point); branding comes later.
- Package `org.experimentalmachines.mobileagent`, minSdk 31, arm64, LiteRT-LM 0.19.1.
- The prompt, parser, image size and action execution are ported from the research repo's benchmark harness
  (see [Same as the benchmarks](#same-as-the-benchmarks)).

## Measured

Phone: Snapdragon SM8975 reference phone (Qualcomm Device Cloud), Android 17, 15.5 GB RAM, 1440 × 3200.
File: `LFM3-VL-2B-MobileAgent-v4_int4_g1280x576_ctx4096.litertlm` (2,072,561,915 bytes; image grid 1280 × 576).
BENCH image: a 1440 × 3200 launcher screenshot, goal "Open the Clock app", empty history; the prompt has
1,310 tokens with this phone's 60 launcher apps. Every call answered `{"action_type":"open_app","app_name":"Clock"}`.

| Setting | Calls | Model call (median) | Time to first token | Prefill | Decode | Engine load |
|---|---|---|---|---|---|---|
| GPU text + GPU vision, one engine | 10 | **2.46 s** | 1.10 s | 1,213 tok/s | 43.3 tok/s | 4.3 s |
| GPU + GPU, new engine every call | 5 | 2.55 s | 1.15 s | 1,176 tok/s | 30.7 tok/s | 4.27 s per call |
| CPU text + CPU vision, one engine | 2 | 10.8 s, 14.0 s | 7.3 s, 6.8 s | 182, 196 tok/s | 14.1, 12.2 tok/s | 2.2 s |

- **Vision encoder** (LiteRT-LM mark `vision_executor`): 0.75–0.80 s on the GPU, 2.3–5.7 s on the CPU. The time to
  first token above is LiteRT-LM's and does not include it; the first streamed piece arrived 1.9–2.5 s after sending.
- **Engine load:** 8.7 s for the very first load after the first install (one sample), 4.0–4.3 s afterwards
  (probably LiteRT-LM's cache in the app's cache folder; not checked).
- **Thought cache filtering:** with LiteRT-LM's default `filterChannelContentFromKvCache`, every call spent ~3.5 s
  after its last token, presumably rewriting a cache that is never reused (5.8–7.3 s per call, 11 calls). The app
  turns it off: 2.3–2.9 s per call (18 GPU calls), same replies.
- **Per step, besides the model:** screenshot 150–230 ms (incl. Display P3 → sRGB), resize + PNG 100–400 ms,
  action 110–150 ms, then 1 s and a quiet screen (up to 4 s more while the screen keeps moving).
- **Agent runs (GPU, one engine):** "Open the Clock app" → `open_app Clock`, `status complete` (2 steps);
  "Start the stopwatch" → `open_app Clock`, tap Stopwatch tab, tap Start, `status complete` (4 steps).
  Model calls 2.4–2.7 s per step.

Numbers come from the app's own logs (`MOBILEAGENT_BENCH`, `MOBILEAGENT_STEP`) on 2026-11-22/23.

## Permissions and why

| Permission | Why | Granted how |
|---|---|---|
| Accessibility service | Screenshots, taps, swipes, typing, Back / Home | Home screen › Turn on (Android settings) |
| Display over other apps | The floating Stop / Pause control during a run | Home screen › Allow |
| Notifications (Android 13+) | Run status with Stop / Pause (and Allow / Skip for the guard) | Home screen › Allow |
| Foreground service (special use) | Keeps a run going while other apps are in front | Declared; no prompt |
| `<queries>` MAIN/LAUNCHER | The prompt's "Installed apps" line and `open_app` | Declared; no QUERY_ALL_PACKAGES |

- Battery-optimization exemption is not needed: a run is a foreground service.
- A sideloaded app's accessibility setting can be "restricted": App info › ⋮ › Allow restricted settings.

## Install

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug     # JDK 21, Android SDK in local.properties
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.experimentalmachines.mobileagent/.MainActivity   # creates its folders first
```

On a test phone the three grants can be given over adb:

```sh
P=org.experimentalmachines.mobileagent
adb shell settings put secure enabled_accessibility_services $P/$P.agent.AgentService   # replaces the list
adb shell settings put secure accessibility_enabled 1
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
```

## Model files

Folder: `/sdcard/Android/data/org.experimentalmachines.mobileagent/files/models/` (open the app once first, so the
folder belongs to the app). Each `.litertlm` needs a sidecar JSON next to it; `model.json` is picked first.

| Field | Example | Meaning |
|---|---|---|
| `file` | `LFM3-VL-2B-MobileAgent-v4_int4_g1280x576_ctx4096.litertlm` | The model file in the same folder |
| `bytes` | `2072561915` | Size check on the home screen |
| `sha256` | `6008eef7…` | "Verify checksum" on the home screen |
| `image_height`, `image_width` | `1280`, `576` | The fixed grid every screenshot is resized to |
| `context_tokens` | `4096` | Shown on the model card |
| `display_name` | `MobileAgent v4 (int4, 1280x576, ctx 4096)` | Shown on the model card |
| `prompt_version` | `v4` (default) | `v4`: system prompt v4 + installed apps; `v1`: models v1–v3 |
| `enable_thinking` | `true` (default) | Passed to the chat template |

Copy or download on the phone (root shell on a test phone), then give the files to the app's user id:

```sh
D=/sdcard/Android/data/org.experimentalmachines.mobileagent/files/models
adb shell curl -L -o $D/<file>.litertlm https://huggingface.co/<repo>/resolve/main/<file>.litertlm
adb shell stat -c %U /data/media/0/Android/data/org.experimentalmachines.mobileagent      # e.g. u0_a252
adb shell chown u0_a252:ext_data_rw /data/media/0/Android/data/org.experimentalmachines.mobileagent/files/models/*
```

`adb push` into the folder after the app has created it is how Google's sample installs its model (not tried here).

On the QDC phone the Hugging Face CDN cut every download after ~2.5 MB (and answered range requests with 502), so
files were copied from `/data/local/tmp` instead; `adb push` through the tunnel ran at 2.2 MB/s.

## adb hooks

All are broadcasts to `org.experimentalmachines.mobileagent/.host.HostCommandReceiver`, action
`org.experimentalmachines.mobileagent.<NAME>`; only adb's shell can send them (guarded by DUMP).

| Name | Extras | Does |
|---|---|---|
| `RUN_GOAL` | `--es goal "..."` `[--ei max_steps N]` `[--ez engine_per_step B]` `[--ez confirm B]` | Starts a run |
| `STOP`, `PAUSE`, `RESUME` | | Controls the run |
| `ALLOW`, `SKIP` | | Answers the safety guard's question |
| `LOAD`, `UNLOAD` | `[--es backend gpu\|cpu]` `[--es vision_backend gpu\|cpu]` (saved to settings) | Loads / frees the engine |
| `BENCH` | `--es image bench.png` `[--ei n 5]` `[--es goal "..."]` `[--ez new_engine B]` `[--ei max_tokens 512]` `[--ez litert_benchmark B]` `[--es filter_thought true\|false\|default]` | N model calls on a PNG from the files folder |
| `RENDER` | `[--es goal "..."]` | Saves the runtime's rendering of the prompt to `files/render.json` |
| `WINDOWS` | | Logs the windows the accessibility service sees |

```sh
adb shell am broadcast -n org.experimentalmachines.mobileagent/.host.HostCommandReceiver \
  -a org.experimentalmachines.mobileagent.RUN_GOAL --es goal "'Open the Clock app'" --ei max_steps 3
adb shell screencap -p /sdcard/Android/data/org.experimentalmachines.mobileagent/files/bench.png
adb shell am broadcast -n org.experimentalmachines.mobileagent/.host.HostCommandReceiver \
  -a org.experimentalmachines.mobileagent.BENCH --es image bench.png --ei n 10
adb logcat -s MobileAgent
```

## Logs

| Where | What |
|---|---|
| logcat `MOBILEAGENT_STEP {json}` | One line per step: intent, action, outcome, screenshot / prep / engine / model / act / settle ms, TTFT, prefill and decode tok/s, guard, loop rules |
| logcat `MOBILEAGENT_RESULT {json}` | One line per run: status, answer, steps, s/step, median model ms, load ms, path of run.json |
| logcat `MOBILEAGENT_BENCH_CALL`, `MOBILEAGENT_BENCH` | One line per bench call, then the summary (also `files/bench/bench-*.json`) |
| `files/runs/<id>/run.json` | The whole run: every model reply (thought and text), LiteRT-LM counters, taps in pixels; a small JPEG per step |
| Run log screen | The same, per step, with Export JSON (share sheet) |

## Settings

| Setting | Default | Note |
|---|---|---|
| Text decoder, vision encoder | GPU, GPU | Applies at the next load |
| New engine every step | Off | On: a fresh engine per step (works around LiteRT-LM issue #3165, ~4.2 s per step) |
| Step limit | 20 | AndroidWorld's budget is 10 × task complexity; its median task complexity is 2 |
| Ask before risky steps | On | The safety guard (below) |
| Show taps | On | A ring where each tap lands (accessibility overlay, gone before the next screenshot) |
| Press Enter after typing | On | AndroidWorld's `input_text` (what v4 was benchmarked on); off = MobileWorld-native typing |

## Same as the benchmarks

| Part | Source in the research repo | Here |
|---|---|---|
| System prompt v1 / v4, user text, history (last 8 steps), action JSON | `train/agent_format.py` | `protocol/AgentFormat.kt` (byte-for-byte golden tests) |
| Lenient and strict action parser | `agent_format.parse_action` | `AgentFormat.parseAction` (52 golden cases, Python JSON semantics) |
| Image: `smart_resize`, Pillow BICUBIC | `agent_format.resize_screenshot` | `image/Resample.kt` to the sidecar's grid (pixel-identical to Pillow 13 on the test images) |
| 0–1000 point → pixels (round half to even) | `agent_format.norm_to_px` | `AgentFormat.normToPx` |
| Step: one parse retry, intent = first line of the thought, `(invalid output)` history, `status` / `answer` end the run, 1 s after an action | `eval/androidworld/lfm_agent.py` | `agent/AgentController.kt` |
| Action execution (tap, 1 s long press, full-screen scroll from the centre, type + Enter, 1 s wait) | `android_world/env/actuation.py` | `AgentController.execute` |
| Greedy decoding, thinking on, max 512 tokens | `lfm_agent.py` (temperature 0) | top-k 1, `enable_thinking: true` |
| Loop guards G1 / G2 | `train/agent_guards.py` (off in the sealed v4 runs) | Logged only (`loopRules`); no re-ask |

The runtime's rendered prompt (`RENDER`) equals the fine-tuned model's Hugging Face chat template output, apart from
the runtime's image placeholder and the BOS token. Regenerate the goldens with
`tools/gen_golden.py --train-dir <research repo>/train` (Python 3.12 + Pillow).

## Safety guard

Before a tap, long press, typing, Enter or scroll, the app checks the live screen. It asks (floating control and
notification: Allow / Skip / Stop; no answer in 2 minutes stops the run) when:

- an installer, permission, credential, device-admin or wallet screen is on screen;
- the text goes into a password field;
- the tapped element says pay, buy, purchase, checkout, place order, subscribe, transfer, delete, uninstall, erase or
  reset;
- the active window hides its content from the service. On Android 17 a permission prompt does this: the service
  sees no node tree, its taps are ignored, and Android also hides the floating control.

## Known issues

- LiteRT-LM #3165 (fixed on main, not in 0.19.1): a new conversation on a used engine can continue from the previous
  conversation's state. Measure with "New engine every step" on and off.
- The floating control sits at the top and can cover an app's tab bar for the user (it is hidden during screenshots
  and gestures, so the model and the taps never see it); drag it away.
- Typing uses the accessibility "set text" action (the field's text plus the new text), not key events; Enter is
  the IME action of the focused field. With no focused field, `keyboard_enter` does nothing.
- `open_app` matches launcher labels (exact, prefix, contains, package name); the benchmark used AndroidWorld's
  own app table.
