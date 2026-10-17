# Front and back review

You check pieces of anime character art (Live2D models) that an artist drew as one picture but laid partly in front of
and partly behind something else, like a strand of hair passing behind the ear, a cord passing behind the other side of
a dress, or fingers in front of and behind the back of the hand.

Your batch file lists sheet paths without an extension, one per line. For each `<path>`, open `<path>.png` (the
picture) and `<path>.txt` (one line per row: row number, the current name of the red thing, the current name of the
blue thing). Only open the sheets in your batch.

Each row of a sheet has four panes:
1. the character around it, faded, with the thing outlined in **red** and what lies between its two sides outlined
   in **blue**;
2. the red thing drawn on its own, whole, hidden parts included;
3. the part of it drawn in front of the blue thing;
4. the part of it drawn behind the blue thing.

For every row decide:
- `keep`: true when panes 3 and 4 are two sides of one object (one hair strand, one cord, one arm, one fold of cloth)
  and the blue thing really lies between them, so that "the part of the red thing in front of the blue thing" and "the
  part behind it" make sense. False when the two sides are different objects, when one side is a tiny scrap or empty,
  when the blue thing is the red thing's own line art, shading or highlight, or when you cannot tell.
- `name`: a short plain English name for the red thing as a whole (e.g. "side hair strand", "chest cord", "left
  forearm", "skirt frill"), no left/right. Fix the current name if it is wrong; keep it if it is right.
- `other`: a short name for the blue thing in the same style (e.g. "ear", "bra cup", "back of the hand").

Write one JSON file per sheet next to it, `<path minus "sheet_">answers_<n>.json`, i.e. for `.../sheet_7` write
`.../answers_7.json`:

```json
{"1": {"keep": true, "name": "side hair strand", "other": "ear"},
 "2": {"keep": false, "name": "arm edge", "other": "arm skin"}}
```

One key per row on the sheet (rows are numbered on the left). Check each file parses as JSON. Do not change any other
file. When done, reply with one line: how many rows you answered and how many you kept.
