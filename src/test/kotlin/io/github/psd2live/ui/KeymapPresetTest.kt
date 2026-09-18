package io.github.psd2live.ui

import io.github.psd2live.ui.state.Keymap
import io.github.psd2live.ui.state.KeymapPreset
import io.github.psd2live.ui.state.ShortcutAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the two invariants the shortcut presets rely on and that nothing else checks.
 *
 * `Keymap.init` only verifies that every action *has* a default; it cannot see a preset that claims
 * one chord for two actions. That failure is silent and nasty: `buildIndex` is a `buildMap` of `put`
 * calls, so the later action wins and the earlier one's key is dead while its label is still drawn
 * in the toolbar and the settings table.
 */
class KeymapPresetTest {

    @Test
    fun noPresetClaimsOneChordForTwoActions() {
        for (preset in KeymapPreset.entries) {
            val conflicts = Keymap.of(preset).conflictIndex().filterValues { it.size > 1 }
            assertTrue(conflicts.isEmpty(), "$preset claims the same chord twice: $conflicts")
        }
    }

    /**
     * Every canvas tool has to be reachable from the keyboard in every preset. A preset override that
     * moves a tool off its only key, without giving it a replacement, leaves that tool unselectable.
     */
    @Test
    fun everyCanvasToolIsBoundInEveryPreset() {
        for (preset in KeymapPreset.entries) {
            val keymap = Keymap.of(preset)
            for (tool in CanvasTool.entries) {
                assertNotNull(keymap.labelFor(tool.action), "${tool.name} has no key in $preset")
            }
        }
    }

    /** Selecting a tool and selecting its shortcut must agree on which action a chord resolves to. */
    @Test
    fun eachToolOwnerResolvesToItsOwnAction() {
        for (preset in KeymapPreset.entries) {
            val keymap = Keymap.of(preset)
            val owners = keymap.conflictIndex()
            for (tool in CanvasTool.entries) {
                for (binding in keymap.bindingsFor(tool.action)) {
                    val owner = owners[binding]?.singleOrNull()
                    assertTrue(owner == tool.action, "$preset: ${tool.name}'s chord $binding resolves to $owner instead")
                }
            }
        }
    }

    /**
     * An empty default binding is a deliberate "this command has no key" declaration, not an oversight —
     * but it is also what an accidental `keys()` typo looks like. Pinning the exempt set makes the
     * difference visible: a new name here means someone meant it, and nothing else may join it.
     */
    @Test
    fun onlyTheKnownActionsAreUnboundByDefault() {
        val deliberatelyUnbound = setOf(ShortcutAction.OPEN_OUTPUT)
        val unbound = ShortcutAction.entries.filter { Keymap.DEFAULT.bindingsFor(it).isEmpty() }.toSet()
        assertEquals(deliberatelyUnbound, unbound)
    }
}
