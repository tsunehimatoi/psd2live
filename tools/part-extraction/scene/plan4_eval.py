"""Planner v4 end to end: split_image.py's plan of each held-out model's whole close-up (plan4_data.py export) against
the layers it was drawn with. A true layer is found when one planned layer covers it with IoU above 0.5.

  python plan4_eval.py DATA CHECKPOINT [--grain unit] [--out OUT.jsonl]

Prints and appends one line: layers planned (all, and those scored: not mostly on pixels the truth leaves out) and true, precision and recall overall and by true layer size, and recall
by tag. The close-up's own background is the silhouette, as an illustration's alpha would be.
"""
import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
import split_image as si  # noqa: E402

SIZES = (0, 1000, 4000, 16000, 10 ** 9)  # true layer pixels in the 1024 close-up


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("checkpoint")
    ap.add_argument("--grain", default="unit", choices=("part", "group", "unit"))
    ap.add_argument("--out", default="")
    ap.add_argument("--absorb", type=int, default=None, help="smallest cluster kept (train_planner4.ABSORB)")
    ap.add_argument("--merge", type=float, default=None, help="neighbour merge distance (train_planner4.MERGE)")
    ap.add_argument("--no-eyes", action="store_true", help="no close-ups of the eyes and mouth inside the head's")
    ap.add_argument("--no-join", action="store_true", help="leave layers kin big parts' borders cut in two apart")
    ap.add_argument("--whole", action="store_true", help="no big parts: the whole picture is one close-up")
    a = ap.parse_args()
    data = Path(a.data)
    stems = sorted(str(p.with_suffix("")) for p in data.glob("*_whole_*.json"))
    stems = [s for s in stems if json.loads(Path(s + ".json").read_text(encoding="utf-8"))["held"]]
    if a.absorb is not None:
        si.p4.ABSORB = a.absorb
    if a.merge is not None:
        si.p4.MERGE = a.merge
    run = si.Planner4Run(Path(a.checkpoint))
    classes = run.classes
    found, planned, true, matched, everything = 0, 0, 0, 0, 0
    kinds = defaultdict(int)
    by_size = defaultdict(lambda: [0, 0])
    by_tag = defaultdict(lambda: [0, 0])
    for stem in stems:
        meta = json.loads(Path(stem + ".json").read_text(encoding="utf-8"))
        inst = np.asarray(Image.open(stem + "_inst.png")).astype(np.int64)
        silhouette = inst > 0  # drawn pixels, fragments and edges included
        rgb = Image.open(stem + ".jpg").convert("RGB")
        image = rgb.convert("RGBA")
        image.putalpha(Image.fromarray((silhouette * 255).astype(np.uint8)))
        tags = np.where(silhouette, classes.index("topwear"), 255).astype(np.uint8) if a.whole else None
        leaves, _ = si.plan_layers(image, silhouette, run, a.grain, 400, tags, eyes=not a.no_eyes, join=not a.no_join)
        # A planned layer mostly on pixels left out of the truth (layers too small at this size, fragments) is not
        # scored: it may well be right.
        ignored = inst == 65535
        masks = [leaf["mask"] for leaf in leaves]
        planned += sum(1 for m in masks if (m & ignored).sum() * 2 < m.sum())
        everything += len(masks)
        hit = set()
        for k, info in enumerate(meta["instances"], start=1):
            t = inst == k
            n = int(t.sum())
            if n == 0:
                continue
            true += 1
            ok = False
            for j, m in enumerate(masks):
                inter = int((m & t).sum())
                if inter * 2 > n and inter / int((m | t).sum()) > 0.5:
                    ok = True
                    hit.add(j)
                    break
            found += ok
            size = next(f"{SIZES[i]}-{SIZES[i + 1]}" for i in range(len(SIZES) - 1) if SIZES[i] <= n < SIZES[i + 1])
            by_size[size][0] += 1
            by_size[size][1] += ok
            by_tag[info["tag"] or "?"][0] += 1
            by_tag[info["tag"] or "?"][1] += ok
        matched += len(hit)
        # The planned layers that found nothing: a piece of one true layer, or across several.
        for j, m in enumerate(masks):
            if j in hit or (m & ignored).sum() * 2 >= m.sum():
                continue
            ids, counts = np.unique(inst[m & (inst > 0) & ~ignored], return_counts=True)
            kinds["piece" if len(ids) and counts.max() * 4 >= 3 * counts.sum() else "across"] += 1
        print(Path(stem).name, len(masks), "planned,", sum(1 for k in range(1, len(meta["instances"]) + 1) if (inst == k).any()), "true", file=sys.stderr)
    line = {"checkpoint": a.checkpoint, "grain": a.grain, "absorb": si.p4.ABSORB, "merge": si.p4.MERGE, "eyes": not a.no_eyes, "join": not a.no_join, "whole": a.whole, "pictures": len(stems), "layers": everything, "planned": planned, "true": true,
            "precision": round(matched / max(planned, 1), 3), "recall": round(found / max(true, 1), 3), "unmatched": dict(kinds),
            "recall_by_size": {k: [v[0], round(v[1] / v[0], 3)] for k, v in sorted(by_size.items(), key=lambda kv: int(kv[0].split("-")[0]))},
            "recall_by_tag": {k: [v[0], round(v[1] / v[0], 3)] for k, v in sorted(by_tag.items(), key=lambda kv: -kv[1][0])}}
    print(json.dumps(line))
    if a.out:
        with open(a.out, "a", encoding="utf-8") as f:
            print(json.dumps(line), file=f)


if __name__ == "__main__":
    main()
