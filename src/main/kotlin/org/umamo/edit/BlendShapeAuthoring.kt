package org.umamo.edit

import io.github.psd2live.core.RigKeyformChannelsEdit
import org.umamo.render.eval.activeBlendKeys
import org.umamo.render.eval.limitMultiplier
import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import kotlin.math.abs

/**
 * Blend-shape parameters add a delta on top of the keyform grid. A key at value 0 is the neutral
 * row and stores no form. Any other key stores the absolute form whose difference from the
 * default-pose grid is the delta the evaluator adds.
 */
internal fun PuppetModel.blendParametersIn(coordinate: Map<String, Float>): List<Parameter> =
	coordinate.mapNotNull { (name, value) ->
		val parameter = parameters.firstOrNull { it.id.raw == name } ?: return@mapNotNull null
		if (parameter.kind != ParameterKind.BLEND_SHAPE || abs(value) < EPS_KEY) null else parameter
	}

/** Grid axes named in a keyform coordinate. Blend-shape parameters are never grid axes. */
internal fun PuppetModel.gridCoordinateOf(coordinate: Map<String, Float>): Map<String, Float> {
	val blendIds = parameters.filter { it.kind == ParameterKind.BLEND_SHAPE }.map { it.id.raw }.toSet()
	return coordinate.filterKeys { it !in blendIds }
}

/**
 * Writes [observed] mesh deltas, warp points, or a rotation pivot into the single non-neutral
 * blend parameter named by the coordinate. The grid is left unchanged: the stored form is solved
 * so that grid(pose) + blends reproduces [observed].
 */
internal fun PuppetModel.withBlendShapeCaptured(
	owner: KeyformOwner,
	coordinate: Map<String, Float>,
	observedMesh: FloatArray? = null,
	observedWarp: FloatArray? = null,
	observedRotation: RotationPivotForm? = null,
	channels: RigKeyformChannelsEdit? = null,
): PuppetModel {
	val targets = blendParametersIn(coordinate)
	require(targets.size == 1) { "Name one blend shape parameter away from 0" }
	val parameter = targets.single()
	val value = coordinate.getValue(parameter.id.raw)
	require(value in parameter.min..parameter.max) { "Blend shape key is outside the parameter range" }
	val defaults = parameters.associate { it.id to it.default }
	val pose: (ParameterId) -> Float = { id -> coordinate[id.raw] ?: defaults[id] ?: 0f }
	val defaultValue: (ParameterId) -> Float = { id -> defaults[id] ?: 0f }
	var current = this
	if (parameter.keys != null && parameter.keys.none { abs(it - value) < EPS_KEY }) {
		current = current.withParameterKeys(parameter.id, parameter.keys + value)
	}
	return when (owner) {
		is KeyformOwner.Drawable -> current.captureDrawableBlend(owner, parameter, value, pose, defaultValue, observedMesh, channels)
		is KeyformOwner.Deformer -> current.captureDeformerBlend(owner, parameter, value, pose, defaultValue, observedWarp, observedRotation, channels)
		is KeyformOwner.Part -> current.capturePartBlend(owner, parameter, value, pose, defaultValue, channels)
		is KeyformOwner.Glue -> this
	}
}

internal fun PuppetModel.withoutBlendParameter(id: ParameterId): PuppetModel {
	fun <T : Any> List<BlendShapeBinding<T>>.drop(): List<BlendShapeBinding<T>> = filterNot { it.parameterId == id }
	return copy(
		drawables = drawables.map { drawable ->
			val next = drawable.blendShapes.drop()
			if (next.size == drawable.blendShapes.size) drawable else drawable.copy(blendShapes = next)
		},
		deformers = deformers.map { deformer ->
			when (deformer) {
				is Deformer.Warp -> {
					val next = deformer.blendShapes.drop()
					if (next.size == deformer.blendShapes.size) deformer else deformer.copy(blendShapes = next)
				}
				is Deformer.Rotation -> {
					val next = deformer.blendShapes.drop()
					if (next.size == deformer.blendShapes.size) deformer else deformer.copy(blendShapes = next)
				}
			}
		},
		parts = parts.map { part ->
			val next = part.blendShapes.drop()
			if (next.size == part.blendShapes.size) part else part.copy(blendShapes = next)
		},
	)
}

/** Moves or removes [from] on every blend binding driven by [parameterId]. Value 0 stays the neutral key. */
internal fun PuppetModel.retargetBlendKeys(parameterId: ParameterId, from: Float, to: Float?): PuppetModel {
	require(abs(from) >= EPS_KEY) { "The blend shape neutral key stays at 0" }
	if (to != null) require(abs(to) >= EPS_KEY) { "The blend shape neutral key stays at 0" }
	fun <T : Any> List<BlendShapeBinding<T>>.retarget(): List<BlendShapeBinding<T>> =
		mapNotNull { binding ->
			if (binding.parameterId != parameterId) binding else binding.retargetKey(from, to)
		}
	return copy(
		drawables = drawables.map { it.copy(blendShapes = it.blendShapes.retarget()) },
		deformers = deformers.map { deformer ->
			when (deformer) {
				is Deformer.Warp -> deformer.copy(blendShapes = deformer.blendShapes.retarget())
				is Deformer.Rotation -> deformer.copy(blendShapes = deformer.blendShapes.retarget())
			}
		},
		parts = parts.map { it.copy(blendShapes = it.blendShapes.retarget()) },
	)
}

internal fun PuppetModel.removeBlendBinding(owner: KeyformOwner, parameterId: ParameterId): PuppetModel {
	fun <T : Any> List<BlendShapeBinding<T>>.cut(): List<BlendShapeBinding<T>> = filterNot { it.parameterId == parameterId }
	return when (owner) {
		is KeyformOwner.Drawable -> copy(drawables = drawables.map { if (it.id == owner.id) it.copy(blendShapes = it.blendShapes.cut()) else it })
		is KeyformOwner.Deformer -> copy(deformers = deformers.map { deformer ->
			if (deformer.id != owner.id) deformer else when (deformer) {
				is Deformer.Warp -> deformer.copy(blendShapes = deformer.blendShapes.cut())
				is Deformer.Rotation -> deformer.copy(blendShapes = deformer.blendShapes.cut())
			}
		})
		is KeyformOwner.Part -> copy(parts = parts.map { if (it.id == owner.id) it.copy(blendShapes = it.blendShapes.cut()) else it })
		is KeyformOwner.Glue -> this
	}
}

internal fun PuppetModel.removeBlendKey(owner: KeyformOwner, parameterId: ParameterId, value: Float): PuppetModel {
	require(abs(value) >= EPS_KEY) { "The blend shape neutral key stays at 0" }
	fun <T : Any> List<BlendShapeBinding<T>>.cut(): List<BlendShapeBinding<T>> =
		mapNotNull { binding -> if (binding.parameterId == parameterId) binding.retargetKey(value, null) else binding }
	return when (owner) {
		is KeyformOwner.Drawable -> copy(drawables = drawables.map { if (it.id == owner.id) it.copy(blendShapes = it.blendShapes.cut()) else it })
		is KeyformOwner.Deformer -> copy(deformers = deformers.map { deformer ->
			if (deformer.id != owner.id) deformer else when (deformer) {
				is Deformer.Warp -> deformer.copy(blendShapes = deformer.blendShapes.cut())
				is Deformer.Rotation -> deformer.copy(blendShapes = deformer.blendShapes.cut())
			}
		})
		is KeyformOwner.Part -> copy(parts = parts.map { if (it.id == owner.id) it.copy(blendShapes = it.blendShapes.cut()) else it })
		is KeyformOwner.Glue -> this
	}
}

private fun <T : Any> BlendShapeBinding<T>.retargetKey(from: Float, to: Float?): BlendShapeBinding<T>? {
	val index = keys.indexOfFirst { abs(it - from) < EPS_KEY }
	if (index < 0) return this
	if (to == null) {
		val nextKeys = keys.filterIndexed { keyIndex, _ -> keyIndex != index }.toFloatArray()
		val nextForms = forms.filterIndexed { keyIndex, _ -> keyIndex != index }
		if (nextKeys.none { abs(it) >= EPS_KEY }) return null
		return copy(keys = nextKeys, neutralIndex = nextKeys.indexOfFirst { abs(it) < EPS_KEY }.coerceAtLeast(0), forms = nextForms)
	}
	require(keys.withIndex().none { (keyIndex, key) -> keyIndex != index && abs(key - to) < EPS_SPAN }) { "Keys must remain distinct" }
	val pairs = keys.indices.map { keyIndex -> (if (keyIndex == index) to else keys[keyIndex]) to forms[keyIndex] }.sortedBy { it.first }
	val nextKeys = pairs.map { it.first }.toFloatArray()
	return copy(keys = nextKeys, neutralIndex = nextKeys.indexOfFirst { abs(it) < EPS_KEY }.coerceAtLeast(0), forms = pairs.map { it.second })
}

private fun PuppetModel.captureDrawableBlend(
	owner: KeyformOwner.Drawable,
	parameter: Parameter,
	value: Float,
	pose: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
	observedMesh: FloatArray?,
	channels: RigKeyformChannelsEdit?,
): PuppetModel {
	val drawable = drawables.firstOrNull { it.id == owner.id } ?: return this
	val count = drawable.mesh?.positions?.size ?: observedMesh?.size ?: return this
	if (observedMesh != null) require(observedMesh.size == count) { "Mesh position deltas size mismatch" }
	val reference = meshGridDefaultDeltas(drawable, defaultValue) ?: FloatArray(count)
	val gridNow = meshDeltasAt(drawable, pose) ?: FloatArray(count)
	val binding = drawable.blendShapes.find { it.parameterId == parameter.id }
	val weight = binding.weightAt(value, pose)
	require(weight > 1e-4f) { "Blend shape key has zero weight" }
	val others = FloatArray(count)
	accumulateMeshOthers(drawable.blendShapes, binding, pose, reference, others)
	val existing = binding?.formAt(value)
	val solved = if (observedMesh == null) existing?.positionDeltas ?: reference.copyOf() else FloatArray(count) { index ->
		reference[index] + (observedMesh[index] - gridNow[index] - others[index]) / weight
	}
	val drawOrder = channels?.drawOrder ?: existing?.drawOrder ?: drawable.channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawable.drawOrder, defaultValue)
	val opacity = channels?.opacity ?: existing?.opacity ?: drawable.channelGrids.scalarAt(FormChannel.OPACITY, drawable.opacity, defaultValue)
	val multiply = channels.colorOr(channels?.multiplyColor, existing?.multiplyColor) { drawable.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor, defaultValue) }
	val screen = channels.colorOr(channels?.screenColor, existing?.screenColor) { drawable.channelGrids.colorAt(FormChannel.SCREEN_COLOR, drawable.screenColor, defaultValue) }
	val form = MeshForm(solved, drawOrder, opacity, multiply, screen)
	val next = (binding ?: emptyBinding(parameter.id)).withFormAt(value, form)
	return copy(drawables = drawables.map { if (it.id == drawable.id) it.copy(blendShapes = it.blendShapes.replace(next)) else it })
}

private fun PuppetModel.captureDeformerBlend(
	owner: KeyformOwner.Deformer,
	parameter: Parameter,
	value: Float,
	pose: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
	observedWarp: FloatArray?,
	observedRotation: RotationPivotForm?,
	channels: RigKeyformChannelsEdit?,
): PuppetModel {
	val deformer = deformers.firstOrNull { it.id == owner.id } ?: return this
	return when (deformer) {
		is Deformer.Warp -> {
			val count = (deformer.rows + 1) * (deformer.columns + 1) * 2
			val reference = warpControlPointsAt(deformer.geometryGrid, defaultValue) ?: FloatArray(count)
			val gridNow = warpControlPointsAt(deformer.geometryGrid, pose) ?: reference.copyOf()
			val binding = deformer.blendShapes.find { it.parameterId == parameter.id }
			val weight = binding.weightAt(value, pose)
			require(weight > 1e-4f) { "Blend shape key has zero weight" }
			val others = FloatArray(reference.size)
			for (candidate in deformer.blendShapes) {
				if (candidate.parameterId == parameter.id) continue
				addWeighted(candidate, pose, reference.size) { form, component -> form.controlPoints.getOrElse(component) { 0f } - reference.getOrElse(component) { 0f } }.let { delta ->
					for (index in others.indices) others[index] += delta[index]
				}
			}
			val existing = binding?.formAt(value)
			val solved = if (observedWarp == null) existing?.controlPoints ?: reference.copyOf() else {
				require(observedWarp.size == reference.size) { "Warp control points size mismatch" }
				FloatArray(reference.size) { index -> reference[index] + (observedWarp[index] - gridNow[index] - others[index]) / weight }
			}
			val form = WarpForm(
				solved,
				channels?.opacity ?: existing?.opacity ?: deformer.channelGrids.scalarAt(FormChannel.OPACITY, deformer.opacity, defaultValue),
				channels.colorOr(channels?.multiplyColor, existing?.multiplyColor) { deformer.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, deformer.multiplyColor, defaultValue) },
				channels.colorOr(channels?.screenColor, existing?.screenColor) { deformer.channelGrids.colorAt(FormChannel.SCREEN_COLOR, deformer.screenColor, defaultValue) },
			)
			val next = (binding ?: emptyBinding(parameter.id)).withFormAt(value, form)
			copy(deformers = deformers.map { if (it.id == deformer.id && it is Deformer.Warp) it.copy(blendShapes = it.blendShapes.replace(next)) else it })
		}
		is Deformer.Rotation -> {
			val reference = rotationFormAt(deformer.geometryGrid, defaultValue) ?: RotationPivotForm(0f, 0f, 0f, 1f)
			val gridNow = rotationFormAt(deformer.geometryGrid, pose) ?: reference
			val binding = deformer.blendShapes.find { it.parameterId == parameter.id }
			val weight = binding.weightAt(value, pose)
			require(weight > 1e-4f) { "Blend shape key has zero weight" }
			var otherX = 0f; var otherY = 0f; var otherAngle = 0f; var otherScale = 0f
			for (candidate in deformer.blendShapes) {
				if (candidate.parameterId == parameter.id) continue
				val active = activeBlendKeys(candidate, pose(candidate.parameterId), limitMultiplier(candidate.limits, pose))
				for (key in active) {
					val form = candidate.forms[key.keyIndex] ?: continue
					otherX += key.weight * (form.originX - reference.originX)
					otherY += key.weight * (form.originY - reference.originY)
					otherAngle += key.weight * (form.angle - reference.angle)
					otherScale += key.weight * (form.scale - reference.scale)
				}
			}
			val existing = binding?.formAt(value)
			val observed = observedRotation
			val originX = if (observed == null) existing?.originX ?: reference.originX else reference.originX + (observed.originX - gridNow.originX - otherX) / weight
			val originY = if (observed == null) existing?.originY ?: reference.originY else reference.originY + (observed.originY - gridNow.originY - otherY) / weight
			val angle = if (observed == null) existing?.angle ?: reference.angle else reference.angle + (observed.angle - gridNow.angle - otherAngle) / weight
			val scale = if (observed == null) existing?.scale ?: reference.scale else reference.scale + (observed.scale - gridNow.scale - otherScale) / weight
			val form = RotationForm(
				originX, originY, angle, scale,
				existing?.flipX ?: false, existing?.flipY ?: false,
				channels?.opacity ?: existing?.opacity ?: deformer.channelGrids.scalarAt(FormChannel.OPACITY, deformer.opacity, defaultValue),
				channels.colorOr(channels?.multiplyColor, existing?.multiplyColor) { deformer.channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, deformer.multiplyColor, defaultValue) },
				channels.colorOr(channels?.screenColor, existing?.screenColor) { deformer.channelGrids.colorAt(FormChannel.SCREEN_COLOR, deformer.screenColor, defaultValue) },
			)
			val next = (binding ?: emptyBinding(parameter.id)).withFormAt(value, form)
			copy(deformers = deformers.map { if (it.id == deformer.id && it is Deformer.Rotation) it.copy(blendShapes = it.blendShapes.replace(next)) else it })
		}
	}
}

private fun PuppetModel.capturePartBlend(
	owner: KeyformOwner.Part,
	parameter: Parameter,
	value: Float,
	pose: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
	channels: RigKeyformChannelsEdit?,
): PuppetModel {
	val part = parts.firstOrNull { it.id == owner.id } ?: return this
	val binding = part.blendShapes.find { it.parameterId == parameter.id }
	val weight = binding.weightAt(value, pose)
	require(weight > 1e-4f) { "Blend shape key has zero weight" }
	val reference = part.channelGrids.scalarAt(FormChannel.DRAW_ORDER, part.drawOrder.toFloat(), defaultValue)
	val existing = binding?.formAt(value)
	val drawOrder = channels?.drawOrder ?: existing?.drawOrder ?: reference
	val form = PartForm(drawOrder, channels?.opacity ?: existing?.opacity ?: part.composite.opacity, existing?.multiplyColor ?: ColorRgb.MultiplyIdentity, existing?.screenColor ?: ColorRgb.ScreenIdentity)
	val next = (binding ?: emptyBinding(parameter.id)).withFormAt(value, form)
	return copy(parts = parts.map { if (it.id == part.id) it.copy(blendShapes = it.blendShapes.replace(next)) else it })
}

private fun <T : Any> emptyBinding(parameterId: ParameterId): BlendShapeBinding<T> =
	BlendShapeBinding(parameterId, floatArrayOf(0f), 0, listOf(null))

private fun <T : Any> BlendShapeBinding<T>?.weightAt(value: Float, pose: (ParameterId) -> Float): Float {
	val binding = this
	val limits = if (binding == null || binding.limits.isEmpty()) 1f else limitMultiplier(binding.limits, pose)
	if (binding == null) return limits
	val index = binding.keys.indexOfFirst { abs(it - value) < EPS_KEY }
	if (index < 0) return limits
	val active = activeBlendKeys(binding, value, limits)
	return active.firstOrNull { it.keyIndex == index }?.weight ?: 0f
}

private fun <T : Any> BlendShapeBinding<T>.formAt(value: Float): T? {
	val index = keys.indexOfFirst { abs(it - value) < EPS_KEY }
	return if (index < 0) null else forms[index]
}

private fun <T : Any> BlendShapeBinding<T>.withFormAt(value: Float, form: T): BlendShapeBinding<T> {
	val base = if (keys.any { abs(it) < EPS_KEY }) this else {
		val pairs = (listOf(0f to null) + keys.indices.map { keys[it] to forms[it] }).sortedBy { it.first }
		copy(
			keys = pairs.map { it.first }.toFloatArray(),
			neutralIndex = pairs.indexOfFirst { abs(it.first) < EPS_KEY },
			forms = pairs.map { it.second },
		)
	}
	val index = base.keys.indexOfFirst { abs(it - value) < EPS_KEY }
	if (index >= 0) {
		if (index == base.neutralIndex) return base
		return base.copy(forms = base.forms.mapIndexed { formIndex, current -> if (formIndex == index) form else current })
	}
	val ordered = ArrayList<Pair<Float, T?>>(base.keys.size + 1)
	for (source in base.keys.indices) ordered += base.keys[source] to base.forms[source]
	ordered += value to form
	ordered.sortBy { it.first }
	val nextKeys = ordered.map { it.first }.toFloatArray()
	return base.copy(
		keys = nextKeys,
		neutralIndex = nextKeys.indexOfFirst { abs(it) < EPS_KEY }.coerceAtLeast(0),
		forms = ordered.map { it.second },
	)
}

private fun <T : Any> List<BlendShapeBinding<T>>.replace(binding: BlendShapeBinding<T>): List<BlendShapeBinding<T>> {
	val index = indexOfFirst { it.parameterId == binding.parameterId }
	return if (index < 0) this + binding else toMutableList().also { it[index] = binding }
}

private fun meshDeltasAt(drawable: Drawable, pose: (ParameterId) -> Float): FloatArray? {
	val grid = drawable.geometryGrid ?: return null
	val corners = gridCorners(grid, pose) ?: return null
	val deltas = FloatArray(grid.cells.firstOrNull()?.form?.positionDeltas?.size ?: 0)
	val cells = cellsByLinearIndex(grid)
	for (corner in corners) {
		val form = cells[corner.linearIndex]?.form ?: continue
		for (index in deltas.indices) deltas[index] += corner.weight * form.positionDeltas[index]
	}
	return deltas
}

private fun accumulateMeshOthers(
	bindings: List<BlendShapeBinding<MeshForm>>,
	skip: BlendShapeBinding<MeshForm>?,
	pose: (ParameterId) -> Float,
	reference: FloatArray,
	into: FloatArray,
) {
	for (candidate in bindings) {
		if (skip != null && candidate.parameterId == skip.parameterId) continue
		val delta = addWeighted(candidate, pose, into.size) { form, component ->
			form.positionDeltas.getOrElse(component) { 0f } - reference.getOrElse(component) { 0f }
		}
		for (index in into.indices) into[index] += delta[index]
	}
}

private fun <T : Any> addWeighted(
	binding: BlendShapeBinding<T>,
	pose: (ParameterId) -> Float,
	size: Int,
	component: (T, Int) -> Float,
): FloatArray {
	val out = FloatArray(size)
	val active = activeBlendKeys(binding, pose(binding.parameterId), limitMultiplier(binding.limits, pose))
	for (key in active) {
		val form = binding.forms[key.keyIndex] ?: continue
		for (index in out.indices) out[index] += key.weight * component(form, index)
	}
	return out
}

private fun RigKeyformChannelsEdit?.colorOr(explicit: List<Float>?, existing: ColorRgb?, fallback: () -> ColorRgb): ColorRgb {
	if (explicit != null && explicit.size == 3) return ColorRgb(explicit[0], explicit[1], explicit[2])
	return existing ?: fallback()
}
