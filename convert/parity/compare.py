#!/usr/bin/env python3
"""Score runtime outputs on the parity set and compare them with each other.

  python compare.py --set SET REF.jsonl OTHER.jsonl [MORE.jsonl ...]
For every file: parse rate, action-type match and step accuracy against AndroidControl's ground truth (the offline
benchmark's rule, eval/androidcontrol/score.py match: click within the gt box or 0.14 of the screen, text F1 >= 0.5),
split by high / low level. For every file after the first: agreement with the first (same action type; for point
actions the distance on the 0-1000 grid; same text / direction / app), and a paired count of steps right in one and
wrong in the other. The first file is the reference (the GGUF all published numbers came from).
"""
import argparse
import json
import math
import os
import sys

RESEARCH = os.environ.get("LFM3_REPO", os.path.expanduser("~/lfm3-android"))  # the research repo
sys.path.insert(0, os.path.join(RESEARCH, "train"))
sys.path.insert(0, os.path.join(RESEARCH, "eval/androidcontrol"))
import agent_format as af  # noqa: E402
from score import match  # noqa: E402


def load(p):
  return {json.loads(l)["id"]: json.loads(l) for l in open(p) if l.strip()}


def same_action(a, b):
  if a is None or b is None:
    return a is None and b is None, None
  if a["action_type"] != b["action_type"]:
    return False, None
  t = a["action_type"]
  if t in af.POINT_TYPES:
    d = math.hypot(a["point_2d"][0] - b["point_2d"][0], a["point_2d"][1] - b["point_2d"][1])
    return d <= 20, d
  keys = {"input_text": "text", "answer": "text", "scroll": "direction", "open_app": "app_name", "status": "goal_status"}
  k = keys.get(t)
  return (a.get(k) == b.get(k)) if k else True, None


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--set", required=True)
  ap.add_argument("files", nargs="+")
  a = ap.parse_args()
  rows = {json.loads(l)["id"]: json.loads(l) for l in open(os.path.join(a.set, "rows.jsonl"))}
  outs = [load(f) for f in a.files]
  ids = [i for i in rows if all(i in o for o in outs)]
  report = {"n_common": len(ids), "files": {}}
  preds = []
  for f, o in zip(a.files, outs):
    res = {"all": [0, 0, 0, 0], "high": [0, 0, 0, 0], "low": [0, 0, 0, 0]}  # n, parse, type, step
    pr, secs = {}, []
    for i in ids:
      r = rows[i]
      bbox = r.get("gt_bbox")
      bbox = json.loads(bbox) if isinstance(bbox, str) and bbox else (bbox or None)
      p = af.parse_action(o[i]["content"])
      pr[i] = p
      t_ok, s_ok = match(p, r["gt_action"], bbox)
      for k in ("all", r["mode"]):
        res[k][0] += 1; res[k][1] += p is not None; res[k][2] += t_ok; res[k][3] += s_ok
      secs.append(o[i].get("seconds") or 0)
    preds.append(pr)
    secs.sort()
    report["files"][os.path.basename(f)] = {
      k: {"n": v[0], "parse": round(v[1] / max(1, v[0]), 3), "type": round(v[2] / max(1, v[0]), 3),
          "step_acc": round(v[3] / max(1, v[0]), 3)} for k, v in res.items()}
    report["files"][os.path.basename(f)]["median_s"] = secs[len(secs) // 2] if secs else None
  ref = preds[0]
  for f, pr, o in zip(a.files[1:], preds[1:], outs[1:]):
    same = dists = 0
    dl, only_ref, only_new, intent_same = [], 0, 0, 0
    for i in ids:
      s, d = same_action(ref[i], pr[i])
      same += s
      if d is not None:
        dl.append(d)
      r = rows[i]
      bbox = r.get("gt_bbox")
      bbox = json.loads(bbox) if isinstance(bbox, str) and bbox else (bbox or None)
      ok_ref = match(ref[i], r["gt_action"], bbox)[1]
      ok_new = match(pr[i], r["gt_action"], bbox)[1]
      only_ref += ok_ref and not ok_new
      only_new += ok_new and not ok_ref
      intent_same += outs[0][i].get("reasoning", "").strip() == o[i].get("reasoning", "").strip()
    dl.sort()
    report["files"][os.path.basename(f)]["vs_ref"] = {
      "same_action": round(same / max(1, len(ids)), 3), "same_intent_text": round(intent_same / max(1, len(ids)), 3),
      "point_dist_median": dl[len(dl) // 2] if dl else None, "point_dist_p90": dl[int(len(dl) * 0.9)] if dl else None,
      "right_only_in_ref": only_ref, "right_only_here": only_new}
  print(json.dumps(report, indent=1))


if __name__ == "__main__":
  main()
