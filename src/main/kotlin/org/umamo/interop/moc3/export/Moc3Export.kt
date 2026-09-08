package org.umamo.interop.moc3.export

import org.umamo.format.moc3.Moc3
import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.moc.CanvasInfo
import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportReport
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.interop.mocVersion
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/**
 * Lowers a [PuppetModel] into a [MocDocument] - the semantic half of writing a `.moc3` from a rig
 * that may never have been one.
 *
 * This is a FULL SYNTHESIS, deliberately unlike the CMO3 export's state-based reconcile.  That
 * reconcile exists to preserve unmodeled XML the writer does not understand; a MOC3 has no such
 * payload once every section index is modeled, so there is nothing to carry and a reference
 * container would only constrain the output.  A CMO3-origin or future UMA-origin document
 * therefore exports exactly like a MOC3-origin one.
 *
 * THE LOAD-BEARING INVARIANT, which every geometry path here depends on:
 * `drawable.mesh.positions[i] + cell.positionDeltas[i]` is the drawable's ABSOLUTE position in its
 * parent-deformer space, for every document origin.  `restMeshesToCanvasSpace` rewrites the base
 * and compensates the deltas so the sum is untouched, and CMO3 stores the same mixed-space
 * convention natively - which is what lets one lowering serve both.
 *
 * An export ALWAYS writes.  Anything it cannot express becomes an [ExportNotice] rather than a
 * silent drop, including hidden objects, which by default are CARRIED with their flag clear rather
 * than deleted the way the official editor's bake deletes them.  [Moc3ExportOptions] is where the
 * rigger changes that: its hidden/guide flags feed [resolveExportEligibility], and its scale
 * override feeds the [Moc3ExportContext]; the options-less default stays the carrying behavior.
 *
 * This object is only the orchestrator.  The work sits in per-concern producers beside it -
 * [resolveExportEligibility] for what gets written at all, then [lowerParameters], [lowerRenderOrder],
 * [lowerParts], [lowerDeformers], [lowerArtMeshes], [lowerGlues], [lowerOffscreens], and
 * [lowerBlendShapes] - over one immutable [Moc3ExportContext] and three collaborators each producer
 * takes explicitly: the [Moc3KeyformPool] it interns bindings into, the [Moc3ExportIds] it claims
 * written ids from, and the [Moc3ExportNotices] it reports through.  A producer's signature is
 * therefore the statement of what it can change.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
object Moc3Export {
	/**
	 * The lowered document plus whatever the lowering could not express.
	 *
	 * @property MocDocument     document   The document to bake.
	 * @property ExportReport    report     The advisory findings; empty for a fully-lowered export.
	 * @property Moc3WrittenIds  writtenIds What each object's id was written as, for the sidecars that
	 *                                      have to name the same objects (see [Moc3WrittenIds]).
	 */
	class Lowered(
		val document: MocDocument,
		val report: ExportReport,
		val writtenIds: Moc3WrittenIds,
	)

	/**
	 * Lowers [puppet] into a [MocDocument] at [version], stripping whatever that version cannot carry.
	 *
	 * The strip runs FIRST, on the model (see [Moc3VersionDowngrade]), so every producer works on a rig
	 * the target version can express completely - and the loss is reported against entities the rigger
	 * recognizes rather than against section indices.
	 *
	 * @param PuppetModel puppet  The rig to export.
	 * @param MocVersion  version The moc version to target; the document's own runtime target by default.
	 * @param CanvasToParentSpace? canvasToParentSpace Inverts the deformer chain for an unkeyed
	 *   drawable; null drops those drawables with a notice instead (see [CanvasToParentSpace]).
	 * @param Moc3ExportOptions options What the rigger chose to include; the default is the
	 *   options-less behavior.
	 * @return Lowered The document and its notices.
	 */
	fun toMocDocument(
		puppet: PuppetModel,
		version: MocVersion = puppet.runtimeTarget.mocVersion(),
		canvasToParentSpace: CanvasToParentSpace? = null,
		options: Moc3ExportOptions = Moc3ExportOptions.Default,
	): Lowered {
		val noticeSink = Moc3ExportNotices()
		val downgraded = Moc3VersionDowngrade.strip(puppet, version)
		noticeSink.addStripNotices(downgraded.notices)
		// Everything below works on the STRIPPED rig, never the parameter.  The two are the same object
		// whenever the target carries every feature the rig uses, which is every corpus export and every
		// re-target upward - so reading the parameter by mistake is invisible on all of them.  Naming
		// them apart is what makes the mistake say so.
		val downgradedPuppet = downgraded.puppet
		val eligibility = resolveExportEligibility(downgradedPuppet, canvasToParentSpace, options)
		val plan = Moc3IndexPlan.of(downgradedPuppet, eligibility.drawables, eligibility.parts)
		val context = Moc3ExportContext(downgradedPuppet, version, eligibility, plan, canvasToParentSpace, options)
		val pool = Moc3KeyformPool { parameterId -> plan.parameterIndex(parameterId) }
		// Built from the plan, so every id the file will contain is claimed before the first record is
		// written and an id too wide for the record can only be shortened INTO a free name.
		val ids = Moc3ExportIds(plan, downgradedPuppet.glues, noticeSink)

		// Neither of these two interns: a parameter is an axis objects bind TO, and the render-order tree
		// addresses objects that already exist.
		val parameters = lowerParameters(context, ids)
		val renderOrderGroups = lowerRenderOrder(downgradedPuppet, plan)

		// The four producers below intern into the shared pool IN THIS ORDER, and that order is the
		// file's binding numbering.  Reordering them is semantically harmless - the numbering is a
		// permutation nothing downstream reads - but it is also the visible symptom of a producer moving
		// relative to a data dependency it has, which is not harmless.  Moc3ExportBindingOrderTest pins
		// it so that second thing cannot happen quietly.
		val loweredParts = lowerParts(context, pool, ids, noticeSink)
		val parts = loweredParts.records
		val deformers = lowerDeformers(context, pool, ids, noticeSink)
		val artMeshes = lowerArtMeshes(context, pool, ids, noticeSink)
		val glues = lowerGlues(downgradedPuppet, context, pool, ids, noticeSink)

		// LAST, so drop notices trail the per-object findings rather than leading them.  The eligibility
		// pass runs first, but a rigger reads its findings better after the rest.
		noticeSink.reportDroppedDrawables(eligibility.droppedDrawables)

		val document =
			MocDocument(
				version = version,
				canvas =
					CanvasInfo(
						pixelsPerUnit = context.pixelsPerUnit,
						originX = downgradedPuppet.worldOriginX,
						// The runtime negates the canvas y into world space; storing it re-negates.
						originY = -downgradedPuppet.worldOriginY,
						width = downgradedPuppet.canvasWidth,
						height = downgradedPuppet.canvasHeight,
					),
				parameters = parameters,
				keyformBindings = pool.bindings().associateBy { binding -> binding.index },
				parts = parts,
				deformers = deformers,
				artMeshes = artMeshes,
				glues = glues,
				renderOrderGroups = renderOrderGroups,
				offscreens =
					if (context.offscreensEnabled) {
						lowerOffscreens(downgradedPuppet, plan, loweredParts.keyformsByPartId, context.colorsEnabled, noticeSink)
					} else {
						emptyList()
					},
				// Blend shapes arrived in Cubism 4.2; a lower target simply carries none.
				blendShapes =
					if (version.byteValue < 4) {
						emptyList()
					} else {
						lowerBlendShapes(
							plan.drawables,
							plan.deformers,
							plan.parts,
							plan.parameters,
							plan,
							context.canvas,
							{ parentId -> context.spaceOfParent(parentId) },
							{ rotation -> context.rotationScaleFactorFor(rotation) },
							context.colorsEnabled,
						)
					},
			)
		return Lowered(document, noticeSink.report(), ids.writtenIds())
	}

	/**
	 * Lowers [puppet] and bakes it to `.moc3` bytes.
	 *
	 * @param PuppetModel puppet  The rig to export.
	 * @param MocVersion  version The moc version to target; the document's own runtime target by default.
	 * @param CanvasToParentSpace? canvasToParentSpace The unkeyed-drawable space inverse, or null.
	 * @param Moc3ExportOptions options What the rigger chose to include; the default is the
	 *   options-less behavior.
	 * @return Pair The bytes and the advisory report.
	 */
	fun write(
		puppet: PuppetModel,
		version: MocVersion = puppet.runtimeTarget.mocVersion(),
		canvasToParentSpace: CanvasToParentSpace? = null,
		options: Moc3ExportOptions = Moc3ExportOptions.Default,
	): Pair<ByteArray, ExportReport> {
		val lowered = toMocDocument(puppet, version, canvasToParentSpace, options)
		return Moc3.write(lowered.document) to lowered.report
	}

	/**
	 * The pixels-per-unit a bake of [puppet] should carry.
	 *
	 * A moc's canvas scale is a BAKE parameter, not a project property: every corpus `.cmo3` stores
	 * `CModelInfo.pixelsPerUnit = 1` - a CMO3 works in canvas pixels - while the editor's bake of the
	 * same project writes a real scale, and the rigger picks it in the export dialog.  Its default there
	 * is the canvas WIDTH, which 21 of the 25 corpus bakes use exactly (the four that do not chose their
	 * own: 9000 -> 5000, 9000 -> 3077, 4500 -> 3000, 5134 -> 5000).
	 *
	 * So a CMO3-origin export defaults to the canvas width, and a MOC3-origin one keeps the scale its
	 * file already had.  Writing the project's literal 1 instead is not a smaller choice - it stores the
	 * whole rig at PIXEL scale, which every runtime then draws hundreds of times too large.  A rigger
	 * who wants a different bake scale sets [Moc3ExportOptions.pixelsPerUnitOverride]; the export
	 * dialog seeds its scale field from this function, which is why it is public.
	 *
	 * The two cases are told apart by [PuppetModel.pixelsPerUnit] being NULL, never by the value's
	 * magnitude: 1 is a legitimate bake scale, so a `> 1` test would silently rescale a moc that baked
	 * at 1 - or at any scale below it - by the canvas width and shrink the rig by that whole factor.
	 *
	 * @param PuppetModel puppet The rig being exported.
	 * @return Float The canvas scale to write.
	 */
	fun mocPixelsPerUnitFor(puppet: PuppetModel): Float {
		puppet.pixelsPerUnit?.let { recorded -> return recorded }
		return puppet.canvasWidth.takeIf { width -> width > 0f } ?: 1f
	}
}

/**
 * Inverts a drawable's canvas-space rest mesh into its parent deformer's space.
 *
 * An injected seam rather than a call, because the inverse lives in `:render` (a closed-form rotation
 * inverse and a damped-Newton warp inverse over the evaluated chain) and `:interop` is its sibling
 * over `:runtime`, not its dependent - the same shape as the atlas decode's injected byte reader.
 *
 * Only reached for a drawable with no keyform grid under a deformer: everything else already stores
 * parent-local values.  Returning null (or a differently-sized array) leaves the rest mesh as authored
 * and raises a notice, which is the honest outcome when the chain cannot be inverted at all.
 *
 * @param DrawableId drawable  The drawable being written.
 * @param FloatArray positions Its interleaved canvas-space rest positions.
 * @return FloatArray? The interleaved parent-space positions, or null when the chain cannot invert.
 */
typealias CanvasToParentSpace = (drawable: DrawableId, positions: FloatArray) -> FloatArray?