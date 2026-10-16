"""How each model builds its parts (its author's conventions), and how those conventions spread over authors.

  python styles.py SCENE_ROOT        write <model>/styles.json for every labelled model and SCENE_ROOT/styles.tsv
                                     (one row per model) plus a summary per convention and per author

Conventions are measured from the scene facts and the labels, never guessed: a mouth with a skin border has a
mouth mesh whose pixels are mostly skin; eyes that close by swapping art have meshes the eye-open parameter fades.
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np
from PIL import Image


def tokens(text: str) -> str:
    text = re.sub(r"([a-z])([A-Z])", r"\1 \2", text)
    return " " + re.sub(r"[_\-.\s\d]+", " ", text).lower().strip() + " "


def author(model: str) -> str:
    """The source a model came from, as a stand-in for its author: the first path part that is not a generic
    collection folder."""
    parts = model.split("/")
    generic = {"live2d", "galgame live2d", "_non_humanoid", "models", "model"}
    for i, p in enumerate(parts[:-1]):
        if p.lower() not in generic:
            return "/".join(parts[:i + 1])
    return parts[0]


def skin_fraction(folder: Path, m: dict, skin) -> float:
    path = folder / "meshes" / f"{m['render']}.png"
    if skin is None or not path.exists():
        return 0.0
    px = np.asarray(Image.open(path).convert("RGBA"), np.float32).reshape(-1, 4)
    px = px[px[:, 3] >= 128][:, :3]
    if len(px) == 0:
        return 0.0
    ref = np.array(skin, np.float32)
    chroma = px / (px.sum(1, keepdims=True) + 1)
    near = np.linalg.norm(chroma - ref / (ref.sum() + 1), axis=1) < 0.045
    light = px.mean(1)
    return float((near & (light > ref.mean() * 0.45) & (light < ref.mean() * 1.12)).mean())


def moved_by(m: dict, pattern: str, fade: bool = False) -> float:
    """Largest relative move (or opacity change) any parameter matching [pattern] gives this mesh."""
    best = 0.0
    for p, a in m.get("answers", {}).items():
        if re.search(pattern, tokens(p)):
            best = max(best, max(abs(a[2]), abs(a[5])) if fade else max(a[1], a[4]))
    return best


def conventions(folder: Path) -> dict:
    scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
    labels = json.loads((folder / "labels.json").read_text(encoding="utf-8"))["labels"]
    meshes = [m for m in scene["meshes"] if m.get("opacity", 1) >= 0.05 or m.get("answers")]
    tag = lambda m: labels[m["id"]]["tag"]
    role = lambda m: labels[m["id"]]["role"]
    by_tag = defaultdict(list)
    for m in meshes:
        by_tag[tag(m)].append(m)
    faces = [m for m in by_tag["face"] if m.get("look", {}).get("rgb")]
    skin = max(faces, key=lambda m: m["look"]["solid"])["look"]["rgb"] if faces else None
    out = {}

    # Mouth: what the mouth parameters move or fade, near the mouth.
    mouth = [m for m in meshes if moved_by(m, r"mouth (open|form)") > 0.03 or moved_by(m, r"mouth (open|form)", fade=True) > 0.3]
    if mouth:
        out["mouth.skin_border"] = any(skin_fraction(folder, m, skin) > 0.5 for m in mouth if m.get("look", {}).get("solid", 0) > 30)
        out["mouth.open_by"] = "swap" if any(moved_by(m, r"mouth open", fade=True) > 0.5 for m in mouth) else "deform"
        out["mouth.inside_layer"] = bool(by_tag["mouth_inside"]) or any(m.get("masks") and any(x in {y["id"] for y in mouth} for x in m["masks"]) for m in meshes)
        out["mouth.meshes"] = len(mouth)

    # Eyes.
    irides = by_tag["irides"]
    if irides:
        out["eye.iris_clipped"] = sum(1 for m in irides if m.get("masks")) >= len(irides) / 2
        eyes = [m for m in meshes if tag(m) in ("eyelash", "eyewhite", "irides")]
        out["eye.close_by"] = "swap" if any(moved_by(m, r"eye ?[lr]? ?open", fade=True) > 0.5 for m in eyes) else "deform"
        highlights = [m for m in irides if role(m) == "highlight"]
        if highlights:
            out["eye.highlight_moves"] = sum(1 for m in highlights if moved_by(m, r"eye ?ball") > 0.03) >= len(highlights) / 2
        out["eye.lash_meshes"] = len(by_tag["eyelash"])

    # Hair: how many pieces, and whether shading is its own layer.
    hair = [m for m in meshes if tag(m) in ("front_hair", "side_hair", "back_hair", "hair")]
    if hair:
        groups = {m.get("deformer") for m in hair if tag(m) == "front_hair"}
        out["hair.front_pieces"] = len(groups)
        shaded = [m for m in hair if role(m) in ("shadow", "highlight")]
        out["hair.shading_layers"] = round(len(shaded) / len(hair), 3)

    # Everywhere: line art apart, shading by blend or by clipping, left and right apart.
    solid = [m for m in meshes if m.get("look", {}).get("solid", 0) > 30]
    if solid:
        out["all.line_layers"] = round(sum(1 for m in solid if role(m) == "line") / len(solid), 3)
        multiply = sum(1 for m in solid if "MULTIPLY" in m.get("blend", ""))
        clipped_dark = sum(1 for m in solid if role(m) == "shadow" and m.get("masks"))
        shadows = sum(1 for m in solid if role(m) == "shadow")
        out["all.shadow_by"] = "multiply" if multiply > 3 else "clipped" if clipped_dark > 3 else "layers" if shadows > 3 else "painted"
        out["all.meshes"] = len(solid)
    return out


def main(root: Path) -> None:
    rows = []
    families = json.loads((root / "families.json").read_text(encoding="utf-8")) if (root / "families.json").exists() else []
    family_of = {m: i for i, f in enumerate(families) for m in f["members"]}
    for folder in sorted(root.glob("scene*/*/")):
        if not (folder / "labels.json").exists():
            continue
        model = json.loads((folder / "labels.json").read_text(encoding="utf-8"))["model"]
        styles = conventions(folder)
        (folder / "styles.json").write_text(json.dumps(styles, ensure_ascii=False), encoding="utf-8")
        key = f"{folder.parent.name}/{folder.name}"
        rows.append({"key": key, "model": model, "author": author(model), "family": family_of.get(key, -1), **styles})
    columns = sorted({k for r in rows for k in r} - {"key", "model", "author", "family"})
    with open(root / "styles.tsv", "w", encoding="utf-8") as f:
        f.write("\t".join(["key", "author", "family"] + columns + ["model"]) + "\n")
        for r in rows:
            f.write("\t".join(str(r.get(c, "")) for c in ["key", "author", "family"] + columns + ["model"]) + "\n")
    # One model per family, so a game with forty costumes counts once.
    seen, distinct = set(), []
    for r in rows:
        if r["family"] not in seen or r["family"] == -1:
            seen.add(r["family"])
            distinct.append(r)
    print(len(rows), "models,", len(distinct), "families")
    for c in columns:
        values = [r[c] for r in distinct if c in r]
        if not values:
            continue
        if isinstance(values[0], (bool, str)):
            print(f"{c:22}", dict(Counter(values).most_common()))
        else:
            v = np.array(values, float)
            print(f"{c:22} median {np.median(v):.3g}  p10 {np.percentile(v, 10):.3g}  p90 {np.percentile(v, 90):.3g}")
    # Authors with several families: how consistent each is.
    by_author = defaultdict(list)
    for r in distinct:
        by_author[r["author"]].append(r)
    print("\nauthors with 3+ families:")
    for a, rs in sorted(by_author.items(), key=lambda kv: -len(kv[1])):
        if len(rs) < 3:
            break
        summary = {c: Counter(r[c] for r in rs if c in r).most_common(1)[0] for c in ("mouth.skin_border", "mouth.open_by", "eye.close_by", "all.shadow_by") if any(c in r for r in rs)}
        print(f"  {a} ({len(rs)}):", {c: f"{v}×{n}" for c, (v, n) in summary.items()})


if __name__ == "__main__":
    main(Path(sys.argv[1]))
