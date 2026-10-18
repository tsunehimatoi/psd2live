"""star_lan fine split: the layer list (back to front) with each layer's hint, prompt and LoRA, written to
specs/star_fine.json and hints/star_fine/<id>.png (canvas size). Hints come from the planner's plans of upscaled crops
(P), hand-drawn polygons, ellipses and strokes (a polyline with a width, for strands); a layer may subtract others'.
Hints are only pointers for the LoRA; nothing here touches the layers' pixels.

python build.py [--sheet]   (sheets: star/hints_<section>.png, every hint of a section numbered over the picture)
"""
import json
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

L = Path(os.environ.get("L2D_LIVE", "."))
W, H = 1024, 1536
CROPS = json.loads((L / "art/star_crops.json").read_text())
CROPS.update({k + "_fine": v for k, v in list(CROPS.items())})
_plans = {}


def _region(plan: str, num: str) -> np.ndarray:
    rows = _plans.setdefault(plan, {r["name"].split("_")[0]: r for r in json.loads((L / f"plan/{plan}/plan.json").read_text())})
    r = rows[num]
    m = Image.open(L / f"plan/{plan}/{r['region']}")
    (x0, y0, x1, y1), s = CROPS[plan]["box"], CROPS[plan]["scale"]
    small = np.asarray(m.resize((x1 - x0, y1 - y0), Image.BOX)) > 100
    out = np.zeros((H, W), bool)
    out[y0:y1, x0:x1] = small
    return out


def P(plan, *nums):
    return ("P", plan, nums)


def poly(*pts):
    return ("poly", pts)


def ell(cx, cy, rx, ry):
    return ("ell", (cx, cy, rx, ry))


def stroke(w, *pts):
    return ("stroke", (w, pts))


def draw(items) -> np.ndarray:
    im = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(im)
    out = np.zeros((H, W), bool)
    for kind, *a in items:
        if kind == "P":
            for n in a[1]:
                out |= _region(a[0], n)
        elif kind == "poly":
            d.polygon([tuple(p) for p in a[0]], fill=255)
        elif kind == "ell":
            cx, cy, rx, ry = a[0]
            d.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=255)
        elif kind == "stroke":
            w, pts = a[0]
            d.line([tuple(p) for p in pts], fill=255, width=w, joint="curve")
            for x, y in (pts[0], pts[-1]):
                d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=255)
    return out | (np.asarray(im) > 127)


LAYERS = []
SECTION = [""]


def section(name):
    SECTION[0] = name


def layer(id, prompt, tags, hint, group=None, lora="v7e2", minus=(), **kw):
    LAYERS.append(dict(id=id, prompt=prompt, tags=list(tags), hint=hint, group=group or SECTION[0], lora=lora,
                       minus=list(minus), section=SECTION[0], **kw))


HAIR = "light blue hair with lavender shading"
HB = "s_head_fine"

# ---------------------------------------------------------------- 1 back hair
section("hair/back")
layer("hair_back_inner", f"the back hair behind the head and neck, the inner side of the hair curtain, {HAIR}, darker",
      ["hair", "back"], [P(HB, "00"), poly((438, 195), (470, 195), (472, 300), (436, 300)), poly((566, 195), (600, 195), (604, 300), (568, 300))])
# Long strands hanging behind the arms: on the picture's left (the character's right) and right, inner to outer.
LEFT = [  # (width, points) inner to outer
    (26, [(425, 470), (405, 560), (380, 640), (365, 730), (385, 828)]),
    (26, [(375, 465), (335, 555), (305, 650), (302, 740), (330, 825)]),
    (28, [(395, 435), (325, 525), (275, 620), (252, 710), (272, 805)]),
    (30, [(410, 390), (305, 500), (235, 590), (195, 680), (195, 760), (222, 810)]),
    (24, [(420, 352), (322, 458), (222, 558), (150, 650), (130, 740), (172, 792)]),
    (20, [(425, 330), (340, 420), (250, 510), (160, 590), (100, 662), (90, 742)]),
]
RIGHT = [
    (24, [(615, 480), (650, 560), (680, 650), (690, 740), (700, 828)]),
    (24, [(640, 470), (680, 560), (715, 650), (730, 740), (745, 828)]),
    (28, [(660, 420), (720, 500), (770, 580), (800, 660), (820, 740), (840, 822)]),
    (30, [(650, 380), (730, 470), (800, 550), (850, 630), (870, 720), (870, 800)]),
    (22, [(655, 330), (740, 420), (830, 500), (900, 600), (930, 700), (930, 780)]),
]
for i, (w, pts) in enumerate(LEFT):
    layer(f"hair_long_r{i + 1}", f"one long strand of the back hair, {HAIR}, hanging from behind the shoulder down past the hip, "
          "without the arm and the other strands", ["hair", "back", "right"], [stroke(w, *pts)], group="hair/back/right")
for i, (w, pts) in enumerate(RIGHT):
    layer(f"hair_long_l{i + 1}", f"one long strand of the back hair, {HAIR}, hanging from behind the shoulder down past the hip, "
          "without the arm and the other strands", ["hair", "back", "left"], [stroke(w, *pts)], group="hair/back/left")

# ---------------------------------------------------------------- 2 legs
section("legs")
SKIN = "bare skin"
layer("thigh_r", f"the base leg layer, the thigh, {SKIN}, drawn complete up under the skirt", ["leg", "right"],
      [P("s_legs", "12"), poly((425, 690), (515, 690), (510, 830), (432, 830))], lora="v7e3")
layer("thigh_l", f"the base leg layer, the thigh, {SKIN}, drawn complete up under the skirt", ["leg", "left"],
      [P("s_legs", "16"), poly((540, 690), (625, 690), (620, 830), (545, 830))], lora="v7e3")
layer("calf_r", f"the base leg layer, the lower leg from the knee to the ankle, {SKIN}, drawn complete inside the boot",
      ["leg", "right"], [poly((402, 985), (482, 985), (472, 1100), (462, 1330), (418, 1330), (404, 1100))], lora="v7e3")
layer("calf_l", f"the base leg layer, the lower leg from the knee to the ankle, {SKIN}, drawn complete inside the boot",
      ["leg", "left"], [poly((572, 985), (650, 985), (690, 1100), (690, 1330), (632, 1330), (592, 1100))], lora="v7e3")
BOOT = "the navy boot, the shaft and foot only, without the silver straps, the silver star, the silver toe cap and the dangling charm"
layer("boot_r", BOOT, ["foot", "right"], [poly((390, 1282), (466, 1282), (470, 1400), (482, 1478), (470, 1518), (410, 1520), (400, 1460))])
layer("boot_l", BOOT, ["foot", "left"], [poly((598, 1278), (682, 1278), (686, 1400), (690, 1490), (672, 1526), (620, 1526), (614, 1450))])
layer("boot_toe_r", "the silver toe cap and heel trim of the boot", ["foot", "right"],
      [poly((400, 1468), (472, 1468), (484, 1500), (462, 1527), (410, 1527), (398, 1500))])
layer("boot_toe_l", "the silver toe cap and heel trim of the boot", ["foot", "left"],
      [poly((614, 1468), (690, 1468), (696, 1500), (672, 1530), (624, 1530), (612, 1500))])
layer("boot_straps_r", "the silver X-shaped straps crossing over the boot", ["foot", "right"], [P("s_legs", "17")], minus=["boot_star_r"])
layer("boot_straps_l", "the silver X-shaped straps crossing over the boot", ["foot", "left"], [P("s_legs", "15", "11")], minus=["boot_star_l"])
layer("boot_star_r", "the silver four-pointed star on the front of the boot", ["foot", "right"], [ell(440, 1386, 9, 20)], min_side=96)
layer("boot_star_l", "the silver four-pointed star on the front of the boot", ["foot", "left"], [ell(683, 1386, 9, 20)], min_side=96)
layer("boot_charm_r", "the dangling charm on the side of the boot, a short silver chain with two silver diamond stars",
      ["foot", "right"], [P("s_legs", "13", "06", "07")], min_side=96)
layer("boot_charm_l", "the dangling charm on the side of the boot, a short silver chain with two silver diamond stars",
      ["foot", "left"], [P("s_legs", "09", "14", "10")], min_side=96)

# ---------------------------------------------------------------- 3 body
section("body")
layer("torso", "the base body layer (torso) from the collarbones to the hips, drawn complete under the clothes, with the white "
      "underwear painted on it", ["body"],
      [poly((432, 268), (612, 268), (630, 360), (618, 450), (612, 560), (628, 640), (420, 640), (432, 560), (420, 450), (412, 360))],
      lora="v7e3")
layer("neck", "the base neck layer, drawn complete under the collar and reaching down to the collarbones", ["neck"],
      [P(HB, "12"), poly((492, 222), (548, 222), (552, 300), (488, 300))], lora="v7e3")

# ---------------------------------------------------------------- 4 skirt
section("skirt")
layer("skirt_under", "the lower layer of the navy skirt showing under the hem, a second pleated layer", ["bottom"],
      [P("s_skirt", "23", "15", "13", "47", "36", "50", "08")])
layer("skirt", "the navy pleated skirt with its two gold hem stripes, from the waistband down to the hem, without the waistband, "
      "the chain belt, the bow, the ribbons and the pendants", ["bottom"],
      [poly((402, 560), (640, 560), (702, 688), (706, 720), (622, 760), (500, 768), (330, 728), (330, 690))])
layer("skirt_emblem", "the small gold emblem on the skirt", ["bottom"], [ell(638, 687, 8, 10)], min_side=96)
layer("waistband", "the dark navy waistband of the skirt, a wide band around the waist", ["bottom"],
      [poly((440, 524), (602, 524), (604, 562), (438, 562))])
layer("chain_belt", "the gold chain belt slung diagonally across the skirt", ["bottom"], [P("s_skirt", "59")])
layer("pendant_belt", "the gold pendant hanging from the chain belt, three gold diamond stars on a short chain", ["bottom"],
      [poly((411, 594), (441, 594), (441, 686), (411, 686))], min_side=128)
layer("ribbon_navy", "the long navy ribbon tail with gold stripes hanging from the hip bow down past the hem", ["bottom"],
      [poly((585, 600), (615, 600), (640, 700), (632, 802), (598, 802), (588, 700))])
layer("ribbon_white", "the white ribbon tail hanging from the hip bow", ["bottom"], [P("s_skirt", "39")])
layer("bow_hip", "the navy bow at the hip with its two loops and knot", ["bottom"], [P("s_skirt", "66", "65", "64", "06", "61")])
layer("pendant_bow", "the gold pendant hanging below the hip bow, a chain with two gold diamond stars", ["bottom"],
      [poly((583, 643), (617, 643), (619, 737), (583, 737))], min_side=128)
layer("brooch_hip", "the gold four-pointed star brooch with a blue gem on the hip bow", ["bottom"], [ell(566, 547, 16, 16)], min_side=128)

# ---------------------------------------------------------------- 5 top
section("top")
layer("blouse", "the white sleeveless blouse from the halter neck to the waist, without the collar, the bow, the gold trims, the "
      "star buttons, the hair, the arms and the waistband", ["top"],
      [poly((448, 300), (505, 335), (535, 335), (595, 300), (625, 370), (615, 450), (604, 528), (438, 528), (425, 450), (418, 370))])
layer("trim_halter", "the gold piping along the halter straps and under the bust", ["top"],
      [stroke(5, (473, 345), (448, 352), (425, 372)), stroke(5, (565, 345), (592, 352), (618, 372))])
layer("trim_waist", "the two vertical gold bands on the waist of the blouse", ["top"],
      [stroke(7, (463, 462), (465, 525)), stroke(7, (571, 462), (570, 525))])
for i, y in enumerate((425, 467, 513)):
    layer(f"button_{i + 1}", "the gold four-pointed star button with a blue gem on the front of the blouse", ["top"],
          [ell(517, y, 14, 15)], min_side=96)
layer("collar", "the high navy collar around the neck with its white folded collar wings", ["neck", "accessory"],
      [poly((490, 246), (546, 246), (548, 268), (560, 300), (520, 302), (474, 300), (488, 268))])
layer("bow_tails", "the two navy tails of the collar bow hanging down to the chest", ["neck", "accessory"],
      [poly((462, 318), (505, 318), (500, 362), (470, 362)), poly((530, 318), (572, 318), (566, 362), (536, 362))])
layer("bow_loops", "the two navy loops of the collar bow", ["neck", "accessory"],
      [poly((464, 290), (510, 288), (510, 325), (470, 328)), poly((526, 288), (572, 290), (566, 328), (526, 325))])
layer("brooch_neck", "the gold four-pointed star brooch with a blue gem at the collar", ["neck", "accessory"], [ell(517, 295, 17, 15)], min_side=128)
layer("pendant_neck", "the small gold pendant chain hanging below the collar brooch", ["neck", "accessory"],
      [poly((511, 305), (523, 305), (522, 348), (512, 348))], min_side=128)

# ---------------------------------------------------------------- 6 side locks (over the chest, under the arms)
section("hair/side")
layer("sidelock_r", f"the long side lock of hair falling from beside the face down over the chest, {HAIR}", ["hair", "front", "right"],
      [P(HB, "97", "02"), P("s_torso", "62", "42")])
layer("sidelock_l", f"the long side lock of hair falling from beside the face down over the chest, {HAIR}", ["hair", "front", "left"],
      [P(HB, "87", "91"), P("s_torso", "71")])

# ---------------------------------------------------------------- 7 arms
for side, s, pic in (("r", "right", "L"), ("l", "left", "R")):
    section(f"arm/{s}")
    A = side == "r"
    layer(f"upperarm_{side}", f"the base arm layer, the upper arm from the shoulder to the elbow, {SKIN}, drawn complete under the "
          "puffed sleeve", ["arm", s], [stroke(42, *((400, 318), (385, 420), (362, 505)) if A else ((642, 318), (660, 420), (688, 505)))],
          lora="v7e3")
    layer(f"forearm_{side}", f"the base arm layer, the forearm from the elbow to the wrist, {SKIN}, drawn complete under the wrist cuff",
          ["arm", s], [stroke(36, *((365, 505), (320, 585), (284, 668)) if A else ((688, 505), (724, 585), (760, 668)))], lora="v7e3")
    layer(f"hand_{side}", f"the base hand layer, the open relaxed hand with its fingers, {SKIN}", ["arm", s],
          [poly(*((225, 668), (292, 664), (302, 700), (272, 762), (240, 802), (198, 802), (186, 772), (214, 720)) if A else
                 ((722, 668), (792, 668), (832, 730), (858, 792), (812, 802), (760, 772), (730, 730)))], lora="v7e3")
    layer(f"sleeve_{side}", "the white puffed detached sleeve around the upper arm, without the navy arm band and its star",
          ["arm", s], [poly(*((318, 380), (420, 380), (424, 470), (412, 500), (318, 500), (312, 450)) if A else
                              ((612, 380), (720, 380), (726, 450), (720, 500), (618, 500), (606, 470)))])
    layer(f"sleeve_frill_{side}", "the white frill with a thin navy band at the bottom of the puffed sleeve", ["arm", s],
          [poly(*((314, 470), (422, 470), (422, 506), (314, 506)) if A else ((614, 470), (722, 470), (722, 506), (614, 506)))])
    layer(f"armband_{side}", "the dark navy arm band around the top of the puffed sleeve", ["arm", s],
          [poly(*((338, 373), (412, 373), (412, 400), (338, 402)) if A else ((618, 373), (706, 373), (706, 402), (618, 400)))])
    layer(f"armband_ribbon_{side}", "the navy ribbon tail hanging from the arm band", ["arm", s],
          [poly(*((322, 395), (348, 395), (346, 455), (324, 455)) if A else ((688, 380), (712, 380), (712, 460), (690, 460)))],
          min_side=128)
    layer(f"armband_star_{side}", "the gold four-pointed star ornament with a blue gem on the arm band", ["arm", s],
          [ell(*((362, 392) if A else (667, 392)), 12, 17)], min_side=96)
    layer(f"cuff_ribbon_{side}", "the navy ribbon tail hanging from the wrist cuff", ["arm", s],
          [poly(*((268, 690), (296, 690), (294, 772), (272, 772)) if A else ((735, 690), (765, 690), (765, 772), (738, 772)))],
          min_side=128)
    layer(f"cuff_frill_{side}", "the white frill of the wrist cuff", ["arm", s],
          [poly(*((228, 670), (296, 670), (296, 702), (228, 702)) if A else ((733, 666), (806, 666), (806, 702), (733, 702)))],
          min_side=128)
    layer(f"cuff_{side}", "the dark navy wrist cuff around the wrist", ["arm", s],
          [poly(*((238, 644), (302, 644), (302, 682), (238, 682)) if A else ((728, 640), (802, 640), (802, 682), (728, 682)))],
          min_side=128)
    layer(f"cuff_star_{side}", "the gold four-pointed star ornament with a blue gem on the wrist cuff", ["arm", s],
          [ell(*((270, 656) if A else (765, 657)), 10, 15)], min_side=96)

# ---------------------------------------------------------------- 8 head
section("head")
layer("ear_r", "the ear", ["ear", "right"], [P(HB, "28")], min_side=96)
layer("ear_l", "the ear", ["ear", "left"], [P(HB, "27")], min_side=96)
layer("face", "the base face layer, bare skin with the blush only, no eyes, eyebrows, nose or mouth drawn on it", ["face"],
      [poly((455, 120), (580, 120), (585, 190), (560, 225), (518, 248), (476, 225), (452, 190))], lora="v7e3")
layer("nose", "the nose, a tiny soft shading line", ["nose"], [ell(515, 203, 3, 5)], min_side=96)
# One eye per close-up; both eyes framed alike so they come out the same size.
EYES = {"r": (479, 175), "l": (552, 173)}
IRIS = {"r": (481, 176), "l": (550, 175)}
for side, s in (("r", "right"), ("l", "left")):
    cx, cy = EYES[side]
    box = [cx - 40, cy - 40, 80]
    d = -1 if side == "r" else 1
    layer(f"eyewhite_{side}", "the eye white, plain white almond shape", ["eye", "white", s], [ell(cx, cy + 1, 19, 10)], box=box)
    layer(f"iris_{side}", "the iris with its pupil, blue", ["eye", "iris", s], [ell(*IRIS[side], 10, 9)], box=box)
    layer(f"highlight_{side}", "the white highlights of the iris", ["eye", "highlight", s], [ell(IRIS[side][0], IRIS[side][1] - 3, 6, 5)], box=box)
    layer(f"lash_{side}", "the upper eyelash and eyelid line, dark brown", ["eye", "lash", s],
          [stroke(6, (cx - 22, cy - 2), (cx - 12, cy - 11), (cx + 2, cy - 14), (cx + 14, cy - 11), (cx + 23, cy - 3))], box=box)
    layer(f"lash_lower_{side}", "the thin lower eyelash line", ["eye", "lash", "lower", s],
          [stroke(3, (cx - 12, cy + 11), (cx + 2, cy + 12), (cx + 14, cy + 9))], box=box)
layer("brow_r", "the eyebrow, a thin light blue arc", ["brow", "right"], [P(HB, "85")], min_side=96)
layer("brow_l", "the eyebrow, a thin light blue arc", ["brow", "left"], [P(HB, "79")], min_side=96)
layer("mouth", "the open mouth with its lips, teeth and tongue", ["mouth"], [ell(517, 219, 14, 7)], min_side=128, grow_hint=0.04,
      source="art/star_lan_open_s4.png")
layer("earring_r", "the dangling gold earring with a small star", ["ear", "accessory", "right"], [P(HB, "30")], min_side=96)
layer("earring_l", "the dangling gold earring with a small star", ["ear", "accessory", "left"], [P(HB, "35", "58")], min_side=96)

# ---------------------------------------------------------------- 9 front hair
section("hair/front")
layer("braid_r", f"the braid on the side of the head, {HAIR}", ["hair", "back", "right"], [P(HB, "06", "31", "13", "76", "88")])
layer("braid_l", f"the braid on the side of the head behind the hair ornament, {HAIR}", ["hair", "back", "left"],
      [P(HB, "90", "84", "45", "37", "25")])
layer("crown_top", f"the hair on the crown of the head, {HAIR}", ["hair", "front"], [P(HB, "86")])
layer("crown_r", f"the strand sweeping from the crown down the side of the head, {HAIR}", ["hair", "front", "right"], [P(HB, "15", "89")])
layer("crown_l", f"the strand sweeping from the crown down the side of the head, {HAIR}", ["hair", "front", "left"], [P(HB, "43")])
layer("bang_r", f"the front hair strand over the eye, {HAIR}", ["hair", "front", "right"], [P(HB, "83")])
layer("bang_l", f"the front hair strand over the eye, {HAIR}", ["hair", "front", "left"], [P(HB, "82")])
layer("bang_mid_thin", f"the thin front hair strand beside the middle strand, {HAIR}", ["hair", "front"], [P(HB, "94")])
layer("bang_mid", f"the middle front hair strand falling between the eyes, {HAIR}", ["hair", "front"], [P(HB, "95")])
layer("ahoge", f"the long thin ahoge, a loop of hair rising from the crown and arching over to the side, {HAIR}", ["hair", "front"],
      [P(HB, "42")])

# ---------------------------------------------------------------- 10 headwear
section("headwear")
layer("hair_ribbon", "the long dark navy ribbon with a gold chain hanging from the hair ornament", ["headwear"], [P(HB, "14")])
layer("crescent", "the gold crescent moon hair ornament", ["headwear"], [P(HB, "98")], min_side=128)
layer("crescent_star", "the small gold star on the crescent hair ornament", ["headwear"], [P(HB, "99")], min_side=96)
layer("hair_pins", "the small gold star hair pins beside the crescent ornament", ["headwear"], [P(HB, "54", "44", "04", "80", "96")],
      min_side=96)


def build():
    out = L / "hints/star_fine"
    out.mkdir(parents=True, exist_ok=True)
    masks = {}
    for l in LAYERS:
        masks[l["id"]] = draw(l["hint"])
    spec = {"image": "art/star_lan.png", "plan": "plan/star_lan", "loras": ["aitk/split_v7e2.safetensors"], "seeds": [1, 2, 3, 4],
            "layers": []}
    for l in LAYERS:
        m = masks[l["id"]].copy()
        for o in l["minus"]:
            m &= ~masks[o]
        Image.fromarray((m * 255).astype(np.uint8)).save(out / f"{l['id']}.png")
        e = {k: v for k, v in l.items() if k not in ("hint", "minus", "lora", "section")}
        e.update(regions=[], mask=f"hints/star_fine/{l['id']}.png", name=l["id"].replace("_", " "),
                 loras=[f"aitk/split_{l['lora']}.safetensors"])
        spec["layers"].append(e)
    (L / "specs/star_fine.json").write_text(json.dumps(spec, indent=1), encoding="utf-8")
    print(len(LAYERS), "layers")
    return masks


def sheets(masks):
    art = Image.open(L / "art/star_lan.png").convert("RGBA")
    bg = Image.new("RGBA", art.size, (255, 255, 255, 255)); bg.alpha_composite(art)
    base = np.asarray(bg.convert("RGB")).astype(np.float32)
    font = ImageFont.truetype("arial.ttf", 11)
    for sec in dict.fromkeys(l["section"] for l in LAYERS):
        rows = [l for l in LAYERS if l["section"] == sec]
        over = base * 0.55 + 110
        rng = np.random.RandomState(7)
        allm = np.zeros((H, W), bool)
        labels = []
        for l in rows:
            m = masks[l["id"]]
            if not m.any():
                print("EMPTY", l["id"]); continue
            c = rng.randint(20, 235, 3)
            over[m] = over[m] * 0.35 + c * 0.65
            allm |= m
            ys, xs = np.nonzero(m)
            k = np.argmin((ys - ys.mean()) ** 2 + (xs - xs.mean()) ** 2)
            labels.append((xs[k], ys[k], l["id"], tuple(int(v) // 3 for v in c)))
        ys, xs = np.nonzero(allm)
        x0, y0, x1, y1 = max(0, xs.min() - 20), max(0, ys.min() - 20), min(W, xs.max() + 20), min(H, ys.max() + 20)
        im = Image.fromarray(over.clip(0, 255).astype(np.uint8))
        s = max(1, int(1100 / max(x1 - x0, y1 - y0)))
        im = im.crop((x0, y0, x1, y1)).resize(((x1 - x0) * s, (y1 - y0) * s), Image.NEAREST)
        d = ImageDraw.Draw(im)
        for x, y, t, c in labels:
            X, Y = (x - x0) * s, (y - y0) * s
            d.rectangle((X - 1, Y - 7, X + 6 * len(t), Y + 7), fill=(255, 255, 255)); d.text((X, Y - 7), t, fill=c, font=font)
        im.save(L / f"star/hints_{sec.replace('/', '_')}.png")


if __name__ == "__main__":
    m = build()
    if "--sheet" in sys.argv:
        sheets(m)
