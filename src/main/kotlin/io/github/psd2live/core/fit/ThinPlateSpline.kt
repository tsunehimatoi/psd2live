package io.github.psd2live.core.fit

import kotlin.math.ln

/**
 * A thin-plate spline from [from] points to [to] points in the plane: an affine part (least squares when the points
 * alone cannot fix it) plus radial terms that bend the plane through every point exactly. With [regularization] > 0
 * it trades exactness for smoothness. Fitting a template to detected feature points with it carries every point of
 * the template - not only the feature points - onto the art, and the same spline carries every pose of the template.
 */
class ThinPlateSpline(private val from: List<Pair<Float, Float>>, to: List<Pair<Float, Float>>, regularization: Double = 0.0) {
	private val wx: DoubleArray
	private val wy: DoubleArray

	init {
		require(from.size == to.size && from.size >= 3) { "A thin-plate spline needs three or more matching points" }
		val n = from.size
		val size = n + 3
		val a = Array(size) { DoubleArray(size) }
		for (i in 0 until n) {
			for (j in 0 until n) a[i][j] = if (i == j) regularization else kernel(from[i], from[j])
			a[i][n] = 1.0; a[i][n + 1] = from[i].first.toDouble(); a[i][n + 2] = from[i].second.toDouble()
			a[n][i] = 1.0; a[n + 1][i] = from[i].first.toDouble(); a[n + 2][i] = from[i].second.toDouble()
		}
		wx = solve(a, DoubleArray(size) { if (it < n) to[it].first.toDouble() else 0.0 })
		wy = solve(a, DoubleArray(size) { if (it < n) to[it].second.toDouble() else 0.0 })
	}

	/** Where the spline carries ([x], [y]). */
	fun map(x: Float, y: Float): Pair<Float, Float> {
		val n = from.size
		var rx = wx[n] + wx[n + 1] * x + wx[n + 2] * y
		var ry = wy[n] + wy[n + 1] * x + wy[n + 2] * y
		for (i in 0 until n) {
			val k = kernel(from[i], x to y)
			rx += wx[i] * k; ry += wy[i] * k
		}
		return rx.toFloat() to ry.toFloat()
	}

	private fun kernel(a: Pair<Float, Float>, b: Pair<Float, Float>): Double {
		val dx = (a.first - b.first).toDouble(); val dy = (a.second - b.second).toDouble()
		val r2 = dx * dx + dy * dy
		return if (r2 < 1e-12) 0.0 else r2 * ln(r2) * 0.5
	}

	companion object {
		/** [a]x = [b] by Gaussian elimination with partial pivoting; a singular system gives the least-change answer zero. */
		internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
			val n = b.size
			val m = Array(n) { a[it].copyOf() }
			val v = b.copyOf()
			for (col in 0 until n) {
				var pivot = col
				for (row in col + 1 until n) if (kotlin.math.abs(m[row][col]) > kotlin.math.abs(m[pivot][col])) pivot = row
				if (kotlin.math.abs(m[pivot][col]) < 1e-12) continue
				val t = m[col]; m[col] = m[pivot]; m[pivot] = t
				val tv = v[col]; v[col] = v[pivot]; v[pivot] = tv
				for (row in 0 until n) {
					if (row == col) continue
					val f = m[row][col] / m[col][col]
					if (f == 0.0) continue
					for (k in col until n) m[row][k] -= f * m[col][k]
					v[row] -= f * v[col]
				}
			}
			return DoubleArray(n) { if (kotlin.math.abs(m[it][it]) < 1e-12) 0.0 else v[it] / m[it][it] }
		}
	}
}
