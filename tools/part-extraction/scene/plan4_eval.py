"""Planner v4 end to end: split_image.py's plan of each held-out model's whole close-up (plan4_data.py export) against
the layers it was drawn with. A true layer is found when one planned layer covers it with IoU above 0.5.

  python plan4_eval.py DATA CHECKPOINT [--grain unit] [--out OUT.jsonl]

Prints and appends one line: layers planned (all, and those scored: not mostly on pixels the truth leaves out) and true, precision and recall overall and by true layer size, and recall
by tag. The close-up's own background is the silhouette, as an illustration's alpha would be. With --v5 also: covered
true layers found by a planned whole shape, the planned whole shapes' fit, and what the planned covered layers are (a
covered true layer, a shown one planned twice, or nothing).
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
from plan4_data import load_amodal  # noqa: E402

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
    ap.add_argument("--keep-score", type=float, default=None, help="v5: plan5.KEEP_SCORE")
    ap.add_argument("--keep-pixels", type=int, default=None, help="v5: plan5.KEEP_PIXELS")
    ap.add_argument("--overlap", type=float, default=None, help="v5: plan5.OVERLAP")
    ap.add_argument("--hidden", type=float, default=None, help="v5: plan5.HIDDEN")
    ap.add_argument("--fine-all", action="store_true", help="v5: plan5.FINE_ALL")
    ap.add_argument("--covered-score", type=float, default=None, help="v5.1: plan5.COVERED_SCORE")
    ap.add_argument("--score", type=float, default=None, help="v5: plan5.SCORE")
    ap.add_argument("--finest", action="store_true", help="v5: plan5.FINEST")
    ap.add_argument("--split-tags", type=float, default=None, help="v5: plan5.SPLIT_TAGS")
    ap.add_argument("--v5", action="store_true", help="CHECKPOINT is a planner v5 (train_planner5.py), planned by plan5.py")
    a = ap.parse_args()
    data = Path(a.data)
    stems = sorted(str(p.with_suffix("")) for p in data.glob("*_whole_*.json"))
    stems = [s for s in stems if json.loads(Path(s + ".json").read_text(encoding="utf-8"))["held"]]
    if a.absorb is not None:
        si.p4.ABSORB = a.absorb
    if a.merge is not None:
        si.p4.MERGE = a.merge
    if a.v5:
        import plan5
        if a.keep_score is not None:
            plan5.KEEP_SCORE = a.keep_score
        if a.keep_pixels is not None:
            plan5.KEEP_PIXELS = a.keep_pixels
        if a.overlap is not None:
            plan5.OVERLAP = a.overlap
        if a.hidden is not None:
            plan5.HIDDEN = a.hidden
        plan5.FINE_ALL = plan5.FINE_ALL or a.fine_all
        if a.covered_score is not None:
            plan5.COVERED_SCORE = a.covered_score
        if a.score is not None:
            plan5.SCORE = a.score
        plan5.FINEST = plan5.FINEST or a.finest
        if a.split_tags is not None:
            plan5.SPLIT_TAGS = a.split_tags
        run = plan5.Planner5Run(Path(a.checkpoint))
    else:
        run = si.Planner4Run(Path(a.checkpoint))
    classes = run.classes
    found, planned, true, matched, everything = 0, 0, 0, 0, 0
    kinds = defaultdict(int)
    hidden_kinds = defaultdict(int)  # planned covered layers by what their whole shape is
    covered, fits = [0, 0], []
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
        if a.v5:
            leaves, _ = plan5.plan_layers(image, silhouette, run, a.grain, 400)
        else:
            leaves, _ = si.plan_layers(image, silhouette, run, a.grain, 400, tags, eyes=not a.no_eyes, join=not a.no_join)
        # A planned layer mostly on pixels left out of the truth (layers too small at this size, fragments) is not
        # scored: it may well be right.
        ignored = inst == 65535
        masks = [leaf["mask"] for leaf in leaves]
        planned += sum(1 for m in masks if (m & ignored).sum() * 2 < m.sum())
        everything += len(masks)
        hit = set()
        # Whole shapes (plan4_data.py export --amodal): covered layers found by a planned whole shape, and how well the
        # planned whole shapes of found layers fit.
        amodal = Path(stem + "_amodal.npz")
        if amodal.exists() and any("whole" in leaf for leaf in leaves):
            half = inst.shape[0] // 2
            truth = load_amodal(amodal, (half, half))
            shrink = lambda m: np.asarray(Image.fromarray(m.astype(np.uint8) * 255).resize((half, half), Image.NEAREST)) > 127
            planned_whole = [shrink(leaf["whole"]) for leaf in leaves]
            for k, info in enumerate(meta["instances"]):
                if info.get("shown", True):
                    continue
                covered[1] += 1
                ious = [(w & truth[k]).sum() / max(1, (w | truth[k]).sum()) for w in planned_whole]
                covered[0] += bool(ious) and max(ious) > 0.5
            # Planned covered layers: whose whole shape is a covered true layer's, a shown one's (planned twice), or none.
            for leaf, w in zip(leaves, planned_whole):
                if not leaf.get("covered"):
                    continue
                best = max(range(len(truth)), key=lambda k: (w & truth[k]).sum() / max(1, (w | truth[k]).sum()), default=None)
                fit = best is not None and (w & truth[best]).sum() / max(1, (w | truth[best]).sum()) > 0.5
                hidden_kinds["none" if not fit else "shown" if meta["instances"][best].get("shown", True) else "covered"] += 1
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
                    if amodal.exists() and "whole" in leaves[j]:
                        w = planned_whole[j]
                        fits.append((w & truth[k - 1]).sum() / max(1, (w | truth[k - 1]).sum()))
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
    line = {"checkpoint": a.checkpoint, "grain": a.grain, "v5": a.v5, **({"keep_score": plan5.KEEP_SCORE, "keep_pixels": plan5.KEEP_PIXELS, "overlap": plan5.OVERLAP, "hidden": plan5.HIDDEN, "fine_all": plan5.FINE_ALL, "covered_score": plan5.COVERED_SCORE, "score": plan5.SCORE, "finest": plan5.FINEST, "split_tags": plan5.SPLIT_TAGS} if a.v5 else {}), "absorb": si.p4.ABSORB, "merge": si.p4.MERGE, "eyes": not a.no_eyes, "join": not a.no_join, "whole": a.whole, "pictures": len(stems), "layers": everything, "planned": planned, "true": true,
            "precision": round(matched / max(planned, 1), 3), "recall": round(found / max(true, 1), 3), "unmatched": dict(kinds),
            "covered_recall": [covered[1], round(covered[0] / max(covered[1], 1), 3)], "covered_planned": dict(hidden_kinds), "whole_iou": round(float(np.mean(fits)), 3) if fits else None,
            "recall_by_size": {k: [v[0], round(v[1] / v[0], 3)] for k, v in sorted(by_size.items(), key=lambda kv: int(kv[0].split("-")[0]))},
            "recall_by_tag": {k: [v[0], round(v[1] / v[0], 3)] for k, v in sorted(by_tag.items(), key=lambda kv: -kv[1][0])}}
    print(json.dumps(line))
    if a.out:
        with open(a.out, "a", encoding="utf-8") as f:
            print(json.dumps(line), file=f)


if __name__ == "__main__":
    main()
