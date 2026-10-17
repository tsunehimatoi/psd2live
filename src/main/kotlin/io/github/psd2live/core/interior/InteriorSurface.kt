package io.github.psd2live.core.interior

import io.github.psd2live.core.mesh.cross
import kotlin.math.*

/** Independent absolute gains, in units of 100%. Contour is 0 = free, 1 = retained. */
internal data class InteriorSurfaceSettings(
	val horizontal: Float = 1f,
	val vertical: Float = 1f,
	val bulge: Float = .85f,
	val press: Float = 1f,
	val folds: Float = .85f,
	val contour: Float = 1f,
) {
	init {
		require(
			listOf(horizontal, vertical, bulge, press, folds).all { it.isFinite() && it in 0f..2f }
		) {
			"Invalid interior surface amounts"
		}
		require(contour.isFinite() && contour in 0f..1f) { "Invalid interior contour retention" }
	}
}

/** Static longitudinal-arch material projection. No input timing or independent physics state. */
internal object InteriorSurface {
	private const val SAMPLES = 256

	private class Section(height: Double, skew: Double) {
		val x = DoubleArray(SAMPLES + 1)
		val arc = DoubleArray(SAMPLES + 1)

		init {
			var previousZ = 0.0
			for (i in 0..SAMPLES) {
				val t = i.toDouble() / SAMPLES
				val arch = 4 * t * (1 - t)
				x[i] = t + skew * arch
				val z = height * arch
				if (i > 0) arc[i] = arc[i - 1] + hypot(x[i] - x[i - 1], z - previousZ)
				previousZ = z
			}
			val length = arc.last()
			for (i in arc.indices) arc[i] /= length
		}
	}

	private fun lookup(domain: DoubleArray, values: DoubleArray, t: Double): Double {
		if (t <= 0) return values.first()
		if (t >= 1) return values.last()
		var a = 0
		var b = domain.lastIndex
		while (b - a > 1) {
			val m = (a + b) / 2
			if (domain[m] < t) a = m else b = m
		}
		val f = (t - domain[a]) / max(domain[b] - domain[a], 1e-15)
		return values[a] + (values[b] - values[a]) * f
	}

	private fun projection(
		bulge: Float,
		press: Float,
		input: Double,
		local: Boolean = false,
	): DoubleArray {
		if (input == 0.0 || bulge == 0f || press == 0f) return DoubleArray(SAMPLES + 1)
		val height = (if (local) .30 else .38) * bulge.toDouble()
		val rest = Section(height, 0.0)
		val current =
			Section(height, .235 * tanh(input * press.toDouble() * bulge.toDouble() * 1.4))
		return DoubleArray(SAMPLES + 1) { i ->
			val u = i.toDouble() / SAMPLES
			if (i == 0 || i == SAMPLES) 0.0
			else lookup(current.arc, current.x, lookup(rest.x, rest.arc, u)) - u
		}
	}

	private fun sample(values: DoubleArray, u: Double): Double {
		val f = u.coerceIn(0.0, 1.0) * SAMPLES
		val i = min(SAMPLES - 1, floor(f).toInt())
		return values[i] + (values[i + 1] - values[i]) * (f - i)
	}

	class Frame
	internal constructor(
		private val source: FloatArray,
		private val raw: DoubleArray,
		val safetyGain: Double,
		val movableVertices: Int,
	) {
		fun positions() = FloatArray(source.size) { (source[it] + raw[it] * safetyGain).toFloat() }

		/** Diagnostics compare the actual material residual before a common area constraint. */
		fun rawDisplacements() = raw.copyOf()

		fun precisePositions() = DoubleArray(source.size) { source[it] + raw[it] * safetyGain }
	}

	class Plan
	internal constructor(
		private val mesh: InteriorMesh,
		private val binding: InteriorMaterialBinding,
		private val settings: InteriorSurfaceSettings,
	) {
		private val source = mesh.positions()

		fun evaluate(horizontal: Float, vertical: Float): Frame {
			require(
				horizontal.isFinite() &&
					vertical.isFinite() &&
					horizontal in -1f..1f &&
					vertical in -1f..1f
			) {
				"Interior pose must be within -1..1"
			}
			val offsets = DoubleArray(source.size)
			val movable = binding.interior.count { it > 0 }
			if (
				(horizontal == 0f && vertical == 0f) ||
					settings.bulge == 0f ||
					settings.press == 0f ||
					(settings.horizontal == 0f && settings.vertical == 0f)
			)
				return Frame(source, offsets, 1.0, movable)
			val px = projection(settings.bulge, settings.press, horizontal.toDouble())
			val localX =
				projection(settings.bulge, settings.press, horizontal.toDouble(), local = true)
			val hasPitch = vertical != 0f && settings.vertical != 0f
			val py =
				if (hasPitch) projection(settings.bulge, settings.press, vertical.toDouble())
				else null
			val localY =
				if (hasPitch)
					projection(settings.bulge, settings.press, vertical.toDouble(), local = true)
				else null
			val fold = DoubleArray(source.size)
			val pitchFold = DoubleArray(source.size)
			val mass = DoubleArray(mesh.vertexCount)
			val pitchMass = DoubleArray(mesh.vertexCount)
			for (f in binding.folds) for (b in f.bindings) {
				// The reference X channel follows the authored curve normal. Reversing a curve
				// deliberately reverses that local preference; do not silently symmetrize it.
				if (!f.pitchOnly) {
					val delta = sample(localX, b.u) * f.diameter * f.amount
					fold[b.vertex * 2] += delta * b.nx * b.weight
					fold[b.vertex * 2 + 1] += delta * b.ny * b.weight
					mass[b.vertex] += b.weight
				}
				if (localY != null) {
					val dot = b.nx * binding.alongX + b.ny * binding.alongY
					val sign = if (dot >= 0) 1.0 else -1.0
					val d =
						sample(localY, if (sign > 0) b.u else 1 - b.u) *
							f.diameter *
							dot *
							dot *
							sign *
							f.amount
					pitchFold[b.vertex * 2] += d * b.nx * b.weight
					pitchFold[b.vertex * 2 + 1] += d * b.ny * b.weight
					pitchMass[b.vertex] += b.weight
				}
			}
			val c = settings.contour.toDouble()
			for (i in 0 until mesh.vertexCount) {
				val weight = settings.horizontal.toDouble() * (1 - c * (1 - binding.interior[i]))
				val verticalWeight =
					settings.vertical.toDouble() * (1 - c * (1 - binding.interior[i]))
				val localEdge = 1 - c * (1 - binding.edge[i])
				val cross =
					(1 - c) * sample(px, binding.u[i]) * binding.span +
						c * sample(px, binding.rowU[i]) * binding.rowSpan[i]
				val normalizer = max(1.0, mass[i])
				offsets[i * 2] =
					(binding.acrossX * cross +
						fold[i * 2] / normalizer * settings.folds.toDouble() * localEdge) * weight
				offsets[i * 2 + 1] =
					(binding.acrossY * cross +
						fold[i * 2 + 1] / normalizer * settings.folds.toDouble() * localEdge) *
						weight
				if (py != null) {
					val aspect = min(1.0, binding.span / binding.length)
					val d =
						((1 - c) * sample(py, binding.alongU[i]) * binding.length +
							c * sample(py, binding.columnU[i]) * binding.columnSpan[i]) *
							aspect *
							verticalWeight
					offsets[i * 2] += binding.alongX * d
					offsets[i * 2 + 1] += binding.alongY * d
					val gain =
						settings.folds.toDouble() * localEdge * verticalWeight /
							max(1.0, pitchMass[i])
					offsets[i * 2] += pitchFold[i * 2] * gain
					offsets[i * 2 + 1] += pitchFold[i * 2 + 1] * gain
				}
			}
			fun permitted(gain: Double): Boolean =
				mesh.triangles.all { t ->
					val ax = source[t.a * 2] + offsets[t.a * 2] * gain
					val ay = source[t.a * 2 + 1] + offsets[t.a * 2 + 1] * gain
					val bx = source[t.b * 2] + offsets[t.b * 2] * gain
					val by = source[t.b * 2 + 1] + offsets[t.b * 2 + 1] * gain
					val cx = source[t.c * 2] + offsets[t.c * 2] * gain
					val cy = source[t.c * 2 + 1] + offsets[t.c * 2 + 1] * gain
					val ratio =
						((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) /
							cross(mesh.points[t.a], mesh.points[t.b], mesh.points[t.c])
					ratio in .4..2.4
				}
			var gain = 1.0
			if (!permitted(1.0)) {
				if (hasPitch) {
					for (t in mesh.triangles) {
						val a = mesh.points[t.a]
						val b = mesh.points[t.b]
						val d = mesh.points[t.c]
						val area = cross(a, b, d)
						val dx = offsets[t.b * 2] - offsets[t.a * 2]
						val dy = offsets[t.b * 2 + 1] - offsets[t.a * 2 + 1]
						val ex = offsets[t.c * 2] - offsets[t.a * 2]
						val ey = offsets[t.c * 2 + 1] - offsets[t.a * 2 + 1]
						val linear =
							((b.x - a.x) * ey + dx * (d.y - a.y) -
								(b.y - a.y) * ex -
								dy * (d.x - a.x)) / area
						val quadratic = (dx * ey - dy * ex) / area
						gain = min(gain, firstRoot(quadratic, linear, .6))
						gain = min(gain, firstRoot(quadratic, linear, -1.4))
					}
					gain = max(0.0, gain * (1 - 1e-10))
				} else {
					var low = 0.0
					var high = 1.0
					repeat(15) {
						val mid = (low + high) / 2
						if (permitted(mid)) low = mid else high = mid
					}
					gain = low
				}
			}
			require(offsets.all(Double::isFinite)) { "Interior shape exceeds mesh precision" }
			return Frame(source, offsets, gain, movable)
		}
	}

	private fun firstRoot(a: Double, b: Double, c: Double): Double {
		if (abs(a) < 1e-14)
			return if (abs(b) > 1e-14 && -c / b > 0) -c / b else Double.POSITIVE_INFINITY
		val discriminant = b * b - 4 * a * c
		if (discriminant < 0) return Double.POSITIVE_INFINITY
		val q = -.5 * (b + if (b >= 0) sqrt(discriminant) else -sqrt(discriminant))
		val r1 = q / a
		val r2 = if (q == 0.0) Double.POSITIVE_INFINITY else c / q
		return min(
			if (r1 > 0) r1 else Double.POSITIVE_INFINITY,
			if (r2 > 0) r2 else Double.POSITIVE_INFINITY,
		)
	}

	fun compile(
		mesh: InteriorMesh,
		raster: InteriorRaster,
		settings: InteriorSurfaceSettings = InteriorSurfaceSettings(),
		guides: List<InteriorGuide> = emptyList(),
	): Plan = Plan(mesh, InteriorSurfaceCompiler.compile(mesh, raster, guides), settings)
}
