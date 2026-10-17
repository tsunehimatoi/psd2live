"""Which label set agrees more with the review: per mesh, the reviewer's tag of the node holding it against the
rules' tag in labels_v1.json and labels.json.

  python compare_labels.py SCENE_ROOT NODES_INDEX VERDICTS...
"""
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from plan_data import PAIRED, SCENE_OF


def main(root: Path, index_dir: Path, verdict_paths) -> None:
    model_of, local = {}, {}
    for tsv in index_dir.glob("*.tsv"):
        for line in tsv.read_text(encoding="utf-8").splitlines()[1:]:
            k, model = line.split("\t")[:2]
            model_of[(tsv.stem, k)] = model
    for scene_dir in root.glob("scene*"):
        idx = scene_dir / "index.tsv"
        if idx.exists():
            for line in idx.read_text(encoding="utf-8").splitlines()[1:]:
                k, model = line.split("\t")[:2]
                local[(scene_dir.name, model)] = k
    reviews = defaultdict(dict)
    for path in verdict_paths:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            v = json.loads(line)
            if "_p" in v["node"] or not v["keep"]:
                continue
            model = model_of.get((v["dir"], v["key"]))
            scene = SCENE_OF.get(v["dir"])
            if model and (scene, model) in local:
                reviews[(scene, local[(scene, model)])][v["node"]] = v["tag"]
    right = Counter()
    confusion = {1: Counter(), 2: Counter()}
    for (scene, key), nodes in reviews.items():
        folder = root / scene / key
        sc = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
        old = json.loads((folder / "labels_v1.json").read_text(encoding="utf-8"))["labels"]
        new = json.loads((folder / "labels.json").read_text(encoding="utf-8"))["labels"]
        tag = {m["id"]: old[m["id"]]["tag"] for m in sc["meshes"] if old[m["id"]]["confidence"] >= 0.3 and m.get("opacity", 1) >= 0.05}
        faces = [m for m in sc["meshes"] if tag.get(m["id"]) == "face" and m.get("box")]
        centre = (min(m["box"][0] for m in faces) + max(m["box"][2] for m in faces)) / 2 if faces else sc["canvas"][0] / 2
        for m in sc["meshes"]:
            t = tag.get(m["id"])
            if t is None or not m.get("box"):
                continue
            side = ("l" if (m["box"][0] + m["box"][2]) / 2 < centre else "r") if t in PAIRED else "c"
            truth = nodes.get(f"{t}_{side}")
            if truth is None:
                continue
            right["n"] += 1
            for version, labels in ((1, old), (2, new)):
                got = labels[m["id"]]["tag"]
                if got == truth:
                    right[version] += 1
                else:
                    confusion[version][(truth, got)] += 1
    n = right["n"]
    print(f"{n} reviewed meshes; v1 (colour) agrees {right[1] / n:.3f}, v2 (occlusion) agrees {right[2] / n:.3f}")
    for version in (1, 2):
        print(f"v{version} errors:", confusion[version].most_common(10))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3:])
