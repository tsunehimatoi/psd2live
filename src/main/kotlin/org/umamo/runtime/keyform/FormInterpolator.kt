package org.umamo.runtime.keyform

import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm

/*
 * The one interpolation primitive the grid algebra is allowed to use: a 1-D blend between two ADJACENT
 * slices of a single axis.  The algebra deliberately never performs an N-D blend - that is the evaluator's
 * job in runtime.eval, and duplicating it here would put a second, drifting copy of the deformation math
 * next to the one the differential oracle checks.
 *
 * Key insertion, export refinement, and compaction all reduce to this one operation, which is what makes
 * the losslessness argument one-dimensional and therefore checkable.
 */

/**
 * Blends a form between two adjacent slices of one axis, and tests two stored forms for exact equality.
 *
 * The [interpolate] contract is strict: it MUST compute `(1 - fraction) * lower + fraction * upper`, the
 * same expression `gridCorners` builds its corner weights from - NOT the numerically tidier
 * `lower + (upper - lower) * fraction`.  Compaction drops a key only when the stored form is bit-equal to
 * this function's output, and refinement re-inserts that key by evaluating this same function on the same
 * inputs; IEEE-754 is deterministic, so sharing one expression is exactly what makes the two mutual
 * inverses.  Switching to an algebraically equal formula silently breaks that.
 *
 * @param TForm The cell payload type this interpolator handles.
 */
interface FormInterpolator<TForm> {
	/**
	 * The form [fraction] of the way from [lower] to [upper].
	 *
	 * @param TForm lower The form at the lower key.
	 * @param TForm upper The form at the next key up.
	 * @param Float fraction The blend position in 0..1 (0 yields [lower], 1 yields [upper]).
	 * @return TForm The blended form.
	 */
	fun interpolate(lower: TForm, upper: TForm, fraction: Float): TForm

	/**
	 * Whether two stored forms are EXACTLY equal - bit-equal floats, never an epsilon compare.
	 *
	 * Compaction gates on this.  An epsilon here would silently alter a rigger's authored values, which
	 * the fidelity contract forbids; a value that is merely close is a value someone chose.
	 *
	 * @param TForm left The first form.
	 * @param TForm right The second form.
	 * @return Boolean True when the two forms are exactly equal.
	 */
	fun isExactlyEqual(left: TForm, right: TForm): Boolean
}

/**
 * Blends two floats with the evaluator's own corner-weight expression.
 *
 * @param Float lower The lower key's value.
 * @param Float upper The upper key's value.
 * @param Float fraction The blend position in 0..1.
 * @return Float The blended value.
 */
internal fun blendScalar(lower: Float, upper: Float, fraction: Float): Float = (1f - fraction) * lower + fraction * upper

/**
 * Blends two interleaved float arrays component-wise.
 *
 * A length mismatch is a malformed grid rather than something to paper over, so the result takes the
 * shorter length and the caller's own vertex-count guards catch it - matching how the evaluator's blend
 * sites already tolerate a short delta array.
 *
 * @param FloatArray lower The lower key's components.
 * @param FloatArray upper The upper key's components.
 * @param Float fraction The blend position in 0..1.
 * @return FloatArray The blended components.
 */
internal fun blendComponents(lower: FloatArray, upper: FloatArray, fraction: Float): FloatArray {
	val length = minOf(lower.size, upper.size)
	val blended = FloatArray(length)
	for (componentIndex in 0 until length) {
		blended[componentIndex] = blendScalar(lower[componentIndex], upper[componentIndex], fraction)
	}
	return blended
}

/** Interpolates a drawable's per-vertex position deltas. */
object MeshDeltaInterpolator : FormInterpolator<MeshDeltaForm> {
	override fun interpolate(lower: MeshDeltaForm, upper: MeshDeltaForm, fraction: Float): MeshDeltaForm =
		MeshDeltaForm(blendComponents(lower.positionDeltas, upper.positionDeltas, fraction))

	override fun isExactlyEqual(left: MeshDeltaForm, right: MeshDeltaForm): Boolean =
		left.positionDeltas.contentEquals(right.positionDeltas)
}

/** Interpolates a warp deformer's absolute lattice control points. */
object WarpLatticeInterpolator : FormInterpolator<WarpLatticeForm> {
	override fun interpolate(lower: WarpLatticeForm, upper: WarpLatticeForm, fraction: Float): WarpLatticeForm =
		WarpLatticeForm(blendComponents(lower.controlPoints, upper.controlPoints, fraction))

	override fun isExactlyEqual(left: WarpLatticeForm, right: WarpLatticeForm): Boolean =
		left.controlPoints.contentEquals(right.controlPoints)
}

/** Interpolates a rotation deformer's absolute pivot transform (its flags are FLAG channels, not geometry). */
object RotationPivotInterpolator : FormInterpolator<RotationPivotForm> {
	override fun interpolate(lower: RotationPivotForm, upper: RotationPivotForm, fraction: Float): RotationPivotForm =
		RotationPivotForm(
			originX = blendScalar(lower.originX, upper.originX, fraction),
			originY = blendScalar(lower.originY, upper.originY, fraction),
			angle = blendScalar(lower.angle, upper.angle, fraction),
			scale = blendScalar(lower.scale, upper.scale, fraction),
		)

	override fun isExactlyEqual(left: RotationPivotForm, right: RotationPivotForm): Boolean =
		left.originX == right.originX &&
			left.originY == right.originY &&
			left.angle == right.angle &&
			left.scale == right.scale
}

/**
 * Interpolates a channel track's value.
 *
 * Scalars and colors blend; a FLAG snaps to [lower], which is the floor cell.  That matches the evaluator
 * exactly: `gridCorners` seeds a single corner and appends the lower-key split before the upper-key one,
 * so `corners[0]` is always the all-lower-key corner - and that is the corner the rotation flags already
 * read today.  A mismatched pair (two different kinds in one track) is a malformed track; [lower] wins,
 * so the track keeps its own kind rather than silently changing type mid-axis.
 */
object ChannelValueInterpolator : FormInterpolator<ChannelValue> {
	override fun interpolate(lower: ChannelValue, upper: ChannelValue, fraction: Float): ChannelValue =
		when (lower) {
			is ChannelValue.Scalar -> {
				if (upper is ChannelValue.Scalar) {
					ChannelValue.Scalar(blendScalar(lower.value, upper.value, fraction))
				} else {
					lower
				}
			}

			is ChannelValue.Color -> {
				if (upper is ChannelValue.Color) {
					ChannelValue.Color(
						ColorRgb(
							blendScalar(lower.color.red, upper.color.red, fraction),
							blendScalar(lower.color.green, upper.color.green, fraction),
							blendScalar(lower.color.blue, upper.color.blue, fraction),
						),
					)
				} else {
					lower
				}
			}

			// A boolean has no midpoint: the floor cell wins for the whole span, matching the evaluator.
			is ChannelValue.Flag -> lower
		}

	override fun isExactlyEqual(left: ChannelValue, right: ChannelValue): Boolean = left == right
}