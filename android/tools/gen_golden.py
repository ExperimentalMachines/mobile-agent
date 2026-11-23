"""Golden outputs of the research repo's train/agent_format.py (and Pillow's BICUBIC resize) for the app's unit tests.

The app's Kotlin port (app/src/main/kotlin/.../protocol/AgentFormat.kt, image/Resample.kt) must reproduce these byte for
byte. Run with the research repo's data venv (Python 3.12 + Pillow; agent_format.py needs only the standard library):

    <lfm3-android>/.venv-data/bin/python tools/gen_golden.py --train-dir <lfm3-android>/train

Writes app/src/test/resources/golden/agent_format.json, agent_loop.json and golden/resize_{in,out}[_land].png.
"""
import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE.parent / "app" / "src" / "test" / "resources" / "golden"

PARSE_CASES = [
    '{"action_type":"click","point_2d":[500,300]}',
    '<think>Open the clock</think>{"action_type":"open_app","app_name":"Clock"}',
    'Open the clock</think>\n{"action_type":"open_app","app_name":"Clock"}',
    '<think>Still thinking {"action_type":"click","point_2d":[1,2]}',
    '{"action_type":"click","point_2d":[500.7,300.2]}',
    '{"action_type":"click","point_2d":[-5,1200]}',
    '{"action":"type","text":"hello"}',
    '{"action_type":"click","x":10,"y":20}',
    '{"action_type":"click","coordinate":["12","34"]}',
    '{"action_type":"click","point":"53"}',
    '{"action_type":"click","point":"5,3"}',
    '{"action_type":"scroll","direction":"down"}}',
    '{"action_type":"scroll","direction":"sideways"}',
    '{"action_type":"status"}',
    '{"action_type":"status","goal_status":"infeasible"}',
    '{"action_type":"status","goal_status":"done"}',
    '{"action_type":"status","goal_status":null}',
    '{"action_type":"open_app","label":"Chrome"}',
    '{"action_type":"open_app","app_name":5,"name":"Maps"}',
    'Sure! {"foo": 1} then {"action_type":"navigate_back"}',
    '{"action_type":"answer","text":"It\'s 5 \\"pm\\"\\n"}',
    '{"action_type":"input_text","text":"café ☕ 日本"}',
    '{"action_type":"input_text","text":"\\u00e9\\ud83d\\ude00\\u0001"}',
    '{"action_type":"input_text","text":"a\tb"}',
    '{"action_type":"click","point_2d":[NaN,5]}',
    '{"action_type":"click","point_2d":[Infinity,5]}',
    '{"action_type":"click","point_2d":[true,5]}',
    '{"action_type":"click","point_2d":[1e2,5]}',
    '{"action_type":"click","point_2d":[1e400,5]}',
    '{"action_type":"click","point_2d":[123456789012345678901234567890,5]}',
    '{"action_type":"click","point_2d":[" 12_5 ","9"]}',
    '{"action_type":"wait","action_type":"click","point_2d":[1,2]}',
    '{"action_type":"","action":"navigate_home"}',
    '{"action_type":"keyboard_enter"} trailing words',
    '',
    'no json here',
    '{"action_type":"click","point_2d":[500,300],"extra":1}',
    ' \n{"action_type":"navigate_home"}\n ',
    '{"action_type": "click", "point_2d": [499.5, 0.5]}',
    '{"action_type":"click","point_2d":[]}',
    '{"action_type":"click","point_2d":[], "coordinate":[3,4]}',
    '{"action_type":"click","point_2d":null,"x":7,"y":8}',
    '{"action_type":"long_press","point_2d":[1000,0]}',
    '{"a":{"action_type":"wait"}}',
    '<think>x</think><think>y</think>{"action_type":"wait"}',
    '{"action_type":"click","point_2d":[5]}',
    '{"action_type":"click","point_2d":{"0":1,"1":2}}',
    '{"action_type":"input_text","text":5}',
    '{"action_type":"swipe","direction":"up"}',
    '{"action_type":"scroll","direction":"up"',
    ' {"action_type":"wait"} ',
    '{"action_type":"click","point_2d":[1,2]} {"action_type":"wait"}',
]

INTENT_CASES = [
    ["Open the Clock app.", ""],
    ["  \n Tap the search bar\nmore lines", ""],
    ["", "<think>Scroll down to find it</think>{\"action_type\":\"wait\"}"],
    ["", "no think tags"],
    ["x" * 250, ""],
    ["\U0001F600" * 210, ""],
    ["line1 line2", ""],
    ["  padded ", ""],
    ["   ", "<think> fallback </think>{}"],
    ["first\r\nsecond", ""],
    ["a\x85b", ""],
]

SIZES = [(3200, 1440), (2400, 1080), (2340, 1080), (1920, 1080), (2560, 1600), (100, 100), (200, 50), (1280, 576),
         (2992, 1344), (3120, 1440), (2412, 1080), (1600, 720), (2000, 2000), (800, 480)]

POINTS = [(500, 300, 1440, 3200), (0, 0, 1440, 3200), (1000, 1000, 1440, 3200), (499.5, 0.5, 1440, 3200),
          (250, 750, 1080, 2400), (2.5, 3.5, 1001, 1001), (0.5, 1.5, 1001, 1001), (333.3, 666.7, 1080, 2340),
          (999.9, 0.1, 1440, 3200)]


def history(af, n, empty_intent_at=(), invalid_at=()):
    acts = [
        {"action_type": "open_app", "app_name": "Clock"},
        {"action_type": "click", "point_2d": [512, 140]},
        {"action_type": "scroll", "direction": "down"},
        {"action_type": "input_text", "text": "7:30 \"am\""},
        {"action_type": "navigate_back"},
        {"action_type": "wait"},
        {"action_type": "long_press", "point_2d": [10, 990]},
    ]
    h = []
    for i in range(n):
        if i in invalid_at:
            h.append(("(invalid output)", "Sure, I will tap the button {\"act"[:80]))
            continue
        intent = "" if i in empty_intent_at else f"Step {i + 1}: do the thing → café"
        h.append((intent, af.action_to_str(acts[i % len(acts)])))
    return h


def simulate_lfm_agent(af, goal, apps, replies, max_steps):
    """The decision logic of eval/androidworld/lfm_agent.py `step()` (copied; no environment): per step up to 1 + 1
    model calls until the reply parses, the invalid-output history entry, the intent rule, `status` and `answer` ending
    the episode, and the step budget. Returns every prompt's user text, the executed actions and how it ended."""
    history, calls, executed = [], [], []
    replies = iter(replies)
    status, answer = "max_steps", None
    for _ in range(max_steps):
        resp, action = None, None
        for _ in range(1 + 1):  # 1 + max_parse_retries
            calls.append(af.user_text(goal, history, apps=apps))
            resp = next(replies)
            action = af.parse_action(resp["content"])
            if action is not None:
                break
        if action is None:
            history.append(("(invalid output)", resp["content"][:80]))
            continue
        reasoning = resp["reasoning"] or ""
        if not reasoning.strip() and "</think>" in resp["content"]:
            reasoning = resp["content"].split("</think>", 1)[0].replace("<think>", "")
        intent = reasoning.strip().splitlines()[0][:200] if reasoning.strip() else ""
        history.append((intent, af.action_to_str(action)))
        t = action["action_type"]
        if t == "status":
            status = action.get("goal_status", "complete")
            break
        executed.append(af.action_to_str(action))
        if t == "answer":
            status, answer = "answered", action["text"]
            break
    return {"calls": calls, "executed": executed, "status": status, "answer": answer, "history": [list(h) for h in history]}


def loop_episodes(af):
    apps = ["Chrome", "Clock", "Settings"]
    tap = lambda x, y: {"reasoning": f"Tap at {x},{y}", "content": '{"action_type":"click","point_2d":[%d,%d]}' % (x, y)}
    long = (
        [{"reasoning": "Open Settings\nsecond line", "content": '{"action_type":"open_app","app_name":"Settings"}'},
         {"reasoning": "", "content": "I am not sure"},                       # unparseable ...
         {"reasoning": "", "content": "<think>still thinking"},              # ... and its retry: invalid-output step
         {"reasoning": "", "content": "<think>Scroll down</think>{\"action_type\":\"scroll\",\"direction\":\"down\"}"},
         {"reasoning": "Type it", "content": '{"action_type":"input_text","text":"caf\u00e9 \\"7\\""}'},
         {"reasoning": "nonsense first", "content": "{}"},                   # retried, then parses
         {"reasoning": "Press enter", "content": '{"action_type":"keyboard_enter"}'}]
        + [tap(100 + 10 * i, 200 + 10 * i) for i in range(8)]
        + [{"reasoning": "Wait for it", "content": '{"action_type":"wait"} trailing'},
           {"reasoning": "The answer is on screen", "content": '{"action_type":"answer","text":"Android 17"}'}]
    )
    limit = [tap(500, 500), {"reasoning": "", "content": "??"}, {"reasoning": "", "content": "??"}, tap(1, 2)]
    done = [{"reasoning": "Nothing to do", "content": '{"action_type":"status","goal_status":"infeasible"}'}]
    out = []
    for name, goal, replies, max_steps in [("long_answer", "What version is this?", long, 20),
                                           ("step_limit", "Tap around", limit, 3),
                                           ("infeasible", "Fly", done, 5)]:
        sim = simulate_lfm_agent(af, goal, apps, replies, max_steps)
        out.append({"name": name, "goal": goal, "apps": apps, "max_steps": max_steps, "replies": replies, **sim})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train-dir", required=True, help="research repo's train/ directory (holds agent_format.py)")
    args = ap.parse_args()
    sys.path.insert(0, args.train_dir)
    import agent_format as af  # noqa: E402
    from PIL import Image, ImageDraw  # noqa: E402

    apps = ["Calendar", "Camera", "Chrome", "Clock", "Contacts", "Files", "Gallery", "Settings", "日本 App"]
    user_cases = []
    for goal, hist, ap_, hint in [
        ("Open the Clock app", [], apps, None),
        ("Open the Clock app", history(af, 3, empty_intent_at=(1,), invalid_at=(2,)), apps, None),
        ("Set an alarm for 7:30 am", history(af, 12, empty_intent_at=(4,)), apps, None),
        ("Open the Clock app", history(af, 2), None, None),
        ('Reply "ok" to\nAlice — café', history(af, 8), apps, None),
        ("Open the Clock app", history(af, 9), [], "tap the clock icon"),
    ]:
        user_cases.append({"goal": goal, "history": [list(x) for x in hist], "apps": ap_, "step_instruction": hint,
                           "expected": af.user_text(goal, hist, hint, apps=ap_)})
        if ap_ is not None and hint is None:
            msgs = af.build_messages(goal, hist, image=None, prompt_version="v4", apps=ap_)
            assert msgs[0]["content"][0]["text"] == af.SYSTEM_PROMPT_V4
            assert msgs[1]["content"][-1]["text"] == user_cases[-1]["expected"]

    parse = []
    for t in PARSE_CASES:
        rec = {"text": t}
        for mode in ("lenient", "strict"):
            a = af.parse_action(t, strict=(mode == "strict"))
            rec[mode] = None if a is None else af.action_to_str(a)
            if a is not None and a["action_type"] in af.POINT_TYPES:
                rec[mode + "_point"] = [float(a["point_2d"][0]), float(a["point_2d"][1])]
        parse.append(rec)

    intents = []
    for reasoning, content in INTENT_CASES:  # the intent rule of eval/androidworld/lfm_agent.py, step()
        r = reasoning or ""
        if not r.strip() and "</think>" in content:
            r = content.split("</think>", 1)[0].replace("<think>", "")
        intent = r.strip().splitlines()[0][:200] if r.strip() else ""
        intents.append({"reasoning": reasoning, "content": content, "expected": intent})

    golden = {
        "source_sha256": hashlib.sha256(Path(af.__file__).read_bytes()).hexdigest(),
        "system_v1": af.SYSTEM_PROMPT,
        "system_v4": af.SYSTEM_PROMPT_V4,
        "history_steps": af.HISTORY_STEPS,
        "user_text": user_cases,
        "parse": parse,
        "intent": intents,
        "invalid_history": [{"content": c, "expected": c[:80]} for c in ["x" * 100, "\U0001F600" * 90, "short"]],
        "smart_resize": [{"h": h, "w": w, "expected": list(af.smart_resize(h, w))} for h, w in SIZES],
        "norm_to_px": [{"x": x, "y": y, "w": w, "h": h, "expected": list(af.norm_to_px([x, y], w, h))}
                       for x, y, w, h in POINTS],
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "agent_loop.json").write_text(json.dumps(loop_episodes(af), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    (OUT / "agent_format.json").write_text(json.dumps(golden, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    # A synthetic 20:9 "screenshot" (gradients, thin lines, small text-like marks) and its BICUBIC resize, the way
    # agent_format.resize_screenshot shrinks a 1440 x 3200 screen to 576 x 1280 (here at a quarter of the size).
    w, h = 360, 800
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = ((x * 255) // (w - 1), (y * 255) // (h - 1), ((x ^ y) * 7) & 255)
    d = ImageDraw.Draw(img)
    for i in range(0, h, 37):
        d.line([(0, i), (w - 1, i)], fill=(255, 255, 255), width=1)
    for i in range(12):
        d.rectangle([20 + i * 27, 100 + i * 50, 40 + i * 27, 130 + i * 50], fill=(10, 10, 10), outline=(250, 40, 40))
        d.text((25, 400 + i * 30), f"Row {i} café 12:{i:02d}", fill=(0, 0, 0))
    img.save(OUT / "resize_in.png")
    th, tw = af.smart_resize(h * 4, w * 4)  # (1280, 576) for the full-size screen
    out = img.resize((tw // 4, th // 4), Image.BICUBIC)
    out.save(OUT / "resize_out.png")
    # Landscape: Pillow runs the horizontal pass first here (the portrait case above runs the vertical pass first).
    land = img.transpose(Image.Transpose.ROTATE_90)
    land.save(OUT / "resize_in_land.png")
    land.resize((th // 4, tw // 4), Image.BICUBIC).save(OUT / "resize_out_land.png")
    print("wrote", OUT, "parse cases", len(parse), "resize", img.size, "->", out.size)


if __name__ == "__main__":
    main()
