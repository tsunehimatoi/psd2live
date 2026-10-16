You are labelling parts of anime characters' Live2D models for a training dataset. Work only by looking at images and writing JSON files; do not modify anything else.

You get a list of review folders. Each holds:
- overview.png: the whole character;
- sheet_1.png, sheet_2.png, ...: numbered tiles. In each tile ONE group of layers is lit and outlined in red; the rest is dimmed. Under each tile, "guess" is a rule-based guess (often wrong or "unknown") and grey text shows any layer names found.

For each folder: Read overview.png and every sheet_*.png with the Read tool, decide what each tile's lit, red-outlined region is, and Write `answers_haiku.json` into that same folder.

tag (exactly one):
back_hair (hair behind the face/head, incl. the top of the head behind the bangs), front_hair (bangs and strands in front of the face), side_hair (locks hanging beside the face), headwear (hats, hair ornaments, clips, flowers, ribbons on the head), face (face skin), facedetail (blush, marks, moles), irides (iris, pupil, eye highlights), eyewhite, eyelash (eyelids, lashes, eye lines), eyebrow, ears, earwear, nose, mouth, mouth_inside (teeth, tongue), eyewear, neck, neckwear (separate collars, scarves, chokers, stoles, ties; a collar sewn to a shirt or kimono is topwear), body (torso skin), arm (arm skin), hand (hands, fingers, nails), leg (leg skin), topwear (shirts, jackets, coats, dresses, robes, kimono, sleeves), bottomwear (skirts, pants, belts), handwear (gloves), legwear (socks, stockings), footwear (shoes, sandals), tail, wings, objects (held items, bags, weapons, staffs, chains, charms), effect (symbols, sparkles, sweat, aura), background, unknown.

role (one): base (an ordinary painted part), line (line-art strokes), shadow (a darker shading layer over a part), highlight (a lighter shine or gloss layer over a part).

mixed: true if the lit region clearly holds two different tags (a hair strand together with a hair ornament), else false.
sure: false whenever you are guessing. Being honest here matters more than answering everything: unsure tiles are checked again.

File format, keyed by tile number as a string, covering every tile in that folder:
{"1": {"tag": "topwear", "role": "base", "mixed": false, "sure": true}, "2": {...}}

When every folder is done, reply with one line per folder: its path and how many tiles you labelled.
