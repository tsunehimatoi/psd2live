"""A reviewer's look at each picture laid in front of and behind something (layer_units.py's layered pairs), before
subnodes.py teaches "the part of the X in front of the Y" from it.

  python layered_review.py make SCENE_ROOT LAYER_UNITS.jsonl DESCRIBE.jsonl[,...] OUT   sheet_<n>.png/.txt, batch_<n>.txt
  python layered_review.py collect OUT                                                 OUT/layered_review.jsonl

Each row of a sheet is one pair: the model around it with the thing outlined in red and what lies between in blue,
then the thing alone, its front side alone and its back side alone. The reviewer says whether the red thing really
passes in front of and behind the blue one, and names both. Pairs are keyed by model and the first mesh of the front.
"""
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

PANE = 256
ROWS = 4
PER_BATCH = 40


def mask_of(folder: Path, meshes: dict, ids: list[str], size: tuple[int, int]) -> np.ndarray:
    out = np.zeros(size[::-1], bool)
    for i in ids:
        m = meshes.get(i)
        path = folder / "meshes" / f"{m['render']}.png" if m and "at" in m else None
        if path is None or not path.exists():
            continue
        a = np.asarray(Image.open(path))[..., 3] > 64
        x, y = m["at"]
        h, w = a.shape
        x0, y0, x1, y1 = max(0, x), max(0, y), min(size[0], x + w), min(size[1], y + h)
        if x1 > x0 and y1 > y0:
            out[y0:y1, x0:x1] |= a[y0 - y:y1 - y, x0 - x:x1 - x]
    return out


def alone(folder: Path, meshes: dict, ids: list[str], size: tuple[int, int]) -> Image.Image:
    """The meshes drawn by themselves in render order, hidden parts included."""
    out = Image.new("RGBA", size, (0, 0, 0, 0))
    for m in sorted((meshes[i] for i in ids if i in meshes and "at" in meshes[i]), key=lambda m: m["render"]):
        path = folder / "meshes" / f"{m['render']}.png"
        if path.exists():
            layer = Image.new("RGBA", size, (0, 0, 0, 0))
            layer.paste(Image.open(path).convert("RGBA"), tuple(m["at"]))
            out = Image.alpha_composite(out, layer)
    return out


def outline(mask: np.ndarray) -> np.ndarray:
    m = Image.fromarray(mask.astype(np.uint8) * 255)
    return (np.asarray(m.filter(ImageFilter.MaxFilter(7))) > 0) & ~mask


def fit(img: Image.Image) -> Image.Image:
    img.thumbnail((PANE, PANE))
    pane = Image.new("RGB", (PANE, PANE), (200, 200, 200))
    pane.paste(img, ((PANE - img.width) // 2, (PANE - img.height) // 2), img if img.mode == "RGBA" else None)
    return pane


def row(folder: Path, scene: dict, pair: dict) -> Image.Image:
    full = Image.open(folder / "full.png").convert("RGBA")
    meshes = {m["id"]: m for m in scene["meshes"]}
    whole = mask_of(folder, meshes, pair["front"] + pair["back"], full.size)
    between = mask_of(folder, meshes, pair["between"], full.size)
    ys, xs = np.nonzero(whole | between)
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    pad = int(max(x1 - x0, y1 - y0) * 0.15) + 8
    box = (max(0, x0 - pad), max(0, y0 - pad), min(full.width, x1 + pad), min(full.height, y1 + pad))
    rgb = np.asarray(full).astype(np.float32)
    flat = rgb[..., :3] * rgb[..., 3:] / 255 + 255 * (1 - rgb[..., 3:] / 255)  # the model on white
    ctx = np.where((whole | between)[..., None], flat, flat * 0.45 + 255 * 0.55)  # the rest dimmed
    ctx[outline(whole)] = (230, 20, 20)
    ctx[outline(between)] = (20, 60, 230)
    panes = [fit(Image.fromarray(ctx.clip(0, 255).astype(np.uint8)).crop(box))]
    for ids in (pair["front"] + pair["back"], pair["front"], pair["back"]):
        panes.append(fit(alone(folder, meshes, ids, full.size).crop(box)))
    out = Image.new("RGB", (PANE * 4 + 12, PANE), "white")
    for i, p in enumerate(panes):
        out.paste(p, (i * (PANE + 4), 0))
    return out


def make(root: Path, units: Path, describe: list[Path], out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    folder_of = {}
    for sc in root.glob("scene*"):
        idx = sc / "index.tsv"
        if idx.exists():
            for line in idx.read_text(encoding="utf-8").splitlines()[1:]:
                k, m = line.split("\t")[:2]
                folder_of.setdefault(m, sc / k)
    said = defaultdict(dict)
    for line in (l for path in describe for l in path.read_text(encoding="utf-8").splitlines()):
        if line.strip():
            d = json.loads(line)
            said[str(root / d["scene"] / d["key"])][d["mesh"]] = d
    items = []
    for line in units.read_text(encoding="utf-8").splitlines():
        d = json.loads(line)
        folder = folder_of.get(d["model"])
        rows = said.get(str(folder)) if folder else None
        if not rows:
            continue  # only the described representatives are reviewed; members share their meshes
        for pair in d["layered"]:
            named = [rows[i] for i in pair["front"] + pair["back"] if i in rows]
            over = [rows[i] for i in pair["between"] if i in rows]
            if not named or not over:
                continue
            name = Counter(r["part"] for r in named).most_common(1)[0][0]
            other = Counter(r["part"] for r in over).most_common(1)[0][0]
            items.append({"model": d["model"], "folder": str(folder), "pair": pair["front"][0], "name": name,
                          "other": other, "front": pair["front"], "back": pair["back"], "between": pair["between"]})
    scenes = {}
    sheets = []
    for s in range(0, len(items), ROWS):
        chunk = items[s:s + ROWS]
        sheet = Image.new("RGB", (PANE * 4 + 12 + 40, ROWS * (PANE + 8)), "white")
        draw = ImageDraw.Draw(sheet)
        lines = []
        for r, it in enumerate(chunk, 1):
            folder = Path(it["folder"])
            if it["folder"] not in scenes:
                scenes = {it["folder"]: json.loads((folder / "scene.json").read_text(encoding="utf-8"))}
            sheet.paste(row(folder, scenes[it["folder"]], it), (40, (r - 1) * (PANE + 8)))
            draw.text((8, (r - 1) * (PANE + 8) + PANE // 2), str(r), fill="black")
            lines.append(f"{r}\tred: {it['name']}\tblue: {it['other']}")
        n = len(sheets) + 1
        sheet.save(out / f"sheet_{n}.png")
        (out / f"sheet_{n}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        sheets.append([{k: it[k] for k in ("model", "pair", "name", "other")} for it in chunk])
    (out / "sheets.json").write_text(json.dumps(sheets, ensure_ascii=False), encoding="utf-8")
    per = PER_BATCH // ROWS
    for b in range(0, len(sheets), per):
        (out / f"batch_{b // per:03d}.txt").write_text(
            "\n".join(str(out.resolve() / f"sheet_{n}") for n in range(b + 1, min(b + per, len(sheets)) + 1)) + "\n", encoding="utf-8")
    print(len(items), "pairs on", len(sheets), "sheets")


def collect(out: Path) -> None:
    sheets = json.loads((out / "sheets.json").read_text(encoding="utf-8"))
    rows, missing = [], 0
    for n, chunk in enumerate(sheets, 1):
        path = out / f"answers_{n}.json"
        answers = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        for r, it in enumerate(chunk, 1):
            a = answers.get(str(r))
            if a is None:
                missing += 1
                continue
            rows.append({"model": it["model"], "pair": it["pair"], "keep": bool(a.get("keep")),
                         "name": a.get("name") or it["name"], "other": a.get("other") or it["other"]})
    with open(out / "layered_review.jsonl", "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(len(rows), "pairs reviewed,", sum(r["keep"] for r in rows), "kept,", missing, "missing")


if __name__ == "__main__":
    if sys.argv[1] == "make":
        make(Path(sys.argv[2]), Path(sys.argv[3]), [Path(p) for p in sys.argv[4].split(",")], Path(sys.argv[5]))
    else:
        collect(Path(sys.argv[2]))
