<!-- lang: zh en ko ja -->
> [中文](../../zh/spec/RIG_PRESETS.md) · **English** · [한국어](../../ko/spec/RIG_PRESETS.md) · [日本語](../../ja/spec/RIG_PRESETS.md)

# Rig preset architecture: areas, base algorithms, and modules

[Documentation](../README.md) · Module and algorithm catalogs: face · [Deformers and parameters](DEFORMER_AND_PARAMETER_SPEC.md) · [Document layer](DOCUMENT_LAYER.md)

This page describes how automatic generation is organized into sections that can be chosen, turned off, and adjusted:

- Each area has one or several mutually exclusive **base algorithms**, plus a number of **modules** that can stack;
- The project's **recipe** records which base algorithm each area uses, which modules are off, and which values were changed.

The design of each algorithm and module (what it reads, what it produces, how keyforms are computed, and the standard for looking good) lives in four catalogs. This page only specifies how they are registered, selected, saved, replayed, and exposed.

## Goals

A model from automatic generation should, by default, have the visual strengths of a hand-tuned model:

- The eyes, brows, and mouth have a full expression space, beyond open and close;
- Blinking and speaking bring along the surrounding coupling and aftershock;
- A head turn has depth;
- Hair and clothing have weight and phase offset.

Each of these capabilities is made into a module.

- **On by default**: a new project turns on every applicable module.
- **Turn off as needed**: the user can turn any one off according to its parameter count, and can also turn a batch off at once with a preset or a parameter budget.
- **Few knobs**: each module exposes only a few meaningful knobs. Each knob drives a group of underlying values. Later, feature points and handles can be dragged on the canvas to adjust them.

Whether a module is finished is judged only by the result:

1. Use the review tool (`RigReviewTool`) to produce a pose sheet and motion clips;
2. Check each item against that area's "what looks good" and "common faults";
3. Numeric checks are only hints.

## Terms

| Term | Meaning | Code |
| --- | --- | --- |
| Area | The range one card covers: head and face, eyes, brows, mouth and lower face, hair, body, clothing and accessories, expressions, and motions. One card covers both the left and the right side | `RigArea` |
| Base algorithm | A mutually exclusive approach inside an area, for example whether eyelid closing uses the original algorithm or template fitting. The ID is `部位/名称`, with a version and adjustable values | `RigBase` |
| Module | One capability that can stack inside an area, such as smiling eyes, gaze with eyelids, and blink aftershock, each with its own switch. The ID is `部位.名称` | `RigModule` |
| Knob | One adjustable value of a base algorithm or a module: unit, range, default, and whether it folds into Advanced | `RecipeKnob` |
| Recipe | The base algorithm the project chooses for each area, the modules that are off, and the values that were changed, stored in the `rig` setting | `RigRecipe` |
| Part role | What a layer is inside its area: highlight, pupil, lower lashes, double-eyelid line, shadow, blush, side hair, ahoge, ponytail, braid, animal ear, ribbon, pendant, rigid ornament, sleeve, skirt | `PartRole` |
| Model preset | The quick combinations on the start page (minimal / default / full) and the groups in the model preset panel | `StartQuickPreset` |
| Rig values | The amplitude of the original algorithm when a parameter is at full value. They are the parameters of each area's original base algorithm. On the panel they sit in Values on each area's card | `RigTuning` |

## Registry

`core/recipe/RigModules.kt` is the only registry. Areas are registered in the order the panel shows their cards.

**Base algorithms**

- The first base algorithm of each area is `部位/legacy`, the way generation worked before the registry was introduced.
- Newer base algorithms are registered after it. The last one is the default for a new project.

**Modules**

Each module declares the following:

| Field | Meaning |
| --- | --- |
| `tier` | Importance: `CORE` (required for the area to look complete), `MAJOR` (clear visual quality), `MINOR` (a finishing touch). Presets and the budget trim by this |
| `requires` | Modules it depends on. Turning off a module that others depend on also turns off the modules that depend on it |
| `applies` | Whether the artwork has the parts it is meant to process (judged by area, side, and part role). A module that does not apply is not shown, is not counted in the cost, and is not generated |
| `parameters` | Parameters it adds for this artwork |
| `estimate` | The cost estimate used when it is off |
| `physicsGroups` | Physics groups it generates |
| `setting` | The corresponding switch that already exists in settings (see below) |

**IDs and versions**

- Once an ID might be saved by a project, it is not renamed.
- When an algorithm changes the generated result of an existing project, its version is raised. The recipe records the version at which the values were set.

**Modules that keep an old switch**

Several switches that existed before the registry are also registered as modules:

| Module | Corresponding setting |
| --- | --- |
| `eye.see-through` | `seeThroughEyes` |
| `eye.jelly` | `physicsEyeJelly` |
| `brow.see-through` | `seeThroughBrows` |
| `mouth.outline` | `mouthOutlineEnabled` |

- Their switch is the original setting, and it works the same way in an old project;
- They have no knobs.

## Cost

Cost has four items:

- Added parameters (each ID listed);
- Added keyforms: on meshes keyed by that module's parameters, the number of extra cells;
- The number of blend-shape forms;
- The number of physics groups.

Where cost comes from:

- When the module is on, it is measured on the generated rig (`ModuleCost.measure`);
- When the module is off, the module's own estimate is used, and the panel marks it with “≈”.

## Recipe: selection, saving, and upgrade

**New and old projects**

- A new project's settings write the `rig` key. An empty object means all defaults: each area uses the default base algorithm, and every applicable module is on.
- A project saved before recipes were introduced has no `rig` key in its settings and is read as the legacy recipe (`RigRecipe.Legacy`):
  - Each area uses the `legacy` base algorithm;
  - No module is on except the modules that keep an old switch.

  An old project therefore generates as before, and the settings text and the revision identity stay the same.
- Upgrade: write `rig: {}`. It can be undone.
- Return to the legacy recipe: write `rig: null`. The key is then no longer present in settings.

**Edits (settings patch `changes.rig`)**

The panel and MCP use the same path.

| Content | Form |
| --- | --- |
| An area's base algorithm | `bases: {部位: {base, values}}`; `null` returns to the default |
| A module | `modules: {模块: {on, values}}`; `null` returns to "on, values at default" |

- When the same base algorithm or module is kept, the values merge with the existing ones.
- For a module that keeps an old switch, `on` is written to the corresponding setting and must not carry `values`.
- All of the following are rejected:
  - An unknown area, base algorithm, module, or value;
  - A value outside the range;
  - A key other than `bases` or `modules`.

**Save format**

- Only items that differ from the default are written. A module records only `on: false` and values that were changed.
- On read, an unrecognized area, base algorithm, module, or value is skipped, so a project saved by a newer version can still be opened. Values are clamped into range.

## Rebuild and replay

The recipe is a generation setting, and it takes part in a rebuild as follows:

- It enters the settings the stage cache can read (`RigBuilder.stageConfig`);
- `RigGenerationChange` treats it as a change to the generation rules. Editing the recipe generates a new base G′, which is merged three ways with the old base G and the authored state M, and `rig_checkpoint` is written at the end of the log. Edits the user made on the old base are kept by the merge rules;
- Parameters a module adds enter the model only when that module generates. After the module is turned off, unreferenced parameters are removed by the merge.

Turning a module on or off, and adjusting a knob, is therefore one undoable history commit. After a save and a reopen, the rebuild proceeds as usual.

A module that recomputes when a parameter changes and keeps the user's fine adjustments should be implemented as a generator after the log, and should register the keyforms it owns in `DocumentGenerators` (see [Document layer](DOCUMENT_LAYER.md)).

## Part roles

Part roles refine semantic tags:

- For example, inside the same iris layer, the highlight, the pupil, and the iris body each move on their own;
- Inside the same lash layer, the lower lashes, the double-eyelid line, and the eyelid shadow each behave differently as the eye closes.

**As tag words**

- Each role is a tag word: `highlight`, `pupil`, `lower`, `lid`, `shadow`, `blush`, `side`, `ahoge`, `ponytail`, `braid`, `animal`, `ribbon`, `pendant`, `rigid`, `sleeve`, `skirt`, `upperlip`, `lowerlip`, `upperline`, `lowerline`.
- Role words are written in the layer's tags together with the area word and the side word (`LayerTags`).
- Each role can refine only specific areas. On an area it does not apply to, it has no effect.

**Automatic classification** (`PartRoles`) proceeds in this order:

1. Chinese, Japanese, English, and Korean keywords in the layer name and in the folder it sits in;
2. The layer's position among the layers of the same area, and its blend mode. For example:
   - Inside the iris, a smaller screen layer or light layer is a highlight, and a smaller dark layer is the pupil;
   - A lash layer below the eye's midline is the lower lashes, and a thin line above the upper lashes is a double-eyelid line;
   - Face, hair, and clothing layers set to multiply are shadows;
   - Front hair that hangs beside the face, down past the middle of the face, is side hair;
   - A lower garment that is one piece (not split into left and right), whose hem is no narrower than the waist and whose hem is not broken in the middle (trousers break between the two legs), is a skirt.

**User corrections**

- A role the user writes in the tags takes priority.
- Removing an inferred role from the tags is recorded as `PartRole.PLAIN` (no role), and analysis does not infer it again.
- MCP `layer_classify` sets this with the `part` field: a role word, `none`, or `auto`.
- The role is stored in the layer-classification override. An override with no role is exactly the same text as before.

**Who reads roles**

- Only modules read roles. The legacy recipe does not, so roles do not change the generated result of an old project.

## Panel and MCP

**Panel**

The Areas and modules group of the model preset panel:

- At the top:
  - Presets (full / standard / reduced / custom);
  - Parameter budget: modules are turned off by "importance from low to high, parameters from many to few" until the added parameters do not exceed the budget;
  - A reset button.
- Below that are the cards for each area. The card header shows how many modules are on and how many parameters were added. Expanded, a card shows:
  - When there is more than one base algorithm, a base-algorithm dropdown and its knobs;
  - One row per module: the switch and the cost, and the knobs once it is on;
  - Only modules that apply to the current artwork;
  - Last, that area's folded Values section: this area's rig values (`RigTuning`; head and face include head turn and the nose and ears; the body includes turning, up and down, leaning forward and back, scale, body Z and breathing, and depth and perspective). The commonly used ones are listed in small groups, the rest fold into Advanced, and there is a button that resets only these values.
- Only the rig values that actually take effect under the current recipe are shown. The part that a module or a base algorithm draws itself at generation time no longer shows the corresponding old values (`RigTuning.replacedBy`, `RigModules.tuningInEffect`). When `face.depth-turn` generates, the nine-cell head-turn values of the face, the features, and the front and back hair on AngleX / AngleY are not shown (the style head turn replaces them, and the original deformers stay still on the head-turn keys). Fitted eyes replace the original eye-close values (blink depth, eye corners, lash squash), fitted brows replace brow raise, fitted mouth replaces the original mouth-shape values, and hair chains replace the overall swing and curl of the front hair and of the back hair. In an old project (the recipe has not been upgraded), and when these modules are turned off, these values are shown and take effect as usual. The project file and MCP still accept every field.
- An old project shows a prompt at the top of the group that it can be upgraded. It is not upgraded automatically.

**MCP**

- `module_catalog` (read-only): lists every area, base algorithm, module, and knob. When a project is open it also returns:
  - Whether it is a new recipe, and each area's current base algorithm;
  - For each module, whether it applies, whether it is on, and its cost.
- `settings_update`: edits the recipe through `changes.rig`.
- `workspace_inspect scope=settings`: returns the stored form, which can be written back as it is.

## Feature-point fitting (`core/fit`)

The new base algorithms for the features do not rely on formulas written by hand. They read feature points from the original art, then fit a standard template onto them:

| Component | Role |
| --- | --- |
| `EyeFitting` | The line between the centers of the two eye whites first gives the head's horizontal direction (when there is only one eye, the rig's horizontal is used), and the eye's coordinate system tilts with it: a character with a tilted head or a side pose opens and closes along their own face. Along that direction, the two ends of the eye white's opaque pixels are the inner and outer corners. Between the two corners, the upper and lower lid margins are then read point by point along "the face's downward direction" (17 samples each; artists usually draw the eye white fully pressed under the lashes). The closed-eye line is read as its centerline, and the iris is read as its outer frame. Coordinates use the inner corner as the origin and the distance between the two corners along the head's horizontal as the unit. The outer corner may sit higher or lower than the inner corner. Width, the open ratio, the order of the upper and lower lid margins, and the corner tilt are checked. An eye that fails falls back to the original algorithm, and a reason is given |
| `EyelidModel` | A pseudo-3D eyelid: the eyelid is a plane that rotates about the line through the eye corners, and where it intersects the eyeball is the lid margin. As the eyelid moves down, the lid margin therefore stays an arc covering the eyeball, becomes straight only as it crosses the corner line, and after that becomes the downward arc of a closed eye |
| `ThinPlateSpline` | Thin-plate spline: maps the template's feature points exactly onto the detected feature points. The same mapping is used for every pose of the template |
| `BiharmonicField` | A biharmonic displacement field on a regular grid: displacement is given only at the constraints (lid margins, lip margins), everywhere else takes the smoothest solution, and the outer ring is zero. It lets eyelid skin, shadows, and similar layers follow the lid margin and move smoothly across several rows |
| `EyeRig` / `EyeBand` | `eye/fitted` observes, from the original art, the main stroke's band body, independent tufts, side edges, upper and lower parts, and join regions. The band body generates a closed shape from a continuous curve and transverse cross-sections, and keeps the line width. A normal close and a smile close are generated separately. A recognized pointed tuft may gather locally and cross to the other side of the band body, with a smooth transition in the root region. Side edges gather in near the band body. Additional eye shapes generate displacement inside the same eye coordinates, and the overall head turn is still carried by the existing parent. The neutral original-art key stays exactly unchanged. When reliable stroke observation is missing, the original eyelid path is kept. Eye-white clipping and stroke geometry are generated separately. A layer that combines both eyes binds left and right open and close independently by source position. |
| `BrowRig` | The `brow/fitted` base algorithm and the brow modules. Brows are read in the head coordinate system the same way: the two ends along the head's horizontal are the inner and outer ends (the end closer to the face midline is the inner end), and the centerline is read as 17 samples. The amount of up and down movement is about half the opening height of the eye below (the "movement amount" knob). Without fitted eyes it is a quarter of the brow length. Angle rotates along an arc about the brow midpoint rather than shearing, using five key points. Left and right movement, arching, and the blink sink are stacked blend shapes |
| `HeadTurn` | The `face.depth-turn` module. The face, the features, and the hair change by a "head-turn style" (`TurnStyle`, stored in the binary `rig/head-turn-style.bin`). A style has three layers. **Meta-deforms** are a set of named displacements, all in units of face width and mirrored by turn direction (turning toward +x is positive): the slide of the mid-face; the displacement, relative to the middle, of each of 24 points spaced equally by arc length along the face contour, starting from the chin; for each eye (eye white plus lashes), the lead and the up and down of the center relative to the middle, an overall 2×2 linear change (narrowing and tilt together; the far eye's outer end narrowing more is a result of the linear change itself, and is not a rotation), extra displacement of the part of the lash tail that passes the outer end of the eye white, the iris's relative position inside the opening and its own width, and the margin from the eye to the contour at the same height; for each brow, the lead, up and down, width, tilt, and arch; for the mouth, the lead, up and down, a 2×2 linear change, and the bend of the mouth line; the overall displacement of the near ear, the far ear, and the headwear; and a set of field parameters for each hair role (bangs, side hair, the crown, hanging hair, back hair, and the lower section of the back hair). Each meta-deform is a **Chebyshev surface** on the turn share a and the pitch share p: f(a,p) = Σ c<sub>i0</sub> U<sub>i</sub>(a) + Σ c<sub>0j</sub> V<sub>j</sub>(p) + Σ c<sub>ij</sub> U<sub>i</sub>(a) V<sub>j</sub>(p), U<sub>i</sub>(a) = T<sub>i</sub>(2a−1) − T<sub>i</sub>(−1), V<sub>j</sub>(p) = T<sub>j</sub>(p) − T<sub>j</sub>(0), each taken through order 4. At rest it is 0. The first term handles only left and right turn, the second only looking up and looking down, and the third is only the change that belongs to a diagonal pose itself. The coefficients can be read directly. For turn, order 1 is the constant-speed part and order 2 is lead or lag (the parts of one turn do not scale in sync; for example, the spacing between the eyes and the narrowing of the far eye become obvious only in the second half, and the near eye starts slowly). For pitch, the odd orders are the difference between looking up and looking down, and the even orders are the part the two share. A surface is fit to every keyform of each reference. Higher-order terms are penalized by order, so a reference with few keyforms stays low-order and smooth, and draws toward the average shape of the whole set. The principal axes are built on the coefficients. On storage, each value keeps only the terms that are used (after a term is removed, the change of any group of surfaces does not exceed the tolerance). A coefficient is one scaled 8-bit or 16-bit integer per value, then compressed. A whole style is about 48 KB. **User control** stacks art sliders on the default style. Each slider changes a group of meta-deforms along one direction (100% is one typical gap in that style's amount): depth (the lead of the face center line relative to the contour), perspective (far-side gathering: the far eye, the spacing between the eyes, and mouth width), gaze hold (the iris stays inside the eye), cheek-line swing (the difference between the near and far cheek lines), diagonal roll (eye-line tilt when looking up or down on a diagonal), pitch depth (the up and down of the features and the front hair when looking up or down), and accessory follow (headwear and front hair). Hair has three more sliders, hair follow, tip lead, and back-hair parallax, which scale the corresponding field parameters by proportion. Turn amount scales the surface's turn terms, pitch amount scales the pitch terms, and the product of the two scales the diagonal terms (the turn knob at 45% is the style itself), then multiplied by the global "turn strength". A style can also start from one of several complete head-turn styles (the `style` knob; on the panel this is the "head-turn style" dropdown: standard, strong depth, exaggerated, large, nod, soft). Each is a full set of surfaces on the style's principal axes, including hair. After one is selected, the module's other values return to the defaults, and the sliders adjust on top of that. Angle values are only positions of the parameters. The AngleX endpoints ±45 are the style's turn endpoints, and the AngleY endpoints ±30 are the look-up and look-down endpoints. Keyforms take AngleX at 0, ±11.25, ±22.5, ±33.75, and ±45, and AngleY at 0, ±15, and ±30. The small range and the large range each have their own shapes. **Drawing**: on each key the face (`FaceTurn`) places the 24 contour points at the positions the style gives. A middle block from between the eyes to below the nose slides as a whole, and the other points are interpolated by a thin-plate spline, so the face keeps its own shape. The face Warp and the finer face-skin Warp under it are evaluated on this field, and the head container's own displacement is subtracted. The features under the face no longer turn on their own. Each feature's own deformer (`FeatureTurn`) is placed on the same keys by its own meta-deform. An eye is transformed about the eye center by its overall linear change and moves with the center, and the lash tail is displaced on its own. The iris is placed into the transformed opening by its relative position inside the opening, and its width follows its own scale. Measure from the outer end of the eye white to the contour points that were at the same height at rest (using their positions in that pose). When the margin is smaller than the margin the style gives, the whole eye pulls inward. An eye that lies inside the contour at rest does not cross the contour. Brows work the same way. The nose and mouth of the default style are positioned as described below. The ears move as a whole by the near-ear and far-ear displacements, and the part that hangs below the chin travels less. Hair (`HairTurn`) moves as "one smooth sheet of hair", without splitting roles such as bangs and the crown. Hair in front of the face travels more and more from the crown downward (the crown is about half the mid-face displacement, the hairline matches that, the brow and eye level is thirty percent more, and below the chin it returns to the mid-face). There is no step in between, so a single piece of front hair is not cut at the forehead. The front layer and the back layer each have one sheet. Each sheet's displacement relative to the mid-face is a smooth function of position. Along head height (starting at the eye line, in units of eye line to chin) it is linearly interpolated between several heights, and bends are penalized. Horizontally (in units of face width, mirrored with the turn direction, and held past a certain range) it is quadratic, and the near side and the far side differ. Along the strand direction (each column is hair root 0 where this sheet starts and hair tip 1 where it ends) it is a linear term, and the tip leads the root. The three sliders, hair follow, tip lead, and back-hair parallax, scale by proportion the front sheet's deviation, the front sheet's tip term, and the back sheet's deviation. Each coefficient of a hair sheet is likewise a Chebyshev surface on turn (split into left and right) and pitch. Hair and headwear are generated as shapes added separately on AngleX and on AngleY. The follow deformers of the front hair and the back hair no longer add another head turn. Layers the style does not handle (glasses, ribbons, hanging accessories, and similar) still rotate around the head by the head "shell" they sit in: skin and features lie on the face plane, the nose is slightly proud, front hair is farther out, back hair is on the back, and the ears sit slightly behind the axis. Glasses and headwear are rigid as a whole, and the amplitude follows the turn amount and the depth. Known limits: on a large turn, the outer edge of the crown on the near side may show a little scalp (the gap-prevention rule is not finished). A diagonal pose of the hair is only the sum of the two directions |
| `HairChain` | The `hair/chains` base algorithm: one deform mesh per hair layer. The free part of the mesh (bangs from above the brows, side hair and back hair from below the eye line, and a ponytail or braid from the root; ahoge goes upward from the root) bends in segments like a pendulum chain. Each column bends about its own root, like separate strands. A layer wider than the face is split into left, center, and right bundles. Each has its own set of pendulums with staggered lengths, and while swinging they separate and then come back together. Category decides the segment count, the swing amplitude, the bounce, and the pendulums: bangs are short and light, side hair is longer, back hair is long and slow, ahoge is springy, and a ponytail or braid is a long whip. Each segment parameter and the up and down bounce are blend shapes added on the deform mesh. Physics groups are driven by the head and body angles, and bounce also reads nodding and breathing. This replaces the original single overall swing of the front hair and the single overall swing of the back hair. Hair that has already chosen cloth simulation is unaffected. Knobs: swing amplitude, and hair softness (low bends the whole bundle evenly; high bends mainly at the tips) |
| `FeatureHandles` | Feature handles that can be corrected on the canvas: the inner and outer eye corners and the midpoints of the upper and lower lids, the two ends of a brow and the brow peak, and the two mouth corners and the midpoints of the upper and lower lips. The recipe's `moves` records canvas-pixel displacement by feature and by handle. During fitting, the displacement is converted through head coordinates into rig space. Each point on a lid margin, a lip margin, or a brow centerline blends the displacements of the two ends and the midpoint by position (the midpoint uses a sine-shaped weight), then resamples in the coordinate system formed by the new eye corners or mouth corners. Every pose is rebuilt from the fitting after the move. The legacy recipe has no displacement |
| `MouthFitting` | The mouth is read in the head coordinate system the same way. On the open-mouth original art (the mouth and mouth-interior layers, not including a generated lip-line stroke), the two ends along the head's horizontal are the mouth corners. Between the two corners, the upper edge of the upper lip and the lower edge of the lower lip are read point by point (49 samples each, enough to follow the steep edge of a round mouth at the corners). When the opening height is under 12% of the mouth width (the original art is a closed-mouth line), or higher than 1.6 times the mouth width, it falls back to the original algorithm |
| `MouthRig` | The `mouth/fitted` base algorithm and the mouth modules. Every mouth shape is "a pose of the two lip margins": the original upper and lower lip margins are carried toward the closed line, rounded, flattened, narrowed, or translated, and each vertex keeps its relative position between the two lip margins. The thickness of the lip-line stroke outside the lip margin stays unchanged, and the part beyond it gathers in with open and close, so on close the upper and lower lips form one line that has its own thickness. Teeth follow the lip they belong to, and the mouth interior gathers in with them. When there is a lip-line stroke band (mouth outline is on), the band itself is the line, and the mouth and the mouth interior do not keep the lip-line band's thickness: on close the band overlaps into one line, the whole fill gathers under the line, and it does not show past the line's edge. Opening is staged: at 0.05 the lip gap appears (when there is a closed-mouth layer, the line swaps here), at 0.25 the lower lip drops first into a crescent and the upper lip and the corners stay still, at 0.5 an ellipse, and at 1 the original art. Mouth shape runs from a pucker (a small pout when closed, opening into a round "o") to a smile (the corners rise, opening into a wide D with a flat upper lip), with intermediate keys between the two ends. A round mouth (O, U, and an open pucker) is an ellipse centered on the opening's midline: the corners fall onto the midline, the upper and lower lips meet vertically at the corners, and the closer to a corner the more completely the path follows the ellipse, so no sharp corner is left and the lower lip does not pinch into a pointed bottom. A point outside the lip margin (a stroke, a mouth corner) follows the nearest point on the original lip margin, rather than the point directly above or directly below. At the corners the lip margin is very steep, and a distance measured vertically would make the original mouth corners stick up as spikes on a rounded mouth. The closed-line shape follows the mouth-shape setting (flat / smile / w / custom). When `reach` (default 100%) is above 100%, the maximum of the mouth-open parameter grows with it. Past the original art, the lower lip drops twice as much as the upper lip rises, the mouth narrows slightly, and the jaw keeps moving down, becoming a large mouth for a shout. Teeth translate as a whole row, the way a hand-made model does. They do not bend and they do not squash, and the width stays the original art (whether the full width crosses the corners, or covers only part of the mouth, is decided by the original art). They widen evenly with a full smile only when the `teeth` knob (default 0) is on. The upper teeth stay at the closed-mouth position through the first half of opening, so the growing opening reveals the teeth, and in the second half they move with the mouth to the original-art position. The lower teeth belong to the jaw and follow the lower lip the whole way. The upper teeth follow 65% of the middle of the upper lip, and the lower teeth follow 88% of the middle of the lower lip. The rounder the mouth, the less they follow. Relative to the upper lip, the upper teeth are never lower than in the original art, so when a smile raises the upper lip the teeth rise with it and no gap shows above. On a smile the upper teeth show at most half the opening height. By how far the tooth mesh extends under the lip at the middle of the mouth, the whole row is pulled back behind the upper lip when needed, so a small smile often shows no teeth at all. Teeth do not take part in the mouth interior's open-and-close shading, and they stay white. Teeth should be drawn larger than the opening and extending under the lip line. How much shows is decided entirely by the cavity clip, so in every mouth shape the teeth sit against the lip line with no gap, and on a small mouth, a pucker, a funnel mouth, and an O mouth the teeth hide behind the lips. The tongue moves down with the jaw as a whole by about 30% of the lower lip. The deep cavity and an oversized cavity base color do not move with mouth shape at all. They only translate with the mouth's sideways shift, plus head-turn parallax. The `narrow` knob (default 0) makes the mouth narrow first when it is half open, then widen back to the original art, for a mouth that opens into a small round shape first. A layered mouth (mouth layers with the roles `upperlip` / `lowerlip` / `upperline` / `lowerline`; see [PSD layer specification](PSD_LAYER_SPEC.md#part-layering)): a lip line is a stroke along the lip margin. It keeps its thickness (the line width is measured from the line layer), and the width direction rotates with the lip margin, so on a round mouth the line at the corners stands up with the lip margin and does not poke out sideways. The part beyond the line width (the side wall that stands up at the corners of a round mouth) gathers in as the mouth closes. On close the upper line moves up and the lower line moves down by half a line width each, and the two lines form one line. At the corners the two lines were already drawn as one stroke, so they no longer move toward each other, and the corner does not stack thicker. Near a corner (within 8% of the mouth width) the ends of the two lines gradually move together, and at the corner each follows by half, so on a round mouth the two ends always overlap into one corner and do not step apart. The opening follows the same line motion. When there are upper and lower lip lines, a mouth corner is the point where the two lines meet, not the midpoint of the side wall. A lip block (the lip together with the skin around it) moves as a whole with the nearest lip margin. The farther from the lip margin, the less it moves (the outer edge keeps about 35%), and the way the lip is drawn only bends and does not squash. The cavity (a mouth layer with no role) moves sandwiched between the two lip lines: the part under a lip-line band follows that lip line, and the middle is interpolated between the two lines by position, so in every mouth shape, including the corners, the cavity edge stays under the line and does not show outside the line. When there is a lip block, `inside` (default 50%) gathers in only part of it and the rest is covered by the lip block. When there is no lip block, the whole sheet gathers under the lip line. The cavity is the opening that clips the other layers. It does not add head-turn parallax; only the teeth, the tongue, and the deep cavity that it clips have it. A role-less mouth layer clipped onto the cavity in the PSD is the cavity base color: it is drawn larger than any opening, it does not take part in reading the mouth shape, it is clipped by the cavity, it is ordered behind the teeth and the tongue, and it does not move with mouth shape. The cavity can be an invisible shape (layer opacity 0) and still be a clip source. Each layer of a layered mouth keeps the mesh traced along the original art and is not replaced by the column mesh of an outlined mouth, so the opening's mesh covers the original art completely. Highlights and shadows on the mouth follow the upper lip or the lower lip according to where their center falls, or they act as the deep cavity, barely move, and are clipped by the cavity. Only the cavity clips the teeth, the tongue, and the deep cavity. A layered mouth does not generate a stroke band |
| `EyeModules` | Meshes and channels of the eye modules: the open/close axis (`eye.wide` extends to 2) and the smiling-eye axis (`eye.smile`) form the lid's keyform grid. Gaze with eyelids, emotion eye shapes and squint, and blink aftershock are stacked blend shapes, weight-limited by how open the eye is, and they do nothing when the eye is closed. On a smiling eye, the original closed-eye line bends into an upward arch. The iris squashes downward as the eye closes and shrinks slightly when the eye opens wide. Highlights drift opposite the head turn. Lid lines and highlights fade out ahead of the open amount. Each form of a blend shape records the mesh's own draw order and opacity, and only displacement is stacked |

**Eye, brow, and side-face coupling on a head turn** measures the eye's opening with the quarter points of the upper and lower lid margins and the inner and outer corners. Fitting reads the side-face contour on every pose and computes the clearance from the deformed lid-margin height. When a sideways turn is limited by space, the far eye shortens its horizontal opening with the inner corner as the anchor, keeps the originally fitted lid-margin height, and the iris couples to the opening width. When the margin is still short, the whole eye moves inward. The brow follows the horizontal correction of the eye on the same side, and corresponding samples of the brow centerline and the upper lid margin constrain the vertical spacing. When a contact can be read from the overlap of the side ear and the face skin, the ear root moves with the face skin and gradually returns to an independent displacement toward the helix, and the far ear reduces the contact weight as the side turn deepens. A place that lacks the corresponding landmark keeps the original style deformation.

**The upper-forehead contour**, under the default head-turn style, is fit from the original forehead top, the eye line, and the forehead cross-section. When the front hair has continuous coverage in the original art and the margin past the face edge is small, a sideways turn gathers the upper-forehead edge on the side being turned toward. A diagonal look-down weakens that gather according to pitch, and keeps the diagonal contour change that is in the style. The compensation falls off continuously from the forehead edge toward the midline, the forehead top, and the eye line. The front hair still uses its own deformer.

**Mouth and nose head turn of the default style** reads the two mouth corners, the midpoints of the upper and lower lips, the face width at the eye line, the spacing between the eyes, the tip and the upper edge of a separate nose, and the chin on the face contour. On a left or right turn, the chin moves with the face contour. Face width at the eye line and the spacing between the eyes decide the nose-tip lead and the mouth-to-nose spacing, respectively, and the side-face margin limits how far the nose tip and the mouth corners can move. Looking up and looking down each compute their own vertical displacement. On a look-down, the margin from the nose to the upper lip and from the lower lip to the chin constrains the opening after deformation. A nose bridge actually drawn up to the eye line joins back to the face plane at its upper edge. A short nose line and highlights move as a whole with the nose tip. A nose drawn on the face skin moves with the face skin.

The mouth's shared bend deformer fits the width, the height, and the upper and lower lip midpoints on a side turn, from the ratio of mouth width to face width and the height-to-width ratio of the original opening. Open and close, and mouth shape, are still evaluated independently on its child meshes. Diagonal keyforms keep the style's cross terms. When there is no separate nose layer, or no reliable landmark, the fit uses the mouth and the face contour that are available.

The extended open-eye range (`Eye Open > 1`) is expressed by `EyeOpeningModel` alone. The upper and lower lids each have two fourth-order Chebyshev displacement curves, one horizontal and one vertical, stored as binary in `rig/eye-opening.bin` and evaluated by Clenshaw recurrence. Displacement is scaled by `wideLift × (open − 1)`, so the original-art value 1 stays exactly unchanged. Horizontal is in units of the original eye width, and vertical is in units of the original opening height. The constant term of a curve carries the whole lift, and the other terms adjust the difference between the two ends and the local arc, so the center is not the only part arched up. The upper and lower lids share a two-dimensional displacement at the corners, and the eye white and the lashes share the same opening. A compound eye line assigns displacement continuously by its position between the upper and lower lids. An extended tail wing keeps the original-art offset, and the upper lashes keep the thickness of the same column. A stroke near the lower lid follows the nearest edge point on the original lower lid, so a thick tail wing under a steep corner is not sheared and flipped along vertical columns. The biharmonic fields of the double-eyelid line and the shadow receive both the horizontal and the vertical constraints. The default lift is 10% of the original opening, and it can be set to 0. The default aims at a gentle wide-open, and an explicitly saved amplitude keeps its value. The original closing range is kept on its own. When the eye opens wide, the iris keeps the original-art size and position, and pupil contraction is controlled by a separate emotion.

Opacity tracks related to open and close cover the parameter's full actual range, including the wide-open endpoint: the closed-eye line stays hidden above 1, and the lashes, the double-eyelid line, and the highlights keep the visibility each of them has when the eye is open, so they do not return to the static opacity after leaving the track.

`FitDetectionTool` draws the detection result and the lid margins at each opening on top of the original image, to check the fit. The lash, eye-white, and closed-eye-line meshes of a fitted eye are about four times finer, so bending into an arc does not show polygon corners.

Eye modules (all built on `eye/fitted`; they are not generated under the legacy recipe or under another base algorithm):

| Module | Importance | Added parameters | What it does | Knobs |
| --- | --- | --- | --- | --- |
| `eye.smile` | core | `ParamEyeLSmile` / `ParamEyeRSmile` | On close the two lids form an upward arch and the lower lid pushes up. An open eye stays unchanged | arch, lower-lid push |
| `eye.wide` | major | (the open parameter extends to 2) | Keeps the original eye shape. The upper lid lifts as a whole, the corners move with it, and the local arc is adjusted, gently enlarging the opening. The iris keeps the original size and position | wide-open amount |
| `eye.gaze-lid` | major | `ParamEyeBallLX/LY/RX/RY` | Looking up lifts the lid, looking down presses the lid, and looking left or right offsets the peak of the lid arc | follow amount |
| `eye.emotion` | major | `ParamEyeLForm` / `R` (−1 sad … +1 angry), `ParamEyeLSquint` / `R` | Angry: the inner corner is pressed down and the eye shape flattens. Sad: the inner side rises and the outer side droops. Squint: the lower lid pushes up | amount |
| `eye.lid-fade` | major | — | The double-eyelid line and the eyelid shadow sink and fade out before close (needs layers with the `lid` / `shadow` roles) | — |
| `eye.highlight` | major | — | Highlights drift opposite the head turn and fade out before close (needs a layer with the `highlight` role) | drift amount |
| `eye.jelly` | major | — | Iris jelly after a blink (the existing switch `physicsEyeJelly`) | — |
| `eye.aftershock` | finishing touch | `ParamEyeLBounce` / `R` | After a blink the lid overshoots and rebounds, and the lashes tremble lightly, driven by a pendulum whose input is open and close (physics groups `PhysicsEyeLBounce` / `R`) | softness |
| `eye.squash` | finishing touch | — | On close the iris squashes downward | — |

The motion base algorithm `motion/natural`: a blink closes in about two frames, holds, then eases open over about five frames, with an interval that varies from two to five seconds. One of them is a double blink, and the right eye is one frame later than the left. In idle, the gaze sweeps to one side three times, drifts slightly while it holds, and overshoots a little on the way back, and the brows rise and fall with breathing. A **speaking** motion is added (about six seconds; the opening is mostly small and occasionally large, with short pauses; each syllable is paired with one vowel; the mouth shape changes slightly; nods follow the stress). When a motion plays in the preview, the idle layer does not stop, and a parameter driven by both keeps one third of the idle motion. The VTube Studio configuration plays idle when tracking is lost. Motions of the legacy recipe are unchanged. Preview and export use the same curves.

Physics of the strand chains: bangs have a very small tremor with a blink. Hair softness also adjusts the pendulums: stiff hair has short pendulums, low mobility, and small delay; soft hair and flowing hair have long pendulums and large delay.

Expression modules:

| Module | Importance | Added parameters | What it does | Knobs |
| --- | --- | --- | --- | --- |
| `expression.cheek` | major | `ParamCheek` | Blush: the blush layer (part role `blush`) deepens, and the skin of the face, the nose, and the ears warms. With no blush layer, only the skin color changes (multiply-color keyforms) | amount |
| `expression.gloom` | finishing touch | `ParamGloom` | Gloom: the skin darkens and cools, and highlight layers fade out | amount |
| `expression.toggles` | major | — | A variant (switch) is shown in full at the midpoint between each pair of adjacent values. The new one fades in first and the old one fades out afterward, so a switch neither shows a gap nor two translucent layers. Switches and variant parameters enter each chain's pendulum at a small weight, and a switch gives the swing an impulse | — |

Standard expressions become richer along with these parameters (see the export notes).

Body and outfit modules:

| Module | Importance | Added parameters | What it does | Knobs |
| --- | --- | --- | --- | --- |
| `body.bust` | major | `ParamBustY`, `ParamBustX` (physics outputs) | The chest region of the upper body (one third of the way from the shoulder to the waist, and most of the torso width) rises and falls with breathing and body motion and sways left and right. The edge falls off, and the clothing around it does not move. It applies to tops and neckwear that are not sleeves and not skirts. A rigid ornament (part role `rigid`, such as a button or a badge) translates as a whole by its center and is not pulled into a bend. It needs a character that has a face | bounce amplitude |
| `body.arms` | major | `ParamArmLSwing` / `R` (physics outputs) | A hanging arm starts to swing a little later than the body, from the shoulder, and swings more toward the hand (blend shapes on the hanging-arm mesh, physics groups `PhysicsArmLSwing` / `R`). When a skeleton is enabled, the skeleton is responsible | — |
| `outfit.skirt` | core | `ParamClothSkirt<n>[L/C/R]<段>`, `ParamClothSkirt<n>Y` (physics outputs) | A skirt (part role `skirt`) uses a `HairChain` chain and, from below the waist, swings as left, center, and right sheets with staggered timing. `Y` is the hem flaring out and gathering in | swing amplitude |
| `outfit.creature` | major | `ParamPartEar…`, `ParamPartAnimalTail…`, `ParamPartWing…` (physics outputs) | Animal ears (part role `animal`), the tail, and the wings swing with a `HairChain` chain. An animal ear stands upward from a root at the bottom, and jitters with a blink. When a skeleton is already enabled, the tail and the wings are the skeleton's responsibility | swing amplitude |
| `outfit.ribbons` | major | `ParamClothRibbon…`, `ParamClothPendant…` (physics outputs) | A ribbon (`ribbon`) trails, and a pendant (`pendant`) swings from its attachment point. Each has a pendulum | swing amplitude |

Generated motion keeps the seams by a fixed chain root and weights that fall off: the root of a chain is pinned to the join line (a belt, a hairline, an attachment point), and chest bounce falls to zero at the edge of the region, so neighboring parts do not separate. Generation does not add Glue of its own. The Glue tool can still be used when it is needed. Hair shadows and highlights (part roles `shadow` / `highlight`) hang on the chain of the same kind of hair they cover, and bend with that bundle.

Turning the body with the head is not done in physics: a pendulum returns to zero after it comes to rest, and a physics output would overwrite the body angle the host is tracking. That is left to the host mapping.

Face modules:

| Module | Importance | Added parameters | What it does | Knobs |
| --- | --- | --- | --- | --- |
| `face.depth-turn` | core | — | Depth head turn by head-turn style (see `HeadTurn` in the table above). When the turn passes 40°, side hair on the far side (front hair with the part role `side`) recedes behind the face, as if going around the cheek | turn amount, pitch amount, depth, perspective, gaze hold, cheek-line swing, diagonal roll, pitch depth, accessory follow, hair follow, tip lead, back-hair parallax |
| `face.neck` | major | — | The neck shares a head tilt: under AngleZ the top of the neck rotates with the head about the head pivot, and the bottom stays on the shoulders. A shadow on the neck (part role `shadow`) rotates as a whole with the head rotation and the head turn, and is clipped to the neck | — |
| `face.skin-clip` | major | — | Face-detail layers that fall mostly inside the face (blush, shadows, and similar) and the shadow the front hair casts on the face (part role `shadow`) are clipped to the face, and do not move outside the face on a head turn | — |

Mouth modules (all built on `mouth/fitted`; the existing `mouth.outline` stroke lip line is laid out again along the fitted mouth's lip margins):

| Module | Importance | Added parameters | What it does | Knobs |
| --- | --- | --- | --- | --- |
| `mouth.vowel` | major | `ParamA` / `ParamI` / `ParamU` / `ParamE` / `ParamO` | Five vowel mouth shapes (A wide open, I wide across, U rounded in, E medium-open and wide, O round), stacked on open and close and on mouth shape. When they open, the closed-mouth layer hides and the mouth interior is shown | — |
| `mouth.capture` | major | `ParamMouthX`, `ParamMouthCornerL/R`, `ParamMouthPuckerWidenL/R`, `ParamMouthPuckerWiden`, `ParamMouthShrug`, `ParamMouthPressLipOpen`, `ParamMouthFunnel`, `ParamCheekPuff`, `ParamJawOpen` | The shapes face capture commonly uses: a crooked mouth, a pout and a grin, a mouth shrug, pressed lips or lips parting, a funnel mouth (ch, sh, u: the corners gather toward the middle and a small round opening opens in the center), puffed cheeks, and a jaw opening with the lips closed (the chin moves down, and the closed mouth moves down and narrows). On a layered mouth, every mouth shape acts by each layer's way of following. Pressed lips and lips parting (`ParamMouthPressLipOpen`), together with open and close and mouth shape, are built into a three-dimensional keyform (−1 both lips press tight and roll inward, and the lip color lessens; +1 a lip gap and the teeth show, while the corners stay closed; the wider the mouth opens, the less parting does), so smiling with the teeth showing, and pressing the lips on a half-open mouth, each have their own shape. Parameter names follow the names VBridger uses, and a face-capture plugin can drive them directly | — |
| `mouth.tongue` | finishing touch | `ParamTongueOut` | When there is a tongue layer: the mouth opens, the original tongue keeps the cavity clip, and a transparent companion tongue layer extends down past the lower lip. Requires `mouth.capture` | — |
| `mouth.jaw` | core | — | When the mouth opens, the face skin below the mouth-corner line moves down with the jaw (at the chin this is 15% of the original opening), the cheeks pull in slightly, and the nose moves a little | jaw drop |
| `mouth.cheek-lid` | finishing touch | — | When the mouth shape rises, the cheek pushes the lower lid up. Requires fitted eyes | amount |
| `mouth.interior-depth` | major | — | On a head turn, the upper teeth, the lower teeth, the tongue, and the cavity lag the lip line by their own depth. The tongue, the lower teeth, and the cavity are darker at a small opening, and return to the original-art brightness when fully open (multiply-color keyforms) | depth |
| `mouth.spring` | finishing touch | `ParamMouthSpring` | A pendulum whose input is open and close (and a little mouth shape) gives the jaw a slight rebound (physics group `PhysicsMouthSpring`). Requires `mouth.jaw` | — |

Brow modules (all built on `brow/fitted`):

| Module | Importance | Added parameters | What it does | Knobs |
| --- | --- | --- | --- | --- |
| `brow.shape` | core | `ParamBrowLAngle` / `R` (−1 inner end pressed down … +1 raised), `ParamBrowLX` / `R`, `ParamBrowLForm` / `R` (−1 flat … +1 arched) | Brow angle, left and right movement, and brow shape | amount |
| `brow.lid-coupling` | major | — | The upper lid and the brow are like one layer of skin: when the brow is pressed down or furrowed, the upper lid presses down and flattens (a stare), and when the brow rises the upper lid lifts. Emotion also fans out to the pupil: raising the brow (surprise) shrinks the iris slightly, and pressing the brow lets it out slightly. Requires fitted eyes | amount |
| `brow.blink` | major | — | On close the brows sink slightly (more at the inner end), they rise when `eye.wide` opens the eye wide, and they rise back when a smiling eye closes | sink |
| `brow.spring` | finishing touch | `ParamBrowLSpring` / `R` (physics outputs) | When a brow stops it overshoots slightly and then falls back. The pendulum takes brow height and angle as input (physics groups `PhysicsBrowLSpring` / `R`) | — |

## Implementation conventions

- The code path of a `legacy` base algorithm stays unchanged:
  - A new base algorithm or module chooses its implementation at the dispatch point by `config.recipe.base(部位)` and `RigModules.builds(模块, config, 素材)`;
  - An implementation depends only on pure geometry input, and does not read interface state.
- When a module changes the keys of a parameter, every mesh that shares that parameter must use the same set of keys. For example, the eye white is the iris's mask and must share keys with the lashes.
- A secondary change prefers a blend shape stacked on the main keyforms, so the keyform count does not multiply by dimension. An export target that does not support blend shapes has them baked or dropped by the downgrader, and a loss report is written.
- A generated result contains only blend shapes Cubism can express (`CubismBlendShapes`, run after the base rig is assembled): Cubism stacks blend shapes only on parameters of the "blend shape" type, and that kind of parameter cannot be a keyform axis. A blend shape hung on an ordinary parameter cannot be written into moc3 / cmo3, and the Cubism runtime draws it wrong. Therefore:
  - A parameter used only by blend shapes, whose default is 0 and whose forms each have their rest key at 0 (vowels, face capture, brow shape, chain segments of hair and cloth, arm swing, the chest, and similar), becomes a blend-shape parameter;
  - A shape on a parameter that is also some keyform axis (head-turn angles, eye and mouth open and close, gaze, brow height and angle, mouth shape) becomes a keyform. When the mesh's own keyform grid (the union of the geometry and each channel's axes, which is that mesh's keyform count in moc3) does not exceed 256, and vertex values do not exceed 120,000, the shape is folded into the mesh along those parameters' keys, and the result is exact (a shape's weight is linear between keys, and the limits are linear between points, so a mesh evaluated at every key reproduces it completely). When that is exceeded, the shape is given to the mesh's own Warp (`<网格 ID>_Turn` holds the head turn, `<网格 ID>_Shape<n>` holds the rest of the coupling, each with no more than 64 keyforms), inserted between the mesh and its original parent. An 8×8 grid of points least-squares fits, from the shape, the displacement of the mesh vertices under each of the mesh's own keyforms, the same way a hand-made Live2D model puts the head turn in a part Warp. When the parent carries a bend, a Warp can only approximate by its grid points, and the error is about 1 canvas pixel;
  - Opacity and color that a shape changes are folded into the mesh's channels. A generated shape never changes draw order (each form's draw order takes the mesh's final rest value).
- IDs of generated objects and parameters are deterministic:
  - Parameters a module adds prefer Cubism standard names, and face-capture extensions use common community names;
  - A new object ID is derived from the module and the target. Random numbers are not used.

## Extending a module

1. Write the design in the catalog:
   - When it applies, what it produces, the algorithm step by step, and the knobs;
   - What looks good, and the common faults;
   - What it falls back to on failure.
2. Register it in `RigModules`: the area it belongs to, its importance, its dependencies, when it applies, its parameters, its cost estimate, and its knobs.
3. Implement it at the generation dispatch point. The `legacy` path stays unchanged.
4. Add the name and the description in four languages (`settings.rig.module.*`, `settings.rig.knob.*`).
5. Verify:
   - Use `RigReviewTool` to produce a pose sheet and motion clips, and check them against the standard for looking good;
   - Use `ExportGoldenTool` to confirm that a project on the legacy recipe is unchanged byte for byte;
   - Add regression tests (`RigRecipeTest`, `RigModulesScenario`).

## Three-layer mouth shapes and tracking export

When a stroke is generated for a whole-mouth artwork, the closed-mouth line uses the continuous curve of the mouth-shape setting, and the midpoint of the open contour is not kept as the closed-mouth line. An independent layered lip line still keeps its original-art structure. When the original art and the target contour use different sampling densities, the sampling residual is drawn back as the mouth closes, so the overlapping span of the upper and lower lip margins and the stroke that crosses the lips converges onto the same line on close, and does not leave a short tail at a steep corner. The original open pose keeps the original contour.

Generated stroke applies only to a cavity layer that has no original lip art. When upper or lower lip lines, lip skin, or a closed mouth seam already exist, or when overlapping complete mouth-shape variants exist, no stroke is added. A mouth-interior clip layer, and lip highlights and shadows, are not sources of the stroke either. Native generation and algorithm plugins use the same test for candidate layers.

The stroke centerline and the original lip line are bound separately: the centerline moves along the target lip margin, and a cross-section that keeps the line width is then generated along the target path. On an exact close, both the generated stroke and the original line mouth remove each expression's base-opening residual. An original lip line keeps its material thickness and its corner strokes along each of its lip-margin stations, and a corner no longer keeps the source open-mouth arc as extra thickness. The mouth-interior boundary and the lip margin use a consistent correspondence, and lip skin keeps its coverage area. Extra motion such as a lip press, showing the teeth, and the jaw continues to stack on the shared mouth-shape target. Stroke thickness must not be kept as cavity opening.

A fitted mouth is evaluated in three layers: a normalized contour, a deformation subspace, and user parameters. The neutral pose of the original open mouth stays in place, and on close the upper and lower lip margins meet. A cel lip line keeps its thickness by the local tangent and the local normal, and the outer edge of thick-painted lip skin shares the perioral deformation with the main face. The upper teeth follow the upper jaw and the lower teeth follow the lower jaw. On a crooked mouth the tooth row tilts as a whole and gathers on the compressed side. A one-sided mouth corner, and a one-sided puff or press, are stacked as independent parameters.

The teeth and the tongue of a layered mouth are clipped by the cavity mask, and they do not fade in with the opening. A cavity layer whose opacity is zero can still be a mask. Sticking the tongue out generates a companion mesh with a deterministic ID, `<舌网格 ID>_Out`: transparent at rest, not clipped by the cavity, above the lower lip and below the upper lip. Vowel mouth shapes can be driven on their own, and they fall off as the open parameter grows, so speech and face capture do not open the mouth twice when they are stacked.

`eye.gaze-lid` adds `ParamEyeBallLX/LY/RX/RY`. The shared gaze parameters stay. An independent gaze acts on the corresponding iris and eyelid at the same time, and stays compatible with two-dimensional parameter mirroring and with project rebuild.

VTube Studio export offers the following choices. Each output parameter is mapped only once. A model parameter that does not exist does not get a mapping:

| Setting | Choices and behavior |
| --- | --- |
| `vts_mouth` | `standard` is face capture; `voice` is speech vowels (when there are no vowel parameters, volume drives the opening); `voice_hybrid` mixes speech and face capture; `vbridger` adds the plugin inputs JawOpen, MouthPucker, MouthFunnel, MouthShrug, and MouthPressLipOpen |
| `vts_gaze` | `independent` is left and right independent, and an old model falls back to shared gaze; `shared` shares the right eye's gaze; `mouse` is the mouse position |
| `vts_brows` | `independent` is left and right brow height; `shared` shares Brows |
| `vts_position` | Off by default. It maps the existing `ParamFacePositionX/Y/Z` |
| `vts_angry` | Off by default. It maps the experimental FaceAngry input onto the existing eye-emotion parameters |

This covers the official 33 inputs for the head, gaze, mouth, mouse, and voice. Mutually exclusive inputs are chosen by mode. A custom model can take the corresponding official input with `Param<输入名>` (for example `ParamVoiceSilence`). The exporter does not create an empty parameter for it. VBridger mode requires the user to run the plugin and provide the corresponding inputs.

Input names follow the [official VTS parameter list](https://github.com/DenchiSoft/VTubeStudio/wiki/VTS-Model-Settings#supported-input-parameters-face-tracking-etc), and plugin parameters follow the [official VBridger documentation](https://github.com/PiPuProductions/VBridger-Documentation/wiki).

Current visual limit: when thick-painted lip skin combines a maximum crooked mouth with opening, per-point displacement blending of the perioral skin can fold the local mesh. That combination has not passed visual acceptance, and the shared skin field needs a further correction.

### Eye structure correction

`module_catalog.eye_structure` returns, for each eye, the original-art paths, the component IDs, a source summary, the upper and lower assignment, the geometry type, the join regions, and the branch paths. Observation takes the originally generated pixels as its source, and keeps the original layer, the UVs, the draw order, and the explicit masks. A numeric suffix does not mean a replacement image.

`settings.rig.eyeParts[layerId][componentId]` can override `side`, `region`, `primitive`, `contacts`, and `path`, and it must also carry a matching `sourceStamp`. Path and join coordinates use that eye's local coordinates: the inner corner is the origin, eye width is the unit, and the second axis points down the face. An override that does not match the source is still saved, but it is not applied to original art that has changed. After the source is restored, it can take effect again. A null patch of a component, a layer, or the whole of `eyeParts` resets it, and a continuous settings gesture keeps the order of the resets. A new settings node uses schema 5. An old project with no structure correction keeps its original node version.
