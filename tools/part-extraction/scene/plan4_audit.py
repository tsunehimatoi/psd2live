"""Planner v4 data audit: how large each big part's close-up is in the rest renders and how many layers it holds.

  python plan4_audit.py ROOT NODES_INDEX VERDICTS[,...] DESCRIBE[,...] LAYER_UNITS.jsonl SUBNODES[,...] OUT.jsonl [--skip DIR,...]

A big part is what the first-level planner finds: a tag, paired tags by side of the face, and the head (face, its
features and the ears together), the close-up the second level reads. Its close-up is the square 1.3 times the longer
side of its visible pixels, as split_image.py frames it. For each one this writes the close-up's side in pixels of the
rest render (full.png, longer side 2048) and in canvas units, and the layers (layer_units.py: one drawn layer, however
many meshes it was cut into) it shows: base layers of the part itself with their visible pixels, other layers in the
close-up, roles layered on the part (shadow, highlight, line, detail), and the reviewer's named groups among them.
Fragments are not layers. One line per big part.
"""
import argparse
import json
import sys
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from plan_data import Labels, PAIRED, face_centre, scene_folders, side_of
from layer_units import read as read_units
from plan4_data import HEAD, boxes, visible

MARGIN = 1.3


def audit(job):
    folder, tag, rows, units, groups = job
    scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
    by_render = {m["render"]: m for m in scene["meshes"]}
    top = visible(folder, scene)
    unit = lambda mesh: units.get(mesh, mesh)
    role = lambda mesh: rows.get(mesh, {}).get("role", "base")
    fragment = lambda mesh: bool(rows.get(mesh, {}).get("fragment"))
    centre = face_centre(scene, tag)
    region_of = {}
    for m in scene["meshes"]:
        t = tag.get(m["id"])
        if t and m.get("box"):
            region_of[m["id"]] = [f"{t}_{side_of(m, t, centre)}"] + (["head"] if t in HEAD else [])
    # Visible pixels per mesh: count and box.
    stats = {m["id"]: s for m, s in ((by_render[r], s) for r, s in boxes(top).items())}
    regions = defaultdict(list)
    for mesh, names in region_of.items():
        if mesh in stats:
            for n in names:
                regions[n].append(mesh)
    out = []
    H, W = top.shape
    for name, meshes in regions.items():
        x0 = min(stats[m][1] for m in meshes); y0 = min(stats[m][2] for m in meshes)
        x1 = max(stats[m][3] for m in meshes); y1 = max(stats[m][4] for m in meshes)
        span = MARGIN * max(x1 - x0, y1 - y0)
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        fx0, fy0 = int(max(0, cx - span / 2)), int(max(0, cy - span / 2))
        crop = top[fy0:int(min(H, cy + span / 2)), fx0:int(min(W, cx + span / 2))]
        r, n = np.unique(crop[crop >= 0], return_counts=True)
        inside = {by_render[int(k)]["id"]: int(c) for k, c in zip(r, n)}
        own = set(meshes)
        base, other, roles, named = defaultdict(int), defaultdict(int), defaultdict(int), set()
        for mesh, c in inside.items():
            if fragment(mesh):
                continue
            if mesh in own and role(mesh) != "base":
                roles[role(mesh)] += 1
            elif mesh in own:
                base[unit(mesh)] += c
            else:
                other[unit(mesh)] += c
            if mesh in own and mesh in groups:
                named.add(groups[mesh])
        out.append({"model": scene["model"], "scene": folder.parent.name, "key": folder.name, "region": name,
                    "span": round(span), "canvas_span": round(span / scene["scale"]), "scale": scene["scale"],
                    "base": sorted(base.values(), reverse=True), "other": len(other), "roles": dict(roles), "groups": len(named)})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=Path)
    ap.add_argument("index_dir", type=Path)
    ap.add_argument("verdicts")
    ap.add_argument("describe")
    ap.add_argument("layer_units", type=Path)
    ap.add_argument("subnodes")
    ap.add_argument("out", type=Path)
    ap.add_argument("--skip", default="scene_eik")
    ap.add_argument("--workers", type=int, default=12)
    a = ap.parse_args()
    describe = [Path(p) for p in a.describe.split(",") if p]
    labels = Labels(a.root, a.index_dir, [Path(p) for p in a.verdicts.split(",")], describe)
    layers = read_units(a.layer_units)
    group_of = defaultdict(dict)  # model -> mesh -> its named group (subnodes.py kind group)
    for path in a.subnodes.split(","):
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if line.strip():
                d = json.loads(line)
                if d.get("kind") == "group":
                    for mesh in d["meshes"]:
                        group_of[d["model"]].setdefault(mesh, d["node"])
    jobs = []
    for folder in scene_folders(a.root, frozenset(filter(None, a.skip.split(",")))):
        scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
        model = scene["model"]
        jobs.append((folder, labels.tags(folder, scene), labels.rows.get((folder.parent.name, folder.name), {}),
                     layers.get(model, {}).get("unit", {}), group_of.get(model, {})))
    n = 0
    with open(a.out, "w", encoding="utf-8") as f, ProcessPoolExecutor(a.workers) as pool:
        for rows in pool.map(audit, jobs, chunksize=2):
            for row in rows:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
            n += 1
    print(n, "models")


if __name__ == "__main__":
    main()
