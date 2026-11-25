<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/SIMULATION_TUTORIAL.md) · **English** · [한국어](../../ko/guide/SIMULATION_TUTORIAL.md) · [日本語](../../ja/guide/SIMULATION_TUTORIAL.md)

# Simulation tutorial: natural motion for hair and clothing

[Documentation](../README.md) · [Simulation and baking](SIMULATION.md) · [Canvas editing](CANVAS_EDITOR.md) · [Quick reference](USER_GUIDE.md)

Hair, skirt hems, and loose clothing can pick up follow-through, lag, and spring-back from simulation. This tutorial first uses a before-and-after of the front hair to show what to aim for, then walks through pin weights and baking.

## See the problem and the goal

The pin range generated automatically may not suit every piece of art. This example needs the shape and position of the face-side strands adjusted while the front hair swings: keep the roots following the head, and adjust the region below that is free to move, so the front hair sits closer to the outline you want.

![Front-hair simulation before and after: before on the left, after on the right](../../imgs/simulation-tutorial/preview-comparison.webp)

*The left side is taken from the overall preview before the edit, and the right side from a close preview after it. They are cropped and joined into one head comparison. Both screenshots keep their original content, so the face-side strands can be compared. The live swing still needs a check by changing parameters or playing a motion.*

The adjustment is: **check the pins → change the pin range with the weight brush → watch the reference simulation → bake again → check the export**. While editing pins, keep the root connection, and use a gradient to decide which regions follow the head and which regions can swing.

## Before you start

The **reference simulation** is for live adjustment. The **export** is for seeing how the bake behaves. Those motions appear in the exported model only after a bake. Open a model that already has meshes and a rig, then follow the steps below.

## 1. Turn simulation on in the model presets

Open **Model presets → Physics & simulation**. Front hair and back hair each have a dropdown, and each can set its own motion.

![The Physics & simulation group of the model presets, and the hair motion dropdown](../../imgs/simulation-tutorial/model-presets.webp)

*This screenshot shows the dropdown. The red box circles Classic sway. While following this tutorial, choose Simulate, the item above it.*

| Option | Use |
| --- | --- |
| Simulate | Creates the hair body and pin weights, and generates a bake, which can be adjusted further afterward |
| Classic sway | Uses the existing hair sway and pendulum. It does not create the hair simulation body this tutorial uses |
| Off | Turns that hair’s motion off |

Set **Front hair** to **Simulate** first, and wait for generation to finish. When the back hair should take part too, set **Back hair** to **Simulate** as well. To handle only some layers, select them first, then turn on **Selected only**.

The preset generates pins at the roots of the strands automatically. Watch the default result first, then decide whether to adjust it by hand.

### Clothing can be turned on here too

Check **Clothing**. The application generates a simulation by part, lets only the loose regions move, and keeps the tight regions following the rig.

![Tops, skirt looseness, and tight parts listed under the clothing preset](../../imgs/simulation-tutorial/clothing-report.webp)

*Under the clothing row, the parts that take part in the simulation are listed with their looseness. Tight parts are gathered on the row Tight, not simulated.*

The percentages here are the application’s report of how loose the art is, so you can see which parts the preset handled. The actual motion still needs a check through pins, material, and the preview.

## 2. Choose the body and mesh to adjust

Open the **Simulation** panel and select **Front hair (preset)** in the **Bodies** list. The checkbox on the left of a row turns that body on or off.

While debugging, turn on the preview switch at the top of the panel so the label reads **Reference sim**. The editor’s live simulation is running then. Turn the head and tilt the body in the parameter panel, and watch how the strands follow.

To edit the pins, select the matching front-hair mesh, switch the mode at the top left of the canvas to **Simulate**, and on the left toolbar choose **Weight paint → Pin**. You can also expand **Vertex groups** in the Simulation panel and click **Paint** on the pin row to enter that tool.

![The pin weight group selected in Simulate mode: the front-hair roots are red and the tips are blue](../../imgs/simulation-tutorial/pin-weights.webp)

*The mesh selected in the figure is the front hair. The colors show the current pin weights. Other parts going dim is an editing display.*

| Color | Pin weight | Meaning |
| --- | --- | --- |
| Red | Near 1 | Held firmly to the existing rig. Suits roots or joins |
| Green / yellow | In between | Partly pinned, for a transition |
| Blue | Near 0 | Takes part in the simulation more freely. Suits tips or a hem |

Hair is usually pinned at the root and freed toward the tip. A skirt is usually pinned at the waist and freed toward the hem. Check that the joins are steady first, then adjust the region that can swing.

## 3. Adjust the pin range with the weight brush

The tool options bar at the bottom left of the canvas shows the weight-brush settings (right-clicking the canvas opens the same set). Choose how the brush changes weights first, then adjust **radius, strength, and hardness**.

![Weight-brush context settings: radius, hardness, strength, and add, subtract, set, and smooth](../../imgs/simulation-tutorial/weight-brush.webp)

*The screenshot has Subtract selected, which suits weakening the pin and enlarging the region that can swing.*

| Action | The adjustment it suits |
| --- | --- |
| Add | Holds a root or a join more firmly |
| Subtract | Lets a region that is pinned too much swing more freely |
| Set | Pulls the weight gradually toward the current strength |
| Smooth | Softens the weight change between neighbouring vertices |

For example, when the hair is pinned too much and the strands do not swing easily, use **Subtract** and brush lightly below the roots, freeing the transition a little at a time. Keep the roots that really join the head. If a join starts to slide as well, use **Add** to put root weight back.

Use a smaller radius to clean an edge, and a lower hardness for a soft transition. Change a little at a time, look at the color, then continue. Use **Smooth** to even out a jump. When the transition should run in one direction, use the **weight gradient** as well.

## 4. Check the weight distribution and the reference simulation

Below is one distribution after adjustment: the top still keeps a high pin weight, and it falls off toward the sides and the tips.

![Front-hair pin weights after adjustment, with a gradient between the roots and the free region](../../imgs/simulation-tutorial/weights-adjusted.webp)

*What matters is a stable join, a continuous transition, and freedom at the tips. Set the pin range for your own art.*

Go back to **Preview** mode and keep **Reference sim** on. Turn the head slowly, then turn it quickly and stop, and watch follow-through, the throw, and the return separately. If the hair barely moves as a whole, check whether it is pinned too much. If the roots drift, check whether the pins at the joins are enough.

Material can also be adjusted in **Material** on the Simulation panel:

| Item | What the adjustment is for |
| --- | --- |
| Bend | How easily a strand bends |
| Damping | How fast motion dies out. Raising it usually makes the motion stop sooner |
| Shape | How strongly it springs back toward the drawn shape |
| Stretch | Keeps strand length. Hair should usually be near 1 |

Start from the **Hair** material preset, and change one item at a time. Other materials, weight groups, and glue roles are described in [Simulation and baking](SIMULATION.md).

**Auto pin weights** in the model presets recomputes the weights and bakes again. After the pins have been repaired by hand, keep using the bake button on the Simulation panel when that distribution should be kept.

## 5. Bake again after the edit

After weights or material change, an existing bake becomes **Stale**. In the Simulation panel, select the body just edited, expand **Bake**, and click **Bake again**. When there is no result yet, the button reads **Bake**.

![The front-hair body in the Simulation panel is stale, and the red box marks Bake again](../../imgs/simulation-tutorial/rebake.webp)

*The button in the red box bakes only the current body. When several bodies need an update, use Bake all at the top of the panel.*

The first time, the defaults can stay: **2 modes, 5 keys, exaggeration ×1.3**. Finish one bake and look at it. The mode count decides how many parameters express the overall swing and the extra deformation. Keys affect how finely the shape is sampled. Exaggeration controls how far the bake swings.

**Stale** means the current setup differs from the old bake. Export still uses the old result. After baking again, the weight and material edits show up in the new export.

## 6. Wait for the bake to finish

During a bake, the status bar at the bottom shows progress. The time depends on how complex the meshes and the body are. Wait until it finishes, then check the result.

![During a simulation bake, the red arrow points at the progress bar in the bottom status bar](../../imgs/simulation-tutorial/bake-progress.webp)

*The arrow points at this bake’s progress. When it finishes, the body that was just baked shows a green dot in the list.*

To stop, use the cancel button beside the progress. **Bake automatically** is off by default. When weights need a run of hand edits, change them together first, then bake once at the end.

## 7. Switch back to the export and save

Turn off the preview switch at the top of the Simulation panel so the label reads **Export**. The canvas then plays the baked keyforms and pendulum, which is how the exported model behaves.

Check a head turn, a tilt, a stop after a fast move, and the body moving up and down. Watch whether the roots stay steady, whether the tips spring back naturally, and whether the clothing still keeps its drawn shape. To compare, play the same motion and switch between **Reference sim** and **Export**. You can also expand the body’s **Compare** section and lay the reference simulation over the export (**Overlay**), or play them **side by side** on two canvases. Choose a motion and **Play** so both start from rest together, or **Check** to get that motion’s comparison of swing, lag, and settling.

When the result looks right, save the `.psd2live` project, then export the model. If weights, meshes, or material are edited again later, check the bake state and update it.

## Common problems

| What you see | Check first |
| --- | --- |
| The Simulation panel has no front-hair body | Whether the front-hair dropdown is set to Simulate, and whether the art is recognized as front hair. The target meshes can also be selected and created by hand with New → Hair on the Simulation panel |
| Weight colors are not shown | Whether the target mesh is selected, and whether the canvas is in Simulate mode on the Pin weight group |
| The hair roots come loose, or the hair barely swings | Whether there are too few pins or too many. Steady the joins first, then free the tips gradually |
| The reference simulation moves, and the export has not updated | Whether it is unbaked or stale. Bake, or bake again |
| Some clothing parts have no simulation | Look at the clothing looseness report and the Tight, not simulated list, and confirm the preset’s recognition matches the art |
| The bake differs from the reference simulation | A bake is a fit of the live simulation. Read the quality notes, adjust the mode count or the keys as needed, bake again, and check the improvement on real motion |
