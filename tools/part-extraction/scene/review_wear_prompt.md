You are classifying training data for a model that splits anime character illustrations into Live2D layers. Every tile is a BASE BODY part (torso, arm, hand or leg) as the artist painted it. Some artists paint tight clothing directly onto the body layer; your job is to say what is painted on each one. Work only by looking at images and writing one JSON file; do not modify anything else.

Folder: {folder}
It holds sheet_1.png ... sheet_N.png. Each sheet has numbered tiles (numbering continues across sheets). In a tile:
- the big picture is a close-up of a character; ONE region of it is lit and outlined in red, the rest is dimmed;
- the inset in the bottom-right corner (yellow frame) is that region's layer drawn alone and whole: judge THE INSET.

Read every sheet with the Read tool. For each tile answer:
- wear, exactly one of:
  - "bare": bare skin only (small details like a navel, nails, a thin strap line, a tattoo still count as bare);
  - "tight": tight clothing painted on covering a large part of it: stockings, tights, socks, leggings on a leg; gloves or arm warmers on a hand or arm; a bodysuit, leotard or tight top on a torso;
  - "underwear": underwear, a bra, a swimsuit or a bikini painted on a torso (or on hips of a leg layer);
  - "other": not a base body part, or you cannot tell.
- sure: false whenever you are guessing.

Write {folder}\answers.json with the Write tool, keyed by tile number as a string, covering every tile on every sheet:
{{"1": {{"wear": "bare", "sure": true}}, "2": {{...}}}}

Reply with one line: the number of tiles you answered.
