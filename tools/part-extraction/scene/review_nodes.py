"""Review sheets for training nodes (NodeDatasetTool), and the verdicts folded back.

  python review_nodes.py sheets NODES_DIR OUT [MODELS]   OUT/<batch>/sheet_<n>.png + tiles.json for the variant-0
                                                         sample of every node (first MODELS models), 16 tiles a sheet
  python review_nodes.py verdicts OUT VERDICTS           gather OUT/*/answers.json into VERDICTS (jsonl, one line per
                                                         node: key, node, tag, keep)

A tile is the close-up with the node's visible area lit and outlined, the node alone (the target) inset bottom
right, and the label the rules gave it. The reviewer answers the tag it really is and whether the target is one
clean part worth training on.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

TILE = 300
INSET = 110
PER_SHEET = 16
SHEETS_PER_BATCH = 8


def tile(stem: Path) -> Image.Image:
    inp = Image.open(f"{stem}_input.png").convert("RGBA")
    bg = Image.new("RGBA", inp.size, (150, 150, 150, 255))
    bg.alpha_composite(inp)
    picture = np.asarray(bg.convert("RGB"), np.float32)
    hint = np.asarray(Image.open(f"{stem}_hint.png").convert("L"), np.float32)[..., None] / 255
    lit = picture * hint + (picture * 0.35 + 30) * (1 - hint)
    image = Image.fromarray(lit.clip(0, 255).astype(np.uint8)).resize((TILE, TILE), Image.LANCZOS)
    edge = Image.open(f"{stem}_hint.png").convert("L").resize((TILE, TILE)).filter(ImageFilter.FIND_EDGES).filter(ImageFilter.MaxFilter(3))
    image.paste(Image.new("RGB", image.size, (255, 40, 40)), (0, 0), edge)
    target = Image.open(f"{stem}_target.png").convert("RGBA")
    inset = Image.new("RGBA", target.size, (128, 128, 128, 255))
    inset.alpha_composite(target)
    image.paste(inset.convert("RGB").resize((INSET, INSET), Image.LANCZOS), (TILE - INSET, TILE - INSET))
    ImageDraw.Draw(image).rectangle([TILE - INSET - 1, TILE - INSET - 1, TILE - 1, TILE - 1], outline="yellow")
    return image


def sheets(nodes: Path, out: Path, models: int | None = None) -> None:
    samples = []
    for f in sorted(nodes.glob("*/samples.jsonl")):
        samples += [dict(json.loads(l), dir=str(f.parent)) for l in f.read_text(encoding="utf-8").splitlines() if l.strip()]
    first = [s for s in samples if s["variant"] == 0]
    if models:
        keep = sorted({(s["dir"], s["key"]) for s in first})[:models]
        first = [s for s in first if (s["dir"], s["key"]) in set(keep)]
    out.mkdir(parents=True, exist_ok=True)
    per_batch = PER_SHEET * SHEETS_PER_BATCH
    for b in range(0, len(first), per_batch):
        batch = out / f"batch_{b // per_batch:04d}"
        batch.mkdir(exist_ok=True)
        tiles = []
        chunk = first[b:b + per_batch]
        for s0 in range(0, len(chunk), PER_SHEET):
            sheet = Image.new("RGB", (4 * TILE, 4 * (TILE + 20)), "white")
            draw = ImageDraw.Draw(sheet)
            for i, s in enumerate(chunk[s0:s0 + PER_SHEET]):
                number = len(tiles) + 1
                x, y = (i % 4) * TILE, (i // 4) * (TILE + 20)
                sheet.paste(tile(Path(s["dir"]) / s["key"] / f"{s['node']}_0"), (x, y))
                draw.rectangle([x, y, x + 34, y + 20], fill="black")
                draw.text((x + 4, y + 4), str(number), fill="white")
                draw.text((x + 4, y + TILE + 4), f"rules: {s['tag']}" + (f" (piece {s['piece']}/{s['pieces']})" if s["piece"] else ""), fill="black")
                tiles.append({"tile": number, "dir": Path(s["dir"]).name, "key": s["key"], "node": s["node"], "tag": s["tag"]})
            sheet.save(batch / f"sheet_{s0 // PER_SHEET + 1}.png")
        (batch / "tiles.json").write_text(json.dumps(tiles, ensure_ascii=False), encoding="utf-8")
    print(len(first), "nodes in", (len(first) + per_batch - 1) // per_batch, "batches")


def verdicts(out: Path, target: Path) -> None:
    lines, n_keep, n_changed = [], 0, 0
    for batch in sorted(out.glob("batch_*")):
        answers_path = batch / "answers.json"
        if not answers_path.exists():
            continue
        tiles = {str(t["tile"]): t for t in json.loads((batch / "tiles.json").read_text(encoding="utf-8"))}
        answers = json.loads(answers_path.read_text(encoding="utf-8"))
        for number, a in answers.items():
            t = tiles.get(str(number))
            if not t:
                continue
            # Kept when clean and either the reviewer is sure or it agrees with the rules: two independent
            # readings that match are confirmation enough.
            keep = bool(a.get("clean", True)) and (a.get("sure", True) or a.get("tag") == t["tag"]) and a.get("tag") not in (None, "unknown")
            n_keep += keep
            n_changed += keep and a["tag"] != t["tag"]
            lines.append(json.dumps({"dir": t["dir"], "key": t["key"], "node": t["node"], "rules": t["tag"],
                                     "tag": a.get("tag"), "keep": keep}, ensure_ascii=False))
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(len(lines), "verdicts,", n_keep, "kept,", n_changed, "of them relabelled")


if __name__ == "__main__":
    if sys.argv[1] == "sheets":
        sheets(Path(sys.argv[2]), Path(sys.argv[3]), int(sys.argv[4]) if len(sys.argv) > 4 else None)
    else:
        verdicts(Path(sys.argv[2]), Path(sys.argv[3]))
