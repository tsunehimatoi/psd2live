package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspaceDocumentPort
import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.core.*
import kotlinx.serialization.json.*

/** One immutable drag origin, preview-only samples and one final document submission. */
internal class TimelineGestureController(
    private val state: () -> PSD2LiveState,
    private val documentPort: () -> WorkspaceDocumentPort?,
    private val stateToken: () -> String?,
    private val motionEditor: MotionEditorState,
    private val editingMotionClip: () -> MotionClip?,
    private val previewMotionClip: (String, (MotionClip) -> MotionClip) -> Unit,
    private val motionParameterRanges: () -> Map<String, ClosedFloatingPointRange<Float>>,
    private val motionKeyLimits: () -> Map<String, ClosedFloatingPointRange<Float>>,
    private val restoreCommitted: (List<MotionClip>) -> Unit,
    private val timelineEdits: (String, JsonObject, MotionClip) -> List<WorkspaceDocumentOperation>,
    private val saveWorkspaceEdit: (suspend () -> Unit) -> Unit,
    private val reportConflict: (String) -> Unit,
) {
    private var dragOrigin: MotionClip? = null
    private var dragSelection: Set<MotionKeyRef> = emptySet()
    private var dragState: String? = null
    private var dragClips: List<MotionClip> = emptyList()
    private var dragMode = "move_keys"
    private var dragRequest: JsonObject? = null
    private var dragGeneration = 0L

    private fun currentOrigin(): MotionClip? {
        if (state().projectOpenGeneration != dragGeneration) clear()
        return dragOrigin
    }

    fun clear() {
        dragOrigin = null
        dragSelection = emptySet()
        dragState = null
        dragClips = emptyList()
        dragRequest = null
    }

    fun cancel() {
        val before = dragClips
        val selection = dragSelection
        val active = currentOrigin() != null
        clear()
        if (active) {
            restoreCommitted(before)
            val clip = editingMotionClip()
            motionEditor.selection = selection.filterTo(linkedSetOf()) { ref ->
                clip?.curve(ref.parameterId)?.keys?.any(ref::matches) == true
            }
        }
    }

	private fun motionKeyJson(key: MotionKey) = kotlinx.serialization.json.buildJsonObject {
		put("time", key.time); put("value", key.value); put("interpolation", key.interpolation.name)
		put("out", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(key.outHandle.x), kotlinx.serialization.json.JsonPrimitive(key.outHandle.y))))
		put("in", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(key.inHandle.x), kotlinx.serialization.json.JsonPrimitive(key.inHandle.y))))
	}
	private fun motionSelectionJson(selection: Set<MotionKeyRef>) = kotlinx.serialization.json.buildJsonArray {
		selection.forEach { ref -> add(kotlinx.serialization.json.buildJsonObject { put("parameter", ref.parameterId); put("time", ref.time) }) }
	}
	/** Starts a key or handle drag: samples apply to this clip, and the drag records one history node. */
	fun beginMotionKeyDrag() {
		if (dragOrigin != null) return
		dragGeneration = state().projectOpenGeneration
		dragOrigin = editingMotionClip() ?: return
		dragSelection = motionEditor.selection
		dragState = stateToken()
		dragClips = MotionCatalog.entries(state().rigEdits)
		dragRequest = null
	}

	fun dragMotionKeys(dt: Float, dv: Float = 0f, normalized: Boolean = false) {
		val origin = currentOrigin() ?: return
		val (next, moved) = MotionKeyEdits.move(origin, dragSelection, dt, dv, motionParameterRanges(), normalized, motionKeyLimits())
		previewMotionClip(origin.id) { next }
		dragMode = "move_keys"
		dragRequest = kotlinx.serialization.json.buildJsonObject { put("selection", motionSelectionJson(dragSelection)); put("dt", dt); put("dv", dv); put("normalized", normalized) }
		motionEditor.selection = moved
	}

	/** Sets one Bezier handle of [ref], relative to the drag's origin clip. */
	internal fun dragMotionHandle(ref: MotionKeyRef, outgoing: Boolean, handle: MotionHandle) {
		val origin = currentOrigin() ?: return
		val next = MotionKeyEdits.mapKeys(origin, setOf(ref)) { _, key ->
			if (outgoing) key.copy(outHandle = handle.clamped()) else key.copy(inHandle = handle.clamped())
		}
		previewMotionClip(origin.id) { next }
		val key = next.curve(ref.parameterId)?.keys?.firstOrNull(ref::matches) ?: return
		dragMode = "replace_keys"
		dragRequest = kotlinx.serialization.json.buildJsonObject { putJsonArray("keys") { add(kotlinx.serialization.json.buildJsonObject { put("parameter", ref.parameterId); put("from_time", ref.time); put("key", motionKeyJson(key)) }) } }
	}

	fun endMotionKeyDrag() {
		val origin = currentOrigin() ?: return
		val request = dragRequest
		val mode = dragMode
		val before = dragClips
		val expected = dragState
        val generation = dragGeneration
		clear()
		// The drag only previewed; the committed clips come back whatever landed meanwhile.
		restoreCommitted(before)
		// Whether the preview still shows the drag does not matter: a commit shown meanwhile may have replaced it.
		if (expected == null || request == null) return
		val edits = timelineEdits(mode, request, origin)
		fun yieldDrag() = reportConflict(origin.name)
		// Another author's commit that left the clip as the drag found it does not stop the drag, as with any field
		// draft; one that changed the clip wins and the drag is dropped with a warning. The state read here may
		// predate a commit already shown; its conflict names the state to follow.
		saveWorkspaceEdit {
            if (state().projectOpenGeneration != generation) return@saveWorkspaceEdit
			val port: io.github.psd2live.application.WorkspaceDocumentPort = requireNotNull(documentPort()) { "Project workspace unavailable" }
			var state = stateToken() ?: return@saveWorkspaceEdit
			repeat(MAX_REBASES) {
                if (this.state().projectOpenGeneration != generation) return@saveWorkspaceEdit
				if (state != expected && editingMotionClip() != origin) { yieldDrag(); return@saveWorkspaceEdit }
				try {
					port.applyDocumentEdits(state, "Edit timeline", edits, io.github.psd2live.project.MutationAuthor.USER)
					return@saveWorkspaceEdit
				} catch (conflict: io.github.psd2live.application.WorkspaceConflict) { state = conflict.actualState }
			}
			yieldDrag()
		}
	}

    private companion object { const val MAX_REBASES = 4 }
}
