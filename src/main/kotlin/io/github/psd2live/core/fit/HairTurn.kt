package io.github.psd2live.core.fit

import kotlin.math.abs
import kotlin.math.exp

/**
 * The hair turning with the head by the style's role fields ([TurnStyle], [FaceTurn.Pose.hair]). Hair is seldom split
 * the way its roles move, and its drawing does not say where a strand runs, so each piece is read from its own shape:
 *
 * - **Down a strand** ([v]): 0 where the piece starts in its column (the root, at the scalp) to 1 where it ends there
 *   (the tip), smoothed across the columns so neighbouring strands never step apart.
 * - **Its role, by where it lies** against the face: in front, the bangs over the forehead's middle, the side locks
 *   beside the face, the crown above the hairline and what hangs well below the chin; behind, the head's back and the
 *   long hair below it. The roles blend softly, so one piece carries their different moves without being cut.
 *
 * Each role moves its points against the face's middle by a smooth field over across and down - its root's move, its
 * bend toward the tip, its squeeze across and its sides going apart, and its tilt - in the face's width. A role the
 * style has nothing for takes its neighbour's (the crown the bangs', what hangs the side locks'). Below the chin the
 * hair hangs on the body and goes less.
 */
object HairTurn {
	private val FALLBACK = mapOf("crown" to "bangs", "tail" to "side", "back_tail" to "back")
	/** How far across from a role's middle its field reaches; a piece wider than that holds to it. */
	private const val REACH = 0.75f

	private fun sig(x: Float, w: Float) = 1f / (1f + exp(-x / w.coerceAtLeast(1e-6f)))

	/** [positions] (x, y pairs) of one hair piece, [front] of the face or behind it, placed at [pose]. */
	fun place(positions: FloatArray, front: Boolean, turn: FaceTurn, pose: FaceTurn.Pose): FloatArray {
		val n = positions.size / 2
		if (n == 0) return positions.copyOf()
		val f = turn.unit; val length = (turn.chin - turn.eyeLine).coerceAtLeast(1f)
		val v = down(positions)
		val weights = roles(positions, front, turn)
		val moved = FloatArray(positions.size); val total = FloatArray(n)
		for ((role, weight) in weights) {
			if (weight.max() < 1e-3f) continue
			val r = if (TurnStyle.HAIR_PARAMS.all { abs(pose.hair["hair_${role}_$it"]) < 1e-9f }) FALLBACK[role] ?: role else role
			val p = TurnStyle.HAIR_PARAMS.map { pose.hair["hair_${r}_$it"] }
			var sum = 0f; var sw = 0f
			for (i in 0 until n) { sum += weight[i] * (positions[i * 2] - turn.middle) / f; sw += weight[i] }
			val uc = sum / sw.coerceAtLeast(1e-6f)
			for (i in 0 until n) {
				val x = positions[i * 2]; val y = positions[i * 2 + 1]; val vv = v[i]
				val u = uc + ((x - turn.middle) / f - uc).coerceIn(-REACH, REACH)
				val w = (u - uc) * pose.sx
				val dx = (p[0] + p[1] * vv + p[2] * vv * vv + p[3] * w + p[4] * w * w + p[5] * w * vv) * pose.sx * f + pose.slide.first
				val dy = (p[6] + p[7] * vv + p[8] * vv * vv + p[9] * w + p[10] * w * vv) * f + pose.slide.second
				moved[i * 2] += weight[i] * (x + dx); moved[i * 2 + 1] += weight[i] * (y + dy); total[i] += weight[i]
			}
		}
		return FloatArray(positions.size) { k ->
			val i = k / 2; val rest = positions[k]
			if (total[i] < 1e-6f) return@FloatArray rest
			val below = ((positions[i * 2 + 1] - turn.chin) / length).coerceIn(0f, 1f)
			val fade = 1f - 0.6f * below * below * (3f - 2f * below)
			rest + (moved[k] / total[i] - rest) * fade
		}
	}

	/** Each point's role weights by where it lies against the face. */
	private fun roles(positions: FloatArray, front: Boolean, turn: FaceTurn): Map<String, FloatArray> {
		val n = positions.size / 2; val length = turn.chin - turn.eyeLine
		val u = FloatArray(n) { abs(positions[it * 2] - turn.middle) / turn.unit }; val y = FloatArray(n) { positions[it * 2 + 1] }
		if (front) {
			val tail = FloatArray(n) { sig(y[it] - (turn.chin + 0.35f * length), 0.25f * length) }
			val crown = FloatArray(n) { (1f - tail[it]) * sig((turn.top + 0.15f * length) - y[it], 0.06f * length) }
			val bangs = FloatArray(n) { (1f - tail[it] - crown[it]) * sig(0.32f - u[it], 0.12f) }
			val side = FloatArray(n) { 1f - tail[it] - crown[it] - bangs[it] }
			return mapOf("bangs" to bangs, "side" to side, "crown" to crown, "tail" to tail)
		}
		val hang = FloatArray(n) { sig(y[it] - (turn.chin + 0.8f * length), 0.15f * length) }
		return mapOf("back" to FloatArray(n) { 1f - hang[it] }, "back_tail" to hang)
	}

	/** Each point's place down its strand: 0 where the piece starts in its column, 1 where it ends. */
	private fun down(positions: FloatArray): FloatArray {
		val n = positions.size / 2
		val xs = FloatArray(n) { positions[it * 2] }; val ys = FloatArray(n) { positions[it * 2 + 1] }
		val bins = 24
		val lo = xs.min() - 1e-3f; val hi = xs.max() + 1e-3f; val step = (hi - lo) / bins
		val columns = Array(bins) { ArrayList<Float>() }
		for (i in 0 until n) columns[((xs[i] - lo) / step).toInt().coerceIn(0, bins - 1)] += ys[i]
		val top = FloatArray(bins) { Float.NaN }; val bottom = FloatArray(bins) { Float.NaN }
		for (b in 0 until bins) if (columns[b].isNotEmpty()) { val s = columns[b].sorted(); top[b] = percentile(s, 3f); bottom[b] = percentile(s, 97f) }
		fill(top); fill(bottom)
		val kernel = floatArrayOf(1f, 2f, 3f, 2f, 1f)
		fun smooth(a: FloatArray) = FloatArray(bins) { b -> var s = 0f; for (k in -2..2) s += kernel[k + 2] * a[(b + k).coerceIn(0, bins - 1)]; s / 9f }
		val t = smooth(top); val bo = smooth(bottom)
		return FloatArray(n) { i ->
			// Read continuously along x between the columns' middles.
			val c = ((xs[i] - lo) / step - 0.5f).coerceIn(0f, bins - 1f)
			val b0 = c.toInt().coerceAtMost(bins - 2); val k = c - b0
			val ti = t[b0] + (t[b0 + 1] - t[b0]) * k; val bi = bo[b0] + (bo[b0 + 1] - bo[b0]) * k
			((ys[i] - ti) / (bi - ti).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
		}
	}

	private fun percentile(sorted: List<Float>, q: Float): Float {
		val pos = (sorted.size - 1) * q / 100f; val i = pos.toInt().coerceAtMost(sorted.size - 1); val j = (i + 1).coerceAtMost(sorted.size - 1)
		return sorted[i] + (sorted[j] - sorted[i]) * (pos - i)
	}

	/** Columns with no points take their neighbours', linearly between them and held at the ends. */
	private fun fill(a: FloatArray) {
		val known = a.indices.filter { !a[it].isNaN() }
		if (known.isEmpty()) { a.fill(0f); return }
		for (b in a.indices) if (a[b].isNaN()) {
			val l = known.lastOrNull { it < b }; val r = known.firstOrNull { it > b }
			a[b] = when {
				l == null -> a[r!!]
				r == null -> a[l]
				else -> a[l] + (a[r] - a[l]) * (b - l).toFloat() / (r - l)
			}
		}
	}
}
