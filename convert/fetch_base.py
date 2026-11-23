#!/usr/bin/env python3
"""Download the code and text files of LiquidAI/LFM3-VL-2B at the converter's pinned revision f83d2b0a into
build/base_f83/ and check each against convert/base_f83.sha256 (the weights come from the merged v4 model instead).
v4 was trained from revision 23387be; its code, tokenizer and chat template are byte-identical to f83d2b0a.

  python convert/fetch_base.py            (any Python with huggingface_hub)
"""
import hashlib
import os
import sys

from huggingface_hub import hf_hub_download

REPO, REV = "LiquidAI/LFM3-VL-2B", "f83d2b0a2296aaa277f394990a9b8b949ddf9691"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "build", "base_f83")
want = {}
for line in open(os.path.join(ROOT, "convert", "base_f83.sha256")):
  h, name = line.split()
  want[name] = h
bad = 0
for name, h in want.items():
  p = hf_hub_download(REPO, name, revision=REV, local_dir=OUT)
  got = hashlib.sha256(open(p, "rb").read()).hexdigest()
  ok = got == h
  bad += not ok
  print(("ok  " if ok else "BAD ") + name)
sys.exit(1 if bad else 0)
