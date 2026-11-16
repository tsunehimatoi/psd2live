package io.github.psd2live.ui.state

import java.util.prefs.Preferences

/** Keeps a safe startup mode until a Desktop session closes normally. */
internal class CanvasBackgroundRecovery(private val preferences: Preferences) {
	var kind: CanvasBackgroundKind = CanvasBackgroundKind.fromId(preferences.get(KIND, null))
		private set
	private var armed = false

	/** Called after acquiring the instance lock, before creating any windows or UI state. */
	fun begin() {
		if (preferences.getBoolean(PENDING, false)) {
			kind = safeKind(preferences.get(SAFE_KIND, null))
			preferences.put(KIND, kind.id)
			preferences.putBoolean(PENDING, false)
			preferences.flush()
			System.err.println("Previous Desktop background session did not close normally; restored ${kind.id} background.")
		}
		select(kind)
	}

	fun select(next: CanvasBackgroundKind) {
		if (next == CanvasBackgroundKind.TRANSPARENT) {
			if (!armed) {
				val stored = CanvasBackgroundKind.fromId(preferences.get(KIND, null))
				val safe = if (stored == CanvasBackgroundKind.TRANSPARENT) safeKind(preferences.get(SAFE_KIND, null)) else stored
				preferences.put(SAFE_KIND, safe.id)
				preferences.putBoolean(PENDING, true)
				// Must reach disk before the transparent window can render or block the event thread.
				preferences.flush()
				armed = true
			}
		} else {
			preferences.put(KIND, next.id)
			preferences.put(SAFE_KIND, next.id)
			// Keep the marker until normal shutdown: replacing the transparent window can also stall.
			if (armed && next != kind) preferences.flush()
		}
		kind = next
	}

	/** Never call from a shutdown hook: termination signals are not evidence of a working window. */
	fun finish() {
		preferences.put(KIND, kind.id)
		preferences.putBoolean(PENDING, false)
		preferences.flush()
		armed = false
	}

	private fun safeKind(id: String?) = CanvasBackgroundKind.fromId(id)
		.takeUnless { it == CanvasBackgroundKind.TRANSPARENT } ?: CanvasBackgroundKind.CHECKER

	private companion object {
		const val KIND = "canvas_bg_kind"
		const val SAFE_KIND = "canvas_bg_safe_kind"
		const val PENDING = "canvas_bg_desktop_pending"
	}
}
