"""Text to image on the 5090's ComfyUI: a new character illustration for the live split test.

usage: python gen_art.py OUT_DIR SEEDS... (prompt from PROMPT below, the one the pink test picture was made with;
  W x H 1024 x 1536)
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "comfy"))
import l2d
from env import client

PROMPT = ("Anime illustration of an original VTuber girl standing straight, front view, full body from head to shoes, "
          "facing the viewer, arms hanging relaxed slightly away from the body, hands open and empty. Medium-long pastel "
          "pink hair with straight bangs and two side locks reaching the chest, a small black ribbon hair clip. Large "
          "amber eyes looking at the viewer, gentle closed-mouth smile. Light blue hooded jacket worn open over a white "
          "t-shirt, black pleated skirt, white knee socks, brown loafers. Clean lineart, flat cel shading, bright even "
          "light, high detail, plain white background, character centred with margin around her.")
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
c = client(timeout=900)
for seed in map(int, sys.argv[2:]):
    files = c.run(l2d.edit_graph(None, PROMPT, 1024, 1536, seed=seed, name=f"art_{seed}"), out, f"art_{seed}")
    print(seed, [f.name for f in files], flush=True)
