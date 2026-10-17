"""Labels from the reviewer's per-mesh tags, so nodes are grouped as the reviewer saw the meshes rather than by rules.

  python describe_labels.py SCENE_ROOT FAMILIES.json DESCRIBE.jsonl[,DESCRIBE.jsonl...] [GLUE_DIR]

For every family whose representative was described (describe.py collect), each member's labels.json is written
again as labels_described.json ("source": "describe"): a mesh the representative's reviewer tagged takes that tag
(meshes share their IDs across a family), its guesses too (the rules are wrong more often, and a mesh left out
would leave a hole in its part), and the rest (meshes too small to describe, or not in the representative) keep
their rules label. NodeDatasetTool reads it with PARTS_LABELS=labels_described.json and marks its samples
"described", which make_node_pairs.py counts as reviewed. A mesh the reviewer called a fragment (a tiny scrap cut off a
bigger piece: a joint patch, a sliver, a corner) is left out of every node for now. The segments Cubism's skinning cut
one drawn layer into take that layer's most common tag together, fragments and undescribed ones too (layer_units:
meshes glued within one part, with GLUE_DIR the GlueSurveyTool tables of the representatives, and skinning parts).
"""
import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from make_node_pairs import PHRASES


SKINNING = re.compile(r"(?i)(_skinning|蒙皮|スキニング)\)?$")


def glue_pairs(glue_dir: Path | None) -> dict[str, list[tuple[str, str]]]:
    """model path -> glued mesh pairs, from GlueSurveyTool's tables."""
    pairs = {}
    for tsv in (glue_dir.glob("*.tsv") if glue_dir else []):
        for line in tsv.read_text(encoding="utf-8").splitlines()[1:]:
            c = line.split("	")
            if len(c) >= 7 and c[1] not in ("none", "error"):
                pairs.setdefault(c[0], []).append((c[2], c[5]))
    return pairs


def layer_units(scene: dict, glued: list[tuple[str, str]] = ()) -> dict[str, str]:
    """mesh -> its layer, for meshes that are one drawn layer cut apart: glued together within one part (Cubism's
    skinning glues the segments it cuts a layer into, one per bone; authors glue layers they cut by hand), or under
    a skinning part ("<layer>_Skinning", "<layer>(蒙皮)"). Glue across parts joins layers at a seam and does not count."""
    part = {m["id"]: m.get("part") for m in scene["meshes"]}
    names = {p["id"]: p["name"] for p in scene["parts"]}
    root = {}
    def find(x):
        while root.get(x, x) != x:
            x = root[x]
        return x
    def join(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            root[ra] = rb
    for a, b in glued:
        if a in part and b in part and part[a] is not None and part[a] == part[b]:
            join(a, b)
    by_part = {}
    for m in scene["meshes"]:
        if SKINNING.search(names.get(m.get("part"), "").strip()):
            by_part.setdefault(m["part"], []).append(m["id"])
    for ids in by_part.values():
        for i in ids[1:]:
            join(ids[0], i)
    return {x: find(x) for x in set(root) | set(root.values())}


def main(root: Path, families: Path, describe: list[Path], glue_dir: Path | None = None) -> None:
    glued = glue_pairs(glue_dir)
    model_of = {}
    for sc in root.glob("scene*"):
        idx = sc / "index.tsv"
        if idx.exists():
            for line in idx.read_text(encoding="utf-8").splitlines()[1:]:
                k, m = line.split("	")[:2]
                model_of[(sc.name, k)] = m
    tags = {}  # (scene, key) -> mesh -> tag, or None (unknown, effect, background) to leave the mesh out
    for line in (l for path in describe for l in path.read_text(encoding="utf-8").splitlines()):
        if line.strip():
            d = json.loads(line)
            keep = d.get("tag") in PHRASES and not d.get("fragment")
            tags.setdefault((d["scene"], d["key"]), {})[d["mesh"]] = d["tag"] if keep else None
    stats = Counter()
    for family in json.loads(families.read_text(encoding="utf-8")):
        members = [tuple(m.split("/")) for m in family["members"]]
        rep = next((m for m in members if m in tags), None)
        if rep is None:
            stats["families without a described member"] += 1
            continue
        for scene, key in members:
            src = root / scene / key / "labels.json"
            if not src.exists():
                continue
            doc = json.loads(src.read_text(encoding="utf-8"))
            own = dict(tags[rep])
            # The representative's glue, by mesh ID (a family shares its meshes).
            units = layer_units(json.loads((root / scene / key / "scene.json").read_text(encoding="utf-8")),
                                glued.get(model_of.get(rep, ""), []))
            segments = {}
            for mesh, unit in units.items():
                segments.setdefault(unit, []).append(mesh)
            for unit, ids in segments.items():
                votes = Counter(own[m] for m in ids if own.get(m))
                if votes:
                    tag = votes.most_common(1)[0][0]
                    for m in ids:
                        own[m] = tag
                    stats["cut layers joined"] += 1
            for mesh, tag in own.items():
                if mesh not in doc["labels"]:
                    continue
                lab = dict(doc["labels"][mesh])
                if tag is None:
                    lab["confidence"] = 0.0
                    stats["meshes left out"] += 1
                else:
                    stats["meshes relabelled" if lab.get("tag") != tag else "meshes confirmed"] += 1
                    lab.update(tag=tag, confidence=1.0)
                doc["labels"][mesh] = lab
            doc["source"] = "describe"
            (root / scene / key / "labels_described.json").write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
            stats["models"] += 1
    print(dict(stats))


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), [Path(p) for p in sys.argv[3].split(",")], *(Path(a) for a in sys.argv[4:5]))
