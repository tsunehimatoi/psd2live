"""The head with its mouth opened (Klein edit, no LoRA): a source picture for the open-mouth layer, which Live2D
needs at its widest. The edit is laid at its square on an empty canvas of the illustration's size, only so the split
can read close-ups from it in canvas coordinates; it is never part of a layer.

usage: python open_mouth.py ART.png FACE_REGION.png OUT.png [SEEDS...]
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "comfy"))
import l2d
from env import client

PROMPT = ("Open her mouth wide as if saying 'ah' while talking, showing the upper teeth, the dark inside of the mouth "
          "and the tongue. Keep everything else exactly the same: the same face shape, eyes, eyebrows, hair, colours, "
          "lineart, lighting and framing.")
art, face, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
seeds = [int(s) for s in sys.argv[4:]] or [1]
im = Image.open(art).convert("RGBA")
bg = Image.new("RGBA", im.size, (235, 235, 235, 255)); bg.alpha_composite(im)
m = np.asarray(Image.open(face)) > 127
ys, xs = np.nonzero(m)
side = int(max(xs.max() - xs.min(), ys.max() - ys.min()) * 1.6)
cx, cy = (xs.min() + xs.max()) // 2, (ys.min() + ys.max()) // 2
box = (cx - side // 2, cy - side // 2, cx - side // 2 + side, cy - side // 2 + side)
work = out.parent / f"{out.stem}_work"
work.mkdir(parents=True, exist_ok=True)
bg.convert("RGB").crop(box).resize((1024, 1024), Image.LANCZOS).save(work / "head.png")
c = client(timeout=900)
a = c.upload(work / "head.png", f"live_{out.stem}_head.png")
for seed in seeds:
    f = c.run(l2d.edit_graph(a, PROMPT, 1024, 1024, seed=seed, name=f"open_{seed}"), work, f"open_{seed}")[0]
    canvas = Image.new("RGBA", im.size, (0, 0, 0, 0))
    canvas.paste(Image.open(f).convert("RGBA").resize((side, side), Image.LANCZOS), box[:2])
    canvas.save(out.parent / f"{out.stem}_s{seed}.png")
    print(seed, box, flush=True)
