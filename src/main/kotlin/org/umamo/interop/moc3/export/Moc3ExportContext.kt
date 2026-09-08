package org.umamo.interop.moc3.export

import org.umamo.format.moc3.moc.MocVersion
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.interop.moc3.MocCanvasMapping
import org.umamo.interop.moc3.PointSpace
import org.umamo.interop.moc3.rotationAncestorsById
import org.umamo.interop.moc3.rotationScaleFactor
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/**
 * Everything the export producers derive from a rig before any of them writes a record.
 *
 * Immutable, and deliberately narrower than the encode side's `MocLoweringContext`: the two things a
 * producer MUTATES - the keyform pool it interns bindings into and the notice sink it reports through -
 * are passed alongside this rather than held here, so a producer's signature says what it changes.
 *
 * The rig here is the STRIPPED one.  A producer that reached back to the caller's model would read
 * features the target version cannot carry, and on every export at a model's own version the two are
 * the same object - so nothing would notice.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.6</a>
 */
internal class Moc3ExportContext(
	val puppet: PuppetModel,
	val version: MocVersion,
	val eligibility: Moc3ExportEligibility,
	val plan: Moc3IndexPlan,
	val canvasToParentSpace: CanvasToParentSpace?,
	options: Moc3ExportOptions = Moc3ExportOptions.Default,
) {
	/**
	 * The bake scale written into the canvas record and used to convert every position.
	 *
	 * Resolved HERE, before [canvas] is built, because an override that only patched the canvas
	 * record afterward would leave every converted position at the old scale - the record and the
	 * geometry must come from the same number.
	 */
	val pixelsPerUnit: Float =
		options.pixelsPerUnitOverride?.takeIf { override -> override > 0f }
			?: Moc3Export.mocPixelsPerUnitFor(puppet)

	/** The px↔model mapping every geometry conversion goes through. */
	val canvas: MocCanvasMapping = MocCanvasMapping(pixelsPerUnit, puppet.worldOriginX, -puppet.worldOriginY)

	/**
	 * Whether the target version carries the per-object color tables.
	 *
	 * Per-object multiply/screen color arrived in Cubism 4.2; below that the tables do not exist and
	 * every keyform must carry null rather than an identity, or the lowering would synthesize sections
	 * the version cannot address.
	 */
	val colorsEnabled: Boolean = version.byteValue >= 4

	/**
	 * Whether the target version carries offscreen records.
	 *
	 * Offscreen rendering (an isolated part composited as one layer) arrived in Cubism 5.3, as did the
	 * extended blend surface.  Two names for one number: they are separate features that happen to
	 * share a version, and a later version bump should be able to move one without the other - which is
	 * why this and [extendedBlendEnabled] stay separate fields rather than collapsing into one.
	 */
	val offscreensEnabled: Boolean = version.byteValue >= 6

	/** Whether the target version carries the packed extended blend (MOC3 v6 §5.6 s153). */
	val extendedBlendEnabled: Boolean = version.byteValue >= 6

	/** Per rotation deformer, whether a rotation ancestor already applied the px→model factor. */
	private val rotationAncestors: Map<DeformerId, Boolean> = rotationAncestorsById(plan.deformers)

	/** Each drawable's owning part, null when unparented. */
	val partByDrawable: Map<DrawableId, PartId?> get() = eligibility.partByDrawable

	/** The org tree's part→parent link. */
	val partParentById: Map<PartId, PartId> get() = eligibility.partParentById

	/**
	 * The space a child of [parentId] stores its positions in.
	 *
	 * The export's mirror of the import's `pointSpaceOf`, resolved through the plan so an unknown parent
	 * normalizes to root exactly as the import normalizes an unresolvable index.  Three callers - the
	 * deformer, art-mesh, and blend-shape lowerings - and they must all resolve identically, which is
	 * what makes this a context method rather than a local helper in one of them.
	 *
	 * @param DeformerId? parentId The owning object's parent deformer.
	 * @return PointSpace The space to store in.
	 */
	fun spaceOfParent(parentId: DeformerId?): PointSpace {
		val index = plan.deformerIndex(parentId)
		return when (plan.deformers.getOrNull(index)) {
			is Deformer.Warp -> PointSpace.WarpLattice
			is Deformer.Rotation -> PointSpace.RotationLocal
			null -> PointSpace.ModelRoot
		}
	}

	/**
	 * The px→model factor a rotation deformer's pivot scale divides by.
	 *
	 * Routed through the context because the deformer lowering and the blend-shape pass both need it
	 * and must agree; deriving it twice is how the two would drift.
	 *
	 * @param Deformer.Rotation rotation The rotation deformer.
	 * @return Float The scale factor.
	 */
	fun rotationScaleFactorFor(rotation: Deformer.Rotation): Float =
		rotationScaleFactor(rotationAncestors[rotation.id] ?: false, canvas)
}