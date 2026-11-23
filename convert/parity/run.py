#!/usr/bin/env python3
"""Run one runtime on the parity set (convert/parity/build_set.py) and write the raw replies.

  gguf   : llama-server (llama.cpp cd959bae9, the evaluation build) already serving v4 Q4_K_M + mmproj Q8_0 with the
           evaluation flags (-c 8192 --image-max-tokens 1024 --reasoning-format deepseek); request exactly as
           eval/androidworld/lfm_agent.py (system + user[image PNG, text], temperature 0, max_tokens 512,
           enable_thinking true).
  litert : LiteRT-LM Python API (litert-lm 0.19.1) on a .litertlm file; system message + user[image, text], thinking on,
           greedy (no sampler in the file), max 512 output tokens. --engine_per_row 1 creates a new Engine for every
           row (LiteRT-LM issue #3165: a later Conversation on a shared Engine may start from the previous state).

  python run.py --set SET --runtime gguf --url http://127.0.0.1:8131 --out OUT.jsonl
  python run.py --set SET --runtime litert --model FILE.litertlm --backend gpu --out OUT.jsonl [--engine_per_row 1]
Each output row: id, content (answer), reasoning (thought), seconds, and the runtime's own numbers when it gives them.
A re-run skips rows already in OUT.
"""
import argparse
import base64
import json
import os
import time


def load_rows(set_dir, limit):
  rows = [json.loads(l) for l in open(os.path.join(set_dir, "rows.jsonl"))]
  return rows[:limit] if limit else rows


def run_gguf(a, rows, done, fo):
  import requests
  for r in rows:
    if r["id"] in done:
      continue
    png = open(os.path.join(a.set, r["image"]), "rb").read()
    payload = {"model": "local", "temperature": 0.0, "max_tokens": 512,
               "chat_template_kwargs": {"enable_thinking": True},
               "messages": [{"role": "system", "content": r["system"]},
                            {"role": "user", "content": [
                              {"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(png).decode()}},
                              {"type": "text", "text": r["user"]}]}]}
    t0 = time.time()
    out = requests.post(f"{a.url}/v1/chat/completions", json=payload, timeout=600).json()
    msg = out["choices"][0]["message"]
    fo.write(json.dumps({"id": r["id"], "content": msg.get("content") or "", "reasoning": msg.get("reasoning_content") or "",
                         "seconds": round(time.time() - t0, 3), "usage": out.get("usage"), "timings": out.get("timings")}) + "\n")
    fo.flush()


def run_litert(a, rows, done, fo):
  import litert_lm
  backend = litert_lm.Backend.GPU() if a.backend == "gpu" else litert_lm.Backend.CPU()
  vbackend = litert_lm.Backend.GPU() if a.vision_backend == "gpu" else litert_lm.Backend.CPU()

  def new_engine():
    t0 = time.time()
    kw = dict(backend=backend, cache_dir=a.cache_dir)
    try:
      e = litert_lm.Engine(a.model, vision_backend=vbackend, **kw)
    except TypeError:
      e = litert_lm.Engine(a.model, **kw)
    e.__enter__()
    return e, time.time() - t0

  engine, load_s = (None, 0.0) if a.engine_per_row else new_engine()
  print("engine load s", round(load_s, 2), flush=True)
  for r in rows:
    if r["id"] in done:
      continue
    if a.engine_per_row:
      engine, load_s = new_engine()
    img = os.path.abspath(os.path.join(a.set, r["image"]))
    t0 = time.time()
    conv = engine.create_conversation(messages=[{"role": "system", "content": r["system"]}],
                                      extra_context={"enable_thinking": True})
    reply = conv.send_message({"role": "user", "content": [{"type": "image", "path": img}, {"type": "text", "text": r["user"]}]})
    dt = time.time() - t0
    text = "".join(p.get("text", "") for p in reply.get("content", []) if p.get("type") == "text")
    thought = (reply.get("channels") or {}).get("thought", "")
    fo.write(json.dumps({"id": r["id"], "content": text, "reasoning": thought, "seconds": round(dt, 3),
                         "engine_load_s": round(load_s, 3) if a.engine_per_row else None}) + "\n")
    fo.flush()
    del conv
    if a.engine_per_row:
      engine.__exit__(None, None, None)
      engine = None


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument("--set", required=True)
  ap.add_argument("--runtime", choices=("gguf", "litert"), required=True)
  ap.add_argument("--out", required=True)
  ap.add_argument("--url", default="http://127.0.0.1:8131")
  ap.add_argument("--model", default="")
  ap.add_argument("--backend", default="gpu")
  ap.add_argument("--vision_backend", default="gpu")
  ap.add_argument("--engine_per_row", type=int, default=0)
  ap.add_argument("--cache_dir", default=":nocache")
  ap.add_argument("--limit", type=int, default=0)
  a = ap.parse_args()
  rows = load_rows(a.set, a.limit)
  done = set()
  if os.path.exists(a.out):
    done = {json.loads(l)["id"] for l in open(a.out) if l.strip()}
  with open(a.out, "a") as fo:
    (run_gguf if a.runtime == "gguf" else run_litert)(a, rows, done, fo)
  print("RUN_DONE", a.runtime, a.out, flush=True)


if __name__ == "__main__":
  main()
