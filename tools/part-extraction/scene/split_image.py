"""Splits a character illustration into Live2D-style parts: the planner tags every pixel, and each tagged region is
repainted whole (hidden parts included) by the split LoRA from a close-up of it, at the generator's top size.

  python split_image.py IMAGE OUT_DIR --planner planner.pt --lora NAME [--min 400] [--size 1024]
  python split_image.py IMAGE OUT_DIR --planner4 planner4.pt [--planner planner.pt] [--grain unit] --lora NAME
  python split_image.py IMAGE OUT_DIR --planner4 planner4.pt --plan-only

With --planner4 (train_planner4.py) the split has as many layers as the picture holds: the big parts found on the whole
picture (by --planner when given) are each planned again in a close-up, the head as one, and their pixels clustered
into parts, the groups in them and the single layers (--grain stops sooner); each layer sits in the Layout group of
its big part, part and group. --plan-only writes the plan (plan.png, plan_layers.png, regions/, plan.json) and stops.

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
import train_planner4 as p4  # noqa: E402

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


class Planner4Run:
    """Planner v4 on one close-up: tags, roles, embeddings and order scores at the network's size (embeddings, order
    and everything clustered at half of it)."""

    def __init__(self, checkpoint: Path):
        state = torch.load(checkpoint, map_location="cpu")
        self.classes = state["classes"]
        self.model = p4.Planner4(len(self.classes))
        self.model.load_state_dict(state["state"])
        self.model = self.model.cuda().eval()

    @torch.no_grad()
    def __call__(self, crop: Image.Image) -> dict:
        x = torch.from_numpy(np.asarray(crop.convert("RGB").resize((p4.SIZE, p4.SIZE), Image.BILINEAR)).copy()).permute(2, 0, 1).float() / 255
        with torch.autocast("cuda", dtype=torch.bfloat16):
            out = self.model(((x - p4.MEAN) / p4.STD)[None].cuda())
        half = p4.SIZE // 2
        down = lambda t: torch.nn.functional.interpolate(t.float(), size=(half, half), mode="bilinear", align_corners=False)[0]
        return {"classes": out["classes"][0].float().argmax(0), "half": down(out["classes"]).argmax(0),
                "roles": torch.sigmoid(down(out["roles"])) > 0.5, "embed": out["embed"][0].float(), "order": out["order"][0, 0].float()}


def square(mask: np.ndarray, grow: float) -> tuple[int, int, int]:
    """The square (left, top, side) grow times the longer side of mask's pixels, centred on them."""
    ys, xs = np.nonzero(mask)
    side = int(max(xs.max() - xs.min(), ys.max() - ys.min()) * grow) + 16
    return int((xs.min() + xs.max()) // 2 - side // 2), int((ys.min() + ys.max()) // 2 - side // 2), side


SMALL = {"irides", "eyewhite", "eyelash", "eyebrow", "mouth", "mouth_inside", "nose", "facedetail", "earwear"}
FACE = {"face", "ears", "eyewear"} | SMALL  # the head close-up: the face with its features and ears


def plan_layers(image: Image.Image, silhouette: np.ndarray, run: Planner4Run, grain: str, min_pixels: int,
                tags: np.ndarray | None = None):
    """Planner v4, recursively. The whole picture's tags (or tags, from a planner v3) give the big parts; each big
    part's close-up, and the head's, is planned again, and its own pixels there are clustered into parts, the groups
    in each part, then the layers in each group, down to grain. Returns the leaves back to front, each with its tag,
    side, big part, path in the hierarchy, mask on the picture, the roles drawn over it and an order key (the tag's
    place in ORDER, then the close-up's order score), and the whole picture's tags. Masks are only the LoRA's hints."""
    classes = run.classes
    flat = Image.new("RGBA", image.size, (225, 225, 225, 255))
    flat.alpha_composite(image)
    flat = flat.convert("RGB")
    W, H = image.size

    def to_picture(grid: torch.Tensor, left: int, top: int, side: int) -> np.ndarray:
        """A close-up's map back onto the picture, nearest neighbour; 0 where it does not reach."""
        im = Image.fromarray(grid.cpu().numpy().astype(np.int32)).resize((side, side), Image.NEAREST)
        out = np.zeros((H, W), np.int32)
        sx0, sy0, sx1, sy1 = max(0, left), max(0, top), min(W, left + side), min(H, top + side)
        out[sy0:sy1, sx0:sx1] = np.asarray(im)[sy0 - top:sy1 - top, sx0 - left:sx1 - left]
        return out

    if tags is None:
        left, top, side = square(silhouette, 1.05)
        whole = run(flat.crop((left, top, left + side, top + side)))
        tags = to_picture(whole["classes"] + 1, left, top, side) - 1
        tags = np.where(tags < 0, 255, tags).astype(np.uint8)
    tags = tags.copy()
    tags[~silhouette] = 255
    ys, xs = np.nonzero(tags == classes.index("face"))
    centre = xs.mean() if len(xs) else W / 2
    big = [(name, side, m) for name, side, m in nodes(tags, classes, min_pixels) if name not in FACE and name != "background"]
    head = np.isin(tags, [classes.index(t) for t in FACE])
    if head.sum() >= min_pixels:
        big.append(("head", "c", head))
    grains = p4.GRAINS[::-1][:{"part": 1, "group": 2, "unit": 3}[grain]]  # coarse to fine
    half = p4.SIZE // 2
    leaves = []
    for name, side, region in big:
        left, top, span = square(region, 1.3)
        out = run(flat.crop((left, top, left + span, top + span)))
        cls = out["half"]
        own = torch.zeros_like(cls, dtype=torch.bool)
        for t in (FACE if name == "head" else {name}):
            own |= cls == classes.index(t)
        x_on_picture = (torch.arange(half, device=cls.device) + 0.5) * span / half + left
        if side != "c":  # a paired part keeps to its side of the face
            own &= ((x_on_picture < centre) if side == "l" else (x_on_picture >= centre))[None, :]
        stack = [(own, [])]
        for g in grains:
            k = p4.GRAINS.index(g)
            stack = [(labels == j, path + [j]) for mask, path in stack
                     for labels in [p4.cluster(out["embed"][k * p4.EMB:(k + 1) * p4.EMB], mask)] for j in range(1, int(labels.max()) + 1)]
        for mask, path in stack:
            picture = to_picture(mask.int(), left, top, span) > 0
            picture &= silhouette
            if picture.sum() < min_pixels // 4:
                continue
            tag = classes[int(torch.bincount(cls[mask], minlength=len(classes)).argmax())]
            mid = float(x_on_picture[mask.nonzero()[:, 1]].mean())
            leaf_side = side if side != "c" else ("l" if mid < centre else "r") if tag in PAIRED else "c"
            leaves.append({"tag": tag, "side": leaf_side, "big": name if side == "c" else f"{name}_{side}", "path": path,
                           "mask": picture, "order": (ORDER.index(tag) if tag in ORDER else len(ORDER), float(out["order"][mask].mean())),
                           "roles": [r for r, bits in zip(("shadow", "highlight", "line", "detail"), out["roles"])
                                     if float(bits[mask].float().mean()) > 0.15]})
    return sorted(leaves, key=lambda leaf: leaf["order"]), tags


def words(tag: str, piece: int = 0) -> str:
    """The node's prompt; a torso hidden under the clothes is asked for with underwear on it, never bare."""
    return prompt(tag, piece, cover="complete", wear="underwear") if tag == "body" else prompt(tag, piece)


def extract(client, image: Image.Image, tag: str, mask: np.ndarray, lora: str, size: int, work: Path, n: int, piece: int = 0) -> dict:
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
    words_ = words(tag, piece)
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
    ap.add_argument("--planner", help="planner v3 (train_planner.py): the tags, or with --planner4 only the big parts")
    ap.add_argument("--planner4", help="planner v4 (train_planner4.py): every part's layers, planned in close-ups")
    ap.add_argument("--grain", choices=("part", "group", "unit"), default="unit", help="how finely planner v4 splits")
    ap.add_argument("--plan-only", action="store_true", help="write the plan (plan.png, regions/, plan.json) and stop")
    ap.add_argument("--lora")
    ap.add_argument("--min", type=int, default=400)
    ap.add_argument("--size", type=int, default=1024)
    a = ap.parse_args()
    if not (a.planner or a.planner4) or not (a.lora or a.plan_only):
        ap.error("needs --planner or --planner4, and --lora unless --plan-only")
    out = Path(a.out)
    (out / "parts").mkdir(parents=True, exist_ok=True)
    (out / "work").mkdir(exist_ok=True)
    (out / "regions").mkdir(exist_ok=True)
    image = Image.open(a.image).convert("RGBA")
    flat = Image.new("RGBA", image.size, (255, 255, 255, 255))
    flat.alpha_composite(image)
    # No part is planned where the illustration is empty, nor on the planner's own background class.
    silhouette = np.asarray(image)[..., 3] >= 128
    tags = None
    if a.planner:
        tags, classes = plan(flat, Path(a.planner))
        tags = tags.copy()
        tags[~silhouette] = 255
    if a.planner4:
        run = Planner4Run(Path(a.planner4))
        leaves, tags = plan_layers(image, silhouette, run, a.grain, a.min, tags)
        classes = run.classes
    if "background" in classes:
        tags[tags == classes.index("background")] = 255
    rng = np.random.RandomState(1)
    palette = rng.randint(40, 255, (256, 3)).astype(np.uint8)
    palette[255] = 0
    Image.fromarray(palette[tags]).save(out / "plan.png")
    if a.planner4:
        # Each leaf: its tag and side, its Layout group (the big part, then the part and group it was clustered into),
        # and whether it is one of several pieces of its tag in its big part.
        many = {}
        for leaf in leaves:
            many[(leaf["big"], leaf["tag"])] = many.get((leaf["big"], leaf["tag"]), 0) + 1
        found = [(leaf["tag"], leaf["side"], leaf["mask"],
                  "/".join([leaf["big"]] + [f"{g[0]}{j}" for g, j in zip(("part", "group"), leaf["path"][:-1])]),
                  int(many[(leaf["big"], leaf["tag"])] > 1), leaf["roles"]) for leaf in leaves]
        sheet = np.zeros((image.height, image.width, 3), np.uint8)
        for n, (tag, side, mask, group, piece, roles) in enumerate(found):
            sheet[mask] = palette[n % 255]
        Image.fromarray(sheet).save(out / "plan_layers.png")
    else:
        found = [(tag, side, mask, "head" if tag in HEAD else None, 0, [])
                 for tag, side, mask in sorted(nodes(tags, classes, a.min), key=lambda t: ORDER.index(t[0]) if t[0] in ORDER else len(ORDER))]
    names = [f"{n:02d}_{tag}_{side}" for n, (tag, side, *_) in enumerate(found)]
    for name, (tag, side, mask, *_) in zip(names, found):
        Image.fromarray((mask * 255).astype(np.uint8)).save(out / "regions" / f"{name}.png")
    if a.plan_only:
        (out / "plan.json").write_text(json.dumps([{"name": name, "tag": tag, "side": side, "group": group, "piece": piece, "roles": roles,
                                                    "pixels": int(mask.sum()), "region": f"regions/{name}.png"}
                                                   for name, (tag, side, mask, group, piece, roles) in zip(names, found)], indent=1), encoding="utf-8")
        print(len(found), "layers planned")
        return
    from env import client
    c = client(timeout=900)
    image.save(out / "source.png")
    layers = [{"id": "source", "name": "source", "image": {"path": "source.png", "width": image.width, "height": image.height},
               "rect": {"left": 0, "top": 0, "width": image.width, "height": image.height}, "reference": True, "opacity": 0.5}]
    groups = set()
    for n, (name, (tag, side, mask, group, piece, roles)) in enumerate(zip(names, found)):
        got = extract(c, flat, tag, mask, a.lora, a.size, out / "work", n, piece)
        picture = Image.open(got["matted"])
        picture.save(out / "parts" / f"{name}.png")
        # Sides on the picture (l: left of the face's centre) are the character's own right, and the other way round.
        own = {"l": "right", "r": "left"}.get(side)
        left, top, w, h = got["rect"]
        if group:
            groups.update("/".join(group.split("/")[:i + 1]) for i in range(group.count("/") + 1))
        layers.append({
            "id": name, "name": f"{tag.replace('_', ' ')}" + (f" {own}" if own else ""),
            "image": {"path": f"parts/{name}.png", "width": picture.width, "height": picture.height},
            "rect": {"left": left, "top": top, "width": w, "height": h},
            **({"group": group} if group else {}),
            "tags": WORDS.get(tag, []) + ([own] if own else []) + [tag],
            "split": {"tag": tag, "side_on_picture": side, "order": n, "prompt": got["prompt"], "roles": roles,
                      "region": f"regions/{name}.png", "region_pixels": int(mask.sum()),
                      "close_up": str(got["input"].relative_to(out)).replace("\\", "/"),
                      "hint": str(got["hint"].relative_to(out)).replace("\\", "/"),
                      "generated": str(got["raw"].relative_to(out)).replace("\\", "/"),
                      "generated_size": [picture.width, picture.height]}})
        print(name, int(mask.sum()), flush=True)
    manifest = {"format": "psd2live-layout", "version": 1, "name": Path(a.image).stem,
                "canvas": {"width": image.width, "height": image.height},
                "groups": [{"path": g} for g in sorted(groups)], "layers": layers,
                "split": {"planner": str(a.planner), "planner4": str(a.planner4), "grain": a.grain, "classes": classes,
                          "lora": a.lora, "size": a.size, "min": a.min, "plan": "plan.png", "order": ORDER}}
    (out / "layout.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
