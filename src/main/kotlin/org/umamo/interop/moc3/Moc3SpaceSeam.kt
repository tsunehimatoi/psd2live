package org.umamo.interop.moc3

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId

/*
 * The coordinate seam between a `.moc3`'s stored values and the runtime's convention, in BOTH
 * directions.
 *
 * MOC3 stores geometry in three different spaces depending on what an object hangs from, and only
 * one of them differs from the runtime's - so an import converts a minority of values and an
 * export has to invert exactly that same minority.  There is also a unit seam on rotation scale
 * that is easy to miss and impossible to see in a single object: the FIRST rotation on each root
 * path carries the pixels-per-unit factor and every rotation below it does not.
 *
 * Import and export live in one file because the failure mode of two copies is invisible until it
 * is catastrophic.  Get the rotation-ancestor predicate subtly wrong in one of them and a whole
 * subtree exports at the wrong scale, with nothing but a differential oracle to say so.
 *
 * @see <a href="https://docs.umamo.org/format/MOC3.md">MOC3.md §5.3</a>
 */

/** The coordinate space an object's stored positions live in, selected by its parent deformer. */
enum class PointSpace {
	/** MOC3 model space (a root object): canvas = CanvasInfo origin + ppu·model, Y-down like the canvas. */
	ModelRoot,

	/** A warp parent's normalized lattice (u, v) - identical in both conventions. */
	WarpLattice,

	/** A rotation parent's pixel-scale local frame - identical in both conventions. */
	RotationLocal,
}

/**
 * The canvas mapping a `.moc3` stores, as the two numbers every conversion here needs.
 *
 * @property Float pixelsPerUnit Canvas pixels per model unit; 1 when the source carried no canvas.
 * @property Float originX       The canvas-space origin's x.
 * @property Float originY       The canvas-space origin's y, in CANVAS orientation (not negated).
 */
class MocCanvasMapping(
	val pixelsPerUnit: Float,
	val originX: Float,
	val originY: Float,
)

/**
 * The scale every conversion in this file actually applies.
 *
 * A degenerate canvas (ppu 0) has no invertible scale: dividing by it is a division by zero, and
 * multiplying by it collapses the whole rig onto the canvas origin.  Both directions therefore
 * substitute the identity, which is also how the import treats a canvas-less document.
 *
 * Shared rather than written out per direction because a guard applied on only one side is exactly
 * how the two stop being inverses - the moc direction would map a degenerate canvas as the identity
 * while the runtime direction flattened every point onto one spot, and nothing here would say so.
 *
 * @param MocCanvasMapping canvas The document's canvas mapping.
 * @return Float The scale factor to apply.
 */
private fun effectiveScale(canvas: MocCanvasMapping): Float =
	if (canvas.pixelsPerUnit != 0f) canvas.pixelsPerUnit else 1f

/**
 * Converts interleaved x,y [points] from the moc's stored [space] to the runtime's convention.
 *
 * Warp-lattice and rotation-local values are already in the runtime's convention (corpus-verified) and
 * pass through untouched; only root-space values map through the canvas affine.
 *
 * @param PointSpace       space  The stored space.
 * @param FloatArray       points Interleaved x,y positions as stored in the moc.
 * @param MocCanvasMapping canvas The document's canvas mapping.
 * @return FloatArray The converted positions (always a fresh array).
 */
fun convertPointsToRuntime(space: PointSpace, points: FloatArray, canvas: MocCanvasMapping): FloatArray {
	val converted = FloatArray(points.size)
	val scale = effectiveScale(canvas)
	var coordIndex = 0
	while (coordIndex + 1 < points.size) {
		when (space) {
			// MOC3 §5.3 CanvasInfo: canvas px = origin + ppu·model, same Y-down orientation.
			PointSpace.ModelRoot -> {
				converted[coordIndex] = canvas.originX + scale * points[coordIndex]
				converted[coordIndex + 1] = canvas.originY + scale * points[coordIndex + 1]
			}
			// Warp-lattice (u, v) and pixel-scale rotation-local frames match the runtime verbatim.
			PointSpace.WarpLattice, PointSpace.RotationLocal -> {
				converted[coordIndex] = points[coordIndex]
				converted[coordIndex + 1] = points[coordIndex + 1]
			}
		}
		coordIndex += 2
	}
	return converted
}

/**
 * The exact inverse of [convertPointsToRuntime]: runtime positions back to the moc's stored [space].
 *
 * @param PointSpace       space  The space to store in.
 * @param FloatArray       points Interleaved x,y positions in the runtime's convention.
 * @param MocCanvasMapping canvas The document's canvas mapping.
 * @return FloatArray The values to store (always a fresh array).
 */
fun convertPointsToMoc(space: PointSpace, points: FloatArray, canvas: MocCanvasMapping): FloatArray {
	val converted = FloatArray(points.size)
	val scale = effectiveScale(canvas)
	var coordIndex = 0
	while (coordIndex + 1 < points.size) {
		when (space) {
			PointSpace.ModelRoot -> {
				converted[coordIndex] = (points[coordIndex] - canvas.originX) / scale
				converted[coordIndex + 1] = (points[coordIndex + 1] - canvas.originY) / scale
			}
			PointSpace.WarpLattice, PointSpace.RotationLocal -> {
				converted[coordIndex] = points[coordIndex]
				converted[coordIndex + 1] = points[coordIndex + 1]
			}
		}
		coordIndex += 2
	}
	return converted
}

/**
 * Converts interleaved delta components from the moc's stored [space] to the runtime's convention.
 *
 * Unlike [convertPointsToRuntime] the canvas ORIGIN does not apply - it cancels out of a difference -
 * so a root-space delta scales by ppu only; the other spaces pass through.
 *
 * @param PointSpace       space  The stored space.
 * @param FloatArray       deltas Interleaved x,y deltas as stored in the moc.
 * @param MocCanvasMapping canvas The document's canvas mapping.
 * @return FloatArray The converted deltas (always a fresh array).
 */
fun convertDeltasToRuntime(space: PointSpace, deltas: FloatArray, canvas: MocCanvasMapping): FloatArray =
	when (space) {
		PointSpace.ModelRoot -> {
			val scale = effectiveScale(canvas)
			FloatArray(deltas.size) { index -> scale * deltas[index] }
		}
		PointSpace.WarpLattice, PointSpace.RotationLocal -> deltas.copyOf()
	}

/**
 * The exact inverse of [convertDeltasToRuntime].
 *
 * @param PointSpace       space  The space to store in.
 * @param FloatArray       deltas Interleaved x,y deltas in the runtime's convention.
 * @param MocCanvasMapping canvas The document's canvas mapping.
 * @return FloatArray The values to store (always a fresh array).
 */
fun convertDeltasToMoc(space: PointSpace, deltas: FloatArray, canvas: MocCanvasMapping): FloatArray {
	val scale = effectiveScale(canvas)
	return when (space) {
		PointSpace.ModelRoot -> FloatArray(deltas.size) { index -> deltas[index] / scale }
		PointSpace.WarpLattice, PointSpace.RotationLocal -> deltas.copyOf()
	}
}

/**
 * The px→model unit factor a rotation deformer's stored scale carries.
 *
 * Along every root path the FIRST rotation is where the accumulated scale chain converts pixel-scale
 * local space into model units, so only that rotation's stored scale carries the factor.  Every
 * rotation below it - whatever its direct parent's kind - inherits it through the accumulator and
 * stores its scale verbatim.
 *
 * @param Boolean          hasRotationAncestor Whether a rotation sits anywhere above this deformer.
 * @param MocCanvasMapping canvas              The document's canvas mapping.
 * @return Float The multiplier taking a stored scale to the runtime's, 1 when a rotation is above.
 */
fun rotationScaleFactor(hasRotationAncestor: Boolean, canvas: MocCanvasMapping): Float =
	if (hasRotationAncestor) 1f else effectiveScale(canvas)

/**
 * Whether a rotation deformer sits anywhere on each deformer's ancestor chain, by FILE index.
 *
 * @param Int      deformerCount   How many deformers there are.
 * @param Function parentIndexOf   Each deformer's parent index (-1 at the root).
 * @param Function isRotationAt    Whether the deformer at an index is a rotation.
 * @return BooleanArray Indexed like the deformer list.
 */
fun rotationAncestorFlags(
	deformerCount: Int,
	parentIndexOf: (Int) -> Int,
	isRotationAt: (Int) -> Boolean,
): BooleanArray =
	BooleanArray(deformerCount) { deformerIndex ->
		var currentIndex = parentIndexOf(deformerIndex)
		var found = false
		// The step cap breaks a malformed cyclic parent chain rather than hanging on it.
		var chainSteps = 0
		while (currentIndex in 0 until deformerCount && chainSteps <= deformerCount) {
			if (isRotationAt(currentIndex)) {
				found = true
				break
			}
			currentIndex = parentIndexOf(currentIndex)
			chainSteps++
		}
		found
	}

/**
 * [rotationAncestorFlags] over a RUNTIME deformer list, keyed by deformer id.
 *
 * The export walks ids rather than file indices, but the predicate must stay the one the import used -
 * hence one implementation with two adapters rather than two walks.
 *
 * @param List deformers The runtime deformers.
 * @return Map Whether a rotation sits above each deformer.
 */
fun rotationAncestorsById(deformers: List<Deformer>): Map<DeformerId, Boolean> {
	val byId = deformers.associateBy { deformer -> deformer.id }
	return deformers.associate { deformer ->
		var current = deformer.parent?.let { byId[it] }
		var found = false
		var chainSteps = 0
		while (current != null && chainSteps <= deformers.size) {
			if (current is Deformer.Rotation) {
				found = true
				break
			}
			current = current.parent?.let { byId[it] }
			chainSteps++
		}
		deformer.id to found
	}
}