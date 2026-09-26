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
internal data class MotionKeyRef(val parameterId: String, val time: Float) {
	fun matches(key: MotionKey) = abs(key.time - time) < MotionClips.TIME_EPSILON
}

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
	/** Copied keys, times relative to the earliest. */
	var clipboard: List<Pair<String, MotionKey>> = emptyList()

	/** The clip a key drag started from; every drag sample is applied to it, not to the previous sample. */
	var dragOrigin: MotionClip? = null
	var dragSelection: Set<MotionKeyRef> = emptySet()
}

internal object MotionKeyEdits {
	fun keysOf(clip: MotionClip, selection: Set<MotionKeyRef>): List<Pair<String, MotionKey>> =
		clip.curves.flatMap { curve ->
			curve.keys.filter { key -> selection.any { it.parameterId == curve.parameterId && it.matches(key) } }
				.map { curve.parameterId to it }
		}

	fun setKey(clip: MotionClip, parameterId: String, key: MotionKey): MotionClip {
		val curve = clip.curve(parameterId)
		val keys = MotionClips.normalized((curve?.keys.orEmpty()) + key)
		val next = MotionCurve(parameterId, keys)
		return clip.copy(curves = if (curve == null) clip.curves + next else clip.curves.map { if (it.parameterId == parameterId) next else it })
	}

	/** Every selected key replaced by [transform]; a curve left without keys is removed. */
	fun mapKeys(clip: MotionClip, selection: Set<MotionKeyRef>, transform: (String, MotionKey) -> MotionKey?): MotionClip =
		clip.copy(curves = clip.curves.mapNotNull { curve ->
			val picked = selection.filter { it.parameterId == curve.parameterId }
			if (picked.isEmpty()) return@mapNotNull curve
			val kept = curve.keys.filter { key -> picked.none { it.matches(key) } }
			val changed = curve.keys.filter { key -> picked.any { it.matches(key) } }.mapNotNull { transform(curve.parameterId, it) }
			// Moved keys land over the ones they reach.
			val keys = MotionClips.normalized(kept + changed)
			if (keys.isEmpty()) null else MotionCurve(curve.parameterId, keys)
		})

	/**
	 * The selected keys shifted by [dt] seconds (clamped to the clip) and [dv] units, and the selection that
	 * follows them. With [normalized], [dv] is a fraction of each curve's parameter range, so keys of
	 * parameters with different ranges move together in the curve view.
	 */
	fun move(
		clip: MotionClip,
		selection: Set<MotionKeyRef>,
		dt: Float,
		dv: Float,
		ranges: Map<String, ClosedFloatingPointRange<Float>> = emptyMap(),
		normalized: Boolean = false,
	): Pair<MotionClip, Set<MotionKeyRef>> {
		val picked = keysOf(clip, selection)
		if (picked.isEmpty()) return clip to selection
		val minTime = picked.minOf { it.second.time }
		val maxTime = picked.maxOf { it.second.time }
		val shift = dt.coerceIn(-minTime, clip.duration - maxTime)
		val moved = mutableSetOf<MotionKeyRef>()
		val next = mapKeys(clip, selection) { parameterId, key ->
			val range = ranges[parameterId]
			val delta = if (normalized && range != null) dv * (range.endInclusive - range.start) else dv
			val value = (key.value + delta).let { if (range != null) it.coerceIn(range) else it }
			key.copy(time = key.time + shift, value = value).also { moved += MotionKeyRef(parameterId, it.time) }
		}
		return next to moved
	}

	fun delete(clip: MotionClip, selection: Set<MotionKeyRef>): MotionClip = mapKeys(clip, selection) { _, _ -> null }

	/** [keys] (relative times) pasted at [at], each onto its own curve. */
	fun paste(clip: MotionClip, keys: List<Pair<String, MotionKey>>, at: Float): Pair<MotionClip, Set<MotionKeyRef>> {
		var next = clip
		val pasted = mutableSetOf<MotionKeyRef>()
		for ((parameterId, key) in keys) {
			val time = (at + key.time).coerceIn(0f, clip.duration)
			next = setKey(next, parameterId, key.copy(time = time))
			pasted += MotionKeyRef(parameterId, time)
		}
		return next to pasted
	}

	/** Relative copies of the selected keys, for [paste]. */
	fun copy(clip: MotionClip, selection: Set<MotionKeyRef>): List<Pair<String, MotionKey>> {
		val picked = keysOf(clip, selection)
		val start = picked.minOfOrNull { it.second.time } ?: return emptyList()
		return picked.map { (id, key) -> id to key.copy(time = key.time - start) }
	}

	/** A shorter clip drops the keys past its end, holding each curve's value there. */
	fun withDuration(clip: MotionClip, duration: Float): MotionClip {
		if (duration >= clip.duration) return clip.copy(duration = duration)
		return clip.copy(duration = duration, curves = clip.curves.map { curve ->
			val inside = curve.keys.filter { it.time <= duration + MotionClips.TIME_EPSILON }
			val keys = if (inside.size == curve.keys.size) inside
				else MotionClips.normalized(inside + MotionKey(duration, MotionClips.sample(curve, duration)))
			MotionCurve(curve.parameterId, keys)
		})
	}

	fun snap(time: Float, fps: Float): Float = (Math.round(time * fps) / fps)
}
