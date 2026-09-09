# Face shape and reference poses

Nine poses are samples on two parameter axes, not nine independent axes. More samples increase expressiveness but cannot repair incorrect structure or missing art.

Separate head surface motion, feature placement, skin silhouette and local expression when their ownership differs. At larger turns, near/far features differ in projection and visibility; nose, cheeks, eyes, mouth and ears need coherent attachment and occlusion. Style and original asymmetry matter more than a universal compression percentage.

A generated target pose is a candidate reference, not ground truth. It can change identity, proportions or detail. Compare a fixed camera and mark a few useful correspondences (silhouette, eye corners, nose, mouth) before adjusting selected geometry. Prefer local corrections with pinned regions over unbounded whole-face fitting. rig_transform landmarks interpolates displacement from caller-supplied from/to point pairs, including unchanged pairs for pins. It supports reference-guided corrections, not automatic image correspondences or an image-to-rig solver.

Inspect target and neighboring/intermediate poses. Diagonal forms combine X/Y effects and can need additional correction. An attractive endpoint can interpolate poorly. Exposed regions at large turns may need new paint or masks, not stronger stretching.
