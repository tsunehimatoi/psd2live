package org.umamo.interop.cmo3

import kotlin.test.Test
import kotlin.test.assertEquals

class Cmo3DrawOrdersTest {
	private fun renumber(vararg orders: Float) = Cmo3DrawOrders.renumbering(orders.sorted()).let { map -> orders.sorted().map { map.getValue(it).toInt() } }

	@Test fun aFractionTakesAFreeNeighbourAndWholeOrdersKeepTheirNumbers() {
		// 37.5 between the teeth at 36 and the lip outline at 38 rounds onto 38, so it takes 37.
		assertEquals(listOf(33, 36, 37, 38), renumber(33f, 36f, 37.5f, 38f))
		assertEquals(listOf(33, 38, 39, 500), renumber(33f, 38f, 38.5f, 500f))
	}

	@Test fun aFractionWithNoGapPushesTheOrdersAboveItUp() {
		assertEquals(listOf(38, 39, 40, 41), renumber(38f, 38.5f, 39f, 40f))
		assertEquals(listOf(1, 2, 3), renumber(1.2f, 1.4f, 1.6f))
	}

	@Test fun ordersThatRunPastTheTopAreRanked() {
		assertEquals(listOf(0, 1, 2), renumber(999f, 999.5f, 1000f))
	}
}
