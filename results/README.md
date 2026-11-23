# results/

The raw outputs behind the numbers in [`../README.md`](../README.md) and [`../docs/conversion.md`](../docs/conversion.md).

## parity/: LiteRT file vs GGUF on the Mac

| File | What |
|---|---|
| `set_rows.jsonl` | The 180 AndroidControl test steps (v4 prompt, ground truth). Screenshots are not included: `convert/parity/build_set.py` rebuilds them from `experimentalmachines/MobileAgent-SFT-v1` |
| `gguf_v4.jsonl` | Reference: v4 Q4_K_M GGUF, llama.cpp `cd959bae9` with the evaluation flags (Mac M5, Metal) |
| `litert_v4_int4_shared.jsonl` | The `.litertlm` file, LiteRT-LM 0.19.1 Python, Mac GPU, one engine for all rows |
| `litert_v4_int4_perrow30.jsonl` | The same file with a new engine per row, first 30 rows (LiteRT-LM issue #3165 check) |
| `compare_gguf_vs_litert_shared.json` | `convert/parity/compare.py` output: accuracy per file, agreement, paired counts (GGUF as the reference) |
| `check_vision.txt` | `convert/parity/check_vision.py`: per-token cosine of the exported vision graphs vs the original model, 6 screenshots |
| `hf_bf16_v4.jsonl` | The unquantized reference: merged v4 in Transformers bf16 (Mac MPS), as the published AndroidControl numbers were made (`convert/parity/run_hf.py`) |
| `litert_v4_int8.jsonl`, `litert_v4_f16.jsonl` | The int8 and fp16 decoder files on the Mac (LiteRT-LM 0.19.1 Python, GPU) |
| `litert_phone_int4.jsonl`, `litert_phone_int8.jsonl` | The same 180 rows on the phone through the app's `PARITY` hook (QDC session 2, device b3ba6e19, 11.5 GB RAM) |
| `compare_all_vs_bf16.json` | Every run above against bf16 |
| `set_terminal_rows.jsonl`, `terminal_*.jsonl`, `compare_terminal.json` | 60 terminal steps (ground truth `status complete`): GGUF, LiteRT int4 and int8 on the Mac |

## phone/: the app on the QDC phone

Snapdragon SM8975 reference phone, Android 17, LiteRT-LM 0.19.1 (Kotlin), the same `.litertlm` file. Times are the
phone's clock (UTC−8).

| Folder | What |
|---|---|
| `bench/bench-*.json` | `BENCH` hook: N model calls on one 1440 × 3200 launcher screenshot. The files do not record the `filterChannelContentFromKvCache` setting: the runs at 5.8–7.1 s per call (…380212, …215229, …294664, …377099) had it on (the runtime default), the 2.3–2.5 s runs had it off. …194821 is the headline (10 calls, one engine), …242955 a new engine per call, …295417 the CPU |
| `runs/<id>/run.json` | Every agent run, every step: prompt-side counters, thought, action, timings. Includes a small JPEG per step |

All 9 agent runs from the first session, in order:

| Run | Goal | Result | Note |
|---|---|---|---|
| 215031 | Open the Clock app | Step limit (3) | The Clock app's first-launch permission dialog: the model tapped it, and on Android 17 the tap has no effect |
| 215834, 220125, 220445 | Open the Clock app | `needs_user` | The guard now detects such dialogs and asks the user (Skip / time-out tested) |
| 220818, 220844, 221820, 222024 | Open the Clock app | Complete, 2 steps | `open_app Clock`, then `status complete` |
| 221516 | Start the stopwatch | Complete, 4 steps | Open Clock, Stopwatch tab, Start, `status complete` |
