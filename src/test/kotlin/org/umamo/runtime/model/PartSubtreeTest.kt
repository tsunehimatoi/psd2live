package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the part subtree query behind both the org-move cycle guard and the Properties picker's owner
 * filter: a part plus every part beneath it, which is exactly the set it may NOT be re-homed under.
 *
 * This replaced two private implementations that walked the tree in OPPOSITE directions - one down from
 * the part, one up the parent chain - so the point of the test is that ONE definition serves both, and
 * that a malformed (already cyclic) tree terminates rather than hanging the editor.
 */
class PartSubtreeTest {
	private val rootId = PartId("root")
	private val childId = PartId("child")
	private val grandchildId = PartId("grandchild")
	private val siblingId = PartId("sibling")

	private fun part(id: PartId, children: List<OrgChild> = emptyList()): Part = Part(id, id.raw, children = children)

	/** root > child > grandchild, with an unrelated sibling at the top level. */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts =
				listOf(
					part(rootId, listOf(OrgChild.Part(childId))),
					part(childId, listOf(OrgChild.Part(grandchildId))),
					part(grandchildId),
					part(siblingId),
				),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = listOf(OrgChild.Part(rootId), OrgChild.Part(siblingId)),
			rootPartId = null,
		)

	@Test
	fun collectsThePartAndEveryDescendant() {
		assertEquals(setOf(rootId, childId, grandchildId), model().partSelfAndDescendants(rootId))
		assertEquals(setOf(childId, grandchildId), model().partSelfAndDescendants(childId))
		// A leaf is just itself - self-parenting is still refused because the set contains it.
		assertEquals(setOf(grandchildId), model().partSelfAndDescendants(grandchildId))
	}

	@Test
	fun excludesUnrelatedBranchesSoTheyStayValidOwners() {
		val forbidden = model().partSelfAndDescendants(rootId)
		assertTrue(siblingId !in forbidden, "an unrelated part is a legal owner")
		assertTrue(rootId in forbidden, "self is in the forbidden set")
		assertTrue(grandchildId in forbidden, "a descendant is forbidden")
	}

	@Test
	fun aMalformedCyclicTreeTerminates() {
		// child claims root as a child, so the tree already contains a cycle.  The walk must stop, not hang.
		val cyclic =
			model().copy(
				parts =
					listOf(
						part(rootId, listOf(OrgChild.Part(childId))),
						part(childId, listOf(OrgChild.Part(rootId))),
					),
			)
		assertEquals(setOf(rootId, childId), cyclic.partSelfAndDescendants(rootId))
	}

	@Test
	fun aPartMissingFromTheListIsJustItself() {
		assertEquals(setOf(PartId("ghost")), model().partSelfAndDescendants(PartId("ghost")))
	}
}