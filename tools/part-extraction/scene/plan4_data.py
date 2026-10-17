"""Planner v4 data: close-ups at three levels with which drawn layer every pixel shows, its tag, roles and group.

  python plan4_data.py frames ROOT NODES_INDEX VERDICTS[,...] DESCRIBE[,...] OUT/frames.jsonl [--skip DIR,...]
  python plan4_data.py export ROOT NODES_INDEX VERDICTS[,...] DESCRIBE[,...] LAYER_UNITS SUBNODES[,...] FRAMES OUT
      [--families F.json] [--held a,b,...] [--skip DIR,...]

frames lists, per model, the close-ups the planner reads at each level, framed on the visible pixels of the rest render
(full.png) as split_image.py frames them at inference: the whole model (level 0); each big part the first level finds
(a tag, paired tags by side of the face) and the head, the face with its features and ears (level 1); each eye with its
brow, and the mouth (level 2). Every close-up but the whole is a square 1.3 times the longer side of its pixels, and
again at a random scale and shift. Frames are in canvas units, so CloseupDensityTool can measure the art they hold.

export crops each close-up out of the rest render (whose pixels match the art's own density, CloseupDensityTool) at
its own size, at most SIZE, leaving out those under MIN_SIDE, and paints the meshes (meshes/<render>.png) in draw
order into per-pixel labels:
- the instance: the topmost drawn layer of base colour (layer_units.py: the meshes one layer was cut into are one), so
  shading, highlights, line art and details drawn over it do not take its pixels;
- the roles over it, a bit each (shadow 1, highlight 2, line 4, detail 8), cleared where a later base layer covers them;
- the tag of its instance (CLASSES; background where the model leaves the close-up empty).
A fragment's pixels (a scrap cut off a bigger piece), an instance too small to be found and a pixel where only a role
or a soft edge is drawn are left out of the instance map. Each instance records its tag, side, part (its tag and side,
from its group's name when the tag is unsure), named group (subnodes.py's groups of base pieces; else its part, or
itself when untagged) and draw order. Writes
OUT/<scene>_<key>_<id>.jpg (on a random light background), _inst.png (16 bit: 0 none, k + 1 instance k, 65535 left
out), _cls.png, _role.png and .json ({"model", "region", "level", "held", "instances": [{"tag", "side", "part",
"group", "render", "pixels"}]}), held true for every model of a family that holds a --held model.
"""
import argparse
import json
import random
from collections import Counter
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from plan_data import CLASSES, Labels, face_centre, scene_folders, side_of
from layer_units import read as read_units

HEAD = {"face", "facedetail", "irides", "eyewhite", "eyelash", "eyebrow", "nose", "mouth", "mouth_inside", "ears", "eyewear"}
EYE = {"irides", "eyewhite", "eyelash", "eyebrow"}
MOUTH = {"mouth", "mouth_inside"}
SMALL = EYE | MOUTH | {"nose", "facedetail", "earwear"}  # inside the head close-up; no close-up of their own
MARGIN = 1.3
SIZE, MIN_SIDE = 1024, 160
ROLE_BITS = {"shadow": 1, "highlight": 2, "line": 4, "detail": 8}
MIN_INSTANCE = 0.0004  # of the close-up's pixels: an instance smaller than this is left out (24 pixels at least)
IGNORE = 65535
HELD = "caracal,mint,Ergosum/kiharu,灵魂潮汐国际服-倒闭二游/asuna,booth-6687553-weather-girl,booth-4499251-same-sister"


def mesh_pixels(folder: Path, scene: dict):
    """(mesh, x, y, pixels of alpha 128 and up) in draw order, as plan_data.py paints tags."""
    for m in sorted(scene["meshes"], key=lambda m: m["render"]):
        path = folder / "meshes" / f"{m['render']}.png"
        if "at" in m and path.exists() and m.get("opacity", 1) >= 0.05:
            yield m, *m["at"], np.asarray(Image.open(path).convert("RGBA"))[..., 3] >= 128


def paint(target: np.ndarray, x: int, y: int, mask: np.ndarray, value) -> None:
    region = target[y:y + mask.shape[0], x:x + mask.shape[1]]
    region[mask[:region.shape[0], :region.shape[1]]] = value


def visible(folder: Path, scene: dict):
    """Render index of the topmost mesh per pixel of full.png (-1: none)."""
    full = Image.open(folder / "full.png")
    top = np.full((full.height, full.width), -1, np.int32)
    for m, x, y, a in mesh_pixels(folder, scene):
        paint(top, x, y, a, m["render"])
    return top


def boxes(top):
    """render index -> (visible pixels, x0, y0, x1, y1) over a topmost-mesh map."""
    ys, xs = np.nonzero(top >= 0)
    r = top[ys, xs]
    n = int(r.max()) + 1 if len(r) else 0
    count = np.bincount(r, minlength=n)
    x0 = np.full(n, 1 << 30); y0 = np.full(n, 1 << 30); x1 = np.full(n, -1); y1 = np.full(n, -1)
    np.minimum.at(x0, r, xs); np.minimum.at(y0, r, ys); np.maximum.at(x1, r, xs + 1); np.maximum.at(y1, r, ys + 1)
    return {int(i): (int(count[i]), int(x0[i]), int(y0[i]), int(x1[i]), int(y1[i])) for i in np.nonzero(count)[0]}


def regions(scene: dict, tag: dict) -> dict[str, list[str]]:
    """Close-up name -> its meshes: "whole", "<tag>_<side>" for each big part, "head", "eye_l", "eye_r", "mouth"."""
    centre = face_centre(scene, tag)
    out = {"whole": [m["id"] for m in scene["meshes"]]}
    for m in scene["meshes"]:
        t = tag.get(m["id"])
        if not t or not m.get("box"):
            continue
        side = side_of(m, t, centre)
        names = [] if t in SMALL else [f"{t}_{side}"]
        if t in HEAD:
            names.append("head")
        if t in EYE:
            names.append(f"eye_{'l' if (m['box'][0] + m['box'][2]) / 2 < centre else 'r'}")
        if t in MOUTH:
            names.append("mouth")
        for n in names:
            out.setdefault(n, []).append(m["id"])
    return out


def part_of(group: str | None) -> str | None:
    """The tag and side a subnodes.py group name ("d_<tag>_<side>_<role>_...") was found under."""
    for t in sorted(CLASSES, key=len, reverse=True):
        if group and group.startswith(f"d_{t}_"):
            return f"{t}_{group[len(t) + 3:].split('_')[0]}"
    return None


def level(region: str) -> int:
    return 0 if region == "whole" else 2 if region in ("eye_l", "eye_r", "mouth") else 1


def frames(a) -> None:
    labels = Labels(a.root, a.index_dir, [Path(p) for p in a.verdicts.split(",")], [Path(p) for p in a.describe.split(",") if p])
    n = 0
    with open(a.out, "w", encoding="utf-8") as f:
        for folder in scene_folders(a.root, frozenset(filter(None, a.skip.split(",")))):
            scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
            by_render = {m["render"]: m["id"] for m in scene["meshes"]}
            stats = {by_render[r]: s for r, s in boxes(visible(folder, scene)).items() if r in by_render}
            rng = random.Random(f"{folder.parent.name}/{folder.name}")
            closeups = []
            for name, meshes in regions(scene, labels.tags(folder, scene)).items():
                shown = [m for m in meshes if m in stats]
                if not shown:
                    continue
                x0 = min(stats[m][1] for m in shown); y0 = min(stats[m][2] for m in shown)
                x1 = max(stats[m][3] for m in shown); y1 = max(stats[m][4] for m in shown)
                for v in range(1 if name == "whole" else 2):
                    grow = 1.05 if name == "whole" else MARGIN if v == 0 else rng.uniform(1.15, 2.0)
                    side = max(x1 - x0, y1 - y0) * grow + 4
                    shift = 0 if v == 0 else side * 0.08
                    cx = (x0 + x1) / 2 + rng.uniform(-1, 1) * shift
                    cy = (y0 + y1) / 2 + rng.uniform(-1, 1) * shift
                    s = scene["scale"]
                    closeups.append({"id": f"{name}_{v}", "frame": [round((cx - side / 2) / s, 2), round((cy - side / 2) / s, 2), round(side / s, 2)],
                                     "meshes": shown})
            f.write(json.dumps({"scene": folder.parent.name, "key": folder.name, "model": scene["model"], "closeups": closeups},
                               ensure_ascii=False) + "\n")
            n += 1
    print(n, "models")


def crop(array: np.ndarray, x: int, y: int, side: int, fill) -> np.ndarray:
    """array[y:y + side, x:x + side], filled with fill past its edges."""
    out = np.full((side, side) + array.shape[2:], fill, array.dtype)
    sx0, sy0 = max(0, x), max(0, y)
    sx1, sy1 = min(array.shape[1], x + side), min(array.shape[0], y + side)
    if sx1 > sx0 and sy1 > sy0:
        out[sy0 - y:sy1 - y, sx0 - x:sx1 - x] = array[sy0:sy1, sx0:sx1]
    return out


def export_model(job) -> int:
    folder, out, model, closeups, tag, rows, rules, units, groups, held = job
    scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
    full = np.asarray(Image.open(folder / "full.png").convert("RGBA"))
    H, W = full.shape[:2]
    centre = face_centre(scene, tag)
    role = lambda mesh: rows.get(mesh, {}).get("role") or rules.get(mesh, "base")
    # The whole render's instance (-1 none, -2 left out) and role maps; instances are the model's layers.
    inst = np.full((H, W), -1, np.int32)
    roles = np.zeros((H, W), np.uint8)
    # A layer cut into meshes takes the tag most of them have, and that mesh's side and group.
    members = {}
    for m in scene["meshes"]:
        members.setdefault(units.get(m["id"], m["id"]), []).append(m)
    lead = {}
    for u, ms in members.items():
        votes = Counter(tag.get(m["id"]) for m in ms if tag.get(m["id"]))
        lead[u] = next(m for m in ms if tag.get(m["id"]) == votes.most_common(1)[0][0]) if votes else ms[0]
    ids, table = {}, []
    for m, x, y, mask in mesh_pixels(folder, scene):
        mesh = m["id"]
        if rows.get(mesh, {}).get("fragment"):
            paint(inst, x, y, mask, -2)
        elif role(mesh) == "base":
            u = units.get(mesh, mesh)
            if u not in ids:
                ids[u] = len(table)
                first = lead[u]
                t = tag.get(first["id"])
                side = side_of(first, t, centre) if t and first.get("box") else "c"
                group = groups.get(first["id"]) or next((groups[o["id"]] for o in members[u] if o["id"] in groups), None)
                table.append({"tag": t, "side": side, "part": f"{t}_{side}" if t else part_of(group) or f"unit:{u}",
                              "group": group or (f"{t}_{side}" if t else f"unit:{u}"), "render": m["render"]})
            paint(inst, x, y, mask, ids[u])
            paint(roles, x, y, mask, 0)
        else:
            region = roles[y:y + mask.shape[0], x:x + mask.shape[1]]
            region[mask[:region.shape[0], :region.shape[1]]] |= ROLE_BITS.get(role(mesh), 0)
    inst[(full[..., 3] >= 16) & (inst == -1)] = -2  # only a role or a soft edge drawn here
    inst[full[..., 3] < 16] = -1
    lut = np.array([CLASSES.index(t["tag"]) if t["tag"] in CLASSES else 255 for t in table] + [255, 255], np.uint8)
    rng = random.Random(f"{folder.parent.name}/{folder.name}")
    written = 0
    for c in closeups:
        s = scene["scale"]
        x, y, side = round(c["frame"][0] * s), round(c["frame"][1] * s), round(c["frame"][2] * s)
        if side < MIN_SIDE:
            continue
        region = c["id"].rsplit("_", 1)[0]
        image = crop(full, x, y, side, 0)
        ci = crop(inst, x, y, side, -1)
        cr = crop(roles, x, y, side, 0)
        size = min(SIZE, side)
        if size < side:
            image = np.asarray(Image.fromarray(image).resize((size, size), Image.LANCZOS))
            ci = np.asarray(Image.fromarray(ci).resize((size, size), Image.NEAREST))
            cr = np.asarray(Image.fromarray(cr).resize((size, size), Image.NEAREST))
        cls = lut[ci]  # -1 and -2 index the two trailing 255s
        cls[ci == -1] = CLASSES.index("background")
        present, counts = np.unique(ci[ci >= 0], return_counts=True)
        keep = present[counts >= max(24, MIN_INSTANCE * size * size)]
        renum = np.full(len(table) + 2, IGNORE, np.int64)  # -1 and -2 land on the last two
        renum[keep] = np.arange(1, len(keep) + 1)
        labels = renum[ci].astype(np.uint16)
        labels[ci == -1] = 0
        stem = out / f"{folder.parent.name}_{folder.name}_{c['id']}"
        canvas = Image.new("RGBA", (size, size), tuple(rng.randint(200, 250) for _ in range(3)) + (255,))
        canvas.alpha_composite(Image.fromarray(image))
        canvas.convert("RGB").save(f"{stem}.jpg", quality=92)
        Image.fromarray(labels).save(f"{stem}_inst.png")
        Image.fromarray(cls).save(f"{stem}_cls.png")
        Image.fromarray(cr).save(f"{stem}_role.png")
        count = dict(zip(present.tolist(), counts.tolist()))
        meta = {"model": model, "region": region, "level": level(region), "held": held,
                "instances": [dict(table[k], pixels=count[k]) for k in keep.tolist()]}
        Path(f"{stem}.json").write_text(json.dumps(meta, ensure_ascii=False), encoding="utf-8")
        written += 1
    return written


def export(a) -> None:
    labels = Labels(a.root, a.index_dir, [Path(p) for p in a.verdicts.split(",")], [Path(p) for p in a.describe.split(",") if p])
    layers = read_units(a.layer_units)
    group_of = {}  # model -> mesh -> its named group of base pieces
    for path in a.subnodes.split(","):
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if line.strip():
                d = json.loads(line)
                if d.get("kind") == "group" and d.get("role", "base") == "base":
                    for mesh in d["meshes"]:
                        group_of.setdefault(d["model"], {}).setdefault(mesh, d["node"])
    held = [h for h in a.held.split(",") if h]
    held_models = set()
    for f in json.loads(a.families.read_text(encoding="utf-8")) if a.families else []:
        if any(any(h in m for h in held) for m in f["models"]):
            held_models.update(f["models"])
    framed = {}
    for line in a.frames.read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line)
            framed[(d["scene"], d["key"])] = d["closeups"]
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "classes.json").write_text(json.dumps(CLASSES), encoding="utf-8")
    jobs = []
    for folder in scene_folders(a.root, frozenset(filter(None, a.skip.split(",")))):
        closeups = framed.get((folder.parent.name, folder.name))
        if not closeups:
            continue
        scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
        model = scene["model"]
        rules = {k: v.get("role", "base") for k, v in json.loads((folder / "labels.json").read_text(encoding="utf-8"))["labels"].items()}
        jobs.append((folder, a.out, model, closeups, labels.tags(folder, scene), labels.rows.get((folder.parent.name, folder.name), {}),
                     rules, layers.get(model, {}).get("unit", {}), group_of.get(model, {}),
                     model in held_models or any(h in model for h in held)))
    with ProcessPoolExecutor(a.workers) as pool:
        n = list(pool.map(export_model, jobs, chunksize=2))
    print(len(n), "models,", sum(n), "close-ups")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    fr = sub.add_parser("frames")
    ex = sub.add_parser("export")
    for p in (fr, ex):
        p.add_argument("root", type=Path)
        p.add_argument("index_dir", type=Path)
        p.add_argument("verdicts")
        p.add_argument("describe")
    fr.add_argument("out", type=Path)
    ex.add_argument("layer_units", type=Path)
    ex.add_argument("subnodes")
    ex.add_argument("frames", type=Path)
    ex.add_argument("out", type=Path)
    ex.add_argument("--families", type=Path)
    ex.add_argument("--held", default=HELD)
    ex.add_argument("--workers", type=int, default=6)
    for p in (fr, ex):
        p.add_argument("--skip", default="scene_eik")
    a = ap.parse_args()
    (frames if a.cmd == "frames" else export)(a)


if __name__ == "__main__":
    main()
