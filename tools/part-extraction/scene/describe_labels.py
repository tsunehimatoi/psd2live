"""Labels from the reviewer's per-mesh tags, so nodes are grouped as the reviewer saw the meshes rather than by rules.

  python describe_labels.py SCENE_ROOT FAMILIES.json DESCRIBE.jsonl[,DESCRIBE.jsonl...]

For every family whose representative was described (describe.py collect), each member's labels.json is written
again as labels_described.json ("source": "describe"): a mesh the representative's reviewer tagged takes that tag
(meshes share their IDs across a family), its guesses too (the rules are wrong more often, and a mesh left out
would leave a hole in its part), and the rest (meshes too small to describe, or not in the representative) keep
their rules label. NodeDatasetTool reads it with PARTS_LABELS=labels_described.json and marks its samples
"described", which make_node_pairs.py counts as reviewed. A mesh the reviewer called a fragment (a tiny scrap cut off a
bigger piece: a joint patch, a sliver, a corner) is left out of every node for now. The segments Cubism's skinning cut
one drawn layer into (skin_units) take that layer's most common tag together, fragments and undescribed ones too.
"""
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from make_node_pairs import PHRASES


def skin_units(scene: dict) -> dict[str, str]:
    """mesh -> its skinning unit: Cubism's skinning cuts one drawn layer into segments, one per bone, under a part
    named "<layer>_Skinning", so the meshes of such a part are one layer."""
    parts = {p["id"]: p["name"] for p in scene["parts"]}
    return {m["id"]: m["part"] for m in scene["meshes"] if parts.get(m.get("part"), "").lower().endswith("_skinning")}


def main(root: Path, families: Path, describe: list[Path]) -> None:
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
            units = skin_units(json.loads((root / scene / key / "scene.json").read_text(encoding="utf-8")))
            segments = {}
            for mesh, unit in units.items():
                segments.setdefault(unit, []).append(mesh)
            for unit, ids in segments.items():
                votes = Counter(own[m] for m in ids if own.get(m))
                if votes:
                    tag = votes.most_common(1)[0][0]
                    for m in ids:
                        own[m] = tag
                    stats["skinning layers joined"] += 1
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
    main(Path(sys.argv[1]), Path(sys.argv[2]), [Path(p) for p in sys.argv[3].split(",")])
