@@ t-beginner | Tutorials for Beginners
@@ t-eye-blink | Learn "Eye Blinking" in 10 Minutes
Make a character blink. In PSD2Live, blinking is generated at import time from the layer tags, so in this lesson you split the material into the right layers, confirm the closed-eye shape, and fix whatever you are not happy with.
## Prepare the material
- Eye white, pupil and upper eyelash each get their own layer; the layer names can be `eyewhite` / `眼白`, `irides` / `瞳孔`, `eyelash` / `睫毛`.
- Draw only the upper lashes on the eyelash layer; if the lower eye outline is mixed in, the lower outline is pulled along when the eye closes.
- Split the left and right eyes into separate layers with the side in the name, such as `eyelash-l` or `左睫毛` (left and right refer to the character itself).
- If you want a specific closed-eye shape, draw a separate `eye_close` / `闭眼` layer; if you do not, the program generates the closed-eye curve along the eyelashes.
## Generate and check
1. Choose **File → New → New project from PSD…** ({key:OPEN_PSD}) and select the PSD.
2. Leave **Quick setup** on the start screen at **Default**; after generation you land in the editing workspace.
3. In the **Layers** panel, check the **Tags** (part and side) of the eye layers, and if one is wrong, open the tag and change it.
4. In the **Parameters** panel, drag **Left Eye Open** / **Right Eye Open**: 0 is closed and 1 is open.
5. If the arc of the closed eye or the eye corner position is not right, adjust **Blink sag**, **Blink corners** and **Closed lash thickness** in the **Values** of the **Eyes** component card in the **Modifiers** panel (tick **Left and right apart** to set each side separately), which is much quicker than correcting by hand.
{tutorial=BASIC}
## Fix the closed eye by hand
Do it by hand when the values cannot get it right. Your corrections are layered on top of the generated result and are kept after regeneration.
1. In the Hierarchy, select the eyelash or the eye white and move the open parameter to the closed-eye key (right-click on the key marker to snap).
2. Switch to **Deform** mode ({key:MODE_DEFORM}) and drag the control points, or push the shape with a brush ({key:TOOL_BRUSH}).
3. After fixing one side, choose **Mirror keyform → Mirror to …** in the canvas right-click menu; the shape is written to the same kind of object on the other side, and the parameter is switched automatically to that side's open parameter.
4. Switch to **Preview** ({key:MODE_PREVIEW}) and turn on **Idle animation** to see the automatic blinking.
{tutorial=DEFORM_MODE}
*> When MOC3 is exported, a model with the blink motion enabled gets the EyeBlink group written into model3.json, so the runtime can blink automatically right away. For more settings see [[eye-blink-settings]].
@@ t-mouth-aiueo | Learn "AIUEO" Mouth Movements in 20 Minutes
Combine the two parameters Mouth Open and Mouth Form to make the five vowel shapes あいうえお. These two parameters and their keyforms are already generated at import; this lesson walks you through checking the five shapes and learning where and how to tune and fix them.
## Prepare the material
- Draw the mouth layer at its widest open state with a clear outline; name the layer for example `mouth` / `嘴巴`.
- The mouth interior can be drawn as one piece, or split into `tooth_t` (upper teeth), `tooth_b` (lower teeth) and `tongue`, which are automatically kept shown only inside the mouth.
- If you care about the closed-mouth shape, draw a separate `mouth_close` / `闭嘴`; otherwise the program generates the closed mouth.
## How the two parameters combine
| Parameter | ID | Range | Effect |
|---|---|---|---|
| Mouth Open | `ParamMouthOpenY` | 0…1 | 0 closed, 1 widest open |
| Mouth Form | `ParamMouthForm` | -1…1 | Negative: corners down and narrower; positive: corners up and wider |
"あ" is a wide opening; "い" a small opening, widened; "う" a small opening, narrowed; "え" a medium opening, slightly wider; "お" a medium opening, narrowed.
## Check and adjust
1. After importing the PSD ({key:OPEN_PSD}), confirm the **Tags** of the mouth, teeth and tongue in the **Layers** panel.
2. In the **Parameters** panel, set **Mouth Open** and **Mouth Form** together and check the five shapes one by one against the table above.
3. The closed-mouth line shape is tuned on the **Mouth** component card: turn on **Generate lip outlines**, click **Mouth settings** and choose the **Initial mouth shape** (**Flat**, **Smile**, **W shape** or a custom curve), then set the **Thickness** and **Sample edge color**.
4. The mouth shape amplitude is tuned in the **Values** of the **Mouth** component card, with items such as **Smile corners**, **Smile widen**, **Closed narrow** and **Lip seam**.
{tutorial=PARAMETERS}
## Fix the mouth shapes by hand
1. In the Hierarchy, select the mouth ArtMesh and move the two parameters to the key to fix (right-click on the key marker to snap).
2. Switch to **Deform** mode ({key:MODE_DEFORM}) and drag the control points, or adjust with the brush {key:TOOL_BRUSH} and smooth {key:TOOL_SMOOTH}; turning on the brush's "Connected Only" lets you brush just the upper or the lower lip.
3. For more in-between mouth shapes, click the parameter name to open **Parameter Properties**, click empty space on the **Key points** axis to add a key, and then deform on the new key.
4. Switch to **Preview** ({key:MODE_PREVIEW}) and drag the two parameters continuously to see whether the transitions between values look natural.
{tutorial=DEFORM_MODE}
*> On export `ParamMouthOpenY` is written into the LipSync group of model3.json, so the runtime can use it directly for lip sync.
@@ t-figure | Learn Live2D with Diagrams
New to Live2D? Spend a few minutes getting to know what a model is made of. After you import a PSD, PSD2Live generates all of these structures automatically; once you understand them, you can read what is in the Hierarchy and the Parameters panel and know where to start when you want to change something.
{path=BEGINNER}
## ArtMesh: a picture that can deform
Each layer of the PSD becomes an ArtMesh after import: a texture plus a triangle mesh laid over it. When the mesh vertices move, the texture bends and stretches with them. The density and shape of the mesh are changed in **Edit** mode, and the deformation is made in **Deform** mode.
*> An ArtMesh is the same as Cubism's ArtMesh.
## Deformer: a frame that wraps ArtMeshes
A deformer is like a frame that holds ArtMeshes or other deformers inside and moves them together. A **warp deformer** bends its contents as a whole with a set of control points (corresponding to Cubism's Warp Deformer), and a **rotation deformer** turns its contents around a point. Deformers can be nested layer upon layer, and the parent's motion is passed to the child: when the head turns, the eyes and hair follow. You see the nesting in the **Hierarchy**, while what covers what is decided separately by the draw order.
{tutorial=HIERARCHY}
## Parameters and keyforms: shapes that change with a slider
A parameter is a slider, such as **Angle X** or **Left Eye Open**. The shape recorded at a certain parameter value is called a keyform, and the markers beside the parameter are "keys"; when the slider sits between two keys, the shape is interpolated automatically. Two parameters can also form a two-dimensional link that controls the combined poses of four corners at once, for example a diagonal head turn.
## Animation and physics: they only push parameters
Animation is parameters changing over time; physics computes sway from input parameters such as the head and body and outputs it to parameters such as "hair sway". Neither changes shapes directly; they only push parameters, and the shapes are decided by the keyforms.
*> PSD2Live also has helpers such as the skeleton, deform paths and glue, and on export they are all baked into the standard structures above. For details see [[concept-of-artmesh]], [[deformer]] and [[parameter]].
@@ t-basic | Basic Tutorials
@@ t-psd | 1. Illustration Processing
Prepare the illustration as a layered PSD that can move. PSD2Live recognizes parts and sides by layer name, so splitting layers and naming them well in this step makes the automatic generation afterwards much more accurate.
## File requirements
- A layered PSD in RGB, 8 bits, with a transparent background.
- Rasterize text, vectors, layer styles and adjustment effects first, or merge them into the corresponding pixel layer.
- Keep the body basically upright; the head may tilt slightly.
## Layering points
1. Separate front hair and back hair; draw more in covered areas so no bare spots show when it moves.
2. Eye white, pupil and upper eyelash each get a layer; draw the pupil complete, including the part hidden by the eyelid.
3. Draw the mouth at its widest open; the interior can be drawn as one piece, or split into upper teeth, lower teeth and tongue. If you need a specific closed-mouth shape, draw a separate closed-mouth layer.
4. Split left and right parts into layers, with the side in the name. Left and right refer to the character itself; the character's left side is usually on the right of the picture.
5. Body parts such as the neck, top, arms and legs each get their own layer, with overlap left at the joins.
## Naming
Part names in English, Chinese, Japanese or Korean are all recognized, for example `front hair`, `前发`, `eyelash-l`, `左睫毛`. Layers that are not recognized are not lost; after import, add the tags in the **Layers** panel. For the full name table see [[precautions-for-psd-data]].
{tutorial=BASIC}
*> Left and right drawn on the same layer? After import you can split them with **Model → Split Layers by Mesh…**, see [[split-by-mesh]]; when a layer must pass through another part, see [[depth-split]].
@@ t-import | 2. Preparing to Move the Illustration
Load the PSD into PSD2Live to get a model that can move. The meshes, deformers, standard parameters, motions and physics are generated in one go at import, and what remains for you is to check and fix.
## Import
1. Choose **File → New → New project from PSD…** ({key:OPEN_PSD}), or drag the PSD into the window.
2. In **Quick setup** on the start screen, choose **Minimal**, **Default** or **Complete**; Default is recommended the first time.
3. **Split layers by mesh** lists the layers that contain several separate pieces; tick the ones you want to split.
4. After you confirm, the model is generated and you enter the editing workspace. Hair and clothing simulations are baked one after another in the background, with progress shown in the status bar, and you can keep working meanwhile.
{tutorial=BASIC}
## Check the generated result
1. In the **Layers** panel, check each layer's **Tags** (part and side) and **Variant**; after changing a tag, regenerate to apply it.
2. In the **Hierarchy**, see which deformers and ArtMeshes were generated.
3. After selecting an ArtMesh, open the **Mesh** panel, where you can adjust **Outer Margin**, **Interior Density** and so on; you can also enter **Edit** mode ({key:MODE_EDIT}) to change the mesh by hand.
4. Switch to **Preview** ({key:MODE_PREVIEW}) and drag the parameters to see the overall motion.
5. Use **File → Save project** ({key:SAVE_PROJECT}) to save a `.psd2live` project.
{tutorial=HIERARCHY}
*> Already have a Cubism model? Open it with **File → New → New project from CMO3…**. What exactly gets generated is decided by [[model-presets]].
@@ t-expression | 3. Adding Facial Expressions
Make the eyes, brows and mouth move. The standard parameters of the facial features are already bound at import; in this lesson you first confirm them one by one, and then learn to adjust the amplitude, edit the keyforms, and use variants for expression parts such as blushes and tears.
## Confirm the facial feature parameters
1. Open the **Parameters** panel and drag in turn **Left Eye Open** / **Right Eye Open**, **EyeBall X** / **EyeBall Y**, **Left Brow Y** / **Right Brow Y**, **Mouth Open** and **Mouth Form**.
2. A parameter that does nothing? The analysis warns that it is not bound to any ArtMesh, most likely because the eye or mouth tag is missing; go back to the **Layers** panel and add it.
3. If the overall amplitude is wrong, adjust it in the **Values** of the **Eyes**, **Brows** and **Mouth** component cards, for example **Blink sag** and **Gaze across**.
## Edit keyforms
1. In the Hierarchy, select the ArtMesh or deformer to change.
2. In the Parameters panel, move the parameter to the key to edit (right-click on the key marker to snap).
3. Switch to **Deform** mode ({key:MODE_DEFORM}) and drag the control points, or use the brush {key:TOOL_BRUSH} and smooth {key:TOOL_SMOOTH}; hold {kbd:Alt} and right-drag to adjust the brush radius and hardness on the spot.
4. For left-right symmetric facial features, write to the other side with the canvas right-click menu **Mirror keyform**, or press {key:MIRROR_KEYFORM} to mirror to the symmetric key.
5. If you need more keys, click the parameter name to open **Parameter Properties** and click on the **Key points** axis to add a key.
{tutorial=DEFORM_MODE}
## Use variants for expression parts
For on/off parts such as blushes and tears, set **Toggle Variant** in the **Layers** panel; for several mutually exclusive expressions, set **Switch Variant**, which share one parameter and each use a different ID.
{tutorial=VARIANTS}
@@ t-deformer | 4. Adding Body Movement
Make the body turn, lean and breathe. The body deformers and body parameters are already generated, so check them first; add deformers yourself only when you need extra control.
## Check the body parameters
1. Drag **Body Angle X**, **Body Angle Y**, **Body Angle Z**, **Body lean** and **Breath** in the Parameters panel.
2. If the overall amplitude is too large or too small, adjust **Body range** on the **Body pose** component card; details are in its **Values** (body X, body Y, lean, size change, depth and perspective), and body Z and breathing are on the **Breath** component card.
## Add a deformer yourself
1. In the Hierarchy, select the ArtMesh or deformer to wrap.
2. In **Object** mode ({key:MODE_SELECT}), press {key:TOOL_CREATE_WARP} to create a warp deformer or {key:TOOL_CREATE_ROTATION} to create a rotation deformer; you can also right-click in the Hierarchy to add a parent / child deformer.
3. A placement preview appears on the canvas, where the placement panel at the bottom left lets you check the name, relation and divisions; press {key:FINISH_PATH} to confirm, {key:CANCEL} to cancel.
4. Move the parameter to the key to edit, switch to **Deform** mode and adjust the deformer's shape; between keys it is interpolated automatically.
{tutorial=CREATE_DEFORMER}
## Parent-child relationships
The parent deformer's motion is passed to the child, and dragging a node in the Hierarchy changes its parent. The draw order is independent of the parent-child relationship, and occlusion is adjusted separately in the Inspector. For details see [[setting-of-parent-child-relation]].
## Pose arms and legs with the skeleton
Limbs are better done with the skeleton: build the bone chain, bind the meshes, pose with FK/IK, and on export it is baked into parameters, deformers and keyforms; see [[skeleton]].
{tutorial=SKELETON}
@@ t-xy | 5. Adding Facial XY Movement
Make the face turn left and right (Angle X) and nod up and down (Angle Y). PSD2Live generates nine poses for the head from combinations of end and middle points, and your work is mostly to check the diagonals and fix whatever looks unnatural.
## Check the head turn
1. In the Parameters panel, drag **Angle X** (-45 to 45) and **Angle Y** (-30 to 30), and also pull both to their end points at once to see the diagonal poses.
2. The overall head amplitude is tuned with **Head range** on the **Head turn** component card.
3. Details are tuned in the **Values** of the **Head turn** component card, for example **Face turn**, **Face tilt**, **Corner fix** and **Shell turn**; how the eyes change with the head turn is in the **Eyes** group (**Far eye narrow**, **Near eye widen**).
## Fix the keyforms
1. In the Hierarchy, select the object to fix and move **Angle X** / **Angle Y** to the key to edit.
2. Switch to **Deform** mode ({key:MODE_DEFORM}) to adjust. When the target is a warp deformer, use {key:PICK_OPTION_1} / {key:PICK_OPTION_2} to switch between the **Vertices** and **Bezier** levels: Bezier suits overall bending, and Vertices suits local fine touch-ups.
3. After finishing one side, press {key:MIRROR_KEYFORM} to mirror the current key to the symmetric key (such as Angle X 30 → −30). Keyforms on the parent must be mirrored separately.
4. In **Preview**, turn on **Tracking** and let the mouse take the gaze and head turn around a full circle to check the in-between values.
{tutorial=DEFORM_MODE}
!> The nine poses are estimated from the head tilt in the original art. Wide hair strands or extreme angles may still need manual correction.
@@ t-animator | 6. Adding Motion
Make the model move. You do not need to open a separate animation project: idle, blink, nod and other preset motions come with the model when it is generated and can be tuned directly, and for motions of your own, make them in the **Animation Editor**.
## Adjust the preset motions
1. Switch to the **Animation** workspace and open the **Animation** panel.
2. Pick one under **Built-in Motions** and play it. Each preset has its own knobs, for example the idle's **Sway**, **Breath**, **Head**, **Speed** and **Blink**.
3. The knob values are saved with the project, and both preview and export use them.
## Make a custom motion
1. In the Animation panel click **New Motion** and choose **Blank Motion** or **Copy From Preset**.
2. In the **Animation Editor** click **Track** to add parameter tracks, and double-click a track to add a keyframe.
3. With **Auto Key** ({key:MOTION_TOGGLE_AUTO_KEY}) on, dragging a parameter at the playhead or posing on the canvas keys automatically; in manual mode, set the pose first and then write it with **Key Pose** ({key:MOTION_KEY_POSE}).
4. Use the **Dopesheet** to arrange timing and **Curves** to tune easing; keyframes are Bezier-eased by default.
5. {key:MOTION_PLAY_PAUSE} plays to check; toggle **Loop** in the Animation Editor, and set the fade-in and fade-out times in the Animation panel.
{tutorial=ANIMATION}
*> When CMO3 is exported, the motions are saved as `.can3` files of the same name, which you can keep editing in Cubism Animator; when MOC3 is exported they are written as motion3.json.
@@ t-template | Easy Modeling with the Template Function
@@ t-controller | Parameter Controller
@@ t-controller-settings | 1. Controller Settings
@@ t-target-tracking-settings | 2. Target Tracking Settings
@@ t-animation-using-controller | 3. Creating Animation Using the Controller
@@ t-parameter-controller-tips | Parameter Controller Tips
@@ t-blendmode-offscreendrawing | Enhancing Expression with Blend Modes and Offscreen Drawing
@@ t-embed | Tutorials for Embedding
@@ t-preparing-the-model | 1. Preparing the Model
Confirm that the model can be handed over to an application or streaming software. Runtimes drive the model by parameter ID, and the parameters PSD2Live generates already use the Cubism standard IDs, so most of the time you only need to check them once.
## Checklist
1. In the Parameters panel, confirm the standard parameters are all there, such as `ParamAngleX`, `ParamEyeLOpen`, `ParamMouthOpenY` and `ParamBreath`; click a parameter name to see its ID and range in **Parameter Properties**.
2. Parameters you create yourself should also use unique IDs, with a sensible range and default.
3. Blink and lip sync: when the blink motion is enabled, the export writes the blink parameters into the EyeBlink group of model3.json; `ParamMouthOpenY` is written into the LipSync group.
4. Open the **Texture** workspace and check the number of pages and the size of the texture atlas; set the page size and maximum number of pages at the top right.
5. In **Preview**, switch **Preview runtime** to **Cubism SDK**, play it once with the official runtime, and confirm it matches the editing canvas.
{tutorial=PARAMETERS}
For an overview of parameter IDs see [[standard-parameter-list]], and for the texture atlas see [[texture-atlas-edit]].
@@ t-physical-calculation-settings | 2. Physics Settings
Make hair and accessories sway with the head and body at runtime. Physics groups are generated automatically from the layers; in the panel you can see the inputs and outputs and adjust by dragging the pendulums right on the canvas.
## View and adjust physics groups
1. Switch to the **Physics** workspace and open the **Physics** panel. The **Physics groups** list holds groups from sources such as preset, skeleton, swing, custom and simulation.
2. Select a group and look at its **Inputs** (such as Angle X, Body Angle X) and **Outputs** (such as **Front Hair Sway**). Missing parameters, feedback loops and output conflicts are shown right on the panel.
3. Drag a pendulum ring on the canvas to change the length; drag empty space on the canvas to pull the model and watch the sway.
4. Look at the output's maximum and range, and click **Fit scale to max** to calibrate, so it does not sit at the parameter's end point for long.
{tutorial=PHYSICS}
## Create a physics group
- **New → Blank pendulum**: set up inputs, outputs and pendulums from scratch.
- **Swing for the selection…**: generates the swing shapes and the matching physics together for the selected hair or accessory.
- **Import physics3.json…**: loads an existing Cubism physics setup.
- **Preset**: applies a built-in setup, such as head and body sway.
## Hair and clothing simulation
For cloth-like hair and clothing, use simulation and then bake it into parameters and pendulums; see [[simulation]].
{tutorial=SIMULATION}
*> When exporting MOC3, tick **Include physics3.json**, or the physics is not written into the deliverable files.
@@ t-prepare-motions-and-expressions | 3. Preparing Motions and Expressions
Prepare the motions and expressions the application will call. Motions are made in the project and written as motion3.json automatically on export; expressions are done with variant parameters or short motions.
## Motions
1. In the **Animation** panel, confirm the built-in motions to export are enabled and their knobs are tuned.
2. For a whole group of motions you do not need, turn it off in **Motions** in **Model presets**, and it is removed from the Animation panel, the preview and the export alike.
3. Make custom motions in the **Animation Editor** and set **Loop**; set fade-in and fade-out in the Animation panel.
{tutorial=ANIMATION}
## Expressions
PSD2Live does not export `.exp3.json` expression files. To switch expressions, build a parameter with **Toggle Variant** / **Switch Variant** in the **Layers** panel, or make a short motion that changes only the facial feature parameters, and call it in the application.
{tutorial=VARIANTS}
@@ t-exporting-data-for-embedding | 4. Exporting Data for Embedding
Export all the files the runtime needs. The model, motions and physics are exported together in one window, and model3.json is generated automatically.
## Export MOC3
1. Choose **File → Export Live2D model…** ({key:GENERATE}) to open the **Export Live2D model** window.
2. Under **Formats:** tick moc3; to keep editing in Cubism Editor, tick cmo3 as well.
3. In **SDK target** choose the target runtime version; when a feature in use is newer than the target version, the window lists the parts that will be downgraded.
4. In **MOC3 bake options** confirm **Include physics3.json**, **Include cdi3.json** and so on.
5. Pick the **Output folder** and click **Generate & Export**. When done, an "Export complete" window appears, from which you can open the folder directly.
{tutorial=BASIC}
## What gets delivered
| File | Contents |
|---|---|
| `.moc3` | Model data |
| model3.json | The settings file that ties the other files together |
| Textures | Texture atlas images |
| physics3.json | Physics (when ticked) |
| cdi3.json | Display names of parameters and parts (when ticked) |
| motion3.json | Each motion |
!> Hand over the whole folder when delivering; do not copy only the moc3.
Other export targets (VTube Studio, PSD2Live runtime, web player and so on) are under **File → Export as**, see [[other-formats]]; for version selection see [[target-version-selection]].
@@ t-tips | Tips and Feature Introductions
@@ t-motion-hint | Tips for Improving Motion Quality
Whether a motion looks good often depends on rhythm and layering, not on how many keyframes there are. All of the general tips below can be used right away in PSD2Live's Animation Editor.
## Tips
- **Settle the rhythm before the details**: first arrange the timing of the key poses in the **Dopesheet**, then tune the easing in the **Curves** view.
- **Ease in and out**: keyframes are Bezier-eased by default; do not make the start and end constant speed, and use the curve handles to make the beginning and the end slow.
- **Stagger the timing**: do not start the head, body and gaze on the same frame; let the body or gaze start a little earlier or later and the motion gets more layers.
- **Leave follow-through to physics**: let physics handle the sway of hair and accessories instead of doing it frame by frame; turn on physics in the preview to check the effect.
- **Keep stillness alive**: blinking and light breathing keep pauses from looking stiff, and you can use the idle motion's **Blink** and **Breath** knobs directly.
- **Do not stop at the end points**: a parameter that stays at an end of its range for a long time looks dead; if needed, widen the parameter range or adjust the physics output scale.
*> During playback all canvases share the same pose, so you can tune in the Animation Editor while watching in the preview canvas.
{tutorial=ANIMATION}
Related: [[grapheditor]] · [[timeline-basic-operation-dopesheet]] · [[physics-operation]]
@@ t-function | Introduction to Useful Features
The biggest time saver in PSD2Live is "generate automatically first, then edit on the generated result, and the edits are not lost when you regenerate". The features worth knowing are listed below by purpose; click the links for details.
{tutorial=LIVE2D_BRIDGE}
## Generation and regeneration
- **Automatic generation**: at PSD import, meshes, deformers, parameters, motions and physics are generated according to the model presets; see [[model-presets]].
- **Update Generated Rig**: **Model → Update Generated Rig** regenerates with a newer version of the generators and then merges your edits onto it; see [[update-generated-rig]] and [[generated-overrides]].
- **Layout project**: arrange loose material first and then generate; images added later can be regenerated; see [[layout-project]].
## Editing aids
- **Mirror keyform**: after finishing one side, mirror it to the symmetric key or the opposite object; see [[motion-mirroring]].
- **Skeleton**: pose with FK/IK, and on export it is baked into parameters and keyforms; see [[skeleton]].
- **Glue and deform paths**: make adjacent meshes move together, or bend a mesh along a path; see [[glue]] and [[deformpath]].
- **Swing generation**: generate swing shapes and physics for hair and accessories in one click; see [[auto-generation-of-sway-motion]].
- **Hair and clothing simulation**: simulate and then bake into parameters and pendulums; see [[simulation]].
## Material and textures
- **Split by mesh / front-back layering**: split parts drawn on the same layer; see [[split-by-mesh]] and [[depth-split]].
- **Paint mode**: edit layer pixels directly in the project; see [[paint-mode]].
- **Texture upscale**: **Model → Texture Upscale…** ({key:TEXTURE_UPSCALE}), for when the original art resolution is low; see [[texture-upscale]].
## Project management
- **Branching history**: the history is kept as a tree, and you can return to any version and continue from it; see [[history-palette]].
- **Parameter snapshots**: save the current parameter combination in the Parameters panel and load it back any time.
- **Many export options**: **File → Export as** exports runtimes, images and video; see [[other-formats]].
{tutorial=PROJECT_HISTORY}
@@ t-model-introduction | Try Making a Model Introduction Video
Make a showcase motion for the model and export it as a video. You do not need to build a scene; just export a motion video straight from the project.
## Make a showcase motion
1. In the **Animation** panel click **New Motion** and choose **Blank Motion** or **Copy From Preset**.
2. In the **Animation Editor**, arrange a head turn, blink, mouth shapes and body motion in turn: turn on **Auto Key** ({key:MOTION_TOGGLE_AUTO_KEY}), move the playhead and drag parameters to key them.
3. Tune the easing in the **Curves** view, and press {key:MOTION_PLAY_PAUSE} to check in the preview canvas.
{tutorial=ANIMATION}
## Export the video
1. Choose **File → Export as**, and under **Images and video** pick **MP4 video…**; for a transparent background pick **WebM video (with alpha)…** or **QuickTime ProRes 4444…**.
2. In the settings choose the **Motion**, and set the **FPS**, **Size**, **Background** and **Physics** as needed.
3. Choose the output folder and export.
!> Video is encoded through ffmpeg. MP4 has no transparency channel and is composited onto the background color.
*> One motion is exported at a time. If you need a background or subtitles, export a video with transparency and composite it in video editing software. For more formats see [[exporting-images]].
