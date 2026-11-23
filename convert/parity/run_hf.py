#!/usr/bin/env python3
"""The unquantized reference on the parity set: the merged v4 model in Transformers (bf16) with the same prompt rules
as the published AndroidControl run (eval/androidcontrol/generate.py: processor chat template, enable_thinking=True,
greedy). Differences from that run: Apple MPS instead of CUDA, one row at a time instead of batches of 32, and at most
512 new tokens instead of 256 (replies here are 20-30 tokens).

  .venv-dec/bin/python run_hf.py --set SET --model MODEL_DIR --out OUT.jsonl [--device mps] [--dtype bf16]
Output rows as run.py: id, content (after </think>), reasoning (inside <think>), seconds. A re-run skips done rows.
"""
import argparse
import json
import os
import re
import time

import torch
from PIL import Image
from transformers import AutoModelForImageTextToText, AutoProcessor

THINK = re.compile(r"<think>(.*?)</think>", re.S)


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--set", required=True)
  ap.add_argument("--model", required=True)
  ap.add_argument("--out", required=True)
  ap.add_argument("--device", default="mps")
  ap.add_argument("--dtype", default="bf16", choices=("bf16", "fp32"))
  ap.add_argument("--max_new_tokens", type=int, default=512)
  ap.add_argument("--limit", type=int, default=0)
  a = ap.parse_args()
  rows = [json.loads(l) for l in open(os.path.join(a.set, "rows.jsonl"))]
  rows = rows[: a.limit] if a.limit else rows
  done = {json.loads(l)["id"] for l in open(a.out)} if os.path.exists(a.out) else set()
  dt = torch.bfloat16 if a.dtype == "bf16" else torch.float32
  proc = AutoProcessor.from_pretrained(a.model, trust_remote_code=True,
                                       size={"shortest_edge": 256 * 256, "longest_edge": 880 * 880})
  model = AutoModelForImageTextToText.from_pretrained(a.model, dtype=dt, trust_remote_code=True).to(a.device).eval()
  eos = proc.tokenizer.convert_tokens_to_ids("<|im_end|>")
  with open(a.out, "a") as fo:
    for r in rows:
      if r["id"] in done:
        continue
      img = Image.open(os.path.join(a.set, r["image"])).convert("RGB")
      msgs = [{"role": "system", "content": [{"type": "text", "text": r["system"]}]},
              {"role": "user", "content": [{"type": "image", "image": img}, {"type": "text", "text": r["user"]}]}]
      inp = proc.apply_chat_template(msgs, add_generation_prompt=True, tokenize=True, return_dict=True,
                                     return_tensors="pt", enable_thinking=True).to(a.device)
      t0 = time.time()
      with torch.inference_mode():
        out = model.generate(**inp, max_new_tokens=a.max_new_tokens, do_sample=False, eos_token_id=eos)
      text = proc.tokenizer.decode(out[0, inp["input_ids"].shape[1]:], skip_special_tokens=False)
      text = text.replace("<|im_end|>", "").strip()
      # the generation prompt ends with "<think>" (enable_thinking), so the reply is "<intent></think><action>"
      m = THINK.search(text)
      if m:
        reasoning, content = m.group(1).strip(), text[m.end():].strip()
      elif "</think>" in text:
        reasoning, content = (x.strip() for x in text.split("</think>", 1))
      else:
        reasoning, content = "", text.strip()
      fo.write(json.dumps({"id": r["id"], "content": content, "reasoning": reasoning, "raw": text,
                           "seconds": round(time.time() - t0, 3), "prompt_tokens": int(inp["input_ids"].shape[1])}) + "\n")
      fo.flush()
  print("RUN_HF_DONE", a.out, flush=True)


if __name__ == "__main__":
  main()
