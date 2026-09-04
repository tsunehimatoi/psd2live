package io.github.psd2live.history

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class WorkspaceHistoryTreeTest {
	@Test
	fun checkoutThenCommitCreatesABranchWithoutDeletingTheOldFuture() {
		var id = 0
		val tree = WorkspaceHistoryTree(
			initialSnapshot = "root-state",
			initialRevisionId = "revision-root",
			initialSnapshotHash = "hash-root",
			clock = { Instant.EPOCH.plusSeconds(id.toLong()) },
			newId = { "node-${id++}" },
		)
		val root = tree.head().node
		val first = tree.commit(root.id, "first-state", "revision-1", "hash-1", "First edit")
		val oldFuture = tree.commit(first.node.id, "old-future", "revision-2a", "hash-2a", "Old future")

		tree.checkout(first.node.id)
		val newFuture = tree.commit(first.node.id, "new-future", "revision-2b", "hash-2b", "New future")

		assertNotEquals(oldFuture.node.id, newFuture.node.id)
		assertEquals(setOf(oldFuture.node.id, newFuture.node.id), tree.childrenOf(first.node.id).map { it.id }.toSet())
		assertEquals("new-future", tree.head().snapshot)
		assertEquals("old-future", tree.checkout(oldFuture.node.id).snapshot)
		assertEquals(4, tree.nodes().size)
	}

	@Test
	fun staleCommitCannotOverwriteAConcurrentHead() {
		var id = 0
		val tree = WorkspaceHistoryTree("root", "revision-root", "hash-root", newId = { "node-${id++}" })
		val inspectedHead = tree.head().node.id
		tree.commit(inspectedHead, "first", "revision-1", "hash-1", "First edit")

		assertFailsWith<StaleWorkspaceHeadException> {
			tree.commit(inspectedHead, "stale", "revision-stale", "hash-stale", "Stale edit")
		}
		assertEquals("first", tree.head().snapshot)
		assertEquals(2, tree.nodes().size)
	}

	@Test
	fun checkoutRejectsUnknownNodeWithoutChangingHead() {
		val tree = WorkspaceHistoryTree("root", "revision-root", "hash-root", newId = { "root-node" })
		val head = tree.head()

		assertFailsWith<IllegalArgumentException> { tree.checkout("missing") }
		assertEquals(head, tree.head())
	}
}
