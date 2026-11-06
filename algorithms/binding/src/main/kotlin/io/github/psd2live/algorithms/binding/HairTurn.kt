package io.github.psd2live.core.fit

/**
 * The hair turning with the head by the style's hair sheet ([TurnStyle], [FaceTurn.Pose.hair]). Hair moves as one
 * smooth sheet over the head, not as parts with their own moves: the hair before the face goes more and more from the
 * top of the head down - about half the face's middle at the crown, as much at the hairline, more over the brows and
 * eyes, back to the middle's own below the chin - with no step anywhere, so a single fringe piece never shears across
 * its forehead. Each layer, before the face or behind it, has a sheet of its own.
 *
 * A sheet moves a point against the face's middle by where it lies: down the head (from the eye line, in the eye line
 * to chin length), linear between the sheet's heights ([TurnStyle.hairKnots]); across (in the face's width, mirrored
 * with the turn, held at [TurnStyle.hairReach]) by a quadratic, so the near and the far side differ; and down its lock
 * ([down]: 0 where its piece starts in its column, 1 where it ends), so a lock's tip leads its root - at each height
 * `a + b w + c w^2 + e v`, in the face's width.
 */
object HairTurn {
	/** [positions] (x, y pairs) of one hair piece, [front] of the face or behind it, placed at [pose]. */
	fun place(positions: FloatArray, front: Boolean, turn: FaceTurn, pose: FaceTurn.Pose): FloatArray {
		val n = positions.size / 2
		if (n == 0) return positions.copyOf()
		val style = turn.style; val knots = style.hairKnots; val terms = TurnStyle.HAIR_TERMS
		val f = turn.unit; val length = (turn.chin - turn.eyeLine).coerceAtLeast(1f)
		val layer = if (front) "front" else "back"
		val cx = FloatArray(knots.size * terms) { pose.hair["${layer}_x_$it"] }
		val cy = FloatArray(knots.size * terms) { pose.hair["${layer}_y_$it"] }
		val v = down(positions)
		return FloatArray(positions.size).also { out ->
			for (i in 0 until n) {
				val x = positions[i * 2]; val y = positions[i * 2 + 1]
				val h = ((y - turn.eyeLine) / length).coerceIn(knots.first(), knots.last())
				val w = ((x - turn.middle) / f * pose.sx).coerceIn(-style.hairReach, style.hairReach)
				var k = 0
				while (k < knots.size - 2 && h > knots[k + 1]) k++
				val t = (h - knots[k]) / (knots[k + 1] - knots[k])
				val across = floatArrayOf(1f, w, w * w, v[i])
				var dx = 0f; var dy = 0f
				for ((knot, share) in listOf(k to 1f - t, k + 1 to t)) for (j in 0 until terms) {
					dx += share * across[j] * cx[knot * terms + j]; dy += share * across[j] * cy[knot * terms + j]
				}
				out[i * 2] = x + dx * pose.sx * f + pose.slide.first
				out[i * 2 + 1] = y + dy * f + pose.slide.second
			}
		}
	}

	/** Each point's place down its lock: 0 where the piece starts in its column, 1 where it ends. */
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
