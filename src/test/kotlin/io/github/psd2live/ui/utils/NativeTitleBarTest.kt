package io.github.psd2live.ui.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeTitleBarTest {
    @Test fun opaqueLinuxWindowsKeepSystemDecorationsOnAnyJdk() {
        assertTrue(NativeTitleBar.useSystemTitleBar(false, "Linux"))
        assertFalse(NativeTitleBar.useSystemTitleBar(true, "Linux"))
        assertFalse(NativeTitleBar.useSystemTitleBar(false, "Windows 11"))
        assertFalse(NativeTitleBar.useSystemTitleBar(false, "Mac OS X"))
    }

}
