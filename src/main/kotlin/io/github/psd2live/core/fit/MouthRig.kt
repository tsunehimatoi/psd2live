package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.MouthCurve
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which mouth modules a project builds, with their values as fractions. */
data class MouthOptions(
	val early: Float = 0.35f, val formStrength: Float = 1f,
	val curve: MouthCurve = MouthCurve.preset("smile"), val exactClose: Boolean = true,
	val vowels: Boolean = false, val capture: Boolean = false,
	val jaw: Boolean = false, val jawShare: Float = 0.15f,
	val depth: Boolean = false, val depthAmount: Float = 1f,
	val spring: Boolean = false,
)

/**
 * The `mouth/fitted` base and the mouth modules for the drawn open mouth ([fit]). Every mouth the rig makes is a pose
 * of the two lip edges ([Pose]): the drawn upper and lower edge carried toward a closed line, rounded, flattened,
 * narrowed or shifted, and each vertex of the mouth's layers keeps its place between the edges - a lip line's band
 * along each edge stays as drawn and the inside closes between them - so the lips meet in one line with their own
 * thickness, the teeth ride the lip they belong to and the interior shuts with them.
 *
 * Opening goes in stages, as hand-made mouths do: at a hair the lips part ([SWAP], where a drawn closed mouth hands
 * over), at a quarter the lower lip has dropped into a crescent while the upper lip and the corners stay, at a half
 * the mouth is an oval, at 1 the drawing. The form runs from a pout - closed a small frown, open a round "o" - to a
 * smile - corners up, open a wide D under a flat upper lip - with keys between the ends.
 */
class MouthRig(val fit: MouthFit, val options: MouthOptions) {
	/**
	 * A mouth's shape: how far the upper and lower lip have gone from the closed line toward the drawing ([upper],
	 * [lower]), its width and sideways shift (of the corners' distance), how far the corners rise ([corner], of the
	 * width), how round it opens ([round]) and how flat the upper lip stays ([flatTop]), the closed line lifted at its
	 * middle ([lift]) and the lips pressed together ([press]).
	 */
	data class Pose(
		val upper: Float, val lower: Float, val width: Float = 1f, val shift: Float = 0f, val corner: Float = 0f,
		val round: Float = 0f, val flatTop: Float = 0f, val lift: Float = 0f, val press: Float = 0f,
	)

	private val h = fit.opening
	/** A lip line's band along each edge, which moves with its edge as drawn. */
	private val band = min(0.045f, h * 0.18f)
	/** The gap left between the lips when they close over nothing else ([MouthOptions.exactClose] off). */
	private val gap = if (options.exactClose) 0f else (1.2f / fit.width).coerceIn(0.004f, 0.04f)

	private fun drawnUpper(u: Float) = MouthFit.sample(fit.upper, u) - fit.baseline(u)
	private fun drawnLower(u: Float) = MouthFit.sample(fit.lower, u) - fit.baseline(u)

	/** The closed line below the corners' line, from the mouth's closed shape (positive down). */
	private fun closed(u: Float) = options.curve.yAt(u) * h * 0.6f

	/** The lip edges of [pose] at [u] (of the drawing), below the corners' line. */
	fun edges(pose: Pose, u: Float): Pair<Float, Float> {
		val hump = EyeRig.hump(u).coerceAtLeast(0f)
		val e = sqrt(hump)
		var ur = drawnUpper(u); var lr = drawnLower(u)
		ur += (drawnUpper(0.5f) * e - ur) * pose.round
		lr += (drawnLower(0.5f) * e - lr) * pose.round
		ur *= 1f - 0.6f * pose.flatTop
		val c = closed(u) * (1f - pose.press) - pose.lift * hump
		var top = c + (ur - c) * pose.upper - gap * 0.5f * hump
		var bottom = c + (lr - c) * pose.lower + gap * 0.5f * hump
		val rise = pose.corner * (1f - hump)
		top -= rise; bottom -= rise
		if (bottom < top) { val m = (top + bottom) / 2f; top = m; bottom = m }
		return top to bottom
	}

	/** Where a point of the mouth at ([u], [v]) in the mouth's frame goes in [pose], as a change in the frame. */
	fun move(u: Float, v: Float, pose: Pose): Pair<Float, Float> {
		val uc = u.coerceIn(0f, 1f)
		val r = v - fit.baseline(u)
		val u0 = drawnUpper(uc); val l0 = drawnLower(uc)
		val (u1, l1) = edges(pose, uc)
		val tb = min(band, max(0f, l0 - u0) * 0.3f)
		// Past an edge a lip line's thickness stays as drawn; more than that - a round mouth's side bending away near a
		// corner - closes with the mouth, or a shut mouth keeps hooks at its corners.
		val ratio = ((edges(pose, 0.5f).let { it.second - it.first }) / h.coerceAtLeast(1e-4f)).coerceIn(0f, 1.5f)
		fun beyond(d: Float): Float {
			val keep = d.coerceIn(-band, band)
			return keep + (d - keep) * ratio
		}
		val r1 = when {
			r <= u0 + tb -> u1 + beyond(r - u0)
			r >= l0 - tb -> l1 + beyond(r - l0)
			else -> {
				val inTop = u1 + tb; val inBottom = max(l1 - tb, inTop)
				val t = (r - (u0 + tb)) / ((l0 - tb) - (u0 + tb)).coerceAtLeast(1e-5f)
				inTop + (inBottom - inTop) * t
			}
		}
		val un = 0.5f + (u - 0.5f) * pose.width + pose.shift
		val vn = fit.baseline(un) + r1
		return (un - u) to (vn - v)
	}

	/** The mouth at [form] (−1 pout … 1 smile) and [open] (0 … 1). */
	fun pose(form: Float, open: Float): Pose {
		val (up, lo) = staged(open)
		val width = interpolate(OPEN_KEYS, WIDTHS, open)
		val f = (form * options.formStrength).coerceIn(-1.5f, 1.5f)
		val opened = (open / 0.25f).coerceIn(0f, 1f)
		return if (f >= 0f) Pose(up, lo, width * (1f + 0.08f * f), corner = 0.06f * f, flatTop = f * opened)
		else Pose(up, lo, width * (1f + f * (0.22f + 0.3f * (open / 0.5f).coerceIn(0f, 1f))), corner = 0.05f * f * (1f - 0.6f * open.coerceIn(0f, 1f)),
			round = -f * 0.9f * opened)
	}

	/** How far the upper and the lower lip have gone at [open]: the lower lip first, the upper one late. */
	private fun staged(open: Float): Pair<Float, Float> {
		val lower = floatArrayOf(0f, 0.08f, options.early, (options.early + 1f) / 2f + 0.05f, 1f)
		return interpolate(OPEN_KEYS, UPPERS, open) to interpolate(OPEN_KEYS, lower, open)
	}

	/** The vowel poses lip sync drives, each over the closed mouth. */
	fun vowel(name: Char): Pose = when (name) {
		'A' -> pose(0f, 1f)
		'I' -> staged(0.3f).let { (u, l) -> Pose(u, l, 1.18f, corner = 0.03f, flatTop = 0.5f) }
		'U' -> staged(0.3f).let { (u, l) -> Pose(u, l, 0.62f, round = 1f) }
		'E' -> staged(0.55f).let { (u, l) -> Pose(u, l, 1.1f, corner = 0.01f) }
		else -> staged(0.75f).let { (u, l) -> Pose(u, l, 0.78f, round = 0.8f) }
	}

	private fun form(positions: FloatArray, frame: Bounds, pose: Pose): MeshDeltaForm = form(positions, frame) { u, v -> move(u, v, pose) }

	private fun form(positions: FloatArray, frame: Bounds, change: (Float, Float) -> Pair<Float, Float>): MeshDeltaForm {
		val out = FloatArray(positions.size)
		for (i in positions.indices step 2) {
			val (u, v) = fit.toMouth(positions[i], positions[i + 1])
			val (du, dv) = change(u, v)
			val (dx, dy) = fit.canvasDelta(du, dv)
			out[i] = dx / frame.width.coerceAtLeast(1e-4f); out[i + 1] = dy / frame.height.coerceAtLeast(1e-4f)
		}
		return MeshDeltaForm(out)
	}

	/** A mouth layer's grid: its form by its openness. Deltas are over the parent [frame]. */
	fun grid(positions: FloatArray, frame: Bounds, mouthForm: ParameterId, mouthOpen: ParameterId): KeyformGrid<MeshDeltaForm> =
		keyed(mouthForm, mouthOpen) { pose -> form(positions, frame, pose) }

	/** A grid over the form and the openness whose every key is [form] of that key's pose. */
	fun keyed(mouthForm: ParameterId, mouthOpen: ParameterId, form: (Pose) -> MeshDeltaForm): KeyformGrid<MeshDeltaForm> {
		val cells = ArrayList<KeyformCell<MeshDeltaForm>>()
		for ((o, open) in OPEN_KEYS.withIndex()) for ((f, value) in FORM_KEYS.withIndex()) {
			cells += KeyformCell(intArrayOf(f, o), form(pose(value, open)))
		}
		return KeyformGrid(listOf(KeyformAxis(mouthForm, FORM_KEYS), KeyformAxis(mouthOpen, OPEN_KEYS)), cells)
	}

	/** Where the point ([x], [y]) of the mouth (in the rig's space) goes in [pose]. */
	fun point(x: Float, y: Float, pose: Pose): Pair<Float, Float> {
		val (u, v) = fit.toMouth(x, y)
		val (du, dv) = move(u, v, pose)
		val (dx, dy) = fit.canvasDelta(du, dv)
		return (x + dx) to (y + dy)
	}

	/**
	 * A drawn closed mouth's grid: it bends with the form - corners up into a smile, down into a pout - narrowing or
	 * widening about its middle. It shows only while the mouth is shut.
	 */
	fun closedGrid(positions: FloatArray, frame: Bounds, mouthForm: ParameterId): KeyformGrid<MeshDeltaForm> =
		KeyformGrid(listOf(KeyformAxis(mouthForm, FORM_KEYS)), FORM_KEYS.mapIndexed { f, form ->
			KeyformCell(intArrayOf(f), form(positions, frame) { u, v -> closedMove(u, v, pose(form, 0f)) })
		})

	private fun closedMove(u: Float, v: Float, pose: Pose): Pair<Float, Float> {
		val uc = u.coerceIn(0f, 1f)
		val un = 0.5f + (u - 0.5f) * pose.width / WIDTHS[0] + pose.shift
		val rest = closed(uc)
		val (top, _) = edges(pose.copy(upper = 0f, lower = 0f), uc)
		return (un - u) to (fit.baseline(un) - fit.baseline(u) + (top - rest) + gap * 0.5f * EyeRig.hump(uc))
	}

	/**
	 * The mouth's additive shapes (each over the closed mouth): the vowels lip sync drives (`mouth.vowel`) and the face
	 * capture's mouth movements (`mouth.capture`) - sideways, pucker and widen, shrug, the lips pressed or parted.
	 * [visible] gives a form's opacity: a drawn closed mouth hides and the open mouth's parts show while a vowel opens
	 * the mouth.
	 */
	fun shapes(positions: FloatArray, frame: Bounds, closedLayer: Boolean, visible: (Boolean) -> Float): List<BlendShapeBinding<MeshForm>> =
		shapes({ pose -> (if (closedLayer) form(positions, frame) { u, v -> closedMove(u, v, pose) } else form(positions, frame, pose)).positionDeltas }, visible)

	/** The same shapes for a mesh whose deltas at a pose [deltas] gives - a lip ribbon laid along the posed lips. */
	fun shapes(deltas: (Pose) -> FloatArray, visible: (Boolean) -> Float): List<BlendShapeBinding<MeshForm>> = buildList {
		fun shapeOf(pose: Pose, opens: Boolean): MeshForm = MeshForm(deltas(pose), opacity = visible(opens))
		if (options.vowels) for ((name, id) in VOWELS) add(BlendShapeBinding(id, floatArrayOf(0f, 1f), 0, listOf(null, shapeOf(vowel(name), true))))
		if (options.capture) {
			val rest = pose(0f, 0f)
			add(BlendShapeBinding(MOUTH_X, floatArrayOf(-1f, 0f, 1f), 1, listOf(shapeOf(rest.copy(shift = -0.18f, corner = 0.01f), false), null,
				shapeOf(rest.copy(shift = 0.18f, corner = 0.01f), false))))
			add(BlendShapeBinding(PUCKER_WIDEN, floatArrayOf(-1f, 0f, 1f), 1, listOf(shapeOf(rest.copy(width = rest.width * 0.7f, corner = -0.015f, lift = 0.01f), false), null,
				shapeOf(rest.copy(width = rest.width * 1.16f, corner = 0.01f), false))))
			add(BlendShapeBinding(SHRUG, floatArrayOf(0f, 1f), 0, listOf(null, shapeOf(rest.copy(corner = -0.025f, lift = 0.05f), false))))
			// The jaw opened behind closed lips ("mmm"): the shut mouth drops and narrows a little with the chin.
			add(BlendShapeBinding(JAW_OPEN, floatArrayOf(0f, 1f), 0, listOf(null, shapeOf(rest.copy(lift = -0.06f, width = rest.width * 0.94f), false))))
			val parted = staged(0.2f).let { (u, l) -> rest.copy(upper = u + 0.08f, lower = l * 0.7f) }
			add(BlendShapeBinding(PRESS_OPEN, floatArrayOf(-1f, 0f, 1f), 1, listOf(shapeOf(rest.copy(press = 0.7f, width = rest.width * 0.96f), false), null,
				shapeOf(parted, true))))
		}
	}

	/**
	 * The lower face following the jaw (`mouth.jaw`): below the mouth the skin drops with the opening, from nothing at
	 * the corners' line to [MouthOptions.jawShare] of the mouth's drawn opening at [chin], the cheeks narrowing a
	 * little; [nose] only nudges down. Over the closed mouth on the openness, and on the face capture's cheek puff.
	 */
	fun faceShapes(positions: FloatArray, frame: Bounds, chin: Float, nose: Boolean, mouthOpen: ParameterId, spring: ParameterId?): List<BlendShapeBinding<MeshForm>> = buildList {
		val drop = options.jawShare * h
		fun weight(v: Float) = ((v - 0.02f) / (chin - 0.02f).coerceAtLeast(0.05f)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
		fun jaw(amount: Float) = MeshForm(form(positions, frame) { u, v ->
			if (nose) 0f to amount * drop * 0.08f
			else weight(v).let { w -> -(u - 0.5f) * 0.25f * amount * drop * w to amount * drop * w }
		}.positionDeltas, opacity = Float.NaN)
		if (options.jaw) {
			add(BlendShapeBinding(mouthOpen, floatArrayOf(0f, 1f), 0, listOf(null, jaw(1f))))
			if (spring != null) add(BlendShapeBinding(spring, floatArrayOf(-1f, 0f, 1f), 1, listOf(jaw(-0.35f), null, jaw(0.35f))))
		}
		if (options.capture) add(BlendShapeBinding(JAW_OPEN, floatArrayOf(0f, 1f), 0, listOf(null, jaw(1.4f))))
		if (options.capture && !nose) add(BlendShapeBinding(CHEEK_PUFF, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(form(positions, frame) { u, v ->
			// The cheeks swell beside the mouth, most level with it and a little below.
			val side = u - 0.5f
			val across = ((abs(side) - 0.35f) / 0.6f).coerceIn(0f, 1f)
			val down = (1f - abs(v - 0.15f) / 0.75f).coerceIn(0f, 1f)
			val w = sin(across * Math.PI.toFloat() / 2f) * down * down
			(if (side >= 0f) 1f else -1f) * 0.1f * w to 0.02f * w
		}.positionDeltas, opacity = Float.NaN))))
	}

	/**
	 * The mouth's inside lagging behind its lips as the head turns (`mouth.interior-depth`): [depth] of the mouth's
	 * width against the turn, deeper parts more.
	 */
	fun depthShapes(positions: FloatArray, frame: Bounds, depth: Float, angleX: ParameterId, angleY: ParameterId): List<BlendShapeBinding<MeshForm>> {
		if (!options.depth) return emptyList()
		val d = depth * options.depthAmount
		fun shift(du: Float, dv: Float) = MeshForm(form(positions, frame) { _, _ -> du to dv }.positionDeltas, opacity = Float.NaN)
		val yaw = sin(Math.toRadians(30.0)).toFloat() * d
		return listOf(
			BlendShapeBinding(angleX, floatArrayOf(-30f, 0f, 30f), 1, listOf(shift(yaw * (if (fit.axisX >= 0f) 1f else -1f), 0f), null,
				shift(-yaw * (if (fit.axisX >= 0f) 1f else -1f), 0f))),
			BlendShapeBinding(angleY, floatArrayOf(-30f, 0f, 30f), 1, listOf(shift(0f, -yaw * 0.6f), null, shift(0f, yaw * 0.6f))),
		)
	}

	companion object {
		/** The openness where the lips part and a drawn closed mouth hands over to the open one. */
		const val SWAP = 0.05f
		val OPEN_KEYS = floatArrayOf(0f, SWAP, 0.25f, 0.5f, 1f)
		val FORM_KEYS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
		private val UPPERS = floatArrayOf(0f, 0.03f, 0.1f, 0.38f, 1f)
		private val WIDTHS = floatArrayOf(1.04f, 1.04f, 1.08f, 1.06f, 1f)

		val VOWELS = listOf('A', 'I', 'U', 'E', 'O').map { it to ParameterId("Param$it") }
		val MOUTH_X = ParameterId("ParamMouthX")
		val PUCKER_WIDEN = ParameterId("ParamMouthPuckerWiden")
		val SHRUG = ParameterId("ParamMouthShrug")
		val PRESS_OPEN = ParameterId("ParamMouthPressLipOpen")
		val CHEEK_PUFF = ParameterId("ParamCheekPuff")
		val JAW_OPEN = ParameterId("ParamJawOpen")
		val SPRING = ParameterId("ParamMouthSpring")
		/** The shapes that open the mouth: they show its open parts and hide a drawn closed mouth. */
		val OPENING = VOWELS.map { it.second }.toSet() + PRESS_OPEN

		private fun interpolate(keys: FloatArray, values: FloatArray, x: Float): Float {
			if (x <= keys.first()) return values.first()
			for (i in 1 until keys.size) if (x <= keys[i]) {
				val t = (x - keys[i - 1]) / (keys[i] - keys[i - 1])
				return values[i - 1] + (values[i] - values[i - 1]) * t
			}
			return values.last()
		}
	}
}

/**
 * The fitted mouth of a project: [rig] when the drawn mouth could be read ([result]), and which layers it moves;
 * [chin] is how far down the face reaches below the mouth, in the mouth's frame.
 */
class FittedMouth(val result: MouthFitting.Result, val rig: MouthRig?, val chin: Float = 0.6f, layers: List<io.github.psd2live.core.ClassifiedLayer> = emptyList()) {
	private val variant = result.layers.firstOrNull()?.semantic?.variant

	/** Whether a closed mouth is drawn beside the open one: it shows at rest and hands over as the lips part. */
	val closedLine: Boolean = layers.any { it.semantic.tag == io.github.psd2live.core.SemanticTag.MOUTH_CLOSE && it.opaquePixels > 0 && covers(it) }

	/** Whether [layer] is a part of the fitted mouth: the mouth itself, its closed line, teeth, tongue or inside. */
	fun covers(layer: io.github.psd2live.core.ClassifiedLayer): Boolean = rig != null && layer.semantic.role == null &&
		layer.semantic.variant == variant && layer.semantic.tag in TAGS

	companion object {
		val TAGS = setOf(io.github.psd2live.core.SemanticTag.MOUTH, io.github.psd2live.core.SemanticTag.MOUTH_OPEN,
			io.github.psd2live.core.SemanticTag.MOUTH_CLOSE, io.github.psd2live.core.SemanticTag.TOOTH_T,
			io.github.psd2live.core.SemanticTag.TOOTH_B, io.github.psd2live.core.SemanticTag.TONGUE)
	}
}
