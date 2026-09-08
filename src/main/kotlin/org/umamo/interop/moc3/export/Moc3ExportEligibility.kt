package org.umamo.interop.moc3.export

import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.partByDrawable

/**
 * Which of a rig's objects an export actually writes, and why the rest were left out.
 *
 * @property List<Drawable>          drawables       The drawables that survive, in model order.
 * @property Set<PartId>             parts           The part ids that survive.
 * @property Map<DrawableId, PartId?> partByDrawable Each drawable's owning part, null when unparented.
 * @property Map<PartId, PartId>     partParentById  The org tree's part→parent link, inverted once.
 * @property Map<DrawableId, ExportNoticeReason> droppedDrawables Each omitted drawable and the reason,
 *                                                               for notices.
 */
internal class Moc3ExportEligibility(
	val drawables: List<Drawable>,
	val parts: Set<PartId>,
	val partByDrawable: Map<DrawableId, PartId?>,
	val partParentById: Map<PartId, PartId>,
	val droppedDrawables: Map<DrawableId, ExportNoticeReason>,
)

/**
 * Decides which objects of [puppet] the export can write.
 *
 * Runs BEFORE the index plan, because the plan's indices are the file's addressing scheme: a drawable
 * dropped after the plan was built would leave every later index - and every mask reference into them -
 * naming the wrong object.
 *
 * Must also run AFTER the version strip, on the stripped rig.  No strip currently touches the
 * predicates below, so passing the un-stripped rig would be invisible today - which is exactly why the
 * order is stated rather than left to be rediscovered.
 *
 * Structural drops (sketch subtree, no mesh, no parent-space inverse) are decided before the
 * option-driven hidden drops, so the mask exemption below can never resurrect a drawable the file
 * has no way to write.
 *
 * @param PuppetModel puppet The rig to export, already stripped to the target version.
 * @param CanvasToParentSpace? canvasToParentSpace The unkeyed-drawable space inverse, or null.
 * @param Moc3ExportOptions options What the rigger chose to include; the default is the
 *   options-less behavior (hidden objects carried, guides dropped).
 * @return Moc3ExportEligibility The surviving objects and the drop reasons.
 */
internal fun resolveExportEligibility(
	puppet: PuppetModel,
	canvasToParentSpace: CanvasToParentSpace?,
	options: Moc3ExportOptions = Moc3ExportOptions.Default,
): Moc3ExportEligibility {
	// A SKETCH part is a guide overlay - a scan or a rough the rigger traces over - and the official
	// bake leaves it out of the moc entirely.  The whole subtree goes: a guide's drawables are the
	// thing that would otherwise render in the runtime, sitting on top of the puppet.
	val sketchParts =
		if (options.exportGuideImageParts) {
			emptySet()
		} else {
			partSubtree(puppet, puppet.parts.filter { part -> part.isSketch })
		}
	// A hidden part cascades over its whole subtree, matching the parts panel's eyeball: a visible
	// child of a hidden part does not show, so omitting hidden parts omits the child's contents too.
	val hiddenParts =
		if (options.exportHiddenParts) {
			emptySet()
		} else {
			partSubtree(puppet, puppet.parts.filter { part -> !part.isVisible })
		}
	val exportableParts =
		puppet.parts.mapNotNullTo(LinkedHashSet()) { part ->
			part.id.takeIf { it !in sketchParts && it !in hiddenParts }
		}
	val partByDrawable = puppet.partByDrawable()
	// The org tree's part->parent link, inverted ONCE.  Both of these are asked per object further
	// down, and re-deriving either there turns its pass into a quadratic rescan of the whole tree.
	val partParentById = HashMap<PartId, PartId>()
	for (part in puppet.parts) {
		for (child in part.children) {
			if (child is OrgChild.Part) {
				partParentById[child.id] = part.id
			}
		}
	}
	val dropped = LinkedHashMap<DrawableId, ExportNoticeReason>()
	for (drawable in puppet.drawables) {
		if (partByDrawable[drawable.id] in sketchParts) {
			dropped[drawable.id] = ExportNoticeReason.SketchPartIsNotRuntimeContent
		} else if (drawable.mesh == null) {
			dropped[drawable.id] = ExportNoticeReason.DrawableHasNoMesh
		} else if (drawable.geometryGrid == null && drawable.parentDeformerId != null && canvasToParentSpace == null) {
			// The rest mesh is CANVAS-space while a parented drawable stores parent-local values, and
			// with no grid there are no deltas to recover the parent-local form from.  Inverting the
			// deformer chain needs :render's damped-Newton warp inverse, which :interop cannot reach -
			// so without the injected seam the drawable is dropped rather than written at the wrong
			// scale, which is what a canvas-space value under a warp would be.
			dropped[drawable.id] = ExportNoticeReason.UnkeyedDrawableUnderDeformerHasNoParentGeometry
		} else if (partByDrawable[drawable.id] in hiddenParts) {
			dropped[drawable.id] = ExportNoticeReason.HiddenPartOmittedByExportOption
		} else if (!options.exportHiddenDrawables && !drawable.isVisible) {
			dropped[drawable.id] = ExportNoticeReason.HiddenDrawableOmittedByExportOption
		}
	}
	// A hidden mask is routine in a Cubism rig - the mesh exists to clip, not to draw - so dropping
	// it for being hidden would unclip everything it masks.  A drawable dropped ONLY by the
	// hidden-art-mesh option is therefore kept after all when a surviving drawable masks with it,
	// written with its flag clear exactly as the options-less export writes it.  One pass suffices:
	// a mask's own mask list is not consulted by the runtime's mask render, so keeping it creates no
	// further need.  The exemption deliberately does not extend to hidden-PART drops - those take
	// the part record itself, and a written drawable under an unwritten part is a state the index
	// plan has no shape for - so there the mask reference is filtered with a notice instead (see
	// the mask handling in the art-mesh lowering).
	if (dropped.isNotEmpty()) {
		val masksOfSurvivors = LinkedHashSet<DrawableId>()
		for (drawable in puppet.drawables) {
			if (drawable.id !in dropped) {
				masksOfSurvivors.addAll(drawable.maskedBy)
			}
		}
		for (maskId in masksOfSurvivors) {
			if (dropped[maskId] == ExportNoticeReason.HiddenDrawableOmittedByExportOption) {
				dropped.remove(maskId)
			}
		}
	}
	return Moc3ExportEligibility(
		drawables = puppet.drawables.filter { drawable -> drawable.id !in dropped },
		parts = exportableParts,
		partByDrawable = partByDrawable,
		partParentById = partParentById,
		droppedDrawables = dropped,
	)
}

/**
 * Every part in the subtrees rooted at [roots], roots included.
 *
 * A guide image or a hidden group is usually one part, but nothing stops a rigger from grouping
 * several under it - and a child of a guide is still a guide, a child of a hidden part still
 * hidden.
 *
 * @param PuppetModel puppet The rig.
 * @param List<Part>  roots  The subtree roots to close over.
 * @return Set The part ids to omit.
 */
private fun partSubtree(puppet: PuppetModel, roots: List<Part>): Set<PartId> {
	if (roots.isEmpty()) {
		return emptySet()
	}
	val partsById = puppet.parts.associateBy { part -> part.id }
	val omitted = LinkedHashSet<PartId>()
	val pending = ArrayDeque(roots.map { part -> part.id })
	while (pending.isNotEmpty()) {
		val partId = pending.removeFirst()
		if (!omitted.add(partId)) {
			continue
		}
		for (child in partsById[partId]?.children.orEmpty()) {
			if (child is OrgChild.Part) {
				pending.addLast(child.id)
			}
		}
	}
	return omitted
}