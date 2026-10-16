"""Semantic labels for the meshes of a SceneGraphTool scene: rules over names, motion and look.

  python label.py SCENE_DIR [SCENE_DIR...]      label every model folder (with scene.json) under each SCENE_DIR,
                                                 writing labels.json and labelmap.png beside scene.json

Each mesh gets a tag (TAGS), a role (base / line / shadow / highlight), a side and a confidence. Evidence is text
(mesh, part chain, deformer chain, the parameters and physics groups that move it) matched against a dictionary in
Japanese, Chinese and English, plus what standard parameters do to it and how it looks.
"""
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

# First level as PSD2Live's SemanticTag where it has one, plus what it lacks (skin of the body and limbs, effects).
TAGS = {
    "back_hair": (120, 70, 40), "front_hair": (250, 200, 60), "side_hair": (230, 140, 40), "hair": (200, 160, 90),
    "headwear": (180, 60, 200), "face": (255, 220, 200), "facedetail": (255, 120, 150), "irides": (40, 90, 255),
    "eyebrow": (90, 50, 20), "eyewhite": (235, 245, 255), "eyelash": (20, 20, 20), "eyewear": (120, 120, 200),
    "ears": (230, 180, 160), "earwear": (200, 80, 120), "nose": (220, 150, 130), "mouth": (220, 40, 60),
    "mouth_inside": (140, 0, 30), "neck": (240, 200, 180), "neckwear": (0, 160, 160), "body": (250, 190, 170),
    "arm": (240, 170, 150), "hand": (230, 150, 130), "leg": (220, 160, 140), "topwear": (40, 160, 80),
    "bottomwear": (20, 100, 160), "handwear": (100, 200, 160), "legwear": (60, 60, 120), "footwear": (90, 60, 60),
    "tail": (160, 110, 60), "wings": (200, 230, 255), "objects": (150, 150, 0), "effect": (255, 255, 0),
    "background": (90, 90, 90), "unknown": (255, 0, 255),
}

# (tag, pattern): CJK by substring, Latin by whole token after camelCase and separators are split.
WORDS = [
    ("irides", r"瞳|目玉|眼球|虹彩|黒目|黑眼|眼珠|瞳孔|ひとみ|\b(iris|irides|pupil|eyeball|hitomi|medama)\b"),
    ("eyewhite", r"白目|眼白|しろめ|\b(sclera|eyewhite|shirome)\b|\beye white\b"),
    ("eyelash", r"まつ毛|まつげ|睫|まぶた|瞼|眼睑|眼皮|アイライン|二重|\b(lash|lashes|eyelash|eyelid|lid|eyeline|eyeliner)\b|\beye (open|close|smile)\b"),
    ("eyebrow", r"眉|まゆ|\b(brow|eyebrow|mayu)\b"),
    ("mouth_inside", r"歯|牙|舌|口内|口の中|口腔|\b(teeth|tooth|tongue|inner mouth|oral)\b"),
    ("mouth", r"口|唇|くち|嘴|\b(mouth|lip|lips|kuchi)\b"),
    ("nose", r"鼻|\b(nose|hana)\b"),
    ("facedetail", r"頬|ほお|腮|赤面|紅潮|チーク|照れ|ほくろ|痣|そばかす|\b(blush|cheek|cheeks|mole|freckle|freckles)\b"),
    ("earwear", r"耳飾|イヤリング|ピアス|耳环|耳坠|\b(earring|earrings|piercing)\b|\bear (acc|accessory)\b"),
    ("ears", r"耳|\b(ear|ears|mimi)\b"),
    ("face", r"顔|輪郭|脸|面部|\b(face|kao|contour)\b"),
    ("front_hair", r"前髪|刘海|前发|前髮|アホ毛|呆毛|触角|\b(bangs|fringe|maegami|ahoge)\b|\bhair front\b|\bfront hair\b"),
    ("side_hair", r"横髪|もみあげ|サイド髪|侧发|鬓|鬢|\b(sidelock|sidelocks|yokogami)\b|\bhair side\b|\bside hair\b"),
    ("back_hair", r"後ろ髪|後髪|后发|後髮|背发|ポニー|ツイン|おさげ|马尾|辫|辮|\b(ushirogami|ponytail|twintail|twintails|braid)\b|\bhair back\b|\bback hair\b"),
    ("hair", r"髪|头发|頭髮|发丝|毛先|\b(hair|kami)\b"),
    ("headwear", r"帽|冠|カチューシャ|ヘアピン|髪飾|髪留|发饰|头饰|發飾|フード|ヘッドドレス|ティアラ|\b(hat|cap|crown|hairpin|hairclip|headband|hood|headdress|tiara|helmet|beret)\b"),
    ("eyewear", r"眼鏡|メガネ|眼镜|ゴーグル|\b(glasses|goggles|spectacles|monocle)\b"),
    ("neckwear", r"襟|首輪|チョーカー|ネクタイ|ネックレス|マフラー|领|項鍊|项链|领带|围巾|\b(collar|choker|necklace|tie|necktie|scarf|muffler)\b"),
    ("neck", r"首|脖|頸|\b(neck|kubi)\b"),
    ("handwear", r"手袋|手套|\b(glove|gloves|mitten)\b"),
    ("hand", r"手|指|掌|\b(hand|hands|finger|fingers|palm)\b"),
    ("arm", r"腕|手臂|胳膊|\b(arm|arms|elbow|ude)\b"),
    ("footwear", r"靴|鞋|ブーツ|\b(shoe|shoes|boot|boots|sandal)\b"),
    ("legwear", r"靴下|ニーソ|タイツ|ストッキング|袜|\b(sock|socks|stocking|stockings|tights|kneesocks)\b"),
    ("leg", r"脚|腿|太もも|もも|膝|\b(leg|legs|thigh|knee|ashi)\b"),
    ("bottomwear", r"ベルト|腰带|\b(belt|waist)\b|スカート|ズボン|パンツ|短パン|裙|裤|褲|\b(skirt|pants|shorts|trousers)\b"),
    ("topwear", r"服|衣|シャツ|上着|ジャケット|袖|外套|コート|ブラウス|セーラー|ドレス|ワンピ|ローブ|マント|ケープ|ポンチョ|着物|羽織|袴|浴衣|コルセット|フリル|ベスト|パーカー|ボタン|\b(shirt|jacket|coat|sleeve|sleeves|sode|robe|cape|cloak|mantle|poncho|kimono|yukata|corset|frill|parka|sweater|cardigan|blouse|dress|vest|hoodie|uniform|clothes|cloth|button)\b"),
    ("body", r"体|胴|胸|腹|おなか|身体|躯|軀|\b(body|torso|chest|bust|belly|breast|karada)\b"),
    ("tail", r"尻尾|しっぽ|尾巴|\b(tail|shippo)\b"),
    ("wings", r"翼|羽|翅|\b(wing|wings|feather)\b"),
    ("effect", r"汗|涙|泪|怒|効果|エフェクト|符号|漫符|マーク|キラ|ハート|星|\b(effect|sweat|tear|tears|sparkle|heart|star|symbol)\b"),
    ("background", r"背景|\b(bg|background|backdrop)\b"),
    ("objects", r"チェーン|鎖|链|\b(chain|chains|string|strings|pocket|charm|strap|acc|accessory)\b|武器|剣|剑|杖|本|道具|旗|傘|伞|ぬいぐるみ|\b(weapon|sword|staff|wand|book|item|prop|umbrella|flag|plush)\b"),
]
ROLES = [
    ("shadow", r"影|かげ|阴影|陰影|暗部|乗算|\b(shadow|shade|kage|multiply)\b"),
    ("highlight", r"ハイライト|高光|反射|光|艶|ツヤ|\b(highlight|hl|light|glow|gloss|shine)\b"),
    ("line", r"線|线|ライン|描线|\b(line|lines|lineart|outline)\b"),
]
GENERIC = re.compile(r"^(art ?mesh|part|parts|warp|rotation|deformer|layer|group|グループ|レイヤー|图层|圖層|コピー|copy|複製|new|untitled)?[\s_\-.]*\d*$", re.I)


def tokens(text: str) -> str:
    """Lower-case words: camelCase, digits and separators split, so ParamHairFront reads 'param hair front'."""
    text = re.sub(r"([a-z])([A-Z])", r"\1 \2", text)
    text = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", text)
    return " " + re.sub(r"[_\-.\s\d]+", " ", text).lower().strip() + " "


def matches(text: str, table) -> list[str]:
    t = tokens(text)
    return [tag for tag, pattern in table if re.search(pattern, t)]


def label_model(folder: Path) -> dict:
    scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
    parts = {p["id"]: p for p in scene["parts"]}
    deformers = {d["id"]: d for d in scene["deformers"]}
    params = {p["id"]: p for p in scene["parameters"]}
    meshes = scene["meshes"]
    by_id = {m["id"]: m for m in meshes}
    physics_of = defaultdict(list)
    for g in scene["physics"]:
        for p in g["outputs"]:
            physics_of[p].append(g["name"])
    # How many meshes each parameter moves noticeably: a parameter that moves everything says nothing about any one.
    reach = defaultdict(int)
    for m in meshes:
        for p, a in m.get("answers", {}).items():
            if max(a[1], a[4]) > 0.02 or max(abs(a[2]), abs(a[5])) > 0.3:
                reach[p] += 1
    total = max(1, len(meshes))

    def named(text):
        return text and not GENERIC.match(text.strip())

    def evidence(m):
        """(text, weight, source) pairs for one mesh."""
        out = []
        if named(m["name"]):
            out.append((m["name"], 5.0, "mesh"))
        # The nearest named part speaks for the mesh; ancestors only lean (a staff's part sits in the arm's).
        part, depth = m.get("part"), 0
        while part and part in parts:
            if named(parts[part]["name"]):
                out.append((parts[part]["name"], 4.0 if depth == 0 else 1.2 * 0.6 ** (depth - 1), "part"))
                depth += 1
            part = parts[part].get("parent")
        d, depth = m.get("deformer"), 0
        while d and d in deformers:
            if named(deformers[d]["name"]):
                out.append((deformers[d]["name"], max(0.5, 1.5 - 0.4 * depth), "deformer"))
            d, depth = deformers[d].get("parent"), depth + 1
        # The parameters that are this mesh's own: of those moving it, the ones that move the fewest meshes. A
        # parameter moving it through a parent deformer (head angle, body, shoulder) moves many more.
        moving = [(reach[p], p) for p, a in m.get("answers", {}).items()
                  if (max(a[1], a[4]) > 0.03 or max(abs(a[2]), abs(a[5])) > 0.3) and reach[p] < total * 0.5]
        if moving:
            least = min(r for r, _ in moving)
            own = [p for r, p in moving if r <= least * 2 + 2]
            for p in own:
                info = params.get(p, {})
                w = 2.5 / len(own)  # all its own parameters together weigh as one name
                out.append((f"{p} {info.get('name', '')}", w, "param"))
                if info.get("group"):
                    out.append((info["group"], w * 0.6, "param group"))
                for g in physics_of.get(p, []):
                    out.append((g, w * 0.8, "physics"))
        return out

    labels = {}
    for m in meshes:
        votes, role_votes, sources = defaultdict(float), defaultdict(float), defaultdict(list)
        for text, w, src in evidence(m):
            hits = matches(text, WORDS)
            # Within one text the first (most specific) hit counts fully, later ones a little.
            for i, tag in enumerate(hits):
                votes[tag] += w if i == 0 else w * 0.3
                sources[tag].append(src)
            for role in matches(text, ROLES):
                role_votes[role] += w
        look = m.get("look", {})
        answers = m.get("answers", {})
        # Standard parameters by what they do: the eye ball moves the iris, eye-open the lids.
        ball = max((max(a[1], a[4]) for p, a in answers.items() if re.search(r"eye ?ball", tokens(p))), default=0)
        if ball > 0.06:
            votes["irides"] += 4 * min(1, ball * 5)
        blend = m.get("blend", "")
        if "MULTIPLY" in blend:
            role_votes["shadow"] += 3
        elif "ADD" in blend or "SCREEN" in blend:
            role_votes["highlight"] += 3
        thickness = look.get("thickness", 99)
        rgb = look.get("rgb")
        lum = (0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]) if rgb else 128
        if rgb and thickness < 3 and lum < 90:
            role_votes["line"] += 1.5
        # Hair parts the names do not place: front or back by draw order against the face.
        tag, confidence = "unknown", 0.0
        if votes:
            ranked = sorted(votes.items(), key=lambda kv: -kv[1])
            top, score = ranked[0]
            runner = ranked[1][1] if len(ranked) > 1 else 0.0
            tag = top
            confidence = round(min(1.0, score / 4) * (score - runner) / max(score, 1e-6), 3)
        role = max(role_votes, key=role_votes.get) if role_votes and max(role_votes.values()) >= 1.5 else "base"
        labels[m["id"]] = {"tag": tag, "role": role, "confidence": confidence, "lum": round(lum),
                           "votes": {k: round(v, 2) for k, v in sorted(votes.items(), key=lambda kv: -kv[1])[:4]},
                           "sources": {k: sorted(set(v)) for k, v in sources.items() if k in dict(list(votes.items()))}}
    refine(scene, labels, by_id, folder)
    return {"model": scene["model"], "labels": labels}


def lum(rgb):
    return 0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2]


def head_response(m):
    """How far the head-angle parameters move this mesh for its size; they leave the body still."""
    return max((max(a[1], a[4]) for p, a in m.get("answers", {}).items()
                if re.search(r"(^|[^a-z])angle ?[xyz]", tokens(p)) and "body" not in tokens(p)), default=0)


def refine(scene, labels, by_id, folder):
    """Context the per-mesh votes cannot see:
    - meshes with no evidence of their own take the tag most of their deformer siblings have;
    - the face is the large skin-coloured head mesh drawn under the eyes, and its colour is the skin reference;
    - limbs and body that are not skin-coloured are clothing on them (feet lowest), skin-coloured head meshes face;
    - generic hair goes front or back by draw order against the face;
    - the eye white and lids are told apart by colour."""
    meshes = scene["meshes"]
    solid = [m for m in meshes if m.get("look", {}).get("rgb") and m["look"]["solid"] > 0]
    head = {m["id"] for m in meshes if head_response(m) > 0.01}

    def siblings_vote():
        groups = defaultdict(list)
        for m in meshes:
            groups[m.get("deformer") or m.get("part")].append(m)
        for group in groups.values():
            known = defaultdict(float)
            for m in group:
                lab = labels[m["id"]]
                if lab["tag"] != "unknown":
                    known[lab["tag"]] += max(0.05, lab["confidence"]) * (m.get("look", {}).get("solid", 0) + 1) ** 0.5
            if not known:
                continue
            tag = max(known, key=known.get)
            share = known[tag] / sum(known.values())
            for m in group:
                lab = labels[m["id"]]
                if lab["tag"] == "unknown":
                    lab["tag"], lab["confidence"], lab["by"] = tag, round(0.3 * share, 3), "siblings"

    # The skin reference: a face the names found, else the largest light, warm head mesh under the irides.
    irides = [m["render"] for m in meshes if labels[m["id"]]["tag"] == "irides"]
    under_eyes = min(irides) if irides else 10 ** 9
    warm = lambda rgb: rgb[0] >= rgb[1] >= rgb[2] - 10 and rgb[0] - rgb[2] > 15 and lum(rgb) > 110
    faces = [m for m in solid if labels[m["id"]]["tag"] == "face" and warm(m["look"]["rgb"])] or \
        [m for m in solid if m["id"] in head and m["render"] < under_eyes and warm(m["look"]["rgb"])
         and labels[m["id"]]["tag"] in ("unknown", "face", "neck", "ears")]
    face = max(faces, key=lambda m: m["look"]["solid"], default=None)
    skin = face["look"]["rgb"] if face else None

    def is_skin(m):
        """Whether most of the mesh's solid pixels share the skin's hue, at any shading."""
        if skin is None or "at" not in m:
            return None
        path = folder / "meshes" / f"{m['render']}.png"
        if not path.exists():
            return None
        px = np.asarray(Image.open(path).convert("RGBA"), np.float32).reshape(-1, 4)
        px = px[px[:, 3] >= 128][:, :3]
        if len(px) == 0:
            return None
        ref = np.array(skin, np.float32)
        chroma = px / (px.sum(1, keepdims=True) + 1)
        near = np.linalg.norm(chroma - ref / (ref.sum() + 1), axis=1) < 0.045
        light = px.mean(1)
        return float((near & (light > ref.mean() * 0.45) & (light < ref.mean() * 1.12)).mean()) > 0.35

    if face and labels[face["id"]]["tag"] != "face":
        labels[face["id"]].update(tag="face", confidence=0.5, by="skin reference")
    feet_line = None
    boxes = [m["box"] for m in meshes if m.get("box") and m.get("at")]
    if boxes:
        top, bottom = min(b[1] for b in boxes), max(b[3] for b in boxes)
        feet_line = bottom - (bottom - top) * 0.12
    clothing = {"arm": "topwear", "hand": "handwear", "body": "topwear", "neck": "neckwear"}
    for m in meshes:
        lab = labels[m["id"]]
        skinny = is_skin(m)
        if lab["tag"] in clothing and skinny is False:
            lab.update(tag=clothing[lab["tag"]], confidence=round(lab["confidence"] * 0.7, 3), by="not skin")
        elif lab["tag"] == "leg" and skinny is False:
            low = feet_line is not None and m.get("box") and m["box"][1] > feet_line - (m["box"][3] - m["box"][1]) * 0.5
            lab.update(tag="footwear" if low else "legwear", confidence=round(lab["confidence"] * 0.6, 3), by="not skin")
        elif lab["tag"] == "unknown" and m["id"] in head and face and m["render"] < under_eyes:
            if skinny:
                lab.update(tag="face", confidence=0.3, by="head skin")
            elif m.get("box") and face.get("box") and m["box"][3] < face["box"][1] + (face["box"][3] - face["box"][1]) * 0.2:
                lab.update(tag="headwear", confidence=0.2, by="above the face")
    siblings_vote()
    contained(meshes, labels)
    face_meshes = [m for m in meshes if labels[m["id"]]["tag"] == "face"]
    face_render = min((m["render"] for m in face_meshes), default=None)
    for m in meshes:
        lab = labels[m["id"]]
        if lab["tag"] == "hair" and face_render is not None:
            lab["tag"] = "back_hair" if m["render"] < face_render else "front_hair"
            lab["confidence"] = round(lab["confidence"] * 0.6, 3)
        if lab["tag"] in ("eyelash", "eyewhite") and m.get("look", {}).get("rgb"):
            rgb = m["look"]["rgb"]
            if lab["tag"] == "eyelash" and lum(rgb) > 215 and m["look"].get("thickness", 0) > 4 and "eyewhite" not in lab["votes"]:
                lab["tag"], lab["confidence"] = "eyewhite", round(lab["confidence"] * 0.7, 3)


def contained(meshes, labels):
    """A small unsure mesh lying inside a surer one drawn under it is detail on that part (a nail's gloss, a
    shadow on a sleeve): it takes that part's tag, and a role by whether it is lighter or darker."""
    boxed = [m for m in meshes if m.get("box") and m.get("look", {}).get("rgb")]
    for m in boxed:
        lab = labels[m["id"]]
        if lab["confidence"] >= 0.25:
            continue
        x0, y0, x1, y1 = m["box"]
        area = (x1 - x0) * (y1 - y0)
        best = None
        for o in boxed:
            ol = labels[o["id"]]
            if o is m or ol["confidence"] < 0.35 or ol["tag"] == "unknown" or o["render"] >= m["render"]:
                continue
            a0, b0, a1, b1 = o["box"]
            if a0 <= x0 and b0 <= y0 and a1 >= x1 and b1 >= y1 and (a1 - a0) * (b1 - b0) > area * 2:
                if best is None or (a1 - a0) * (b1 - b0) < (best["box"][2] - best["box"][0]) * (best["box"][3] - best["box"][1]):
                    best = o
        if best is None:
            continue
        lab.update(tag=labels[best["id"]]["tag"], confidence=0.4, by="inside " + best["id"])
        if lab["role"] == "base":
            d = lum(m["look"]["rgb"]) - lum(best["look"]["rgb"])
            lab["role"] = "highlight" if d > 25 else "shadow" if d < -25 else "base"


def label_map(folder: Path, scene: dict, labels: dict) -> None:
    """The model at rest beside its meshes painted in tag colours, back to front, low-confidence ones hatched."""
    full = Image.open(folder / "full.png").convert("RGBA")
    canvas = np.zeros((full.height, full.width, 3), np.float32) + 40
    for m in sorted(scene["meshes"], key=lambda m: m["render"]):
        path = folder / "meshes" / f"{m['render']}.png"
        if "at" not in m or not path.exists() or m.get("opacity", 1) < 0.05:
            continue
        lab = labels[m["id"]]
        a = np.asarray(Image.open(path).convert("RGBA"), np.float32)[..., 3:] / 255
        x, y = m["at"]
        h, w = a.shape[:2]
        color = np.array(TAGS.get(lab["tag"], TAGS["unknown"]), np.float32)
        if lab["role"] == "shadow":
            color = color * 0.55
        elif lab["role"] == "highlight":
            color = color * 0.5 + 127
        elif lab["role"] == "line":
            color = color * 0.3
        if lab["confidence"] < 0.25:
            yy, xx = np.mgrid[0:h, 0:w]
            color = np.where((((xx + yy + x + y) // 4) % 2 == 0)[..., None], color, np.array([255, 0, 255], np.float32))
        region = canvas[y:y + h, x:x + w]
        region[:] = region * (1 - a[:region.shape[0], :region.shape[1]]) + color * a[:region.shape[0], :region.shape[1]]
    painted = Image.fromarray(canvas.clip(0, 255).astype(np.uint8))
    bg = Image.new("RGBA", full.size, (128, 128, 128, 255))
    bg.alpha_composite(full)
    side = Image.new("RGB", (full.width * 2, full.height), "white")
    side.paste(bg.convert("RGB"), (0, 0))
    side.paste(painted, (full.width, 0))
    scale = 1600 / max(side.size)
    side = side.resize((int(side.width * scale), int(side.height * scale)), Image.LANCZOS)
    # Legend of the tags present.
    present = sorted({labels[m["id"]]["tag"] for m in scene["meshes"]})
    legend = Image.new("RGB", (side.width, 22 * ((len(present) + 5) // 6)), "white")
    draw = ImageDraw.Draw(legend)
    for i, tag in enumerate(present):
        x, y = (i % 6) * (side.width // 6), (i // 6) * 22
        draw.rectangle([x + 4, y + 4, x + 18, y + 18], fill=TAGS.get(tag, TAGS["unknown"]), outline="black")
        draw.text((x + 22, y + 5), tag, fill="black")
    out = Image.new("RGB", (side.width, side.height + legend.height), "white")
    out.paste(side, (0, 0))
    out.paste(legend, (0, side.height))
    out.save(folder / "labelmap.png")


def label_folder(folder: Path) -> str:
    result = label_model(folder)
    (folder / "labels.json").write_text(json.dumps(result, ensure_ascii=False, indent=0), encoding="utf-8")
    scene = json.loads((folder / "scene.json").read_text(encoding="utf-8"))
    label_map(folder, scene, result["labels"])
    counts = defaultdict(int)
    for lab in result["labels"].values():
        counts[lab["tag"]] += 1
    low = sum(1 for lab in result["labels"].values() if lab["confidence"] < 0.25)
    return f"{folder.name} {scene['model']} {len(result['labels'])} meshes, {low} low {dict(sorted(counts.items(), key=lambda kv: -kv[1])[:6])}"


def main(dirs):
    from multiprocessing import Pool
    folders = []
    for d in dirs:
        folders += [Path(d)] if (Path(d) / "scene.json").exists() else [f for f in sorted(Path(d).glob("*/")) if (f / "scene.json").exists()]
    with Pool(min(16, max(1, len(folders)))) as pool:
        for line in pool.imap(label_folder, folders):
            print(line, flush=True)


if __name__ == "__main__":
    main(sys.argv[1:])
