"""Splits a character illustration into Live2D-style parts: the planner tags every pixel, and each tagged region is
repainted whole (hidden parts included) by the split LoRA from a close-up of it, at the generator's top size.

  python split_image.py IMAGE OUT_DIR --planner planner.pt --lora NAME [--min 400] [--size 1024]
  python split_image.py IMAGE OUT_DIR --planner4 planner4.pt [--planner planner.pt] [--grain unit] --lora NAME
  python split_image.py IMAGE OUT_DIR --planner4 planner4.pt --plan-only
  python split_image.py IMAGE OUT_DIR --planner5 planner5.pt [--grain unit] (--lora NAME | --plan-only)

With --planner4 (train_planner4.py) the split has as many layers as the picture holds: the big parts found on the whole
picture (by --planner when given) are each planned again in a close-up, the head as one, and their pixels clustered
into parts, the groups in them and the single layers (--grain stops sooner); each layer sits in the Layout group of
its big part, part and group. --plan-only writes the plan (plan.png, plan_layers.png, regions/, plan.json) and stops.
With --planner5 (train_planner5.py, plan5.py) the picture is planned in tiles at three zooms instead, each layer with
its whole shape too (regions/<name>_whole.png): a layer the picture does not show at all is planned by it alone, and
its whole shape is the LoRA's hint.

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
        self.model.load_state_dict(p4.fit_state(self.model, state["state"]))
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
EYE = {"irides", "eyewhite", "eyelash", "eyebrow"}  # an eye close-up, as plan4_data.py frames one
MOUTH = {"mouth", "mouth_inside"}
# Clustering threshold per grain, from train_planner4.py --eval --thresholds --absorbs on held-out close-ups (best F1
# with fragments under p4.ABSORB merged): parts match best a little coarser than the trained margin, groups and single
# layers at it.
THRESHOLDS = {"part": 2.0, "group": p4.DELTA_D, "unit": p4.DELTA_D}


def plan_layers(image: Image.Image, silhouette: np.ndarray, run: Planner4Run, grain: str, min_pixels: int,
                tags: np.ndarray | None = None, eyes: bool = True, join: bool = True):
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

    left, top, side = square(silhouette, 1.05)
    whole = run(flat.crop((left, top, left + side, top + side)))
    if tags is None:
        tags = to_picture(whole["classes"] + 1, left, top, side) - 1
        tags = np.where(tags < 0, 255, tags).astype(np.uint8)
    # The layers as the whole picture sees them: coarse, but whole across the big parts' borders (join below).
    sx = ((torch.arange(p4.SIZE // 2, device="cuda") + 0.5) * side / (p4.SIZE // 2) + left).long()
    sy = ((torch.arange(p4.SIZE // 2, device="cuda") + 0.5) * side / (p4.SIZE // 2) + top).long()
    seen = torch.from_numpy(silhouette).cuda()[sy.clamp(0, H - 1)][:, sx.clamp(0, W - 1)]         & ((sy >= 0) & (sy < H))[:, None] & ((sx >= 0) & (sx < W))[None, :]
    k = p4.GRAINS.index("unit")
    whole_units = to_picture(p4.cluster(whole["embed"][k * p4.EMB:(k + 1) * p4.EMB], seen, THRESHOLDS["unit"]), left, top, side)
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
    claimed = np.zeros((H, W), bool)  # every big part's region: a close-up takes no pixel another part was given
    for _, _, region in big:
        claimed |= region
    leaves = []

    def plan_closeup(name: str, side: str, region: np.ndarray, kinds: set[str], path0: list):
        """Plans region in its own close-up: its pixels there are the region, and what the close-up tags as one of
        kinds where no part was given anything; the head's eyes and mouth are planned again in close-ups of their
        own first. Appends the leaves."""
        left, top, span = square(region, 1.3)
        out = run(flat.crop((left, top, left + span, top + span)))
        cls = out["half"]
        x_on_picture = (torch.arange(half, device=cls.device) + 0.5) * span / half + left
        y_on_picture = (torch.arange(half, device=cls.device) + 0.5) * span / half + top
        xi, yi = x_on_picture.long(), y_on_picture.long()
        inside = ((yi >= 0) & (yi < H))[:, None] & ((xi >= 0) & (xi < W))[None, :]
        sample = lambda m: torch.from_numpy(m).to(cls.device)[yi.clamp(0, H - 1)][:, xi.clamp(0, W - 1)] & inside
        kind = torch.zeros_like(cls, dtype=torch.bool)
        for t in kinds:
            kind |= cls == classes.index(t)
        # The region is the part's whatever the close-up calls it (boots it calls legwear stay the boots'); the
        # close-up adds its own kind where the whole picture gave nothing.
        own = sample(region) | (kind & sample(silhouette & ~claimed))
        if side != "c":  # a paired part keeps to its side of the face
            own &= ((x_on_picture < centre) if side == "l" else (x_on_picture >= centre))[None, :]
        if name == "head" and eyes:  # third level: each eye (with its brow) and the mouth in a close-up of its own
            for sub, sub_kinds, sub_side in (("eye", EYE, "l"), ("eye", EYE, "r"), ("mouth", MOUTH, "c")):
                m = torch.zeros_like(own)
                for t in sub_kinds:
                    m |= cls == classes.index(t)
                m &= own
                if sub_side != "c":
                    m &= ((x_on_picture < centre) if sub_side == "l" else (x_on_picture >= centre))[None, :]
                picture = (to_picture(m.int(), left, top, span) > 0) & silhouette
                if picture.sum() < min_pixels // 4:
                    continue
                plan_closeup(sub, sub_side, picture, sub_kinds, [f"{sub}_{sub_side}" if sub_side != "c" else sub])
                own &= ~m
        # Each grain is clustered over all of own; a leaf of the finest sits in the coarser clusters it falls in most.
        # (Cutting the coarser clusters first and the finer inside them carries every coarse cut down.)
        labels = {g: p4.cluster(out["embed"][p4.GRAINS.index(g) * p4.EMB:(p4.GRAINS.index(g) + 1) * p4.EMB], own, THRESHOLDS[g])
                  for g in grains}
        fine = labels[grains[-1]]
        stack = [(fine == j, path0 + [int(torch.mode(labels[g][fine == j]).values) for g in grains[:-1]] + [j])
                 for j in range(1, int(fine.max()) + 1)]
        allowed = torch.tensor([c in kinds for c in classes], device=cls.device)
        for mask, path in stack:
            picture = to_picture(mask.int(), left, top, span) > 0
            picture &= silhouette
            if picture.sum() < min_pixels // 4:
                continue
            # A part's pieces keep its tag; the head's, eyes' and mouth's take the tag of theirs the close-up gives
            # them most.
            votes = torch.bincount(cls[mask], minlength=len(classes)) * allowed
            tag = classes[int(votes.argmax())] if len(kinds) > 1 and votes.any() else name if name in classes else sorted(kinds)[0]
            mid = float(x_on_picture[mask.nonzero()[:, 1]].mean())
            leaf_side = side if side != "c" else ("l" if mid < centre else "r") if tag in PAIRED else "c"
            big_name = "head" if name in ("eye", "mouth") else name if side == "c" else f"{name}_{side}"
            leaves.append({"tag": tag, "side": leaf_side, "big": big_name, "path": path,
                           "mask": picture, "order": (ORDER.index(tag) if tag in ORDER else len(ORDER), float(out["order"][mask].mean())),
                           "roles": [r for r, bits in zip(("shadow", "highlight", "line", "detail"), out["roles"])
                                     if float(bits[mask].float().mean()) > 0.15]})

    for name, side, region in big:
        plan_closeup(name, side, region, FACE if name == "head" else {name}, [])
    if join:
        leaves = join_across(leaves, whole_units)
    return sorted(leaves, key=lambda leaf: leaf["order"]), tags


# Big parts one drawn layer often runs across: the whole picture calls a stocking's thigh leg and its calf legwear, a
# lock of hair front at the top and side below, a dress top and skirt.
KIN = [{"leg", "legwear"}, {"front_hair", "side_hair", "back_hair"}, {"topwear", "bottomwear", "body"},
       {"arm", "hand", "handwear"}, {"headwear", "ears"}]


def kin(a: str, b: str) -> bool:
    """Whether big parts a and b (tag_side names) are ones a layer runs across."""
    ta, tb = a.rsplit("_", 1)[0] if a.endswith(("_l", "_r")) else a, b.rsplit("_", 1)[0] if b.endswith(("_l", "_r")) else b
    same_side = a[-2:] == b[-2:] or not (a.endswith(("_l", "_r")) and b.endswith(("_l", "_r")))
    return same_side and any(ta in k and tb in k for k in KIN)


def join_across(leaves: list[dict], whole_units: np.ndarray, share: float = 0.6, cover: float = 0.0) -> list[dict]:
    """Leaves of kin big parts (KIN) that touch and lie mostly (share) in one layer of the whole picture's, each
    covering a good part (cover) of it, are one layer the big parts' border cut in two: each such set becomes one leaf,
    the biggest one's tag, side, group and order kept. A small leaf in a big layer of the whole picture's is a layer
    only its close-up could see, and stays; inside a big part its close-up's finer split stands."""
    sizes = np.bincount(whole_units.ravel())
    key = []
    for leaf in leaves:
        ids, counts = np.unique(whole_units[leaf["mask"]], return_counts=True)
        counts[ids == 0] = 0
        top = int(ids[counts.argmax()])
        key.append(top if counts.max() > 0 and counts.max() >= share * counts.sum() and counts.max() >= cover * sizes[top] else 0)
    parent = list(range(len(leaves)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    boxes = []
    for leaf in leaves:
        ys, xs = np.nonzero(leaf["mask"])
        boxes.append((ys.min(), ys.max(), xs.min(), xs.max()))
    for i in range(len(leaves)):
        if not key[i]:
            continue
        y0, y1, x0, x1 = boxes[i]
        near = None
        for j in range(i + 1, len(leaves)):
            if key[j] != key[i] or leaves[j]["big"] == leaves[i]["big"] or not kin(leaves[i]["big"], leaves[j]["big"]) or find(i) == find(j):
                continue
            b = boxes[j]
            if b[0] > y1 + 3 or b[1] < y0 - 3 or b[2] > x1 + 3 or b[3] < x0 - 3:
                continue
            if near is None:  # leaf i grown by 3 pixels, within its box
                crop = Image.fromarray(leaves[i]["mask"][max(0, y0 - 3):y1 + 4, max(0, x0 - 3):x1 + 4].astype(np.uint8) * 255)
                near = np.asarray(crop.filter(ImageFilter.MaxFilter(7))) > 127
            if (near & leaves[j]["mask"][max(0, y0 - 3):y1 + 4, max(0, x0 - 3):x1 + 4]).any():
                parent[find(j)] = find(i)
    sets = {}
    for i in range(len(leaves)):
        sets.setdefault(find(i), []).append(i)
    joined = []
    for members in sets.values():
        lead = max(members, key=lambda i: leaves[i]["mask"].sum())
        leaf = dict(leaves[lead])
        if len(members) > 1:
            leaf["mask"] = np.logical_or.reduce([leaves[i]["mask"] for i in members])
        joined.append(leaf)
    return joined


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
    ap.add_argument("--planner5", help="planner v5 (train_planner5.py): every layer, shown or covered, planned in tiles")
    ap.add_argument("--grain", choices=("part", "group", "unit"), default="unit", help="how finely planner v4 splits")
    ap.add_argument("--plan-only", action="store_true", help="write the plan (plan.png, regions/, plan.json) and stop")
    ap.add_argument("--lora")
    ap.add_argument("--min", type=int, default=400)
    ap.add_argument("--size", type=int, default=1024)
    a = ap.parse_args()
    if not (a.planner or a.planner4 or a.planner5) or not (a.lora or a.plan_only):
        ap.error("needs --planner, --planner4 or --planner5, and --lora unless --plan-only")
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
    if a.planner5:
        import plan5
        run = plan5.Planner5Run(Path(a.planner5))
        leaves, tags = plan5.plan_layers(image, silhouette, run, a.grain, a.min)
        classes = run.classes
    layered = bool(a.planner4 or a.planner5)
    if "background" in classes:
        tags[tags == classes.index("background")] = 255
    rng = np.random.RandomState(1)
    palette = rng.randint(40, 255, (256, 3)).astype(np.uint8)
    palette[255] = 0
    Image.fromarray(palette[tags]).save(out / "plan.png")
    wholes = {}
    if layered:
        # Each leaf: its tag and side, its Layout group (the big part, then the part and group it was clustered into),
        # and whether it is one of several pieces of its tag in its big part.
        many = {}
        for leaf in leaves:
            many[(leaf["big"], leaf["tag"])] = many.get((leaf["big"], leaf["tag"]), 0) + 1
        def group_of(leaf) -> str:
            """big/[eye_l|mouth]/p<i>/g<j>: the close-ups it was planned in, then the clusters above its own."""
            named = [p for p in leaf["path"] if isinstance(p, str)]
            ids = [p for p in leaf["path"] if not isinstance(p, str)]
            return "/".join([leaf["big"]] + named + [f"{g[0]}{j}" for g, j in zip(("part", "group"), ids[:-1])])

        # A covered layer (planner v5) has no pixels of its own: its whole shape is its region.
        found = [(leaf["tag"], leaf["side"], leaf["mask"] if leaf["mask"].any() else leaf["whole"], group_of(leaf),
                  int(many[(leaf["big"], leaf["tag"])] > 1), leaf["roles"]) for leaf in leaves]
        wholes = {n: leaf["whole"] for n, leaf in enumerate(leaves) if "whole" in leaf}
        sheet = np.zeros((image.height, image.width, 3), np.uint8)
        for n, (tag, side, mask, group, piece, roles) in enumerate(found):
            sheet[mask] = palette[n % 255]
        Image.fromarray(sheet).save(out / "plan_layers.png")
    else:
        found = [(tag, side, mask, "head" if tag in HEAD else None, 0, [])
                 for tag, side, mask in sorted(nodes(tags, classes, a.min), key=lambda t: ORDER.index(t[0]) if t[0] in ORDER else len(ORDER))]
    names = [f"{n:02d}_{tag}_{side}" for n, (tag, side, *_) in enumerate(found)]
    for n, (name, (tag, side, mask, *_)) in enumerate(zip(names, found)):
        Image.fromarray((mask * 255).astype(np.uint8)).save(out / "regions" / f"{name}.png")
        if n in wholes:
            Image.fromarray((wholes[n] * 255).astype(np.uint8)).save(out / "regions" / f"{name}_whole.png")
    if a.plan_only:
        (out / "plan.json").write_text(json.dumps([{"name": name, "tag": tag, "side": side, "group": group, "piece": piece, "roles": roles,
                                                    "pixels": int(mask.sum()), "region": f"regions/{name}.png",
                                                    **({"whole": f"regions/{name}_whole.png", "covered": bool(leaves[n].get("covered"))} if n in wholes else {})}
                                                   for n, (name, (tag, side, mask, group, piece, roles)) in enumerate(zip(names, found))], indent=1), encoding="utf-8")
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
                      **({"whole": f"regions/{name}_whole.png", "covered": bool(leaves[n].get("covered"))} if n in wholes else {}),
                      "close_up": str(got["input"].relative_to(out)).replace("\\", "/"),
                      "hint": str(got["hint"].relative_to(out)).replace("\\", "/"),
                      "generated": str(got["raw"].relative_to(out)).replace("\\", "/"),
                      "generated_size": [picture.width, picture.height]}})
        print(name, int(mask.sum()), flush=True)
    manifest = {"format": "psd2live-layout", "version": 1, "name": Path(a.image).stem,
                "canvas": {"width": image.width, "height": image.height},
                "groups": [{"path": g} for g in sorted(groups)], "layers": layers,
                "split": {"planner": str(a.planner), "planner4": str(a.planner4), "planner5": str(a.planner5), "grain": a.grain, "classes": classes,
                          "lora": a.lora, "size": a.size, "min": a.min, "plan": "plan.png", "order": ORDER}}
    (out / "layout.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
