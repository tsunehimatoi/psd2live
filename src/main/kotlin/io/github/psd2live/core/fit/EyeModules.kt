package io.github.psd2live.core.fit

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PartRole
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId

/**
 * Which eye modules a project builds on its fitted eyes, with their values (the knobs, as fractions). Strength values
 * are 1 at the module's default.
 */
data class EyeOptions(
	val smile: Boolean = false, val smileArc: Float = 0.12f, val smilePush: Float = 0.5f,
	val wide: Boolean = false, val wideLift: Float = 0.1f,
	val gaze: Boolean = false, val gazeFollow: Float = 1f,
	val emotion: Boolean = false, val emotionStrength: Float = 1f,
	val lidFade: Boolean = false,
	val aftershock: Boolean = false, val softness: Float = 1f,
	val highlight: Boolean = false, val parallax: Float = 1f,
	val squash: Boolean = false,
	val browCoupling: Boolean = false, val browStrength: Float = 1f,
	val cheekPush: Boolean = false, val cheekStrength: Float = 1f,
)

/**
 * The geometry and channels the eye modules give the meshes of a fitted eye ([EyeRig]): the openness axis (widened to 2
 * by `eye.wide`) and the smile axis (`eye.smile`) of the lids, the additive shapes of the gaze (`eye.gaze-lid`), the
 * emotions (`eye.emotion`) and the blink's aftershock (`eye.aftershock`), the closed line bending into a smile, the
 * iris squashing as the lid closes (`eye.squash`), the highlights drifting against the
 * head's turn (`eye.highlight`) and the lid lines fading before the lid closes (`eye.lid-fade`).
 */
object EyeModules {
	val EYE_BALL_L_X = ParameterId("ParamEyeBallLX")
	val EYE_BALL_L_Y = ParameterId("ParamEyeBallLY")
	val EYE_BALL_R_X = ParameterId("ParamEyeBallRX")
	val EYE_BALL_R_Y = ParameterId("ParamEyeBallRY")
	val EYE_L_SMILE = ParameterId("ParamEyeLSmile")
	val EYE_R_SMILE = ParameterId("ParamEyeRSmile")
	val EYE_L_FORM = ParameterId("ParamEyeLForm")
	val EYE_R_FORM = ParameterId("ParamEyeRForm")
	val EYE_L_SQUINT = ParameterId("ParamEyeLSquint")
	val EYE_R_SQUINT = ParameterId("ParamEyeRSquint")
	val EYE_L_BOUNCE = ParameterId("ParamEyeLBounce")
	val EYE_R_BOUNCE = ParameterId("ParamEyeRBounce")
	private val EYE_L_OPEN = ParameterId("ParamEyeLOpen")
	private val EYE_R_OPEN = ParameterId("ParamEyeROpen")
	private val EYE_BALL_X = ParameterId("ParamEyeBallX")
	private val EYE_BALL_Y = ParameterId("ParamEyeBallY")
	private val EYE_BALL_FORM = ParameterId("ParamEyeBallForm")
	private val ANGLE_X = ParameterId("ParamAngleX")
	private val MOUTH_FORM = ParameterId("ParamMouthForm")
	private val ANGLE_Y = ParameterId("ParamAngleY")

	fun open(side: Side) = if (side == Side.LEFT) EYE_L_OPEN else EYE_R_OPEN
	fun smile(side: Side) = if (side == Side.LEFT) EYE_L_SMILE else EYE_R_SMILE
	private fun form(side: Side) = if (side == Side.LEFT) EYE_L_FORM else EYE_R_FORM
	private fun squint(side: Side) = if (side == Side.LEFT) EYE_L_SQUINT else EYE_R_SQUINT
	private fun bounce(side: Side) = if (side == Side.LEFT) EYE_L_BOUNCE else EYE_R_BOUNCE

	/** How a lid layer follows its eye; null for a layer the lids do not move. */
	fun meshOf(tag: SemanticTag, role: PartRole?): EyeRig.EyeMesh? = when {
		tag == SemanticTag.EYEWHITE -> EyeRig.EyeMesh.WHITE
		tag != SemanticTag.EYELASH -> null
		role == PartRole.LOWER -> EyeRig.EyeMesh.LOWER_LASH
		role == PartRole.LID || role == PartRole.SHADOW -> EyeRig.EyeMesh.SKIN
		else -> EyeRig.EyeMesh.UPPER_LASH
	}

	/** The openness keys of [eye], with the widened eye's beyond 1. */
	fun openKeys(eye: EyeRig, options: EyeOptions): FloatArray = if (options.wide) eye.keys + floatArrayOf(1.5f, 2f) else eye.keys

	/** The closed drawing and liner handover, held explicitly through the entire openness range. */
	internal fun drawingOpacity(side: Side, closedLine: Boolean, swap: Boolean, maxOpen: Float): KeyformGrid<ChannelValue> {
		val basic = if (swap) floatArrayOf(0f, EyeRig.SWAP, 1f) else floatArrayOf(0f, 1f)
		val keys = if (maxOpen > 1f) basic + maxOpen else basic
		return KeyformGrid(listOf(KeyformAxis(open(side), keys)), keys.mapIndexed { i, value ->
			val alpha = if (!closedLine) { if (value == 0f) 0f else 1f }
				else if (swap) { if (value == 0f) 1f else 0f }
				else (1f - value).coerceIn(0f, 1f)
			KeyformCell(intArrayOf(i), ChannelValue.Scalar(alpha))
		})
	}

	/**
	 * A lid mesh's grid: the openness keys, by the smile when the eye smiles. [positions] are the mesh's canvas points
	 * in the rig's space, [frame] the parent frame the deltas are normalized by.
	 */
	fun lidGrid(mesh: EyeRig.EyeMesh, side: Side, positions: FloatArray, frame: Bounds, eye: EyeRig, options: EyeOptions): KeyformGrid<MeshDeltaForm> {
		val keys = openKeys(eye, options)
		val axes = listOfNotNull(KeyformAxis(open(side), keys), if (options.smile) KeyformAxis(smile(side), floatArrayOf(0f, 1f)) else null)
		val smiles = if (options.smile) floatArrayOf(0f, 1f) else floatArrayOf(0f)
		val cells = ArrayList<KeyformCell<MeshDeltaForm>>()
		for ((s, smileValue) in smiles.withIndex()) for ((o, open) in keys.withIndex()) {
			val pose = if (smileValue == 0f || open > 1f) eye.pose(open) else eye.smilePose(open)
			cells += KeyformCell(if (options.smile) intArrayOf(o, s) else intArrayOf(o),
				if (open == 1f && smileValue == 0f) MeshDeltaForm(FloatArray(positions.size)) else form(mesh, positions, frame, eye, pose))
		}
		return KeyformGrid(axes, cells)
	}

	private fun form(mesh: EyeRig.EyeMesh, positions: FloatArray, frame: Bounds, eye: EyeRig, pose: EyeRig.Pose): MeshDeltaForm {
		val delta = FloatArray(positions.size)
		for (i in positions.indices step 2) {
			val (dx, dy) = eye.move(mesh, positions[i], positions[i + 1], pose)
			delta[i] = dx / frame.width.coerceAtLeast(1e-4f); delta[i + 1] = dy / frame.height.coerceAtLeast(1e-4f)
		}
		return MeshDeltaForm(delta)
	}

	/** An additive shape keyed on [parameter] at [keys], its forms at each key from [poseAt] (null at the neutral key). */
	private fun shape(parameter: ParameterId, keys: FloatArray, neutral: Int, mesh: EyeRig.EyeMesh, positions: FloatArray, frame: Bounds,
	                  eye: EyeRig, limit: BlendWeightLimit?, poseAt: (Float) -> EyeRig.Pose): BlendShapeBinding<MeshForm> =
		BlendShapeBinding(parameter, keys, neutral, keys.mapIndexed { i, key ->
			if (i == neutral) null else MeshForm(form(mesh, positions, frame, eye, poseAt(key)).positionDeltas)
		}, listOfNotNull(limit))

	/**
	 * A lid mesh's additive shapes: the lids following the gaze, the emotion and squint, and the blink's aftershock.
	 * Each fades out as the eye closes ([BlendWeightLimit] on the openness), so a shut eye keeps its closed line.
	 */
	fun lidShapes(mesh: EyeRig.EyeMesh, side: Side, positions: FloatArray, frame: Bounds, eye: EyeRig, options: EyeOptions): List<BlendShapeBinding<MeshForm>> {
		val h = eye.opening
		val hump = EyeRig::hump
		val fade = BlendWeightLimit(open(side), listOf(BlendWeightLimitPoint(eye.crease, 0f), BlendWeightLimitPoint((eye.crease + 1f) / 2f, 1f)))
		// u runs from the inner corner (0) to the outer one (1).
		return buildList {
			if (options.gaze) {
				val k = options.gazeFollow
				add(shape(EYE_BALL_Y, floatArrayOf(-1f, 0f, 1f), 1, mesh, positions, frame, eye, fade) { y ->
					// Looking up the upper lid lifts with the eye; looking down it drops further and the lower lid follows a little.
					if (y > 0f) eye.shape({ u -> -0.14f * k * h * hump(u) }, { u -> -0.05f * k * h * hump(u) })
					else eye.shape({ u -> 0.22f * k * h * hump(u) }, { u -> 0.06f * k * h * hump(u) })
				})
				add(shape(EYE_BALL_X, floatArrayOf(-1f, 0f, 1f), 1, mesh, positions, frame, eye, fade) { x ->
					// The lid's peak leans toward where the eye looks. The canvas's x runs one way, the eye frame's u the other on one side.
					val toward = x * (if (eye.fit.axisX >= 0f) 1f else -1f)
					eye.shape({ u -> -0.06f * k * h * hump(u) * (u - 0.5f) * 2f * toward }, { 0f })
				})
			}
			if (options.gaze && side != Side.NONE) {
				val x = if (side == Side.LEFT) EYE_BALL_L_X else EYE_BALL_R_X
				val y = if (side == Side.LEFT) EYE_BALL_L_Y else EYE_BALL_R_Y
				addAll(toList().filter { it.parameterId == EYE_BALL_X || it.parameterId == EYE_BALL_Y }
					.map { it.copy(parameterId = if (it.parameterId == EYE_BALL_X) x else y) })
			}
			if (options.emotion) {
				val k = options.emotionStrength
				add(shape(form(side), floatArrayOf(-1f, 0f, 1f), 1, mesh, positions, frame, eye, fade) { f ->
					if (f > 0f) eye.shape(
						// Angry: the inner half of the upper lid presses down and the lid flattens; the lower lid tightens.
						{ u -> 0.32f * k * h * hump(u) * (1.15f - u) }, { u -> -0.08f * k * h * hump(u) })
					else eye.shape(
						// Sad: the inner end of the upper lid lifts while the outer end droops.
						{ u -> k * h * hump(u) * (-0.18f * (1f - u) + 0.22f * u) }, { u -> -0.04f * k * h * hump(u) })
				})
				add(shape(squint(side), floatArrayOf(0f, 1f), 0, mesh, positions, frame, eye, fade) {
					// Squinting: the lower lid pushes up, the upper one comes down a little.
					eye.shape({ u -> 0.1f * k * h * hump(u) }, { u -> -0.32f * k * h * hump(u) })
				})
			}
			if (options.cheekPush) {
				val k = options.cheekStrength
				add(shape(MOUTH_FORM, floatArrayOf(-1f, 0f, 1f), 1, mesh, positions, frame, eye, fade) { f ->
					// A smile lifts the cheeks, which push the lower lid up and lean on the upper one a little.
					val s = f.coerceAtLeast(0f)
					eye.shape({ u -> 0.04f * k * s * h * hump(u) }, { u -> -0.16f * k * s * h * hump(u) })
				})
			}
			if (options.browCoupling) {
				val k = options.browStrength
				val y = ParameterId(if (side == Side.LEFT) "ParamBrowLY" else "ParamBrowRY")
				val angle = ParameterId(if (side == Side.LEFT) "ParamBrowLAngle" else "ParamBrowRAngle")
				add(shape(y, floatArrayOf(-1f, 0f, 1f), 1, mesh, positions, frame, eye, fade) { b ->
					// The brow and the upper lid are one skin: lowered, the brow presses the lid down and flat; raised, it lifts it.
					if (b < 0f) eye.shape({ u -> 0.16f * k * h * hump(u) * (1.1f - 0.4f * u) }, { 0f })
					else eye.shape({ u -> -0.1f * k * h * hump(u) }, { 0f })
				})
				add(shape(angle, floatArrayOf(-1f, 0f, 1f), 1, mesh, positions, frame, eye, fade) { a ->
					// Knitted (inner end down), the inner lid follows into a glare; worried (inner end up), it lifts.
					eye.shape({ u -> -0.14f * k * a * h * hump(u) * (1f - u) * 1.6f }, { 0f })
				})
			}
			if (options.aftershock) {
				val k = options.softness
				add(shape(bounce(side), floatArrayOf(-1f, 0f, 1f), 1, mesh, positions, frame, eye, null) { b ->
					// The lid overshoots and settles after a blink; the lashes flick with it.
					eye.shape({ u -> -0.09f * k * b * h * hump(u) }, { u -> 0.02f * k * b * h * hump(u) })
				})
			}
		}
	}

	/**
	 * The drawn closed line of a smiling eye: bent from its drawn downward arc onto the smile's upward one, along the
	 * smile axis; null when the eye does not smile or draws no closed line.
	 */
	fun closedLineGrid(side: Side, positions: FloatArray, frame: Bounds, eye: EyeRig, options: EyeOptions): KeyformGrid<MeshDeltaForm>? {
		val drawn = eye.fit.closed ?: return null
		if (!options.smile) return null
		val bent = FloatArray(positions.size)
		for (i in positions.indices step 2) {
			val (u, _) = eye.fit.toEye(positions[i], positions[i + 1])
			val (dx, dy) = eye.fit.canvasDelta(0f, EyeFit.sample(eye.smileClosed, u) - EyeFit.sample(drawn, u))
			bent[i] = dx / frame.width.coerceAtLeast(1e-4f); bent[i + 1] = dy / frame.height.coerceAtLeast(1e-4f)
		}
		return KeyformGrid(listOf(KeyformAxis(smile(side), floatArrayOf(0f, 1f))),
			listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size))), KeyformCell(intArrayOf(1), MeshDeltaForm(bent))))
	}

	/**
	 * An iris's grid: its jelly ([jelly] gives a point's place at a jelly value) by the openness - squashed toward its
	 * bottom as the lid closes (`eye.squash`). Widening the lids keeps the drawn iris size and position: pupil changes
	 * belong to the separate expression shapes. Null when squashing is off.
	 */
	fun irisGrid(side: Side, positions: FloatArray, frame: Bounds, iris: Bounds, eye: EyeRig, options: EyeOptions,
	             jelly: (Float, Float, Float) -> Pair<Float, Float>): KeyformGrid<MeshDeltaForm>? {
		if (!options.squash) return null
		val opens = buildList {
			if (options.squash) { add(0f); add(eye.crease) }
			add(1f)
			if (options.wide) add(2f)
		}.toFloatArray()
		val jellies = floatArrayOf(-1f, 0f, 1f)
		val cells = ArrayList<KeyformCell<MeshDeltaForm>>()
		for ((o, open) in opens.withIndex()) for ((j, value) in jellies.withIndex()) {
			val scaleY = when {
				open < 1f -> 0.82f + 0.18f * (open / eye.crease).coerceIn(0f, 1f)
				else -> 1f
			}
			val delta = FloatArray(positions.size)
			for (i in positions.indices step 2) {
				val (jx, jy) = jelly(positions[i], positions[i + 1], value)
				val x = jx
				val y = iris.bottom + (jy - iris.bottom) * scaleY
				delta[i] = (x - positions[i]) / frame.width.coerceAtLeast(1e-4f); delta[i + 1] = (y - positions[i + 1]) / frame.height.coerceAtLeast(1e-4f)
			}
			cells += KeyformCell(intArrayOf(j, o), MeshDeltaForm(delta))
		}
		return KeyformGrid(listOf(KeyformAxis(EYE_BALL_FORM, jellies), KeyformAxis(open(side), opens)), cells)
	}

	/**
	 * A highlight's shapes (`eye.highlight`): it drifts against the head's turn, as a reflection of a light that stays
	 * put, by up to a fifth of its iris's width at full turn.
	 */
	/**
	 * The pupil's share of the mood (`brow.lid-coupling`): raised brows - surprise - draw the iris in a little, lowered
	 * brows let it open, about the iris's middle ([iris]).
	 */
	fun pupilShapes(side: Side, positions: FloatArray, frame: Bounds, iris: Bounds, options: EyeOptions): List<BlendShapeBinding<MeshForm>> {
		if (!options.browCoupling) return emptyList()
		fun scaled(k: Float) = MeshForm(FloatArray(positions.size) { i ->
			if (i % 2 == 0) (positions[i] - iris.centerX) * (k - 1f) / frame.width.coerceAtLeast(1e-4f)
			else (positions[i] - iris.centerY) * (k - 1f) / frame.height.coerceAtLeast(1e-4f)
		})
		val y = ParameterId(if (side == Side.LEFT) "ParamBrowLY" else "ParamBrowRY")
		val k = 0.08f * options.browStrength
		return listOf(BlendShapeBinding(y, floatArrayOf(-1f, 0f, 1f), 1, listOf(scaled(1f + k * 0.5f), null, scaled(1f - k))))
	}

	fun highlightShapes(positions: FloatArray, frame: Bounds, iris: Bounds, options: EyeOptions): List<BlendShapeBinding<MeshForm>> {
		if (!options.highlight) return emptyList()
		fun shift(dx: Float, dy: Float) = MeshForm(FloatArray(positions.size) { i ->
			if (i % 2 == 0) dx / frame.width.coerceAtLeast(1e-4f) else dy / frame.height.coerceAtLeast(1e-4f)
		})
		val reach = 0.2f * iris.width * options.parallax
		return listOf(
			BlendShapeBinding(ANGLE_X, floatArrayOf(-30f, 0f, 30f), 1, listOf(shift(reach, 0f), null, shift(-reach, 0f))),
			BlendShapeBinding(ANGLE_Y, floatArrayOf(-30f, 0f, 30f), 1, listOf(shift(0f, -0.6f * reach), null, shift(0f, 0.6f * reach))),
		)
	}

	/**
	 * The opacity of an eye layer the modules fade: lid lines and shadows ([PartRole.LID], [PartRole.SHADOW]) sink into
	 * the closing lid well before it shuts (`eye.lid-fade`); highlights go out before the white folds over them
	 * (`eye.highlight`). Null for other layers.
	 */
	fun opacity(tag: SemanticTag, role: PartRole?, side: Side, eye: EyeRig, options: EyeOptions): KeyformGrid<ChannelValue>? {
		val keys = when {
			options.lidFade && tag == SemanticTag.EYELASH && (role == PartRole.LID || role == PartRole.SHADOW) ->
				floatArrayOf(0f, eye.crease + 0.15f, 0.75f, 1f) to floatArrayOf(0f, 0f, 1f, 1f)
			options.highlight && tag == SemanticTag.IRIDES && role == PartRole.HIGHLIGHT ->
				floatArrayOf(0f, eye.crease, (eye.crease + 1f) / 2f, 1f) to floatArrayOf(0f, 0f, 1f, 1f)
			else -> return null
		}
		val opens = if (options.wide) keys.first + 2f else keys.first
		val values = if (options.wide) keys.second + keys.second.last() else keys.second
		return KeyformGrid(listOf(KeyformAxis(open(side), opens)),
			opens.indices.map { KeyformCell(intArrayOf(it), ChannelValue.Scalar(values[it])) })
	}
}
