package io.github.psd2live.core.legacy

import io.github.psd2live.core.*

import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.render.eval.drawableSpaceMapping
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.PuppetModel

/**
 * Geometry a journal record holds in the space of a parent deformer the rig no longer has.
 *
 * Every deformer the journal made before a record exists again when the record replays, so a recorded parent that
 * is missing there is one the base generation no longer makes: a generation setting changed after the record (an
 * enabled skeleton drops the arm hang warps, its bones taking the arms). Such a record keeps its canvas appearance
 * at the default pose instead: its points go through the recorded parent's rest mapping - the affine map from the
 * recorded points to their canvas texture coordinates, exact for a regular rest lattice or a rigid frame - and then
 * into the space of the parent the drawable has now.
 */
internal object VanishedParent {
	/** A least-squares affine map between two interleaved point sets: x' = a x + b y + c, y' = d x + e y + f. */
	class Affine private constructor(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
		fun map(points: FloatArray) = FloatArray(points.size) { index ->
			val x = points[index - index % 2].toDouble(); val y = points[index - index % 2 + 1].toDouble()
			(if (index % 2 == 0) a * x + b * y + c else d * x + e * y + f).toFloat()
		}

		/** [base] moved by [deltas] through the linear part. */
		fun displace(base: FloatArray, deltas: FloatArray) = FloatArray(base.size) { index ->
			val x = deltas[index - index % 2].toDouble(); val y = deltas[index - index % 2 + 1].toDouble()
			(base[index] + if (index % 2 == 0) a * x + b * y else d * x + e * y).toFloat()
		}

		companion object {
			/** The fit of [to] by [from], or null for fewer than three points or a degenerate (collinear) [from]. */
			fun fit(from: FloatArray, to: FloatArray): Affine? {
				if (from.size != to.size || from.size < 6 || from.size % 2 != 0) return null
				val n = from.size / 2
				// Centred normal equations; the translation follows from the means.
				var mx = 0.0; var my = 0.0; var mu = 0.0; var mv = 0.0
				for (i in 0 until n) { mx += from[2 * i]; my += from[2 * i + 1]; mu += to[2 * i]; mv += to[2 * i + 1] }
				mx /= n; my /= n; mu /= n; mv /= n
				var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxu = 0.0; var syu = 0.0; var sxv = 0.0; var syv = 0.0
				for (i in 0 until n) {
					val x = from[2 * i] - mx; val y = from[2 * i + 1] - my; val u = to[2 * i] - mu; val v = to[2 * i + 1] - mv
					sxx += x * x; sxy += x * y; syy += y * y; sxu += x * u; syu += y * u; sxv += x * v; syv += y * v
				}
				val det = sxx * syy - sxy * sxy
				if (!(sxx > 0.0 && syy > 0.0 && det > 1e-9 * sxx * syy)) return null
				val a = (sxu * syy - syu * sxy) / det; val b = (syu * sxx - sxu * sxy) / det
				val d = (sxv * syy - syv * sxy) / det; val e = (syv * sxx - sxv * sxy) / det
				return Affine(a, b, mu - a * mx - b * my, d, e, mv - d * mx - e * my)
					.takeIf { fit -> listOf(fit.a, fit.b, fit.c, fit.d, fit.e, fit.f).all(Double::isFinite) }
			}
		}
	}

	/**
	 * The space of [drawable]'s parent in [model] at the default pose: canvas points to local positions. [drawable]
	 * need not be in [model] yet; [failure] names the error.
	 */
	class Space(model: PuppetModel, drawable: Drawable, private val failure: String) {
		private val mapping: DrawableSpaceMapping = requireNotNull(drawableSpaceMapping(
			model.copy(drawables = model.drawables.filterNot { it.id == drawable.id } + drawable), emptyMap(), drawable.id)) { failure }

		/** [canvas] points in the parent's space, each solved from [seed] where the parent is a warp. */
		fun toLocal(canvas: FloatArray, seed: FloatArray = FloatArray(canvas.size) { 0.5f }): FloatArray {
			val world = FloatArray(canvas.size) { if (it % 2 == 0) canvas[it] else -canvas[it] }
			return mapping.worldToLocalLinearized(world, seed, world, (0 until canvas.size / 2).toSet()).also {
				require(it.all(Float::isFinite)) { failure }
			}
		}
	}
}
