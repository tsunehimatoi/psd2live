package io.github.psd2live.core

import org.umamo.edit.withParameterCreated
import org.umamo.edit.withParameterDeleted
import org.umamo.edit.withParameterRange
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel

/**
 * A durable, replayable edit to one parameter. The complete desired value is stored instead of a
 * sequence of UI gestures, so rebuilding meshes or the generated base rig cannot silently lose it.
 */
data class RigParameterEdit(
	val id: String,
	val name: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val kind: ParameterKind = ParameterKind.NORMAL,
	val repeat: Boolean = false,
	/** True when this parameter did not exist in the generated base rig at creation time. */
	val created: Boolean = false,
) {
	init {
		require(id.isNotBlank() && id.none(Char::isISOControl)) { "Parameter ID must not be blank or contain control characters" }
		require(name.isNotBlank() && name.none(Char::isISOControl)) { "Parameter name must not be blank or contain control characters" }
		require(min.isFinite() && max.isFinite() && default.isFinite()) { "Parameter range values must be finite" }
		require(min < max) { "Parameter minimum must be less than maximum" }
		require(default in min..max) { "Parameter default must be within its range" }
	}

	fun asParameter(): Parameter = Parameter(ParameterId(id), name.trim(), min, max, default, kind, repeat)
}

/**
 * Authoritative rig customization applied after every deterministic base-rig build. This value is
 * included in Agent history snapshots and export configuration.
 */
data class RigEditOverlay(
	val parameterEdits: List<RigParameterEdit> = emptyList(),
	val deletedParameterIds: Set<String> = emptySet(),
) {
	init {
		require(parameterEdits.map(RigParameterEdit::id).distinct().size == parameterEdits.size) {
			"Rig parameter edits contain duplicate IDs"
		}
		require(parameterEdits.none { it.id in deletedParameterIds }) {
			"A parameter cannot be both edited and deleted"
		}
	}

	fun applyTo(base: PuppetModel): PuppetModel {
		var model = base
		for (id in deletedParameterIds.sorted()) model = model.withParameterDeleted(ParameterId(id))
		for (edit in parameterEdits) {
			val id = ParameterId(edit.id)
			if (model.parameters.none { it.id == id }) {
				model = model.withParameterCreated(id, edit.name, edit.kind)
			}
			model = model.withParameterRange(id, edit.min, edit.default, edit.max)
			val desired = edit.asParameter()
			model = model.copy(parameters = model.parameters.map { current -> if (current.id == id) desired else current })
		}
		return model
	}

	fun upsert(edit: RigParameterEdit): RigEditOverlay {
		val index = parameterEdits.indexOfFirst { it.id == edit.id }
		val next = if (index < 0) parameterEdits + edit else parameterEdits.toMutableList().also { it[index] = edit }
		return copy(parameterEdits = next, deletedParameterIds = deletedParameterIds - edit.id)
	}

	fun delete(id: String): RigEditOverlay = copy(
		parameterEdits = parameterEdits.filterNot { it.id == id },
		deletedParameterIds = deletedParameterIds + id,
	)

	companion object {
		val Empty = RigEditOverlay()
	}
}

internal fun BuiltRig.withRigEdits(overlay: RigEditOverlay): BuiltRig =
	if (overlay == RigEditOverlay.Empty) this else copy(puppet = overlay.applyTo(puppet))
