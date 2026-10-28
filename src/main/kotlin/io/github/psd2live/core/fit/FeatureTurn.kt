package io.github.psd2live.core.fit

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Where the parts on the face go at a pose of the face's turn ([FaceTurn.Pose]), by the style's moves for each:
 *
 * - **An eye changes as one linear map** - its squeeze and its lean together, the far eye narrowing more toward its
 *   outer end reading as that, not as a turn - about its middle, which leads or lags the face's middle. Its lash's tail,
 *   past the white's outer end, moves on its own; the iris keeps its place in the opening, by its own share of it, and
 *   its own width. An eye keeps its room to the outline at its height, from its white's outer end: never less than the
 *   style gives it, and an eye inside the outline at rest never crosses it.
 * - **A brow** leads or lags, narrows, tilts and arches. **The mouth** changes as one linear map too and bends its line.
 * - **The ears** and **headwear** move whole against the face's middle, less where they hang below the chin; the nose
 *   goes with the face.
 *
 * Every rule takes a rest point and gives where it goes. The near side is the one away from the turn, the far side on it.
 */
object FeatureTurn {
	/** A box: left, top, right, bottom. */
	private class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
		val cx get() = (l + r) / 2f
		val cy get() = (t + b) / 2f
		val w get() = (r - l).coerceAtLeast(1e-3f)
		val h get() = (b - t).coerceAtLeast(1e-3f)
		companion object {
			fun of(points: List<Pair<Float, Float>>) = Box(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
		}
	}

	/** An eye as the face's points draw it ([side] "L" or "R"): its opening's outline, its lash's ends and its iris; null without its corners. */
	class Eye private constructor(val outward: Float, internal val white: List<Pair<Float, Float>>, internal val lash: List<Pair<Float, Float>>,
	                              internal val iris: List<Pair<Float, Float>>) {
		companion object {
			fun of(marks: FaceLandmarks, side: String): Eye? {
				fun p(id: String) = marks["eye.$side.$id"]?.let { it.x to it.y }
				val inner = p("inner") ?: return null; val outer = p("outer") ?: return null
				val white = listOfNotNull(inner, outer, p("apex"), p("lowLid"), p("upper25"), p("upper50"), p("upper75"))
				val lash = listOfNotNull(p("lashTail"), p("lashInner"), p("lidTop"), p("lidOuter"))
				val iris = listOfNotNull(p("irisTop"), p("irisBottom"), p("irisInner"), p("irisOuter"))
				return Eye(if (outer.first > inner.first) 1f else -1f, white, lash, iris.takeIf { it.size == 4 }.orEmpty())
			}
		}
	}

	/** An eye placed at a pose: [eye] for its warp's points, [iris] for its iris's. */
	class PlacedEye(val eye: (Float, Float) -> Pair<Float, Float>, val iris: (Float, Float) -> Pair<Float, Float>)

	private fun side(far: Boolean) = if (far) "far" else "near"

	fun eye(e: Eye, turn: FaceTurn, pose: FaceTurn.Pose): PlacedEye {
		val m = pose.moves; val sx = pose.sx; val f = turn.unit
		val lab = side(e.outward == sx)
		val white0 = Box.of(e.white); val all = Box.of(e.white + e.lash)
		val c0x = all.cx; val c0y = all.cy
		val c1x = c0x + pose.slide.first + m["${lab}_eye_lead"] * sx * f; val c1y = c0y + pose.slide.second + m["${lab}_eye_dy"] * f
		val a00 = 1f + m["${lab}_eA00"]; val a01 = m["${lab}_eA01"] * sx; val a10 = m["${lab}_eA10"] * sx; val a11 = 1f + m["${lab}_eA11"]
		fun linear(x: Float, y: Float): Pair<Float, Float> { val dx = x - c0x; val dy = y - c0y; return (c1x + a00 * dx + a01 * dy) to (c1y + a10 * dx + a11 * dy) }
		val outerEnd = if (e.outward > 0f) white0.r else white0.l
		// The lash's tail, past the white's outer end, moves on its own.
		fun tail(x: Float): Float = (((x - outerEnd) * e.outward) / (0.15f * white0.w)).coerceIn(0f, 1f)
		fun shaped(x: Float, y: Float): Pair<Float, Float> {
			val (lx, ly) = linear(x, y); val k = tail(x)
			return (lx + m["${lab}_tail_x"] * e.outward * f * k) to (ly + m["${lab}_tail_y"] * f * k)
		}
		// Its room to the outline at its height, from its white's outer end as the style measures it (the lash's tail moves
		// on its own): the style's, and never across the outline it was inside at rest.
		val shift = run {
			val edge0 = pose.outlineAt(c0y, e.outward, moved = false) ?: return@run 0f
			val edge1 = pose.outlineAt(c0y, e.outward, moved = true) ?: return@run 0f
			val reach0 = e.white.maxOf { it.first * e.outward } * e.outward
			val reach1 = e.white.maxOf { linear(it.first, it.second).first * e.outward } * e.outward
			val room0 = (edge0 - reach0) * e.outward
			val want = max(room0 + m["${lab}_room"] * f, if (room0 > 0f) 0f else room0)
			val short = want - (edge1 - reach1) * e.outward
			if (short > 0f) -short * e.outward else 0f
		}
		val eye = { x: Float, y: Float -> shaped(x, y).let { (it.first + shift) to it.second } }
		if (e.iris.isEmpty()) return PlacedEye(eye, eye)
		// The iris: its place in the opening by its own share of it, its width its own.
		val white1 = Box.of(e.white.map { linear(it.first, it.second) })
		val i0 = Box.of(e.iris)
		val u1 = (i0.cx - white0.l) / white0.w + m["${lab}_iris_u"] * sx; val v1 = (i0.cy - white0.t) / white0.h + m["${lab}_iris_v"]
		val ci1x = white1.l + u1 * white1.w; val ci1y = white1.t + v1 * white1.h
		val kx = (1f + m["${lab}_iris_w"]) * white1.w / white0.w; val ky = white1.h / white0.h
		return PlacedEye(eye) { x, y -> (ci1x + (x - i0.cx) * kx + shift) to (ci1y + (y - i0.cy) * ky) }
	}

	/** A brow ([side] "L" or "R", +1 for the one on +x) by its head, peak and tail; null without them. */
	fun brow(marks: FaceLandmarks, side: String, sign: Float, turn: FaceTurn, pose: FaceTurn.Pose): ((Float, Float) -> Pair<Float, Float>)? {
		val points = listOf("head", "peak", "tail").map { marks["brow.$side.$it"]?.let { p -> p.x to p.y } ?: return null }
		val m = pose.moves; val sx = pose.sx; val f = turn.unit
		val lab = side(sign == sx)
		val box = Box.of(points)
		val c1x = box.cx + pose.slide.first + m["${lab}_brow_lead"] * sx * f; val c1y = box.cy + pose.slide.second + m["${lab}_brow_dy"] * f
		val a = Math.toRadians((m["${lab}_brow_tilt"] * sx).toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
		val k = 1f + m["${lab}_brow_w"]; val arch = m["${lab}_brow_arch"] * f
		return { x, y ->
			val dx = (x - box.cx) * k; val dy = y - box.cy
			val u = ((x - box.cx) / (box.w / 2f)).coerceIn(-1f, 1f)
			(c1x + dx * ca - dy * sa) to (c1y + dx * sa + dy * ca - arch * (1f - u * u))
		}
	}

	/** The mouth by its corners and its lips' middles; null without them. */
	fun mouth(marks: FaceLandmarks, turn: FaceTurn, pose: FaceTurn.Pose): ((Float, Float) -> Pair<Float, Float>)? {
		val points = listOf("cornerA", "cornerB", "upperMid", "lowerMid").mapNotNull { marks["mouth.$it"]?.let { p -> p.x to p.y } }
		if (points.size < 2) return null
		val m = pose.moves; val sx = pose.sx; val f = turn.unit
		val box = Box.of(points)
		val c1x = box.cx + pose.slide.first + m["mouth_lead"] * sx * f; val c1y = box.cy + pose.slide.second + m["mouth_dy"] * f
		val a00 = 1f + m["mA00"]; val a01 = m["mA01"] * sx; val a10 = m["mA10"] * sx; val a11 = 1f + m["mA11"]
		val curve = m["mouth_curve"] * f
		return { x, y ->
			val dx = x - box.cx; val dy = y - box.cy
			val u = (dx / (box.w / 2f)).coerceIn(-1f, 1f)
			(c1x + a00 * dx + a01 * dy) to (c1y + a10 * dx + a11 * dy + curve * (1f - u * u))
		}
	}

	/**
	 * A part moving whole against the face's middle by the style's move [name] (`earN`, `earF`, `headwear`): its points
	 * hanging below the chin go less, down to four tenths of it a face's length lower.
	 */
	fun whole(name: String, turn: FaceTurn, pose: FaceTurn.Pose): (Float, Float) -> Pair<Float, Float> {
		val m = pose.moves; val f = turn.unit
		val dx = pose.slide.first + m["${name}_x"] * pose.sx * f; val dy = pose.slide.second + m["${name}_y"] * f
		val length = (turn.chin - turn.eyeLine).coerceAtLeast(1f)
		return { x, y ->
			val below = ((y - turn.chin) / length).coerceIn(0f, 1f)
			val k = 1f - 0.6f * below * below * (3f - 2f * below)
			(x + dx * k) to (y + dy * k)
		}
	}

	/** An ear on the side [sign] (+1 for the one on +x): the near ear's move or the far one's. */
	fun ear(sign: Float, turn: FaceTurn, pose: FaceTurn.Pose) = whole(if (sign == pose.sx) "earF" else "earN", turn, pose)
}
