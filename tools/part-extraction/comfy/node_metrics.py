"""Scores a general-split LoRA on held-out nodes (make_node_pairs' test folder): shape, completion and colour per tag.

usage: python node_metrics.py LORA OUT_DIR TEST_DIR [PER_TAG] [--sheet]
  LORA is relative to ComfyUI's loras folder ("none" runs the bare model). PER_TAG rest-pose nodes per tag (6).
  Runs the distilled model, 4 steps, with the close-up and the hint as references; writes metrics.jsonl, a summary
  per tag and, with --sheet, sheet.png (input, hint, prediction, truth per row).
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
import l2d
from env import client
from metrics import score, visible_part


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    lora, out, test = args[0], Path(args[1]), Path(args[2])
    per_tag = int(args[3]) if len(args) > 3 else 6
    out.mkdir(parents=True, exist_ok=True)
    rows_in = [json.loads(l) for l in (test / "prompts.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    chosen, count = [], defaultdict(int)
    for r in sorted(rows_in, key=lambda r: r["name"]):
        if r["name"].endswith("_0") and count[r["tag"]] < per_tag:
            chosen.append(r)
            count[r["tag"]] += 1
    c = client(timeout=900)
    rows, cells = [], []
    for r in chosen:
        inp_path, hint_path, truth_path = (test / f"{r['name']}_{k}.png" for k in ("input", "hint", "target"))
        inp = np.asarray(Image.open(inp_path).convert("RGB"))
        h, w = inp.shape[:2]
        a = c.upload(inp_path, f"l2d_node_{inp_path.name}")
        b = c.upload(hint_path, f"l2d_node_{hint_path.name}")
        files = c.run(l2d.edit_graph(a, r["prompt"], w, h, lora=None if lora == "none" else lora, refs=(b,),
                                     name=f"node_{r['name']}"), out, f"pred_{r['name']}")
        pred = np.asarray(Image.open(files[0]).convert("RGB").resize((w, h)))
        truth = np.asarray(Image.open(truth_path).convert("RGB"))
        row = {"name": r["name"], "tag": r["tag"], "size": w, **score(pred, truth, visible_part(inp, truth))}
        rows.append(row)
        cells.append((r, inp_path, hint_path, files[0], truth_path))
        print(json.dumps(row), flush=True)
    (out / "metrics.jsonl").write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    by_tag = defaultdict(list)
    for r in rows:
        by_tag[r["tag"]].append(r)
    avg = lambda rs, k: np.mean([r[k] for r in rs if r[k] is not None]) if any(r[k] is not None for r in rs) else float("nan")
    lines = [f"{'tag':14}{'n':>4}{'IoU':>7}{'hidden':>8}{'colour':>8}"]
    for tag, rs in sorted(by_tag.items()):
        lines.append(f"{tag:14}{len(rs):4}{avg(rs, 'iou'):7.3f}{avg(rs, 'hidden_iou'):8.3f}{avg(rs, 'colour_err'):8.1f}")
    lines.append(f"{'all':14}{len(rows):4}{avg(rows, 'iou'):7.3f}{avg(rows, 'hidden_iou'):8.3f}{avg(rows, 'colour_err'):8.1f}")
    (out / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    if "--sheet" in sys.argv:
        cell = 200
        sheet = Image.new("RGB", (4 * cell + 130, len(cells) * cell), "white")
        draw = ImageDraw.Draw(sheet)
        for i, (r, *paths) in enumerate(cells):
            draw.text((4, i * cell + cell // 2), r["tag"], fill="black")
            for j, p in enumerate(paths):
                sheet.paste(Image.open(p).convert("RGB").resize((cell, cell)), (130 + j * cell, i * cell))
        sheet.save(out / "sheet.png")


if __name__ == "__main__":
    main()
