package io.github.psd2live.ui.views

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DockLayoutTest {
    @Test fun newCanvasStaysBesideTheDefaultCanvasInsideTheWorkspace() {
        val base = defaultDockLayout()
        val placed = requireNotNull(reconcileDockModules(base, listOf("canvas", "canvas:new"), emptyList()))

        assertEquals(base.id, placed.id)
        assertEquals(base.ratio, placed.ratio)
        assertEquals(base.second, placed.second)
        assertEquals(base.first?.first, placed.first?.first)
        assertTrue(placed.first?.allModules()?.containsAll(listOf("canvas", "canvas:new", "hierarchy", "log")) == true)

        assertSame(placed, reconcileDockModules(placed, listOf("canvas", "canvas:new"), emptyList()))
        val history = requireNotNull(reconcileDockModules(placed, listOf("canvas", "canvas:new"), listOf("history")))
        assertEquals(base.id, history.id)
        assertEquals(base.second, history.second)
        assertTrue(history.first?.allModules()?.contains("history") == true)
    }

    @Test fun repairsPreviouslySavedRootLevelCanvasSplits() {
        val base = defaultDockLayout()
        val malformed = dockModule(base, "canvas:first", "canvas", DockSide.RIGHT)
        assertNotEquals(base.id, malformed.id)
        val malformedTwice = dockModule(malformed, "canvas:second", "canvas", DockSide.RIGHT)

        val repaired = repairLegacyCanvasDocking(malformedTwice)
        assertEquals(base.id, repaired.id)
        assertEquals(base.second, repaired.second)
        assertTrue(repaired.first?.allModules()?.containsAll(listOf("canvas", "canvas:first", "canvas:second")) == true)
        assertEquals(malformedTwice.allModules().toSet(), repaired.allModules().toSet())

        val custom = dockBesideModule(base, "canvas:custom", "canvas", DockSide.RIGHT)
        assertSame(custom, repairLegacyCanvasDocking(custom))
    }

    @Test fun insertsMeshTabIntoLegacyInspectorLeaf() {
        val legacy = DockNode(modules = listOf("layers", "parameters", "tools", "inspector", "animation", "physics"))
        val updated = ensureMeshDockTab(legacy)
        assertEquals(
            listOf("layers", "parameters", "tools", "mesh", "inspector", "animation", "physics"),
            updated.modules,
        )
        assertSame(updated, ensureMeshDockTab(updated))
    }

    @Test fun insertsAnimationEditorTabBesideTheLog() {
        assertEquals(listOf("log", "animationEditor"), defaultDockLayout().containing("log")?.modules)
        val legacy = DockNode(modules = listOf("log"))
        val updated = ensureAnimationEditorDockTab(legacy)
        assertEquals(listOf("log", "animationEditor"), updated.modules)
        assertSame(updated, ensureAnimationEditorDockTab(updated))
        val withoutLog = DockNode(modules = listOf("canvas"))
        assertSame(withoutLog, ensureAnimationEditorDockTab(withoutLog))
    }
}
