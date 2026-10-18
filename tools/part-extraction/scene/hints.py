"""Hints as a planner draws them: the visible area a pair marks, loosened so the LoRA reads the hint as a pointer to
the part, not as its outline. Holes closed (a face's eyes and mouth, a strap across a sleeve), grown into a rounded
blob or replaced by a box; the target stays the whole part either way.

Used by make_node_pairs.py (--loose-hints), rehint.py (pairs already built) and node_metrics.py (--hint STYLE).
"""
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

STYLES = ("exact", "filled", "blob", "box")
# Whole tags lean on the prompt and the close-up; single pieces and groups need a tighter pointer among their kin.
WHOLE = (0.3, 0.3, 0.25, 0.15)
PART = (0.6, 0.3, 0.1, 0.0)


def binary(hint: Image.Image) -> Image.Image:
    return hint.convert("L").point(lambda v: 255 if v > 127 else 0)


def filled(hint: Image.Image) -> Image.Image:
    """The hint with every hole closed: what is not reachable from the frame's edge belongs to it."""
    m = binary(hint)
    pad = Image.new("L", (m.width + 2, m.height + 2), 0)
    pad.paste(m, (1, 1))
    ImageDraw.floodfill(pad, (0, 0), 128)
    return Image.fromarray(np.where(np.asarray(pad)[1:-1, 1:-1] == 128, 0, 255).astype(np.uint8))


def grown(m: Image.Image, radius: float, level: int) -> Image.Image:
    """[m] blurred by [radius] and cut at [level]: grown and rounded, faster than a max filter of that size."""
    if radius < 0.5:
        return m
    return m.filter(ImageFilter.GaussianBlur(radius)).point(lambda v: 255 if v > level else 0)


def loosen(hint: Image.Image, style: str, rng: random.Random) -> Image.Image:
    """[hint] drawn in [style] (see STYLES), as RGB like the hints the pairs carry."""
    if style == "exact":
        return hint.convert("RGB")
    w = hint.width
    m = filled(hint)
    if style == "filled":
        m = grown(m, w * rng.uniform(0.003, 0.015), 40)
    elif style == "blob":
        m = grown(m, w * rng.uniform(0.02, 0.06), rng.randint(20, 60))
    elif style == "box":
        box = m.getbbox()
        if box:
            pad = int(w * rng.uniform(0.0, 0.04))
            m = Image.new("L", m.size, 0)
            ImageDraw.Draw(m).rectangle((box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad), fill=255)
    else:
        raise ValueError(style)
    return m.convert("RGB")


def pick(rng: random.Random, whole: bool) -> str:
    return rng.choices(STYLES, WHOLE if whole else PART)[0]
