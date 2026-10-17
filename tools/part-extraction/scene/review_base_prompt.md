You are checking training data for a model that splits anime character illustrations into Live2D layers. These tiles are BASE BODY parts (body, arm, leg, neck, hand): the character's own body layer as the artist painted it, before separate clothing layers go on top. Work only by looking at images and writing one JSON file; do not modify anything else.

Folder: {folder}
It holds sheet_1.png ... sheet_N.png. Each sheet has numbered tiles (numbering continues across sheets). In a tile:
- the big picture is a close-up of a character; ONE region of it is lit and outlined in red, the rest is dimmed;
- the inset in the bottom-right corner (yellow frame) is that region's layer drawn alone and whole, as the training target;
- the caption "rules: X" is a guess made by simple rules.

A base body part includes whatever the artist painted directly onto it. All of these are fine and still clean:
- underwear, a bra, a swimsuit, a bikini, a leotard painted on the torso;
- stockings, tights or socks painted onto the leg; nail polish on a hand; a choker line on a neck;
- tattoos, a navel, shading, garter lines;
- skin that is partly or wholly missing where clothes cover it, or drawn complete under the clothes.

Read every sheet with the Read tool. For each tile decide:
- tag, exactly one of: body (torso), arm, leg, neck, hand, face, topwear, bottomwear, legwear, handwear, footwear, neckwear, back_hair, objects, unknown. Use body/arm/leg/neck/hand when the inset is that base body part (with anything painted on it); use a clothing tag only when the inset is mainly a separate garment with no body under it.
- clean: true if the inset is ONE base body part as above. false only if it clearly joins separate things: a body part together with a whole loose garment that hangs away from the body (a coat, a dress skirt, a cape, long sleeves), together with hair, several unrelated parts, the whole character, or a mostly empty inset.
- sure: false whenever you are guessing.

Write {folder}\answers.json with the Write tool, keyed by tile number as a string, covering every tile on every sheet:
{{"1": {{"tag": "leg", "clean": true, "sure": true}}, "2": {{...}}}}

Reply with one line: the number of tiles you answered.
