"""A PSD2Live Layout manifest from a live split: per layer of SPEC (back to front) the candidate PICKS names, matted,
as generated, on the square of the canvas its close-up covers; the illustration as a reference layer. Pictures are
copied as they are.

usage: python assemble.py SPEC.json SPLIT_DIR PICKS.json OUT_DIR
  PICKS: {"layer_id": "split_v7e3_s2", ...}; a layer left out of PICKS takes its best-ranked candidate, "-" drops it,
  "retry_id/candidate" takes a retry's.
"""
import json
import os
import shutil
import sys
from pathlib import Path

from PIL import Image

HEAD = {"face", "ear", "eye", "brow", "mouth", "nose", "hair", "headwear"}
spec = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
split, out = Path(sys.argv[2]), Path(sys.argv[4])
picks = json.loads(Path(sys.argv[3]).read_text(encoding="utf-8")) if Path(sys.argv[3]).exists() else {}
(out / "parts").mkdir(parents=True, exist_ok=True)
art = Path(os.environ.get("L2D_LIVE", ".")) / spec["image"]
src = Image.open(art)
shutil.copy(art, out / "source.png")
layers = [{"id": "source", "name": "source", "image": {"path": "source.png", "width": src.width, "height": src.height},
           "rect": {"left": 0, "top": 0, "width": src.width, "height": src.height}, "reference": True, "opacity": 0.5}]
chosen = {}
for layer in spec["layers"]:
    pick = picks.get(layer["id"], "")
    if pick == "-":
        continue
    # "other_id/candidate" takes a candidate of a retry of this layer (same close-up, another prompt)
    where = pick.split("/")[0] if "/" in pick else layer["id"]
    pick = pick.split("/")[-1]
    meta = json.loads((split / where / "meta.json").read_text(encoding="utf-8"))
    pick = pick or meta["candidates"][0]
    chosen[layer["id"]] = pick
    shutil.copy(split / where / f"{pick}.png", out / "parts" / f"{layer['id']}.png")
    im = Image.open(out / "parts" / f"{layer['id']}.png")
    left, top, span = meta["rect"][:3]
    layers.append({"id": layer["id"], "name": layer["name"], "image": {"path": f"parts/{layer['id']}.png", "width": im.width, "height": im.height},
                   "rect": {"left": left, "top": top, "width": span, "height": span},
                   **({"group": "head"} if HEAD & set(layer["tags"]) else {}), "tags": layer["tags"],
                   "split": {"candidate": pick, "prompt": meta["prompt_full"], "close_up": f"{where}/input.png"}})
manifest = {"format": "psd2live-layout", "version": 1, "name": out.name, "canvas": {"width": src.width, "height": src.height},
            "groups": [{"path": "head"}], "layers": layers}
(out / "layout.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
print(out / "layout.json", len(layers) - 1, "layers", chosen)
