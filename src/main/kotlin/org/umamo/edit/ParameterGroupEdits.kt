package org.umamo.edit

import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/*
 * Parameter-group document edits: create an empty group, delete (unwrap) a group keeping its parameters,
 * and rename a group. All operate on PuppetModel.parameterTree (layout) and never touch the flat
 * parameters list or the links; each undoes for free through EditorSession.mutate's whole-model snapshot.
 * Reuses materializedParameterTree / collectParameterGroupIds from ParameterTreeEdits.
 *
 * パラメータグループの編集：空グループ作成・グループ解除（子は残す）・改名。parameterTree のみを変更。
 */

/**
 * A fresh CParameterGroup-style idstr ("ParamGroup", then "ParamGroup2", "ParamGroup3", ...) that
 * collides with no existing group id anywhere in the tree. Deterministic from the model alone so the
 * same edit replays identically under undo / redo - no clock, no random.
 *
 * // CMO3: CParameterGroupId.idstr - editor-minted sequential group identifiers.
 *
 * @return ParameterGroupId A group id not already present in this model.
 */
fun PuppetModel.freshParameterGroupId(): ParameterGroupId {
	val existing = collectParameterGroupIds(parameterTree)
	if (ParameterGroupId("ParamGroup") !in existing) {
		return ParameterGroupId("ParamGroup")
	}
	var suffix = 2
	while (ParameterGroupId("ParamGroup$suffix") in existing) {
		suffix++
	}
	return ParameterGroupId("ParamGroup$suffix")
}

/**
 * A copy of this model with a new empty group [newGroupId] named [name] inserted at the top of the
 * parameter root, expanded. Materializes a flat tree first for a group-less model, so the existing rows
 * keep their order beneath the new group. An empty group has no visual anchor, so it lands at the top of
 * the scroll region where the user is already looking and its immediate inline-rename is on screen -
 * rather than below the fold. Refuses (returns this same instance) a colliding id.
 *
 * @param ParameterGroupId newGroupId The minted id of the new group.
 * @param String name The initial display name.
 * @return PuppetModel The model with the group added, or this if the id already exists.
 */
fun PuppetModel.withParameterGroupCreated(newGroupId: ParameterGroupId, name: String): PuppetModel {
	if (newGroupId in collectParameterGroupIds(parameterTree)) {
		return this
	}
	val newGroup = ParameterNode.Group(newGroupId, name, initiallyOpen = true, children = emptyList())
	return copy(parameterTree = listOf(newGroup) + materializedParameterTree())
}

/**
 * A copy of this model with group [groupId] dissolved: its child nodes splice into the tree at the
 * group's own position and the group node is dropped, so no parameter is lost. Never touches the flat
 * parameters list or the links. A no-op (no such group) returns this same instance.
 *
 * @param ParameterGroupId groupId The group to unwrap.
 * @return PuppetModel The model with the group unwrapped, or this if it was absent.
 */
fun PuppetModel.withParameterGroupDeleted(groupId: ParameterGroupId): PuppetModel {
	if (groupId !in collectParameterGroupIds(parameterTree)) {
		return this
	}

	fun splice(nodes: List<ParameterNode>): List<ParameterNode> =
		nodes.flatMap { node ->
			when {
				node is ParameterNode.Group && node.id == groupId -> node.children
				node is ParameterNode.Group -> listOf(node.copy(children = splice(node.children)))
				else -> listOf(node)
			}
		}
	return copy(parameterTree = splice(parameterTree))
}

/**
 * A copy of this model with group [groupId] renamed to [newName] (trimmed). A blank name is a no-op
 * (returns this same instance), so an empty commit keeps the old label. Names are user data - never
 * localized - and are not unique; identity is the group id, so only the matching node changes.
 *
 * @param ParameterGroupId groupId The group to rename.
 * @param String newName The new display name (trimmed; blank is ignored).
 * @return PuppetModel The model with the group renamed, or this if unchanged or blank.
 */
fun PuppetModel.withParameterGroupRenamed(groupId: ParameterGroupId, newName: String): PuppetModel {
	val trimmed = newName.trim()
	if (trimmed.isEmpty()) {
		return this
	}

	fun rename(nodes: List<ParameterNode>): List<ParameterNode> =
		nodes.map { node ->
			when {
				node is ParameterNode.Group && node.id == groupId -> node.copy(name = trimmed)
				node is ParameterNode.Group -> node.copy(children = rename(node.children))
				else -> node
			}
		}
	val newTree = rename(parameterTree)
	if (newTree == parameterTree) {
		return this
	}
	return copy(parameterTree = newTree)
}

/**
 * Creates a new empty parameter group (a freshly minted id, expanded) and returns its id, so the caller
 * can immediately open inline rename on it. One undo step.
 *
 * @param String name The initial display name.
 * @return ParameterGroupId The id of the created group.
 */
fun EditorSession.createParameterGroup(name: String): ParameterGroupId {
	val id = model.value.freshParameterGroupId()
	mutate(ParameterChange.CreateGroup(id)) { model -> model.withParameterGroupCreated(id, name) }
	return id
}

/**
 * Deletes (unwraps) parameter group [id], splicing its parameters into the parent, as one undo step. A
 * no-op records nothing.
 *
 * @param ParameterGroupId id The group to unwrap.
 */
fun EditorSession.deleteParameterGroup(id: ParameterGroupId) {
	mutate(ParameterChange.DeleteGroup(id)) { model -> model.withParameterGroupDeleted(id) }
}

/**
 * Renames parameter group [id] to [newName] (trimmed) as one undo step. A blank or unchanged name
 * records nothing.
 *
 * @param ParameterGroupId id The group to rename.
 * @param String newName The new display name.
 */
fun EditorSession.renameParameterGroup(id: ParameterGroupId, newName: String) {
	mutate(ParameterChange.RenameGroup(id, newName.trim())) { model -> model.withParameterGroupRenamed(id, newName) }
}