"""Families of models: the same rig stored twice, or versions of one character, so a label made once carries over.

  python families.py SCENE_ROOT        write SCENE_ROOT/families.json: [{"members": [model dirs], "shared": ...}]

Two models are one family when their meshes overlap: the same mesh ids at about the same places (Jaccard >= 0.6
on id and coarse position) and mesh counts within 30%. The first member (most meshes) is the representative.
"""
import json
import sys
from collections import defaultdict
from pathlib import Path


def signature(scene: dict) -> frozenset:
    return frozenset((m["id"], round(m["box"][0] / 50), round(m["box"][1] / 50)) for m in scene["meshes"] if m.get("box"))


def main(root: Path) -> None:
    sig, model = {}, {}
    for f in sorted(root.glob("scene*/*/scene.json")):
        s = json.loads(f.read_text(encoding="utf-8"))
        key = f"{f.parent.parent.name}/{f.parent.name}"
        sig[key], model[key] = signature(s), s["model"]
    keys = sorted(sig, key=lambda k: -len(sig[k]))
    parent = {k: k for k in keys}

    def find(k):
        while parent[k] != k:
            parent[k] = parent[parent[k]]
            k = parent[k]
        return k

    # Candidate pairs share a mesh id+position, so only models with something in common are compared.
    index = defaultdict(list)
    for k in keys:
        for item in sig[k]:
            index[item].append(k)
    seen = set()
    for k in keys:
        counts = defaultdict(int)
        for item in sig[k]:
            for o in index[item]:
                if o != k:
                    counts[o] += 1
        for o, shared in counts.items():
            pair = (min(k, o), max(k, o))
            if pair in seen:
                continue
            seen.add(pair)
            a, b = len(sig[k]), len(sig[o])
            if abs(a - b) <= 0.3 * max(a, b) and shared / (a + b - shared) >= 0.6:
                parent[find(o)] = find(k)
    groups = defaultdict(list)
    for k in keys:
        groups[find(k)].append(k)
    families = sorted(({"members": v, "models": [model[m] for m in v]} for v in groups.values()), key=lambda f: -len(f["members"]))
    (root / "families.json").write_text(json.dumps(families, ensure_ascii=False, indent=0), encoding="utf-8")
    print(len(keys), "models,", len(families), "families,", sum(1 for f in families if len(f["members"]) > 1), "with several members")
    for f in families[:10]:
        print(len(f["members"]), f["models"][:3])


if __name__ == "__main__":
    main(Path(sys.argv[1]))
