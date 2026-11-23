# Response to the re-check (review 2 @ 2d4800d)

The report is in `review2_recheck.md`. Verdict: SHIP WITH FIXES. Of the review-1 findings, 12 are resolved and 5 partly.

| # | Finding (short) | What was done |
|---|---|---|
| N1 | Guard gaps: "Enter sends" by package name; confirm dialogs | **Open**, listed in the README. Next: use the field's IME send action, and read the dialog's text |
| N2 | Send fix falls back below Android 13 | **Open**, listed. Needs an Android 12 test |
| N3 | Debug install has `EXEC` without the guard | The hook comment is corrected. The README now says the debug APK is a test build and gives the release APK for real use. Running `EXEC` through the guard is open |
| N4 | ±4.4 belongs to one pair; int8 not run on terminal rows | Each pair's interval against bf16 is in the docs and README. int8 on the 60 terminal rows: 0.683 (GGUF 0.650, int4 0.667) |
| N5 | "Misses are the model's, not runtime errors" not shown | Reworded to "consistent with the model's known weaknesses", noting what is not shown. Task-file header fixed (int8 on b3ba6e19) |
| N6 | "int8 not slower" confounded | Phrase dropped. The docs state the heat confound |
| N7 | "One file for every phone" on one phone model | Now reads "by design … tested on one phone model" |
| N8 | bf16 run is not exactly the published setup | Docstring and docs list the differences (MPS, batch 1, 512 vs 256 tokens) |
| N9 | Committed `__pycache__` and `logs/` | Removed and gitignored |
| N10 | Re-check starts after the model call | **Open**, listed. Next: compare a thumbnail taken at the screenshot with one taken just before acting |
