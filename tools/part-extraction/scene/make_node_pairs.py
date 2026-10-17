"""Training pairs from NodeDatasetTool renders: (close-up, hint) -> the node alone, with a prompt from its tag.

  python make_node_pairs.py OUT NODES_DIR [NODES_DIR...] [--verdicts V.jsonl] [--families F.json] [--held H,...]
      [--size 768] [--min 128] [--reviewed-only] [--styles styles.tsv] [--subnodes S.jsonl --piece-min 256 --piece-share 0.4]
      [--dry] [--test-meta]

OUT/train_in (close-up, JPEG), OUT/train_hint (visible area), OUT/train_tgt (+ .txt prompt), OUT/test/<name>_{input,hint,
target}.png and OUT/test/prompts.jsonl. Verdicts (review_nodes.py) relabel or drop nodes; with --reviewed-only
nodes nobody reviewed are left out. A model whose family holds a held-out model goes to the test set whole.
With --subnodes (subnodes.py's files, which say what kind each finer node is) a single piece of a group (one strand,
one finger) needs --piece-min pixels on a side (the side of a square of its area at native size; the close-up's own
size says little, as small parts are framed to at least 256) and makes at most --piece-share of the training pairs, drawn at random,
so the whole tag and the named groups keep their weight. --low-face/--high-face sort models by how wide their face is
drawn: a low resolution model keeps --low-keep of its training pairs and is never softened (its target is no sharper
than its close-up), a sharp one is softened at --soften-high. A finer node of kind "dropped" (piece_kinds.py: a piece that is
only a cut-off segment of one thing, or too small for its character) is left out. --dry counts the pairs by kind and size without writing;
--test-meta writes only test/meta.jsonl (each test node's kind, sharpness tier and size; prompts.jsonl carries them too).
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
        # Up to soften_max times smaller, never under 64 pixels a side.
        close_up = soft(close_up, rng.uniform(2, max(2.0, min(j.get("soften_max", 4), side / 64))), rng)
    close_up.save(out / "train_in" / f"{name}.jpg", quality=95)
    images[1].resize((side, side), Image.NEAREST).save(out / "train_hint" / f"{name}.png")
    over(images[2], GRAY).resize((side, side), Image.LANCZOS).save(out / "train_tgt" / f"{name}.png")
    (out / "train_tgt" / f"{name}.txt").write_text(j["prompt"], encoding="utf-8")


def test_meta(j: dict) -> dict:
    """What node_metrics.py groups a test node's scores by."""
    return {"name": j["test_name"], "kind": j["kind"], "tier": j["tier"], "pixels": j["pixels"]}


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
    ap.add_argument("--subnodes", action="append", default=[], help="subnodes.py output, for each finer node's kind")
    ap.add_argument("--piece-min", type=int, default=0, help="smallest single piece of a group: side of a square of its area at native size")
    ap.add_argument("--piece-share", type=float, default=1.0, help="largest share of training pairs from single pieces")
    ap.add_argument("--low-face", type=float, default=0, help="a model whose face is narrower than this (native pixels) is low resolution")
    ap.add_argument("--high-face", type=float, default=0, help="a model whose face is at least this wide is sharp")
    ap.add_argument("--low-keep", type=float, default=1.0, help="share of a low resolution model's training pairs kept")
    ap.add_argument("--soften-high", type=float, default=None, help="--soften for sharp models (low resolution ones get none)")
    ap.add_argument("--soften-max", type=float, default=4, help="largest factor a softened close-up is shrunk by")
    ap.add_argument("--dry", action="store_true", help="count the pairs by kind and size, write nothing")
    ap.add_argument("--test-meta", action="store_true", help="write only OUT/test/meta.jsonl: each test node's kind, sharpness tier and size")
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
    kinds = {}
    for path in a.subnodes:
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if line.strip():
                j = json.loads(line)
                kinds[(j["model"], j["node"])] = j.get("kind", "group")
    # How sharp each model is drawn: its face's width at native size (the side of a square of the face node's area).
    face = {}
    if a.low_face or a.high_face:
        for nodes_dir in map(Path, a.nodes):
            for line in (nodes_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines():
                s = json.loads(line)
                if s["tag"] == "face" and not s.get("prompt"):
                    face[s["model"]] = max(face.get(s["model"], 0), s["pixels"] * s.get("area", 0) ** 0.5)
    tier = lambda model: ("low" if 0 < face.get(model, 0) < a.low_face else
                          "high" if a.high_face and face.get(model, 0) >= a.high_face else "mid")
    soften = {"low": 0.0 if a.soften_high is not None else a.soften, "mid": a.soften,
              "high": a.soften if a.soften_high is None else a.soften_high}
    for d in ("train_in", "train_hint", "train_tgt", "test"):
        if not a.dry:
            (out / d).mkdir(parents=True, exist_ok=True)
    jobs, dropped, small, bare = [], 0, 0, 0
    for nodes_dir in map(Path, a.nodes):
        for line in (nodes_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            s = json.loads(line)
            kind = kinds.get((s["model"], s["node"]), "group") if s.get("prompt") else "tag"
            if s["pixels"] < a.min or kind == "dropped" or kind == "piece" and s["pixels"] * s.get("area", 1) ** 0.5 < a.piece_min:
                small += 1
                continue
            if a.pieces_once and s["piece"] and s["variant"]:
                continue
            # A node grouped by the reviewer's per-mesh tags (describe_labels.py) is reviewed already; node verdicts were
            # made on the rules' groups and do not apply to it.
            v = None if s.get("described") else verdicts.get((nodes_dir.name, s["key"], s["node"]))
            if v is not None and not v["keep"]:
                dropped += 1
                continue
            if v is None and a.reviewed_only and not s.get("prompt") and not s.get("described"):
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
                         "soften": soften[tier(s["model"])], "kind": kind, "pixels": s["pixels"], "tier": tier(s["model"]),
                         "soften_max": a.soften_max})
    # Low resolution models teach less: a share of their training pairs, drawn at random.
    if a.low_keep < 1:
        pick = random.Random(1)
        jobs = [j for j in jobs if j["test"] or j["tier"] != "low" or pick.random() < a.low_keep]
    # Single pieces are many; a random share of them keeps the whole tags and named groups weighted.
    train = [j for j in jobs if not j["test"]]
    pieces = [j for j in train if j["kind"] == "piece"]
    room = int(a.piece_share / (1 - a.piece_share) * (len(train) - len(pieces))) if a.piece_share < 1 else len(pieces)
    if len(pieces) > room:
        keep = set(map(id, random.Random(0).sample(pieces, room)))
        jobs = [j for j in jobs if j["test"] or j["kind"] != "piece" or id(j) in keep]
    count = {}
    for j in jobs:
        if not j["test"]:
            band = "<256" if j["pixels"] < 256 else "<512" if j["pixels"] < 512 else "<768" if j["pixels"] < 768 else "768+"
            count.setdefault(j["kind"], {}).setdefault(band, 0)
            count[j["kind"]][band] += 1
    print("training pairs by kind and close-up size:", json.dumps(count), f"({len(pieces)} single pieces before sampling)")
    tiers = {}
    for j in jobs:
        if not j["test"]:
            tiers[j["tier"]] = tiers.get(j["tier"], 0) + 1
    print("training pairs by model sharpness:", json.dumps(tiers), {k: v for k, v in soften.items()})
    if a.test_meta:
        (out / "test" / "meta.jsonl").write_text("".join(json.dumps(test_meta(j)) + "\n" for j in jobs if j["test"]), encoding="utf-8")
        print(sum(j["test"] for j in jobs), "test nodes described")
        return
    if a.dry:
        print(f"{small} too small, {dropped} dropped by review, {bare} bare torsos")
        return
    train_index = 0
    for j in jobs:  # numbering fixed before the workers run, so the output does not depend on their order
        if not j["test"]:
            j["name"] = f"{train_index:06d}"
            train_index += 1
    from multiprocessing import Pool
    with Pool(32) as pool:
        pool.starmap(write_pair, [(j, str(out), i) for i, j in enumerate(jobs)])
    tests = [dict(test_meta(j), tag=j["tag"], prompt=j["prompt"]) for j in jobs if j["test"]]
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
