"""Sheet of a split's layers: per row the close-up, the hint and each candidate (matted, over a checker).

usage: python review.py SPLIT_DIR OUT.png [LAYER ...]
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

G = "--g" in sys.argv  # show the general-matte versions (<candidate>_g.png)
args = [a for a in sys.argv[1:] if a != "--g"]
d, out = Path(args[0]), Path(args[1])
ids = args[2:] or sorted(p.name for p in d.iterdir() if (p / "meta.json").exists())
cell = 256
TOP = 6
rows = []
for i in ids:
    m = json.loads((d / i / "meta.json").read_text(encoding="utf-8"))
    cands = m["candidates"][:TOP]  # best ranked first (live_split.py scores)
    sc = m.get("scores", {})
    rows.append((i, [d / i / "input.png", d / i / "hint.png"] + [d / i / (f"{c}_g.png" if G and (d / i / f"{c}_g.png").exists() else f"{c}.png") for c in cands],
                 [c.replace("split_", "") + (f" {sc[c]['rank']:.2f}" if c in sc else "") for c in cands]))
cols = max(len(r[1]) for r in rows)
sheet = Image.new("RGB", (cols * cell, len(rows) * (cell + 16)), "white")
dr = ImageDraw.Draw(sheet)
check = Image.new("RGB", (cell, cell), (200, 200, 200))
cd = ImageDraw.Draw(check)
for y in range(0, cell, 16):
    for x in range(0, cell, 16):
        if (x // 16 + y // 16) % 2:
            cd.rectangle((x, y, x + 15, y + 15), fill=(150, 150, 150))
for r, (i, files, cands) in enumerate(rows):
    y = r * (cell + 16)
    dr.text((4, y + 2), i, fill="black")
    for k, f in enumerate(files):
        im = Image.open(f).convert("RGBA").resize((cell, cell))
        bg = check.copy().convert("RGBA")
        bg.alpha_composite(im)
        sheet.paste(bg.convert("RGB"), (k * cell, y + 16))
        if k >= 2:
            dr.text((k * cell + 4, y + 2), cands[k - 2], fill="black")
sheet.save(out)
