package io.github.psd2live.history

import java.time.Instant
import java.util.UUID

/** Immutable metadata for one committed workspace state. */
data class WorkspaceHistoryNode(
	val id: String,
	val parentId: String?,
	val revisionId: String,
	val snapshotHash: String,
	val summary: String,
	val actor: String,
	val taskId: String?,
	val createdAt: Instant,
)

/** The state selected by a history operation; the history node itself is never modified. */
data class WorkspaceHistorySelection<T>(
	val node: WorkspaceHistoryNode,
	val snapshot: T,
)

data class WorkspaceHistoryState<T>(
	val headNodeId: String,
	val selections: List<WorkspaceHistorySelection<T>>,
)

/** Raised when a long-running command tries to commit on a HEAD it did not inspect. */
class StaleWorkspaceHeadException(expected: String, actual: String) :
	IllegalStateException("Workspace HEAD changed: expected $expected, actual $actual")

/**
 * Append-only branch-preserving history for the authoritative workspace snapshot.
 *
 * [T] must be an immutable value (or an immutable content-addressed handle). Committing after a checkout
 * creates a new child and retains every previous child. Checkout only moves HEAD; no API can replace,
 * delete, reorder, or otherwise mutate a node already stored here.
 *
 * Methods are synchronized because an embedded MCP server may execute independent Agent tasks at the
 * same time as UI commands. The expected-HEAD precondition turns that race into an explicit retry rather
 * than a lost update.
 */
class WorkspaceHistoryTree<T>(
	initialSnapshot: T,
	initialRevisionId: String,
	initialSnapshotHash: String,
	private val clock: () -> Instant = Instant::now,
	private var newId: () -> String = { "history-${UUID.randomUUID()}" },
) {
	private data class Stored<T>(val node: WorkspaceHistoryNode, val snapshot: T)

	private val storedById = linkedMapOf<String, Stored<T>>()
	private var headId: String

	init {
		require(initialRevisionId.isNotBlank()) { "Initial revision ID must not be blank" }
		require(initialSnapshotHash.isNotBlank()) { "Initial snapshot hash must not be blank" }
		val root = WorkspaceHistoryNode(
			id = uniqueId(),
			parentId = null,
			revisionId = initialRevisionId,
			snapshotHash = initialSnapshotHash,
			summary = "Workspace opened",
			actor = "system",
			taskId = null,
			createdAt = clock(),
		)
		storedById[root.id] = Stored(root, initialSnapshot)
		headId = root.id
	}

	@Synchronized
	fun head(): WorkspaceHistorySelection<T> = storedById.getValue(headId).selection()

	@Synchronized
	fun nodes(): List<WorkspaceHistoryNode> = storedById.values.map(Stored<T>::node)

	@Synchronized
	fun selections(): List<WorkspaceHistorySelection<T>> = storedById.values.map { it.selection() }

	@Synchronized
	fun state(): WorkspaceHistoryState<T> = WorkspaceHistoryState(headId, storedById.values.map { it.selection() })

	@Synchronized
	fun childrenOf(nodeId: String): List<WorkspaceHistoryNode> {
		requireNode(nodeId)
		return storedById.values.map(Stored<T>::node).filter { node -> node.parentId == nodeId }
	}

	/** Read an immutable historical snapshot without moving HEAD. */
	@Synchronized
	fun selectionAt(nodeId: String): WorkspaceHistorySelection<T> = requireNode(nodeId).selection()

	/** Append a child of the current HEAD and atomically advance HEAD to it. */
	@Synchronized
	fun commit(
		expectedHeadNodeId: String,
		snapshot: T,
		revisionId: String,
		snapshotHash: String,
		summary: String,
		actor: String = "agent",
		taskId: String? = null,
	): WorkspaceHistorySelection<T> {
		if (expectedHeadNodeId != headId) throw StaleWorkspaceHeadException(expectedHeadNodeId, headId)
		require(revisionId.isNotBlank()) { "Revision ID must not be blank" }
		require(snapshotHash.isNotBlank()) { "Snapshot hash must not be blank" }
		require(summary.isNotBlank()) { "History summary must not be blank" }
		require(actor.isNotBlank()) { "History actor must not be blank" }

		val node = WorkspaceHistoryNode(
			id = uniqueId(),
			parentId = headId,
			revisionId = revisionId,
			snapshotHash = snapshotHash,
			summary = summary,
			actor = actor,
			taskId = taskId,
			createdAt = clock(),
		)
		storedById[node.id] = Stored(node, snapshot)
		headId = node.id
		return WorkspaceHistorySelection(node, snapshot)
	}

	/** Move HEAD to an existing snapshot without changing the tree. */
	@Synchronized
	fun checkout(nodeId: String): WorkspaceHistorySelection<T> {
		val stored = requireNode(nodeId)
		headId = nodeId
		return stored.selection()
	}

	private fun requireNode(nodeId: String): Stored<T> =
		storedById[nodeId] ?: throw IllegalArgumentException("History node not found: $nodeId")

	private fun uniqueId(): String {
		var candidate = newId()
		require(candidate.isNotBlank()) { "History node ID must not be blank" }
		while (candidate in storedById) candidate = newId()
		return candidate
	}

	private fun Stored<T>.selection(): WorkspaceHistorySelection<T> = WorkspaceHistorySelection(node, snapshot)

	companion object {
		/** Rehydrate a previously validated append-only tree without creating a synthetic new root. */
		fun <T> restore(
			selections: List<WorkspaceHistorySelection<T>>,
			headNodeId: String,
			clock: () -> Instant = Instant::now,
			newId: () -> String = { "history-${UUID.randomUUID()}" },
		): WorkspaceHistoryTree<T> {
			require(selections.isNotEmpty()) { "Persisted history must contain at least one node" }
			val ids = selections.map { it.node.id }
			require(ids.all(String::isNotBlank) && ids.toSet().size == ids.size) { "Persisted history node IDs must be unique and non-blank" }
			val byId = selections.associateBy { it.node.id }
			require(headNodeId in byId) { "Persisted history HEAD does not exist: $headNodeId" }
			require(selections.count { it.node.parentId == null } == 1) { "Persisted history must contain exactly one root" }
			val validated = mutableSetOf<String>()
            for (selection in selections) {
				val node = selection.node
				require(node.revisionId.isNotBlank() && node.snapshotHash.isNotBlank()) { "Persisted history node metadata is incomplete: ${node.id}" }
				node.parentId?.let { parent -> require(parent in byId) { "Persisted history parent is missing: $parent" } }
				val visited = mutableSetOf<String>()
				var cursor: String? = node.id
				while (cursor != null && cursor !in validated) {
					require(visited.add(cursor)) { "Persisted history contains a cycle at $cursor" }
					cursor = byId.getValue(cursor).node.parentId
				}
                validated.addAll(visited)
			}
			val root = selections.single { it.node.parentId == null }
			val tree = WorkspaceHistoryTree(
				initialSnapshot = root.snapshot,
				initialRevisionId = root.node.revisionId,
				initialSnapshotHash = root.node.snapshotHash,
				clock = clock,
				newId = { root.node.id },
			)
			tree.storedById.clear()
			selections.forEach { selection ->
				tree.storedById[selection.node.id] = Stored(selection.node, selection.snapshot)
			}
			tree.headId = headNodeId
			// Future commits must use the caller's ID source, not the bootstrap root ID supplier.
			tree.newId = newId
			return tree
		}
	}
}
