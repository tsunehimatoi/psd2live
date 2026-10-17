"""Planner training data: each model at rest and its per-pixel tag map, labels corrected by the node review.

  python plan_data.py SCENE_ROOT NODES_INDEX VERDICTS[,VERDICTS...] OUT [SIZE]

SCENE_ROOT holds scene*/<key>/{scene.json,labels.json,full.png,meshes/} (SceneGraphTool, label.py); NODES_INDEX holds
<nodes dir>.tsv (NodeDatasetTool's index.tsv from the machine that made the nodes, mapping its keys to models);
VERDICTS is review_nodes.py's jsonl. Writes OUT/<scene dir>_<key>.jpg (the model on a random light background,
longer side SIZE, 1024) and .png (tag index per pixel, 255 where unknown or rejected) plus OUT/classes.json.

A mesh takes its rules tag (confidence 0.3 and up, as the nodes did); a reviewed whole-tag node relabels or rejects
the meshes it holds, found as NodeDatasetTool found them (tag, and side of the face for paired tags).
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

CLASSES = sorted(PHRASES)
PAIRED = {"irides", "eyewhite", "eyelash", "eyebrow", "ears", "earwear", "arm", "hand", "leg", "handwear", "legwear", "footwear"}
SCENE_OF = {"nodes": "scene", "nodes_eik": "scene_eik", "nodes_kis": "scene_kis", "nodes_col": "scene_col"}


def main(root: Path, index_dir: Path, verdict_paths: list[Path], out: Path, size: int = 1024) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "classes.json").write_text(json.dumps(CLASSES), encoding="utf-8")
    # Server node keys -> model paths -> local scene keys.
    model_of = {}
    for tsv in index_dir.glob("*.tsv"):
        for line in tsv.read_text(encoding="utf-8").splitlines()[1:]:
            k, model = line.split("\t")[:2]
            model_of[(tsv.stem, k)] = model
    local = {}
    for scene_dir in root.glob("scene*"):
        idx = scene_dir / "index.tsv"
        if idx.exists():
            for line in idx.read_text(encoding="utf-8").splitlines()[1:]:
                k, model = line.split("\t")[:2]
                local[(scene_dir.name, model)] = k
    reviews = defaultdict(dict)  # (scene dir, local key) -> node id -> verdict
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
            reviews[(scene, local[(scene, model)])][v["node"]] = v
    rng = random.Random(0)
    n = 0
    for folder in sorted(root.glob("scene*/*/")):
        if folder.parent.name == "trial" or not (folder / "labels.json").exists():
            continue
        scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
        labels = json.loads((folder / "labels.json").read_text(encoding="utf-8"))["labels"]
        review = reviews.get((folder.parent.name, folder.name), {})
        tag = {}
        for m in scene["meshes"]:
            lab = labels[m["id"]]
            if lab["confidence"] >= 0.3 and lab["tag"] in PHRASES and m.get("opacity", 1) >= 0.05:
                tag[m["id"]] = lab["tag"]
        faces = [m for m in scene["meshes"] if tag.get(m["id"]) == "face" and m.get("box")]
        centre = (min(m["box"][0] for m in faces) + max(m["box"][2] for m in faces)) / 2 if faces else scene["canvas"][0] / 2
        for m in scene["meshes"]:
            t = tag.get(m["id"])
            if t is None or not m.get("box"):
                continue
            side = ("l" if (m["box"][0] + m["box"][2]) / 2 < centre else "r") if t in PAIRED else "c"
            v = review.get(f"{t}_{side}")
            if v is not None:
                # Per pixel, a node that was not clean as a whole is still mostly right mesh by mesh: the reviewer's tag
                # counts when it was kept or agrees with the rules; a disputed one is left out.
                agreed = v["keep"] or v["tag"] == v["rules"]
                tag[m["id"]] = v["tag"] if agreed and v["tag"] in PHRASES else None
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
    main(Path(sys.argv[1]), Path(sys.argv[2]), [Path(p) for p in sys.argv[3].split(",")], Path(sys.argv[4]), *(int(a) for a in sys.argv[5:6]))
