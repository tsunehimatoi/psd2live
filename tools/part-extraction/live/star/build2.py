"""star_lan fine split, batch 2: finer pieces of batch 1's groups (build.py): every strand of hair, every charm and
gem. Each piece is generated from the original close-up like any layer; batch 1's group layer stays under its pieces
where they leave gaps. Writes specs/star_fine2.json and hints/star_fine2/<id>.png, and the sheets star/hints2_*.png.
"""
import json

import numpy as np
from PIL import Image

import build as b
from build import HAIR, HB, EYES, LEFT, RIGHT, P, poly, ell, stroke, draw, L

BATCH2 = []


def piece(id, prompt, tags, hint, group, lora="v7e2", **kw):
    BATCH2.append(dict(id=id, prompt=prompt, tags=list(tags), hint=hint, group=group, lora=lora, minus=[], section=group, **kw))


# Front hair (bangs), one strand per layer.
BANGS = [
    ("a", 8, [(462, 48), (458, 90), (456, 140), (462, 190)]),
    ("b", 7, [(470, 52), (474, 100), (472, 150), (468, 196)]),
    ("c", 7, [(486, 58), (484, 110), (478, 160)]),
    ("d", 8, [(500, 55), (502, 110), (508, 160), (520, 192)]),
    ("e", 6, [(512, 60), (518, 110), (526, 150), (532, 175)]),
    ("f", 6, [(526, 62), (534, 110), (540, 150)]),
    ("g", 8, [(540, 55), (555, 100), (568, 140), (578, 165)]),
    ("h", 7, [(552, 50), (572, 90), (588, 130), (596, 165)]),
]
for k, w, pts in BANGS:
    piece(f"bang_{k}", f"one strand of the front hair (bangs), a single lock, {HAIR}", ["hair", "front"], [stroke(w, *pts)],
          "hair/front/bangs", min_side=128)
# Strands over the crown, sweeping down the sides of the head.
CROWN = [
    ("r1", 8, [(497, 48), (475, 58), (455, 80), (440, 115)]),
    ("r2", 8, [(495, 42), (462, 50), (438, 72), (422, 105)]),
    ("r3", 7, [(492, 38), (455, 42), (425, 62), (408, 95)]),
    ("l1", 8, [(515, 48), (540, 58), (560, 80), (575, 110)]),
    ("l2", 8, [(518, 42), (550, 48), (578, 68), (595, 95)]),
    ("l3", 7, [(520, 38), (558, 40), (590, 58), (612, 85)]),
]
for k, w, pts in CROWN:
    side = "right" if k[0] == "r" else "left"
    piece(f"crown_{k}", f"one strand of hair sweeping from the crown down the side of the head, {HAIR}", ["hair", "front", side],
          [stroke(w, *pts)], "hair/front/crown", min_side=128)
# Side locks beside the face, down over the chest.
SIDE = [
    ("r1", 7, [(455, 150), (450, 210), (446, 270), (440, 340), (434, 400)]),
    ("r2", 7, [(445, 140), (438, 200), (432, 270), (428, 340), (424, 420), (420, 480)]),
    ("r3", 6, [(462, 160), (458, 220), (452, 260)]),
    ("l1", 7, [(578, 150), (584, 210), (590, 270), (596, 340), (604, 400)]),
    ("l2", 7, [(588, 140), (596, 200), (604, 270), (610, 340), (616, 420), (620, 480)]),
    ("l3", 6, [(572, 160), (576, 220), (582, 260)]),
]
for k, w, pts in SIDE:
    side = "right" if k[0] == "r" else "left"
    piece(f"sidelock_{k}", f"one thin strand of the side lock beside the face, falling down over the chest, {HAIR}",
          ["hair", "front", side], [stroke(w, *pts)], "hair/side", min_side=128)
# Ahoge in two pieces.
piece("ahoge_loop", f"the big loop of the ahoge arching from the crown over to the side of the head, {HAIR}", ["hair", "front"],
      [P(HB, "42")], "hair/front/ahoge")
piece("ahoge_tuft", f"the small tuft of hair sticking up on the crown, {HAIR}", ["hair", "front"],
      [stroke(5, (500, 32), (505, 18), (515, 10))], "hair/front/ahoge", min_side=96)
# Braids: three plaits each.
for side, s, pts in (("r", "right", [(425, 110), (410, 150), (408, 190), (420, 240)]),
                     ("l", "left", [(606, 120), (622, 160), (628, 200), (622, 240)])):
    for i, dx in enumerate((-6, 0, 6)):
        piece(f"braid_{side}{i + 1}", f"one plait of the braid on the side of the head, {HAIR}", ["hair", "back", s],
              [stroke(6, *[(x + dx, y + 8 * i) for x, y in pts])], f"hair/front/braid_{side}", min_side=128)
# Long back hair: each strand of batch 1 split into two thin locks.
for prefix, group, strands in (("r", "hair/back/right", LEFT), ("l", "hair/back/left", RIGHT)):
    side = "right" if prefix == "r" else "left"
    for i, (w, pts) in enumerate(strands):
        for j, off in enumerate((-w // 3, w // 3)):
            piece(f"hair_long_{prefix}{i + 1}{'ab'[j]}", f"one thin lock of a long strand of the back hair, {HAIR}",
                  ["hair", "back", side], [stroke(max(6, w // 2), *[(x + off, y) for x, y in pts])], f"{group}/strand{i + 1}")

# Hair ornament.
piece("crescent_chain", "the short gold chain with small charms hanging under the crescent hair ornament", ["headwear"],
      [poly((604, 125), (630, 125), (634, 170), (606, 170))], "headwear", min_side=96)
piece("hair_ribbon_chain", "the thin gold chain running down the navy hair ribbon", ["headwear"],
      [stroke(4, (618, 140), (630, 175), (640, 210))], "headwear", min_side=96)
# Earrings: hook and star charm.
for side, s, (x, y) in (("r", "right", (466, 230)), ("l", "left", (582, 228))):
    piece(f"earring_hook_{side}", "the small gold hook of the earring", ["ear", "accessory", s], [ell(x, y - 18, 3, 5)], "head/earrings", min_side=64)
    piece(f"earring_star_{side}", "the gold star charm of the earring", ["ear", "accessory", s], [ell(x, y, 8, 12)], "head/earrings", min_side=64)
# Eyes: crease line; cheeks: blush.
for side, s in (("r", "right"), ("l", "left")):
    cx, cy = EYES[side]
    piece(f"lid_line_{side}", "the thin double eyelid crease line above the eye", ["eye", "lash", "crease", s],
          [stroke(3, (cx - 16, cy - 17), (cx, cy - 20), (cx + 16, cy - 16))], "head/eyes", box=[cx - 40, cy - 40, 80])
    piece(f"blush_{side}", "the soft pink blush on the cheek", ["face", "detail", s], [ell(cx + (-6 if side == "r" else 6), cy + 26, 16, 7)],
          "head", lora="v7e3", min_side=96)
piece("nose_highlight", "the small white highlight on the tip of the nose", ["nose", "highlight"], [ell(516, 196, 3, 3)], "head", min_side=64)

# Collar and bow in pieces.
piece("collar_band", "the dark navy band of the high collar around the neck", ["neck", "accessory"],
      [poly((490, 246), (546, 246), (548, 268), (488, 268))], "neck", min_side=96)
piece("collar_wing_r", "the white folded collar wing with navy edge", ["neck", "accessory", "right"],
      [poly((474, 262), (505, 270), (500, 300), (468, 298))], "neck", min_side=96)
piece("collar_wing_l", "the white folded collar wing with navy edge", ["neck", "accessory", "left"],
      [poly((532, 270), (562, 262), (568, 298), (536, 300))], "neck", min_side=96)
piece("bow_loop_r", "the navy loop of the collar bow", ["neck", "accessory", "right"], [poly((464, 290), (510, 288), (510, 325), (470, 328))], "neck/bow")
piece("bow_loop_l", "the navy loop of the collar bow", ["neck", "accessory", "left"], [poly((526, 288), (572, 290), (566, 328), (526, 325))], "neck/bow")
piece("bow_tail_r", "the navy tail of the collar bow with gold edge", ["neck", "accessory", "right"], [poly((462, 318), (505, 318), (500, 362), (470, 362))], "neck/bow")
piece("bow_tail_l", "the navy tail of the collar bow with gold edge", ["neck", "accessory", "left"], [poly((530, 318), (572, 318), (566, 362), (536, 362))], "neck/bow")
piece("brooch_neck_gem", "the blue gem in the middle of the collar brooch", ["neck", "accessory"], [ell(517, 295, 5, 6)], "neck/brooch", min_side=64)
piece("pendant_neck_charm", "the small gold charm at the end of the collar pendant chain", ["neck", "accessory"], [ell(517, 340, 5, 8)], "neck/brooch", min_side=64)
for i, y in enumerate((425, 467, 513)):
    piece(f"button_{i + 1}_gem", "the blue gem in the middle of the star button", ["top"], [ell(517, y, 4, 5)], "top/buttons", min_side=64)
piece("trim_halter_r", "the gold piping under the bust", ["top", "right"], [stroke(5, (473, 345), (448, 352), (425, 372))], "top/trims")
piece("trim_halter_l", "the gold piping under the bust", ["top", "left"], [stroke(5, (565, 345), (592, 352), (618, 372))], "top/trims")
piece("trim_waist_r", "the vertical gold band on the waist", ["top", "right"], [stroke(7, (463, 462), (465, 525))], "top/trims")
piece("trim_waist_l", "the vertical gold band on the waist", ["top", "left"], [stroke(7, (571, 462), (570, 525))], "top/trims")
piece("blouse_v_line", "the thin V-shaped neckline edge of the blouse over the chest", ["top"],
      [stroke(3, (490, 362), (517, 410), (544, 362))], "top", min_side=96)

# Skirt ornaments piece by piece.
piece("hem_stripes", "the two gold stripes along the hem of the skirt", ["bottom"],
      [stroke(6, (335, 692), (420, 712), (500, 728), (580, 724), (650, 712), (700, 692))], "skirt")
piece("chain_belt_clasp", "the small gold clasp on the chain belt", ["bottom"], [ell(452, 579, 6, 6)], "skirt/chain", min_side=64)
for i, (x, y, rx, ry) in enumerate(((431, 618, 9, 10), (425, 644, 8, 9), (420, 672, 11, 14))):
    piece(f"pendant_belt_{i + 1}", "one gold diamond-shaped star charm of the pendant", ["bottom"], [ell(x, y, rx, ry)], "skirt/chain", min_side=64)
for i, (x, y, rx, ry) in enumerate(((597, 655, 10, 12), (598, 721, 18, 20))):
    piece(f"pendant_bow_{i + 1}", "one gold star charm of the pendant under the hip bow", ["bottom"], [ell(x, y, rx, ry)], "skirt/bow", min_side=64)
piece("pendant_bow_chain", "the short gold chain between the two charms under the hip bow", ["bottom"],
      [stroke(4, (598, 665), (600, 700))], "skirt/bow", min_side=64)
piece("bow_hip_knot", "the knot in the middle of the navy hip bow", ["bottom"], [ell(560, 552, 12, 12)], "skirt/bow", min_side=96)
piece("bow_hip_loop_r", "the left loop of the navy hip bow", ["bottom"], [poly((520, 536), (556, 540), (548, 575), (522, 570))], "skirt/bow")
piece("bow_hip_loop_l", "the right loop of the navy hip bow", ["bottom"], [poly((566, 536), (620, 545), (630, 590), (580, 588))], "skirt/bow")
piece("brooch_hip_gem", "the blue gem in the middle of the hip brooch", ["bottom"], [ell(566, 547, 6, 6)], "skirt/bow", min_side=64)
# Boots.
for side, s, (x, y), cuff in (("r", "right", (388, 1310), ((390, 1280), (470, 1280), (470, 1300), (390, 1300))),
                              ("l", "left", (708, 1310), ((598, 1278), (682, 1278), (682, 1300), (598, 1300)))):
    piece(f"boot_charm_star1_{side}", "the upper silver diamond star of the boot charm", ["foot", s], [ell(x, y, 6, 14)], "legs/charms", min_side=64)
    piece(f"boot_charm_star2_{side}", "the lower silver diamond star of the boot charm", ["foot", s], [ell(x, y + 50, 6, 12)], "legs/charms", min_side=64)
    piece(f"boot_cuff_{side}", "the folded top of the boot shaft", ["foot", s], [poly(*cuff)], "legs", min_side=96)
# Arms: ribbon tips, sleeve bands.
for side, s, (x, y) in (("r", "right", (285, 770)), ("l", "left", (752, 770))):
    piece(f"cuff_ribbon_tip_{side}", "the small gold tip at the end of the ribbon tail", ["arm", s], [ell(x, y, 5, 6)], f"arm/{s}", min_side=64)
for side, s, pts in (("r", "right", ((318, 492), (420, 492), (420, 502), (318, 502))),
                     ("l", "left", ((616, 492), (720, 492), (720, 502), (616, 502)))):
    piece(f"sleeve_band_{side}", "the thin navy band with a small gold charm at the bottom of the puffed sleeve", ["arm", s], [poly(*pts)],
          f"arm/{s}", min_side=96)


def main():
    out = L / "hints/star_fine2"
    out.mkdir(parents=True, exist_ok=True)
    spec = {"image": "art/star_lan.png", "plan": "plan/star_lan", "loras": ["aitk/split_v7e2.safetensors"], "seeds": [1, 2, 3, 4], "layers": []}
    masks = {}
    for l in BATCH2:
        m = masks[l["id"]] = draw(l["hint"])
        if not m.any():
            print("EMPTY", l["id"])
        Image.fromarray((m * 255).astype(np.uint8)).save(out / f"{l['id']}.png")
        e = {k: v for k, v in l.items() if k not in ("hint", "minus", "lora", "section")}
        e.update(regions=[], mask=f"hints/star_fine2/{l['id']}.png", name=l["id"].replace("_", " "),
                 loras=[f"aitk/split_{l['lora']}.safetensors"])
        spec["layers"].append(e)
    (L / "specs/star_fine2.json").write_text(json.dumps(spec, indent=1), encoding="utf-8")
    print(len(BATCH2), "batch 2 layers")
    b.LAYERS[:] = BATCH2
    b.sheets(masks)


if __name__ == "__main__":
    main()
