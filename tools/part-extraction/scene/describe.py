"""Sheets for a reviewer to name every sizeable mesh directly: its tag, its role, and in free words what piece it is
and what fine detail it carries (a skirt's lace trim, a fluffy fur rim), so the splits can go finer than the tags.

  python describe.py make SCENE_ROOT OUT [MIN_SOLID [MAX_SOLID [SKIP,...]]]
                                                       one folder per family representative under OUT, with
                                                        sheet_<n>.png, sheet_<n>.txt (names and rule guesses per tile)
                                                        and tiles.json; writes OUT/batches.json and OUT/batch_<n>.txt
                                                        (about 160 tiles each)
  python describe.py batch OUT [--final]               batch_<n>.txt for finished models not handed out yet
  python describe.py missing OUT [SKIP,...]             redo_<n>.txt batches of the sheets still short of answers
  python describe.py collect OUT [SKIP,...]             fold every answers_*.json into OUT/describe.jsonl, but
                                                        those of the scene dirs listed

A tile is two panes: the mesh lit and outlined in red among its deformer group (half lit) and its surroundings
(dimmed), and the mesh drawn alone and enlarged, so its edge (lace, frills, fur) can be seen.
"""
import json
import sys
from collections import defaultdict
from multiprocessing import Pool
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

PANE = 300
COLUMNS = 2
ROWS = 5
PER_BATCH = 160


def layer(folder: Path, m: dict) -> tuple[np.ndarray, int, int] | None:
    """The mesh's pixels and where they sit on the canvas."""
    path = folder / "meshes" / f"{m['render']}.png"
    if "at" not in m or not path.exists():
        return None
    a = np.asarray(Image.open(path).convert("RGBA"))
    return a, m["at"][0], m["at"][1]


def bounds(layers) -> tuple[int, int, int, int] | None:
    boxes = []
    for a, x, y in layers:
        ys, xs = np.nonzero(a[..., 3] > 25)
        if len(xs):
            boxes.append((x + xs.min(), y + ys.min(), x + xs.max(), y + ys.max()))
    if not boxes:
        return None
    return min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)


def square(box, scale: float, least: float):
    x0, y0, x1, y1 = box
    span = max(x1 - x0, y1 - y0) * scale + least
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    return [int(cx - span / 2), int(cy - span / 2), int(cx + span / 2), int(cy + span / 2)]


def inside(box, shape):
    """The square slid back onto the canvas, no wider than it, so the close-up shows picture rather than padding."""
    h, w = shape[:2]
    span = min(box[2] - box[0], max(w, h))
    x0 = min(max(box[0], 0), max(0, w - span)) if span <= w else (w - span) // 2
    y0 = min(max(box[1], 0), max(0, h - span)) if span <= h else (h - span) // 2
    return [x0, y0, x0 + span, y0 + span]


def window(canvas: np.ndarray, box, fill) -> np.ndarray:
    """canvas[box], padded with fill where the box leaves the canvas."""
    x0, y0, x1, y1 = box
    out = np.empty((y1 - y0, x1 - x0) + canvas.shape[2:], canvas.dtype)
    out[...] = fill
    h, w = canvas.shape[:2]
    sx0, sy0, sx1, sy1 = max(0, x0), max(0, y0), min(w, x1), min(h, y1)
    if sx1 > sx0 and sy1 > sy0:
        out[sy0 - y0:sy1 - y0, sx0 - x0:sx1 - x0] = canvas[sy0:sy1, sx0:sx1]
    return out


def paste_alpha(mask: np.ndarray, box, a: np.ndarray, x: int, y: int) -> None:
    """Max a layer's alpha into mask, which covers box on the canvas."""
    bx0, by0 = box[0], box[1]
    h, w = mask.shape
    ox, oy = x - bx0, y - by0
    sx0, sy0 = max(0, -ox), max(0, -oy)
    sx1, sy1 = min(a.shape[1], w - ox), min(a.shape[0], h - oy)
    if sx1 > sx0 and sy1 > sy0:
        region = mask[oy + sy0:oy + sy1, ox + sx0:ox + sx1]
        np.maximum(region, a[sy0:sy1, sx0:sx1, 3].astype(np.float32) / 255, out=region)


def tile(picture: np.ndarray, own, group: list) -> Image.Image | None:
    """picture: the model over grey, float RGB. own: the mesh's layer; group: its deformer group's layers."""
    mbox = bounds([own])
    if mbox is None:
        return None
    gbox = bounds(group) or mbox
    mspan = max(mbox[2] - mbox[0], mbox[3] - mbox[1])
    gspan = max(gbox[2] - gbox[0], gbox[3] - gbox[1])
    # Context: the group around the mesh, but not so wide that a small piece vanishes in it.
    box = inside(square(mbox, 1, 40 + max(mspan * 1.5, min(gspan * 1.1 - mspan, mspan * 5))), picture.shape)
    view = window(picture, box, 150.0)
    mask = np.zeros(view.shape[:2], np.float32)
    paste_alpha(mask, box, *own)
    near = np.zeros(view.shape[:2], np.float32)
    for g in group:
        paste_alpha(near, box, *g)
    light = np.where(near[..., None] > 0.1, view * 0.65 + 20, view * 0.3 + 30)
    lit = view * mask[..., None] + light * (1 - mask[..., None])
    left = Image.fromarray(lit.clip(0, 255).astype(np.uint8))
    edge = Image.fromarray((mask > 0.1).astype(np.uint8) * 255).filter(ImageFilter.FIND_EDGES).filter(ImageFilter.MaxFilter(3))
    left.paste(Image.new("RGB", left.size, (255, 40, 40)), (0, 0), edge)
    left = left.resize((PANE, PANE), Image.LANCZOS)
    # Alone: the mesh's own pixels over a light checker, framed tight.
    a, x, y = own
    tight = square(mbox, 1.1, 8)
    alone = Image.fromarray(a).crop((tight[0] - x, tight[1] - y, tight[2] - x, tight[3] - y))
    checker = Image.new("RGBA", alone.size, (225, 225, 225, 255))
    d = ImageDraw.Draw(checker)
    step = max(4, alone.width // 24)
    for yy in range(0, alone.height, step):
        for xx in range((yy // step) % 2 * step, alone.width, step * 2):
            d.rectangle([xx, yy, xx + step - 1, yy + step - 1], fill=(200, 200, 200, 255))
    checker.alpha_composite(alone)
    right = checker.convert("RGB").resize((PANE, PANE), Image.LANCZOS)
    out = Image.new("RGB", (PANE * 2 + 4, PANE), (255, 255, 255))
    out.paste(left, (0, 0))
    out.paste(right, (PANE + 4, 0))
    return out


def names_of(scene: dict, m: dict) -> str:
    parts = {p["id"]: p["name"] for p in scene["parts"]}
    deformers = {d["id"]: d["name"] for d in scene["deformers"]}
    names = []
    for n in (m["name"], parts.get(m.get("part"), ""), deformers.get(m.get("deformer"), "")):
        if n and not n.lower().startswith(("artmesh", "part", "warp", "rotation")) and n not in names:
            names.append(n)
    return " / ".join(names)[:80]


def make_one(job) -> tuple[str, int]:
    folder, out, min_solid, max_solid = job
    scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
    labels = json.loads((folder / "labels.json").read_text(encoding="utf-8"))["labels"]
    full = np.asarray(Image.open(folder / "full.png").convert("RGBA")).astype(np.float32)
    alpha = full[..., 3:] / 255
    picture = full[..., :3] * alpha + 150 * (1 - alpha)
    del full, alpha
    shown = [m for m in scene["meshes"] if "at" in m and m.get("opacity", 1) >= 0.05]
    groups = defaultdict(list)
    for m in shown:
        groups[m.get("deformer") or m.get("part") or m["id"]].append(m)
    chosen = [m for m in shown if min_solid <= m["look"]["solid"] < max_solid]
    # Draw order back to front so a reviewer meets the base before what sits on it.
    chosen.sort(key=lambda m: m["render"])
    out.mkdir(parents=True, exist_ok=True)
    layers = {m["id"]: layer(folder, m) for m in shown}
    listed, tiles = [], []
    for m in chosen:
        own = layers[m["id"]]
        if own is None:
            continue
        group = [layers[g["id"]] for g in groups[m.get("deformer") or m.get("part") or m["id"]] if layers[g["id"]] is not None]
        t = tile(picture, own, group)
        if t is None:
            continue
        lab = labels[m["id"]]
        listed.append({"tile": len(listed) + 1, "mesh": m["id"], "rules": lab["tag"], "names": names_of(scene, m)})
        tiles.append(t)
    per = COLUMNS * ROWS
    w, h = PANE * 2 + 4, PANE + 22
    for s in range(0, len(tiles), per):
        sheet = Image.new("RGB", (COLUMNS * (w + 12), ROWS * h), "white")
        draw = ImageDraw.Draw(sheet)
        text = []
        for i, (g, t) in enumerate(zip(listed[s:s + per], tiles[s:s + per])):
            x, y = (i % COLUMNS) * (w + 12), (i // COLUMNS) * h
            sheet.paste(t, (x, y))
            draw.rectangle([x, y, x + 40, y + 22], fill="black")
            draw.text((x + 5, y + 5), str(g["tile"]), fill="white")
            text.append(f"{g['tile']}\trules: {g['rules']}\tnames: {g['names']}")
        n = s // per + 1
        sheet.save(out / f"sheet_{n}.png")
        (out / f"sheet_{n}.txt").write_text("\n".join(text) + "\n", encoding="utf-8")
    (out / "tiles.json").write_text(json.dumps({"scene": folder.parent.name, "key": folder.name, "tiles": listed},
                                               ensure_ascii=False, indent=0), encoding="utf-8")
    return str(out), len(listed)


def make(root: Path, out: Path, min_solid: float = 300, max_solid: float = float("inf"), skip: set[str] = frozenset()) -> None:
    """Sheets of each family representative's meshes with min_solid <= solid pixels < max_solid; skip: scene dirs."""
    families = json.loads((root / "families.json").read_text(encoding="utf-8"))
    jobs = []
    for f in families:
        folder = root / f["members"][0]
        if folder.parent.name not in skip and (folder / "labels.json").exists():
            jobs.append((folder, out / f"{folder.parent.name}_{folder.name}", min_solid, max_solid))
    with Pool(16) as pool:
        done = pool.map(make_one, jobs, chunksize=1)
    # Batches of whole sheets, about PER_BATCH tiles each; a model may span batches.
    batches, current, count = [], [], 0
    per = COLUMNS * ROWS
    for folder, n in done:
        sheets = (n + per - 1) // per
        for s in range(1, sheets + 1):
            current.append(f"{folder}|{s}")
            count += min(per, n - (s - 1) * per)
            if count >= PER_BATCH:
                batches.append(current)
                current, count = [], 0
    if current:
        batches.append(current)
    (out / "batches.json").write_text(json.dumps(batches, ensure_ascii=False, indent=0), encoding="utf-8")
    for i, b in enumerate(batches):
        (out / f"batch_{i:03d}.txt").write_text("\n".join(b) + "\n", encoding="utf-8")
    print(sum(n for _, n in done), "tiles in", len(done), "models,", len(batches), "batches")


def batch(out: Path, final: bool = False) -> None:
    """Batches of the finished models not yet handed out (OUT/assigned.txt), so reviewing can start while sheets are
    still being made; the last part-full batch waits unless final."""
    assigned_path = out / "assigned.txt"
    assigned = set(assigned_path.read_text(encoding="utf-8").split()) if assigned_path.exists() else set()
    numbers = [int(p.stem.split("_")[1]) for p in out.glob("batch_*.txt")]
    n = max(numbers) + 1 if numbers else 0
    per = COLUMNS * ROWS
    current, count, made = [], 0, []
    for tiles_path in sorted(out.glob("*/tiles.json")):
        for s in range(1, (len(json.loads(tiles_path.read_text(encoding="utf-8"))["tiles"]) + per - 1) // per + 1):
            entry = f"{tiles_path.parent.resolve()}|{s}"
            if entry in assigned:
                continue
            current.append(entry)
            count += per
            if count >= PER_BATCH:
                made.append(current)
                current, count = [], 0
    if current and final:
        made.append(current)
    for b in made:
        (out / f"batch_{n:03d}.txt").write_text("\n".join(b) + "\n", encoding="utf-8")
        n += 1
        assigned.update(b)
    assigned_path.write_text("\n".join(sorted(assigned)) + "\n", encoding="utf-8")
    print(len(made), "new batches, up to", n - 1)


def missing(out: Path, skip: set[str] = frozenset()) -> None:
    """Batches (OUT/redo_<n>.txt) of the sheets whose answers are absent, unreadable or short of a tile; skip: scene dirs
    left out whole. Run once every batch has come back."""
    per = COLUMNS * ROWS
    redo = []
    for tiles_path in sorted(out.glob("*/tiles.json")):
        info = json.loads(tiles_path.read_text(encoding="utf-8"))
        if info["scene"] in skip:
            continue
        numbers = sorted(t["tile"] for t in info["tiles"])
        for s in range(1, (len(numbers) + per - 1) // per + 1):
            want = {n for n in numbers if (s - 1) * per < n <= s * per}
            try:
                got = json.loads((tiles_path.parent / f"answers_{s}.json").read_text(encoding="utf-8"))
                keys = {int(k) for k, a in got.items() if str(k).isdigit() and isinstance(a, dict)}
            except (OSError, json.JSONDecodeError, AttributeError):
                keys = set()
            if keys and s > 1 and max(keys) <= per:
                keys = {k + (s - 1) * per for k in keys}
            if not want <= keys:
                redo.append((f"{tiles_path.parent.resolve()}|{s}", len(want - keys)))
    for old in out.glob("redo_*.txt"):
        old.unlink()
    batches, current, count = [], [], 0
    for entry, n in redo:
        current.append(entry)
        count += per
        if count >= PER_BATCH:
            batches.append(current)
            current, count = [], 0
    if current:
        batches.append(current)
    for i, b in enumerate(batches):
        (out / f"redo_{i:03d}.txt").write_text("\n".join(b) + "\n", encoding="utf-8")
    print(len(redo), "sheets short of", sum(n for _, n in redo), "tiles, in", len(batches), "redo batches")


def collect(out: Path, skip: set[str] = frozenset()) -> None:
    """skip: scene dirs left out whole (a source withdrawn from the data)."""
    rows = []
    for tiles_path in sorted(out.glob("*/tiles.json")):
        info = json.loads(tiles_path.read_text(encoding="utf-8"))
        if info["scene"] in skip:
            continue
        by_tile = {str(t["tile"]): t for t in info["tiles"]}
        for answers in sorted(tiles_path.parent.glob("answers_*.json")):
            try:
                got = json.loads(answers.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                print("unreadable", answers)
                continue
            sheet = int(answers.stem.split("_")[1])
            keys = [int(k) for k in got if str(k).isdigit()]
            # Some reviewers number each sheet from 1 instead of using the printed tile numbers.
            offset = (sheet - 1) * COLUMNS * ROWS if sheet > 1 and keys and max(keys) <= COLUMNS * ROWS else 0
            for k, a in got.items():
                t = by_tile.get(str(int(k) + offset)) if str(k).isdigit() else None
                if t and isinstance(a, dict):
                    rows.append({"scene": info["scene"], "key": info["key"], "mesh": t["mesh"], "rules": t["rules"],
                                 **{f: a.get(f) for f in ("tag", "role", "part", "detail", "sure", "fragment")}})
    with open(out / "describe.jsonl", "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(len(rows), "meshes described")


if __name__ == "__main__":
    if sys.argv[1] == "batch":
        batch(Path(sys.argv[2]), "--final" in sys.argv)
    elif sys.argv[1] == "missing":
        missing(Path(sys.argv[2]), set(sys.argv[3].split(",")) if len(sys.argv) > 3 else set())
    elif sys.argv[1] == "make":
        make(Path(sys.argv[2]), Path(sys.argv[3]), *(float(a) for a in sys.argv[4:6]),
             *([set(sys.argv[6].split(","))] if len(sys.argv) > 6 else []))
    else:
        collect(Path(sys.argv[2]), set(sys.argv[3].split(",")) if len(sys.argv) > 3 else set())
