"""Training pairs from NodeDatasetTool renders: (close-up, hint) -> the node alone, with a prompt from its tag.

  python make_node_pairs.py OUT NODES_DIR [NODES_DIR...] [--verdicts V.jsonl] [--families F.json] [--held H,...]
      [--size 768] [--min 128] [--reviewed-only]

OUT/train_in (close-up), OUT/train_hint (visible area), OUT/train_tgt (+ .txt prompt), OUT/test/<name>_{input,hint,
target}.png and OUT/test/prompts.jsonl. Verdicts (review_nodes.py) relabel or drop nodes; with --reviewed-only
nodes nobody reviewed are left out. A model whose family holds a held-out model goes to the test set whole.
"""
import argparse
import json
import random
from pathlib import Path

from PIL import Image

PHRASES = {
    "back_hair": "the back hair", "front_hair": "the front hair (bangs)", "side_hair": "the side locks of hair",
    "headwear": "the headwear or hair ornament", "face": "the face skin", "facedetail": "the face details (blush, marks)",
    "irides": "the iris with its pupil and highlights", "eyewhite": "the eye white",
    "eyelash": "the eyelid layers (eyelashes, lid lines and eyelid skin)", "eyebrow": "the eyebrow", "ears": "the ear",
    "earwear": "the earring", "nose": "the nose", "mouth": "the mouth", "mouth_inside": "the inside of the mouth (teeth and tongue)",
    "eyewear": "the glasses", "neck": "the neck", "neckwear": "the collar or neckwear", "body": "the body skin",
    "arm": "the arm", "hand": "the hand", "leg": "the leg", "topwear": "the top clothing", "bottomwear": "the skirt or pants",
    "handwear": "the glove", "legwear": "the stocking or sock", "footwear": "the shoe", "tail": "the tail",
    "wings": "the wings", "objects": "the held object or accessory",
}
GRAY = (128, 128, 128)


def prompt(tag: str, piece: int) -> str:
    what = PHRASES[tag] + (", one piece of it" if piece else "")
    return (f"Live2D layer split: keep only {what}, the part marked in the second image, drawn complete including "
            f"any part hidden behind other layers, exactly in place, on a flat gray background.")


def over(img: Image.Image, color) -> Image.Image:
    bg = Image.new("RGBA", img.size, color + (255,))
    bg.alpha_composite(img.convert("RGBA"))
    return bg.convert("RGB")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("nodes", nargs="+")
    ap.add_argument("--verdicts")
    ap.add_argument("--families")
    ap.add_argument("--held", default="")
    ap.add_argument("--size", type=int, default=768)
    ap.add_argument("--min", type=int, default=128)
    ap.add_argument("--reviewed-only", action="store_true")
    a = ap.parse_args()
    out = Path(a.out)
    rng = random.Random(0)
    verdicts = {}
    if a.verdicts:
        for line in Path(a.verdicts).read_text(encoding="utf-8").splitlines():
            if line.strip():
                v = json.loads(line)
                verdicts[(v["dir"], v["key"], v["node"])] = v
    held = [h for h in a.held.split(",") if h]
    held_models = set()
    if a.families and held:
        # Every member of a family that holds a held-out model is held out.
        for f in json.loads(Path(a.families).read_text(encoding="utf-8")):
            if any(any(h in m for h in held) for m in f["models"]):
                held_models.update(f["models"])
    for d in ("train_in", "train_hint", "train_tgt", "test"):
        (out / d).mkdir(parents=True, exist_ok=True)
    n_train = n_test = dropped = small = 0
    stats = {}
    tests = []
    for nodes_dir in map(Path, a.nodes):
        for line in (nodes_dir / "samples.jsonl").read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            s = json.loads(line)
            if s["pixels"] < a.min:
                small += 1
                continue
            v = verdicts.get((nodes_dir.name, s["key"], s["node"]))
            if v is not None and not v["keep"]:
                dropped += 1
                continue
            if v is None and a.reviewed_only:
                continue
            tag = v["tag"] if v else s["tag"]
            if tag not in PHRASES:
                continue
            stem = nodes_dir / s["key"] / f"{s['node']}_{s['variant']}"
            side = min(a.size, max(256, s["pixels"] // 64 * 64))
            inp = Image.open(f"{stem}_input.png")
            hint = Image.open(f"{stem}_hint.png").convert("RGB")
            tgt = Image.open(f"{stem}_target.png")
            bg = tuple(rng.randint(200, 250) for _ in range(3))
            test = s["model"] in held_models or any(h in s["model"] for h in held)
            if test:
                name = f"{nodes_dir.name}_{s['key']}_{s['node']}_{s['variant']}"
                over(inp, bg).resize((side, side), Image.LANCZOS).save(out / "test" / f"{name}_input.png")
                hint.resize((side, side), Image.NEAREST).save(out / "test" / f"{name}_hint.png")
                over(tgt, GRAY).resize((side, side), Image.LANCZOS).save(out / "test" / f"{name}_target.png")
                tests.append({"name": name, "tag": tag, "prompt": prompt(tag, s["piece"])})
                n_test += 1
                continue
            images = [inp.convert("RGBA"), hint, tgt.convert("RGBA")]
            if rng.random() < 0.5:
                images = [i.transpose(Image.FLIP_LEFT_RIGHT) for i in images]
            name = f"{n_train:06d}"
            over(images[0], bg).resize((side, side), Image.LANCZOS).save(out / "train_in" / f"{name}.png")
            images[1].resize((side, side), Image.NEAREST).save(out / "train_hint" / f"{name}.png")
            over(images[2], GRAY).resize((side, side), Image.LANCZOS).save(out / "train_tgt" / f"{name}.png")
            (out / "train_tgt" / f"{name}.txt").write_text(prompt(tag, s["piece"]), encoding="utf-8")
            stats[tag] = stats.get(tag, 0) + 1
            n_train += 1
    (out / "test" / "prompts.jsonl").write_text("\n".join(json.dumps(t) for t in tests) + "\n", encoding="utf-8")
    print(f"train {n_train}, test {n_test}, dropped {dropped} by review, {small} too small")
    print(dict(sorted(stats.items(), key=lambda kv: -kv[1])))


if __name__ == "__main__":
    main()
