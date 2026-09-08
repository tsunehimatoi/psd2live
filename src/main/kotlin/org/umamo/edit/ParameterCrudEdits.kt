package org.umamo.edit

import org.umamo.runtime.keyform.CollapsedChannels
import org.umamo.runtime.keyform.colorOr
import org.umamo.runtime.keyform.flagOr
import org.umamo.runtime.keyform.hasFractionalDrawOrder
import org.umamo.runtime.keyform.integralDrawOrderOrNull
import org.umamo.runtime.keyform.scalarOr
import org.umamo.runtime.keyform.withAxisCollapsed
import org.umamo.runtime.keyform.withAxisCollapsedLifting
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Parameter document edits: create a new axis, rename one (its display name), and delete one everywhere.
 * Create and rename mirror the group edits (ParameterGroupEdits.kt) - pure PuppetModel.withX plus thin
 * EditorSession wrappers through mutate. Delete is the heavier one: besides the flat parameters list, the
 * panel tree, and any link, it must scrub the parameter out of every object's keyform grid, since each
 * grid axis references a parameter by id (KeyformAxis.parameterId). Dropping an axis collapses that grid
 * one dimension, keeping the slice at the parameter's default value (the neutral pose) and discarding the
 * off-axis motion. The live pose entry is dropped in the session wrapper (pose lives on EditorSession).
 *
 * パラメータの作成・改名・削除。削除は各オブジェクトのキーフォーム格子から該当軸を取り除き、既定値の
 * スライスへ畳み込む。
 */

/**
 * A fresh CParameterSource-style idstr ("Param", then "Param2", "Param3", ...) that collides with no
 * existing parameter id. Deterministic from the model alone so the same edit replays identically under
 * undo / redo - no clock, no random.
 *
 * // CMO3: CParameterSource id idstr - editor-minted sequential parameter identifiers.
 *
 * @return ParameterId A parameter id not already present in this model.
 */
fun PuppetModel.freshParameterId(): ParameterId {
	val existing = parameters.map { parameter -> parameter.id }.toSet()
	if (ParameterId("Param") !in existing) {
		return ParameterId("Param")
	}
	var suffix = 2
	while (ParameterId("Param$suffix") in existing) {
		suffix++
	}
	return ParameterId("Param$suffix")
}

/**
 * A copy of this model with a new animatable parameter [newId] named [name] of [kind] appended to the axis
 * list and its leaf prepended at the root top of the panel tree (so it lands on-screen for the immediate
 * inline rename, like a new group). The default -1..1 / 0 range makes it animatable (min < max) so it
 * renders as a slider; it is a neutral starting point the user retargets in the range editor. A
 * BLEND_SHAPE kind renders with square knob / key marks (it carries no keys until authoring captures
 * them). Materializes a flat tree first for a group-less model, so the existing rows keep their order
 * beneath the new parameter. Refuses (returns this same instance) a colliding id.
 *
 * @param ParameterId newId The minted id of the new parameter.
 * @param String name The initial display name.
 * @param ParameterKind kind The parameter kind (NORMAL key-form or BLEND_SHAPE); defaults to NORMAL.
 * @return PuppetModel The model with the parameter added, or this if the id already exists.
 */
fun PuppetModel.withParameterCreated(
	newId: ParameterId,
	name: String,
	kind: ParameterKind = ParameterKind.NORMAL,
): PuppetModel {
	if (parameters.any { parameter -> parameter.id == newId }) {
		return this
	}
	val newParameter = Parameter(newId, name, min = -1f, max = 1f, default = 0f, kind = kind)
	val newLeaf = ParameterNode.Param(newId)
	return copy(
		parameters = parameters + newParameter,
		parameterTree = listOf(newLeaf) + materializedParameterTree(),
	)
}

/**
 * A copy of this model with parameter [id] renamed to [newName] (trimmed). The id is format-level and
 * never changes - only the display name. A blank name is a no-op (returns this same instance), so an empty
 * commit keeps the old label; an unchanged name likewise returns this.
 *
 * @param ParameterId id The parameter to rename.
 * @param String newName The new display name (trimmed; blank is ignored).
 * @return PuppetModel The model with the parameter renamed, or this if unchanged or blank.
 */
fun PuppetModel.withParameterRenamed(id: ParameterId, newName: String): PuppetModel {
	val trimmed = newName.trim()
	if (trimmed.isEmpty()) {
		return this
	}
	var changed = false
	val newParameters =
		parameters.map { parameter ->
			if (parameter.id == id && parameter.name != trimmed) {
				changed = true
				parameter.copy(name = trimmed)
			} else {
				parameter
			}
		}
	return if (changed) {
		copy(parameters = newParameters)
	} else {
		this
	}
}

/**
 * A copy of this model with parameter [id] removed everywhere: the axis list, every panel-tree leaf, any
 * link it belongs to (its partner reverts to a plain slider), and every keyform grid in the rig -
 * drawables, both deformer kinds, parts, and glue intensities. Dropping the deleted axis collapses each
 * grid to the slice at the parameter's default value (the neutral look), discarding that axis's motion.
 * A channel track left with no axes is dropped, and the value of its kept slice is lifted into the
 * owner's static so the neutral look survives the drop; a fractional part draw order stays behind as a
 * constant track because the Int static slot cannot hold it without rounding. A geometry grid left with
 * no axes becomes null (the drawable returns to its rest mesh - the base mesh is authored, never baked
 * from a slice). The render root is re-derived at the end so its per-group copies of the part tracks
 * lose the deleted axis too. The live pose entry is dropped by the session wrapper. A no-op (no such
 * parameter) returns this same instance.
 *
 * @param ParameterId id The parameter to delete.
 * @return PuppetModel The model with the parameter removed, or this if it was absent.
 */
fun PuppetModel.withParameterDeleted(id: ParameterId): PuppetModel {
	val deleted = parameters.firstOrNull { parameter -> parameter.id == id } ?: return this
	val keepValue = deleted.default
	// Both halves of a drawable key on parameters: the geometry grid and every channel track.
	val newDrawables =
		drawables.map { drawable ->
			val collapsed = drawable.geometryGrid?.withAxisCollapsed(id, keepValue)
			val scrubbed = drawable.channelGrids.withAxisCollapsedLifting(id, keepValue)
			if (collapsed === drawable.geometryGrid && scrubbed.channelGrids === drawable.channelGrids) {
				drawable
			} else {
				drawable.copy(
					geometryGrid = collapsed,
					channelGrids = scrubbed.channelGrids,
					drawOrder = scrubbed.lifted.scalarOr(FormChannel.DRAW_ORDER, drawable.drawOrder),
					opacity = scrubbed.lifted.scalarOr(FormChannel.OPACITY, drawable.opacity),
					multiplyColor = scrubbed.lifted.colorOr(FormChannel.MULTIPLY_COLOR, drawable.multiplyColor),
					screenColor = scrubbed.lifted.colorOr(FormChannel.SCREEN_COLOR, drawable.screenColor),
				)
			}
		}
	// Both halves of a deformer key on parameters: the geometry grid and every channel track.
	val newDeformers =
		deformers.map { deformer ->
			val scrubbed = deformer.channelGrids.withAxisCollapsedLifting(id, keepValue)
			when (deformer) {
				is Deformer.Warp -> {
					val collapsed = deformer.geometryGrid?.withAxisCollapsed(id, keepValue)
					if (collapsed === deformer.geometryGrid && scrubbed.channelGrids === deformer.channelGrids) {
						deformer
					} else {
						deformer.copy(
							geometryGrid = collapsed,
							channelGrids = scrubbed.channelGrids,
							opacity = scrubbed.lifted.scalarOr(FormChannel.OPACITY, deformer.opacity),
							multiplyColor = scrubbed.lifted.colorOr(FormChannel.MULTIPLY_COLOR, deformer.multiplyColor),
							screenColor = scrubbed.lifted.colorOr(FormChannel.SCREEN_COLOR, deformer.screenColor),
						)
					}
				}

				is Deformer.Rotation -> {
					val collapsed = deformer.geometryGrid?.withAxisCollapsed(id, keepValue)
					if (collapsed === deformer.geometryGrid && scrubbed.channelGrids === deformer.channelGrids) {
						deformer
					} else {
						deformer.copy(
							geometryGrid = collapsed,
							channelGrids = scrubbed.channelGrids,
							opacity = scrubbed.lifted.scalarOr(FormChannel.OPACITY, deformer.opacity),
							multiplyColor = scrubbed.lifted.colorOr(FormChannel.MULTIPLY_COLOR, deformer.multiplyColor),
							screenColor = scrubbed.lifted.colorOr(FormChannel.SCREEN_COLOR, deformer.screenColor),
							flipX = scrubbed.lifted.flagOr(FormChannel.FLIP_X, deformer.flipX),
							flipY = scrubbed.lifted.flagOr(FormChannel.FLIP_Y, deformer.flipY),
						)
					}
				}
			}
		}
	val newParts =
		parts.map { part ->
			val scrubbed = part.channelGrids.withAxisCollapsedLifting(id, keepValue)
			if (scrubbed.channelGrids === part.channelGrids) {
				part
			} else {
				part.copy(
					channelGrids = scrubbed.withFractionalDrawOrderKept().channelGrids,
					drawOrder = scrubbed.lifted.integralDrawOrderOrNull() ?: part.drawOrder,
					composite =
						part.composite.copy(
							opacity = scrubbed.lifted.scalarOr(FormChannel.OPACITY, part.composite.opacity),
							multiplyColor = scrubbed.lifted.colorOr(FormChannel.MULTIPLY_COLOR, part.composite.multiplyColor),
							screenColor = scrubbed.lifted.colorOr(FormChannel.SCREEN_COLOR, part.composite.screenColor),
						),
				)
			}
		}
	// Glue tracks key on parameters exactly like the rest, so they need the same scrub - without it a
	// deleted parameter survives as a dangling KeyformAxis.parameterId that nothing can ever resolve.
	val newGlues =
		glues.map { glue ->
			val scrubbed = glue.channelGrids.withAxisCollapsedLifting(id, keepValue)
			if (scrubbed.channelGrids === glue.channelGrids) {
				glue
			} else {
				glue.copy(
					channelGrids = scrubbed.channelGrids,
					intensity = scrubbed.lifted.scalarOr(FormChannel.GLUE_INTENSITY, glue.intensity),
				)
			}
		}
	return copy(
		parameters = parameters.filterNot { parameter -> parameter.id == id },
		parameterLinks = parameterLinks.filterNot { link -> link.horizontal == id || link.vertical == id },
		parameterTree = removeParameterLeaf(parameterTree, id),
		drawables = newDrawables,
		deformers = newDeformers,
		parts = newParts,
		glues = newGlues,
	).withDerivedRenderRoot()
}

/**
 * These collapsed channels with a fractional lifted DRAW_ORDER kept as a zero-axis constant track.
 *
 * A part's static draw-order slot is an Int, so a fractional slice value cannot lift without rounding,
 * and rounding could reorder two parts the track kept distinct.  The deleted axis cannot stay either, so
 * the value is kept as a single-cell track with no axes - the same degenerate shape MOC3 import already
 * produces - which the evaluator reads as a constant.
 *
 * @return CollapsedChannels The channels with the fractional draw order retained as a constant track.
 */
private fun CollapsedChannels.withFractionalDrawOrderKept(): CollapsedChannels {
	if (!lifted.hasFractionalDrawOrder()) {
		return this
	}
	val constantTrack = KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), lifted[FormChannel.DRAW_ORDER]!!)))
	return CollapsedChannels(
		ChannelGrids(channelGrids.gridsByChannel + (FormChannel.DRAW_ORDER to constantTrack)),
		lifted,
	)
}

/** This node list with every [ParameterNode.Param] leaf for [id] spliced out, recursing into groups. */
private fun removeParameterLeaf(nodes: List<ParameterNode>, id: ParameterId): List<ParameterNode> =
	nodes.mapNotNull { node ->
		when (node) {
			is ParameterNode.Param -> if (node.id == id) null else node
			is ParameterNode.Group -> node.copy(children = removeParameterLeaf(node.children, id))
		}
	}

/**
 * Creates a new animatable parameter (a freshly minted id, default -1..1 range) named [name] of [kind]
 * and returns its id, so the caller can immediately open inline rename on it. One undo step.
 *
 * @param String name The initial display name.
 * @param ParameterKind kind The parameter kind (NORMAL key-form or BLEND_SHAPE); defaults to NORMAL.
 * @return ParameterId The id of the created parameter.
 */
fun EditorSession.createParameter(name: String, kind: ParameterKind = ParameterKind.NORMAL): ParameterId {
	val id = model.value.freshParameterId()
	mutate(ParameterChange.Create(id)) { model -> model.withParameterCreated(id, name, kind) }
	return id
}

/**
 * Renames parameter [id]'s display name to [newName] (trimmed) as one undo step. A blank or unchanged
 * name records nothing.
 *
 * @param ParameterId id The parameter to rename.
 * @param String newName The new display name.
 */
fun EditorSession.renameParameter(id: ParameterId, newName: String) {
	mutate(ParameterChange.Rename(id, newName.trim())) { model -> model.withParameterRenamed(id, newName) }
}