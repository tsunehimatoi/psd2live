package org.umamo.render.eval

import kotlin.math.cos
import kotlin.math.sin

// The float pi the Umamo C++ Runtime uses (matches its sincosf bit-for-bit)
internal const val PI_F = 3.1415927f

/**
 * In-grid warp transform: maps a child point `(u, v)` inside the lattice `[0,1)²` to world via the
 * deformed control points [cp] - `(cols+1)×(rows+1)` points, interleaved x,y. [bilinear] true does the
 * quad (bilinear) blend of the 4 surrounding control points, false the triangle split. Writes the
 * result to `out[outIndex]`,`out[outIndex+1]`; returns false (and writes nothing) when `(u, v)` is
 * outside the grid.
 *
 * @param FloatArray cp       Control points, `(cols+1)*(rows+1)` interleaved x,y.
 * @param Int        cols     Grid columns.
 * @param Int        rows     Grid rows.
 * @param Boolean    bilinear Quad blend (true) vs triangle split (false).
 * @param Float      u        Normalized horizontal coordinate.
 * @param Float      v        Normalized vertical coordinate.
 * @param FloatArray out      Destination for the transformed point.
 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
 * @return Boolean true when in grid (and written), false when outside.
 */
internal fun warpInGrid(
	cp: FloatArray,
	cols: Int,
	rows: Int,
	bilinear: Boolean,
	u: Float,
	v: Float,
	out: FloatArray,
	outIndex: Int,
): Boolean {
	if (u < 0f || v < 0f || u >= 1f || v >= 1f) {
		return false
	}
	val pointsPerRow = cols + 1
	val gridU = cols * u
	val gridV = rows * v
	val cellU = gridU - gridU.toInt()
	val cellV = gridV - gridV.toInt()
	val cellIndex = gridV.toInt() * pointsPerRow + gridU.toInt()
	val p00 = cellIndex * 2
	val p10 = (cellIndex + 1) * 2
	val p01 = (cellIndex + pointsPerRow) * 2
	val p11 = (cellIndex + pointsPerRow + 1) * 2
	if (!bilinear) {
		if (cellU + cellV <= 1f) {
			out[outIndex] = cp[p10] * cellU + cp[p00] * (1 - cellU - cellV) + cp[p01] * cellV
			out[outIndex + 1] = (1 - cellU - cellV) * cp[p00 + 1] + cp[p10 + 1] * cellU + cp[p01 + 1] * cellV
		} else {
			val weight = (cellU - 1) + cellV
			out[outIndex] = cp[p01] * (1 - cellU) + cp[p11] * weight + cp[p10] * (1 - cellV)
			out[outIndex + 1] = weight * cp[p11 + 1] + (1 - cellU) * cp[p01 + 1] + (1 - cellV) * cp[p10 + 1]
		}
	} else {
		val invCellV = 1 - cellV
		out[outIndex] =
			cp[p10] * cellU * invCellV + cp[p00] * (1 - cellU) * invCellV + cp[p01] * (1 - cellU) * cellV + cp[p11] * cellU * cellV
		out[outIndex + 1] =
			invCellV * cp[p10 + 1] * cellU + cp[p00 + 1] * (1 - cellU) * invCellV + (1 - cellU) * cp[p01 + 1] * cellV + cellU * cp[p11 + 1] * cellV
	}
	return true
}

/**
 * Out-of-grid warp extrapolation: a pure affine far outside the lattice, edge-blended near the border
 * (mode-independent). Assumes `(u, v)` is outside `[0,1)²` (call only after [warpInGrid] returned
 * false).
 *
 * @param FloatArray cp       Control points, `(cols+1)*(rows+1)` interleaved x,y.
 * @param Int        cols     Grid columns.
 * @param Int        rows     Grid rows.
 * @param Float      u        Normalized horizontal coordinate (outside `[0,1)`).
 * @param Float      v        Normalized vertical coordinate (outside `[0,1)`).
 * @param FloatArray out      Destination for the transformed point.
 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
 */
internal fun warpExtrap(cp: FloatArray, cols: Int, rows: Int, u: Float, v: Float, out: FloatArray, outIndex: Int) {
	val pointsPerRow = cols + 1
	val lastRowBase = rows * pointsPerRow
	val topLeftX = cp[0]
	val topLeftY = cp[1]
	val topRightX = cp[cols * 2]
	val topRightY = cp[cols * 2 + 1]
	val botLeftX = cp[lastRowBase * 2]
	val botLeftY = cp[lastRowBase * 2 + 1]
	val botRightX = cp[(cols + lastRowBase) * 2]
	val botRightY = cp[(cols + lastRowBase) * 2 + 1]
	val gridU = cols * u
	val gridV = rows * v
	val dUx = ((botRightX - topLeftX) + (topRightX - botLeftX)) * 0.5f
	val dVx = ((botRightX - topLeftX) - (topRightX - botLeftX)) * 0.5f
	val dUy = ((botRightY - topLeftY) + (topRightY - botLeftY)) * 0.5f
	val dVy = ((botRightY - topLeftY) - (topRightY - botLeftY)) * 0.5f
	val originX = (topLeftX + topRightX + botLeftX + botRightX) * 0.25f - (botRightX - topLeftX) * 0.5f
	val originY = (topLeftY + topRightY + botLeftY + botRightY) * 0.25f - (botRightY - topLeftY) * 0.5f
	if (u <= -2f || u >= 3f || v <= -2f || v >= 3f) {
		out[outIndex] = dUx * u + originX + dVx * v
		out[outIndex + 1] = dVy * v + dUy * u + originY
		return
	}
	var blendU = 0f
	var blendV = 0f
	var corner0x = 0f
	var corner0y = 0f
	var corner1x = 0f
	var corner1y = 0f
	var corner2x = 0f
	var corner2y = 0f
	var corner3x = 0f
	var corner3y = 0f
	if (u <= 0f) {
		blendU = (u + 2f) * 0.5f
		corner3x = originX - 2f * dUx
		corner3y = originY - 2f * dUy
		if (v <= 0f) {
			corner2x = topLeftX
			corner2y = topLeftY
			blendV = (v + 2f) * 0.5f
			corner1x = originX - 2f * dVx
			corner0x = corner3x - 2f * dVx
			corner1y = originY - 2f * dVy
			corner0y = corner3y - 2f * dVy
		} else if (v < 1f) {
			var loIdx = gridV.toInt()
			val edgeHi: Float
			val hiIdx: Int
			if (rows == loIdx) {
				loIdx = rows - 1
				hiIdx = rows
				edgeHi = rows.toFloat()
			} else {
				hiIdx = loIdx + 1
				edgeHi = (loIdx + 1).toFloat()
			}
			blendV = gridV - loIdx
			val edgeLo = loIdx.toFloat() / rows
			corner1x = cp[loIdx * pointsPerRow * 2]
			corner1y = cp[loIdx * pointsPerRow * 2 + 1]
			corner2x = cp[hiIdx * pointsPerRow * 2]
			corner2y = cp[hiIdx * pointsPerRow * 2 + 1]
			corner0x = edgeLo * dVx + corner3x
			corner0y = edgeLo * dVy + corner3y
			corner3x = (edgeHi / rows) * dVx + corner3x
			corner3y = (edgeHi / rows) * dVy + corner3y
		} else {
			corner1x = botLeftX
			corner1y = botLeftY
			blendV = (v - 1f) * 0.5f
			corner0x = corner3x + dVx
			corner0y = corner3y + dVy
			corner3x = 3f * dVx + corner3x
			corner2x = 3f * dVx + originX
			corner3y = 3f * dVy + corner3y
			corner2y = 3f * dVy + originY
		}
	} else if (u < 1f) {
		if (v <= 0f) {
			var loIdx = gridU.toInt()
			val edgeX: Float
			val edgeY: Float
			val edgeHi: Float
			if (cols == loIdx) {
				loIdx = cols - 1
				edgeX = topRightX
				edgeY = topRightY
				edgeHi = cols.toFloat()
			} else {
				edgeX = cp[(loIdx + 1) * 2]
				edgeY = cp[(loIdx + 1) * 2 + 1]
				edgeHi = (loIdx + 1).toFloat()
			}
			corner2y = edgeY
			blendV = (v + 2f) * 0.5f
			blendU = gridU - loIdx
			val edgeLo = loIdx.toFloat() / cols
			corner3x = cp[loIdx * 2]
			corner3y = cp[loIdx * 2 + 1]
			corner2x = edgeX
			corner0x = (edgeLo * dUx + originX) - 2f * dVx
			corner0y = (edgeLo * dUy + originY) - 2f * dVy
			corner1x = ((edgeHi / cols) * dUx + originX) - 2f * dVx
			corner1y = ((edgeHi / cols) * dUy + originY) - 2f * dVy
		} else if (v >= 1f) {
			var loIdx = gridU.toInt()
			val edgeHi: Float
			val hiIdx: Int
			if (cols == loIdx) {
				loIdx = cols - 1
				hiIdx = cols
				edgeHi = cols.toFloat()
			} else {
				hiIdx = loIdx + 1
				edgeHi = (loIdx + 1).toFloat()
			}
			blendV = (v - 1f) * 0.5f
			blendU = gridU - loIdx
			val edgeLo = loIdx.toFloat() / cols
			corner0x = cp[(loIdx + lastRowBase) * 2]
			corner0y = cp[(loIdx + lastRowBase) * 2 + 1]
			corner1x = cp[(lastRowBase + hiIdx) * 2]
			corner1y = cp[(lastRowBase + hiIdx) * 2 + 1]
			corner2x = (edgeHi / cols) * dUx + originX + 3f * dVx
			corner3x = edgeLo * dUx + originX + 3f * dVx
			corner3y = edgeLo * dUy + originY + 3f * dVy
			corner2y = (edgeHi / cols) * dUy + originY + 3f * dVy
		} else {
			out[outIndex] = originX + dUx * u + dVx * v
			out[outIndex + 1] = originY + dVy * v + dUy * u
			return
		}
	} else {
		blendU = (u - 1f) * 0.5f
		corner2x = 3f * dUx + originX
		corner2y = 3f * dUy + originY
		if (v <= 0f) {
			corner3x = topRightX
			corner3y = topRightY
			blendV = (v + 2f) * 0.5f
			corner0x = (originX + dUx) - 2f * dVx
			corner1x = corner2x - 2f * dVx
			corner1y = corner2y - 2f * dVy
			corner0y = (originY + dUy) - 2f * dVy
		} else if (v < 1f) {
			var loIdx = gridV.toInt()
			val edgeHi: Float
			val hiIdx: Int
			if (rows == loIdx) {
				loIdx = rows - 1
				hiIdx = rows
				edgeHi = rows.toFloat()
			} else {
				hiIdx = loIdx + 1
				edgeHi = (loIdx + 1).toFloat()
			}
			blendV = gridV - loIdx
			corner0x = cp[(loIdx * pointsPerRow + cols) * 2]
			corner0y = cp[(loIdx * pointsPerRow + cols) * 2 + 1]
			corner3x = cp[(hiIdx * pointsPerRow + cols) * 2]
			corner3y = cp[(hiIdx * pointsPerRow + cols) * 2 + 1]
			val edgeLo = loIdx.toFloat() / rows
			corner1x = edgeLo * dVx + corner2x
			corner1y = edgeLo * dVy + corner2y
			corner2x = (edgeHi / rows) * dVx + corner2x
			corner2y = (edgeHi / rows) * dVy + corner2y
		} else {
			corner0x = botRightX
			corner0y = botRightY
			blendV = (v - 1f) * 0.5f
			corner1x = corner2x + dVx
			corner1y = corner2y + dVy
			corner3x = originX + dUx + 3f * dVx
			corner2x = 3f * dVx + corner2x
			corner3y = originY + dUy + 3f * dVy
			corner2y = 3f * dVy + corner2y
		}
	}
	if (blendU + blendV <= 1f) {
		out[outIndex] = (corner1x - corner0x) * blendU + corner0x + (corner3x - corner0x) * blendV
		out[outIndex + 1] = (corner1y - corner0y) * blendU + corner0y + (corner3y - corner0y) * blendV
	} else {
		out[outIndex] = (corner3x - corner2x) * (1f - blendU) + corner2x + (corner1x - corner2x) * (1f - blendV)
		out[outIndex + 1] = (1f - blendU) * (corner3y - corner2y) + corner2y + (1f - blendV) * (corner1y - corner2y)
	}
}

/**
 * Warp-transforms `(u, v)` through [cp]: the in-grid fast path, falling back to extrapolation outside.
 *
 * @param FloatArray cp       Control points, `(cols+1)*(rows+1)` interleaved x,y.
 * @param Int        cols     Grid columns.
 * @param Int        rows     Grid rows.
 * @param Boolean    bilinear Quad blend (true) vs triangle split (false).
 * @param Float      u        Normalized horizontal coordinate.
 * @param Float      v        Normalized vertical coordinate.
 * @param FloatArray out      Destination for the transformed point.
 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
 */
internal fun warpApply(
	cp: FloatArray,
	cols: Int,
	rows: Int,
	bilinear: Boolean,
	u: Float,
	v: Float,
	out: FloatArray,
	outIndex: Int,
) {
	if (!warpInGrid(cp, cols, rows, bilinear, u, v, out, outIndex)) {
		warpExtrap(cp, cols, rows, u, v, out, outIndex)
	}
}

/**
 * A rotation deformer's baked world affine - the per-vertex-invariant coefficients (xformSetup's exact
 * grouping), applied as `x' = c15·y + c12·x + ox`, `y' = c13·y + c14·x + oy`.
 */
internal class RotationXform(
	val c12: Float,
	val c13: Float,
	val c14: Float,
	val c15: Float,
	val ox: Float,
	val oy: Float,
) {
	/**
	 * Applies the affine to point `(x, y)`, writing the result to `out[outIndex]`,`out[outIndex+1]`.
	 *
	 * @param Float      x        Input x.
	 * @param Float      y        Input y.
	 * @param FloatArray out      Destination for the transformed point.
	 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
	 */
	fun apply(x: Float, y: Float, out: FloatArray, outIndex: Int) {
		out[outIndex] = c15 * y + c12 * x + ox
		out[outIndex + 1] = y * c13 + x * c14 + oy
	}
}

/**
 * Builds a [RotationXform] from a rotation deformer's world angle (degrees), scale, reflection flags,
 * and origin.
 *
 * @param Float   angleDegrees World rotation angle in degrees.
 * @param Float   scale        World scale.
 * @param Boolean flipX        Reflect across the vertical axis.
 * @param Boolean flipY        Reflect across the horizontal axis.
 * @param Float   ox           World origin x.
 * @param Float   oy           World origin y.
 * @return RotationXform The baked affine.
 */
internal fun rotationXform(
	angleDegrees: Float,
	scale: Float,
	flipX: Boolean,
	flipY: Boolean,
	ox: Float,
	oy: Float,
): RotationXform {
	val radians = angleDegrees * PI_F / 180f
	val sn = sin(radians.toDouble()).toFloat()
	val cs = cos(radians.toDouble()).toFloat()
	val fx = if (flipX) -1f else 1f
	val fy = if (flipY) -1f else 1f
	return RotationXform(
		c12 = cs * scale * fx,
		c13 = fy * cs * scale,
		c14 = fx * scale * sn,
		c15 = -sn * scale * fy,
		ox = ox,
		oy = oy,
	)
}