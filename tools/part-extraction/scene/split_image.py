"""Splits a character illustration into Live2D-style layers: the planner tags every pixel, each tagged region is
extracted whole (hidden parts included) by the split LoRA, and the layers are stacked into a PSD.

  python split_image.py IMAGE OUT_DIR --planner planner.pt --lora NAME [--min 400] [--size 512]

Needs the planner checkpoint (train_planner.py) on a local GPU and a ComfyUI with the LoRA (L2D_COMFY_URL, see
comfy/env.py). Writes OUT_DIR/plan.png (tag map), OUT_DIR/layers/<order>_<tag>_<n>.png (full canvas, transparent)
and OUT_DIR/<image>.psd with the layers bottom to top.

Each layer keeps the illustration's own pixels where the part shows (its hint) and takes the LoRA's only where the
part was hidden, so what is visible is exact and only the completion is generated.
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
PAIRED = {"irides", "eyewhite", "eyelash", "eyebrow", "ears", "earwear", "arm", "hand", "leg", "handwear", "legwear", "footwear"}
GRAY = 128


def close(mask: np.ndarray, r: int = 2) -> np.ndarray:
    """Dilate then erode a boolean mask by [r] pixels."""
    im = Image.fromarray(mask.astype(np.uint8) * 255)
    return np.asarray(im.filter(ImageFilter.MaxFilter(2 * r + 1)).filter(ImageFilter.MinFilter(2 * r + 1))) > 127


def open_(mask: np.ndarray, r: int = 1) -> np.ndarray:
    """Erode then dilate a boolean mask by [r] pixels."""
    im = Image.fromarray(mask.astype(np.uint8) * 255)
    return np.asarray(im.filter(ImageFilter.MinFilter(2 * r + 1)).filter(ImageFilter.MaxFilter(2 * r + 1))) > 127


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


def extract(client, image: Image.Image, tag: str, mask: np.ndarray, lora: str, size: int, work: Path, n: int) -> np.ndarray:
    """The node whole, as an RGBA canvas-sized array: the LoRA on a crop around the node, gray made transparent."""
    import l2d
    ys, xs = np.nonzero(mask)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    span = int(max(x1 - x0, y1 - y0) * 1.3) + 16
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    box = (cx - span // 2, cy - span // 2, cx - span // 2 + span, cy - span // 2 + span)
    side = max(256, min(size, span // 16 * 16))
    crop = image.convert("RGB").crop(box).resize((side, side), Image.LANCZOS)
    hint = Image.fromarray((mask * 255).astype(np.uint8)).crop(box).resize((side, side), Image.NEAREST).convert("RGB")
    crop.save(work / f"{n}_input.png")
    hint.save(work / f"{n}_hint.png")
    a = client.upload(work / f"{n}_input.png", f"l2d_split_{n}_input.png")
    b = client.upload(work / f"{n}_hint.png", f"l2d_split_{n}_hint.png")
    files = client.run(l2d.edit_graph(a, prompt(tag, 0), side, side, lora=lora, refs=(b,), name=f"split_{n}"), work, f"pred_{n}")
    pred = np.asarray(Image.open(files[0]).convert("RGB").resize((span, span), Image.LANCZOS)).astype(np.int16)
    alpha = (np.abs(pred - GRAY).max(axis=2) > 18).astype(np.uint8) * 255
    alpha = open_(alpha > 0).astype(np.uint8) * 255
    layer = np.zeros((image.height, image.width, 4), np.uint8)
    # Paste the crop back where it lies on the canvas.
    sx0, sy0 = max(0, box[0]), max(0, box[1])
    sx1, sy1 = min(image.width, box[2]), min(image.height, box[3])
    px0, py0 = sx0 - box[0], sy0 - box[1]
    region = (slice(sy0, sy1), slice(sx0, sx1))
    layer[region + (slice(0, 3),)] = pred[py0:py0 + sy1 - sy0, px0:px0 + sx1 - sx0].clip(0, 255).astype(np.uint8)
    layer[region + (3,)] = alpha[py0:py0 + sy1 - sy0, px0:px0 + sx1 - sx0]
    # Where the part shows, the illustration itself.
    rgb = np.asarray(image.convert("RGB"))
    layer[mask, :3] = rgb[mask]
    layer[mask, 3] = 255
    return layer


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("out")
    ap.add_argument("--planner", required=True)
    ap.add_argument("--lora", required=True)
    ap.add_argument("--min", type=int, default=400)
    ap.add_argument("--size", type=int, default=512)
    a = ap.parse_args()
    out = Path(a.out)
    (out / "layers").mkdir(parents=True, exist_ok=True)
    (out / "work").mkdir(exist_ok=True)
    image = Image.open(a.image).convert("RGBA")
    flat = Image.new("RGBA", image.size, (255, 255, 255, 255))
    flat.alpha_composite(image)
    tags, classes = plan(flat, Path(a.planner))
    rng = np.random.RandomState(1)
    palette = rng.randint(40, 255, (256, 3)).astype(np.uint8)
    palette[255] = 0
    Image.fromarray(palette[tags]).save(out / "plan.png")
    from env import client
    c = client(timeout=900)
    found = sorted(nodes(tags, classes, a.min), key=lambda t: ORDER.index(t[0]) if t[0] in ORDER else len(ORDER))
    layers = []
    for n, (tag, side, mask) in enumerate(found):
        layer = extract(c, flat, tag, mask, a.lora, a.size, out / "work", n)
        name = f"{n:02d}_{tag}_{side}"
        Image.fromarray(layer).save(out / "layers" / f"{name}.png")
        layers.append((name, layer))
        print(name, int(mask.sum()), flush=True)
    write_psd(image.size, layers, out / f"{Path(a.image).stem}.psd")
    (out / "layers.json").write_text(json.dumps([n for n, _ in layers]), encoding="utf-8")


def write_psd(size, layers, path: Path) -> None:
    from psd_tools import PSDImage
    from psd_tools.api.layers import PixelLayer
    psd = PSDImage.new("RGBA", size)
    for name, layer in layers:
        ys, xs = np.nonzero(layer[..., 3])
        if len(xs) == 0:
            continue
        x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
        psd.append(PixelLayer.frompil(Image.fromarray(layer[y0:y1, x0:x1], "RGBA"), psd, name, top=int(y0), left=int(x0)))
    psd.save(path)


if __name__ == "__main__":
    main()
