"""Training pairs from NodeDatasetTool renders: (close-up, hint) -> the node alone, with a prompt from its tag.

  python make_node_pairs.py OUT NODES_DIR [NODES_DIR...] [--verdicts V.jsonl] [--families F.json] [--held H,...]
      [--size 768] [--min 128] [--reviewed-only] [--styles styles.tsv]

OUT/train_in (close-up, JPEG), OUT/train_hint (visible area), OUT/train_tgt (+ .txt prompt), OUT/test/<name>_{input,hint,
target}.png and OUT/test/prompts.jsonl. Verdicts (review_nodes.py) relabel or drop nodes; with --reviewed-only
nodes nobody reviewed are left out. A model whose family holds a held-out model goes to the test set whole.
"""
import argparse
import io
import json
import random
from pathlib import Path

from PIL import Image, ImageFilter

PHRASES = {
    "back_hair": "the back hair", "front_hair": "the front hair (bangs)", "side_hair": "the side locks of hair",
    "headwear": "the headwear or hair ornament", "face": "the base face layer", "facedetail": "the face details (blush, marks)",
    "irides": "the iris with its pupil and highlights", "eyewhite": "the eye white",
    "eyelash": "the eyelid layers (eyelashes, lid lines and eyelid skin)", "eyebrow": "the eyebrow", "ears": "the ear",
    "earwear": "the earring", "nose": "the nose", "mouth": "the mouth", "mouth_inside": "the inside of the mouth (teeth and tongue)",
    "eyewear": "the glasses", "neck": "the base neck layer", "neckwear": "the collar or neckwear", "body": "the base body layer (torso)",
    "arm": "the base arm layer", "hand": "the base hand layer", "leg": "the base leg layer", "topwear": "the top clothing", "bottomwear": "the skirt or pants",
    "handwear": "the glove", "legwear": "the stocking or sock", "footwear": "the shoe", "tail": "the tail",
    "wings": "the wings", "objects": "the held object or accessory",
}
GRAY = (128, 128, 128)


# Base body parts: the character's own body layers as authored, with whatever is painted onto them (underwear,
# painted tights, tattoos), never cut down to bare skin.
SKIN = {"body", "arm", "leg", "neck"}


def skin_cover(sample: dict) -> str | None:
    """How much of a skin node the author painted where clothing hides it: the hidden share of its whole area.
    Some paint the body whole under the clothes, some only the edges, some only what shows."""
    if sample.get("tag") not in SKIN or not sample.get("area"):
        return None
    hidden = 1 - min(1.0, sample["visible"] / sample["area"])
    return "complete" if hidden >= 0.5 else "visible" if hidden < 0.15 else "edges"


WEAR = {
    "bare": ", bare skin",
    "tight": {"leg": ", with the stockings or tights painted on it", "arm": ", with the glove or sleeve painted on it",
              "hand": ", with the glove painted on it", "body": ", with the bodysuit painted on it"},
    "underwear": ", with the underwear painted on it",
}


def prompt(tag: str, piece: int, style: dict | None = None, cover: str | None = None, wear: str | None = None) -> str:
    """The node in words; where authors build the part in different ways, the way this model's author did."""
    what = PHRASES[tag] + (", one piece of it" if piece else "")
    style = style or {}
    if tag == "mouth" and style.get("mouth.skin_border") in ("True", "False"):
        what += ", with the skin-coloured patch around it" if style["mouth.skin_border"] == "True" else ", lips and lines only"
    if tag in SKIN and cover:
        what += {"complete": ", drawn complete under the clothes", "edges": ", drawn a little past the clothing edges",
                 "visible": ", only where it shows"}[cover]
    # Tight wear and underwear are often painted on the base part rather than split off: which one this is.
    if tag in SKIN | {"hand"} and wear in WEAR:
        what += WEAR[wear][tag] if isinstance(WEAR[wear], dict) else WEAR[wear] if (wear != "tight" or tag in WEAR["tight"]) else ""
    return (f"Live2D layer split: keep only {what}, the part marked in the second image, drawn complete including "
            f"any part hidden behind other layers, exactly in place, on a flat gray background.")


def write_pair(j: dict, out: str, index: int) -> None:
    """One pair: the close-up on a random light background, the hint, the target on gray; training pairs flipped
    at random. Each pair draws from its own seed, so pairs come out the same however the work is split."""
    rng = random.Random(index)
    out = Path(out)
    inp = Image.open(f"{j['stem']}_input.png").convert("RGBA")
    hint = Image.open(f"{j['stem']}_hint.png").convert("RGB")
    tgt = Image.open(f"{j['stem']}_target.png").convert("RGBA")
    bg = tuple(rng.randint(200, 250) for _ in range(3))
    side = j["side"]
    if j["test"]:
        name = j["test_name"]
        over(inp, bg).resize((side, side), Image.LANCZOS).save(out / "test" / f"{name}_input.png")
        hint.resize((side, side), Image.NEAREST).save(out / "test" / f"{name}_hint.png")
        over(tgt, GRAY).resize((side, side), Image.LANCZOS).save(out / "test" / f"{name}_target.png")
        if side >= 256:  # the same close-up at a third of its size, for scoring how well the LoRA restores detail
            soft(over(inp, bg).resize((side, side), Image.LANCZOS), 3, random.Random(index)).save(out / "test" / f"{name}_input_soft.png")
        return
    if j.get("rough") and rng.random() < 0.5:
        # A rougher hint, as a planner would draw it: grown or shrunk by a few percent of the frame.
        r = max(1, int(hint.width * rng.uniform(0.005, 0.03)))
        grow = rng.random() < 0.6
        hint = hint.filter(ImageFilter.MaxFilter(2 * r + 1) if grow else ImageFilter.MinFilter(2 * r + 1))
    images = [inp, hint, tgt]
    if rng.random() < 0.5:
        images = [i.transpose(Image.FLIP_LEFT_RIGHT) for i in images]
    name = j["name"]
    close_up = over(images[0], bg).resize((side, side), Image.LANCZOS)
    if side >= 256 and rng.random() < j.get("soften", 0):
        # A blurrier close-up than the target: the LoRA learns to paint the part at the art's own sharpness whatever
        # the size of the illustration it reads.
        close_up = soft(close_up, rng.uniform(2, 4), rng)
    close_up.save(out / "train_in" / f"{name}.jpg", quality=95)
    images[1].resize((side, side), Image.NEAREST).save(out / "train_hint" / f"{name}.png")
    over(images[2], GRAY).resize((side, side), Image.LANCZOS).save(out / "train_tgt" / f"{name}.png")
    (out / "train_tgt" / f"{name}.txt").write_text(j["prompt"], encoding="utf-8")


def soft(img: Image.Image, factor: float, rng: random.Random) -> Image.Image:
    """[img] as if read from a picture [factor] times smaller, as such pictures come: JPEG-compressed at times."""
    small = img.resize((max(16, round(img.width / factor)), max(16, round(img.height / factor))), Image.LANCZOS)
    if rng.random() < 0.5:
        buf = io.BytesIO()
        small.convert("RGB").save(buf, "JPEG", quality=rng.randint(60, 95))
        small = Image.open(io.BytesIO(buf.getvalue())).convert(img.mode)
    return small.resize(img.size, Image.BICUBIC)


def over(img: Image.Image, color) -> Image.Image:
    bg = Image.new("RGBA", img.size, color + (255,))
    bg.alpha_composite(img.convert("RGBA"))
    return bg.convert("RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("nodes", nargs="+")
    ap.add_argument("--verdicts", action="append", default=[], help="review verdicts; later files override earlier ones")
    ap.add_argument("--families")
    ap.add_argument("--held", default="")
    ap.add_argument("--size", type=int, default=768)
    ap.add_argument("--min", type=int, default=128)
    ap.add_argument("--reviewed-only", action="store_true")
    ap.add_argument("--pieces-once", action="store_true", help="only the rest-pose close-up of each piece")
    ap.add_argument("--rough-hints", action="store_true", help="grow or shrink half the training hints")
    ap.add_argument("--soften", type=float, default=0, help="share of training close-ups of 256 and up read from a 2-4 times smaller, at times JPEG-compressed picture")
    ap.add_argument("--styles", help="styles.tsv from styles.py: authoring conventions by model")
    ap.add_argument("--wear", help="jsonl of base body nodes and what is painted on them (bare, tight, underwear)")
    a = ap.parse_args()
    out = Path(a.out)
    verdicts = {}
    for path in a.verdicts:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if line.strip():
                v = json.loads(line)
                verdicts[(v["dir"], v["key"], v["node"])] = v
    wear = {}
    if a.wear:
        for line in Path(a.wear).read_text(encoding="utf-8").splitlines():
            if line.strip():
                w = json.loads(line)
                if w.get("wear") in WEAR:
                    wear[(w["dir"], w["key"], w["node"])] = w["wear"]
    styles = {}
    if a.styles:
        rows = Path(a.styles).read_text(encoding="utf-8").splitlines()
        head = rows[0].split("\t")
        for row in rows[1:]:
            values = dict(zip(head, row.split("\t")))
            styles[values["model"]] = values
    held = [h for h in a.held.split(",") if h]
    held_models = set()
    if a.families and held:
        # Every member of a family that holds a held-out model is held out.
        for f in json.loads(Path(a.families).read_text(encoding="utf-8")):
            if any(any(h in m for h in held) for m in f["models"]):
                held_models.update(f["models"])
    for d in ("train_in", "train_hint", "train_tgt", "test"):
        (out / d).mkdir(parents=True, exist_ok=True)
    jobs, dropped, small, bare = [], 0, 0, 0
    for nodes_dir in map(Path, a.nodes):
        for line in (nodes_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            s = json.loads(line)
            if s["pixels"] < a.min:
                small += 1
                continue
            if a.pieces_once and s["piece"] and s["variant"]:
                continue
            v = verdicts.get((nodes_dir.name, s["key"], s["node"]))
            if v is not None and not v["keep"]:
                dropped += 1
                continue
            if v is None and a.reviewed_only and not s.get("prompt"):
                continue  # a finer node carries a reviewer's own name for it, which is its review
            tag = v["tag"] if v else s["tag"]
            if tag not in PHRASES:
                continue
            # No bare torso is taught as a completion: a body drawn whole under the clothes counts only with underwear
            # or tight wear painted on it, and the body's finer pieces are left out.
            cover, worn = skin_cover(dict(s, tag=tag)), wear.get((nodes_dir.name, s["key"], s["node"]))
            if tag == "body" and (s.get("prompt") or (cover == "complete" and worn not in ("underwear", "tight"))):
                bare += 1
                continue
            test = s["model"] in held_models or any(h in s["model"] for h in held)
            # A finer node named by a reviewer carries its own words.
            words = s.get("prompt") or prompt(tag, s["piece"], styles.get(s["model"]), cover, worn)
            jobs.append({"stem": str(nodes_dir / s["key"] / f"{s['node']}_{s['variant']}"), "side": min(a.size, max(256, s["pixels"] // 64 * 64)),
                         "test": test, "tag": tag, "prompt": words,
                         "test_name": f"{nodes_dir.name}_{s['key']}_{s['node']}_{s['variant']}", "rough": a.rough_hints,
                         "soften": a.soften})
    train_index = 0
    for j in jobs:  # numbering fixed before the workers run, so the output does not depend on their order
        if not j["test"]:
            j["name"] = f"{train_index:06d}"
            train_index += 1
    from multiprocessing import Pool
    with Pool(32) as pool:
        pool.starmap(write_pair, [(j, str(out), i) for i, j in enumerate(jobs)])
    tests = [{"name": j["test_name"], "tag": j["tag"], "prompt": j["prompt"]} for j in jobs if j["test"]]
    stats = {}
    for j in jobs:
        if not j["test"]:
            stats[j["tag"]] = stats.get(j["tag"], 0) + 1
    n_train, n_test = train_index, len(tests)
    (out / "test" / "prompts.jsonl").write_text("\n".join(json.dumps(t) for t in tests) + "\n", encoding="utf-8")
    print(f"train {n_train}, test {n_test}, dropped {dropped} by review, {small} too small, {bare} bare torsos")
    print(dict(sorted(stats.items(), key=lambda kv: -kv[1])))


if __name__ == "__main__":
    main()
