You are checking training data for a model that splits anime character illustrations into Live2D layers. Work only by looking at images and writing one JSON file; do not modify anything else.

Folder: {folder}
It holds sheet_1.png ... sheet_N.png. Each sheet has numbered tiles. In a tile:
- the big picture is a close-up of a character; ONE region of it is lit and outlined in red, the rest is dimmed;
- the inset in the bottom-right corner (yellow frame) is that region's layer drawn alone and whole, as the training target;
- the caption "rules: X" is a guess made by simple rules, often wrong.

Read every sheet with the Read tool. For each tile decide:
- tag: what the lit region (and its inset) really is, exactly one of:
  back_hair (hair behind the face/head, incl. the hair mass on top of the head behind the bangs), front_hair (bangs and strands in front of the face), side_hair (locks hanging beside the face), headwear (hats, crowns, hair ornaments, clips, ribbons on the head), face (face skin), facedetail (blush, marks, moles), irides (iris, pupil, eye highlights), eyewhite, eyelash (eyelids, lashes, eye lines), eyebrow, ears, earwear (earrings, piercings), nose, mouth, mouth_inside (teeth, tongue), eyewear, neck, neckwear (separate collars, chokers, scarves, ties), body (torso skin), arm (arm skin), hand, leg (leg skin), topwear (shirts, tops, jackets, coats, dresses, sleeves), bottomwear (skirts, pants, belts), handwear (gloves), legwear (socks, stockings, tights), footwear (shoes, boots), tail, wings, objects (held items, bags, weapons, chains, charms), effect, unknown.
- clean: true if the inset is ONE part of that tag (it may be partly hidden in the picture and drawn whole in the inset — that is good). false if the inset mixes clearly different things (a boot together with a stocking, skin together with clothing, hair together with an ornament), is mostly empty, or is not what the lit region shows.
- sure: false whenever you are guessing.

Write {folder}\answers.json with the Write tool, keyed by tile number as a string, covering every tile on every sheet:
{{"1": {{"tag": "face", "clean": true, "sure": true}}, "2": {{...}}}}

Reply with one line: the number of tiles you answered.
