package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the deformer subtree query behind both the reparent cycle guard and the Properties picker's
 * candidate filter: a deformer plus everything nested beneath it, which is exactly the set it may not be
 * re-nested under.  Sharing one definition is what keeps the picker from offering a target the move would
 * then silently refuse.
 */
class DeformerSubtreeTest {
	private val rootId = DeformerId("root")
	private val childId = DeformerId("child")
	private val grandchildId = DeformerId("grandchild")
	private val siblingId = DeformerId("sibling")

	private fun warp(id: DeformerId, parent: DeformerId?): Deformer.Warp =
		Deformer.Warp(
			id = id,
			name = id.raw,
			parent = parent,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = false,
			geometryGrid = null,
		)

	/** root > child > grandchild, with an unrelated sibling at the armature root. */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers =
				listOf(
					warp(rootId, null),
					warp(childId, rootId),
					warp(grandchildId, childId),
					warp(siblingId, null),
				),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	@Test
	fun collectsTheDeformerAndEveryDescendant() {
		assertEquals(setOf(rootId, childId, grandchildId), model().deformerSelfAndDescendants(rootId))
		assertEquals(setOf(childId, grandchildId), model().deformerSelfAndDescendants(childId))
		// A leaf is just itself - self-parenting is still refused because the set contains it.
		assertEquals(setOf(grandchildId), model().deformerSelfAndDescendants(grandchildId))
	}

	@Test
	fun excludesUnrelatedBranchesSoTheyStayValidParents() {
		val forbidden = model().deformerSelfAndDescendants(rootId)
		assertTrue(siblingId !in forbidden, "an unrelated deformer is a legal parent")
	}

	@Test
	fun aMoveIntoTheOwnSubtreeIsRefusedAndAnUnrelatedOneIsNot() {
		val base = model()
		// Every member of the subtree is an illegal parent for root, self included.
		for (candidate in base.deformerSelfAndDescendants(rootId)) {
			assertTrue(candidate in base.deformerSelfAndDescendants(rootId))
		}
		// The picker filters on exactly this, so what it offers is what the move accepts.
		assertTrue(rootId in base.deformerSelfAndDescendants(rootId), "self is in the forbidden set")
		assertTrue(grandchildId in base.deformerSelfAndDescendants(rootId), "a descendant is forbidden")
	}
}