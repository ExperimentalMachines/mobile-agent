# Adversarial review 1 — Mobile Agent @ 9fa3ca3

You are an independent, adversarial reviewer. Your job is to find what is wrong, overstated or risky. Do not confirm;
try to break claims. Read-only: do not modify any file in the repositories or on any device; write only your report.

## What to review

A frozen copy of the commit is at `REVIEW_DIR` (given below). It contains:

- `README.md`, `docs/conversion.md`, `results/README.md`: claims about a model conversion, accuracy parity, speed on
  a phone, and an Android app.
- `convert/`: conversion runner (`convert_v4.sh`), patched third-party files (`convert/lfm3vl/`, see `NOTICE.md`),
  parity tools (`convert/parity/`).
- `android/`: an Android app (Kotlin) that runs a vision-language model as a phone agent with an accessibility
  service.
- `results/`: raw outputs behind the numbers.

Context you may need (read-only):
- The research repository the model and protocol come from: `~/lfm3-android` (notably `train/agent_format.py`,
  `eval/androidworld/lfm_agent.py`, `eval/androidcontrol/score.py`, `docs/results.md`).
- Unpatched upstream converter for comparison: `~/mobile-agent/third_party/hf-to-litertlm`
  (`lfm3vl_work/`), and LiteRT-LM source on GitHub (`gh api repos/google-ai-edge/LiteRT-LM/contents/<path>`).
- Local build outputs (not in the commit, read-only): `~/mobile-agent/build/` (parity set images in
  `build/parity_set/`, conversion logs in `~/mobile-agent/logs/`).
- Python with the scoring deps: `~/mobile-agent/.venv-run/bin/python` (set `LFM3_REPO=~/lfm3-android`).

Do not use any phone or adb, do not start emulators, do not run Gradle builds that write into `~/mobile-agent`
(you may build inside `REVIEW_DIR` if you need to; it is a disposable worktree), no network writes.

## Questions to attack (non-exhaustive)

1. **Parity claim** ("LiteRT 0.800 = GGUF 0.800, 8 vs 8"): recompute from `results/parity/` with the repo's scorer.
   Is the sample fair (selection, terminal rows excluded, high/low mix)? Is the comparison like for like (same prompt,
   same image, same decoding)? Is n = 180 enough to say "same accuracy"; what does it actually rule out? Any leakage
   or bias in `build_set.py`?
2. **Vision claim** (cosine ≥ 0.9999999 at 1280 × 576): is the check meaningful (does it test what the phone runs:
   runtime resize, u8 input, GPU precision)? Correctness of the H × W patch in `convert/lfm3vl/lfm3_vision.py`
   (merge order, rope tables, reshape for non-square grids) against the original model code in the research repo's
   model folder (`~/lfm3-android/models/hf_local/LFM3-VL-2B-AndroidAgent-v4/modeling_lfm3_vl.py`).
3. **Speed claims** (2.46 s per call; "28–39 s" llama.cpp baseline; "11–16× faster" if implied): are they like for
   like (same phone model, image tokens, what is included/excluded, thermal state, n)? Check the bench JSONs.
4. **Engine reuse claim** (LiteRT-LM issue #3165 "negligible", 29/30): is 30 rows of single-turn prompts enough, and
   does the app's real usage (many steps, growing history) match what was tested?
5. **App correctness vs the benchmark harness**: the protocol port (`android/app/src/main/kotlin/.../protocol/`),
   coordinate mapping, image resize, history building, parse/retry logic, step-end logic, action execution
   (`agent/AgentController.kt`), compared with `train/agent_format.py` and `eval/androidworld/lfm_agent.py`. Are the
   golden tests strong enough to catch real divergences?
6. **Safety and permissions**: the accessibility service, overlay, foreground service, broadcast hooks (who can send
   them?), the guard's coverage (what risky actions slip through?), anything that could act without the user's intent,
   data written to shared storage, exported components, manifest issues.
7. **Docs**: anything stated as fact that the results do not support, missing caveats, stale numbers, contradictions
   between README files.
8. **Repo hygiene**: secrets, personal data, absolute paths, licensing of copied code, reproducibility of the pinned
   setup.

## Report format (write to `REPORT_PATH`)

1. First line: `VERDICT: <SHIP | SHIP WITH FIXES | DO NOT SHIP> — <one sentence>`
2. Findings table: `| # | Severity (critical/high/medium/low) | Area | Finding | Evidence (file:line or command + output) | Suggested fix |`
3. Claims checked and confirmed (short table), so we know what you verified rather than skipped.
4. Anything you could not check and why.

Be specific and concise. Every finding needs evidence you actually observed; mark anything inferred as inferred.
