You are labelling layers of anime character Live2D models, to teach a model that splits an illustration into layers which ones exist and how artists name them. Work only by looking at images and writing JSON files; do not modify anything else.

Your sheets are listed in the batch file named in your task, one per line as FOLDER|N, meaning FOLDER\sheet_N.png with its notes in FOLDER\sheet_N.txt. Read the batch file first, and work on exactly the sheets it lists: other sheets in the same folders belong to other reviewers, so do not open them.

Each sheet has numbered tiles, each tile two panes:
- left: a close-up of the character; ONE layer is lit and outlined in red. Layers moving with it (same group) are half lit, the rest is dark.
- right: that layer drawn alone and enlarged over a checker, so its shape and edges show.
The .txt gives per tile the layer's names from the model file (often meaningful: Japanese, Chinese or English abbreviations such as "HAIR_FRONT", "前髪", "スカート", "影") and a guess by simple rules (often wrong).

Read each sheet and its .txt with the Read tool. For every tile answer:
- tag: the part it belongs to, exactly one of:
  back_hair, front_hair, side_hair, headwear (hats, hair ornaments, ribbons and clips on the head), face (face skin), facedetail (blush, marks), irides (iris, pupil, eye highlight), eyewhite, eyelash (eyelids, lashes, eye lines), eyebrow, ears, earwear, nose, mouth, mouth_inside (teeth, tongue), eyewear, neck, neckwear (separate collars, chokers, ties, scarves), body (torso skin, incl. underwear painted on it), arm (arm skin), hand, leg (leg skin, incl. tights painted on it), topwear (shirts, jackets, dresses, sleeves, armour on the torso or arms), bottomwear (skirts, pants, belts), handwear (gloves), legwear (socks, stockings, leg armour), footwear, tail, wings, objects (held items, weapons, bags, floating props), effect (glows, particles, auras), background, unknown.
- role: base (the part's own colour fill), shadow (a shading layer over it), highlight (a light or shine layer), line (lineart drawn as its own layer), detail (a pattern, trim, decoration or small piece on the part).
- part: the piece in a few English words as an artist would name the layer, most specific first: "skirt lace trim", "skirt frill", "skirt back layer", "front hair strand", "ahoge", "sleeve cuff", "chest ribbon", "eye highlight", "upper eyelash", "knee armour plate", "jacket shadow".
- detail: what makes it look the way it does, if anything: "scalloped white lace edge", "fluffy fur rim", "gold piping", "pleated", "semi-transparent", "glowing". Empty string when plain.
- sure: false when you are guessing.

Write one file per sheet: FOLDER\answers_N.json with the Write tool, keyed by the tile number printed on the tile as a string (numbers continue across sheets: sheet_2 holds tiles 11-20, sheet_3 tiles 21-30), every tile on the sheet:
{"1": {"tag": "bottomwear", "role": "detail", "part": "skirt lace trim", "detail": "scalloped white lace edge", "sure": true}, "2": {...}}

Keep each answer short. Reply with one line: the number of tiles you answered.
