package io.github.psd2live.ui

import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.ui.state.ImportPresetContinuation
import io.github.psd2live.ui.state.StartPresetChoices
import kotlin.test.*

class ImportPresetContinuationTest {
    private val choices = StartPresetChoices(modifiers = WorkspaceInitialModifiers.RECOMMENDED)

    @Test fun waitsForNamingAndSplitCompletionThenConsumesOnce() {
        val pending = ImportPresetContinuation()
        pending.start(3, choices)
        assertTrue(pending.waiting)
        repeat(4) { assertNull(pending.take(3, blocked = true)) }
        assertEquals(choices, pending.take(3, blocked = false))
        assertFalse(pending.waiting)
        assertNull(pending.take(3, blocked = false))
    }

    @Test fun skippedOrUnneededSplitStillAppliesTheChosenPreset() {
        val pending = ImportPresetContinuation()
        val custom = choices.copy(modifiers = WorkspaceInitialModifiers.CUSTOM, headStrength = .6f)
        pending.start(1, custom)
        assertEquals(custom, pending.take(1, blocked = false))
    }

    @Test fun openingAnotherProjectDiscardsContinuation() {
        val pending = ImportPresetContinuation()
        pending.start(1, choices)
        assertNull(pending.take(2, blocked = false))
        assertNull(pending.take(1, blocked = false))
    }

    @Test fun failedCheckOrCancelledImportDoesNotBind() {
        val pending = ImportPresetContinuation()
        pending.start(1, choices)
        pending.clear()
        assertNull(pending.take(1, blocked = false))
    }

    @Test fun existingProjectHasNoPendingImport() {
        assertNull(ImportPresetContinuation().take(1, blocked = false))
    }
}
