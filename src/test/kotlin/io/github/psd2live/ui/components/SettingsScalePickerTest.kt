package io.github.psd2live.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsScalePickerTest {
    @Test fun customPercentAcceptsWholePercentagesWithinTheSupportedRange() {
        assertEquals(0.75f, parseUiScalePercent("75"))
        assertEquals(1.37f, parseUiScalePercent(" 137% "))
        assertEquals(3f, parseUiScalePercent("300"))
        for (invalid in listOf("", "74", "301%", "137.5", "abc")) {
            assertNull(parseUiScalePercent(invalid), invalid)
        }
    }
}
