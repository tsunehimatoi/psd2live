package io.github.psd2live.ui.views

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DockGroupTest {
	private val group = DockNode(id = "group", modules = listOf("a", "b"), selected = "b")
	private val target = DockNode(id = "target", modules = listOf("x", "y"))
	private val other = DockNode(id = "other", modules = listOf("z"))
	private val root = DockNode(id = "root", first = group, second = DockNode(id = "right", horizontal = false, first = target, second = other))

	@Test fun aGroupMergesIntoAnotherGroupsTabs() {
		val merged = dockGroup(root, "group", "target", DockSide.CENTER, before = "y")
		val leaf = merged.find("target")!!
		assertEquals(listOf("x", "a", "b", "y"), leaf.modules)
		assertEquals("b", leaf.selected)
		assertNull(merged.find("group"))
		assertEquals(listOf("x", "a", "b", "y", "z"), merged.allModules())
	}

	@Test fun aGroupSplitsBesideAnotherPanelWhole() {
		val moved = dockGroup(root, "group", "other", DockSide.LEFT)
		assertEquals(listOf("x", "y", "a", "b", "z"), moved.allModules())
		val split = moved.second!!
		assertEquals(true, split.horizontal)
		assertEquals(group, split.first)
		assertEquals("other", split.second!!.id)
	}

	@Test fun aSavedLayoutsRetiredPanelsGiveWayToTheModifiersTab() {
		// The simulation, interior and model presets panels became the modifiers panel: it takes their place.
		val saved = DockNode(id = "root", first = DockNode(id = "left", modules = listOf("physics", "simulation", "interior", "layers")),
			second = DockNode(id = "right", modules = listOf("settings", "z")))
		assertEquals(listOf("physics", "layers", "modifiers", "z"), retireDockModules(ensureModifiersDockTab(saved)).allModules())
		val kept = DockNode(id = "root", first = DockNode(id = "left", modules = listOf("physics", "modifiers")), second = other)
		assertEquals(kept.allModules(), retireDockModules(ensureModifiersDockTab(kept)).allModules())
	}

}
