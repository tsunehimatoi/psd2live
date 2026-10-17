"""Which meshes of a model are one drawn layer, and which layers are one picture laid in front of and behind something.

  python layer_units.py SCENE_ROOT GLUE_DIR OUT.jsonl

A Live2D author cuts a layer into meshes to move its parts apart (Cubism's skinning cuts a strand into one segment per
bone) and glues the cut edges. A cut keeps one picture, so the glued vertices sit at the same texture coordinates on
both sides; layers drawn apart and glued at a seam (a stocking and its shoe, line art and its colour) do not. So:

- glued meshes whose cut edge lies at one place on the texture (median UV gap under GAP) are one layer, unless
- something else is drawn between them that covers either of them (OVERLAP of the smaller one's pixels): then the
  picture was laid in front of and behind it (a sleeve around a hand, a strand passing behind the body), and the two
  sides stay apart as the front and back of one thing.

The meshes of a skinning part ("<layer>_Skinning", "<layer>(蒙皮)") are one layer too where glue says nothing. GLUE_DIR
holds GlueSurveyTool's tables of the family representatives; each family member takes its representative's units by
mesh ID. Writes one line per model: {"model", "units": [[mesh...]], "layered": [{"front", "back", "between"}]}, where
units lists the layers of more than one mesh and layered gives each such pair's units and the meshes between them.
"""
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

GAP = 5e-4
OVERLAP = 0.05
SKINNING = re.compile(r"(?i)(_skinning|蒙皮|スキニング)\)?$")


def glued(glue_dir: Path) -> dict[str, list[tuple[str, str, float]]]:
    """model -> (mesh A, mesh B, median UV gap) of each glue with vertex pairs."""
    out = defaultdict(list)
    for tsv in glue_dir.glob("*.tsv"):
        for line in tsv.read_text(encoding="utf-8").splitlines()[1:]:
            c = line.split("\t")
            if len(c) >= 15 and c[13]:
                out[c[0]].append((c[2], c[5], float(c[13])))
    return out


class Pixels:
    """Each mesh's pixels at rest (SceneGraphTool's meshes/<render>.png placed at "at")."""

    def __init__(self, folder: Path, scene: dict):
        self.folder, self.meshes, self.cache = folder, {m["id"]: m for m in scene["meshes"]}, {}

    def get(self, mesh: str):
        if mesh not in self.cache:
            m = self.meshes.get(mesh)
            path = self.folder / "meshes" / f"{m['render']}.png" if m else None
            self.cache[mesh] = (np.asarray(Image.open(path))[..., 3] > 64, *m["at"]) if path and path.exists() and "at" in m else None
        return self.cache[mesh]

    def overlap(self, a: str, b: str) -> int:
        A, B = self.get(a), self.get(b)
        if A is None or B is None:
            return 0
        (pa, ax, ay), (pb, bx, by) = A, B
        x0, y0 = max(ax, bx), max(ay, by)
        x1, y1 = min(ax + pa.shape[1], bx + pb.shape[1]), min(ay + pa.shape[0], by + pb.shape[0])
        return 0 if x1 <= x0 or y1 <= y0 else int((pa[y0 - ay:y1 - ay, x0 - ax:x1 - ax] & pb[y0 - by:y1 - by, x0 - bx:x1 - bx]).sum())

    def size(self, mesh: str) -> int:
        p = self.get(mesh)
        return int(p[0].sum()) if p else 0


def units_of(folder: Path, scene: dict, glues: list[tuple[str, str, float]]):
    meshes = {m["id"]: m for m in scene["meshes"]}
    names = {p["id"]: p["name"] for p in scene["parts"]}
    pixels = Pixels(folder, scene)
    root = {}

    def find(x):
        while root.get(x, x) != x:
            x = root[x]
        return x

    def join(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            root[ra] = rb

    layered = []
    for a, b, gap in glues:
        if a not in meshes or b not in meshes or gap >= GAP:
            continue  # drawn apart: separate layers
        lo, hi = sorted([meshes[a]["render"], meshes[b]["render"]])
        small = max(1, min(pixels.size(a), pixels.size(b)))
        between = [m["id"] for m in scene["meshes"] if lo < m["render"] < hi and m.get("opacity", 1) >= 0.05
                   and m.get("part") not in (meshes[a].get("part"), meshes[b].get("part"))
                   and max(pixels.overlap(m["id"], a), pixels.overlap(m["id"], b)) >= OVERLAP * small]
        if between:
            front, back = (a, b) if meshes[a]["render"] > meshes[b]["render"] else (b, a)
            layered.append((front, back, between))
        else:
            join(a, b)
    by_part = defaultdict(list)
    for m in scene["meshes"]:
        if SKINNING.search(names.get(m.get("part"), "").strip()):
            by_part[m["part"]].append(m["id"])
    apart = {frozenset((f, b)) for f, b, _ in layered}
    for ids in by_part.values():
        for i in ids[1:]:
            if frozenset((ids[0], i)) not in apart:
                join(ids[0], i)
    groups = defaultdict(list)
    for x in set(root) | set(root.values()):
        groups[find(x)].append(x)
    unit = {x: find(x) for x in set(root) | set(root.values())}
    members = lambda x: sorted(groups[unit[x]]) if x in unit else [x]
    pairs = []
    for front, back, between in layered:
        if unit.get(front, front) != unit.get(back, back):
            pairs.append({"front": members(front), "back": members(back), "between": sorted(between)})
    return [sorted(g) for g in groups.values() if len(g) > 1], pairs


def read(path: Path | None) -> dict[str, dict]:
    """model -> {"unit": mesh -> its layer's first mesh, "members": layer -> meshes, "layered": [...]} from OUT.jsonl."""
    out = {}
    for line in (path.read_text(encoding="utf-8").splitlines() if path else []):
        if line.strip():
            d = json.loads(line)
            unit = {m: u[0] for u in d["units"] for m in u}
            out[d["model"]] = {"unit": unit, "members": {u[0]: u for u in d["units"]}, "layered": d["layered"]}
    return out


def main(root: Path, glue_dir: Path, out: Path) -> None:
    glues = glued(glue_dir)
    families = json.loads((root / "families.json").read_text(encoding="utf-8"))
    model_of = {}
    for sc in root.glob("scene*"):
        idx = sc / "index.tsv"
        if idx.exists():
            for line in idx.read_text(encoding="utf-8").splitlines()[1:]:
                k, m = line.split("\t")[:2]
                model_of[f"{sc.name}/{k}"] = m
    written = joined = apart = 0
    with open(out, "w", encoding="utf-8") as f:
        for family in families:
            rep = family["members"][0]
            if rep not in model_of or model_of[rep] not in glues and not os.path.exists(root / rep / "scene.json"):
                continue
            scene = json.loads((root / rep / "scene.json").read_text(encoding="utf-8"))
            units, layered = units_of(root / rep, scene, glues.get(model_of[rep], []))
            for member in family["members"]:
                if member not in model_of or not (root / member / "scene.json").exists():
                    continue
                own = {m["id"] for m in json.loads((root / member / "scene.json").read_text(encoding="utf-8"))["meshes"]}
                mu = [u for u in ([i for i in u if i in own] for u in units) if len(u) > 1]
                ml = [{k: [i for i in v if i in own] for k, v in p.items()} for p in layered]
                ml = [p for p in ml if p["front"] and p["back"]]
                f.write(json.dumps({"model": model_of[member], "units": mu, "layered": ml}, ensure_ascii=False) + "\n")
                written += 1
                if member == rep:
                    joined += len(units)
                    apart += len(layered)
    print(written, "models;", joined, "cut layers and", apart, "front-and-back pairs among the representatives")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
