package io.github.psd2live.ui

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.allAxes
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.abs

/**
 * A parameter's key marks: values it is keyed at, split by source. Grid keys come from keyform-grid
 * axes (circle points); blend keys come from blend-shape bindings (square points).
 */
data class ParameterKeyMarks(
	val gridKeys: List<Float>,
	val blendKeys: List<Float>,
) {
	val allKeys: List<Float>
		get() = (gridKeys + blendKeys).distinct().sorted()
}

/**
 * Per-parameter key marks for this rig: sorted, deduplicated unions of every object's keyform-grid and
 * blend-shape keys. Mirrors Cubism / umamo's parameter-panel key points.
 */
fun PuppetModel.parameterKeyMarks(): Map<ParameterId, ParameterKeyMarks> {
	val gridKeysByParameter = HashMap<ParameterId, MutableSet<Float>>()
	val blendKeysByParameter = HashMap<ParameterId, MutableSet<Float>>()

	fun addGridKeys(parameterId: ParameterId, keys: FloatArray) {
		val values = gridKeysByParameter.getOrPut(parameterId) { sortedSetOf() }
		keys.forEach { keyValue -> values.add(keyValue) }
	}

	fun addBlendKeys(parameterId: ParameterId, keys: FloatArray) {
		val values = blendKeysByParameter.getOrPut(parameterId) { sortedSetOf() }
		keys.forEach { keyValue -> values.add(keyValue) }
	}

	for (drawable in drawables) {
		drawable.geometryGrid?.axes?.forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
		drawable.channelGrids.allAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
		drawable.blendShapes.forEach { binding -> addBlendKeys(binding.parameterId, binding.keys) }
	}
	for (deformer in deformers) {
		when (deformer) {
			is Deformer.Warp -> {
				deformer.geometryGrid?.axes?.forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
				deformer.channelGrids.allAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
				deformer.blendShapes.forEach { binding -> addBlendKeys(binding.parameterId, binding.keys) }
			}
			is Deformer.Rotation -> {
				deformer.geometryGrid?.axes?.forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
				deformer.channelGrids.allAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
				deformer.blendShapes.forEach { binding -> addBlendKeys(binding.parameterId, binding.keys) }
			}
		}
	}
	for (part in parts) {
		part.channelGrids.allAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
	}
	for (glue in glues) {
		glue.channelGrids.allAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
	}

	val keyedParameters = gridKeysByParameter.keys + blendKeysByParameter.keys
	return keyedParameters.associateWith { parameterId ->
		ParameterKeyMarks(
			gridKeys = gridKeysByParameter[parameterId]?.toList() ?: emptyList(),
			blendKeys = blendKeysByParameter[parameterId]?.toList() ?: emptyList(),
		)
	}
}

/** True when [value] sits on one of [keys] within the evaluator's [EPS_KEY] snap tolerance. */
fun isOnParameterKey(value: Float, keys: FloatArray): Boolean =
	keys.any { key -> abs(key - value) < EPS_KEY }

/** The key in [keys] nearest to [value], or null when [keys] is empty. */
fun nearestParameterKey(value: Float, keys: FloatArray): Float? {
	if (keys.isEmpty()) return null
	var best = keys[0]
	var bestDistance = abs(keys[0] - value)
	for (index in 1 until keys.size) {
		val distance = abs(keys[index] - value)
		if (distance < bestDistance) {
			best = keys[index]
			bestDistance = distance
		}
	}
	return best
}

/**
 * For each axis in [axes], the nearest key to the current pose value when the pose is off-key.
 * Returns an empty map when every axis is already on-key (or has no keys).
 */
fun nearestKeyPose(
	axes: List<KeyformAxis>,
	pose: Map<ParameterId, Float>,
	defaults: Map<ParameterId, Float>,
): Map<ParameterId, Float> {
	val targets = LinkedHashMap<ParameterId, Float>()
	for (axis in axes) {
		if (axis.keys.isEmpty()) continue
		val current = pose[axis.parameterId] ?: defaults[axis.parameterId] ?: continue
		if (isOnParameterKey(current, axis.keys)) continue
		val nearest = nearestParameterKey(current, axis.keys) ?: continue
		targets[axis.parameterId] = nearest
	}
	return targets
}

/**
 * Keyform axes that the given geometry kind/id writes when deformed: geometry grid plus channel tracks.
 */
fun PuppetModel.keyformAxesFor(kind: String, id: String): List<KeyformAxis> =
	when (kind) {
		"mesh" -> {
			val drawable = drawables.firstOrNull { it.id.raw == id } ?: return emptyList()
			(drawable.geometryGrid?.axes.orEmpty()) + drawable.channelGrids.allAxes()
		}
		"warp" -> {
			val warp = deformers.firstOrNull { it.id.raw == id } as? Deformer.Warp ?: return emptyList()
			(warp.geometryGrid?.axes.orEmpty()) + warp.channelGrids.allAxes()
		}
		"rotation" -> {
			val rotation = deformers.firstOrNull { it.id.raw == id } as? Deformer.Rotation ?: return emptyList()
			(rotation.geometryGrid?.axes.orEmpty()) + rotation.channelGrids.allAxes()
		}
		else -> emptyList()
	}
