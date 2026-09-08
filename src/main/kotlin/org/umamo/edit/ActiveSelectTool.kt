package org.umamo.edit

/** The brush radius (viewport px) a freshly armed Circle-select tool starts at. */
const val DEFAULT_CIRCLE_RADIUS_PX: Float = 40f

/** The smallest Circle-select brush radius (viewport px); the wheel / numpad clamp to it. */
const val MIN_CIRCLE_RADIUS_PX: Float = 5f

/** The largest Circle-select brush radius (viewport px); the wheel / numpad clamp to it. */
const val MAX_CIRCLE_RADIUS_PX: Float = 500f

/** How much one wheel notch or numpad +/- press changes the Circle-select radius (viewport px). */
const val CIRCLE_RADIUS_STEP_PX: Float = 8f

/**
 * A latched Edit-mode selection tool the gizmo overlay drives - the selection-gesture analog of
 * [MeshOperatorKind].  Null when no tool is armed (a plain click selects and an empty drag rubber-bands a
 * box); a command latches one and the overlay reads it to reinterpret pointer input.  Transient editor
 * state, never snapshotted and never on the change bus, exactly like [EditorSession.activeMeshOperator].
 */
sealed interface ActiveSelectTool {
	/**
	 * The viewport area that armed the tool (an opaque workspace-leaf id, the [ActiveOperator]
	 * currency): only the arming area's overlay drives the gesture, so an armed brush in one split
	 * viewport stays inert everywhere else.
	 */
	val areaId: String

	/**
	 * Blender's B box select: the overlay shows full-viewport crosshair guides and the next drag rubber-bands
	 * a box (ignoring element hit-tests), then the tool disarms (one-shot).
	 *
	 * @property String areaId The arming viewport's area id.
	 */
	data class BoxArmed(override val areaId: String) : ActiveSelectTool

	/**
	 * Blender's C circle select: a brush circle of [radiusPx] follows the cursor; a primary drag paints
	 * selection, a middle or Shift+primary drag erases, the wheel and numpad +/- resize it, and Esc / RMB /
	 * Enter leave the tool.
	 *
	 * @property Float radiusPx The brush radius in viewport pixels.
	 * @property String areaId The arming viewport's area id.
	 */
	data class Circle(val radiusPx: Float, override val areaId: String) : ActiveSelectTool
}