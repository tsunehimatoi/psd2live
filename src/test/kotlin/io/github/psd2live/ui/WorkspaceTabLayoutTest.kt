package io.github.psd2live.ui

import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.WorkspaceTabLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceTabLayoutTest {
	@Test
	fun storedTabLayoutDefaultsToLeftAndFallsBackOnUnknown() {
		assertEquals(WorkspaceTabLayout.LEFT, AppSettings.workspaceTabLayoutOf(null))
		assertEquals(WorkspaceTabLayout.LEFT, AppSettings.workspaceTabLayoutOf("LEFT"))
		assertEquals(WorkspaceTabLayout.TOP, AppSettings.workspaceTabLayoutOf("TOP"))
		assertEquals(WorkspaceTabLayout.LEFT, AppSettings.workspaceTabLayoutOf("UNKNOWN"))
	}
}
