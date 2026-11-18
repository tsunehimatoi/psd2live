package io.github.psd2live.core.fit

import kotlin.math.abs
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
 * - **A brow** follows its eye's placement and maintains a styled gap from the upper lid when both are measured.
 * - **An ear** follows the face at a measured skin attachment and preserves independent movement away from it.
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
	class Eye private constructor(
		val outward: Float, internal val white: List<Pair<Float, Float>>, internal val lash: List<Pair<Float, Float>>,
		internal val iris: List<Pair<Float, Float>>, internal val inner: Pair<Float, Float>, internal val outer: Pair<Float, Float>,
		internal val sampledUpper: List<Pair<Float, Float>>,
	) {
		companion object {
			fun of(marks: FaceLandmarks, side: String): Eye? {
				fun p(id: String) = marks["eye.$side.$id"]?.let { it.x to it.y }
				val inner = p("inner") ?: return null; val outer = p("outer") ?: return null
				val upper = listOfNotNull(p("upper25"), p("upper50"), p("upper75"))
				val lower = listOfNotNull(p("lower25"), p("lower50"), p("lower75"))
				val white = listOfNotNull(inner, outer, p("apex"), p("lowLid")) + upper + lower
				val lash = listOfNotNull(p("lashTail"), p("lashInner"), p("lidTop"), p("lidOuter"))
				val iris = listOfNotNull(p("irisTop"), p("irisBottom"), p("irisInner"), p("irisOuter"))
				val sampledUpper = upper.takeIf { it.size == 3 && lower.size == 3 && abs(outer.first - inner.first) > 1e-3f }.orEmpty()
				return Eye(if (outer.first > inner.first) 1f else -1f, white, lash,
					iris.takeIf { it.size == 4 }.orEmpty(), inner, outer, sampledUpper)
			}
		}
	}

	/** An eye placed at a pose: [eye] for its warp's points, [iris] for its iris's. */
	class PlacedEye internal constructor(
		val eye: (Float, Float) -> Pair<Float, Float>, val iris: (Float, Float) -> Pair<Float, Float>,
		internal val outlineShift: Float,
	)

	private fun side(far: Boolean) = if (far) "far" else "near"

	fun eye(e: Eye, turn: FaceTurn, pose: FaceTurn.Pose): PlacedEye {
		val m = pose.moves; val sx = pose.sx; val f = turn.unit
		val lab = side(e.outward == sx)
		val white0 = Box.of(e.white); val all = Box.of(e.white + e.lash)
		val c0x = all.cx; val c0y = all.cy
		val c1x = c0x + pose.slide.first + m["${lab}_eye_lead"] * sx * f; val c1y = c0y + pose.slide.second + m["${lab}_eye_dy"] * f
		val a00 = 1f + m["${lab}_eA00"]; val a01 = m["${lab}_eA01"] * sx; val a10 = m["${lab}_eA10"] * sx; val a11 = 1f + m["${lab}_eA11"]
		fun styled(x: Float, y: Float): Pair<Float, Float> { val dx = x - c0x; val dy = y - c0y; return (c1x + a00 * dx + a01 * dy) to (c1y + a10 * dx + a11 * dy) }
		val fitted = fittedEyeClearance(e, turn, pose, lab, ::styled)
		val innerX = styled(e.inner.first, e.inner.second).first
		fun linear(x: Float, y: Float): Pair<Float, Float> {
			val (u, v) = styled(x, y)
			val width = fitted?.width ?: 1f
			return (if (width == 1f) u else innerX + (u - innerX) * width) to v
		}
		val outerEnd = if (e.outward > 0f) white0.r else white0.l
		// The lash's tail, past the white's outer end, moves on its own.
		fun tail(x: Float): Float = (((x - outerEnd) * e.outward) / (0.15f * white0.w)).coerceIn(0f, 1f)
		fun shaped(x: Float, y: Float): Pair<Float, Float> {
			val (lx, ly) = linear(x, y); val k = tail(x)
			return (lx + m["${lab}_tail_x"] * e.outward * f * k) to (ly + m["${lab}_tail_y"] * f * k)
		}
		// Its room to the outline at its height, from its white's outer end as the style measures it (the lash's tail moves
		// on its own): the style's, and never across the outline it was inside at rest.
		val shift = fitted?.shift ?: run {
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
		if (e.iris.isEmpty()) return PlacedEye(eye, eye, shift)
		// The iris: its place in the opening by its own share of it, its width its own.
		val white1 = Box.of(e.white.map { linear(it.first, it.second) })
		val i0 = Box.of(e.iris)
		val u1 = (i0.cx - white0.l) / white0.w + m["${lab}_iris_u"] * sx; val v1 = (i0.cy - white0.t) / white0.h + m["${lab}_iris_v"]
		val ci1x = white1.l + u1 * white1.w; val ci1y = white1.t + v1 * white1.h
		val kx = (1f + m["${lab}_iris_w"]) * white1.w / white0.w; val ky = white1.h / white0.h
		val iris = { x: Float, y: Float -> (ci1x + (x - i0.cx) * kx + shift) to (ci1y + (y - i0.cy) * ky) }
		return PlacedEye(eye, iris, shift)
	}

	private data class EyeClearance(val width: Float, val shift: Float)

	private fun fittedEyeClearance(e: Eye, turn: FaceTurn, pose: FaceTurn.Pose, lab: String,
	                              move: (Float, Float) -> Pair<Float, Float>): EyeClearance? {
		if (!turn.usable || !pose.hasTurn || e.sampledUpper.isEmpty()) return null
		val outward = e.outward
		val roomKey = "${lab}_room"
		fun clearance(point: Pair<Float, Float>, moved: Boolean): Float? =
			pose.sideAt(point.second, outward, moved)?.let { (it - point.first) * outward }
		val restRoom = clearance(e.outer, moved = false) ?: return null
		var roomChange = pose.moves[roomKey] * turn.unit
		val farSide = restRoom > 0f && lab == "far" && pose.yaw != 0f && turn.defaultTurnStrength > 0f
		if (farSide) {
			// A far corner with room at rest keeps a proportion of that space at full turn.
			val faceWidth = turn.widthAt(e.outer.second)
			val target = (.83f * restRoom - .029f * faceWidth).coerceIn(0f, restRoom)
			val yaw = pose.yaw / FaceTurn.TURN_END
			val adaptive = (target - restRoom) * abs(yaw).coerceAtMost(1f) * turn.defaultTurnStrength
			val original = turn.style.defaultYaw(yaw, turn.defaultTurnStrength)[roomKey] * turn.unit
			roomChange += adaptive - original
		}
		val required = if (restRoom > 0f) max(0f, restRoom + roomChange) else restRoom
		val innerX = move(e.inner.first, e.inner.second).first
		val posed = e.white.mapNotNull { point ->
			val before = clearance(point, moved = false) ?: return@mapNotNull null
			val current = move(point.first, point.second)
			val after = clearance(current, moved = true) ?: return@mapNotNull null
			val wanted = if (point == e.outer) required else minOf(before, 0f)
			(current.first - innerX) * outward to wanted - after
		}
		if (posed.isEmpty() || clearance(move(e.outer.first, e.outer.second), moved = true) == null) return null
		// Keep the far eye's bridge-side corner anchored if the outer contour can fit by narrowing. The
		// remaining mismatch is translated inward so a very shallow silhouette cannot collapse the opening.
		var width = 1f
		if (farSide) {
			for ((reach, shortage) in posed) if (reach > 1e-3f && shortage > 0f)
				width = minOf(width, 1f - shortage / reach)
			val progress = abs(pose.yaw / FaceTurn.TURN_END).coerceAtMost(1f)
			width = max(width, 1f - .5f * progress)
		}
		val shortfall = posed.maxOf { (reach, shortage) -> shortage - (1f - width) * reach }.coerceAtLeast(0f)
		return EyeClearance(width, -outward * shortfall)
	}

	/** A brow ([side] "L" or "R", +1 for the one on +x) by its head, peak and tail; null without them. */
	fun brow(marks: FaceLandmarks, side: String, sign: Float, turn: FaceTurn, pose: FaceTurn.Pose,
	         eye: Eye? = Eye.of(marks, side)): ((Float, Float) -> Pair<Float, Float>)? {
		val points = listOf("head", "peak", "tail").map { marks["brow.$side.$it"]?.let { p -> p.x to p.y } ?: return null }
		val m = pose.moves; val sx = pose.sx; val f = turn.unit
		val lab = side(sign == sx)
		val box = Box.of(points)
		val c1x = box.cx + pose.slide.first + m["${lab}_brow_lead"] * sx * f; val c1y = box.cy + pose.slide.second + m["${lab}_brow_dy"] * f
		val a = Math.toRadians((m["${lab}_brow_tilt"] * sx).toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
		val k = 1f + m["${lab}_brow_w"]; val arch = m["${lab}_brow_arch"] * f
		val styled = { x: Float, y: Float ->
			val dx = (x - box.cx) * k; val dy = y - box.cy
			val u = ((x - box.cx) / (box.w / 2f)).coerceIn(-1f, 1f)
			(c1x + dx * ca - dy * sa) to (c1y + dx * sa + dy * ca - arch * (1f - u * u))
		}
		if (!pose.hasTurn || eye == null || eye.sampledUpper.isEmpty() || eye.outward != sign) return styled
		val centres = listOf("center25", "center50", "center75").map { marks["brow.$side.$it"] ?: return styled }
		val placedEye = pose.eye(eye)
		val gapChange = m["${lab}_brow_gap"] * f
		val offsets = centres.zip(eye.sampledUpper).map { (brow, lid) ->
			val gap = lid.second - brow.y
			val target = placedEye.eye(lid.first, lid.second).second - max(gap * .2f, gap + gapChange)
			(brow.x * sign) to if (gap > 0f) target - styled(brow.x, brow.y).second else 0f
		}.sortedBy { it.first }
		return { x, y ->
			val (bx, by) = styled(x, y)
			val towardOutside = x * sign
			val (u0, y0) = offsets[if (towardOutside < offsets[1].first) 0 else 1]
			val (u1, y1) = offsets[if (towardOutside < offsets[1].first) 1 else 2]
			val t = ((towardOutside - u0) / (u1 - u0).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
			(bx + placedEye.outlineShift) to (by + y0 + (y1 - y0) * t)
		}
	}

	/** The mouth by its corners and its lips' middles; null without them. */
	fun mouth(marks: FaceLandmarks, turn: FaceTurn, pose: FaceTurn.Pose): ((Float, Float) -> Pair<Float, Float>)? {
		val points = listOf("cornerA", "cornerB", "upperMid", "lowerMid").mapNotNull { marks["mouth.$it"]?.let { p -> p.x to p.y } }
		if (points.size < 2) return null
		val m = pose.moves; val sx = pose.sx; val f = turn.unit
		val box = Box.of(points)
		val correction = pose.lowerFacePlacement?.mouth
		val c1x = box.cx + pose.slide.first + m["mouth_lead"] * sx * f + (correction?.first ?: 0f)
		val c1y = box.cy + pose.slide.second + m["mouth_dy"] * f + (correction?.second ?: 0f)
		fittedMouth(marks, turn, pose, box, c1x, c1y)?.let { return it }
		val a00 = 1f + m["mA00"]; val a01 = m["mA01"] * sx; val a10 = m["mA10"] * sx; val a11 = 1f + m["mA11"]
		val curve = m["mouth_curve"] * f
		return { x, y ->
			val dx = x - box.cx; val dy = y - box.cy
			val u = (dx / (box.w / 2f)).coerceIn(-1f, 1f)
			(c1x + a00 * dx + a01 * dy) to (c1y + a10 * dx + a11 * dy + curve * (1f - u * u))
		}
	}

	private fun fittedMouth(marks: FaceLandmarks, turn: FaceTurn, pose: FaceTurn.Pose, box: Box,
	                        centerX: Float, centerY: Float): ((Float, Float) -> Pair<Float, Float>)? {
		val strength = turn.defaultTurnStrength
		if (pose.yaw == 0f || strength == 0f || turn.unit <= 0f) return null
		val chin = marks["face.chin"] ?: return null
		val upper = marks["mouth.upperMid"] ?: return null
		val lower = marks["mouth.lowerMid"] ?: return null
		val a = marks["mouth.cornerA"] ?: return null
		val b = marks["mouth.cornerB"] ?: return null
		val opening = lower.y - upper.y
		val openingRatio = opening / box.w
		if (openingRatio <= .05f || abs(a.x - b.x) <= .001f) return null

		val level = pose.level
		val pureYaw = turn.style.defaultYaw(pose.yaw / FaceTurn.TURN_END, strength)
		val moves = pose.moves
		val fit = MouthTurnFit.shape(box.w / turn.unit, openingRatio, pose.yaw, strength)
		val xCenter = if (pose.lowerFacePlacement != null) centerX else {
			val chinShift = level.field(chin.x, chin.y).first - chin.x
			val originalShift = level.slide.first + pureYaw["mouth_lead"] * pose.sx * turn.unit
			centerX + chinShift - originalShift
		}
		val width = (fit.width + moves["mA00"] - pureYaw["mA00"]).coerceAtLeast(.3f)
		val height = (fit.height + moves["mA11"] - pureYaw["mA11"]).coerceAtLeast(.4f)
		val pitchShear = ((moves["mA01"] - pureYaw["mA01"]) * pose.sx).coerceIn(-.22f, .22f)
		val tilt = moves["mA10"] * pose.sx
		val curve = moves["mouth_curve"] * turn.unit
		return { x, y ->
			val dx = x - box.cx
			val dy = y - box.cy
			val u = (dx / (box.w * .5f)).coerceIn(-1f, 1f)
			val v = ((y - upper.y) / opening).coerceIn(0f, 1f)
			val middleWeight = 1f - u * u
			// A quarter-width center offset would flatten a horizontal row at one corner.
			val lipOffset = (fit.upper + (fit.lower - fit.upper) * v).coerceIn(-.24f, .24f)
			val lipShift = lipOffset * pose.sx * width * box.w * middleWeight
			(xCenter + width * dx + pitchShear * dy + lipShift) to (centerY + tilt * dx + height * dy + curve * middleWeight)
		}
	}

	/** A separately drawn nose's tip follows the fitted path and its bridge joins the face field. */
	fun nose(pose: FaceTurn.Pose): (Float, Float) -> Pair<Float, Float> {
		val placement = pose.lowerFacePlacement ?: return pose.field
		val (dx, dy) = placement.nose
		return { x, y ->
			val (nx, ny) = pose.field(x, y)
			val weight = placement.noseWeight(y)
			(nx + dx * weight) to (ny + dy * weight)
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
	fun ear(sign: Float, turn: FaceTurn, pose: FaceTurn.Pose, attachment: Landmark? = null,
	        outer: Landmark? = null): (Float, Float) -> Pair<Float, Float> {
		val free = whole(if (sign == pose.sx) "earF" else "earN", turn, pose)
		if (!pose.hasTurn || attachment == null || outer == null) return free
		val span = (outer.x - attachment.x) * sign
		if (span <= 1e-3f) return free
		val (px, py) = pose.field(attachment.x, attachment.y)
		val (fx, fy) = free(attachment.x, attachment.y)
		val faceFollow = if (sign == pose.sx) 1f - (abs(pose.yaw) / FaceTurn.TURN_END).coerceAtMost(1f) else 1f
		val dx = (px - fx) * faceFollow
		val dy = (py - fy) * faceFollow
		// Carry a large correction rigidly so that the taper cannot fold the ear horizontally.
		val taperX = dx.coerceIn(-span * .4f, span * .4f)
		return { x, y ->
			val (ex, ey) = free(x, y)
			val t = (((x - attachment.x) * sign) / span).coerceIn(0f, 1f)
			val lose = t * t * (3f - 2f * t)
			(ex + dx - taperX * lose) to (ey + dy * (1f - lose))
		}
	}
}
