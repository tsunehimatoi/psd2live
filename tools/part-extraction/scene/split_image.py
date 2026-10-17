"""Splits a character illustration into Live2D-style parts: the planner tags every pixel, and each tagged region is
repainted whole (hidden parts included) by the split LoRA from a close-up of it, at the generator's top size.

  python split_image.py IMAGE OUT_DIR --planner planner.pt --lora NAME [--min 400] [--size 1024]

Needs the planner checkpoint (train_planner.py) on a local GPU and a ComfyUI with the LoRA (L2D_COMFY_URL, see
comfy/env.py). Writes OUT_DIR/layout.json, a PSD2Live Layout manifest (docs/zh/spec/LAYOUT_MANIFEST.md) that opens
the split as a Layout project: the illustration as a reference layer, then every part back to front, its picture
kept at the generator's size on the square of the canvas it covers (more pixels than canvas units, which PSD2Live
keeps as texture density), with the part and side words of the semantic tags. Each layer also carries a "split"
object (ignored by PSD2Live) with everything the split knew: the tag and side as planned, order, prompt, the close-up,
hint, region and the picture as generated before matting. OUT_DIR keeps source.png, plan.png, parts/, regions/ and
work/.

Each part is the LoRA's whole picture of it, kept at the size it was generated (--size, whatever the part's size in
the illustration: the LoRA is trained to paint at the art's sharpness from a softer close-up), its background taken
off by BiRefNet (ComfyUI node BiRefNetMatte). The close-up frames the part with its surroundings; the planner's region
is only the hint the LoRA reads. Nothing is pasted, cut or composited.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageFilter

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "comfy"))
from make_node_pairs import prompt  # noqa: E402
from train_planner import MEAN, STD, Planner  # noqa: E402

# Back to front, the order the collected models draw their parts in (split_practices.py); a fixed guess without it.
_PRACTICES = HERE.parent / "SPLIT_PRACTICES.json"
ORDER = json.loads(_PRACTICES.read_text(encoding="utf-8"))["order"] if _PRACTICES.exists() else [
    "back_hair", "tail", "wings", "body", "leg", "legwear", "footwear", "bottomwear", "arm", "hand", "handwear",
    "topwear", "neck", "neckwear", "objects", "ears", "earwear", "face", "facedetail", "nose", "mouth_inside", "mouth",
    "eyewhite", "irides", "eyelash", "eyebrow", "eyewear", "side_hair", "front_hair", "headwear"]
# The planner's tags in PSD2Live's semantic words (LayerTags.kt); the parts it has no tag of its own for take the
# nearest one, and the planner's tag always rides along as a user tag.
WORDS = {
    "back_hair": ["hair", "back"], "front_hair": ["hair", "front"], "side_hair": ["hair", "front"], "headwear": ["headwear"],
    "face": ["face"], "facedetail": ["face", "detail"], "irides": ["eye", "iris"], "eyewhite": ["eye", "white"],
    "eyelash": ["eye", "lash"], "eyebrow": ["brow"], "ears": ["ear"], "earwear": ["ear", "accessory"], "nose": ["nose"],
    "mouth": ["mouth"], "mouth_inside": ["mouth"], "eyewear": ["eyewear"], "neck": ["neck"], "neckwear": ["neck", "accessory"],
    "body": ["top"], "topwear": ["top"], "arm": ["arm"], "hand": ["arm"], "handwear": ["arm"], "bottomwear": ["bottom"],
    "leg": ["leg"], "legwear": ["leg"], "footwear": ["foot"], "tail": ["tail"], "wings": ["wings"], "objects": ["object"]}
HEAD = {"back_hair", "front_hair", "side_hair", "headwear", "face", "facedetail", "irides", "eyewhite", "eyelash", "eyebrow",
        "ears", "earwear", "nose", "mouth", "mouth_inside", "eyewear"}
PAIRED = {"irides", "eyewhite", "eyelash", "eyebrow", "ears", "earwear", "arm", "hand", "leg", "handwear", "legwear", "footwear"}


def close(mask: np.ndarray, r: int = 2) -> np.ndarray:
    """Dilate then erode a boolean mask by [r] pixels."""
    im = Image.fromarray(mask.astype(np.uint8) * 255)
    return np.asarray(im.filter(ImageFilter.MaxFilter(2 * r + 1)).filter(ImageFilter.MinFilter(2 * r + 1))) > 127


@torch.no_grad()
def plan(image: Image.Image, checkpoint: Path) -> tuple[np.ndarray, list[str]]:
    state = torch.load(checkpoint, map_location="cpu")
    classes = state["classes"]
    model = Planner(len(classes))
    model.load_state_dict(state["state"])
    model = model.cuda().eval()
    s = 1022 / max(image.size)
    w, h = max(14, int(image.width * s) // 14 * 14), max(14, int(image.height * s) // 14 * 14)
    x = torch.from_numpy(np.asarray(image.convert("RGB").resize((w, h), Image.BILINEAR)).copy()).permute(2, 0, 1).float() / 255
    with torch.autocast("cuda", dtype=torch.bfloat16):
        logits = model(((x - MEAN) / STD)[None].cuda())
    probs = torch.softmax(logits.float(), 1)[0]
    conf, tags = probs.max(0)
    tags = tags.cpu().numpy().astype(np.uint8)
    tags[conf.cpu().numpy() < 0.4] = 255
    return np.asarray(Image.fromarray(tags).resize(image.size, Image.NEAREST)), classes


def nodes(tags: np.ndarray, classes: list[str], min_pixels: int):
    """Connected regions of each tag; paired tags split at the face's centre, small specks dropped."""
    face = classes.index("face") if "face" in classes else None
    ys, xs = np.nonzero(tags == face) if face is not None else ([], [])
    centre = xs.mean() if len(xs) else tags.shape[1] / 2
    out = []
    for c, name in enumerate(classes):
        mask = tags == c
        if mask.sum() < min_pixels:
            continue
        mask = close(mask)
        if name in PAIRED:
            left = mask.copy(); left[:, int(centre):] = False
            right = mask.copy(); right[:, :int(centre)] = False
            parts = [("l", left), ("r", right)]
        else:
            parts = [("c", mask)]
        for side, m in parts:
            if m.sum() >= min_pixels:
                out.append((name, side, m))
    return out


def words(tag: str) -> str:
    """The node's prompt; a torso hidden under the clothes is asked for with underwear on it, never bare."""
    return prompt(tag, 0, cover="complete", wear="underwear") if tag == "body" else prompt(tag, 0)


def extract(client, image: Image.Image, tag: str, mask: np.ndarray, lora: str, size: int, work: Path, n: int) -> dict:
    """The part whole at the generator's size, matted; returns its files, the square of the canvas it covers and its
    prompt."""
    import l2d
    ys, xs = np.nonzero(mask)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    span = int(max(x1 - x0, y1 - y0) * 1.3) + 16
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    box = (cx - span // 2, cy - span // 2, cx - span // 2 + span, cy - span // 2 + span)
    crop = image.convert("RGB").crop(box).resize((size, size), Image.LANCZOS if size <= span else Image.BICUBIC)
    hint = Image.fromarray((mask * 255).astype(np.uint8)).crop(box).resize((size, size), Image.NEAREST).convert("RGB")
    crop.save(work / f"{n}_input.png")
    hint.save(work / f"{n}_hint.png")
    a = client.upload(work / f"{n}_input.png", f"l2d_split_{n}_input.png")
    b = client.upload(work / f"{n}_hint.png", f"l2d_split_{n}_hint.png")
    words_ = words(tag)
    files = client.run(l2d.edit_graph(a, words_, size, size, lora=lora, refs=(b,), name=f"split_{n}", matte=True),
                       work, f"pred_{n}")
    raw = next(f for f in files if "_raw_" in f.name)
    matted = next(f for f in files if "_raw_" not in f.name)
    return {"matted": matted, "raw": raw, "input": work / f"{n}_input.png", "hint": work / f"{n}_hint.png",
            "rect": [int(box[0]), int(box[1]), span, span], "prompt": words_}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("out")
    ap.add_argument("--planner", required=True)
    ap.add_argument("--lora", required=True)
    ap.add_argument("--min", type=int, default=400)
    ap.add_argument("--size", type=int, default=1024)
    a = ap.parse_args()
    out = Path(a.out)
    (out / "parts").mkdir(parents=True, exist_ok=True)
    (out / "work").mkdir(exist_ok=True)
    image = Image.open(a.image).convert("RGBA")
    flat = Image.new("RGBA", image.size, (255, 255, 255, 255))
    flat.alpha_composite(image)
    tags, classes = plan(flat, Path(a.planner))
    # No part is planned where the illustration is empty, nor on the planner's own background class.
    silhouette = np.asarray(image)[..., 3] >= 128
    tags = tags.copy()
    tags[~silhouette] = 255
    if "background" in classes:
        tags[tags == classes.index("background")] = 255
    rng = np.random.RandomState(1)
    palette = rng.randint(40, 255, (256, 3)).astype(np.uint8)
    palette[255] = 0
    Image.fromarray(palette[tags]).save(out / "plan.png")
    from env import client
    c = client(timeout=900)
    found = sorted(nodes(tags, classes, a.min), key=lambda t: ORDER.index(t[0]) if t[0] in ORDER else len(ORDER))
    image.save(out / "source.png")
    (out / "regions").mkdir(exist_ok=True)
    layers = [{"id": "source", "name": "source", "image": {"path": "source.png", "width": image.width, "height": image.height},
               "rect": {"left": 0, "top": 0, "width": image.width, "height": image.height}, "reference": True, "opacity": 0.5}]
    for n, (tag, side, mask) in enumerate(found):
        got = extract(c, flat, tag, mask, a.lora, a.size, out / "work", n)
        name = f"{n:02d}_{tag}_{side}"
        picture = Image.open(got["matted"])
        picture.save(out / "parts" / f"{name}.png")
        Image.fromarray((mask * 255).astype(np.uint8)).save(out / "regions" / f"{name}.png")
        # Sides on the picture (l: left of the face's centre) are the character's own right, and the other way round.
        own = {"l": "right", "r": "left"}.get(side)
        left, top, w, h = got["rect"]
        layers.append({
            "id": name, "name": f"{tag.replace('_', ' ')}" + (f" {own}" if own else ""),
            "image": {"path": f"parts/{name}.png", "width": picture.width, "height": picture.height},
            "rect": {"left": left, "top": top, "width": w, "height": h},
            **({"group": "head"} if tag in HEAD else {}),
            "tags": WORDS.get(tag, []) + ([own] if own else []) + [tag],
            "split": {"tag": tag, "side_on_picture": side, "order": n, "prompt": got["prompt"],
                      "region": f"regions/{name}.png", "region_pixels": int(mask.sum()),
                      "close_up": str(got["input"].relative_to(out)).replace("\\", "/"),
                      "hint": str(got["hint"].relative_to(out)).replace("\\", "/"),
                      "generated": str(got["raw"].relative_to(out)).replace("\\", "/"),
                      "generated_size": [picture.width, picture.height]}})
        print(name, int(mask.sum()), flush=True)
    manifest = {"format": "psd2live-layout", "version": 1, "name": Path(a.image).stem,
                "canvas": {"width": image.width, "height": image.height},
                "groups": [{"path": "head"}], "layers": layers,
                "split": {"planner": str(a.planner), "classes": classes, "lora": a.lora, "size": a.size, "min": a.min,
                          "plan": "plan.png", "order": ORDER}}
    (out / "layout.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
