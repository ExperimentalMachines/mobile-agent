#!/usr/bin/env python3
"""hf-to-litertlm's lfm3vl_work/tools/vl_decoder.py with one more decoder form: int8 = lfm3_work/recipe_int8.json
(every weight int8 per channel, symmetric min-max: the recipe of litert-community/LFM3-1.7B-LiteRT's int8 files).
Same arguments as vl_decoder.py; the upstream file is loaded unchanged from third_party."""
import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
H2L = os.path.join(ROOT, "third_party", "hf-to-litertlm")
spec = importlib.util.spec_from_file_location("vl_decoder", os.path.join(H2L, "lfm3vl_work", "tools", "vl_decoder.py"))
vd = importlib.util.module_from_spec(spec)
sys.modules["vl_decoder"] = vd
spec.loader.exec_module(vd)
vd.RECIPES["int8"] = os.path.join(H2L, "lfm3_work", "recipe_int8.json")

if __name__ == "__main__":
  sys.exit(vd.main())
