# Item or segment

Anime character art for Live2D is drawn in many layers. A reviewer named each layer (e.g. "front hair strand",
"finger", "chest skin"), and layers with the same name in one character were grouped. We now want to teach a model to
repaint a single layer of such a group when asked "the <name>". That only makes sense if one layer is a whole <name>.

Your file is a TSV: number, body-part tag, name. For each line decide:

- `item`: the name is a thing that naturally comes several to a character, each layer being one whole of it, so
  "the <name>" fits a single layer. Examples: front hair strand, side hair lock, finger, glove finger, skirt frill,
  wing feather, ribbon tail, horn, button, petal, sleeve (one per arm), skirt panel (a skirt sewn from panels), earring.
- `segment`: the name is one surface or one object that the artist only cut into pieces to animate it, so a single
  layer is a cut-off part of it and "the <name>" would mean the whole. Examples: chest skin, forearm skin, upper arm
  skin, thigh skin, hand skin, face, eyelash, upper eyelid, eyebrow, mouth, skirt (as a whole), jacket body, cape.
- When unsure, answer `segment`.

Write a JSON object to the output path given in your task, with one key per line number (as a string) and the value
"item" or "segment", e.g. {"1": "item", "2": "segment"}. Cover every line. Check that it parses as JSON and has as many
keys as the file has lines. Do not change any other file. Reply with one line: how many lines, how many items.
