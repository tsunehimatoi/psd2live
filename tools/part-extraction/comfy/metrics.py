"""Scores part extraction on the held-out test set: shape, placement, colour and completion of each part.

usage: python metrics.py LORA OUT_DIR TEST_DIR [MODE ...]
  MODE is distilled (4 steps, the default), base (24 steps, cfg 4) or none (no LoRA, distilled)
  TEST_DIR holds <stem>_input.png with <stem>_<part>.png truths on flat gray (make_pairs' test folder)

Masks come from the flat gray background (pixels away from it belong to the part); they only score, nothing here
touches the images a pipeline would use. Per part and mode: IoU of the masks, the offset between their centroids
(pixels and fraction of the frame), mean colour error inside the true part, the IoU over the true part's hidden
area (where the input does not show it), which is the completion the model had to invent, and the residue: the share
of the true part the prediction draws much darker, left over from layers drawn over it.
"""
import json
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
import l2d
from env import client
from evaluate import PROMPTS

GRAY = 128
MODES = {"distilled": dict(steps=4, cfg=1.0, unet=None, lora=True), "base": dict(steps=24, cfg=4.0, unet="base", lora=True),
         "none": dict(steps=4, cfg=1.0, unet=None, lora=False)}


def mask(rgb: np.ndarray, tolerance: int = 18) -> np.ndarray:
    """Pixels away from the flat gray background."""
    return np.abs(rgb.astype(np.int16) - GRAY).max(axis=2) > tolerance


def score(pred: np.ndarray, truth: np.ndarray, visible: np.ndarray) -> dict:
    p, t = mask(pred), mask(truth)
    union = (p | t).sum()
    iou = float((p & t).sum() / union) if union else 1.0
    hidden = t & ~visible
    hidden_iou = float((p & hidden).sum() / (p & hidden | hidden).sum()) if hidden.sum() > 20 else None
    if p.sum() and t.sum():
        py, px = np.argwhere(p).mean(axis=0)
        ty, tx = np.argwhere(t).mean(axis=0)
        offset = float(np.hypot(py - ty, px - tx))
    else:
        offset = None
    colour = float(np.abs(pred[t].astype(np.int16) - truth[t].astype(np.int16)).mean()) if t.sum() else None
    # Residue: share of the true part where the prediction is much darker than the truth, which is what is left of
    # other layers drawn over it (eyes, brows and a mouth on a bare face, a strap's line on a sleeve).
    lum = lambda a: a.astype(np.float32) @ np.array([0.299, 0.587, 0.114], np.float32)
    residue = float(((lum(truth) - lum(pred)) > 60)[t].mean()) if t.sum() else None
    return {"iou": iou, "hidden_iou": hidden_iou, "offset_px": offset,
            "offset_frac": offset / pred.shape[0] if offset is not None else None, "colour_err": colour,
            "residue": residue}


def visible_part(inp: np.ndarray, truth: np.ndarray) -> np.ndarray:
    """Where the input shows the part as the truth draws it (close colours): the rest of the truth was hidden."""
    return np.abs(inp.astype(np.int16) - truth.astype(np.int16)).max(axis=2) < 24


def main():
    lora, out, test = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    modes = sys.argv[4:] or ["distilled"]
    out.mkdir(parents=True, exist_ok=True)
    c = client(timeout=900)
    base_unet = os.environ.get("L2D_BASE", "flux-2-klein-base-4b-fp8.safetensors")
    rows = []
    for inp_path in sorted(test.glob("*_input.png")):
        stem = inp_path.name[:-len("_input.png")]
        if not stem.endswith("_0") and not stem.endswith("_b0"):
            continue  # one framing per eye keeps the run short; the others vary only pose and crop
        inp = np.asarray(Image.open(inp_path).convert("RGB"))
        h, w = inp.shape[:2]
        name = c.upload(inp_path, f"l2d_metric_{inp_path.name}")
        for part, prompt in PROMPTS.items():
            truth_path = test / f"{stem}_{part}.png"
            if not truth_path.exists():
                continue
            truth = np.asarray(Image.open(truth_path).convert("RGB"))
            for mode in modes:
                m = MODES[mode]
                files = c.run(l2d.edit_graph(name, prompt, w, h, lora=lora if m["lora"] else None, steps=m["steps"], cfg=m["cfg"],
                                             unet=base_unet if m["unet"] else l2d.UNET, name=f"metric_{mode}_{stem}_{part}"),
                              out, f"{mode}_{stem}_{part}")
                pred = np.asarray(Image.open(files[0]).convert("RGB").resize((w, h)))
                row = {"stem": stem, "part": part, "mode": mode, "size": w, **score(pred, truth, visible_part(inp, truth))}
                rows.append(row)
                print(json.dumps(row), flush=True)
    (out / "metrics.jsonl").write_text("\n".join(json.dumps(r) for r in rows), encoding="utf-8")
    summary = {}
    for r in rows:
        summary.setdefault((r["mode"], r["part"]), []).append(r)
    print(f"\n{'mode':10}{'part':7}{'n':>4}{'IoU':>7}{'hidden':>8}{'offset px':>11}{'colour':>8}")
    for (mode, part), rs in sorted(summary.items()):
        avg = lambda k: np.mean([r[k] for r in rs if r[k] is not None]) if any(r[k] is not None for r in rs) else float("nan")
        print(f"{mode:10}{part:7}{len(rs):4}{avg('iou'):7.3f}{avg('hidden_iou'):8.3f}{avg('offset_px'):11.1f}{avg('colour_err'):8.1f}")


if __name__ == "__main__":
    main()
