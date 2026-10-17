"""Planner training data: each model at rest and its per-pixel tag map, labels corrected by the node review.

  python plan_data.py SCENE_ROOT NODES_INDEX VERDICTS[,VERDICTS...] OUT [SIZE] [--describe D.jsonl[,D.jsonl...]] [--skip DIR,...]

SCENE_ROOT holds scene*/<key>/{scene.json,labels.json,full.png,meshes/} (SceneGraphTool, label.py); NODES_INDEX holds
<nodes dir>.tsv (NodeDatasetTool's index.tsv from the machine that made the nodes, mapping its keys to models);
VERDICTS is review_nodes.py's jsonl. Writes OUT/<scene dir>_<key>.jpg (the model on a random light background,
longer side SIZE, 1024) and .png (tag index per pixel, 255 where unknown or rejected) plus OUT/classes.json.

A mesh takes its rules tag (confidence 0.3 and up, as the nodes did); a reviewed whole-tag node relabels or rejects
the meshes it holds, found as NodeDatasetTool found them (tag, and side of the face for paired tags). A mesh a reviewer
described one by one (describe.py collect) takes that tag instead, unless the reviewer was guessing against the rules,
which leaves it out, and so does a fragment (a scrap cut off a bigger piece). --skip leaves whole scene dirs out.
"""
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from make_node_pairs import PHRASES

CLASSES = sorted(PHRASES) + ["background"]  # background: where the model leaves the canvas empty
PAIRED = {"irides", "eyewhite", "eyelash", "eyebrow", "ears", "earwear", "arm", "hand", "leg", "handwear", "legwear", "footwear"}
SCENE_OF = {"nodes": "scene", "nodes_eik": "scene_eik", "nodes_kis": "scene_kis", "nodes_col": "scene_col"}


class Labels:
    """Each mesh's tag in a scene folder: rules, then the node review, then the per-mesh descriptions (None: unknown)."""

    def __init__(self, root: Path, index_dir: Path, verdict_paths: list[Path], describe: list[Path] = ()):
        # Server node keys -> model paths -> local scene keys.
        model_of = {}
        for tsv in index_dir.glob("*.tsv"):
            for line in tsv.read_text(encoding="utf-8").splitlines()[1:]:
                k, model = line.split("	")[:2]
                model_of[(tsv.stem, k)] = model
        local = {}
        for scene_dir in root.glob("scene*"):
            idx = scene_dir / "index.tsv"
            if idx.exists():
                for line in idx.read_text(encoding="utf-8").splitlines()[1:]:
                    k, model = line.split("	")[:2]
                    local[(scene_dir.name, model)] = k
        self.reviews = defaultdict(dict)  # (scene dir, local key) -> node id -> verdict
        lines = [l for p in verdict_paths for l in p.read_text(encoding="utf-8").splitlines()]  # later files win
        for line in lines:
            if not line.strip():
                continue
            v = json.loads(line)
            if "_p" in v["node"]:
                continue  # pieces: the whole-tag node speaks for its meshes
            model = model_of.get((v["dir"], v["key"]))
            scene = SCENE_OF.get(v["dir"])
            if model and scene and (scene, model) in local:
                self.reviews[(scene, local[(scene, model)])][v["node"]] = v
        self.described = defaultdict(dict)  # (scene dir, key) -> mesh -> tag, or None where the reviewer guessed against the rules
        self.rows = defaultdict(dict)  # (scene dir, key) -> mesh -> the reviewer's row
        for line in (l for path in describe for l in path.read_text(encoding="utf-8").splitlines()):
            if line.strip():
                d = json.loads(line)
                guess = d.get("sure") is False and d.get("tag") != d.get("rules") or d.get("fragment")
                self.described[(d["scene"], d["key"])][d["mesh"]] = None if guess or d.get("tag") not in PHRASES else d["tag"]
                self.rows[(d["scene"], d["key"])][d["mesh"]] = d

    def tags(self, folder: Path, scene: dict) -> dict:
        """mesh -> tag (None or absent: unknown) for the model in folder."""
        labels = json.loads((folder / "labels.json").read_text(encoding="utf-8"))["labels"]
        review = self.reviews.get((folder.parent.name, folder.name), {})
        tag = {}
        for m in scene["meshes"]:
            lab = labels[m["id"]]
            if lab["confidence"] >= 0.3 and lab["tag"] in PHRASES and m.get("opacity", 1) >= 0.05:
                tag[m["id"]] = lab["tag"]
        centre = face_centre(scene, tag)
        for m in scene["meshes"]:
            t = tag.get(m["id"])
            if t is None or not m.get("box"):
                continue
            v = review.get(f"{t}_{side_of(m, t, centre)}")
            if v is not None:
                # Per pixel, a node that was not clean as a whole is still mostly right mesh by mesh: the reviewer's tag
                # counts when it was kept or agrees with the rules; a disputed one is left out.
                agreed = v["keep"] or v["tag"] == v["rules"]
                tag[m["id"]] = v["tag"] if agreed and v["tag"] in PHRASES else None
        for mesh, t in self.described.get((folder.parent.name, folder.name), {}).items():
            tag[mesh] = t
        return tag


def face_centre(scene: dict, tag: dict) -> float:
    faces = [m for m in scene["meshes"] if tag.get(m["id"]) == "face" and m.get("box")]
    return (min(m["box"][0] for m in faces) + max(m["box"][2] for m in faces)) / 2 if faces else scene["canvas"][0] / 2


def side_of(mesh: dict, tag: str, centre: float) -> str:
    return ("l" if (mesh["box"][0] + mesh["box"][2]) / 2 < centre else "r") if tag in PAIRED else "c"


def scene_folders(root: Path, skip: frozenset = frozenset()):
    for folder in sorted(root.glob("scene*/*/")):
        if folder.parent.name != "trial" and folder.parent.name not in skip and (folder / "labels.json").exists():
            yield folder


def main(root: Path, index_dir: Path, verdict_paths: list[Path], out: Path, size: int = 1024, describe: list[Path] = (),
         skip: frozenset = frozenset()) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "classes.json").write_text(json.dumps(CLASSES), encoding="utf-8")
    labels = Labels(root, index_dir, verdict_paths, describe)
    rng = random.Random(0)
    n = 0
    for folder in scene_folders(root, skip):
        scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
        tag = labels.tags(folder, scene)
        full = Image.open(folder / "full.png").convert("RGBA")
        classes = np.full((full.height, full.width), 255, np.uint8)
        for m in sorted(scene["meshes"], key=lambda m: m["render"]):
            path = folder / "meshes" / f"{m['render']}.png"
            if "at" not in m or not path.exists() or m.get("opacity", 1) < 0.05:
                continue
            a = np.asarray(Image.open(path).convert("RGBA"))[..., 3] >= 128
            x, y = m["at"]
            region = classes[y:y + a.shape[0], x:x + a.shape[1]]
            a = a[:region.shape[0], :region.shape[1]]
            t = tag.get(m["id"])
            region[a] = CLASSES.index(t) if t else 255
        if (classes != 255).mean() < 0.02:
            continue
        classes[np.asarray(full)[..., 3] < 16] = CLASSES.index("background")
        scale = size / max(full.size)
        w, h = int(full.width * scale), int(full.height * scale)
        bg = Image.new("RGBA", full.size, tuple(rng.randint(200, 250) for _ in range(3)) + (255,))
        bg.alpha_composite(full)
        stem = f"{folder.parent.name}_{folder.name}"
        bg.convert("RGB").resize((w, h), Image.LANCZOS).save(out / f"{stem}.jpg", quality=92)
        Image.fromarray(classes).resize((w, h), Image.NEAREST).save(out / f"{stem}.png")
        (out / f"{stem}.txt").write_text(scene["model"], encoding="utf-8")
        n += 1
    print(n, "models written")


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("root", type=Path)
    ap.add_argument("index_dir", type=Path)
    ap.add_argument("verdicts")
    ap.add_argument("out", type=Path)
    ap.add_argument("size", type=int, nargs="?", default=1024)
    ap.add_argument("--describe", default="")
    ap.add_argument("--skip", default="")
    a = ap.parse_args()
    main(a.root, a.index_dir, [Path(p) for p in a.verdicts.split(",")], a.out, a.size, [Path(p) for p in a.describe.split(",") if p], frozenset(filter(None, a.skip.split(","))))
