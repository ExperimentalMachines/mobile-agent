# Mobile Agent (Android app)

An on-device phone agent. You type a goal; the app takes a screenshot, asks the fine-tuned LFM3-VL-2B
("MobileAgent v4") for one action, performs it, and repeats until the model says it is done. The model runs
with Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) on the phone's GPU. Nothing leaves the phone
(the app has no INTERNET permission).

- Working name and placeholder mark (a screen outline with a tap point); branding comes later.
- Package `org.experimentalmachines.mobileagent`, minSdk 31, arm64, LiteRT-LM 0.19.1 (the only version tested).
- The prompt, parser, image size, step loop and action execution are ported from the research repo's benchmark
  harness (see [Same as the benchmarks](#same-as-the-benchmarks)). The accessibility layer (screenshots, gestures,
  the guard's first version, the stall watch) was adapted from the organisation's earlier on-device agent prototype.

## Measured

Two Qualcomm Device Cloud sessions of one reference phone (SM8975 "Art", Adreno GPU, Android 17, 1440 × 3200):
`ac41dea9` (15.5 GB RAM, cold) and `b3ba6e19` (11.5 GB RAM, warm after two 180-row parity runs). BENCH = N model calls
on a 1440 × 3200 launcher screenshot with the agent's real prompt (goal "Open the Clock app", 60 installed apps,
1,310 tokens); every call answered `{"action_type":"open_app","app_name":"Clock"}`. Sources: `results/phone/` (bench
JSONs, run.json files, `tasks_2026-11-23.md`).

| Phone, file, state | Calls | Model call (median) | First token | Prefill | Decode | Engine load |
|---|---|---|---|---|---|---|
| `ac41dea9`, int4, GPU + GPU, cold | 10 | **2.46 s** | 1.10 s | 1,213 tok/s | 43.3 tok/s | 4.3 s |
| `ac41dea9`, int4, GPU + GPU, new engine every call | 5 | 2.55 s | 1.15 s | 1,176 tok/s | 30.7 tok/s | 4.27 s per call |
| `ac41dea9`, int4, CPU + CPU | 2 | 10.8 s, 14.0 s | 7.3 s, 6.8 s | 182, 196 tok/s | 14.1, 12.2 tok/s | 2.2 s |
| `b3ba6e19`, **int8**, GPU + GPU, warm (thermal 0 → 1) | 10 | 3.53 s (first calls 2.5–2.9 s) | 1.37 s | 986 tok/s | 23.9 tok/s | 3.4–5.9 s |
| `b3ba6e19`, int8, warm, 8 earlier steps in the prompt (1,480 tokens) | 5 | 3.45 s | 1.52 s | 1,000 tok/s | 24.1 tok/s | |

- **Warm means throttled:** at the end of the `b3ba6e19` session the CPU clusters were capped at 2.53 / 2.30 / 2.65 GHz
  (max 3.74 / 4.03 / 5.01) and the GPU at 636 MHz (max 1,450); skin 48 °C. int8 and the throttling changed
  together, so their effects are not separated. BENCH now records thermal status and clocks in its JSON.
- **Vision encoder** (LiteRT-LM mark `vision_executor`): 0.75–0.88 s on the GPU, 2.3–5.7 s on the CPU. LiteRT-LM's
  time to first token does not include it; the first streamed piece arrived 1.9–2.5 s after sending (int4, cold).
- **Thought cache filtering:** with LiteRT-LM's default `filterChannelContentFromKvCache`, every call spent ~3.5 s
  after its last token, presumably rewriting a cache that is never reused (5.8–7.3 s per call, 11 calls, int4). The
  app turns it off: 2.3–2.9 s per call (18 calls), same replies.
- **Per step, besides the model:** screenshot 150–300 ms (incl. Display P3 → sRGB), resize + PNG 100–400 ms, action
  110–150 ms, then 1 s and a quiet screen (up to 4 s more while the screen keeps moving).
- **Agent tasks** (`results/phone/tasks_2026-11-23.md`; int8, `b3ba6e19`): 3 of 11 succeeded (7:30 AM alarm,
  Chrome search with typing, About phone with scrolling), 1 unclear, 7 failed (wrong timer length, questions answered
  without looking, never ending after reaching the goal, `wait` loops, a sign-in detour that the guard held).
- **Not like for like with llama.cpp:** the research repo's llama.cpp figure (25.6–36.9 s per step on this phone's CPU)
  is end to end per step; the CPU row above is per model call.

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

The release build (`assembleRelease`) is signed with the debug key and not minified; it has no adb hooks.

On a test phone the three grants can be given over adb:

```sh
P=org.experimentalmachines.mobileagent
adb shell settings put secure enabled_accessibility_services $P/$P.agent.AgentService   # replaces the list
adb shell settings put secure accessibility_enabled 1
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
```

## Model files

Two files of the same model; **int8 is the default**, int4 the smaller option:

| File | Bytes | sha256 | Same action as the bf16 model (180 parity rows, on the phone) | Click distance p90 | Per row (median) |
|---|---|---|---|---|---|
| `LFM3-VL-2B-MobileAgent-v4_int8_g1280x576_ctx4096.litertlm` | 2,760,755,451 | `8eba5809…870d` | 90.6% | 2 units | 3.63 s |
| `LFM3-VL-2B-MobileAgent-v4_int4_g1280x576_ctx4096.litertlm` | 2,072,561,915 | `6008eef7…a816` | 84.4% | 103 units | 4.07 s |

Parity: the `PARITY` hook on `b3ba6e19` (below), scored on the Mac against the bf16 Hugging Face model; the phone was
running hot (thermal status 3 at the end), so the per-row times are not the cold numbers in [Measured](#measured).

Folder: `/sdcard/Android/data/org.experimentalmachines.mobileagent/files/models/` (open the app once first, so the
folder belongs to the app). Each `.litertlm` needs a sidecar JSON next to it; `model.json` is picked first, and the
model card lists the others (Change).

| Field | Example | Meaning |
|---|---|---|
| `file` | `LFM3-VL-2B-MobileAgent-v4_int8_g1280x576_ctx4096.litertlm` | The model file in the same folder |
| `bytes` | `2760755451` | Size check on the home screen |
| `sha256` | `8eba5809…` | "Verify checksum" on the home screen |
| `image_height`, `image_width` | `1280`, `576` | The fixed grid every screenshot is resized to |
| `context_tokens` | `4096` | Shown on the model card |
| `display_name` | `MobileAgent v4 (int8, 1280x576, ctx 4096)` | Shown on the model card |
| `prompt_version` | `v4` (default) | `v4`: system prompt v4 + installed apps; `v1`: models v1–v3 |
| `enable_thinking` | `true` (default) | Passed to the chat template |

Copy the file into the folder on the phone, then give it to the app's user id (a root shell's files are root-owned):

```sh
D=/sdcard/Android/data/org.experimentalmachines.mobileagent/files/models
adb shell cp /data/local/tmp/<file>.litertlm $D/          # or: adb shell curl -L -o $D/<file> https://huggingface.co/...
adb shell stat -c %U /data/media/0/Android/data/org.experimentalmachines.mobileagent      # e.g. u0_a252
adb shell chown -R u0_a252:ext_data_rw /data/media/0/Android/data/org.experimentalmachines.mobileagent/files/models
```

On the first QDC session (`ac41dea9`) the Hugging Face CDN cut every phone-side download after ~2.5 MB and answered
range requests with 502; on the second (`b3ba6e19`) a 100 MB range request finished in 7 s (16.8 MB/s). The files
were pushed to `/data/local/tmp` and copied.

### Which screens the file fits

Training resized every screenshot with `smart_resize` and the file has one fixed grid, 1280 × 576 (720 image
tokens). That is what `smart_resize` gives most 20:9 portrait phones (1440 × 3200, 1080 × 2400). Other screens get a
different grid in training (1080 × 2424 → 1312 × 576, 1080 × 1920 → 1152 × 640, landscape 2400 × 1080 → 576 × 1280,
a foldable's inner screen → 800 × 960): the app still runs there but stretches the screenshot, and its accuracy is
not measured. The model card shows this screen's grid, the Run section warns when it differs, and `run.json` records
`screenGrid` and `gridMatches`. Training images were also JPEG-compressed after the resize; the app sends PNG.

## What the app stores, and logging

| Where | What | Kept |
|---|---|---|
| `files/runs/<id>/run.json` | The goal, every model reply (thought and text), actions, outcomes, timings, the installed-app list | The last 50 runs (Settings › Runs kept); Run log › Delete all |
| `files/runs/<id>/step-NN.jpg` | A quarter-size screenshot per step | With its run |
| `files/models/` | Model files and sidecars | Until you delete them |
| logcat `MOBILEAGENT_STEP` / `_RESULT` | One line per step and per run | Logcat's ring buffer |

`files/` is `/sdcard/Android/data/org.experimentalmachines.mobileagent/files/` (app-specific storage: readable by
adb and by the user's file transfer, removed with the app).

- **Redaction:** text typed into a password field, or by a step the guard flagged, is replaced by `[redacted]`
  everywhere it appears in `run.json` and logcat (goal, intents, replies, actions, answer). The model's own history
  keeps the real text, as the prompt format requires.
- **Verbose logcat lines** (goal, intents, actions, answers) are on in debug builds and off in release builds; without
  them the lines carry only step numbers, action types and timings. adb `LOGS --ez verbose B` switches them.
- Screenshots are stored as they were: a step that showed something private keeps it until the run is deleted.

## adb hooks (debug builds only)

The receiver and its commands are in the debug source set: a release build has none of them. They are broadcasts to
`org.experimentalmachines.mobileagent/.host.HostCommandReceiver`, action `org.experimentalmachines.mobileagent.<NAME>`,
guarded by the DUMP permission. adb's shell holds DUMP; so do some privileged system apps, and it can be granted, so
treat a debug build as a test build. No hook can switch the safety guard off.

| Name | Extras | Does |
|---|---|---|
| `RUN_GOAL` | `--es goal "..."` `[--ei max_steps N]` `[--ez engine_per_step B]` `[--ez enter B]` `[--ez verbose_logs B]` `[--es script '[...]']` | Starts a run (`script`: fixed replies instead of the model) |
| `STOP`, `PAUSE`, `RESUME` | | Controls the run |
| `ALLOW`, `SKIP` | | Answers the safety guard's question |
| `EXEC` | `--es action '<action JSON>'` `[--ez enter B]` | One action through the agent's executor (no model, no guard) |
| `LOAD`, `UNLOAD` | `[--es backend gpu\|cpu]` `[--es vision_backend gpu\|cpu]` (saved to settings) | Loads / frees the engine |
| `BENCH` | `--es image bench.png` `[--ei n 5]` `[--ei history N]` `[--es goal "..."]` `[--ez new_engine B]` `[--ei max_tokens 512]` `[--ez litert_benchmark B]` `[--es filter_thought true\|false\|default]` | N model calls on a PNG from the files folder; records thermal status and clocks |
| `PARITY` | `--es rows parity/rows.jsonl` `[--es out ...]` `[--ei limit N]` `[--ez new_engine_per_row B]` | Parity rows `{id, system, user, image}` through the agent's exact model call; writes `{id, content, reasoning, seconds}` |
| `RENDER` | `[--es goal "..."]` | Saves the runtime's rendering of the prompt to `files/render.json` |
| `WINDOWS` | | Logs the windows the accessibility service sees |
| `LOGS` | `--ez verbose B` | Verbose logcat lines on / off |

```sh
adb shell am broadcast -n org.experimentalmachines.mobileagent/.host.HostCommandReceiver \
  -a org.experimentalmachines.mobileagent.RUN_GOAL --es goal "'Open the Clock app'" --ei max_steps 3
adb shell screencap -p /sdcard/Android/data/org.experimentalmachines.mobileagent/files/bench.png
adb shell am broadcast -n org.experimentalmachines.mobileagent/.host.HostCommandReceiver \
  -a org.experimentalmachines.mobileagent.BENCH --es image bench.png --ei n 10
adb logcat -s MobileAgent
```

## Settings

| Setting | Default | Note |
|---|---|---|
| Text decoder, vision encoder | GPU, GPU | Applies at the next load |
| New engine every step | Off | Off: a new engine per **run** (~4.3 s once). On: a new engine per step (~4.3 s per step). Both work around LiteRT-LM issue #3165 |
| Step limit | 20 | AndroidWorld's budget is 10 × task complexity; its median task complexity is 2 |
| Ask before risky steps | On | The safety guard (below) |
| Show taps | On | A ring where each tap lands (accessibility overlay, gone before the next screenshot) |
| Press Enter after typing | On | AndroidWorld's `input_text` (what v4 was benchmarked on); off = MobileWorld-native typing |
| Runs kept | 50 | Older runs are deleted after each run |

## Same as the benchmarks

| Part | Source in the research repo | Here |
|---|---|---|
| System prompt v1 / v4, user text, history (last 8 steps), action JSON | `train/agent_format.py` | `protocol/AgentFormat.kt` (byte-for-byte golden tests) |
| Lenient and strict action parser | `agent_format.parse_action` | `AgentFormat.parseAction` (52 golden cases, Python JSON semantics) |
| Image: `smart_resize`, Pillow BICUBIC | `agent_format.resize_screenshot` | `image/Resample.kt` to the sidecar's grid (pixel-identical to Pillow 13 on the test images) |
| 0–1000 point → pixels (round half to even) | `agent_format.norm_to_px` | `AgentFormat.normToPx` |
| Step loop: one parse retry, `(invalid output)` history, intent = first line of the thought, `status` / `answer` end the run, the step limit, 1 s after an action | `eval/androidworld/lfm_agent.py` `step()` | `agent/AgentLoop.kt`; a unit test replays scripted episodes through a copy of `step()` and compares every prompt, action and ending |
| Action execution (tap, 1 s long press, full-screen scroll from the centre, type at the cursor + Enter, 1 s wait) | `android_world/env/actuation.py` | `AgentController.execute` |
| `open_app` name matching | AndroidWorld's app table; `eval/androidworld/app_resolver.py` (lexical fallback) | Launcher labels (exact, prefix, contains, package), then a port of `app_resolver` (golden-tested) |
| Greedy decoding, thinking on, max 512 tokens | `lfm_agent.py` (temperature 0) | top-k 1, `enable_thinking: true` |
| Loop guards G1 / G2 | `train/agent_guards.py` (off in the sealed v4 runs) | Logged only (`loopRules`); no re-ask |

Where the app differs from the harness, and records it:

- **Installed apps:** the prompt lists every launcher label (60 on the QDC phones). Training lists had 12–30 names;
  the effect of longer lists is not measured. `run.json` has `installedAppCount`.
- **Guarded steps** (below): a step the user skips, or one held back because the screen changed, enters the history
  with a suffix in the style of the harness's opt-in runtime feedback (`[failed to execute]`):
  `... [skipped by the user]` / `... [not executed: the screen changed]`. `run.json` has each step's `historyEntry`.

The runtime's rendered prompt (`RENDER`) equals the fine-tuned model's Hugging Face chat template output, apart from
the runtime's image placeholder and the BOS token. Regenerate the goldens with
`tools/gen_golden.py --train-dir <research repo>/train` (Python 3.12 + Pillow).

## Safety guard

Before a tap, long press, typing, Enter or scroll, the app checks the live screen and asks (floating control and
notification: Allow / Skip / Stop; no answer in 2 minutes stops the run) when:

| Rule | Covers |
|---|---|
| A sensitive app is on screen | Package installers, permission controller, credential manager, keychain, managed provisioning, Google Play services (account sign-in, payments), Play Store, Google Wallet, OEM security centres |
| The front window hides its content from the service | Android 14+ permission prompts (the service sees no nodes, its taps are ignored, and Android hides the floating control) |
| A Settings page that grants access (taps and typing, not scrolling) | Page title mentions accessibility, notification access, special app access, device admin, install unknown apps, display over other apps, modify system settings, all files / usage access, default apps, passwords / credentials, VPN, developer options, reset / erase |
| Typing into a password field | Any app |
| Enter in a messaging, mail, phone or chat app | `keyboard_enter`, and `input_text` when "Press Enter after typing" is on (package name contains mms, messag, mail, dialer, telecom, chat, whatsapp, telegram, signal) |
| The tapped element's text, description or view id says | pay, buy, purchase, checkout, place order, subscribe, transfer, send, call, share, post, install, confirm, accept, allow, sign in, log in, add account, delete, remove account, uninstall, erase, reset |
| Nothing readable under a tap | No accessibility node there, or an opaque surface (WebView canvas, SurfaceView, TextureView) with no label: games, maps, drawings, OpenGL apps |

- **Words are English;** view ids (`send_button`, `permission_allow_button`) match in any language.
- **Time of use:** right before the gesture (with the floating control hidden) the app reads the screen again. If the
  risk, the app in front (also compared with the screenshot's), the element's app, or the focused field (for typing)
  changed, or, for a step the user was asked about, the element under the tap changed, the action is not executed
  and the step says why.

## Known issues

- **LiteRT-LM #3165** (fixed on main, not in 0.19.1): a new conversation on a used engine can continue from the previous
  conversation's state. The app makes a new engine per run (or per step); within a run, steps share one engine.
- **Accuracy on the phone** is far from solved: see the task table in `results/phone/tasks_2026-11-23.md`
  (wrong AM/PM, 5 s instead of 5 min, `wait` loops, one detour into Google sign-in before the guard covered it).
- **Loop guards** (G1 `wait` under a non-wait intent, G2 repeats on an unchanged screen) are logged, not enforced; the
  `wait` loops in the task table are exactly what they flag.
- **Permission prompts:** Android 14+ hides them from the service, ignores its taps there and hides the floating
  control. The guard asks (notification: Allow / Skip / Stop); the user has to answer the prompt.
- **Conservative guard:** apps drawn without accessibility nodes (OpenGL galleries, games, maps) ask before every tap.
- **Floating control** sits at the top and can cover an app's tab bar for the user (it is hidden during screenshots
  and gestures, so the model and the taps never see it); drag it away.
- **Typing** uses the accessibility "set text" action at the field's cursor, not key events; Enter is the IME action
  of the focused field, so with no focused field `keyboard_enter` does nothing.
- **`open_app`** resolves launcher labels; the benchmark used AndroidWorld's own app table. Names that are not lexically
  close ("Messages" for "Messaging") do not resolve, as in the research resolver.
- **Tested on one phone model** (SM8975 reference phone, Adreno GPU, Android 17) in two sessions.
