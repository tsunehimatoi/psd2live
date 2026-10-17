"""Review files keyed by the node keys of one render, rekeyed for another render of the same collections.

  python remap_keys.py OLD_INDEX_DIR NEW_NODES_ROOT IN.jsonl OUT.jsonl

A render numbers its models in the order it walks the folders (k000, k001...), which differs between machines, so
verdicts and wear notes ({"dir", "key", ...}) made on one render would land on the wrong models of another.
OLD_INDEX_DIR holds <dir>.tsv, the index.tsv of the render the reviews were made on; NEW_NODES_ROOT holds
<dir>/index.tsv of the new render. Lines whose model the new render lacks are dropped and counted.
"""
import json
import sys
from pathlib import Path


def index(path: Path) -> dict[str, str]:
    """key -> model path."""
    rows = [l.split("\t") for l in path.read_text(encoding="utf-8").splitlines()[1:] if l.strip()]
    return {r[0]: r[1] for r in rows if len(r) >= 2}


def main(old_dir: Path, new_root: Path, src: Path, dst: Path) -> None:
    maps = {}
    for tsv in old_dir.glob("*.tsv"):
        new = new_root / tsv.stem / "index.tsv"
        if new.exists():
            by_model = {m: k for k, m in index(new).items()}
            maps[tsv.stem] = {k: by_model.get(m) for k, m in index(tsv).items()}
    kept = lost = 0
    with open(dst, "w", encoding="utf-8") as f:
        for line in src.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            v = json.loads(line)
            key = maps.get(v["dir"], {}).get(v["key"])
            if key is None:
                lost += 1
                continue
            f.write(json.dumps(dict(v, key=key), ensure_ascii=False) + "\n")
            kept += 1
    moved = sum(1 for m in maps.values() for k, n in m.items() if n and n != k)
    print(f"{src.name}: {kept} kept, {lost} without a model in the new render; {moved} keys renumbered")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]))
