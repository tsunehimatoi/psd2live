package io.github.psd2live.ui.state

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasBackgroundRecoveryTest {
	private fun withPreferences(test: (Preferences) -> Unit) {
		val preferences = Preferences.userRoot().node("io.github.psd2live.tests/background-${UUID.randomUUID()}")
		try { test(preferences) } finally { preferences.removeNode(); preferences.flush() }
	}

	@Test fun failedDesktopTransitionKeepsPreviousMode() = withPreferences { preferences ->
		preferences.put("canvas_bg_kind", "solid")
		val session = CanvasBackgroundRecovery(preferences)
		session.begin()
		session.select(CanvasBackgroundKind.TRANSPARENT)
		assertEquals(CanvasBackgroundKind.TRANSPARENT, session.kind)
		assertEquals("solid", preferences.get("canvas_bg_kind", null))
		assertTrue(preferences.getBoolean("canvas_bg_desktop_pending", false))
		val restarted = CanvasBackgroundRecovery(preferences)
		restarted.begin()
		assertEquals(CanvasBackgroundKind.SOLID, restarted.kind)
		assertFalse(preferences.getBoolean("canvas_bg_desktop_pending", true))
	}

	@Test fun legacySavedDesktopIsGuardedBeforeFirstWindow() = withPreferences { preferences ->
		preferences.put("canvas_bg_kind", "transparent")
		val session = CanvasBackgroundRecovery(preferences)
		session.begin()
		assertEquals(CanvasBackgroundKind.TRANSPARENT, session.kind)
		assertTrue(preferences.getBoolean("canvas_bg_desktop_pending", false))
		val restarted = CanvasBackgroundRecovery(preferences)
		restarted.begin()
		assertEquals(CanvasBackgroundKind.CHECKER, restarted.kind)
		assertEquals("checker", preferences.get("canvas_bg_kind", null))
	}

	@Test fun normalExitRemembersDesktopAndRetainsSafeModeForLaterFailure() = withPreferences { preferences ->
		preferences.put("canvas_bg_kind", "solid")
		val session = CanvasBackgroundRecovery(preferences)
		session.begin()
		session.select(CanvasBackgroundKind.TRANSPARENT)
		session.finish()
		assertEquals("transparent", preferences.get("canvas_bg_kind", null))
		assertFalse(preferences.getBoolean("canvas_bg_desktop_pending", true))
		val restarted = CanvasBackgroundRecovery(preferences)
		restarted.begin()
		assertEquals(CanvasBackgroundKind.TRANSPARENT, restarted.kind)
		val afterCrash = CanvasBackgroundRecovery(preferences)
		afterCrash.begin()
		assertEquals(CanvasBackgroundKind.SOLID, afterCrash.kind)
	}

	@Test fun leavingDesktopKeepsRecoveryArmedUntilNormalExit() = withPreferences { preferences ->
		val session = CanvasBackgroundRecovery(preferences)
		session.begin()
		session.select(CanvasBackgroundKind.TRANSPARENT)
		session.select(CanvasBackgroundKind.SOLID)
		assertTrue(preferences.getBoolean("canvas_bg_desktop_pending", false))
		val restarted = CanvasBackgroundRecovery(preferences)
		restarted.begin()
		assertEquals(CanvasBackgroundKind.SOLID, restarted.kind)
		restarted.finish()
		assertFalse(preferences.getBoolean("canvas_bg_desktop_pending", true))
	}
}
