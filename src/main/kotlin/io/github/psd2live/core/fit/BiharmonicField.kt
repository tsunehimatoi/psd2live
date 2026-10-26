package io.github.psd2live.core.fit

import kotlin.math.floor

/**
 * A smooth displacement field on a regular lattice, given only where it must be: the nodes in [fixed] take their
 * value, every other node the value that bends the field least (the discrete bi-Laplacian is zero there), and the
 * lattice is surrounded by a ring held at zero. So a displacement set along a lid's edge spreads into the skin above
 * it over several rows, easing out to nothing at the lattice border, instead of stretching one row of the mesh.
 *
 * The lattice has [columns] × [rows] nodes over [left]..[right] × [top]..[bottom]; [field] holds the solved x and y
 * displacement of each node, row by row.
 */
class BiharmonicField(
	val columns: Int, val rows: Int,
	val left: Float, val top: Float, val right: Float, val bottom: Float,
	fixed: Map<Int, Pair<Float, Float>>,
) {
	val field: Array<FloatArray> = Array(2) { FloatArray(columns * rows) }

	init {
		require(columns >= 3 && rows >= 3) { "A field needs at least 3 × 3 nodes" }
		for (axis in 0..1) solve(axis, fixed)
	}

	fun index(column: Int, row: Int) = row * columns + column
	fun x(column: Int) = left + (right - left) * column / (columns - 1)
	fun y(row: Int) = top + (bottom - top) * row / (rows - 1)

	/** The node nearest ([x], [y]), clamped to the lattice. */
	fun nearest(x: Float, y: Float): Int {
		val column = Math.round((x - left) / (right - left) * (columns - 1)).coerceIn(0, columns - 1)
		val row = Math.round((y - top) / (bottom - top) * (rows - 1)).coerceIn(0, rows - 1)
		return index(column, row)
	}

	/** The field at ([x], [y]) by bilinear interpolation; zero outside the lattice. */
	fun at(x: Float, y: Float): Pair<Float, Float> {
		val fx = (x - left) / (right - left) * (columns - 1)
		val fy = (y - top) / (bottom - top) * (rows - 1)
		if (fx < 0f || fy < 0f || fx > columns - 1 || fy > rows - 1) return 0f to 0f
		val c = floor(fx).toInt().coerceAtMost(columns - 2); val r = floor(fy).toInt().coerceAtMost(rows - 2)
		val tx = fx - c; val ty = fy - r
		fun lerp(axis: Int): Float {
			val f = field[axis]
			val top = f[index(c, r)] * (1 - tx) + f[index(c + 1, r)] * tx
			val bottom = f[index(c, r + 1)] * (1 - tx) + f[index(c + 1, r + 1)] * tx
			return top * (1 - ty) + bottom * ty
		}
		return lerp(0) to lerp(1)
	}

	/**
	 * Minimizes |L d|² over the free nodes, L the lattice Laplacian with the outside held at zero, by conjugate
	 * gradients on the normal equations LᵀL d = -LᵀL d_fixed.
	 */
	private fun solve(axis: Int, fixed: Map<Int, Pair<Float, Float>>) {
		val n = columns * rows
		val value = DoubleArray(n)
		val isFixed = BooleanArray(n)
		for ((node, v) in fixed) { isFixed[node] = true; value[node] = (if (axis == 0) v.first else v.second).toDouble() }
		fun laplacian(d: DoubleArray): DoubleArray {
			val out = DoubleArray(n)
			for (r in 0 until rows) for (c in 0 until columns) {
				val i = index(c, r)
				var s = -4.0 * d[i]
				if (c > 0) s += d[i - 1]; if (c < columns - 1) s += d[i + 1]
				if (r > 0) s += d[i - columns]; if (r < rows - 1) s += d[i + columns]
				out[i] = s
			}
			return out
		}
		// The operator is symmetric, so LᵀL is L applied twice.
		fun normal(d: DoubleArray): DoubleArray = laplacian(laplacian(d))
		val rhs = normal(value).also { for (i in 0 until n) it[i] = if (isFixed[i]) 0.0 else -it[i] }
		val x = DoubleArray(n)
		fun apply(d: DoubleArray): DoubleArray {
			val masked = DoubleArray(n) { if (isFixed[it]) 0.0 else d[it] }
			return normal(masked).also { for (i in 0 until n) if (isFixed[i]) it[i] = 0.0 }
		}
		val r = rhs.copyOf()
		val p = r.copyOf()
		var rr = r.sumOf { it * it }
		val tolerance = 1e-10 * (rhs.sumOf { it * it }.coerceAtLeast(1e-30))
		var iteration = 0
		while (rr > tolerance && iteration < 4 * n) {
			val ap = apply(p)
			val pap = p.indices.sumOf { p[it] * ap[it] }
			if (pap <= 0.0) break
			val alpha = rr / pap
			for (i in 0 until n) { x[i] += alpha * p[i]; r[i] -= alpha * ap[i] }
			val next = r.sumOf { it * it }
			val beta = next / rr
			for (i in 0 until n) p[i] = r[i] + beta * p[i]
			rr = next
			iteration++
		}
		val out = field[axis]
		for (i in 0 until n) out[i] = (if (isFixed[i]) value[i] else x[i]).toFloat()
	}
}
