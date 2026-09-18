package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.SelectionStyle
import io.github.psd2live.ui.VERTEX_TOOLS
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import io.github.psd2live.ui.theme.frostedGlass
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasViewport
import io.github.psd2live.ui.ComponentPalette
import io.github.psd2live.ui.CubismViewport
import io.github.psd2live.ui.RigCanvasSupport
import io.github.psd2live.ui.SkiaRigPainter
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconMouse
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import org.umamo.render.eval.DeformedGeometry
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/** How long a paused preview keeps rendering after a pointer change, so the eased follow settles. */
private const val PAUSED_TRACKING_SETTLE_NANOS = 750_000_000L

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CanvasViewportComposable(
	mode: CanvasMode,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
	onLayerClicked: ((String?) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current.density

	var viewSize by remember { mutableStateOf(IntSize(600, 600)) }
	var zoom by remember(state.projectOpenGeneration) { mutableStateOf(state.canvasZoom.toDouble()) }
	var panX by remember(state.projectOpenGeneration) { mutableStateOf(state.canvasPanX.toDouble()) }
	var panY by remember(state.projectOpenGeneration) { mutableStateOf(state.canvasPanY.toDouble()) }
	var isDragging by remember { mutableStateOf(false) }
	var lastDragPos by remember { mutableStateOf(Offset.Zero) }
	var fps by remember { mutableStateOf(0f) }
	val fpsCounter = remember { ActualFpsCounter() }
	// A paused preview still follows the pointer, so the frame pump must stay awake for a moment
	// after every pointer change instead of rendering the single frame a pause asks for. A plain
	// AtomicLong rather than snapshot state: only the pump coroutine reads it.
	val lastPointerActivityNanos = remember { AtomicLong(0L) }
	val pointerActivity = remember { Channel<Unit>(Channel.CONFLATED) }

	val editor = viewModel.canvasEditor

	// A capture in the settings panel swallows key events, including the Space release that
	// clears the pan latch, so drop it proactively — a stuck pan would look like a hung canvas.
	LaunchedEffect(state.keyCapture, state.showSettingsDialog) {
		if (state.keyCapture != null || state.showSettingsDialog) editor.space = false
	}

	// Rebinding happens in a modal that takes focus off the canvas. Pull it back on close so the
	// key the user just recorded works straight away instead of needing a click on the canvas.
	LaunchedEffect(state.focusCanvasRequest) {
		if (state.focusCanvasRequest > 0) focusRequester.requestFocus()
	}
    editor.state = state
    LaunchedEffect(viewModel, mode) { viewModel.canvasPathRequests.collect { if(mode == CanvasMode.EDIT) editor.activateTool(CanvasTool.PATH_DEFORM) } }
    LaunchedEffect(state.selectedLayerId, state.selectedDeformerId) {
        if (!editor.inGesture && !editor.busy) {
            editor.resetSelection()
            if(state.selectedLayerId !in editor.objects) editor.objects=setOfNotNull(state.selectedLayerId)
            // Vertex mode is only meaningful for the tools that edit points. Forcing it for the object
            // tools left `objects` populated while objectMode said otherwise, and the transform bounding
            // box — which is computed from objectMode — then framed a different set than the one a drag
            // actually moved.
            if(state.selectedDeformerId!=null && editor.tool in VERTEX_TOOLS) { editor.objects=emptySet();editor.objectMode=false }
        }
    }
    LaunchedEffect(state.historySnapshot?.headNodeId, state.parameterValues) {
        if (!editor.busy && editor.inGesture) editor.cancel()
        if (!editor.busy && state.previewModel != null) editor.target()?.let { t -> editor.vertices=editor.vertices.filter { it in 0 until t.count }.toSet() }
    }
    val previewModel = state.previewModel?.let { source ->
        if (mode == CanvasMode.EDIT && editor.preview != null) source.copy(rig = source.rig.copy(puppet = editor.preview!!)) else source
    }
	val editingPainter = remember(previewModel?.atlas) { previewModel?.atlas?.let(::SkiaRigPainter) }
	DisposableEffect(editingPainter) { onDispose { editingPainter?.close() } }
	val sdkFrame by viewModel.sdkFrame.collectAsState()
	val sdkBitmap = remember(sdkFrame?.image) { sdkFrame?.image?.toComposeImageBitmap() }
	val checkerboardBrush = remember(colors.checkerLight, colors.checkerDark) {
		createCheckerboardBrush(colors.checkerLight, colors.checkerDark)
	}
	// One pose for the whole tab: artwork, diagnostic geometry and hit-testing. A paused preview
	// is still live here, because the follow keeps moving the pose after the motion stops.
	val informationPose = informationPreviewPose(
		state.parameterValues,
		state.previewParameterValues,
		sdkFrame,
		state.animationEnabled || (mode == CanvasMode.PREVIEW && state.mouseTrackingEnabled),
	)
	val currentZoom by rememberUpdatedState(zoom)
	val currentPanX by rememberUpdatedState(panX)
	val currentPanY by rememberUpdatedState(panY)

	LaunchedEffect(viewModel) {
		viewModel.sdkFrame.collect { frame ->
			if (frame == null) {
				fpsCounter.reset()
				fps = 0f
			} else {
				val measured = fpsCounter.record(System.nanoTime())
				if (measured != null) fps = measured
			}
		}
	}

	/** Marks a pointer change a paused preview has to render (see the frame pump). */
	fun notePointerActivity() {
		lastPointerActivityNanos.set(System.nanoTime())
		pointerActivity.trySend(Unit)
	}

	fun resetCamera() {
		zoom = 1.0
		panX = 0.0
		panY = 0.0
        viewModel.setCanvasView(zoom.toFloat(), panX.toFloat(), panY.toFloat())
	}

    fun frameSelection() {
        val model=previewModel ?: return
        val viewport=computeEditorViewport(model,viewSize,zoom,panX,panY)
        val targets=if(editor.objectMode)editor.objects.mapNotNull { editor.target(editor.model,it,null) } else listOfNotNull(editor.target())
        val points=targets.flatMap { t -> editor.screen(t.geometry.points,t,viewport).filterIndexed { i,_ -> editor.objectMode || editor.vertices.isEmpty() || i in editor.vertices } }
        if(points.isEmpty()) { resetCamera();return }
        val minX=points.minOf { it.x };val maxX=points.maxOf { it.x };val minY=points.minOf { it.y };val maxY=points.maxOf { it.y }
        val factor=minOf((viewSize.width-120*density).coerceAtLeast(50f)/(maxX-minX).coerceAtLeast(30f),(viewSize.height-140*density).coerceAtLeast(50f)/(maxY-minY).coerceAtLeast(30f))
        val next=(zoom*factor).coerceIn(0.05,64.0)
        val actual=next/zoom
        panX=-((minX+maxX)/2.0-viewSize.width/2.0-panX)*actual
        panY=-((minY+maxY)/2.0-viewSize.height/2.0-panY)*actual
        zoom=next;viewModel.setCanvasView(zoom.toFloat(),panX.toFloat(),panY.toFloat())
    }


    fun computeViewport(model: RigPreviewModel, width: Int, height: Int, margin: Int = 34): CanvasViewport =
        computeEditorViewport(model,IntSize(width,height),zoom,panX,panY,margin)


	fun zoomAt(mouseX: Float, mouseY: Float, wheelDelta: Float) {
		val model = previewModel ?: return
		val oldViewport = computeViewport(model, viewSize.width, viewSize.height)
		val canvasX = (mouseX - oldViewport.offsetX) / oldViewport.scale
		val canvasY = (mouseY - oldViewport.offsetY) / oldViewport.scale
		val nextZoom = (zoom * 1.15.pow(-wheelDelta.toDouble())).coerceIn(0.05, 64.0)
		if (nextZoom == zoom) return
		zoom = nextZoom
		val centered = computeViewport(model, viewSize.width, viewSize.height)
		panX += mouseX - (centered.offsetX + canvasX * centered.scale)
		panY += mouseY - (centered.offsetY + canvasY * centered.scale)
        viewModel.setCanvasView(zoom.toFloat(), panX.toFloat(), panY.toFloat())
	}

	// Vsync-driven frame pump. Cubism conflates requests while busy, so the newest
	// parameters are rendered next without building latency in a callback queue.
	LaunchedEffect(mode, previewModel, state.animationEnabled, state.mouseTrackingEnabled, viewSize, currentZoom, currentPanX, currentPanY, state.parameterValues) {
		if (previewModel != null && viewSize.width > 0 && viewSize.height > 0) {
			if (mode == CanvasMode.PREVIEW) {
				fun requestFrame(deltaTime: Float, frameNanos: Long) {
					val sdkVp = computeCubismViewport(
						previewModel,
						viewSize.width,
						viewSize.height,
						currentZoom,
						currentPanX,
						currentPanY,
					)
					viewModel.requestSdkFrame(
						viewSize.width,
						viewSize.height,
						sdkVp.scale,
						sdkVp.offsetX,
						sdkVp.offsetY,
						deltaTime,
						frameNanos,
					)
				}
				if (state.animationEnabled) {
					var previousFrameNanos = 0L
					while (isActive) {
						val frameNanos = withFrameNanos { it }
						val deltaTime = if (previousFrameNanos == 0L) {
							1f / 60f
						} else {
							((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
						}
						previousFrameNanos = frameNanos
						requestFrame(deltaTime, frameNanos)
					}
				} else {
					requestFrame(0f, System.nanoTime())
					// Pausing stops the motion, not the follow: Cubism applies the pointer's look
					// offsets without advancing the clock, and the UI eases them in over ~0.5s. So
					// render until that settle window closes instead of the single frame a pause
					// used to ask for, which froze the pose mid-turn. Sleeping on the channel keeps
					// an untouched preview from waking up every vsync.
					while (isActive && state.mouseTrackingEnabled) {
						pointerActivity.receive()
						while (isActive &&
							System.nanoTime() - lastPointerActivityNanos.get() <= PAUSED_TRACKING_SETTLE_NANOS
						) {
							requestFrame(0f, System.nanoTime())
							withFrameNanos { it }
						}
					}
				}
			}
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.clipToBounds()
			.background(colors.windowBackground)
			.focusRequester(focusRequester)
			.onFocusChanged { if(!it.hasFocus) { editor.space=false; if(editor.inGesture)editor.cancel(); if(editor.adjustingBrush)editor.endBrushAdjust(cancel = false) } }
			.focusable()
			.onSizeChanged { viewSize = it }
			.onKeyEvent { event ->
				// A capture in the settings panel owns the keyboard. The root handler already
				// swallowed the event, but stay inert anyway so nothing reaches the canvas
				// mid-recording.
				if (state.keyCapture != null) return@onKeyEvent false
				// The pan latch and its release must outlive every gate below: it is a press /
				// release pair rather than a discrete command, and it stays live while an edit
				// commits. Space is deliberately not a bindable action.
				if (mode == CanvasMode.EDIT && previewModel != null && event.key == Key.Spacebar) {
					editor.space = event.type == KeyEventType.KeyDown
					return@onKeyEvent true
				}
				val action = state.keymap.match(event, ShortcutScope.CANVAS)
					?: return@onKeyEvent false
				// Camera commands sit outside the mode and busy gates, as they always have: a
				// long commit must not take the view controls away.
				when (action) {
					ShortcutAction.FRAME_VIEW -> {
						if (mode == CanvasMode.EDIT) frameSelection() else resetCamera()
						return@onKeyEvent true
					}
					ShortcutAction.RESET_CAMERA -> {
						resetCamera()
						return@onKeyEvent true
					}
					else -> Unit
				}
				if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
				if (mode != CanvasMode.EDIT || previewModel == null) return@onKeyEvent false
				// Consumes rather than falls through while a commit is running.
				if (editor.busy || state.canvasEditBusy) return@onKeyEvent true
				return@onKeyEvent when (action) {
					ShortcutAction.SELECT_ALL -> { editor.selectAll(); true }
					ShortcutAction.INVERT_SELECTION -> { editor.selectAll(true); true }
					ShortcutAction.TOOL_SELECT -> { editor.activateTool(CanvasTool.SELECT); true }
					ShortcutAction.TOOL_TRANSFORM -> { editor.activateTool(CanvasTool.TRANSFORM); true }
					ShortcutAction.TOOL_MESH -> {
						// Toggles back to SELECT when already in mesh mode.
						editor.activateTool(
							if (editor.tool == CanvasTool.MESH) CanvasTool.SELECT else CanvasTool.MESH,
						)
						true
					}
					ShortcutAction.TOOL_WARP -> { editor.activateTool(CanvasTool.WARP); true }
					ShortcutAction.TOOL_BRUSH -> { editor.activateTool(CanvasTool.BRUSH); true }
					ShortcutAction.TOOL_SMOOTH -> { editor.activateTool(CanvasTool.SMOOTH); true }
					ShortcutAction.TOOL_INFLATE -> { editor.activateTool(CanvasTool.INFLATE); true }
					ShortcutAction.TOOL_PATH_DEFORM -> { editor.activateTool(CanvasTool.PATH_DEFORM); true }
					ShortcutAction.TOOL_HAND -> { editor.activateTool(CanvasTool.HAND); true }
					ShortcutAction.SELECTION_STYLE_BOX -> { editor.selectionStyle = SelectionStyle.BOX; true }
					ShortcutAction.SELECTION_STYLE_LASSO -> { editor.selectionStyle = SelectionStyle.LASSO; true }
					ShortcutAction.SELECT_LINKED -> { editor.selectLinked(); true }
					ShortcutAction.CANCEL -> { editor.cancel(); true }
					ShortcutAction.FINISH_PATH -> { editor.finishPath(); true }
					ShortcutAction.DELETE_SELECTION -> {
						if (editor.tool == CanvasTool.PATH_DEFORM) editor.deletePathPoint()
						else if (editor.tool == CanvasTool.MESH) editor.topology("delete")
						true
					}
					ShortcutAction.BRUSH_RADIUS_DOWN -> {
						editor.radius = (editor.radius / 1.2f).coerceAtLeast(4f)
						true
					}
					ShortcutAction.BRUSH_RADIUS_UP -> {
						editor.radius = (editor.radius * 1.2f).coerceAtMost(500f)
						true
					}
					// Never let hardness reach 1.0: brushWeight divides by (1 - hardness).
					ShortcutAction.BRUSH_HARDNESS_DOWN -> {
						editor.hardness = (editor.hardness - 0.05f).coerceIn(0f, 0.95f)
						true
					}
					ShortcutAction.BRUSH_HARDNESS_UP -> {
						editor.hardness = (editor.hardness + 0.05f).coerceIn(0f, 0.95f)
						true
					}
					ShortcutAction.BRUSH_ROTATE_LEFT -> {
						val step = if (event.isShiftPressed) 45f else 15f
						editor.brushAngle = (editor.brushAngle - step).mod(360f)
						true
					}
					ShortcutAction.BRUSH_ROTATE_RIGHT -> {
						val step = if (event.isShiftPressed) 45f else 15f
						editor.brushAngle = (editor.brushAngle + step).mod(360f)
						true
					}
					ShortcutAction.BRUSH_SHAPE_CYCLE -> {
						editor.cycleBrushShape()
						true
					}
					ShortcutAction.AXIS_CONSTRAIN_X -> { editor.axis = if (editor.axis == "x") null else "x"; true }
					ShortcutAction.AXIS_CONSTRAIN_Y -> { editor.axis = if (editor.axis == "y") null else "y"; true }
					else -> false
				}
			}
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(when {
                isDragging -> Cursor.MOVE_CURSOR
                mode == CanvasMode.EDIT -> editor.activeCursor()
                else -> Cursor.DEFAULT_CURSOR
            })))
			.onPointerEvent(PointerEventType.Press) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if(change.isConsumed) return@onPointerEvent
                if(mode == CanvasMode.EDIT && previewModel != null) {
                    val p=change.position/density
                    if(p.y<32f || p.y>viewSize.height/density-25f || (p.x<42f && p.y in 44f..442f)) return@onPointerEvent
                }
                focusRequester.requestFocus()
                // Photoshop parity: Alt + right-drag retunes the brush — right/left grows/shrinks the radius,
                // down/up hardens/softens. The Alt state is latched by the editor, so releasing Alt mid-drag
                // neither aborts the gesture nor changes what it is doing.
                if (mode == CanvasMode.EDIT && previewModel != null && event.button == PointerButton.Secondary &&
                    event.keyboardModifiers.isAltPressed && !isDragging && !editor.inGesture && editor.beginBrushAdjust(change.position, event.keyboardModifiers.isShiftPressed)
                ) {
                    change.consume(); return@onPointerEvent
                }
                if (mode == CanvasMode.EDIT && previewModel != null && event.button == PointerButton.Primary) {
                    if (editor.press(change.position,computeViewport(previewModel,viewSize.width,viewSize.height),event.keyboardModifiers.isShiftPressed,event.keyboardModifiers.isAltPressed,event.keyboardModifiers.isCtrlPressed)) {
                        change.consume(); return@onPointerEvent
                    }
                }
				if (event.button == PointerButton.Primary || event.button == PointerButton.Tertiary) {
					isDragging = true
					lastDragPos = change.position
					// Pressing deliberately leaves the look alone. It used to hand the pointer back
					// to the idle pose, which snapped the character's head to neutral on every click
					// and on the first frame of every pan; the follow already freezes on its own
					// while a drag is in flight, and Exit is what returns the pose to rest.
				}
			}
			.onPointerEvent(PointerEventType.Release) { event ->
				val change = event.changes.firstOrNull()
                // Must be tested before the consumed check below, and the button test is load-bearing: a middle
                // button release during an adjustment has to fall through to the pan-end block, otherwise
                // isDragging stays true and the canvas pans forever.
                if (editor.adjustingBrush && event.button == PointerButton.Secondary) {
                    editor.endBrushAdjust(cancel = false)
                    return@onPointerEvent
                }
                if(change?.isConsumed==true && !editor.inGesture && !isDragging) return@onPointerEvent
                if(mode == CanvasMode.EDIT && previewModel != null && event.button == PointerButton.Primary && !isDragging) {
                    editor.release()
                    editor.finishSelection(computeViewport(previewModel,viewSize.width,viewSize.height))
                    return@onPointerEvent
                }
                if (isDragging) {
					isDragging = false
					if (mode == CanvasMode.PREVIEW && event.button == PointerButton.Primary && change != null && (change.position - lastDragPos).getDistance() < 6f) {
						if (state.clickToSelectLayer && previewModel != null && onLayerClicked != null) {
							val viewport = computeViewport(previewModel, viewSize.width, viewSize.height)
							// Hit the pose that is on screen, not the last animated one: a paused
							// preview still follows the pointer, so the two drift apart.
							val geometry = RigCanvasSupport.evaluate(previewModel, informationPose)
							val drawableBounds = RigCanvasSupport.boundsByDrawable(geometry)
							val hit = RigCanvasSupport.hitLayer(
								model = previewModel,
								drawableBounds = drawableBounds,
								canvasX = viewport.canvasX(change.position.x.toInt()),
								canvasY = viewport.canvasY(change.position.y.toInt()),
								visibleLayerIds = state.effectiveVisibleLayerIds,
								currentSelectedLayerId = state.selectedLayerId,
								geometry = geometry,
							)
							onLayerClicked(hit)
						}
					}
				}
			}
			.onPointerEvent(PointerEventType.Exit) {
                // Releasing the right button outside the window may never route a Release back here.
                if (editor.adjustingBrush) editor.endBrushAdjust(cancel = false)
				editor.clearHover()
                if (mode == CanvasMode.PREVIEW) {
					viewModel.clearPointer()
					// One last frame puts the pose back to neutral now that the look is gone.
					notePointerActivity()
				}
			}
			.onPointerEvent(PointerEventType.Move) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (mode == CanvasMode.EDIT && previewModel != null && !isDragging) {
                    // The brush gesture takes over the pointer: skipping move() here is what keeps the outline
                    // parked at the press point, so the viewport stops feeding hover updates for the duration.
                    if (editor.adjustingBrush) editor.updateBrushAdjust(change.position)
                    else editor.move(
                        change.position,
                        computeViewport(previewModel, viewSize.width, viewSize.height),
                        event.keyboardModifiers.isShiftPressed,
                        event.keyboardModifiers.isAltPressed
                    )
                }
                if (isDragging) {
					val delta = change.position - lastDragPos
					panX += delta.x
					panY += delta.y
                    viewModel.setCanvasView(zoom.toFloat(), panX.toFloat(), panY.toFloat())
					lastDragPos = change.position
				}
				if (!isDragging && mode == CanvasMode.PREVIEW && state.mouseTrackingEnabled) {
					val normX = ((change.position.x - viewSize.width * 0.5f) / (viewSize.width * 0.5f).coerceAtLeast(1f)).coerceIn(-1f, 1f)
					val normY = ((change.position.y - viewSize.height * 0.5f) / (viewSize.height * 0.5f).coerceAtLeast(1f)).coerceIn(-1f, 1f)
					viewModel.updatePointer(normX, normY)
					notePointerActivity()
				}
			}
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				if (mode == CanvasMode.EDIT && event.keyboardModifiers.isAltPressed &&
					editor.tool in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)
				) {
					val step = if (event.keyboardModifiers.isShiftPressed) 45f else 15f
					editor.brushAngle = (editor.brushAngle + if (change.scrollDelta.y > 0) step else -step).mod(360f)
					change.consume()
					return@onPointerEvent
				}
				if(change.isConsumed || (mode == CanvasMode.EDIT && (editor.inGesture || editor.adjustingBrush))) return@onPointerEvent
                val delta = change.scrollDelta.y
                zoomAt(change.position.x, change.position.y, delta)
			},
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			val w = size.width.toInt().coerceAtLeast(1)
			val h = size.height.toInt().coerceAtLeast(1)

			// 1. One cached texture fill replaces thousands of per-frame checkerboard draw calls.
			drawRect(brush = checkerboardBrush)

			val model = previewModel
			if (model == null) {
				return@Canvas
			}

			val viewport = computeViewport(model, w, h)

			// 2. Draw canvas boundary
			drawRect(
				color = Color(198, 205, 216, 105),
				topLeft = Offset(viewport.offsetX.toFloat(), viewport.offsetY.toFloat()),
				size = Size((viewport.canvasWidth * viewport.scale).toFloat(), (viewport.canvasHeight * viewport.scale).toFloat()),
				style = Stroke(width = 1f),
			)

			// 3. Multi-channel Rendering: Texture, Mesh, Warp
			val hoveredIsWarp = state.hoveredDeformerId != null &&
				model.rig.puppet.deformers.any { it.id.raw == state.hoveredDeformerId && it is org.umamo.runtime.model.Deformer.Warp }
			// The per-tab "Deformer Warp" option is authoritative here: an Edit tab shows the rig
			// guides because its default enables them, not because the mode forces them on.
			val showWarp = state.showWarp || (state.selectedDeformerId != null) || hoveredIsWarp
			val showMesh = state.showMesh
			val showTexture = state.showTexture
			val informationNames = state.warpShowNames
			val informationIndices = state.warpShowIndices
			val informationSelectedOnly = state.filterSelectedOnly

			val targetVisibleLayerIds: Set<String> = when {
				!informationSelectedOnly -> state.effectiveVisibleLayerIds
				state.selectedLayerId != null -> state.effectiveVisibleLayerIds.filter { it == state.selectedLayerId }.toSet()
				state.selectedDeformerId != null -> {
					val desc = descendantLayerIds(model, state.selectedDeformerId, state.parentOverrides)
					state.effectiveVisibleLayerIds.filter { it in desc }.toSet()
				}
				else -> state.effectiveVisibleLayerIds
			}

			val hasActiveSelection = state.selectedLayerId != null || state.selectedDeformerId != null
			val highlightedLayerIds: Set<String>? = when {
                mode==CanvasMode.EDIT && editor.objectMode && editor.objects.isNotEmpty() -> editor.objects
				state.selectedLayerId != null -> setOf(state.selectedLayerId)
				state.selectedDeformerId != null -> descendantLayerIds(model, state.selectedDeformerId, state.parentOverrides)
				else -> null
			}
			val isDimmingActive = state.dimUnselected && hasActiveSelection

			val nativeFrame = sdkFrame
			// Path guides never paint outside the Edit tab (see 3e), so they cannot force the
			// preview off its native SDK frame.
			val canUseNativeSdk = mode == CanvasMode.PREVIEW &&
				!showWarp && !showMesh && !informationSelectedOnly && showTexture &&
				!isDimmingActive &&
				state.hoveredLayerId == null && state.hoveredDeformerId == null &&
				(!state.showSelectionBounds || !hasActiveSelection) &&
				state.drawOrderOverrides.isEmpty() &&
				nativeFrame != null && sdkBitmap != null &&
				nativeFrame.image.width == w && nativeFrame.image.height == h

			val currentSdkBitmap = sdkBitmap
			if (canUseNativeSdk && currentSdkBitmap != null) {
				drawImage(currentSdkBitmap)
			} else {
				val buffer = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
				val g = buffer.createGraphics()
				try {
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
					val geometry = RigCanvasSupport.evaluate(model, if (mode == CanvasMode.PREVIEW) informationPose else state.parameterValues)

					// 3a. Texture Channel. The artwork always renders opaque; legibility of the
					// overlays comes from the focus/dim options instead of a global transparency.
					if (showTexture) {
						drawIntoCanvas { target -> editingPainter?.paint(
							target.skiaCanvas,
							model,
							geometry,
							viewport,
							1.0f,
							visibleLayerIds = targetVisibleLayerIds,
							drawOrderOverrides = state.drawOrderOverrides,
							dimUnselected = state.dimUnselected,
							highlightedLayerIds = highlightedLayerIds,
							dimmedAlphaMultiplier = 0.22f,
						) }
					}

					// 3b. Mesh Channel (Wireframe)
					if (showMesh) {
						fun drawMeshWireframe(drawable: org.umamo.runtime.model.Drawable, selected: Boolean, dimmed: Boolean = false) {
							val mesh = drawable.mesh ?: return
							val positions = geometry.worldPositions[drawable.id] ?: return
							val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: return
							if (layerId !in targetVisibleLayerIds) return
							val awtColor = ComponentPalette.strong(layerId)
							val strokeWidth = if (selected) 2.2f else if (dimmed) 0.65f else 1.1f
							val wireColor = when {
								selected -> awtColor.brighter()
								dimmed -> java.awt.Color(awtColor.red, awtColor.green, awtColor.blue, 65)
								else -> awtColor
							}
							if (selected) {
								g.color = java.awt.Color.WHITE
								val radius = 2
								for (i in 0 until mesh.vertexCount) {
									val vx = viewport.x(positions[i * 2]).toInt()
									val vy = viewport.yFromWorld(positions[i * 2 + 1]).toInt()
									g.fillOval(vx - radius, vy - radius, radius * 2 + 1, radius * 2 + 1)
								}
							}
							// Opaque artwork needs a dark halo under every wire so the mesh stays readable.
							fun drawEdges(color: java.awt.Color, width: Float) {
								g.color = color
								g.stroke = BasicStroke(width)
								for (offset in mesh.indices.indices step 3) {
									val a = mesh.indices[offset]
									val b = mesh.indices[offset + 1]
									val c = mesh.indices[offset + 2]
									g.drawLine(
										viewport.x(positions[a * 2]).toInt(),
										viewport.yFromWorld(positions[a * 2 + 1]).toInt(),
										viewport.x(positions[b * 2]).toInt(),
										viewport.yFromWorld(positions[b * 2 + 1]).toInt(),
									)
									g.drawLine(
										viewport.x(positions[b * 2]).toInt(),
										viewport.yFromWorld(positions[b * 2 + 1]).toInt(),
										viewport.x(positions[c * 2]).toInt(),
										viewport.yFromWorld(positions[c * 2 + 1]).toInt(),
									)
									g.drawLine(
										viewport.x(positions[c * 2]).toInt(),
										viewport.yFromWorld(positions[c * 2 + 1]).toInt(),
										viewport.x(positions[a * 2]).toInt(),
										viewport.yFromWorld(positions[a * 2 + 1]).toInt(),
									)
								}
							}
							if (showTexture && !dimmed) drawEdges(java.awt.Color(12, 13, 16, 150), strokeWidth + 1.6f)
							drawEdges(wireColor, strokeWidth)
						}

						val selectedId = state.selectedLayerId
						for (drawable in model.rig.puppet.drawables) {
							val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
							if (layerId != selectedId) {
								val isDimmed = isDimmingActive && (highlightedLayerIds != null && (layerId == null || layerId !in highlightedLayerIds))
								drawMeshWireframe(drawable, selected = false, dimmed = isDimmed)
							}
						}
						if (selectedId != null) {
							for (drawable in model.rig.puppet.drawables) {
								val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
								if (layerId == selectedId) {
									drawMeshWireframe(drawable, selected = true, dimmed = false)
								}
							}
						}
					}

					// 3c. Bounding Boxes (Selection, Hover, and Hierarchy Rotation Deformers)
					val drawableBounds = RigCanvasSupport.boundsByDrawable(geometry)
					val deformerBounds = RigCanvasSupport.boundsByDeformer(model, drawableBounds)

					if (mode == CanvasMode.EDIT) {
						for (deformer in model.rig.puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Rotation>()) {
							val bounds = deformerBounds[deformer.id.raw] ?: continue
							val selected = deformer.id.raw == state.selectedDeformerId
							// With nothing selected every box is unselected, so the rig guide fades.
							val isDimmed = state.dimUnselected && !selected
							val rawColor = ComponentPalette.strong(deformer.id.raw)
							val color = when {
								selected -> rawColor.brighter()
								isDimmed -> java.awt.Color(rawColor.red, rawColor.green, rawColor.blue, 55)
								else -> rawColor
							}
							val strokeWidth = if (selected) 2.8f else if (isDimmed) 0.75f else 1.15f
							RigCanvasSupport.paintBounds(g, bounds, viewport, color, strokeWidth)

							if (!isDimmed || selected) {
								g.font = java.awt.Font(java.awt.Font.SANS_SERIF, if (selected) java.awt.Font.BOLD else java.awt.Font.PLAIN, 11)
								val lx = viewport.x(bounds.left).toInt() + 2
								val ly = (viewport.offsetY + bounds.top * viewport.scale).toInt() - 3
								val metrics = g.fontMetrics
								val labelY = ly.coerceAtLeast(metrics.ascent + 2)
								g.color = java.awt.Color(24, 26, 30, if (isDimmed) 90 else 205)
								g.fillRoundRect(lx - 2, labelY - metrics.ascent, metrics.stringWidth(deformer.name) + 7, metrics.height, 5, 5)
								g.color = color.brighter()
								g.drawString(deformer.name, lx + 1, labelY)
							}
						}
					}

					// Global Selection Bounding Box (across all modes if enabled)
					if (state.showSelectionBounds) {
						state.selectedLayerId?.let { layerId ->
							val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
							val bounds = drawableId?.let(drawableBounds::get)
							if (bounds != null) {
								val selColor = ComponentPalette.strong(layerId).brighter()
								RigCanvasSupport.paintSelectionBounds(g, bounds, viewport, selColor, stroke = 2.0f, isDashed = false)
							}
						}
						if (mode != CanvasMode.EDIT) {
							state.selectedDeformerId?.let { defId ->
								val def = model.rig.puppet.deformers.firstOrNull { it.id.raw == defId }
								if (def !is org.umamo.runtime.model.Deformer.Warp) {
									val bounds = deformerBounds[defId]
									if (bounds != null) {
										val selColor = ComponentPalette.strong(defId).brighter()
										RigCanvasSupport.paintSelectionBounds(g, bounds, viewport, selColor, stroke = 2.0f, isDashed = false)
									}
								}
							}
						}
					}

					// Hover Bounding Box (instant feedback when hovering items in hierarchy tree)
					state.hoveredLayerId?.takeIf { it != state.selectedLayerId }?.let { layerId ->
						val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
						val bounds = drawableId?.let(drawableBounds::get)
						if (bounds != null) {
							RigCanvasSupport.paintSelectionBounds(g, bounds, viewport, java.awt.Color(0, 210, 255, 190), stroke = 1.4f, isDashed = true)
						}
					}
					state.hoveredDeformerId?.takeIf { it != state.selectedDeformerId }?.let { defId ->
						val def = model.rig.puppet.deformers.firstOrNull { it.id.raw == defId }
						if (def !is org.umamo.runtime.model.Deformer.Warp) {
							val bounds = deformerBounds[defId]
							if (bounds != null) {
								RigCanvasSupport.paintSelectionBounds(g, bounds, viewport, java.awt.Color(0, 210, 255, 190), stroke = 1.4f, isDashed = true)
							}
						}
					}

					// 3d. Warp Channel (RigInformationOverlay)
					if (showWarp) {
						val baseWarpIds = computeActiveWarpIds(
							model = model,
							selectedDeformerId = state.selectedDeformerId,
							selectedLayerId = state.selectedLayerId,
							parentOverrides = state.parentOverrides,
							selectedOnly = informationSelectedOnly,
							contextualWarp = state.contextualWarp,
						)
						val hoveredId = state.hoveredDeformerId
						val ids = (if (hoveredIsWarp && hoveredId != null) {
							baseWarpIds + hoveredId
						} else {
							baseWarpIds
						}).filter { state.isDeformerVisible(it) }.toSet()

						io.github.psd2live.ui.RigInformationOverlay.paint(
							g, model.rig.puppet,
							if (mode == CanvasMode.PREVIEW) informationPose else state.parameterValues,
							viewport, ids,
							labels = informationNames,
							pointIndices = informationIndices,
							selectedDeformerId = state.selectedDeformerId,
							hoveredDeformerId = state.hoveredDeformerId,
							dimUnselected = state.dimUnselected,
						)
					}

					// 3e. Deform Paths (RigInformationOverlay). A path belongs to the part it
					// deforms, so it is drawn only while that part (or the part's deformer) is
					// selected -- an edit-time guide, never part of the Preview tab's render.
					if (mode == CanvasMode.EDIT && state.showDeformPaths && model.rig.puppet.deformPaths.isNotEmpty()) {
						val selectedLayerDescendants = if (state.selectedDeformerId != null) {
							descendantLayerIds(model, state.selectedDeformerId, state.parentOverrides)
						} else {
							emptySet()
						}
						val selectedPathIds = model.rig.puppet.deformPaths.filter { path ->
							val layerId = model.rig.layerIdByDrawableId[path.drawableId.raw]
							(state.selectedLayerId != null && layerId == state.selectedLayerId) ||
								(state.selectedDeformerId != null && layerId != null && layerId in selectedLayerDescendants)
						}.map { it.id }.toSet()

						val hoveredPathIds = model.rig.puppet.deformPaths.filter { path ->
							val layerId = model.rig.layerIdByDrawableId[path.drawableId.raw]
							state.hoveredLayerId != null && layerId == state.hoveredLayerId
						}.map { it.id }.toSet()

						// Hovering a part in the tree previews its path -- same instant feedback the
						// warp channel gives, without bringing back the always-on rig clutter.
						val pathIds = selectedPathIds + hoveredPathIds

						if (pathIds.isNotEmpty()) {
							io.github.psd2live.ui.RigInformationOverlay.paintDeformPaths(
								g = g,
								model = model.rig.puppet,
								geometry = geometry,
								viewport = viewport,
								pathIds = pathIds,
								labels = false,
								pointIndices = informationIndices,
								showWidth = state.pathShowWidth,
								showHardness = state.pathShowHardness,
								selectedPathIds = selectedPathIds,
								hoveredPathIds = hoveredPathIds,
							)
						}
					}
				} finally {
					g.dispose()
				}
				drawImage(buffer.toComposeImageBitmap())
			}
		}

		if(mode == CanvasMode.EDIT && previewModel != null) {
            val vp = computeViewport(previewModel,viewSize.width,viewSize.height)
            editor.viewport = vp
            CanvasEditorOverlay(editor,vp,viewModel,keymap = state.keymap) { focusRequester.requestFocus() }
        }
        // Overlay: Empty hint or Stats Badge
		if (previewModel == null) {
			Text(
				text = when (mode) {
					CanvasMode.EDIT -> tr("canvas.hierarchy.empty")
					CanvasMode.PREVIEW -> tr("canvas.preview.empty")
				},
				style = typography.body.copy(fontSize = 12.sp),
				color = colors.textMuted,
				modifier = Modifier.align(Alignment.Center),
			)
		} else {
			// Floating Stats Pill Badge
			val zoomPct = (zoom * 100).toInt()
			val fpsStr = if (fps > 0f) "%.1f FPS · ".format(fps) else ""
			val badgeText = when (mode) {
				CanvasMode.PREVIEW -> when {
					sdkFrame != null -> "${fpsStr}${tr(
						if (previewModel.hasRuntimePhysics) "canvas.preview.cubismPhysicsOn" else "canvas.preview.cubismPhysicsOff",
						zoomPct,
					)}"
					state.sdkStatus != null && state.sdkStatus != "ready" -> "${fpsStr}${tr("canvas.preview.softwareFallback", zoomPct)}"
					previewModel.hasRuntimePhysics -> "${fpsStr}${tr("canvas.preview.physicsOn", zoomPct)}"
					else -> "${fpsStr}${tr("canvas.preview.physicsOff", zoomPct)}"
				}
				CanvasMode.EDIT -> if (state.showMesh) {
					val vertexCount = previewModel.rig.puppet.drawables.sumOf { it.mesh?.vertexCount ?: 0 }
					val triangleCount = previewModel.rig.puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }
					tr("canvas.mesh.stats", previewModel.rig.puppet.drawables.size, vertexCount, triangleCount, zoomPct)
				} else {
					"$zoomPct%"
				}
			}

			if (badgeText.isNotEmpty()) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomEnd)
						// Each tab's bottom bar owns the bottom strip: the editor's footer in Edit,
						// the preview's floating toolbar in Preview. The badge sits above whichever
						// one is showing instead of on top of it.
						.padding(end = 10.dp, bottom = if (mode == CanvasMode.EDIT) 32.dp else 48.dp)
						.background(Color(0xCC181A1E), RoundedCornerShape(4.dp))
						.padding(horizontal = 8.dp, vertical = 4.dp),
				) {
					Text(
						text = badgeText,
						style = typography.caption.copy(fontSize = 11.sp, color = Color(0xFFD7DEE7)),
					)
				}
			}
		}
	}
}

private class ActualFpsCounter {
	private var windowStartNanos = 0L
	private var frames = 0

	fun record(nowNanos: Long): Float? {
		if (windowStartNanos == 0L) {
			windowStartNanos = nowNanos
			return null
		}
		frames++
		val elapsed = nowNanos - windowStartNanos
		if (elapsed < 500_000_000L) return null
		val measured = (frames * 1_000_000_000.0 / elapsed).toFloat()
		windowStartNanos = nowNanos
		frames = 0
		return measured
	}

	fun reset() {
		windowStartNanos = 0L
		frames = 0
	}
}

private fun createCheckerboardBrush(light: Color, dark: Color, cellSize: Int = 14): Brush {
	val tileSize = cellSize * 2
	val tile = BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB)
	val graphics = tile.createGraphics()
	try {
		graphics.color = java.awt.Color(light.toArgb(), true)
		graphics.fillRect(0, 0, tileSize, tileSize)
		graphics.color = java.awt.Color(dark.toArgb(), true)
		graphics.fillRect(cellSize, 0, cellSize, cellSize)
		graphics.fillRect(0, cellSize, cellSize, cellSize)
	} finally {
		graphics.dispose()
	}
	return RepeatedImageBrush(tile.toComposeImageBitmap())
}

private class RepeatedImageBrush(private val image: ImageBitmap) : ShaderBrush() {
	override fun createShader(size: Size): Shader = ImageShader(
		image = image,
		tileModeX = TileMode.Repeated,
		tileModeY = TileMode.Repeated,
	)
}

private fun computeCubismViewport(
	model: RigPreviewModel,
	width: Int,
	height: Int,
	zoom: Double,
	panX: Double,
	panY: Double,
): CubismViewport {
	val safeWidth = width.coerceAtLeast(1).toDouble()
	val safeHeight = height.coerceAtLeast(1).toDouble()
	val viewportAspect = safeWidth / safeHeight
	val canvasWidth = model.analysis.source.widthPx.coerceAtLeast(1).toDouble()
	val canvasHeight = model.analysis.source.heightPx.coerceAtLeast(1).toDouble()
	val modelAspect = canvasWidth / canvasHeight
	val baseScaleX = if (viewportAspect >= 1.0) 1.0 / viewportAspect else 1.0
	val baseScaleY = if (viewportAspect >= 1.0) 1.0 else viewportAspect
	val fitScale = if (modelAspect > viewportAspect) {
		1.0 / (baseScaleX * modelAspect) * 0.95
	} else {
		1.0 / baseScaleY * 0.95
	}
	return CubismViewport(
		(fitScale * zoom).toFloat(),
		(panX / (safeWidth * 0.5)).toFloat(),
		(-panY / (safeHeight * 0.5)).toFloat(),
	)
}

internal fun descendantLayerIds(model: RigPreviewModel, deformerId: String, parentOverrides: Map<String, String?>): Set<String> {
	val deformerById = model.rig.puppet.deformers.associateBy { it.id.raw }
	val drawableById = model.rig.puppet.drawables.associateBy { it.id.raw }
	val layerByDrawable = model.rig.layerIdByDrawableId

	val targetDeformers = mutableSetOf(deformerId)
	var changed = true
	while (changed) {
		changed = false
		for (def in model.rig.puppet.deformers) {
			val defId = def.id.raw
			if (defId !in targetDeformers) {
				val p = parentOverrides[defId] ?: def.parent?.raw
				if (p in targetDeformers) {
					targetDeformers.add(defId)
					changed = true
				}
			}
		}
	}

	val result = mutableSetOf<String>()
	for (drawable in model.rig.puppet.drawables) {
		val drawId = drawable.id.raw
		val p = parentOverrides[drawId] ?: drawable.parentDeformerId?.raw
		if (p in targetDeformers) {
			val layerId = layerByDrawable[drawId]
			if (layerId != null) result.add(layerId)
		}
	}
	return result
}

private fun computeEditorViewport(model: RigPreviewModel, size: IntSize, zoom: Double, panX: Double, panY: Double, margin: Int=34): CanvasViewport {
    val width=model.analysis.source.widthPx.toFloat().coerceAtLeast(1f)
    val height=model.analysis.source.heightPx.toFloat().coerceAtLeast(1f)
    val fit=minOf((size.width-margin*2).coerceAtLeast(1)/width.toDouble(),(size.height-margin*2).coerceAtLeast(1)/height.toDouble())
    val scale=fit*zoom
    return CanvasViewport(scale,(size.width-width*scale)*0.5+panX,(size.height-height*scale)*0.5+panY,width,height)
}


