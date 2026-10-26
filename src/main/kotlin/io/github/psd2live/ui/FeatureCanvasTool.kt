package io.github.psd2live.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigBuilder
import io.github.psd2live.i18n.tr
import kotlin.math.hypot

/**
 * The feature tool: the fitted eyes', brows' and mouth's handles on the canvas ([io.github.psd2live.core.fit.FeatureHandles]).
 * Dragging a handle moves the feature's fit and every pose built from it; a gesture commits once on release as one
 * change of the rig recipe's moves, so it records one history step and replays like any setting. Alt-click returns a
 * handle to where the art put it.
 */
internal class FeatureCanvasTool(private val editor: CanvasEditor) {
	/** A handle by its feature and name. */
	data class Key(val feature: String, val handle: String)

	var hover by mutableStateOf<Key?>(null)
		private set

	private class Drag(val key: Key, val grabX: Float, val grabY: Float, val baseX: Float, val baseY: Float)
	private var drag: Drag? = null

	/** How far the held handle has moved, in canvas pixels. */
	var offset by mutableStateOf(Offset.Zero)
		private set

	val dragging: Boolean get() = drag != null

	/** The feature last handled: what the options bar puts back. */
	var last by mutableStateOf<String?>(null)
		private set
	val active: Key? get() = drag?.key

	// ── The handles ──────────────────────────────────────────────────────────────

	@Volatile private var key: Any? = null
	@Volatile private var generation = 0

	/** The handles where the fit puts them; read off the art in the background, so the canvas never waits for it. */
	var handles by mutableStateOf<List<RigBuilder.FeatureHandle>>(emptyList())
		private set

	fun refresh() {
		val model = editor.state.previewModel ?: return
		val k = listOf(model.analysis, model.config)
		if (k == key) return
		key = k
		val run = ++generation
		kotlin.concurrent.thread(isDaemon = true, name = "feature-handles") {
			val found = runCatching { RigBuilder.featureHandles(model.analysis, model.config) }.getOrDefault(emptyList())
			if (run == generation) handles = found
		}
	}

	fun screen(handle: RigBuilder.FeatureHandle, viewport: CanvasViewport): Offset {
		val held = drag?.key == Key(handle.feature, handle.handle)
		val x = handle.x + if (held) offset.x else 0f; val y = handle.y + if (held) offset.y else 0f
		return Offset(viewport.x(x).toFloat(), (viewport.offsetY + y * viewport.scale).toFloat())
	}

	private fun at(pos: Offset, viewport: CanvasViewport): RigBuilder.FeatureHandle? =
		handles.minByOrNull { val s = screen(it, viewport); hypot(s.x - pos.x, s.y - pos.y) }
			?.takeIf { val s = screen(it, viewport); hypot(s.x - pos.x, s.y - pos.y) <= GRAB }

	// ── The gesture ──────────────────────────────────────────────────────────────

	fun hover(pos: Offset, viewport: CanvasViewport) {
		// A knob stepped by the keys has no drag to end it; going back to the canvas ends its step.
		endKnob()
		refresh()
		hover = at(pos, viewport)?.let { Key(it.feature, it.handle) }
	}

	fun clearHover() { hover = null }

	fun cursorHand(): Boolean = hover != null || dragging

	/** A press on a handle starts dragging it; with Alt it returns the handle to the fit. False off every handle. */
	fun press(pos: Offset, viewport: CanvasViewport, alt: Boolean): Boolean {
		endKnob()
		refresh()
		val handle = at(pos, viewport) ?: return false
		val key = Key(handle.feature, handle.handle)
		last = key.feature
		if (alt) { editor.viewModel.moveFeatureHandle(key.feature, key.handle, null); return true }
		val base = editor.state.recipe.moves(key.feature)[key.handle]
		drag = Drag(key, viewport.canvasX(pos.x), viewport.canvasY(pos.y), base?.dx ?: 0f, base?.dy ?: 0f)
		offset = Offset.Zero
		return true
	}

	fun move(pos: Offset, viewport: CanvasViewport) {
		val d = drag ?: return
		offset = Offset(viewport.canvasX(pos.x) - d.grabX, viewport.canvasY(pos.y) - d.grabY)
	}

	fun release() {
		val d = drag ?: return
		drag = null
		if (offset.getDistance() < 0.05f) return
		editor.viewModel.moveFeatureHandle(d.key.feature, d.key.handle, (d.baseX + offset.x) to (d.baseY + offset.y))
	}

	private var knobGesture = false

	/** A knob on the options bar starts moving: its samples gather into one history step until [endKnob]. */
	fun beginKnob() {
		if (knobGesture) return
		knobGesture = true
		editor.viewModel.beginEditorGesture()
	}

	fun endKnob() {
		if (!knobGesture) return
		knobGesture = false
		editor.viewModel.endEditorGesture()
	}

	fun cancel(): Boolean {
		if (drag == null) return false
		drag = null; offset = Offset.Zero
		return true
	}

	fun hint(): String = if (handles.isEmpty()) tr("editor.tool.feature.none") else tr("editor.tool.feature.hint")

	private companion object {
		const val GRAB = 9f
	}
}
