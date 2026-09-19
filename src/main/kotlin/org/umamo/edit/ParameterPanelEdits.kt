package org.umamo.edit

import org.umamo.runtime.model.ParameterGroupId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterNode
import org.umamo.runtime.model.PuppetModel

/*
 * Parameter-panel layout edits: folders (CParameterGroup), panel order, and LINKED (combined) pairs.
 * The flat [PuppetModel.parameters] list stays the axis authority; [PuppetModel.parameterTree] is the
 * collapsible folder layout Cubism exports as CMO3 groups / CDI3 DisplayInfo; [PuppetModel.parameterLinks]
 * is the combined-parameter set (CMO3 `combined` + adjacency, CDI3 `CombinedParameters`).
 *
 * パラメータパネルのフォルダ・並び・結合ペア編集。フラットな parameters が軸の唯一の真実、
 * parameterTree がパネル階層、parameterLinks が結合（2D）ペア。
 */

/** A parameter-panel node address: either a leaf parameter or a named folder group. */
sealed interface ParameterPanelRef {
	data class Param(val id: ParameterId) : ParameterPanelRef
	data class Group(val id: ParameterGroupId) : ParameterPanelRef
}

/**
 * A fresh CParameterGroup-style id ("ParamGroup", then "ParamGroup2", ...) that collides with no
 * existing group id in the materialized tree.
 */
fun PuppetModel.freshParameterGroupId(): ParameterGroupId {
	val existing = collectGroupIds(materializedParameterTree())
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
 * A copy with a new folder [id] named [name] inserted under [parentId] (null = panel root) before
 * [before] (null / absent = append). Refuses a colliding id or an unknown parent.
 */
fun PuppetModel.withParameterGroupCreated(
	id: ParameterGroupId,
	name: String,
	parentId: ParameterGroupId? = null,
	before: ParameterPanelRef? = null,
	initiallyOpen: Boolean = true,
): PuppetModel {
	val trimmed = name.trim()
	if (trimmed.isEmpty()) {
		return this
	}
	val tree = materializedParameterTree()
	if (id in collectGroupIds(tree)) {
		return this
	}
	if (parentId != null && findGroup(tree, parentId) == null) {
		return this
	}
	val fresh = ParameterNode.Group(id, trimmed, initiallyOpen, emptyList())
	val nextTree = insertNode(tree, ParameterPanelRef.Group(id), fresh, parentId, before) ?: return this
	return copy(parameterTree = nextTree).withParametersSyncedFromTree()
}

/** A copy with folder [id] renamed to [newName] (trimmed). Blank / unchanged names are no-ops. */
fun PuppetModel.withParameterGroupRenamed(id: ParameterGroupId, newName: String): PuppetModel {
	val trimmed = newName.trim()
	if (trimmed.isEmpty()) {
		return this
	}
	var changed = false
	fun rewrite(nodes: List<ParameterNode>): List<ParameterNode> =
		nodes.map { node ->
			when (node) {
				is ParameterNode.Param -> node
				is ParameterNode.Group -> {
					val children = rewrite(node.children)
					if (node.id == id && node.name != trimmed) {
						changed = true
						node.copy(name = trimmed, children = children)
					} else if (children !== node.children) {
						node.copy(children = children)
					} else {
						node
					}
				}
			}
		}
	val nextTree = rewrite(materializedParameterTree())
	return if (changed) copy(parameterTree = nextTree) else this
}

/**
 * A copy with folder [id] removed and its children promoted into the enclosing list (Cubism "ungroup").
 * Nested content is preserved; links are untouched. Unknown id is a no-op.
 */
fun PuppetModel.withParameterGroupDeleted(id: ParameterGroupId): PuppetModel {
	var changed = false
	fun rewrite(nodes: List<ParameterNode>): List<ParameterNode> {
		val result = ArrayList<ParameterNode>(nodes.size)
		for (node in nodes) {
			when (node) {
				is ParameterNode.Param -> result.add(node)
				is ParameterNode.Group -> {
					if (node.id == id) {
						changed = true
						result.addAll(node.children)
					} else {
						val children = rewrite(node.children)
						result.add(if (children !== node.children) node.copy(children = children) else node)
					}
				}
			}
		}
		return if (changed || result.size != nodes.size) result else nodes
	}
	val nextTree = rewrite(materializedParameterTree())
	return if (changed) copy(parameterTree = nextTree).withParametersSyncedFromTree() else this
}

/** A copy with folder [id]'s saved expanded state set to [open] (CMO3 `folderIsOpened`). */
fun PuppetModel.withParameterGroupOpen(id: ParameterGroupId, open: Boolean): PuppetModel {
	var changed = false
	fun rewrite(nodes: List<ParameterNode>): List<ParameterNode> =
		nodes.map { node ->
			when (node) {
				is ParameterNode.Param -> node
				is ParameterNode.Group -> {
					val children = rewrite(node.children)
					if (node.id == id && node.initiallyOpen != open) {
						changed = true
						node.copy(initiallyOpen = open, children = children)
					} else if (children !== node.children) {
						node.copy(children = children)
					} else {
						node
					}
				}
			}
		}
	val nextTree = rewrite(materializedParameterTree())
	return if (changed) copy(parameterTree = nextTree) else this
}

/**
 * A copy with panel node [node] moved under [parentId] (null = root) and positioned before [before]
 * (null / absent = append). Rejects unknown nodes, unknown parents, and group cycles. After a
 * successful move the flat [PuppetModel.parameters] list is rewritten to tree preorder so CMO3
 * combined-pair adjacency matches the panel.
 */
fun PuppetModel.withParameterPanelNodeMoved(
	node: ParameterPanelRef,
	parentId: ParameterGroupId?,
	before: ParameterPanelRef?,
): PuppetModel {
	val tree = materializedParameterTree()
	val extracted = extractNode(tree, node) ?: return this
	if (parentId != null && findGroup(extracted.tree, parentId) == null) {
		return this
	}
	if (node is ParameterPanelRef.Group && parentId != null && parentId in groupSelfAndDescendants(extracted.node as ParameterNode.Group)) {
		return this
	}
	if (before == node) {
		return this
	}
	val nextTree = insertNode(extracted.tree, node, extracted.node, parentId, before) ?: return this
	if (nextTree == tree) {
		return this
	}
	var next = copy(parameterTree = nextTree).withParametersSyncedFromTree()
	// Keep LINKED pairs adjacent after a panel move (CMO3 combined = X then Y in document order).
	if (node is ParameterPanelRef.Param) {
		val link = next.parameterLinks.firstOrNull { it.horizontal == node.id || it.vertical == node.id }
		if (link != null && !next.isLinkedPairAdjacent(link.horizontal, link.vertical)) {
			next = next.placeLinkedPairAdjacent(link.horizontal, link.vertical)
		}
	}
	return next
}

/**
 * Places [vertical] immediately after [horizontal] in the same folder (moving it into horizontal's
 * parent when needed). Used when creating a LINKED pair so CMO3 document order stays adjacent.
 */
fun PuppetModel.withLinkedPairAdjacent(horizontal: ParameterId, vertical: ParameterId): PuppetModel =
	placeLinkedPairAdjacent(horizontal, vertical)

private fun PuppetModel.isLinkedPairAdjacent(horizontal: ParameterId, vertical: ParameterId): Boolean {
	val tree = materializedParameterTree()
	val parent = parentOf(tree, ParameterPanelRef.Param(horizontal))
	if (parent != parentOf(tree, ParameterPanelRef.Param(vertical))) {
		return false
	}
	val siblings = childrenOf(tree, parent)
	val horizontalIndex = siblings.indexOfFirst { it.matches(ParameterPanelRef.Param(horizontal)) }
	return horizontalIndex >= 0 &&
		siblings.getOrNull(horizontalIndex + 1)?.matches(ParameterPanelRef.Param(vertical)) == true
}

private fun PuppetModel.placeLinkedPairAdjacent(horizontal: ParameterId, vertical: ParameterId): PuppetModel {
	if (horizontal == vertical) {
		return this
	}
	val tree = materializedParameterTree()
	val horizontalParent = parentOf(tree, ParameterPanelRef.Param(horizontal))
	val siblings = childrenOf(tree, horizontalParent)
	val horizontalIndex = siblings.indexOfFirst { it.matches(ParameterPanelRef.Param(horizontal)) }
	if (horizontalIndex < 0) {
		return this
	}
	if (siblings.getOrNull(horizontalIndex + 1)?.matches(ParameterPanelRef.Param(vertical)) == true) {
		return withParametersSyncedFromTree()
	}
	val before = siblings.getOrNull(horizontalIndex + 1)?.toRef()
		?.takeUnless { it == ParameterPanelRef.Param(vertical) }
	val extracted = extractNode(tree, ParameterPanelRef.Param(vertical)) ?: return this
	val nextTree = insertNode(extracted.tree, ParameterPanelRef.Param(vertical), extracted.node, horizontalParent, before)
		?: return this
	if (nextTree == tree) {
		return withParametersSyncedFromTree()
	}
	return copy(parameterTree = nextTree).withParametersSyncedFromTree()
}

/**
 * Rewrites [PuppetModel.parameters] to the leaf preorder of the materialized panel tree, appending any
 * axes missing from the tree. No-op when already aligned.
 */
fun PuppetModel.withParametersSyncedFromTree(): PuppetModel {
	val order = flattenParamIds(materializedParameterTree())
	if (order.isEmpty()) {
		return this
	}
	val byId = parameters.associateBy { it.id }
	val ordered = order.mapNotNull { byId[it] }
	val seen = order.toSet()
	val missing = parameters.filter { it.id !in seen }
	val next = ordered + missing
	return if (next.map { it.id } == parameters.map { it.id }) this else copy(parameters = next)
}

private data class ExtractedNode(val tree: List<ParameterNode>, val node: ParameterNode)

private fun extractNode(nodes: List<ParameterNode>, ref: ParameterPanelRef): ExtractedNode? {
	fun walk(list: List<ParameterNode>): Pair<List<ParameterNode>, ParameterNode?>? {
		val result = ArrayList<ParameterNode>(list.size)
		var found: ParameterNode? = null
		for (node in list) {
			when {
				found == null && node.matches(ref) -> found = node
				node is ParameterNode.Group -> {
					val nested = walk(node.children)
					if (nested != null) {
						found = nested.second
						result.add(node.copy(children = nested.first))
					} else {
						result.add(node)
					}
				}
				else -> result.add(node)
			}
		}
		return if (found != null) result to found else null
	}
	val walked = walk(nodes) ?: return null
	return ExtractedNode(walked.first, walked.second!!)
}

private fun insertNode(
	nodes: List<ParameterNode>,
	ref: ParameterPanelRef,
	node: ParameterNode,
	parentId: ParameterGroupId?,
	before: ParameterPanelRef?,
): List<ParameterNode>? {
	if (parentId == null) {
		return nodes.withInsertedBefore(node, before)
	}
	var inserted = false
	fun rewrite(list: List<ParameterNode>): List<ParameterNode> =
		list.map { child ->
			when (child) {
				is ParameterNode.Param -> child
				is ParameterNode.Group -> {
					if (child.id == parentId) {
						inserted = true
						child.copy(children = child.children.withInsertedBefore(node, before))
					} else {
						child.copy(children = rewrite(child.children))
					}
				}
			}
		}
	val next = rewrite(nodes)
	return if (inserted) next else null
}

private fun List<ParameterNode>.withInsertedBefore(node: ParameterNode, before: ParameterPanelRef?): List<ParameterNode> {
	val result = toMutableList()
	val insertAt = before?.let { target -> result.indexOfFirst { it.matches(target) } }?.takeIf { it >= 0 } ?: result.size
	result.add(insertAt, node)
	return result
}

private fun ParameterNode.matches(ref: ParameterPanelRef): Boolean =
	when (this) {
		is ParameterNode.Param -> ref is ParameterPanelRef.Param && id == ref.id
		is ParameterNode.Group -> ref is ParameterPanelRef.Group && id == ref.id
	}

private fun ParameterNode.toRef(): ParameterPanelRef =
	when (this) {
		is ParameterNode.Param -> ParameterPanelRef.Param(id)
		is ParameterNode.Group -> ParameterPanelRef.Group(id)
	}

private fun collectGroupIds(nodes: List<ParameterNode>): Set<ParameterGroupId> {
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

private fun findGroup(nodes: List<ParameterNode>, id: ParameterGroupId): ParameterNode.Group? {
	for (node in nodes) {
		if (node is ParameterNode.Group) {
			if (node.id == id) {
				return node
			}
			findGroup(node.children, id)?.let { return it }
		}
	}
	return null
}

private fun groupSelfAndDescendants(group: ParameterNode.Group): Set<ParameterGroupId> {
	val ids = HashSet<ParameterGroupId>()
	fun walk(node: ParameterNode.Group) {
		ids.add(node.id)
		for (child in node.children) {
			if (child is ParameterNode.Group) {
				walk(child)
			}
		}
	}
	walk(group)
	return ids
}

private fun parentOf(nodes: List<ParameterNode>, ref: ParameterPanelRef): ParameterGroupId? {
	fun walk(list: List<ParameterNode>, parent: ParameterGroupId?): ParameterGroupId? {
		for (node in list) {
			if (node.matches(ref)) {
				return parent
			}
			if (node is ParameterNode.Group) {
				walk(node.children, node.id)?.let { return it }
			}
		}
		return null
	}
	return walk(nodes, null)
}

private fun childrenOf(nodes: List<ParameterNode>, parentId: ParameterGroupId?): List<ParameterNode> {
	if (parentId == null) {
		return nodes
	}
	return findGroup(nodes, parentId)?.children.orEmpty()
}

private fun flattenParamIds(nodes: List<ParameterNode>): List<ParameterId> {
	val ids = ArrayList<ParameterId>()
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
