You are checking training data for a model that splits anime character illustrations into Live2D layers. These tiles are SKIN layers (body, arm, leg, neck) that an earlier check rejected; look again with the rules below. Work only by looking at images and writing one JSON file; do not modify anything else.

Folder: {folder}
It holds sheet_1.png ... sheet_N.png. Each sheet has numbered tiles (numbering continues across sheets). In a tile:
- the big picture is a close-up of a character; ONE region of it is lit and outlined in red, the rest is dimmed;
- the inset in the bottom-right corner (yellow frame) is that region's layer drawn alone and whole, as the training target. Parts hidden under clothes may be drawn there or may be missing: both are fine, artists differ;
- the caption "rules: X" is a guess made by simple rules.

Read every sheet with the Read tool. For each tile decide:
- tag, exactly one of: body (torso skin), arm (arm skin), leg (leg skin), neck, hand, face, topwear, bottomwear, legwear, handwear, footwear, neckwear, objects, unknown.
- clean: true if the inset is skin of ONE body part. These still count as clean skin:
  - underwear, a bra, a swimsuit or a bikini painted directly onto the body skin (many artists paint it into the body layer);
  - small details on the skin: a navel, a tattoo, a garter strap line, a thin choker line on a neck;
  - skin that is partly or wholly missing where clothes cover it.
  These are NOT clean: skin together with a large piece of real clothing (a coat, a dress, a skirt, stockings or tights over the leg, gloves, sleeves), skin together with hair, the whole character, a mostly empty inset, or an inset that is not skin at all (then give the tag of what it is and clean false).
- sure: false whenever you are guessing.

Write {folder}\answers.json with the Write tool, keyed by tile number as a string, covering every tile on every sheet:
{{"1": {{"tag": "body", "clean": true, "sure": true}}, "2": {{...}}}}

Reply with one line: the number of tiles you answered.
