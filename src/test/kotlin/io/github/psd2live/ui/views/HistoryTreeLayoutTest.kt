package io.github.psd2live.ui.views

import io.github.psd2live.agent.AgentHistoryNodeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryTreeLayoutTest {

	private fun createNode(id: String, parentId: String?, isHead: Boolean = false): AgentHistoryNodeSnapshot {
		return AgentHistoryNodeSnapshot(
			id = id,
			parentId = parentId,
			revisionId = "rev-$id",
			summary = "Summary for $id",
			actor = "agent",
			taskId = null,
			createdAt = "2026-09-05T12:00:00Z",
			isHead = isHead,
		)
	}

	@Test
	fun emptyTreeReturnsZeroBounds() {
		val result = calculateTreeLayout(emptyList())
		assertEquals(0f, result.width)
		assertEquals(0f, result.height)
		assertTrue(result.roots.isEmpty())
		assertTrue(result.allNodes.isEmpty())
	}

	@Test
	fun singleNodeTreeHasPositiveBounds() {
		val root = createNode("root", null, isHead = true)
		val result = calculateTreeLayout(listOf(root))
		assertEquals(1, result.roots.size)
		assertEquals(1, result.allNodes.size)
		assertTrue(result.width > 0f)
		assertTrue(result.height > 0f)
	}

	@Test
	fun linearTreePlacesNodesVerticallyWithoutOverlap() {
		val n1 = createNode("1", null)
		val n2 = createNode("2", "1")
		val n3 = createNode("3", "2", isHead = true)
		val result = calculateTreeLayout(listOf(n1, n2, n3))

		assertEquals(3, result.allNodes.size)
		val node1 = result.allNodes.first { it.node.id == "1" }
		val node2 = result.allNodes.first { it.node.id == "2" }
		val node3 = result.allNodes.first { it.node.id == "3" }

		assertTrue(node2.y > node1.y)
		assertTrue(node3.y > node2.y)
		assertNoCollisions(result)
	}

	@Test
	fun branchedTreePlacesSiblingsHorizontallyWithoutOverlap() {
		val root = createNode("root", null)
		val branchA = createNode("A", "root")
		val branchB = createNode("B", "root")
		val result = calculateTreeLayout(listOf(root, branchA, branchB))

		assertEquals(3, result.allNodes.size)
		val nodeA = result.allNodes.first { it.node.id == "A" }
		val nodeB = result.allNodes.first { it.node.id == "B" }

		assertEquals(nodeA.y, nodeB.y)
		assertTrue(kotlin.math.abs(nodeA.x - nodeB.x) >= 250f + 40f)
		assertNoCollisions(result)
	}

	@Test
	fun complexDivergentHistoryHasZeroOverlaps() {
		// Create a realistic git-style divergent history:
		// root -> c1 -> c2 -> c3 (HEAD)
		//          |--> b1 -> b2
		//                |--> b2_side
		// root2 -> r2_1
		val nodes = listOf(
			createNode("root", null),
			createNode("c1", "root"),
			createNode("c2", "c1"),
			createNode("c3", "c2", isHead = true),
			createNode("b1", "c1"),
			createNode("b2", "b1"),
			createNode("b2_side", "b1"),
			createNode("root2", null),
			createNode("r2_1", "root2"),
		)

		val result = calculateTreeLayout(nodes)
		assertEquals(9, result.allNodes.size)
		assertNoCollisions(result)
	}

	private fun assertNoCollisions(result: TreeCalculationResult) {
		val nodes = result.allNodes
		val nodeWidth = 250f
		val nodeHeight = 80f

		for (i in nodes.indices) {
			for (j in i + 1 until nodes.size) {
				val a = nodes[i]
				val b = nodes[j]

				val xOverlap = (a.x < b.x + nodeWidth) && (a.x + nodeWidth > b.x)
				val yOverlap = (a.y < b.y + nodeHeight) && (a.y + nodeHeight > b.y)

				if (xOverlap && yOverlap) {
					throw AssertionError("Collision detected between node ${a.node.id} at (${a.x}, ${a.y}) and node ${b.node.id} at (${b.x}, ${b.y})")
				}
			}
		}
	}
}

