# Response to review 1 (@ 9fa3ca3)

The report is in `review1_report.md`, and the prompt the reviewer ran is in `PROMPT_review1.md`. Verdict: SHIP WITH FIXES.

| # | Finding (short) | What was done | Where |
|---|---|---|---|
| 1 | Guard misses common risky actions | Send/call/share/post/install/confirm/accept/allow labels, Play Store and Google sign-in (`com.google.android.gms`), Settings grant screens, Enter in messaging apps, no-node targets ask; the README lists exactly what is covered. **Found on the phone:** an unguarded Send (the guard read the keyboard, not the app under the tap), now fixed and re-tested (guard asked). **Still open:** labels English only; password and delete tests not reached on the phone (unit tests only) | `android/` |
| 2 | adb hooks in every build; `confirm` override | Hooks moved to the debug source set; the `confirm` override removed. Checked: the release APK's manifest has no `HostCommandReceiver` | `android/` |
| 3 | Parity reference misattributed | Docs now name the GGUF as the AndroidWorld / MobileWorld / phone-benchmark file and say the published AndroidControl numbers are bf16. **Added the bf16 model itself** on the same 180 rows as the reference | `docs/conversion.md`, `results/parity/hf_bf16_v4.jsonl` |
| 4 | "Same accuracy" overstated; terminal rows untested | Claim is now "no difference detected (±4.4 points)". Added 60 terminal rows (GGUF 0.650, LiteRT 0.667). Measured closeness to bf16: **int4 is further from the original model than the GGUF** (same action 0.833 vs 0.894). Built **int8** (0.900 / on the phone 0.906, click p90 2) and fp16 (0.906); **int8 is the new default** | `docs/conversion.md`, `results/parity/` |
| 5 | Every screen stretched to 1280×576 | Docs and README state which screens match training; the app shows a warning on the model card and before a run, and records it in run.json | `docs/conversion.md`, `README.md`, `android/` |
| 6 | Vision checked only on the Mac CPU fp32 | Ran all 180 rows on the phone GPU: int4 on the phone = int4 on the Mac on 98.3% of actions; int8 on the phone matches bf16 on 90.6%. Docs say "CPU, fp32" for the tower check | `docs/conversion.md` |
| 7 | #3165 check does not match app use; 29/30 overstated | Wording: actions 29/30, full output 28/30. A new engine per run by default. On the phone, the steps of a one-engine run and an engine-per-step run that saw the same screen gave identical output (2 tasks). This is not a controlled test | `docs/conversion.md`, `android/`, `results/phone/` |
| 8 | Speed comparison not like for like | README compares CPU with CPU on the phone and gives GPU numbers separately, with n, the phone and thermal state | `README.md` |
| 9 | Typed text and screens kept with no redaction or retention | Redaction of typed secrets in logcat and run.json (unit-tested, not exercised on the phone), verbose logcat opt-in (on in debug builds), Delete all runs, keep the last 50 runs, storage documented | `android/` |
| 10 | No LICENSE | Apache-2.0 `LICENSE` added; modified-file headers on the three patched files, copied-unchanged headers on the other two | `LICENSE`, `convert/lfm3vl/` |
| 11 | Not reproducible from the repo | Lock files for all three envs (verified in fresh envs), `convert/fetch_base.py` with a sha256 manifest, dataset revision pinned (`f88a8432`) | `convert/` |
| 12 | Skipped step stays in history | A skipped or held-back step is marked in the history as not executed | `android/` |
| 13 | Installed-app list longer than in training | Count logged per run; README note (training lists 12–30, test phone 60); not capped | `android/` |
| 14 | Small number / wording issues | Step times and memory from the recipe log; cosine written as 0.99999985; "tested with 0.19.1 only"; vision time stated separately from time to first token | `docs/conversion.md`, `README.md` |
| 15 | Parity harness details | `run.py` passes 512 max tokens and the app's thought filter for LiteRT, and turns off llama-server's prompt cache; earlier runs' settings are noted | `convert/parity/run.py`, `docs/conversion.md` |
| 16 | No agent-loop test | `AgentLoop` test replays scripted episodes through a copy of `lfm_agent.py`'s `step()`: prompts, actions and endings match. 27 unit tests pass (re-run) | `android/` |
| 17 | Hygiene | Full 40-hex pins checked after checkout; dead files and stale comments removed; origin and debug-key release signing noted in the README. The dataset e-mail addresses in `set_rows.jsonl` come from AndroidControl's public goals and are left as is | various |

Also from the automated security review: logcat redaction (= #9); the guard now re-checks the live screen right before each action and does not execute if it changed (checked on the phone with the permission prompt: "risk changed", the tap was held back); third_party pinned to full SHAs.
