"""Loosens the hints of training pairs make_node_pairs.py already built (see hints.py), into a folder next to them;
the close-ups, targets and their cached latents stay as they are.

usage: python rehint.py DATA [OUT_NAME]   (OUT_NAME defaults to train_hint_loose)
"""
import os
import random
import sys
import zlib
from collections import Counter
from multiprocessing import Pool
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from hints import loosen, pick

DATA = Path(sys.argv[1])
OUT = DATA / (sys.argv[2] if len(sys.argv) > 2 else "train_hint_loose")


def one(name: str) -> str:
    rng = random.Random(zlib.crc32(name.encode()))
    whole = (DATA / "train_tgt" / f"{name}.txt").read_text(encoding="utf-8").startswith("Live2D layer split")
    style = pick(rng, whole)
    loosen(Image.open(DATA / "train_hint" / f"{name}.png"), style, rng).save(OUT / f"{name}.png")
    return style


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    names = sorted(f[:-4] for f in os.listdir(DATA / "train_hint") if f.endswith(".png"))
    with Pool(min(32, os.cpu_count() or 1)) as pool:
        styles = Counter(pool.map(one, names, chunksize=128))
    print(len(names), "hints loosened into", OUT, dict(styles))
