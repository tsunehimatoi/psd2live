"""Which single pieces of a group (subnodes.py's kind "piece") a split LoRA should learn on their own.

  python piece_kinds.py names SUBNODES.jsonl[,...] OUT_DIR             OUT_DIR/names_<n>.tsv for a reviewer
  python piece_kinds.py classes OUT_DIR                                 OUT_DIR/classes.json from answers_<n>.json
  python piece_kinds.py sizes NODES_ROOT SUBNODES.jsonl[,...] OUT.json  each piece's longer side and its face's size
  python piece_kinds.py mark SUBNODES.jsonl[,...] SIZES.json CLASSES.json OUT_DIR

A piece is kept when:
- its name is an item that comes several to a character, each layer one whole of it (a hair strand, a finger, a skirt
  frill), not a segment of one thing the artist cut up to animate it (chest skin, an eyelash, an eyelid): "the
  <name>" has to mean that one layer. A reviewer (piece_names_prompt.md) sorts the names; and
- its longer side (from its rendered picture, at native size) is at least max(MIN_SIDE, FACE_SHARE of the character's
  face). Measured against the face, low-resolution models keep as many pieces as sharp ones; measured by the longer
  side, thin strands and fingers count by their length.
mark writes each SUBNODES file again into OUT_DIR with kind "dropped" for the pieces left out; make_node_pairs.py
--subnodes skips those.
"""
import json
import math
import sys
from collections import Counter
from multiprocessing import Pool
from pathlib import Path

MIN_SIDE = 32
FACE_SHARE = 0.2


def name_of(node: dict) -> tuple[str, str]:
    return node["tag"], node["prompt"].split(",")[0].removeprefix("the ").strip()


def read(paths: list[Path]):
    for path in paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                yield path, json.loads(line)


def names(subnodes: list[Path], out: Path, parts: int = 2) -> None:
    out.mkdir(parents=True, exist_ok=True)
    found = sorted({name_of(j) for _, j in read(subnodes) if j.get("kind") == "piece"})
    size = math.ceil(len(found) / parts)
    for n in range(parts):
        chunk = found[n * size:(n + 1) * size]
        (out / f"names_{n}.tsv").write_text("".join(f"{i}\t{t}\t{nm}\n" for i, (t, nm) in enumerate(chunk, 1)), encoding="utf-8")
    print(len(found), "names in", parts, "files")


def classes(out: Path) -> None:
    result, missing = {}, 0
    for tsv in sorted(out.glob("names_*.tsv")):
        answers = json.loads((out / tsv.name.replace("names_", "answers_").replace(".tsv", ".json")).read_text(encoding="utf-8"))
        for line in tsv.read_text(encoding="utf-8").splitlines():
            i, tag, name = line.split("\t")
            value = answers.get(i)
            missing += value is None
            result[f"{tag}\t{name}"] = value if value in ("item", "segment") else "segment"
    (out / "classes.json").write_text(json.dumps(result, ensure_ascii=False, indent=0), encoding="utf-8")
    print(Counter(result.values()), missing, "missing (taken as segment)")


def _longer_side(job):
    path, native = job
    import numpy as np
    from PIL import Image
    if not Path(path).exists():
        return 0.0
    a = np.asarray(Image.open(path))
    mask = a[..., 3] > 32 if a.ndim == 3 and a.shape[2] == 4 else a.max(-1) > 8
    ys, xs = np.nonzero(mask)
    return 0.0 if len(xs) == 0 else max(xs.max() - xs.min() + 1, ys.max() - ys.min() + 1) * native / a.shape[1]


def sizes(root: Path, subnodes: list[Path], out: Path) -> None:
    pieces = {(j["model"], j["node"]) for _, j in read(subnodes) if j.get("kind") == "piece"}
    jobs, keys, face = [], [], {}
    for samples in sorted(root.glob("*/samples.jsonl")):
        for line in samples.read_text(encoding="utf-8").splitlines():
            s = json.loads(line)
            if s["variant"]:
                continue
            if not s.get("prompt") and s["tag"] == "face":
                face[s["model"]] = max(face.get(s["model"], 0), s["pixels"] * math.sqrt(s["area"]))
            if (s["model"], s["node"]) in pieces:
                jobs.append((str(samples.parent / s["key"] / f"{s['node']}_0_target.png"), s["pixels"]))
                keys.append((s["model"], s["node"]))
    with Pool(16) as pool:
        longer = pool.map(_longer_side, jobs, chunksize=64)
    rows = [{"model": m, "node": n, "long": round(l, 1), "face": round(face.get(m, 0), 1)} for (m, n), l in zip(keys, longer)]
    out.write_text(json.dumps(rows), encoding="utf-8")
    print(len(rows), "pieces measured")


def mark(subnodes: list[Path], sizes_path: Path, classes_path: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    size = {(r["model"], r["node"]): r for r in json.loads(sizes_path.read_text(encoding="utf-8"))}
    kind = json.loads(classes_path.read_text(encoding="utf-8"))
    stats = Counter()
    written = {}
    for path, j in read(subnodes):
        if j.get("kind") == "piece":
            r = size.get((j["model"], j["node"]))
            tag, name = name_of(j)
            if kind.get(f"{tag}\t{name}", "segment") != "item":
                j["kind"] = "dropped"
                stats["segment of one thing"] += 1
            elif r is None or r["long"] < max(MIN_SIDE, FACE_SHARE * r["face"]):
                j["kind"] = "dropped"
                stats["too small" if r else "not rendered"] += 1
            else:
                stats["kept"] += 1
        written.setdefault(path.name, []).append(json.dumps(j, ensure_ascii=False))
    for name, lines in written.items():
        (out / name).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(dict(stats))


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    paths = lambda s: [Path(p) for p in s.split(",")]
    if cmd == "names":
        names(paths(args[0]), Path(args[1]))
    elif cmd == "classes":
        classes(Path(args[0]))
    elif cmd == "sizes":
        sizes(Path(args[0]), paths(args[1]), Path(args[2]))
    elif cmd == "mark":
        mark(paths(args[0]), Path(args[1]), Path(args[2]), Path(args[3]))
