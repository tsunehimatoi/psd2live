package org.umamo.runtime.eval

import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm

/*
 * Pure keyform-grid sampling: the multilinear corner selection and the pose-sampling helpers that
 * both the renderer's evaluator (:render) and the MOC3 import (Moc3Import) must agree on. Hoisted
 * from :render's eval so the import can compute the blend-shape delta reference (the grid form at
 * the DEFAULT pose) with the EXACT same arithmetic the evaluator later subtracts - any divergence
 * between the two would leak into every MOC3-imported blend shape as a residual offset.
 */

// Bracket tolerances match the Umamo C++ Runtime, needed for ULP-parity with the differential-oracle test.
// A value within EPS_KEY of a key snaps to it; a key span below EPS_SPAN contributes no fraction.
// runtime.keyform's grid algebra shares them: authoring a key closer than EPS_KEY to an existing one, or a
// span below EPS_SPAN, produces a grid this evaluator cannot resolve, so the algebra refuses both - and it
// must refuse against these exact values, not a second copy of them.

/**
 * The key-coincidence tolerance. Public because :interop's keyform export refinement merges union
 * axes against the same snap distance the sampling here and the grid algebra use; every consumer
 * must bracket against this exact value, never a copy of it.
 */
public const val EPS_KEY: Float = 0.001f

internal const val EPS_SPAN = 0.0015f

// The Umamo C++ Runtime caps the multilinear corner set at 16 (kbCorners `maxc`); past that an axis snaps to its
// lower key instead of splitting. Replicated for fidelity (matters only for >4 fractional axes).
private const val MAX_CORNERS = 16

/**
 * One axis's bracket of a parameter value: the lower key [index] and the [fraction] toward `index+1`
 * (0 when snapped exactly onto a key).
 */
public data class AxisBracket(val index: Int, val fraction: Float)

/**
 * A grid cell selected by the current parameters, addressed by its stride-folded [linearIndex], with
 * its multilinear [weight].
 */
public data class WeightedCell(val linearIndex: Int, val weight: Float)

/**
 * Brackets [value] against an axis's sorted [keys].  Returns null when the value is out of range
 * (the entity auto-hides / freezes), otherwise the lower key index and the blend fraction toward
 * the next key (0 when snapped onto a key).
 *
 * @param FloatArray keys  The axis's key positions (ascending parameter values).
 * @param Float      value The current parameter value on this axis.
 * @return AxisBracket? The bracket, or null when out of range.
 */
public fun bindBracket(keys: FloatArray, value: Float): AxisBracket? {
	val keyCount = keys.size
	if (keyCount <= 1) {
		if (keyCount == 0) {
			return AxisBracket(0, 0f)
		}
		return if (value > keys[0] - EPS_KEY && value < keys[0] + EPS_KEY) AxisBracket(0, 0f) else null
	}
	if (value < keys[0] - EPS_KEY || value >= keys[keyCount - 1] + EPS_KEY) {
		return null
	}
	if (value < keys[0] + EPS_KEY) {
		return AxisBracket(0, 0f)
	}
	var hi = 1
	while (hi < keyCount && keys[hi] + EPS_KEY <= value) {
		hi++
	}
	if (value <= keys[hi] - EPS_KEY) {
		val span = keys[hi] - keys[hi - 1]
		val fraction = if (span >= EPS_SPAN) (value - keys[hi - 1]) / span else 0f
		return AxisBracket(hi - 1, fraction)
	}
	return AxisBracket(hi, 0f)
}

/**
 * The multilinear corner set for [grid] at the current parameters, or null when any controlling axis
 * is out of range (the entity is frozen).  Each fractional axis doubles the corner
 * list (weights ×= frac / 1−frac) and folds a stride-based linear cell index, matching how
 * [cellsByLinearIndex] folds each [KeyformCell.coordinate].
 *
 * @param KeyformGrid grid       The entity's keyform grid (only its axes are read).
 * @param Function    paramValue Current value for a given parameter id.
 * @return List<WeightedCell>? The weighted corner cells, or null when hidden.
 */
public fun gridCorners(grid: KeyformGrid<*>, paramValue: (ParameterId) -> Float): List<WeightedCell>? {
	var corners = mutableListOf(WeightedCell(0, 1f))
	var stride = 1
	for (axis in grid.axes) {
		val bracket = bindBracket(axis.keys, paramValue(axis.parameterId)) ?: return null
		val split = bracket.fraction > 0f && corners.size * 2 <= MAX_CORNERS
		if (split) {
			val next = ArrayList<WeightedCell>(corners.size * 2)
			for (corner in corners) {
				next.add(
					WeightedCell(
						corner.linearIndex + bracket.index * stride,
						corner.weight * (1f - bracket.fraction),
					),
				)
				next.add(
					WeightedCell(
						corner.linearIndex + (bracket.index + 1) * stride,
						corner.weight * bracket.fraction,
					),
				)
			}
			corners = next
		} else {
			// Snap (on a key, or over the corner budget): every corner takes the lower key, weight kept.
			for (cornerIndex in corners.indices) {
				val corner = corners[cornerIndex]
				corners[cornerIndex] = WeightedCell(corner.linearIndex + bracket.index * stride, corner.weight)
			}
		}
		stride *= axis.keys.size
	}
	return corners
}

/**
 * Indexes a grid's cells by their stride-folded linear index (axis `a`'s stride = Π key counts of the
 * earlier axes), so a [WeightedCell.linearIndex] from [gridCorners] resolves to the matching cell.
 *
 * A delegate to the grid's own CACHED index: the grid is immutable, and building the map per call put
 * hundreds of transient HashMaps on every scrub frame.
 *
 * @param KeyformGrid grid The grid to index.
 * @return Map<Int, KeyformCell> linear index → cell.
 */
public fun <TForm> cellsByLinearIndex(grid: KeyformGrid<TForm>): Map<Int, KeyformCell<TForm>> = grid.cellsByLinearIndex

/**
 * The drawable's grid form at the DEFAULT pose as position deltas vs the rest mesh - the shared
 * blend-shape delta reference (E5). Null when the drawable is ungridded or the default pose is out
 * of the grid's range (the reference is then zero). Static per drawable: the CPU pose prep, the
 * GPU delta-texture bake, and the MOC3 import all call this and must agree.
 *
 * @param Drawable drawable     The drawable.
 * @param Function defaultValue Default value per parameter id.
 * @return FloatArray? The interleaved reference deltas, or null.
 */
public fun meshGridDefaultDeltas(drawable: Drawable, defaultValue: (ParameterId) -> Float): FloatArray? {
	val grid = drawable.geometryGrid ?: return null
	val defaultCorners = gridCorners(grid, defaultValue) ?: return null
	val deltas = FloatArray(grid.cells.firstOrNull()?.form?.positionDeltas?.size ?: 0)
	val byLinearIndex = cellsByLinearIndex(grid)
	for (corner in defaultCorners) {
		val form = byLinearIndex[corner.linearIndex]?.form ?: continue
		for (componentIndex in deltas.indices) {
			deltas[componentIndex] += corner.weight * form.positionDeltas[componentIndex]
		}
	}
	return deltas
}

/**
 * The lattice control points grid-blended at the given pose, or null when the warp is unkeyed or
 * the pose is out of the grid's range.
 *
 * @param KeyformGrid? grid       The warp's keyform grid.
 * @param Function     paramValue Value per parameter id defining the pose.
 * @return FloatArray? The interleaved blended control points, or null.
 */
public fun warpControlPointsAt(grid: KeyformGrid<WarpLatticeForm>?, paramValue: (ParameterId) -> Float): FloatArray? {
	if (grid == null) {
		return null
	}
	val corners = gridCorners(grid, paramValue) ?: return null
	val byLinearIndex = cellsByLinearIndex(grid)
	var blended: FloatArray? = null
	for (corner in corners) {
		val form = byLinearIndex[corner.linearIndex]?.form ?: continue
		val target = blended ?: FloatArray(form.controlPoints.size).also { blended = it }
		for (componentIndex in target.indices) {
			target[componentIndex] += corner.weight * form.controlPoints[componentIndex]
		}
	}
	return blended
}

/**
 * The rotation pivot transform grid-blended at the given pose, or null when unkeyed or out of range.
 *
 * The reflection flags are NOT here: they snap to the floor cell rather than blending, so they live as
 * FLAG channels on the deformer's ChannelGrids and are read through flagAt.
 *
 * @param KeyformGrid? grid       The rotation's geometry grid.
 * @param Function     paramValue Value per parameter id defining the pose.
 * @return RotationPivotForm? The blended transform, or null.
 */
public fun rotationFormAt(grid: KeyformGrid<RotationPivotForm>?, paramValue: (ParameterId) -> Float): RotationPivotForm? {
	if (grid == null) {
		return null
	}
	val corners = gridCorners(grid, paramValue) ?: return null
	val byLinearIndex = cellsByLinearIndex(grid)
	var originX = 0f
	var originY = 0f
	var angle = 0f
	var scale = 0f
	var resolvedAnyCell = false
	for (corner in corners) {
		val form = byLinearIndex[corner.linearIndex]?.form ?: continue
		resolvedAnyCell = true
		originX += corner.weight * form.originX
		originY += corner.weight * form.originY
		angle += corner.weight * form.angle
		scale += corner.weight * form.scale
	}
	if (!resolvedAnyCell) {
		return null
	}
	return RotationPivotForm(originX, originY, angle, scale)
}