# Illustrated simulation tutorial: natural swing for hair and clothing

[Documentation](../../README.md) · [中文](../../zh/guide/SIMULATION_TUTORIAL.md) · [Simulation and baking](SIMULATION.md) · [Canvas editing](CANVAS_EDITOR.md) · [User quick reference](USER_GUIDE.md)

Simulation gives hair, hems and loose clothing follow-through, lag and bounce. This tutorial first uses a before-and-after comparison of the front hair to show what to aim for, then walks through pin weights and baking. The screenshots show the Chinese UI.

## The problem and the goal

The automatically generated pinned range does not suit every artwork. In this example, the shape and position of the strands at the sides of the face need adjusting as the front hair swings: the roots keep following the head, and the free area below is adjusted so the front hair comes closer to the intended outline.

![Front hair simulation before and after: before on the left, after on the right](../../imgs/simulation-tutorial/preview-comparison.webp)

*The left side is taken from the overall preview before the change, the right side from a close-up preview after it; both are cropped and joined into one head comparison. The original content of both screenshots is kept, to compare the strands at the sides of the face. The swing in motion still has to be checked by changing parameters or playing motions.*

The approach: **check the pins → change the pinned range with the weight brush → watch the reference simulation → bake again → check the export**. When changing pins, keep the connection at the roots, and use a gradient to control which areas go with the head and which may swing.

## Before you start

**Reference sim** is for tuning live, and **Export** is for seeing how the bake behaves. For these motions to appear in the exported model, you must bake. Open a model whose meshes and rig are already generated, then follow the steps below.

## 1. Turn on simulation in the model presets

Open **Model presets → Physics & Simulation** on the **World** page of the **Modifiers** panel. Front hair and back hair each have a dropdown, so their motion can be set separately.

![The Physics & Simulation group of the model presets, and the hair motion dropdown](../../imgs/simulation-tutorial/model-presets.webp)

*This screenshot shows where the dropdown is; the red box marks **Classic sway**. To follow this tutorial, choose **Simulate** just above it.*

| Option | Use |
| --- | --- |
| Simulate | Creates a hair body and pin weights and generates a bake, which you can refine afterwards |
| Classic sway | Uses the existing hair sway and pendulum, without the hair simulation body this tutorial uses |
| Off | Turns that hair's sway off |

First set **Front hair** to **Simulate** and wait for generation to finish. If the back hair should take part too, set **Back hair** to **Simulate** as well. To work on only some layers, select them first, then turn on **Selected only**.

The preset generates pins at the roots of the strands automatically. Look at the default result first, then decide whether to adjust it by hand.

### Clothing can be turned on here too

Check **Clothing** and the program generates simulations by part, letting only the loose areas move while tight areas keep following the rig.

![The top and skirt looseness and the tight garments listed under the clothing preset](../../imgs/simulation-tutorial/clothing-report.webp)

*Below the clothing row are the simulated parts and their looseness; tight garments are gathered in the "Worn tight, not simulated" row.*

The percentages here are the program's report on how loose the artwork is, to show which parts the preset handled. The actual motion still has to be checked through the pins, the material and the preview.

## 2. Select the body and meshes to adjust

Open the **Simulation** panel and select **Front hair (preset)** in the **Bodies** list. The checkbox at the left of the list controls whether the body is enabled.

While tuning, turn on the preview switch at the top of the panel so the label reads **Reference sim**. The editor's live simulation then runs, and you can turn the head and tilt the body in the Parameters panel to watch how the strands follow.

To change the pins, select the front hair mesh, switch the mode at the top left of the canvas to **Simulate**, and choose **Weight paint → Pin** in the left toolbar. You can also open the simulation card in the front hair mesh's stack in the **Modifiers** panel, and click **Paint** on the Pin row under **Vertex groups** to enter the tool.

![Simulate mode with the pin group selected: the front hair roots are red, the tips blue](../../imgs/simulation-tutorial/pin-weights.webp)

*The front hair mesh is selected in the picture, and the colours show its current pin weights. The other parts are dimmed by the edit display.*

| Colour | Pin weight | Meaning |
| --- | --- | --- |
| Red | Near 1 | Follows the existing rig firmly; for roots and joins |
| Green / yellow | In between | Partly pinned, for transitions |
| Blue | Near 0 | Takes part in the simulation more freely; for tips and hems |

Hair is usually pinned at the roots and freed gradually toward the tips; a skirt is usually pinned at the waist and freed toward the hem. First check that the joins hold, then adjust the areas that may swing.

## 3. Adjust the pinned range with the weight brush

The tool options bar at the bottom left of the canvas shows the weight brush settings (right-clicking the canvas opens the same settings). First choose how to change the weights, then adjust **Radius, Strength, Hardness**.

![The weight brush's right-click settings: radius, hardness, strength, and Add, Subtract, Set and Smooth](../../imgs/simulation-tutorial/weight-brush.webp)

*The screenshot has **Subtract** selected, which weakens pinning and widens the area that can swing.*

| Mode | Suited to |
| --- | --- |
| Add | Holding roots or joins more firmly |
| Subtract | Letting over-pinned areas swing more freely |
| Set | Pulling weights gradually toward the current strength |
| Smooth | Softening weight changes between neighbouring vertices |

For example, when the hair is pinned too much and the strands hardly swing, brush lightly with **Subtract** below the roots to free the transition area step by step, keeping the roots that really join the head. If the joins start to slide too, use **Add** to restore the root weights.

Use a small radius to trim edges and a low hardness for soft transitions. Change a little at a time and look at the colours before going on. **Smooth** tidies abrupt changes; for a transition along one direction, **Weight gradient** works too.

## 4. Check the weight distribution and the reference simulation

Below is one adjusted distribution: the top still holds a high pin weight, which falls off toward the sides and the tips.

![Adjusted front hair pin weights, with a gradient between the roots and the free area](../../imgs/simulation-tutorial/weights-adjusted.webp)

*What matters is stable joins, a continuous transition and freedom at the tips. Adjust the pinned range to your own artwork.*

Go back to **Preview** mode and keep **Reference sim** on. Turn the head slowly, then quickly and stop, watching the follow-through, the fling and the swing back. If the hair barely moves as a whole, check whether it is pinned too much; if the roots drift, check whether the joins have enough pin weight.

You can also adjust the **Material** in the simulation card (in the front hair mesh's stack in the **Modifiers** panel, or by clicking its row in the **Simulations** section of the **World** page):

| Item | Purpose |
| --- | --- |
| Bend | How easily the strands bend |
| Damping | How fast motion dies down; higher usually stops sooner |
| Shape | How much it springs back toward the drawn shape |
| Stretch | Keeps the strand length; for hair it should usually be near 1 |

Start from the **Hair** material preset and change one item at a time. The other material items, weight groups and glue roles are described in [Simulation and baking](SIMULATION.md).

**Auto pin weights** in the model presets recomputes the weights and bakes again. Once you have fixed the pins by hand and want to keep that distribution, keep using the simulation card's bake buttons.

## 5. Bake again after the changes

After weights or material change, the existing bake becomes **Stale**. In the front hair mesh's simulation card, expand **Bake** and click **Bake again**; when there is no result yet, the button reads **Bake**.

![The front hair body is stale; the red box marks the Bake again button (earlier interface)](../../imgs/simulation-tutorial/rebake.webp)

*The button in the red box bakes only the current body. When several bodies need updating, use **Bake all** at the top of the panel.*

For the first time, keep the defaults of **Modes 2, Keys 5, Exaggeration ×1.3**, bake once and look at the result. Modes decide how many parameters express the overall swing and the extra deformation, Keys affect how finely the shape is sampled, and Exaggeration controls the swing after baking.

**Stale** means the current settings differ from the old bake, and export still uses the old result. Only after baking again do the weight and material changes show in the new export.

## 6. Wait for the bake to finish

While baking, the bottom status bar shows the progress. The time depends on how complex the meshes and bodies are; wait for it to finish before checking the result.

![During a simulation bake, a red arrow points to the progress bar in the bottom status bar](../../imgs/simulation-tutorial/bake-progress.webp)

*The arrow points to this bake's progress. When it finishes, the freshly baked body shows a green dot in the list.*

To stop, use the cancel button beside the progress. **Bake automatically** is off by default; when you need to keep fixing weights by hand, make all the changes first and bake once at the end.

## 7. Switch back to Export and save

Turn off **Reference sim** in the simulation card's **Compare** fold to go back to **Export**. The canvas now plays the baked keyforms and pendulum, to check how the exported model behaves.

Check head turns, tilts, stopping after fast motion, and the body moving up and down. Watch whether the roots stay stable, the tips swing back naturally and the clothing keeps its original shape. To compare, switch between **Reference sim** and **Export** with the same motion; or expand the body's **Compare** section to draw the reference simulation over the export (**Overlay**) or play the two on two canvases **Side by side**, pick a motion and **Play** it so both start from rest together, or **Check** it to compare swing, lag and settling on that motion.

Once you are happy with the result, save the `.psd2live` project, then export the model. If you change weights, meshes or material later, remember to check the bake state and update it.

## Troubleshooting

| Symptom | Check first |
| --- | --- |
| No front hair body in the World page's simulation list | Whether the front hair dropdown is set to **Simulate** and whether the artwork is recognized as front hair; you can also select the target meshes and create one by hand with **Simulation · Hair** in the Modifiers panel's **Add** menu |
| Weight colours do not show | Whether the target mesh is selected and the canvas is in **Simulate** mode with the **Pin** group |
| Hair roots come loose, or it barely swings | Whether there are too few or too many pins; hold the joins first, then free the tips gradually |
| The reference simulation moves but the export does not update | Whether it is unbaked or stale; bake or bake again |
| Some clothing parts are not simulated | Look at the clothing looseness report and the "Worn tight, not simulated" list to see whether the preset's recognition matches the artwork |
| The bake differs from the reference simulation | A bake is a fit of the live simulation; look at the quality notes, adjust Modes or Keys as needed and bake again, then check the improvement with real motion |
