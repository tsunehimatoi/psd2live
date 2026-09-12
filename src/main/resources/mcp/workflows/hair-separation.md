# Hair as movable painted volumes

A logical lock is a continuous root-to-tip volume, not a visible patch. Highlights and islands separated by occlusion can belong to one lock. Count independent moving locks separately from drawable sections.

Useful working annotations are the root region, centerline, changing width, tip, visible paint, hidden extension, local occluders and intended motion. These can be estimates; record uncertainty instead of inventing certainty. An annotation is useful only if it informs an edit.

Hidden coverage depends on relative motion: two locks moving apart need more overlap than two moving together. Continue broad buried roots and bodies beneath neighbours, not just the exact footprint currently covered. A continuous underlayer can help some hairstyles; preserve intentional partings. Local front/back crossings may require multiple drawable sections or masks for one logical lock.

Choose redraw technique for the style: source pixels for unchanged regions, SVG for suitable clean shapes, painting or image editing for new detail. Try the assembled silhouette early. Preserve character identity and useful visible paint; hidden contours need plausible coverage rather than unknowable accuracy.

## Motion

Shared head motion belongs in the parent; independent sway belongs in a child. warp_create currently creates a parent-frame identity lattice, not a piece-sized cage or a copy of parent animation. Locate the actual lock within that frame. rig_transform sway accepts explicit root/tip and pins the root region; softness increases the root-to-tip bending gradient. A root near the top-left is not an instruction to scale around that corner.

Physics drives a parameter, which samples authored shapes. Create suitable neutral and sway forms, then connect physics as needed. The current physics_put supports one Angle input and one output with a two-particle pendulum; it is not a general multi-stage chain editor.

## Diagnose causes

| Evidence | Likely causes / useful next check |
|---|---|
| Neutral looks right; scalp appears during sway | Hidden body too narrow, wrong relative amplitude, misplaced attachment |
| Root drifts with independent sway | Wrong pivot/root region or repeated parent motion |
| Correct shape but unnatural timing | Physics input, delay, output scale or saturation |
| Extra bang in composite | Original replacement still visible, wrong draw order, or extra painted lock |
| Fringe only around isolated cutout | Matte cleanup; judge at intended viewing size |

Inspect assembled neutral and relevant motion combinations, excluding replaced source artwork. An opaque scalp can be exposed without any transparent pixels: alpha coverage alone cannot certify hair coverage. Geometry diagnostics do not establish beauty. Report specific regions and poses; do not call all motion safe after a few samples.
