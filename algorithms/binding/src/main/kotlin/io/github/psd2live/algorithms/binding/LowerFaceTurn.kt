package io.github.psd2live.core.fit

import kotlin.math.abs

/** Fits the nose and mouth's displacement relative to the chin carried by the face contour. */
internal class LowerFaceTurn private constructor(
	private val tip: Landmark,
	private val bridgeY: Float?,
	private val chin: Landmark,
	private val upperLip: Landmark,
	private val lowerLip: Landmark,
	private val leftCorner: Landmark,
	private val rightCorner: Landmark,
	private val eyeLineWidth: Float,
	private val eyeSpacing: Float,
) {
	private val mouthPoints = listOf(upperLip, lowerLip, leftCorner, rightCorner)
	private val mouthCenterX = (mouthPoints.minOf { it.x } + mouthPoints.maxOf { it.x }) * .5f
	private val mouthCenterY = (mouthPoints.minOf { it.y } + mouthPoints.maxOf { it.y }) * .5f
	private val mouthHalfWidth = (mouthPoints.maxOf { it.x } - mouthPoints.minOf { it.x }) * .5f

	data class Placement(
		val nose: Pair<Float, Float>,
		val mouth: Pair<Float, Float>,
		val bridgeY: Float?,
		val noseTip: Float,
	) {
		fun noseWeight(y: Float): Float {
			val root = bridgeY ?: return 1f
			return ((y - root) / (noseTip - root)).coerceIn(0f, 1f)
		}
	}

	fun place(turn: FaceTurn, pose: FaceTurn.Pose): Placement {
		val (noseX, mouthX) = if (pose.yaw != 0f && turn.defaultTurnStrength > 0f) yaw(turn, pose.level) else 0f to 0f
		val (noseY, mouthY) = if (pose.pitch != 0f && turn.lookStrength > 0f) pitch(turn, pose.straight) else 0f to 0f
		// Add only the corrections of the single-axis poses. The authored diagonal contribution stays in the style.
		return Placement(noseX to noseY, mouthX to mouthY, bridgeY, tip.y)
	}

	private fun yaw(turn: FaceTurn, level: FaceTurn.Pose): Pair<Float, Float> {
		val fraction = (abs(level.yaw) / FaceTurn.TURN_END).coerceAtMost(1f) * turn.defaultTurnStrength
		val chinShift = level.field(chin.x, chin.y).first - chin.x
		val sx = level.sx
		val noseLead = (.125f * eyeLineWidth * fraction).coerceAtMost(room(tip, level, chinShift))
		val mouthCorner = if (sx > 0f) rightCorner else leftCorner
		// Eye spacing controls the local nose-to-mouth gap, while the face width sets the full lead over the chin.
		val mouthLead = (noseLead - .15f * eyeSpacing * fraction).coerceIn(0f, noseLead)
			.coerceAtMost(room(mouthCorner, level, chinShift))
		val rawNose = level.field(tip.x, tip.y).first - tip.x
		val defaultYaw = turn.style.defaultYaw(level.yaw / FaceTurn.TURN_END, turn.defaultTurnStrength)
		val rawMouth = level.slide.first + defaultYaw["mouth_lead"] * sx * turn.unit
		return (chinShift + sx * noseLead - rawNose) to (chinShift + sx * mouthLead - rawMouth)
	}

	private fun pitch(turn: FaceTurn, straight: FaceTurn.Pose): Pair<Float, Float> {
		val up = straight.pitch > 0f
		val fraction = (abs(straight.pitch) / FaceTurn.LOOK_END).coerceAtMost(1f) * turn.lookStrength
		val chinShift = straight.field(chin.x, chin.y).second - chin.y
		val mouthShare = if (up) .73f else .58f
		val requestedLead = (if (up) .074f else .099f) * eyeLineWidth * fraction
		val shape = straight.moves
		fun lipOffset(lip: Landmark): Float {
			val dx = lip.x - mouthCenterX
			val u = (dx / mouthHalfWidth).coerceIn(-1f, 1f)
			return shape["mA10"] * dx + shape["mA11"] * (lip.y - mouthCenterY) +
				shape["mouth_curve"] * turn.unit * (1f - u * u)
		}
		// The shape can stretch at the same time as the anchors move. Limit the final edges,
		// allowing the tip to lag if retaining room for the opening requires it.
		val noseRoom = .85f * (upperLip.y - tip.y) + lipOffset(upperLip)
		val chinRoom = .85f * (chin.y - lowerLip.y) - lipOffset(lowerLip)
		val lead = (if (up) -requestedLead else requestedLead).coerceAtMost(noseRoom + chinRoom)
		val minimumMouth = minOf(lead - noseRoom, chinRoom)
		val mouthLead = (mouthShare * lead).coerceIn(minimumMouth, chinRoom)
		val rawNose = straight.field(tip.x, tip.y).second - tip.y
		val rawMouth = straight.slide.second + straight.moves["mouth_dy"] * turn.unit
		return (chinShift + lead - rawNose) to (chinShift + mouthLead - rawMouth)
	}

	private fun room(point: Landmark, pose: FaceTurn.Pose, chinShift: Float): Float {
		val restEdge = pose.outlineAt(point.y, pose.sx, moved = false) ?: return Float.POSITIVE_INFINITY
		if ((restEdge - point.x) * pose.sx <= 0f) return Float.POSITIVE_INFINITY
		val posedEdge = pose.outlineAt(point.y, pose.sx, moved = true) ?: return Float.POSITIVE_INFINITY
		return ((posedEdge - point.x - chinShift) * pose.sx).coerceAtLeast(0f)
	}

	companion object {
		fun measure(marks: FaceLandmarks, turn: FaceTurn): LowerFaceTurn? {
			if (marks.measures["nose.drawn"] != 1f) return null
			val tip = marks["nose.tip"] ?: return null
			val top = marks["nose.top"] ?: tip
			val chin = marks["face.chin"] ?: return null
			val upper = marks["mouth.upperMid"] ?: return null
			val lower = marks["mouth.lowerMid"] ?: return null
			val a = marks["mouth.cornerA"] ?: return null
			val b = marks["mouth.cornerB"] ?: return null
			if (tip.y >= upper.y || upper.y > lower.y || lower.y >= chin.y || a.x == b.x) return null
			val left = if (a.x < b.x) a else b
			val right = if (a.x < b.x) b else a
			val noseWidth = abs((marks["nose.sideB"]?.x ?: tip.x) - (marks["nose.sideA"]?.x ?: tip.x))
			// A highlight or a short nose mark is carried as one piece. Only bridge art reaching
			// the eye line has an attachment to the face that needs tapering.
			val bridgeY = top.y.takeIf { it <= turn.eyeLine && tip.y - it > noseWidth * .15f }
			return LowerFaceTurn(tip, bridgeY, chin, upper, lower, left, right, turn.widthAt(turn.eyeLine), turn.eyeSpacing)
		}
	}
}
