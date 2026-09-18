package io.github.psd2live.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the invariants the mode-filtered toolbar and [CanvasEditor.activateTool] rely on.
 *
 * The palette is now the single answer to "can this mode use this tool" — the toolbar draws itself
 * from it and `activateTool` refuses anything outside it. Both would rot silently: a tool missing from
 * every palette becomes unreachable with no test to notice, and a tool left out of
 * [TOOLBAR_TOOL_ORDER] is never drawn even when the palette offers it.
 */
class ToolbarPaletteTest {

    /**
     * Every mode must offer SELECT, because it is what `setHierarchyMode` falls back to when the tool
     * it is leaving behind has no place in the mode it is entering. A mode without it would leave that
     * fallback refused and the canvas armed with a tool the palette does not show.
     */
    @Test
    fun everyModeOffersTheFallbackTool() {
        for (mode in EditHierarchyMode.entries) {
            assertTrue(
                CanvasTool.SELECT in toolbarGroups(mode).flatten(),
                "$mode has no ${CanvasTool.SELECT.name} to fall back to",
            )
        }
    }

    /** The toolbar walks [TOOLBAR_TOOL_ORDER], so anything the palettes offer has to be in it. */
    @Test
    fun everyPalettedToolHasARowToDrawIn() {
        for (mode in EditHierarchyMode.entries) {
            for (tool in toolbarGroups(mode).flatten()) {
                assertTrue(tool in TOOLBAR_TOOL_ORDER, "$mode offers ${tool.name}, which has no toolbar row")
            }
        }
    }

    /**
     * A tool no mode offers could never be armed, and its shortcut would be dead in every mode — the
     * exact outcome the palette is meant to produce deliberately, not by omission.
     */
    @Test
    fun everyToolIsOfferedBySomeMode() {
        val offered = EditHierarchyMode.entries.flatMap { toolbarGroups(it).flatten() }.toSet()
        val orphans = CanvasTool.entries.filter { it !in offered }.map { it.name }
        assertTrue(orphans.isEmpty(), "no mode offers: $orphans")
    }

    /** Every row is animated by identity, so a tool listed twice would draw and animate twice. */
    @Test
    fun everyToolHasExactlyOneToolbarRow() {
        assertEquals(TOOLBAR_TOOL_ORDER.size, TOOLBAR_TOOL_ORDER.distinct().size, "TOOLBAR_TOOL_ORDER repeats a tool")
        for (mode in EditHierarchyMode.entries) {
            val tools = toolbarGroups(mode).flatten()
            assertEquals(tools.size, tools.distinct().size, "$mode lists a tool in two groups")
        }
    }
}
