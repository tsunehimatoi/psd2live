package org.umamo.format.moc3.model

/**
 * A deformer (warp or rotation), carrying the fields every deformer shares - the moc3 deformer
 * block's common head (sections 11-16), in file order.
 *
 * Deformers reference each other by index (their position in
 * [org.umamo.format.moc3.MocDocument.deformers]), and [id] is the authored identifier the editor
 * wrote alongside that index.  The two hierarchies are independent: [parentDeformerIndex] is the
 * deformation nesting, [parentPartIndex] the organizational tree.
 */
public sealed class Deformer {
	/** MOC3 §5.6 s11: the authored id, e.g. "Warp349" or "B_LEG_01". */
	public abstract val id: String

	/** MOC3 §5.6 s12: the deformer's keyform-binding (parameter grid) index. */
	public abstract val keyformBindingIndex: Int

	/** MOC3 §5.6 s13: whether the deformer is visible (the editor's eye toggle). */
	public abstract val isVisible: Boolean

	/** MOC3 §5.6 s14: the deformer's second flag; 1 throughout the corpus (see `DEFORMER_IS_ENABLED`). */
	public abstract val isEnabled: Boolean

	/** MOC3 §5.6 s15: the part this deformer belongs to, -1 at the root. */
	public abstract val parentPartIndex: Int

	/** MOC3 §5.6 s16: the parent in the deformation hierarchy, -1 for a root deformer. */
	public abstract val parentDeformerIndex: Int
}

/** One keyform of a [WarpDeformer]: the deformed control-point grid plus opacity/color. */
public data class WarpKeyform(
	/** Control points, `(columns+1)·(rows+1)` points as interleaved `x,y`. */
	val controlPoints: FloatArray,
	val opacity: Float,
	val multiplyColor: Rgb?,
	val screenColor: Rgb?,
) {
	override fun equals(other: Any?): Boolean =
		this === other ||
			(
				other is WarpKeyform &&
					controlPoints.contentEquals(other.controlPoints) &&
					opacity == other.opacity &&
					multiplyColor == other.multiplyColor &&
					screenColor == other.screenColor
			)

	override fun hashCode(): Int {
		var hash = controlPoints.contentHashCode()
		hash = 31 * hash + opacity.hashCode()
		hash = 31 * hash + (multiplyColor?.hashCode() ?: 0)
		hash = 31 * hash + (screenColor?.hashCode() ?: 0)
		return hash
	}
}

/** A warp (grid) deformer: a `(columns+1)·(rows+1)` control-point lattice keyed over parameters. */
public data class WarpDeformer(
	override val id: String,
	override val keyformBindingIndex: Int,
	override val isVisible: Boolean,
	override val isEnabled: Boolean,
	override val parentPartIndex: Int,
	override val parentDeformerIndex: Int,
	val rows: Int,
	val columns: Int,
	/** Interpolation mode (0 = triangle-split, non-zero = bilinear); 0 on moc < 3. */
	val mode: Int,
	val keyforms: List<WarpKeyform>,
) : Deformer()

/** One keyform of a [RotationDeformer]: the local affine plus opacity/color. */
public data class RotationKeyform(
	val originX: Float,
	val originY: Float,
	/** Angle delta in degrees (added to [RotationDeformer.baseAngle]). */
	val angle: Float,
	val scale: Float,
	val reflectX: Boolean,
	val reflectY: Boolean,
	val opacity: Float,
	val multiplyColor: Rgb?,
	val screenColor: Rgb?,
)

/** A rotation deformer: an origin/angle/scale affine keyed over parameters, plus a static base angle. */
public data class RotationDeformer(
	override val id: String,
	override val keyformBindingIndex: Int,
	override val isVisible: Boolean,
	override val isEnabled: Boolean,
	override val parentPartIndex: Int,
	override val parentDeformerIndex: Int,
	/** Static base angle in degrees, added to each keyform's [RotationKeyform.angle]. */
	val baseAngle: Float,
	val keyforms: List<RotationKeyform>,
) : Deformer()