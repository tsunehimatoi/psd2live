package io.github.psd2live.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionKey
import kotlin.math.abs

internal enum class MotionEditorView { DOPESHEET, CURVES }

/** A key by its curve and time: a curve holds one key per time, and a time survives reordering. */
internal typealias MotionKeyRef = io.github.psd2live.core.MotionKeyRef
internal typealias MotionKeyEdits = io.github.psd2live.core.MotionKeyEdits

/**
 * What the animation panel and the animation editor share: the clip being edited, the playhead and the
 * key selection. It is view state, not project state, so it is never part of the history; the clip itself
 * lives in the rig edits.
 */
internal class MotionEditorState {
	var clipId: String? by mutableStateOf(null)
	var playhead: Float by mutableStateOf(0f)
	var selection: Set<MotionKeyRef> by mutableStateOf(emptySet())
	/** The curve last clicked; the curve view shows it alone when no key is selected. */
	var focusedCurve: String? by mutableStateOf(null)
	var playing: Boolean by mutableStateOf(false)
	var view: MotionEditorView by mutableStateOf(MotionEditorView.DOPESHEET)
	var snapToFrames: Boolean by mutableStateOf(true)
	var autoKey: Boolean by mutableStateOf(false)
	/** Tracks marked shy stay out of the track list and timeline while this is on. */
	var hideShy: Boolean by mutableStateOf(false)
	/** Only bookmarked parameters' tracks show. */
	var bookmarksOnly: Boolean by mutableStateOf(false)
	/** The preview canvas draws the selected object's path through the open motion. */
	var showTrajectory: Boolean by mutableStateOf(false)
	/** Copied keys, times relative to the earliest. */
	var clipboard: List<Pair<String, MotionKey>> = emptyList()

	companion object {
		private const val PRESET_PREFIX = "preset:"

		/** The editor's id for a generated motion: its override once edited, its generated tracks until then. */
		fun presetClipId(name: String) = PRESET_PREFIX + name

		/** The generated motion [clipId] names, or null for a user clip. */
		fun presetOf(clipId: String): String? = clipId.takeIf { it.startsWith(PRESET_PREFIX) }?.removePrefix(PRESET_PREFIX)
	}
}
