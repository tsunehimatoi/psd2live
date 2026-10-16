"""Check sheets for the mesh groups the rules are unsure of, and the labels a reviewer gives back.

  python sheets.py make MODEL_DIR [MAX_CONF]   write MODEL_DIR/review/sheet_<n>.png and review/groups.json for every
                                                group (meshes under one deformer) whose rule label is below MAX_CONF
                                                (0.35); each tile shows the group lit in its surroundings
  python sheets.py apply MODEL_DIR             fold review/answers.json ({"<tile>": {"tag", "role"}}) into labels.json

A group is the meshes one deformer holds directly: what the rig moves as one piece, so one label.
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

TILE = 300
COLUMNS = 4
ROWS = 4


def groups_of(scene: dict, labels: dict) -> list[dict]:
    grouped = defaultdict(list)
    for m in scene["meshes"]:
        if "at" in m:
            grouped[m.get("deformer") or m.get("part") or m["id"]].append(m)
    out = []
    for key, meshes in grouped.items():
        # The group's label is its meshes' weighted by size; its confidence the weakest large mesh's.
        weight = defaultdict(float)
        for m in meshes:
            weight[labels[m["id"]]["tag"]] += m["look"]["solid"] + 1
        tag = max(weight, key=weight.get)
        big = [m for m in meshes if m["look"]["solid"] >= 0.2 * max(x["look"]["solid"] for x in meshes)]
        conf = min(labels[m["id"]]["confidence"] for m in big)
        out.append({"key": key, "meshes": [m["id"] for m in meshes], "tag": tag, "confidence": round(conf, 3),
                    "solid": sum(m["look"]["solid"] for m in meshes)})
    return out


def evidence(scene: dict, group: dict) -> str:
    """Names that might help: mesh, part, deformer and the most specific moving parameters."""
    parts = {p["id"]: p["name"] for p in scene["parts"]}
    deformers = {d["id"]: d["name"] for d in scene["deformers"]}
    by_id = {m["id"]: m for m in scene["meshes"]}
    names = set()
    for mid in group["meshes"][:6]:
        m = by_id[mid]
        for n in (m["name"], parts.get(m.get("part"), ""), deformers.get(m.get("deformer"), "")):
            if n and not n.lower().startswith(("artmesh", "part", "warp", "rotation")):
                names.add(n)
    return ", ".join(sorted(names))[:120]


def tile(full: np.ndarray, folder: Path, scene: dict, group: dict) -> Image.Image:
    """The group lit over its dimmed surroundings, framed loosely, with its outline."""
    by_id = {m["id"]: m for m in scene["meshes"]}
    h, w = full.shape[:2]
    mask = np.zeros((h, w), np.float32)
    for mid in group["meshes"]:
        m = by_id[mid]
        path = folder / "meshes" / f"{m['render']}.png"
        if not path.exists():
            continue
        a = np.asarray(Image.open(path).convert("RGBA"), np.float32)[..., 3] / 255
        x, y = m["at"]
        region = mask[y:y + a.shape[0], x:x + a.shape[1]]
        np.maximum(region, a[:region.shape[0], :region.shape[1]], out=region)
    ys, xs = np.nonzero(mask > 0.1)
    if len(xs) == 0:
        return None
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    span = max(x1 - x0, y1 - y0) * 1.8 + 40
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    box = [int(cx - span / 2), int(cy - span / 2), int(cx + span / 2), int(cy + span / 2)]
    rgb = full[..., :3].astype(np.float32)
    alpha = full[..., 3:].astype(np.float32) / 255
    picture = rgb * alpha + 150 * (1 - alpha)
    dim = picture * 0.35 + 30
    lit = picture * mask[..., None] + dim * (1 - mask[..., None])
    image = Image.fromarray(lit.clip(0, 255).astype(np.uint8))
    edge = Image.fromarray((mask > 0.1).astype(np.uint8) * 255).filter(ImageFilter.FIND_EDGES).filter(ImageFilter.MaxFilter(3))
    red = Image.new("RGB", image.size, (255, 40, 40))
    image.paste(red, (0, 0), edge)
    crop = image.crop(box).resize((TILE, TILE), Image.LANCZOS)
    return crop


def make(folder: Path, max_conf: float = 0.35) -> int:
    scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
    labels = json.loads((folder / "labels.json").read_text(encoding="utf-8"))["labels"]
    full = np.asarray(Image.open(folder / "full.png").convert("RGBA"))
    groups = [g for g in groups_of(scene, labels) if g["confidence"] < max_conf and g["solid"] > 60]
    groups.sort(key=lambda g: -g["solid"])
    review = folder / "review"
    review.mkdir(exist_ok=True)
    for old in review.glob("sheet_*.png"):
        old.unlink()
    listed, tiles = [], []
    for g in groups:
        t = tile(full, folder, scene, g)
        if t is None:
            continue
        g["tile"] = len(listed) + 1
        g["names"] = evidence(scene, g)
        listed.append(g)
        tiles.append(t)
    per = COLUMNS * ROWS
    for s in range(0, len(tiles), per):
        sheet = Image.new("RGB", (COLUMNS * TILE, ROWS * (TILE + 34)), "white")
        draw = ImageDraw.Draw(sheet)
        for i, (g, t) in enumerate(zip(listed[s:s + per], tiles[s:s + per])):
            x, y = (i % COLUMNS) * TILE, (i // COLUMNS) * (TILE + 34)
            sheet.paste(t, (x, y))
            draw.rectangle([x, y, x + 34, y + 20], fill="black")
            draw.text((x + 4, y + 4), str(g["tile"]), fill="white")
            draw.text((x + 4, y + TILE + 3), f"guess: {g['tag']}", fill="black")
            draw.text((x + 4, y + TILE + 18), g["names"][:46], fill="gray")
        sheet.save(review / f"sheet_{s // per + 1}.png")
    # The whole character for orientation.
    overview = Image.fromarray(full).convert("RGBA")
    bg = Image.new("RGBA", overview.size, (150, 150, 150, 255))
    bg.alpha_composite(overview)
    scale = 1000 / max(bg.size)
    bg.convert("RGB").resize((int(bg.width * scale), int(bg.height * scale)), Image.LANCZOS).save(review / "overview.png")
    (review / "groups.json").write_text(json.dumps(listed, ensure_ascii=False, indent=0), encoding="utf-8")
    return len(listed)


def apply(folder: Path) -> None:
    review = folder / "review"
    groups = {str(g["tile"]): g for g in json.loads((review / "groups.json").read_text(encoding="utf-8"))}
    answers = json.loads((review / "answers.json").read_text(encoding="utf-8"))
    data = json.loads((folder / "labels.json").read_text(encoding="utf-8"))
    for tile_id, answer in answers.items():
        g = groups.get(str(tile_id))
        if not g:
            continue
        for mid in g["meshes"]:
            lab = data["labels"][mid]
            if lab["confidence"] >= 0.35 and lab["tag"] != answer.get("tag"):
                continue  # a mesh the rules were sure of keeps its label; the group may hold other parts
            lab["tag"] = answer.get("tag", lab["tag"])
            if answer.get("role"):
                lab["role"] = answer["role"]
            lab["confidence"] = 0.6 if answer.get("sure", True) else 0.4
            lab["by"] = "review"
    (folder / "labels.json").write_text(json.dumps(data, ensure_ascii=False, indent=0), encoding="utf-8")


if __name__ == "__main__":
    command, target = sys.argv[1], Path(sys.argv[2])
    if command == "make":
        print(make(target, *(float(a) for a in sys.argv[3:4])), "groups to review")
    else:
        apply(target)
