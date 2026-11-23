#!/usr/bin/env python3
"""Vision check at the 1280 x 576 grid: the exported encoder (fp32) + adapter (fp16 weights), run as the runtime feeds
them (the PNG as float(u8) / 255, NHWC, XNNPACK on the CPU), against the merged v4 model's own image features
(Transformers, fp32, CPU: model.model.get_image_features on the processor's pixel_values; for a 576 x 1280 screenshot
the processor's smart_resize is the identity, so both sides see the same pixels). Per-token cosine over all 720 tokens.

  .venv-dec/bin/python check_vision.py --src SRC --tower TOWER --set SET [--n 6]
"""
import argparse
import json
import os

import numpy as np
import torch
from PIL import Image


def cos_stats(a, b):
  a, b = a.astype(np.float64).reshape(-1, a.shape[-1]), b.astype(np.float64).reshape(-1, b.shape[-1])
  c = (a * b).sum(-1) / (np.linalg.norm(a, axis=-1) * np.linalg.norm(b, axis=-1) + 1e-30)
  return {"cos_min": round(float(c.min()), 7), "cos_mean": round(float(c.mean()), 7),
          "rel_maxdiff": round(float(np.abs(a - b).max() / np.abs(b).max()), 6)}


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--src", required=True)
  ap.add_argument("--tower", required=True)
  ap.add_argument("--set", required=True)
  ap.add_argument("--n", type=int, default=6)
  ap.add_argument("--hw", default="1280x576")
  a = ap.parse_args()
  from ai_edge_litert.interpreter import Interpreter
  from transformers import AutoModelForImageTextToText, AutoProcessor
  rows = [json.loads(l) for l in open(os.path.join(a.set, "rows.jsonl"))][: a.n]
  proc = AutoProcessor.from_pretrained(a.src, trust_remote_code=True)
  model = AutoModelForImageTextToText.from_pretrained(a.src, dtype=torch.float32, trust_remote_code=True).eval()
  enc = Interpreter(model_path=os.path.join(a.tower, f"vision_encoder_g{a.hw}_v2.tflite"), num_threads=8)
  adp = Interpreter(model_path=os.path.join(a.tower, f"vision_adapter_g{a.hw}_v2_fp16.tflite"), num_threads=8)
  for it in (enc, adp):
    it.allocate_tensors()

  def run(it, x):
    i, o = it.get_input_details()[0], it.get_output_details()[0]
    it.set_tensor(i["index"], np.ascontiguousarray(x, dtype=i["dtype"]))
    it.invoke()
    return it.get_tensor(o["index"]).copy()

  out = []
  for r in rows:
    img = Image.open(os.path.join(a.set, r["image"])).convert("RGB")
    pi = proc.image_processor(images=[img], return_tensors="pt")
    pv = pi["pixel_values"]
    thw = pi.get("image_grid_thw")
    with torch.inference_mode():
      o = model.model.get_image_features(pv, thw, return_dict=True)
    ref = o.pooler_output[0].float().numpy()
    x = (np.asarray(img, dtype=np.float32) / 255.0)[None]
    got = run(adp, run(enc, x))
    s = cos_stats(got, ref)
    s.update({"id": r["id"], "ref_tokens": int(ref.shape[0]), "got_tokens": int(np.asarray(got).reshape(-1, ref.shape[-1]).shape[0]),
              "grid_thw": thw.tolist() if thw is not None else None})
    out.append(s)
    print("VISION", json.dumps(s), flush=True)
  worst = min(x["cos_min"] for x in out)
  print("VISION_WORST_COS", worst, "PASS" if worst >= 0.999 else "FAIL (bar 0.999)", flush=True)


if __name__ == "__main__":
  main()
