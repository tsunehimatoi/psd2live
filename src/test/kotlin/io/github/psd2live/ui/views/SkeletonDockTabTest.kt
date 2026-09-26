package io.github.psd2live.ui.views

import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonDockTabTest {
	@Test fun freshLayoutsHaveTheSkeletonBesideTheHierarchy() {
		assertEquals(listOf("hierarchy", "skeleton"), defaultDockLayout().containing("hierarchy")!!.modules)
	}

	@Test fun olderLayoutsGainTheSkeletonTabOnce() {
		val legacy = DockNode(ratio = .28f, first = DockNode(modules = listOf("hierarchy")), second = DockNode(modules = listOf("canvas")))
		val repaired = ensureSkeletonDockTab(legacy)
		assertEquals(listOf("hierarchy", "skeleton"), repaired.containing("hierarchy")!!.modules)
		assertEquals(repaired.allModules(), ensureSkeletonDockTab(repaired).allModules())
	}
}
