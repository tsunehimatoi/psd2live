package org.umamo.edit

import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/*
 * Parameter-panel group-tree structure edits. PuppetModel.parameterTree is the canonical panel order
 * (an ordered mix of parameter leaves and named groups); the flat parameters list stays the id-keyed
 * authoritative axis list, whose ORDER nothing reads at runtime once a tree exists. So a reorder or a
 * move into / out of a group rewrites ONLY parameterTree, leaving parameters and parameterLinks alone -
 * the future CMO3 exporter flattens the tree for the _sources order. A model imported with no groups
 * has an empty tree; the first real structure edit materializes a flat all-leaves-at-root tree so there
 * is one code path afterward. Every edit snapshots the whole PuppetModel through EditorSession.mutate,
 * so it undoes for free.
 *
 * パラメータパネルのグループツリー構造編集。parameterTree が表示順の正で、平坦な parameters は id 基準
 * の軸リスト。並べ替え・グループ出入りは parameterTree だけを書き換える。
 */

/**
 * What a parameter-tree move relocates: a run of one or two adjacent parameter leaves (a slider is one
 * leaf, a linked 2D pad is two, moved contiguously so they stay adjacent for combined CMO3 export), or a
 * whole group.
 */
sealed interface ParameterMoveSubject {
	/** One or two adjacent parameter leaves, in order (a pad is horizontal then vertical). */
	data class Leaves(val ids: List<ParameterId>) : ParameterMoveSubject

	/** A whole group node, moved with its children intact. */
	data class Group(val id: ParameterGroupId) : ParameterMoveSubject
}

/**
 * A position anchor in the parameter tree - "insert before this node". Null anywhere a ref is expected
 * means "append at the destination's end".
 */
sealed interface ParameterNodeRef {
	/** Anchor on a parameter leaf. */
	data class Leaf(val id: ParameterId) : ParameterNodeRef

	/** Anchor on a group. */
	data class Group(val id: ParameterGroupId) : ParameterNodeRef
}

/**
 * This model's parameter tree, materializing a flat all-leaves-at-root tree when the model carries no
 * groups. Gives structure edits one shape to operate on: a group-less model becomes a root list of
 * [ParameterNode.Param] in the flat parameters order (which is what the panel already renders for an
 * empty tree), so the first move / group edit has a tree to rewrite.
 *
 * @return List<ParameterNode> The existing tree, or a freshly materialized flat one.
 */
internal fun PuppetModel.materializedParameterTree(): List<ParameterNode> =
	if (parameterTree.isEmpty()) {
		parameters.map { parameter -> ParameterNode.Param(parameter.id) }
	} else {
		parameterTree
	}

/**
 * Every group id anywhere in [nodes], recursing into nested groups. Used to mint collision-free ids and
 * to validate a move destination.
 *
 * @param List nodes The tree (or subtree) to scan.
 * @return Set<ParameterGroupId> Every group id present.
 */
internal fun collectParameterGroupIds(nodes: List<ParameterNode>): Set<ParameterGroupId> {
	val ids = HashSet<ParameterGroupId>()

	fun walk(list: List<ParameterNode>) {
		for (node in list) {
			if (node is ParameterNode.Group) {
				ids.add(node.id)
				walk(node.children)
			}
		}
	}
	walk(nodes)
	return ids
}

/** The node ids removed by a detach for [subject] (leaf ids, or the single group id). */
private fun ParameterMoveSubject.movedLeafIds(): Set<ParameterId> =
	when (this) {
		is ParameterMoveSubject.Leaves -> ids.toSet()
		is ParameterMoveSubject.Group -> emptySet()
	}

/** The group id removed by a detach for [subject], or null when it moves leaves. */
private fun ParameterMoveSubject.movedGroupId(): ParameterGroupId? =
	when (this) {
		is ParameterMoveSubject.Leaves -> null
		is ParameterMoveSubject.Group -> id
	}

/**
 * Removes the moved node(s) from a node list (non-recursively at this level), returning the survivors and
 * the detached group node when [subject] was a group found here.
 */
private fun List<ParameterNode>.detachSubject(subject: ParameterMoveSubject): List<ParameterNode> {
	val movedLeaves = subject.movedLeafIds()
	val movedGroup = subject.movedGroupId()
	return filterNot { node ->
		when (node) {
			is ParameterNode.Param -> node.id in movedLeaves
			is ParameterNode.Group -> node.id == movedGroup
		}
	}
}

/** This node list with [inserted] placed before [before] (or appended when [before] is null or absent). */
private fun List<ParameterNode>.withNodesInsertedBefore(inserted: List<ParameterNode>, before: ParameterNodeRef?): List<ParameterNode> {
	val result = toMutableList()
	val insertAt =
		before?.let { ref ->
			result.indexOfFirst { node -> node.matchesRef(ref) }
		}?.takeIf { index -> index >= 0 } ?: result.size
	result.addAll(insertAt, inserted)
	return result
}

/** True when this node is the one [ref] names. */
private fun ParameterNode.matchesRef(ref: ParameterNodeRef): Boolean =
	when {
		this is ParameterNode.Param && ref is ParameterNodeRef.Leaf -> id == ref.id
		this is ParameterNode.Group && ref is ParameterNodeRef.Group -> id == ref.id
		else -> false
	}

/**
 * A copy of this model with [subject] relocated in the parameter-panel group tree: under
 * [newParentGroupId] (null = the root level) and inserted before [before] there (null / absent =
 * appended). The flat parameters list and the links are untouched - this is layout only. Materializes a
 * flat tree first when the model has no groups, so one code path serves grouped and group-less models.
 * The moved leaves are always re-inserted contiguously in [subject] order, so a linked pad keeps its
 * two members adjacent regardless of a slightly-off UI anchor. Refuses - returning this same instance,
 * so the session records no undo step - a group moved under any group (groups do not nest one level
 * deep), an unknown destination group, and any no-op move.
 *
 * @param ParameterMoveSubject subject The leaves or group to move.
 * @param ParameterGroupId? newParentGroupId The destination group, or null for the root level.
 * @param ParameterNodeRef? before The sibling to insert before, or null to append.
 * @return PuppetModel The model with the move applied, or this if it was a no-op or illegal.
 */
fun PuppetModel.withParameterRowMoved(
	subject: ParameterMoveSubject,
	newParentGroupId: ParameterGroupId?,
	before: ParameterNodeRef?,
): PuppetModel {
	// One level only: a group can be reordered among root nodes but never dropped inside another group.
	if (subject is ParameterMoveSubject.Group && newParentGroupId != null) {
		return this
	}
	val workingTree = materializedParameterTree()
	if (newParentGroupId != null && newParentGroupId !in collectParameterGroupIds(workingTree)) {
		return this
	}
	// Detached-first snapshot of what is being moved, resolved before the tree loses it.
	val insertedNodes = collectSubjectNodes(workingTree, subject)
	if (insertedNodes.isEmpty()) {
		return this
	}
	// Detach at the root and inside every group.
	val detachedRoot =
		workingTree.detachSubject(subject).map { node ->
			if (node is ParameterNode.Group) {
				node.copy(children = node.children.detachSubject(subject))
			} else {
				node
			}
		}
	val newTree: List<ParameterNode> =
		if (newParentGroupId == null) {
			detachedRoot.withNodesInsertedBefore(insertedNodes, before)
		} else {
			detachedRoot.map { node ->
				if (node is ParameterNode.Group && node.id == newParentGroupId) {
					node.copy(children = node.children.withNodesInsertedBefore(insertedNodes, before))
				} else {
					node
				}
			}
		}
	if (newTree == workingTree) {
		return this
	}
	return copy(parameterTree = newTree)
}

/**
 * The actual [ParameterNode]s named by [subject], found anywhere in [tree]. Leaves are returned as fresh
 * [ParameterNode.Param]s in [subject] order (so a pad stays horizontal-then-vertical); a group is
 * returned as its existing node with children intact. Empty when nothing matched.
 */
private fun collectSubjectNodes(tree: List<ParameterNode>, subject: ParameterMoveSubject): List<ParameterNode> =
	when (subject) {
		is ParameterMoveSubject.Leaves -> {
			val present = collectLeafIds(tree)
			subject.ids.filter { id -> id in present }.map { id -> ParameterNode.Param(id) }
		}
		is ParameterMoveSubject.Group -> {
			val found = findGroupNode(tree, subject.id)
			if (found == null) {
				emptyList()
			} else {
				listOf(found)
			}
		}
	}

/** Every leaf id anywhere in [nodes]. */
private fun collectLeafIds(nodes: List<ParameterNode>): Set<ParameterId> {
	val ids = HashSet<ParameterId>()

	fun walk(list: List<ParameterNode>) {
		for (node in list) {
			when (node) {
				is ParameterNode.Param -> ids.add(node.id)
				is ParameterNode.Group -> walk(node.children)
			}
		}
	}
	walk(nodes)
	return ids
}

/** The group node with [groupId] anywhere in [nodes], or null. */
private fun findGroupNode(nodes: List<ParameterNode>, groupId: ParameterGroupId): ParameterNode.Group? {
	for (node in nodes) {
		if (node is ParameterNode.Group) {
			if (node.id == groupId) {
				return node
			}
			val nested = findGroupNode(node.children, groupId)
			if (nested != null) {
				return nested
			}
		}
	}
	return null
}

/**
 * Moves a parameter row - one or two adjacent leaves, or a group - in the panel tree as one undo step.
 * A refused or no-op move records nothing.
 *
 * @param ParameterMoveSubject subject The leaves or group to move.
 * @param ParameterGroupId? newParentGroupId The destination group, or null for the root level.
 * @param ParameterNodeRef? before The sibling to insert before, or null to append.
 */
fun EditorSession.moveParameterRow(subject: ParameterMoveSubject, newParentGroupId: ParameterGroupId?, before: ParameterNodeRef?) {
	mutate(ParameterChange.MoveNode(subject, newParentGroupId)) { model ->
		model.withParameterRowMoved(subject, newParentGroupId, before)
	}
}