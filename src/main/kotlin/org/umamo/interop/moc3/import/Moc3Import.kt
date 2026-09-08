package org.umamo.interop.moc3.import

import org.umamo.format.moc3.MocDocument
import org.umamo.format.moc3.json.Cdi3Json
import org.umamo.interop.runtimeTargetOfMocVersion
import org.umamo.runtime.keyform.withChannelsCompacted
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deriveRenderRoot
import org.umamo.runtime.model.partByDrawable

/**
 * Maps a decoded MOC3 document (`:format`) plus its optional cdi3 display info into the concrete
 * [PuppetModel] (`:runtime`) - the baked-runtime counterpart of `Cmo3Import`.
 *
 * MOC3 references everything by index (list position), so the mapping is a single pass over each
 * list with index → id tables built up front.  Three format gaps shape the result:
 *
 *  - Coordinate space.  MOC3 keyform positions are absolute values in the OWNING OBJECT'S PARENT
 *    SPACE, and every parent space but the root matches the runtime's convention VERBATIM (verified
 *    value-for-value against the CMO3 corpus twin): normalized lattice (u, v) under a warp parent and
 *    the pixel-scale local frame under a rotation parent, angles in degrees with the same sign.  Only
 *    the root space differs - the MOC stores model units around CanvasInfo's origin, same Y-down
 *    orientation - so root-space values map through the affine canvas = origin + ppu·model (see
 *    [Moc3ImportContext.pointSpaceOf]).  The one unit seam: a rotation parented to the root or a warp
 *    carries the px→model factor in its keyform scale, so those scales multiply by ppu to land in the
 *    runtime's pixel world; rotation-parented rotations keep their scale verbatim.  One caveat: the
 *    runtime's rest mesh (Drawable.mesh.positions) is canvas-space EDITING geometry in the CMO3
 *    convention, which a MOC does not store - this import leaves the rest mesh in parent space (exact
 *    for evaluation, since the base cancels out of the keyform blend), and `:render`'s
 *    restMeshesToCanvasSpace finishes the job by evaluating the default pose (the document loader
 *    applies it).
 *  - Names.  The binary stores ids (deformers included, §5.6 s11) but no display names; parameter/part
 *    names come from cdi3.json when present, and everything else falls back to the format id - the same
 *    rule `Cmo3Import` uses for an unnamed source.  A deformer's authored label is lost for good (the
 *    bake drops it and cdi3 carries no deformer entries), so its name is the id, plus the drawable it
 *    deforms when exactly one is in reach: "Warp40 (ArtMesh5)".
 *  - Blend shapes.  MOC3 records store per-key DELTAS relative to the object's grid form at the
 *    DEFAULT pose (MOC3.md §5.6), while the runtime `BlendShapeBinding` keeps grid-convention
 *    forms (MeshForm rest-relative; Warp/RotationForm absolute) and the evaluator re-subtracts
 *    that same grid-at-default reference.  The mapping therefore ADDS the reference back when
 *    synthesizing each form - computed with the shared org.umamo.runtime.eval sampling helpers,
 *    the exact functions the evaluator later calls, so the round trip cancels to ULP.  Delta
 *    geometry converts like the grid keyforms minus the origin term (a delta in root space scales
 *    by ppu only; lattice/rotation-local deltas pass through; the rotation-scale ppu seam applies
 *    to scale deltas too).  Neutral form slots import as null (the stored neutral row is all-zero).
 *    PART-owned records carry only a draw-order delta (a part has no other blendable channel) and
 *    ingest onto [org.umamo.runtime.model.Part.blendShapes].  Offscreens ingest into
 *    [org.umamo.runtime.model.PartComposite] per owner part (packed blend int, flags, mask indices)
 *    - the part's group mode becomes Isolated - with the keyformed opacity/color channels merged
 *    into the part's `PartForm` grid (they ride the same cells, MOC3 §5.6).
 *
 * This object is only the orchestrator.  The work sits in per-concern producers beside it -
 * [importParameters], [importParameterLinks], [importParameterTree], [importDeformers],
 * [importDrawables], [importGlues], [importRenderRoot], and [importParts] - over one immutable
 * [Moc3ImportContext].  There is no mutable collaborator to pass alongside it, unlike the export's
 * keyform pool and notice sink: an import reports nothing and interns nothing, so a producer's whole
 * input is the context.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5</a>
 */
object Moc3Import {
	/**
	 * Builds a [PuppetModel] from a decoded [MocDocument] (e.g. from `Moc3.read(bytes)`).
	 *
	 * @param MocDocument mocDocument The decoded semantic model.
	 * @param Cdi3Json?   displayInfo The sibling cdi3.json (display names, parameter groups, combined
	 *                                parameters), or null to fall back to raw format ids everywhere.
	 * @param Boolean compactChannels Whether to run the post-import channel compaction (on by default);
	 *   see Cmo3Import.fromModelSource.
	 * @return PuppetModel The concrete runtime puppet.
	 */
	fun fromMocDocument(
		mocDocument: MocDocument,
		displayInfo: Cdi3Json?,
		compactChannels: Boolean = true,
	): PuppetModel {
		val context = Moc3ImportContext(mocDocument, displayInfo)

		val parameters = importParameters(context)
		val parameterLinks = importParameterLinks(context)
		val parameterTree = importParameterTree(context)
		val deformers = importDeformers(context)
		val drawables = importDrawables(context)
		val glues = importGlues(context)

		// THE ordering contract of this import.  Panel order - the parts panel's stacking - is not stored
		// in a moc at all; it is RECONSTRUCTED from the render tree, and both the org tree's child sort
		// and the flat drawable order below read that reconstruction.  Running the org tree first throws
		// nothing: every panel index would simply be absent, which yields a different and entirely
		// plausible ordering.  Moc3ImportOrderTest is what makes that visible.
		val renderRoot = importRenderRoot(context)
		val panelIndexByDrawable = panelIndexesFrom(renderRoot)
		val importedParts = importParts(context, panelIndexByDrawable)

		// The flat drawables list is kept back-to-front (the storage/base order, mirroring Cmo3Import's
		// panel-derived ordering); unplaced drawables keep file order at the back.
		val orderedDrawables =
			drawables.sortedByDescending { drawable -> panelIndexByDrawable[drawable.id] ?: Int.MAX_VALUE }

		val model =
			PuppetModel(
				parameters = parameters,
				parts = importedParts.records,
				deformers = deformers,
				drawables = orderedDrawables,
				rootChildren = importedParts.rootChildren,
				// MOC3 has no synthetic root part; entities at the root simply carry parentPartIndex -1.
				rootPartId = null,
				glues = glues,
				parameterLinks = parameterLinks,
				parameterTree = parameterTree,
				// MOC3 §5.3 CanvasInfo: width/height are the canvas size in pixels; the world origin is the
				// canvas-space origin with Y negated into world space (same convention as Cmo3Import).
				canvasWidth = context.canvas?.width ?: 0f,
				canvasHeight = context.canvas?.height ?: 0f,
				worldOriginX = context.canvasOriginX,
				worldOriginY = -context.canvasOriginY,
				// Retained purely so an export can invert this import's space conversions; the evaluator and
				// the renderer never read it.  The CANVAS's own value, not the 1f identity that
				// Moc3ImportContext.canvasMapping substitutes for a canvas-less model - an export has to be
				// able to tell "this moc baked at 1" from "this document never had a bake scale" and pick
				// its own default for the second.
				pixelsPerUnit = context.canvas?.pixelsPerUnit,
				// MOC3 §3 Version Gating: the version byte is a hard fact of the baked file, so the import
				// starts at the matching Cubism target rather than NoTarget.
				runtimeTarget = runtimeTargetOfMocVersion(mocDocument.version),
			)
		// Three post-passes, and their order is load-bearing in one place: compaction runs LAST, after the
		// render-root fallback, because deriveRenderRoot reads the channel tracks that compaction lifts
		// into statics.
		//
		// Section 15 is authoritative when it places anything.  When it places NOTHING - a stripped or
		// synthesized MOC3 that omits or zeroes the section, which MocDecoder reads defensively for - the
		// org tree would be a flat root, so fall back to inferring membership from the drawables.
		val withDeformerParts =
			if (deformers.isNotEmpty() && deformers.all { deformer -> deformer.partId == null }) {
				model.copy(deformers = inferDeformerParts(deformers, orderedDrawables, model.partByDrawable()))
			} else {
				model
			}
		val withRenderRoot =
			if (renderRoot != null) {
				withDeformerParts.copy(renderRoot = renderRoot)
			} else {
				withDeformerParts.copy(renderRoot = withDeformerParts.deriveRenderRoot())
			}
		return if (compactChannels) withRenderRoot.withChannelsCompacted() else withRenderRoot
	}
}