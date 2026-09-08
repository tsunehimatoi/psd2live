package org.umamo.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.umamo.runtime.model.DrawableId

/**
 * The session's transient tool state: the modal operator latches, the armed select tool, the zoom
 * region, the axis constraint, the viewport-gesture flag, the stroke-preview selection, the pie
 * menu, the 2D cursor, the pivot mode, and proportional editing - everything that coordinates the
 * viewport overlays without ever being snapshotted or entering the change bus.  The
 * mutual-exclusion story (a transform operator owns the pointer, so arming anything drops the
 * others) lives in [clearTransient]; [EditorSession] keeps the mode / selection / pose guards and
 * delegates the state itself here, exposing every flow unchanged.
 *
 * セッションの一時的なツール状態（モーダル演算子・選択ツール・ズーム領域・軸拘束・パイメニュー・
 * 2D カーソル・ピボット・プロポーショナル編集）。スナップショットには入らないオーバーレイ調整用。
 * 相互排他の規則は clearTransient に集約する。
 *
 * @param Function notify Emits a transient user notice (the proportional toggles confirm through it).
 */
internal class ToolLatches(private val notify: (String, NoticePlacement) -> Unit) {
	private val mutableActiveMeshOperator = MutableStateFlow<ActiveOperator?>(null)

	/** The modal mesh operator currently running, or null (see [EditorSession.activeMeshOperator]). */
	val activeMeshOperator: StateFlow<ActiveOperator?> = mutableActiveMeshOperator.asStateFlow()

	private val mutableActiveObjectOperator = MutableStateFlow<ActiveOperator?>(null)

	/** The modal object operator currently running, or null (see [EditorSession.activeObjectOperator]). */
	val activeObjectOperator: StateFlow<ActiveOperator?> = mutableActiveObjectOperator.asStateFlow()

	private val mutableActiveUvOperator = MutableStateFlow<ActiveOperator?>(null)

	/** The modal UV operator currently running, or null (see [EditorSession.activeUvOperator]). */
	val activeUvOperator: StateFlow<ActiveOperator?> = mutableActiveUvOperator.asStateFlow()

	private val mutableActiveSelectTool = MutableStateFlow<ActiveSelectTool?>(null)

	/** The armed Box / Circle select tool, or null (see [EditorSession.activeSelectTool]). */
	val activeSelectTool: StateFlow<ActiveSelectTool?> = mutableActiveSelectTool.asStateFlow()

	private val mutableZoomRegionArmedArea = MutableStateFlow<String?>(null)

	/** The area id whose Zoom Region gesture is armed, or null (see [EditorSession.zoomRegionArmedArea]). */
	val zoomRegionArmedArea: StateFlow<String?> = mutableZoomRegionArmedArea.asStateFlow()

	private val mutableAxisConstraint = MutableStateFlow<TransformAxisConstraint?>(null)

	/** The axis the in-flight modal transform is locked to, or null (see [EditorSession.axisConstraint]). */
	val axisConstraint: StateFlow<TransformAxisConstraint?> = mutableAxisConstraint.asStateFlow()

	private val mutableViewportGestureActive = MutableStateFlow(false)

	/** True while a non-armed viewport gesture is in flight (see [EditorSession.viewportGestureActive]). */
	val viewportGestureActive: StateFlow<Boolean> = mutableViewportGestureActive.asStateFlow()

	private val mutablePreviewSelection = MutableStateFlow<Set<DrawableId>?>(null)

	/** The transient circle-stroke preview selection, or null (see [EditorSession.previewSelection]). */
	val previewSelection: StateFlow<Set<DrawableId>?> = mutablePreviewSelection.asStateFlow()

	private val mutableActivePieMenu = MutableStateFlow<PieMenuKind?>(null)

	/** The radial pie menu currently open, or null (see [EditorSession.activePieMenu]). */
	val activePieMenu: StateFlow<PieMenuKind?> = mutableActivePieMenu.asStateFlow()

	private val mutablePendingParameterChoice = MutableStateFlow<ParameterChoiceRequest?>(null)

	/** The keyform edit waiting on an axis, or null (see [EditorSession.pendingParameterChoice]). */
	val pendingParameterChoice: StateFlow<ParameterChoiceRequest?> = mutablePendingParameterChoice.asStateFlow()

	private val mutableCursor2d = MutableStateFlow<Cursor2d?>(null)

	/** The 2D cursor's world position, or null before any placement (see [EditorSession.cursor2d]). */
	val cursor2d: StateFlow<Cursor2d?> = mutableCursor2d.asStateFlow()

	private val mutableUvCursor = MutableStateFlow<UvCursor?>(null)

	/** The UV editor's cursor in atlas coordinates, or null before any placement (see [EditorSession.uvCursor]). */
	val uvCursor: StateFlow<UvCursor?> = mutableUvCursor.asStateFlow()

	private val mutablePivotMode = MutableStateFlow(TransformPivotMode.MedianPoint)

	/** What a modal Scale / Rotate turns the selection about (see [EditorSession.pivotMode]). */
	val pivotMode: StateFlow<TransformPivotMode> = mutablePivotMode.asStateFlow()

	private val mutableGridConfig = MutableStateFlow(GridConfig())

	/** The viewport grid geometry driving the backdrop and grid snap (see [EditorSession.gridConfig]). */
	val gridConfig: StateFlow<GridConfig> = mutableGridConfig.asStateFlow()

	/**
	 * Sets the viewport grid geometry.
	 *
	 * @param GridConfig config The new grid scale and subdivisions.
	 */
	fun setGridConfig(config: GridConfig) {
		mutableGridConfig.value = config
	}

	private val mutableProportionalEdit = MutableStateFlow<ProportionalEditState?>(null)

	/** Proportional editing, non-null while enabled (see [EditorSession.proportionalEdit]). */
	val proportionalEdit: StateFlow<ProportionalEditState?> = mutableProportionalEdit.asStateFlow()

	// The configuration proportional editing re-enables with: the last falloff and radius survive an
	// off/on toggle (the circle-select radius pattern), so O comes back the way it was left.
	private var lastProportionalEdit = ProportionalEditState(ProportionalFalloff.Smooth, DEFAULT_PROPORTIONAL_RADIUS_WORLD)

	// The Circle-select brush radius carried across re-entry (Blender remembers it). Deliberately NOT
	// part of EditorSnapshot - re-arming the tool restores the last size.
	private var lastCircleRadiusPx: Float = DEFAULT_CIRCLE_RADIUS_PX

	/**
	 * True while the active mesh operator was latched with proportional editing suppressed - the
	 * duplicate / rip auto-grabs, which place fresh copies and must never drag bystander vertices.
	 * Reset whenever the operator latches or clears.
	 */
	var activeMeshOperatorSuppressesProportional: Boolean = false
		private set

	/**
	 * Clears the mutually-exclusive transient tool latches - the modal mesh / object / UV operators and
	 * the armed select tool - plus the optional extras the caller names.  Every site that latches a tool,
	 * switches mode, or restores a snapshot funnels through here so the mutual-exclusion story lives in
	 * one place; a caller about to latch one of the four simply overwrites its own slot right after.
	 *
	 * @param Boolean clearZoomRegion True to also disarm the zoom region (the tool-latch sites; a mode
	 *   switch or restore leaves it armed - the gesture is mode-agnostic).
	 * @param Boolean clearAxisConstraint True to also drop the axis lock (the operator begins and
	 *   restore; an axis lock is meaningless without a live operator).
	 * @param Boolean clearViewportGesture True to also end the viewport-gesture flag (mode switches and
	 *   restore, which tear down any in-flight gesture).
	 */
	fun clearTransient(
		clearZoomRegion: Boolean = false,
		clearAxisConstraint: Boolean = false,
		clearViewportGesture: Boolean = false,
	) {
		mutableActiveMeshOperator.value = null
		mutableActiveObjectOperator.value = null
		mutableActiveUvOperator.value = null
		mutableActiveSelectTool.value = null
		if (clearZoomRegion) {
			mutableZoomRegionArmedArea.value = null
		}
		if (clearAxisConstraint) {
			mutableAxisConstraint.value = null
		}
		if (clearViewportGesture) {
			mutableViewportGestureActive.value = false
		}
	}

	/**
	 * Latches a modal mesh operator (the caller has already checked the Edit-mode preconditions).
	 * Drops any armed select tool / zoom region first (mutual exclusion), and publishes the
	 * suppression BEFORE the operator itself: the overlay's latch effect keys on the operator flow and
	 * must read a consistent flag when it fires.
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
	 * @param Boolean suppressProportional True to ignore proportional editing for this gesture.
	 */
	fun latchMeshOperator(kind: MeshOperatorKind, areaId: String, suppressProportional: Boolean) {
		clearTransient(clearZoomRegion = true, clearAxisConstraint = true)
		activeMeshOperatorSuppressesProportional = suppressProportional
		mutableActiveMeshOperator.value = ActiveOperator(kind, areaId)
	}

	/** Clears the active modal mesh operator (confirm or cancel), dropping the axis lock with it. */
	fun clearMeshOperator() {
		mutableActiveMeshOperator.value = null
		mutableAxisConstraint.value = null
		activeMeshOperatorSuppressesProportional = false
	}

	/**
	 * Latches a modal object operator (the caller has already checked the Object-mode preconditions),
	 * dropping any other latched tool first (mutual exclusion).
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating viewport's area id (only its overlay drives the gesture).
	 */
	fun latchObjectOperator(kind: MeshOperatorKind, areaId: String) {
		clearTransient(clearZoomRegion = true, clearAxisConstraint = true)
		mutableActiveObjectOperator.value = ActiveOperator(kind, areaId)
	}

	/** Clears the active modal object operator (confirm or cancel), dropping the axis lock with it. */
	fun clearObjectOperator() {
		mutableActiveObjectOperator.value = null
		mutableAxisConstraint.value = null
	}

	/**
	 * Latches a modal UV operator (the caller has already checked the Edit-mode and UV-validity
	 * preconditions), dropping any other latched tool first (mutual exclusion).
	 *
	 * @param MeshOperatorKind kind The operator to begin (Grab / Scale / Rotate).
	 * @param String areaId The initiating UV editor's area id (only its overlay drives the gesture).
	 */
	fun latchUvOperator(kind: MeshOperatorKind, areaId: String) {
		clearTransient(clearZoomRegion = true, clearAxisConstraint = true)
		mutableActiveUvOperator.value = ActiveOperator(kind, areaId)
	}

	/** Clears the active modal UV operator (confirm or cancel), dropping the axis lock with it. */
	fun clearUvOperator() {
		mutableActiveUvOperator.value = null
		mutableAxisConstraint.value = null
	}

	/**
	 * The one modal transform operator currently running, whichever family latched it, or null.
	 *
	 * Safe to collapse the three into one answer because [clearTransient] runs at every latch site, so at
	 * most one is ever non-null.  A plain getter rather than a combined StateFlow: the callers that want
	 * "is any transform running" read it at an instant (the shell's key ladder) and never observe it, so a
	 * combine would advertise a reactivity nobody consumes.
	 */
	val activeOperator: ActiveOperator?
		get() = activeMeshOperator.value ?: activeObjectOperator.value ?: activeUvOperator.value

	/**
	 * Clears whichever operator family is running, if any.
	 *
	 * Dispatches to that family's own clear rather than blanking all three: only [clearMeshOperator]
	 * resets the proportional-suppression flag, so a three-way blank would quietly change the duplicate
	 * and rip auto-grab behavior.
	 */
	fun clearActiveOperator() {
		when {
			mutableActiveMeshOperator.value != null -> clearMeshOperator()
			mutableActiveObjectOperator.value != null -> clearObjectOperator()
			mutableActiveUvOperator.value != null -> clearUvOperator()
		}
	}

	/**
	 * Arms the Box-select tool (the caller has already checked the mode preconditions).
	 *
	 * @param String areaId The arming viewport's area id (only its overlay drives the drag).
	 */
	fun armBoxSelect(areaId: String) {
		clearTransient(clearZoomRegion = true)
		mutableActiveSelectTool.value = ActiveSelectTool.BoxArmed(areaId)
	}

	/**
	 * Arms the Circle-select tool at the remembered radius (preconditions checked by the caller).
	 *
	 * @param String areaId The arming viewport's area id (only its overlay drives the brush).
	 */
	fun armCircleSelect(areaId: String) {
		clearTransient(clearZoomRegion = true)
		mutableActiveSelectTool.value = ActiveSelectTool.Circle(lastCircleRadiusPx, areaId)
	}

	/**
	 * Sets the Circle-select brush radius (clamped), remembering it for the next arm.  When a Circle
	 * tool is live its radius updates in place so the overlay redraws; otherwise only the remembered
	 * value moves.
	 *
	 * @param Float radiusPx The requested radius in viewport pixels.
	 */
	fun setCircleRadius(radiusPx: Float) {
		val clamped = radiusPx.coerceIn(MIN_CIRCLE_RADIUS_PX, MAX_CIRCLE_RADIUS_PX)
		lastCircleRadiusPx = clamped
		val current = mutableActiveSelectTool.value
		if (current is ActiveSelectTool.Circle) {
			// A resize keeps the brush in its arming area - only re-arming moves the tool.
			mutableActiveSelectTool.value = ActiveSelectTool.Circle(clamped, current.areaId)
		}
	}

	/** Grows the Circle-select radius by one step; a no-op unless a Circle tool is live. */
	fun growCircleRadius() {
		val current = mutableActiveSelectTool.value
		if (current is ActiveSelectTool.Circle) {
			setCircleRadius(current.radiusPx + CIRCLE_RADIUS_STEP_PX)
		}
	}

	/** Shrinks the Circle-select radius by one step; a no-op unless a Circle tool is live. */
	fun shrinkCircleRadius() {
		val current = mutableActiveSelectTool.value
		if (current is ActiveSelectTool.Circle) {
			setCircleRadius(current.radiusPx - CIRCLE_RADIUS_STEP_PX)
		}
	}

	/** Clears any armed Box / Circle select tool. */
	fun clearSelectTool() {
		mutableActiveSelectTool.value = null
	}

	/**
	 * Arms the Zoom Region gesture for [areaId], clearing any latched tool so two overlays never
	 * capture at once.
	 *
	 * @param String areaId The viewport area the gesture will run in.
	 */
	fun armZoomRegion(areaId: String) {
		clearTransient()
		mutableZoomRegionArmedArea.value = areaId
	}

	/** Disarms the Zoom Region gesture. */
	fun disarmZoomRegion() {
		mutableZoomRegionArmedArea.value = null
	}

	/**
	 * Toggles the modal axis constraint (pressing a lock's own key again releases it; pressing the
	 * other axis switches).  A no-op unless a Grab or Scale operator is in flight - Rotate has no axis
	 * to lock and idle keys must not arm a stale constraint.
	 *
	 * @param TransformAxisConstraint axis The axis whose lock to toggle.
	 */
	fun toggleAxisConstraint(axis: TransformAxisConstraint) {
		val operator = mutableActiveMeshOperator.value ?: mutableActiveObjectOperator.value ?: mutableActiveUvOperator.value ?: return
		if (operator.kind == MeshOperatorKind.Rotate) {
			return
		}
		mutableAxisConstraint.value = if (mutableAxisConstraint.value == axis) null else axis
	}

	/**
	 * Publishes whether a non-armed viewport gesture is in flight.
	 *
	 * @param Boolean active True while the overlay's gesture owns the pointer.
	 */
	fun setViewportGestureActive(active: Boolean) {
		mutableViewportGestureActive.value = active
	}

	/**
	 * Publishes the transient circle-stroke preview selection; pass null to clear it.
	 *
	 * @param Set<DrawableId>? drawableIds The drawables currently painted by the stroke, or null.
	 */
	fun setPreviewSelection(drawableIds: Set<DrawableId>?) {
		mutablePreviewSelection.value = drawableIds
	}

	/**
	 * Opens a pie menu over the viewport.
	 *
	 * @param PieMenuKind kind The pie to open.
	 */
	fun openPieMenu(kind: PieMenuKind) {
		mutableActivePieMenu.value = kind
	}

	/** Closes the open pie menu. */
	fun closePieMenu() {
		mutableActivePieMenu.value = null
	}

	/**
	 * Parks a keyform edit until the user picks the axis it writes on.
	 *
	 * @param ParameterChoiceRequest request The parked edit and the axes to choose between.
	 */
	fun openParameterChoice(request: ParameterChoiceRequest) {
		mutablePendingParameterChoice.value = request
	}

	/** Discards the parked keyform edit (an axis was picked, or the prompt was dismissed). */
	fun closeParameterChoice() {
		mutablePendingParameterChoice.value = null
	}

	/**
	 * Places (or moves) the 2D cursor.
	 *
	 * @param Float worldX The cursor's new world-space x.
	 * @param Float worldY The cursor's new world-space y.
	 */
	fun setCursor2d(worldX: Float, worldY: Float) {
		mutableCursor2d.value = Cursor2d(worldX, worldY)
	}

	/**
	 * Places (or moves) the UV editor's cursor.
	 *
	 * @param Float u The cursor's new normalized atlas u coordinate.
	 * @param Float v The cursor's new normalized atlas v coordinate.
	 */
	fun setUvCursor(u: Float, v: Float) {
		mutableUvCursor.value = UvCursor(u, v)
	}

	/**
	 * Selects the transform pivot mode.
	 *
	 * @param TransformPivotMode mode The pivot mode the next transforms anchor on.
	 */
	fun setPivotMode(mode: TransformPivotMode) {
		mutablePivotMode.value = mode
	}

	/**
	 * Toggles proportional editing on or off (Blender's O), restoring the last falloff and radius on
	 * re-enable and confirming either way with a near-cursor notice (an idle toggle has no other
	 * visible effect - the influence circle only shows during a modal transform).
	 */
	fun toggleProportionalEdit() {
		val current = mutableProportionalEdit.value
		if (current != null) {
			lastProportionalEdit = current
			mutableProportionalEdit.value = null
			notify("notice.proportional.off", NoticePlacement.NearCursor)
		} else {
			mutableProportionalEdit.value = lastProportionalEdit
			notify("notice.proportional.on", NoticePlacement.NearCursor)
		}
	}

	/**
	 * Toggles Connected Only for proportional editing (influence measured along mesh edges instead of
	 * straight-line, so the halo never leaps to unconnected geometry), enabling proportional editing
	 * if it was off - and then connected mode turns ON regardless of the remembered flag, since the
	 * command expresses the intent to use it.  Confirms either way with a near-cursor notice.
	 */
	fun toggleProportionalConnected() {
		val current = mutableProportionalEdit.value
		val updated =
			if (current == null) {
				lastProportionalEdit.copy(connectedOnly = true)
			} else {
				current.copy(connectedOnly = !current.connectedOnly)
			}
		lastProportionalEdit = updated
		mutableProportionalEdit.value = updated
		notify(
			if (updated.connectedOnly) "notice.proportional.connected.on" else "notice.proportional.connected.off",
			NoticePlacement.NearCursor,
		)
	}

	/**
	 * Selects the proportional falloff curve, enabling proportional editing if it was off - picking a
	 * falloff from the palette or header expresses the intent to use it, and silently updating a
	 * disabled state would look like the command did nothing.
	 *
	 * @param ProportionalFalloff falloff The falloff curve the influence weights follow.
	 */
	fun setProportionalFalloff(falloff: ProportionalFalloff) {
		val updated = (mutableProportionalEdit.value ?: lastProportionalEdit).copy(falloff = falloff)
		lastProportionalEdit = updated
		mutableProportionalEdit.value = updated
	}

	/**
	 * Sets the proportional influence radius, clamped to the allowed range.  A no-op while proportional
	 * editing is off (the radius only changes from the mid-gesture scroll, which requires it on).
	 *
	 * @param Float radiusWorld The influence radius in world units (canvas px).
	 */
	fun setProportionalRadius(radiusWorld: Float) {
		val current = mutableProportionalEdit.value ?: return
		val updated = current.copy(radiusWorld = radiusWorld.coerceIn(MIN_PROPORTIONAL_RADIUS_WORLD, MAX_PROPORTIONAL_RADIUS_WORLD))
		lastProportionalEdit = updated
		mutableProportionalEdit.value = updated
	}
}