"""Scores a general-split LoRA on held-out nodes (make_node_pairs' test folder): shape, completion, colour and residue
per tag.

usage: python node_metrics.py LORA OUT_DIR TEST_DIR [PER_TAG] [--sheet] [--soft | --soften F] [--layered] [--by kind|tier]
  LORA is relative to ComfyUI's loras folder ("none" runs the bare model). PER_TAG rest-pose nodes per tag (6).
  Runs the distilled model, 4 steps, with the close-up and the hint as references; writes metrics.jsonl, a summary
  per tag and, with --sheet, sheet.png (input, hint, prediction, truth per row). --soft reads each close-up from a
  three times smaller picture (make_node_pairs' _input_soft, nodes of 512 and up), to score restored detail.
  --layered scores every held-out picture laid in front of and behind something (subnodes.py's layered nodes) and sums
  up by what the prompt asked for: the whole of it, its front or its back.
  --soften F reads each close-up from an F times smaller picture made here (JPEG-compressed for half of them), for any
  close-up still 64 pixels a side at that size. --by kind sums up by node kind (a whole tag, a named group, a single
  piece, a layered picture), --by tier by how sharp the model is drawn (low, mid, high), choosing PER_TAG nodes per tag
  of each; both read make_node_pairs.py's test/meta.jsonl or the fields in prompts.jsonl.
"""
import io
import json
import sys
import zlib
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
import l2d
from env import client
from metrics import score, visible_part


def softened(path: Path, factor: float, out: Path) -> Path:
    """The close-up at [path] as read from a [factor] times smaller picture, JPEG-compressed for half of the names."""
    img = Image.open(path).convert("RGB")
    small = img.resize((round(img.width / factor), round(img.height / factor)), Image.LANCZOS)
    if zlib.crc32(path.name.encode()) % 2:
        buf = io.BytesIO()
        small.save(buf, "JPEG", quality=75)
        small = Image.open(io.BytesIO(buf.getvalue())).convert("RGB")
    dest = out / "inputs" / path.name.replace("_input", f"_input_x{factor:g}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    small.resize(img.size, Image.BICUBIC).save(dest)
    return dest


def main():
    argv, opts = sys.argv[1:], {}
    for key in ("--soften", "--by"):
        if key in argv:
            i = argv.index(key)
            opts[key] = argv[i + 1]
            del argv[i:i + 2]
    args = [a for a in argv if not a.startswith("--")]
    lora, out, test = args[0], Path(args[1]), Path(args[2])
    per_tag = int(args[3]) if len(args) > 3 else 6
    factor = float(opts.get("--soften", 0))
    by = opts.get("--by", "tag")
    out.mkdir(parents=True, exist_ok=True)
    rows_in = [json.loads(l) for l in (test / "prompts.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    meta_path = test / "meta.jsonl"
    if meta_path.exists():
        meta = {m["name"]: m for m in map(json.loads, meta_path.read_text(encoding="utf-8").splitlines()) if m}
        rows_in = [dict(meta.get(r["name"], {}), **r) for r in rows_in]
    chosen, count = [], defaultdict(int)
    layered = "--layered" in sys.argv
    group = ((lambda r: r["name"].rsplit("_", 2)[-2]) if layered else
             (lambda r: r["tag"]) if by == "tag" else (lambda r: r.get(by, "?")))
    for r in sorted(rows_in, key=lambda r: r["name"]):
        if layered:
            if "_layered" in r["name"] and r["name"].endswith("_0"):
                chosen.append(r)
            continue
        soft_ok = "--soft" not in sys.argv or (test / f"{r['name']}_input_soft.png").exists()
        if factor and soft_ok:
            with Image.open(test / f"{r['name']}_input.png") as img:
                soft_ok = img.width / factor >= 64
        key = (r["tag"], group(r))
        if r["name"].endswith("_0") and soft_ok and count[key] < per_tag:
            chosen.append(r)
            count[key] += 1
    c = client(timeout=900)
    rows, cells = [], []
    for r in chosen:
        inp_path, hint_path, truth_path = (test / f"{r['name']}_{k}.png" for k in
                                           ("input_soft" if "--soft" in sys.argv else "input", "hint", "target"))
        if factor:
            inp_path = softened(inp_path, factor, out)
        inp = np.asarray(Image.open(inp_path).convert("RGB"))
        h, w = inp.shape[:2]
        a = c.upload(inp_path, f"l2d_node_{inp_path.name}")
        b = c.upload(hint_path, f"l2d_node_{hint_path.name}")
        files = c.run(l2d.edit_graph(a, r["prompt"], w, h, lora=None if lora == "none" else lora, refs=(b,),
                                     name=f"node_{r['name']}"), out, f"pred_{r['name']}")
        pred = np.asarray(Image.open(files[0]).convert("RGB").resize((w, h)))
        truth = np.asarray(Image.open(truth_path).convert("RGB"))
        sharp = np.asarray(Image.open(test / f"{r['name']}_input.png").convert("RGB"))  # what shows, from the sharp close-up
        row = {"name": r["name"], "tag": r["tag"], "group": group(r), "size": w, **score(pred, truth, visible_part(sharp, truth))}
        rows.append(row)
        cells.append((r, inp_path, hint_path, files[0], truth_path))
        print(json.dumps(row), flush=True)
    (out / "metrics.jsonl").write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    by_tag = defaultdict(list)
    for r in rows:
        by_tag[r["group"]].append(r)
    avg = lambda rs, k: np.mean([r[k] for r in rs if r[k] is not None]) if any(r[k] is not None for r in rs) else float("nan")
    lines = [f"{by if not layered else 'asked for':14}{'n':>4}{'IoU':>7}{'hidden':>8}{'colour':>8}{'residue':>9}"]
    line = lambda name, rs: (f"{name:14}{len(rs):4}{avg(rs, 'iou'):7.3f}{avg(rs, 'hidden_iou'):8.3f}"
                             f"{avg(rs, 'colour_err'):8.1f}{avg(rs, 'residue'):9.3f}")
    for tag, rs in sorted(by_tag.items()):
        lines.append(line(tag, rs))
    lines.append(line("all", rows))
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
