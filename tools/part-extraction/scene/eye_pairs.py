"""Folds the eye close-up pairs (make_pairs.py: one control) into a general-split dataset (two controls).

  python eye_pairs.py EYES_DIR SPLIT_DIR [PER_PART]

EYES_DIR holds train_in/<n>.png and train_tgt/<n>.png + .txt from make_pairs; each part (iris, white, lash, brow, by
its prompt) gives at most PER_PART (900) pairs to SPLIT_DIR/train_in (JPEG), train_hint and train_tgt, named e<n>, with
the general split's prompt. The hint is the part's visible pixels: where the target is drawn and the close-up shows
the same colour.
"""
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from make_node_pairs import prompt

PARTS = {"iris layer": "irides", "eye white": "eyewhite", "eyelid layers": "eyelash", "eyebrow layer": "eyebrow"}


def main(eyes: Path, split: Path, per_part: int = 900) -> None:
    rng = random.Random(0)
    files = sorted((eyes / "train_tgt").glob("*.png"))
    rng.shuffle(files)
    taken = {}
    for tgt_path in files:
        text = tgt_path.with_suffix(".txt").read_text(encoding="utf-8")
        tag = next((t for key, t in PARTS.items() if key in text), None)
        if tag is None or taken.get(tag, 0) >= per_part:
            continue
        inp = np.asarray(Image.open(eyes / "train_in" / tgt_path.name).convert("RGB")).astype(np.int16)
        tgt = np.asarray(Image.open(tgt_path).convert("RGB")).astype(np.int16)
        drawn = np.abs(tgt - 128).max(axis=2) > 18
        visible = drawn & (np.abs(inp - tgt).max(axis=2) < 24)
        if visible.sum() < 20:
            continue
        name = f"e{tgt_path.stem}"
        Image.fromarray(inp.astype(np.uint8)).save(split / "train_in" / f"{name}.jpg", quality=95)
        Image.fromarray((visible * 255).astype(np.uint8)).convert("RGB").save(split / "train_hint" / f"{name}.png")
        Image.fromarray(tgt.astype(np.uint8)).save(split / "train_tgt" / f"{name}.png")
        (split / "train_tgt" / f"{name}.txt").write_text(prompt(tag, 0), encoding="utf-8")
        taken[tag] = taken.get(tag, 0) + 1
    print(taken)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3]) if len(sys.argv) > 3 else 900)
