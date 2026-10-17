"""Finer nodes from the reviewer's names: a skirt's lace trim, the jacket's shadow, a sleeve cuff, one strand of the
front hair, each prompted in the reviewer's words.

  python subnodes.py SCENE_ROOT DESCRIBE.jsonl OUT.jsonl [PER_GROUP]

DESCRIBE.jsonl is describe.py's collect output. Within a model, meshes whose names agree once synonyms are folded
(lock, tuft and strand; bangs and front hair; shade and shadow) make a group per tag, role and side:
- the group as a whole is a node ("the skirt frills"), unless it holds most of its tag's pixels, which the tag's own
  node already covers;
- a group of three or more base pieces (strands, fingers, panels) also gives its PER_GROUP (3) largest meshes as
  nodes of their own ("the front hair strand"); the hint, the node's visible area, tells them apart.
Guesses (sure false) are kept: they are mostly about which tag a piece falls under, and the prompt is the name. Writes one line per node for NodeDatasetTool
(SUBNODES): {"model", "node", "tag", "role", "prompt", "meshes"}, each model's nodes in a fixed shuffled order.
"""
import json
import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from make_node_pairs import PHRASES
from plan_data import PAIRED

SIDE = re.compile(r"\b(left|right|l|r)\b")
ROLES = {"base", "shadow", "highlight", "line", "detail"}
WHOLE = 0.7  # a group with this share of its tag's pixels is the tag itself
SYNONYMS = [(r"\b(lock|tuft|strand)s?\b", "strand"), (r"\b(shade|shading)\b", "shadow"), (r"\bshine\b", "highlight"),
            (r"\blineart\b", "line"), (r"\bskin\b", "")]


def clean(part: str) -> str:
    return re.sub(r"\s+", " ", SIDE.sub("", part.lower())).strip(" ,.-")


def key(tag: str, part: str) -> str:
    """The name with synonyms folded, for grouping only (prompts keep the reviewer's words)."""
    k = clean(part)
    if tag == "front_hair":
        k = re.sub(r"\b(front hair )?(bangs?|fringe)\b", "front hair", k)
    for pattern, word in SYNONYMS:
        k = re.sub(pattern, word, k)
    words = k.split()
    if words and len(words[-1]) > 3 and words[-1].endswith("s") and not words[-1].endswith("ss"):
        words[-1] = words[-1][:-1]
    return " ".join(words)


def plural(phrase: str) -> str:
    return phrase if phrase.endswith("s") else phrase + "s"


def main(root: Path, describe: Path, out: Path, per_group: int = 3) -> None:
    models = {}
    for scene_dir in root.glob("scene*"):
        idx = scene_dir / "index.tsv"
        if idx.exists():
            for line in idx.read_text(encoding="utf-8").splitlines()[1:]:
                k, model = line.split("\t")[:2]
                models[(scene_dir.name, k)] = model
    by_model = defaultdict(list)
    for line in describe.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        if r.get("tag") not in PHRASES or not (r.get("part") or "").strip() or r.get("role") not in ROLES:
            continue
        by_model[(r["scene"], r["key"])].append(r)
    written = Counter()
    with open(out, "w", encoding="utf-8") as f:
        for (scene, key_), rows in sorted(by_model.items()):
            model = models.get((scene, key_))
            if model is None:
                continue
            meshes = {m["id"]: m for m in json.loads((root / scene / key_ / "scene.json").read_text(encoding="utf-8"))["meshes"]
                      if m.get("box")}
            rows = [r for r in rows if r["mesh"] in meshes]
            solid = {r["mesh"]: meshes[r["mesh"]].get("look", {}).get("solid", 0) for r in rows}
            faces = [meshes[r["mesh"]]["box"] for r in rows if r["tag"] == "face"]
            centre = (min(b[0] for b in faces) + max(b[2] for b in faces)) / 2 if faces else None

            def side(r):
                if r["tag"] not in PAIRED or centre is None:
                    return "c"
                b = meshes[r["mesh"]]["box"]
                return "l" if (b[0] + b[2]) / 2 < centre else "r"

            tag_pixels = Counter()
            groups = defaultdict(list)
            for r in rows:
                s = side(r)
                tag_pixels[(r["tag"], s)] += solid[r["mesh"]]
                groups[(r["tag"], r["role"], key(r["tag"], r["part"]), s)].append(r)
            nodes = []
            for (tag, role, k, s), members in sorted(groups.items()):
                if not k:
                    continue
                name = Counter(clean(m["part"]) for m in members).most_common(1)[0][0]
                details = Counter(m["detail"].strip() for m in members if (m.get("detail") or "").strip())
                detail = details.most_common(1)[0][0] if details else ""
                slug = re.sub(r"[^a-z0-9]+", "_", k)[:40].strip("_")
                ids = sorted(m["mesh"] for m in members)
                pixels = sum(solid[i] for i in ids)
                if pixels < WHOLE * tag_pixels[(tag, s)]:
                    words = f"the {plural(name) if len(ids) > 1 else name}" + (f", {detail}" if detail else "")
                    nodes.append({"model": model, "node": f"d_{tag}_{s}_{role}_{slug}", "tag": tag, "role": role,
                                  "prompt": words, "meshes": ids})
                if role == "base" and len(ids) >= 3:
                    for m in sorted(members, key=lambda m: (-solid[m["mesh"]], m["mesh"]))[:per_group]:
                        own = (m.get("detail") or "").strip()
                        nodes.append({"model": model, "node": f"d_{tag}_{s}_{role}_{slug}_{m['mesh']}", "tag": tag, "role": role,
                                      "prompt": f"the {clean(m['part'])}" + (f", {own}" if own else ""), "meshes": [m["mesh"]]})
            random.Random(model).shuffle(nodes)
            for n in nodes:
                f.write(json.dumps(n, ensure_ascii=False) + "\n")
                written["single" if len(n["meshes"]) == 1 else "group"] += 1
                written[n["role"]] += 1
    print(sum(written[k] for k in ("single", "group")), "finer nodes", dict(written))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), *(int(a) for a in sys.argv[4:5]))
