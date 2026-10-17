"""Finer nodes from the reviewer's names: within a model, the meshes a reviewer called the same piece (a skirt's lace
trim, the jacket's shadow, a sleeve cuff) make one node, prompted in the reviewer's words.

  python subnodes.py SCENE_ROOT DESCRIBE.jsonl OUT.jsonl

DESCRIBE.jsonl is describe.py's collect output. Writes one line per node for NodeDatasetTool (SUBNODES):
{"model", "node", "tag", "prompt", "meshes"}; a node equal to a whole tag's meshes is dropped there.
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from make_node_pairs import PHRASES
from plan_data import PAIRED

SIDE = re.compile(r"\b(left|right|l|r)\b\s*")


def norm(part: str) -> str:
    return re.sub(r"\s+", " ", SIDE.sub("", part.lower())).strip(" ,.")


def main(root: Path, describe: Path, out: Path) -> None:
    models = {}
    for scene_dir in root.glob("scene*"):
        idx = scene_dir / "index.tsv"
        if idx.exists():
            for line in idx.read_text(encoding="utf-8").splitlines()[1:]:
                k, model = line.split("\t")[:2]
                models[(scene_dir.name, k)] = model
    by_model = defaultdict(list)
    for line in describe.read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            if r.get("tag") in PHRASES and r.get("part"):
                by_model[(r["scene"], r["key"])].append(r)
    written = 0
    roles = Counter()
    with open(out, "w", encoding="utf-8") as f:
        for (scene, key), rows in sorted(by_model.items()):
            model = models.get((scene, key))
            if model is None:
                continue
            boxes = {m["id"]: m["box"] for m in json.loads((root / scene / key / "scene.json").read_text(encoding="utf-8"))["meshes"] if m.get("box")}
            faces = [boxes[r["mesh"]] for r in rows if r["tag"] == "face" and r["mesh"] in boxes]
            centre = (min(b[0] for b in faces) + max(b[2] for b in faces)) / 2 if faces else None
            groups = defaultdict(list)
            for r in rows:
                side = "c"
                if r["tag"] in PAIRED and centre is not None and r["mesh"] in boxes:
                    b = boxes[r["mesh"]]
                    side = "l" if (b[0] + b[2]) / 2 < centre else "r"
                groups[(r["tag"], norm(r["part"]), side)].append(r)
            for (tag, part, side), members in sorted(groups.items()):
                details = Counter(m["detail"].strip() for m in members if m.get("detail") and m["detail"].strip())
                detail = details.most_common(1)[0][0] if details else ""
                role = Counter(m.get("role") for m in members).most_common(1)[0][0]
                words = f"the {part}" + (f", {detail}" if detail else "")
                slug = re.sub(r"[^a-z0-9]+", "_", part)[:40].strip("_")
                f.write(json.dumps({"model": model, "node": f"d_{tag}_{side}_{slug}", "tag": tag, "role": role, "prompt": words,
                                    "meshes": sorted(m["mesh"] for m in members)}, ensure_ascii=False) + "\n")
                written += 1
                roles[role] += 1
    print(written, "finer nodes", dict(roles))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
