<!-- lang: zh en ko ja -->
> [中文](../../zh/guide/DEPTH_SPLIT.md) · **English** · [한국어](../../ko/guide/DEPTH_SPLIT.md) · [日本語](../../ja/guide/DEPTH_SPLIT.md)

# Depth-split tutorial

[Documentation](../README.md) · [Quick reference](USER_GUIDE.md) · [Canvas editing](CANVAS_EDITOR.md)

Different parts of one layer sometimes need to sit in front of and behind another part. **Depth split** quickly copies a front layer and a back layer, sets the occlusion order, and uses Glue so they deform together. You only erase, on the new front layer, the part that should hide behind.

This tutorial walks through the full operation with a collar passing through a neck, then uses hair and a shoulder ornament to show another use. It applies to models generated from a PSD or an image. An imported CMO3 model does not support this yet.

## 1. Find the parts that need front-and-back occlusion

In the figure below, the back of the collar covers the neck. Because the whole collar is in one layer, moving it entirely in front of the neck or entirely behind the neck leaves some of the occlusion wrong.

![Before: the back of the collar opening covers the front of the neck](../../imgs/depth-split/collar-before.webp)

*Before: the collar opening that should sit behind the neck appears in front of the neck.*

We want the back of the collar hidden behind the neck, while the front collar panel still covers the lower edge of the neck:

![Target: the neck sits between the back of the collar opening and the front collar panel](../../imgs/depth-split/collar-target.webp)

*Target: the neck shows through the collar opening, and the front-and-back occlusion looks natural.*

The matching occlusion is:

| Position | Part | Role |
| --- | --- | --- |
| Front | The layer the collar is on · front | Erase the back of the collar opening, and keep only what needs to show in front of the neck |
| Middle | Neck | Covers the back of the collar opening, and is covered by the front collar panel |
| Back | The layer the collar is on · back | Keeps the complete original layer, and supplies the content on the back |

The collar in the screenshot belongs to the whole top's `topwear` layer, so what is actually split is the whole top. You do not have to split the collar out on its own first.

## 2. Select the top and the neck together

On the edit canvas, switch to **Object** mode, select the top layer that holds the collar, then hold **Shift** and add the neck. You can also multi-select in the hierarchy tree.

![The top and the neck selected together, with two selection boxes on the canvas](../../imgs/depth-split/select-layers.webp)

*The two boxes are the top and the neck. The top is the object to split into a front layer and a back layer. The neck is the object sandwiched in the middle.*

You can add several ArtMeshes that need to be sandwiched in the middle, but only one ArtMesh is split at a time. The other selected objects are placed together between the front and back layers, and they keep their draw order relative to each other.

## 3. Right-click the top and run "Depth split"

Keep the multi-selection, right-click the **top** you want to split, and choose **Depth-split "topwear"**. The name in the menu follows your layer name. Before you click, confirm that it shows the object you want to copy.

![The context menu, with "Depth-split topwear" marked by a red box](../../imgs/depth-split/context-menu.webp)

*The menu item in the red box creates the front and back layers directly and enters the front-layer eraser.*

After creation, the original layer is replaced by two new layers, "`topwear · back`" and "`topwear · front`". Both carry the original layer's full pixels. The program arranges their draw order with the neck automatically, copies the mesh and the deformation data, and glues the corresponding vertices of the front and back layers so the front layer follows the back layer's motion. The original layer no longer appears in the layer list. To return to before the split, use undo.

**If only the top is selected:** right-click the top in the hierarchy tree, choose **Depth split…**, select the neck under **Interleaving objects** in the dialog, check the front / middle / back relationship, then click **Create and erase**.

## 4. Erase the back of the collar opening on the front layer

After creation, the program selects the new front layer automatically and switches to **Paint** mode and the **Eraser**. First check that the top of the canvas shows "`topwear · front`".

Zoom in on the collar opening and, along the edge of the neck, erase the collar-opening area on the front layer that should sit behind the neck. The arrow in the figure below marks where to erase.

![Using the eraser on the topwear front layer, with a red arrow pointing at the back of the collar opening that should be erased](../../imgs/depth-split/erase-front.webp)

*What you erase is the back of the collar opening on the front layer. The original back layer keeps those pixels, so once you erase through you can see the neck in the middle.*

Adjust the eraser size. Clear the inside of the region first, then clean the edge with a smaller brush. Keep the front collar panel and anything that needs to sit in front of the neck. You do not have to clear the whole top.

If you erase too much, use **Undo** in the paint session to restore strokes. Other parts in the figure dim while you paint. That is an editing display effect.

## 5. Confirm apply, and check different poses

When the erase is done, click **Confirm apply** at the top of the canvas or in the tool panel. The front layer created by depth split keeps the original mesh and deformation automatically. Applying does not require regenerating the mesh.

![The collar after apply: the neck is visible, and the front collar panel stays in front of the neck](../../imgs/depth-split/collar-result.webp)

*The finished occlusion: the back of the collar opening is behind the neck, and the front collar panel is in front of the neck.*

Then, in the parameter panel, check head turns, looking up and down, and body rotation, and pay particular attention to the collar-opening boundary. If some pose still has the back of the collar opening covering the neck, go back to painting the front layer, keep cleaning the edge with the eraser, and confirm apply again.

The front and back layers deform together, but you still decide which pixels to erase. Finally, save the `.psd2live` project so the split, the texture edits, and the history are kept.

## 6. The same method also handles hair and shoulder ornaments

The shoulder ornament and the hair in the figure below also need part of them in front and part behind. First find the layer that holds the ornament you need to split, and the hair layer that should be sandwiched in the middle.

![Before: the occlusion between the hair and the shoulder ornament needs adjustment](../../imgs/depth-split/shoulder-before.webp)

*First decide which ornaments should show in front of the hair, and which should hide behind the hair.*

Multi-select the layer that holds the ornament and the related hair, then right-click the **layer that holds the ornament** and run depth split. Then, on the new front layer, erase the part of the ornament that the hair should cover.

![Erasing the front layer of the shoulder ornament](../../imgs/depth-split/shoulder-erase.webp)

*Trim, only on the new front layer, the part that needs to hide behind. The back layer keeps the complete content.*

After you confirm apply, check the boundary between the hair and the ornament, and move the related parameters to watch the occlusion in motion.

![After: the hair and the shoulder ornament form interleaved front-and-back occlusion](../../imgs/depth-split/shoulder-result.webp)

*Finished result: the same ornament layer can supply both the front-side content and the back-side content.*

## Common questions

| What you see | What to do |
| --- | --- |
| The quick split menu on the canvas is missing | Switch to object mode and select the mesh to split together with at least one middle object. You can also open the dialog from "Depth split…" in the hierarchy tree |
| The object shown in the menu is wrong | Right-click the object you want to copy. In the collar example that is the top, and it cannot be the neck |
| After creation, the look has not changed yet | The new front layer starts as a complete copy. Use the eraser to remove the part of it that should be occluded |
| A prompt says to apply or discard painting first | Finish the paint session that has not been committed yet, then create the depth split |
| The front view is fine, but turning still interpenetrates | Check several poses of the related parameters, go back to the front layer, erase more, and clean the edge |
