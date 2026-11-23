#!/usr/bin/env python3
"""Fixed AndroidControl test sample for the runtime parity check (same rows for every runtime).

Rows come from experimentalmachines/MobileAgent-SFT-v1 (data/test = AndroidControl official test episodes), with the
v4 prompt exactly as the offline benchmark built it (eval/androidcontrol/generate.py v4_msgs: SYSTEM_PROMPT_V4 + the
episode's frozen installed-app list from data/v4work/app_pool.json). The screenshot is resized as the model saw it
in training and evaluation (agent_format.resize_screenshot, max 880*880 px -> 576 x 1280 for 20:9 screens).

  python build_set.py --out SET_DIR [--n_high 120 --n_low 60 --seed 0]
  python build_set.py --out SET_DIR --terminal 60   # terminal rows only (the episode's last row: status complete)
Writes SET_DIR/rows.jsonl (id, mode, episode_id, step_idx, system, user, gt_action, gt_bbox, image file, image size)
and SET_DIR/img/<id>.png.
"""
import argparse
import glob
import hashlib
import io
import json
import os
import sys

import pandas as pd
from huggingface_hub import snapshot_download
from PIL import Image

REVISION = "f88a843239b7e0b68ea8fcb6eb0c5539276d64eb"  # experimentalmachines/MobileAgent-SFT-v1, pinned 2026-11-23
RESEARCH = os.environ.get("LFM3_REPO", os.path.expanduser("~/lfm3-android"))  # the research repo
sys.path.insert(0, os.path.join(RESEARCH, "train"))
import agent_format as af  # noqa: E402


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--out", required=True)
  ap.add_argument("--n_high", type=int, default=120)
  ap.add_argument("--n_low", type=int, default=60)
  ap.add_argument("--seed", type=int, default=0)
  ap.add_argument("--terminal", type=int, default=0, help="build a set of N terminal rows (high-level) instead")
  ap.add_argument("--revision", default=REVISION)
  a = ap.parse_args()
  os.makedirs(os.path.join(a.out, "img"), exist_ok=True)
  snap = snapshot_download("experimentalmachines/MobileAgent-SFT-v1", repo_type="dataset", allow_patterns=["data/test/*"],
                           revision=a.revision)
  files = sorted(glob.glob(os.path.join(snap, "data/test/*.parquet")))
  meta = pd.concat([pd.read_parquet(f, columns=["episode_id", "messages", "gt_action"]) for f in files])
  pool = json.load(open(os.path.join(RESEARCH, "data/v4work/app_pool.json")))
  # one frozen app list per episode (generate.py _episode_apps)
  ep = {}
  for e, m, g in zip(meta["episode_id"], meta["messages"], meta["gt_action"]):
    g = json.loads(g) if isinstance(g, str) else (g or {})
    names = af.apps_in_text(m[1]["content"]) + ([g["app_name"]] if g.get("action_type") == "open_app" else [])
    lst = ep.setdefault(str(e), [])
    lst.extend(n for n in names if n not in lst)
  rows = []
  for f in files:
    df = pd.read_parquet(f)
    for _, r in df.iterrows():
      if bool(r["is_terminal"]) != bool(a.terminal):
        continue
      mode = r["mode"]
      if a.terminal:
        if mode != "high":
          continue
        mode = "terminal"
      key = hashlib.sha256(f"{a.seed}:{r['episode_id']}:{r['step_idx']}:{mode}".encode()).hexdigest()
      rows.append((key, mode, r))
  out_rows = []
  plan = (("terminal", a.terminal),) if a.terminal else (("high", a.n_high), ("low", a.n_low))
  for mode, n in plan:
    pick = sorted((k, r) for k, m, r in rows if m == mode)[:n]
    for k, r in pick:
      msgs = [dict(x) for x in r["messages"]]
      msgs = af.to_v4_messages(msgs, af.v4_app_list(f"androidcontrol/{r['episode_id']}", ep[str(r["episode_id"])], pool))
      imgs = r["images"]
      raw = imgs[0]["bytes"]
      img = af.resize_screenshot(Image.open(io.BytesIO(raw)))
      rid = f"{mode}_{k[:10]}"
      img.save(os.path.join(a.out, "img", rid + ".png"))
      gt = r["gt_action"]
      out_rows.append({"id": rid, "mode": mode, "episode_id": str(r["episode_id"]), "step_idx": int(r["step_idx"]),
                       "system": msgs[0]["content"], "user": msgs[1]["content"],
                       "gt_action": json.loads(gt) if isinstance(gt, str) else gt,
                       "gt_bbox": r["gt_bbox"], "orig_wh": [int(r["orig_w"]), int(r["orig_h"])],
                       "image": f"img/{rid}.png", "image_wh": list(img.size)})
  with open(os.path.join(a.out, "rows.jsonl"), "w") as fo:
    for x in out_rows:
      fo.write(json.dumps(x, default=str) + "\n")
  sizes = {}
  for x in out_rows:
    sizes[tuple(x["image_wh"])] = sizes.get(tuple(x["image_wh"]), 0) + 1
  print("SET_DONE", len(out_rows), "rows; image sizes", sizes, "columns", list(df.columns))


if __name__ == "__main__":
  main()
