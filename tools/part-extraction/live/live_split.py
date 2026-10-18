"""Hand-planned split of one illustration with the split LoRA: each Live2D layer of SPEC is generated whole from a
close-up of the picture, its hint the union of planner regions SPEC names (holes closed); candidates per LoRA and seed,
matted by BiRefNet. Nothing is pasted or cut: the layer is the generator's whole picture.

usage: python live_split.py SPEC.json OUT_DIR [LAYER_ID ...]

SPEC: {"image": "art/x.png", "plan": "plan/x", "loras": [...], "seeds": [...],
       "layers": [{"id", "name", "tags", "prompt", "regions": ["00", ...], "whole": bool, "min_side": 256,
                   "grow": 1.3, "loras"?, "seeds"?, "source"?: "art/x_open.png", "box"?: [l, t, side]}]}
  regions are plan numbers (plan.json name prefixes) or tag:<tag>[:<side on the picture>]; "whole" uses each region's whole shape where the planner gave one.
  "mask": a canvas-size hint picture drawn beforehand (star/build.py); "poly": [[[x, y], ...], ...] adds hand-drawn
  outlines to the hint. "grow_hint" grows the hint by that share of the close-up (a blob). "source" is another picture of the same canvas (an edit of the illustration) to read the close-up from.
Writes OUT_DIR/<id>/{input,hint}.png, <lora>_s<seed>{,_raw}.png and OUT_DIR/<id>/meta.json.
"""
import json
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "comfy"))
sys.path.insert(0, str(HERE.parent / "scene"))
ROOT = Path(os.environ.get("L2D_LIVE", "."))  # the work folder: art/, plan/, specs/, split/
import l2d  # noqa: E402
from env import client  # noqa: E402
from hints import filled, grown  # noqa: E402

spec = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
out = Path(sys.argv[2])
only = set(sys.argv[3:])
root = ROOT
image = Image.open(root / spec["image"]).convert("RGBA")
W, H = image.size
plan = {r["name"].split("_")[0]: r for r in json.loads((root / spec["plan"] / "plan.json").read_text(encoding="utf-8"))}
c = client(timeout=900)


def flat(path: Path) -> Image.Image:
    im = Image.open(path).convert("RGBA")
    bg = Image.new("RGBA", im.size, (235, 235, 235, 255))
    bg.alpha_composite(im)
    return bg.convert("RGB")


# The art's own look: without it some parts come out painted (glossy, realistic skin) rather than cel-shaded.
STYLE = ", flat cel-shaded anime style with clean lineart, exactly like the picture"


def score(d: Path, tag: str) -> dict:
    """Measures a candidate against its close-up (measurement only, nothing is changed): colour error where it covers
    the hint (the part as shown should keep the art's colours and look), the hint's share it covers, its share
    drawn away from the hint (a neighbour dragged in) and its share left flat gray (matting residue)."""
    cand = np.asarray(Image.open(d / f"{tag}.png").convert("RGBA")).astype(np.float32)
    inp = np.asarray(Image.open(d / "input.png").convert("RGB")).astype(np.float32)
    hint = np.asarray(Image.open(d / "hint.png").convert("L")) > 127
    near = np.asarray(grown(Image.fromarray(hint.astype(np.uint8) * 255), hint.shape[0] * 0.08, 20)) > 127
    a = cand[..., 3] > 128
    both = a & hint
    err = float(np.abs(cand[..., :3] - inp)[both].mean()) if both.any() else 99.0
    cover = float(both.sum() / max(1, hint.sum()))
    spill = float((a & ~near).sum() / max(1, a.sum()))
    gray = float((np.abs(cand[..., :3] - 128).max(axis=2) < 14)[a].mean()) if a.any() else 1.0
    return {"err": round(err, 1), "cover": round(cover, 3), "spill": round(spill, 3), "gray": round(gray, 3),
            "rank": round(err / 40 + 2 * spill + 2 * (1 - cover) + 3 * gray, 3)}


jobs = []
for layer in spec["layers"]:
    if only and layer["id"] not in only:
        continue
    d = out / layer["id"]
    d.mkdir(parents=True, exist_ok=True)
    mask = np.zeros((H, W), bool)
    picked = []
    for n in layer["regions"]:
        if n.startswith("tag:"):  # tag:<tag>[:l|r|c] - every shown region of that tag (and side on the picture)
            _, t, *sd = n.split(":")
            picked += [r for r in plan.values() if r["tag"] == t and (not sd or r["side"] == sd[0]) and not r.get("covered")]
        else:
            picked.append(plan[n])
    for r in picked:
        key = "whole" if layer.get("whole") and r.get("whole") else "region"
        mask |= np.asarray(Image.open(root / spec["plan"] / r[key])) > 127
    if layer.get("mask"):  # a hint drawn beforehand (canvas size)
        mask |= np.asarray(Image.open(root / layer["mask"]).convert("L")) > 127
    if layer.get("poly"):  # rough outlines drawn by hand (canvas points) where the planner found nothing
        from PIL import ImageDraw
        drawn = Image.new("L", (W, H), 0)
        for poly in layer["poly"]:
            ImageDraw.Draw(drawn).polygon([tuple(p) for p in poly], fill=255)
        mask |= np.asarray(drawn) > 127
    if "box" in layer:
        left, top, span = layer["box"]
    else:
        ys, xs = np.nonzero(mask)
        x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
        span = max(int(max(x1 - x0, y1 - y0) * layer.get("grow", 1.3)) + 16, layer.get("min_side", 256))
        left, top = (x0 + x1) // 2 - span // 2, (y0 + y1) // 2 - span // 2
    box = (left, top, left + span, top + span)
    size = layer.get("size") or min(1024, max(512, round(span * 3 / 64) * 64))
    src = flat(root / layer.get("source", spec["image"]))
    crop = src.crop(box)
    crop = crop.resize((size, size), Image.LANCZOS if size <= span else Image.BICUBIC)
    hint = filled(Image.fromarray((mask * 255).astype(np.uint8)).crop(box).resize((size, size), Image.NEAREST))
    if layer.get("grow_hint"):  # a share of the close-up's side: the part is bigger in the source than the plan saw it
        hint = grown(hint, size * layer["grow_hint"], 40)
    hint = hint.convert("RGB")
    crop.save(d / "input.png")
    hint.save(d / "hint.png")
    a = c.upload(d / "input.png", f"live_{out.name}_{layer['id']}_input.png")
    b = c.upload(d / "hint.png", f"live_{out.name}_{layer['id']}_hint.png")
    prompt = layer["prompt"] + layer.get("style", spec.get("style", STYLE))
    if not prompt.startswith("Live2D layer split"):
        prompt = (f"Live2D layer split: keep only {prompt}, the part marked in the second image, drawn complete including "
                  f"any part hidden behind other layers, exactly in place, on a flat gray background.")
    jobs.append((layer, d, a, b, prompt, size, (int(left), int(top), int(span))))

# One LoRA at a time over every layer: switching LoRAs is the slow part of a generation.
loras = list(dict.fromkeys(l for layer, *_ in jobs for l in layer.get("loras", spec["loras"])))
for lora in loras:
    for layer, d, a, b, prompt, size, rect in jobs:
        if lora not in layer.get("loras", spec["loras"]):
            continue
        for seed in layer.get("seeds", spec["seeds"]):
            tag = f"{lora.split('/')[-1].replace('.safetensors', '')}_s{seed}"
            if (d / f"{tag}.png").exists() and (d / "meta.json").exists() and                     json.loads((d / "meta.json").read_text(encoding="utf-8"))["prompt_full"] == prompt:
                continue
            files = c.run(l2d.edit_graph(a, prompt, size, size, lora=lora, refs=(b,), seed=seed, name=tag, matte=True), d, tag)
            for f in files:
                f.replace(d / (f"{tag}_raw.png" if "_raw_" in f.name else f"{tag}.png"))
        print(layer["id"], lora, flush=True)
for layer, d, a, b, prompt, size, rect in jobs:
    made = [f"{l.split('/')[-1].replace('.safetensors', '')}_s{s}" for l in layer.get("loras", spec["loras"]) for s in layer.get("seeds", spec["seeds"])]
    scores = {t: score(d, t) for t in made}
    made.sort(key=lambda t: scores[t]["rank"])
    (d / "meta.json").write_text(json.dumps({**layer, "prompt_full": prompt, "rect": list(rect), "size": size, "candidates": made,
                                             "scores": scores},
                                            indent=1), encoding="utf-8")
