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

/** Stable object identity for selection-scoped parameter marks. */
data class ParameterKeyOwner(val kind: String, val id: String)

/**
 * A selectable (or listed) component that carries a keyform / blend key at a parameter key value.
 *
 * [kind] matches [ParameterKeyOwner]: `mesh`, `deformer`, `part`, or `glue`.
 * [subtype] distinguishes warp vs rotation deformers for icons (`warp` / `rotation`).
 */
data class ParameterKeyBoundComponent(
	val kind: String,
	val id: String,
	val name: String,
	val subtype: String? = null,
) {
	val owner: ParameterKeyOwner get() = ParameterKeyOwner(kind, id)
}

/** Resolve the same deformer-first selection as the editor; layer IDs are not drawable IDs. */
fun PuppetModel.selectedParameterOwner(layerId: String?, deformerId: String?, layerIdByDrawableId: Map<String, String>): ParameterKeyOwner? {
    deformers.firstOrNull { it.id.raw == deformerId }?.let {
        return ParameterKeyOwner("deformer", it.id.raw)
    }
    if (layerId == null) return null
    val drawable = drawables.firstOrNull { layerIdByDrawableId[it.id.raw] == layerId }
        ?: drawables.firstOrNull { it.id.raw == layerId }
    return drawable?.let { ParameterKeyOwner("mesh", it.id.raw) }
}

/**
 * Components that store a keyform or blend-shape key at [keyValue] on [parameterId].
 * Order follows the rig: parts, deformers, drawables, then glues.
 */
fun PuppetModel.componentsAtParameterKey(parameterId: ParameterId, keyValue: Float): List<ParameterKeyBoundComponent> {
	val result = ArrayList<ParameterKeyBoundComponent>()
	fun FloatArray.hasKey(): Boolean = any { abs(it - keyValue) < EPS_KEY }
	fun List<KeyformAxis>.hasKey(): Boolean = any { it.parameterId == parameterId && it.keys.hasKey() }

	for (part in parts) {
		if (part.channelGrids.allAxes().hasKey()) {
			result += ParameterKeyBoundComponent("part", part.id.raw, part.name)
		}
	}
	for (deformer in deformers) {
		val geometry = when (deformer) {
			is Deformer.Warp -> deformer.geometryGrid
			is Deformer.Rotation -> deformer.geometryGrid
		}
		val blendShapes = when (deformer) {
			is Deformer.Warp -> deformer.blendShapes
			is Deformer.Rotation -> deformer.blendShapes
		}
		val hit = geometry?.axes.orEmpty().hasKey() ||
			deformer.channelGrids.allAxes().hasKey() ||
			blendShapes.any { it.parameterId == parameterId && it.keys.hasKey() }
		if (hit) {
			val subtype = if (deformer is Deformer.Warp) "warp" else "rotation"
			result += ParameterKeyBoundComponent("deformer", deformer.id.raw, deformer.name, subtype)
		}
	}
	for (drawable in drawables) {
		val hit = drawable.geometryGrid?.axes.orEmpty().hasKey() ||
			drawable.channelGrids.allAxes().hasKey() ||
			drawable.blendShapes.any { it.parameterId == parameterId && it.keys.hasKey() }
		if (hit) {
			result += ParameterKeyBoundComponent("mesh", drawable.id.raw, drawable.name)
		}
	}
	for (glue in glues) {
		if (glue.channelGrids.allAxes().hasKey()) {
			val glueId = glue.id ?: "${glue.meshA.raw}:${glue.meshB.raw}"
			val glueName = glue.id ?: "Glue ${glue.meshA.raw} · ${glue.meshB.raw}"
			result += ParameterKeyBoundComponent("glue", glueId, glueName)
		}
	}
	return result
}

/** Union of components keyed at any of the given (parameter, key) pairs, preserving first-seen order. */
fun PuppetModel.componentsAtParameterKeys(keys: List<Pair<ParameterId, Float>>): List<ParameterKeyBoundComponent> {
	if (keys.isEmpty()) return emptyList()
	val seen = HashSet<ParameterKeyOwner>()
	val result = ArrayList<ParameterKeyBoundComponent>()
	for ((parameterId, keyValue) in keys) {
		for (component in componentsAtParameterKey(parameterId, keyValue)) {
			if (seen.add(component.owner)) result += component
		}
	}
	return result
}

/** Sorted geometry, channel and blend keys for one owner, or the whole rig when owner is null. */
fun PuppetModel.parameterKeyMarks(owner: ParameterKeyOwner? = null): Map<ParameterId, ParameterKeyMarks> {
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
        if (owner != null && (owner.kind != "mesh" || owner.id != drawable.id.raw)) continue
		drawable.geometryGrid?.axes?.forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
		drawable.channelGrids.allAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
		drawable.blendShapes.forEach { binding -> addBlendKeys(binding.parameterId, binding.keys) }
	}
	for (deformer in deformers) {
        if (owner != null && (owner.kind != "deformer" || owner.id != deformer.id.raw)) continue
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
        if (owner != null && (owner.kind != "part" || owner.id != part.id.raw)) continue
		part.channelGrids.allAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
	}
	for (glue in glues) {
        if (owner != null) continue
		glue.channelGrids.allAxes().forEach { axis -> addGridKeys(axis.parameterId, axis.keys) }
	}

	val keyedParameters = gridKeysByParameter.keys + blendKeysByParameter.keys
	val objectMarks = keyedParameters.associateWith { parameterId ->
		ParameterKeyMarks(
			gridKeys = gridKeysByParameter[parameterId]?.toList() ?: emptyList(),
			blendKeys = blendKeysByParameter[parameterId]?.toList() ?: emptyList(),
		)
	}
	if (owner != null) return objectMarks
	return parameters.fold(objectMarks) { marks, parameter ->
		val explicit = parameter.keys ?: return@fold marks
		marks + (parameter.id to ParameterKeyMarks(explicit.sorted(), emptyList()))
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
