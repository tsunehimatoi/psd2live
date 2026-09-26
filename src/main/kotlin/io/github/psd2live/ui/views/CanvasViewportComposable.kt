package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import io.github.psd2live.ui.CanvasEditor
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.CreatePlacementKind
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.PaintShape
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
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
import io.github.psd2live.ui.CachedSkiaPicture
import io.github.psd2live.ui.SkiaRigPainter
import io.github.psd2live.ui.visibleCanvasGuideIds
import io.github.psd2live.ui.state.forCanvas
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PRIMARY_CANVAS_ID
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.TabViewOptions
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
	canvasId: String = PRIMARY_CANVAS_ID,
	viewOptions: TabViewOptions = state.forCanvas(canvasId).activeTabView,
	cameraZoom: Float = state.forCanvas(canvasId).canvasZoom,
	cameraPanX: Float = state.forCanvas(canvasId).canvasPanX,
	cameraPanY: Float = state.forCanvas(canvasId).canvasPanY,
	onStartTutorial: (() -> Unit)? = null,
	onOpenProject: (() -> Unit)? = null,
	onOpenPsd: (() -> Unit)? = null,
) {
    val editor = viewModel.canvasEditorFor(canvasId)
    val ownerState = state.forCanvas(canvasId)
    key(ownerState.projectOpenGeneration, ownerState.activeWorkspace.id, canvasId, mode) {
	// Hover belongs to the input session, so moving over this viewport invalidates only
	// this viewport instead of the shared document and every docked panel.
	val canvasState = if (mode == CanvasMode.EDIT &&
		(editor.hoveredLayerId != null || editor.hoveredDeformerId != null)) ownerState.copy(
		hoveredLayerId = editor.hoveredLayerId,
		hoveredDeformerId = editor.hoveredDeformerId,
	) else ownerState
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current.density

	var viewSize by remember { mutableStateOf(IntSize(600, 600)) }
	// Window position of this canvas. Dock toggles move the canvas without changing zoom or pan;
	// the mesh overlay has to redraw on that move or its picture stays at the old place.
	var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
	val showMesh = viewOptions.showMesh
	val showTexture = viewOptions.showTexture
	val showRotation = viewOptions.showRotation
	val showDeformPaths = viewOptions.showDeformPaths
	val showSelectionBounds = viewOptions.showSelectionBounds
	val dimUnselected = viewOptions.dimUnselected
	val filterSelectedOnly = viewOptions.filterSelectedOnly
	var zoom by remember { mutableStateOf(cameraZoom.toDouble()) }
	var panX by remember { mutableStateOf(cameraPanX.toDouble()) }
	var panY by remember { mutableStateOf(cameraPanY.toDouble()) }
    var isDragging by remember { mutableStateOf(false) }
    var cameraDirty by remember { mutableStateOf(false) }
    LaunchedEffect(cameraZoom, cameraPanX, cameraPanY) {
        // A camera update can arrive after a newer pointer move. The local camera owns
        // the position during a drag; applying that older state makes the canvas jump back.
        if (!isDragging && !cameraDirty) {
            zoom = cameraZoom.toDouble()
            panX = cameraPanX.toDouble()
            panY = cameraPanY.toDouble()
        }
    }
	var lastDragPos by remember { mutableStateOf(Offset.Zero) }
	var dragStartPos by remember { mutableStateOf(Offset.Zero) }
	var showContextMenu by remember { mutableStateOf(false) }
	var contextMenuOffset by remember { mutableStateOf(Offset.Zero) }
	var fps by remember { mutableStateOf(0f) }
	val fpsCounter = remember { ActualFpsCounter() }
	// A paused preview still follows the pointer, so the frame pump must stay awake for a moment
	// after every pointer change instead of rendering the single frame a pause asks for. A plain
	// AtomicLong rather than snapshot state: only the pump coroutine reads it.
	val lastPointerActivityNanos = remember { AtomicLong(0L) }
	val pointerActivity = remember { Channel<Unit>(Channel.CONFLATED) }

    val renderKey = viewModel.canvasRenderKey(canvasId, mode)
    val frameFlow = remember(viewModel, renderKey) { viewModel.retainCanvasFrame(renderKey) }
    DisposableEffect(viewModel, renderKey) {
        onDispose {
            viewModel.releaseRetainedCanvasFrame(renderKey)
            if (mode == CanvasMode.EDIT) {
                editor.space = false
                editor.altHeld = false
                if (editor.inGesture) editor.cancel()
                if (editor.adjustingBrush) editor.endBrushAdjust(cancel = true)
                editor.clearHover()
            }
        }
    }

	// A capture in the settings panel swallows key events, including the Space release that
	// clears the pan latch, so drop it proactively — a stuck pan would look like a hung canvas.
	LaunchedEffect(mode, canvasState.keyCapture, canvasState.showSettingsDialog, canvasState.showExportDialog) {
		if (mode == CanvasMode.EDIT &&
			(canvasState.keyCapture != null || canvasState.showSettingsDialog || canvasState.showExportDialog)) editor.space = false
	}

	// Rebinding happens in a modal that takes focus off the canvas. Pull it back on close so the
	// key the user just recorded works straight away instead of needing a click on the canvas.
	LaunchedEffect(canvasState.focusCanvasRequest) {
		if (canvasState.focusCanvasRequest > 0 && viewModel.state.value.activeCanvas.id == canvasId) focusRequester.requestFocus()
	}
    LaunchedEffect(mode, canvasState.selectedLayerId, canvasState.selectedLayerIds, canvasState.selectedDeformerId) {
        if (mode == CanvasMode.EDIT && !editor.inGesture && !editor.busy) {
            val selectedObjects = canvasState.selectedLayerIds.ifEmpty { setOfNotNull(canvasState.selectedLayerId) }
            // Edit mode edits the whole set at once, and picking a vertex on another mesh of the set only
            // moves the primary. That must not throw away the vertex selection the pick just made.
            val sameEditSet = editor.hierarchyMode == EditHierarchyMode.EDIT &&
                canvasState.selectedDeformerId == null && editor.objects == selectedObjects
            if (!sameEditSet) editor.resetSelection()
            if (editor.objects != selectedObjects) editor.objects = selectedObjects
            // Vertex mode is only meaningful for the tools that edit points. Forcing it for the object
            // tools left `objects` populated while objectMode said otherwise, and the transform bounding
            // box — which is computed from objectMode — then framed a different set than the one a drag
            // actually moved.
            if(canvasState.selectedDeformerId!=null && editor.tool in VERTEX_TOOLS) { editor.objects=emptySet() }
        }
    }
    // A mode asked for without a selection waits for one, and this is where the wait ends: a selection
    // arriving from any view — the canvas pick, the hierarchy tree, the inspector — is what a deferred
    // request was for. Deliberately outside the guard above: an object pick happens on the press of a
    // gesture that is still live, and the request has to be answered whether or not that gesture is.
    LaunchedEffect(mode, canvasState.selectedLayerId, canvasState.selectedDeformerId) {
        if (mode != CanvasMode.EDIT) return@LaunchedEffect
        // A layer or deformer picked from any view replaces the skeleton as the target; its edit is kept.
        if (canvasState.selectedLayerId != null || canvasState.selectedDeformerId != null) editor.deselectSkeleton()
        editor.resolveDeferredMode()
    }
    LaunchedEffect(mode, canvasState.historySnapshot?.headNodeId, canvasState.parameterValues, canvasState.previewModel?.rig?.puppet) {
        if (mode == CanvasMode.EDIT) {
            // A pose drag is the one gesture whose whole output is parameter values, so its own writes
            // must not read as the document changing under it.
            // A swing handle drag patches the preview on every move for the same reason.
            if (!editor.busy && editor.inGesture && editor.poseDrag == null && !editor.swingDragging) editor.cancel()
            editor.reconcileWithDocument()
            if (!editor.busy && canvasState.previewModel != null) editor.target()?.let { t -> editor.vertices=editor.vertices.filter { it in 0 until t.count }.toSet() }
        }
    }
    val sourcePreviewModel = canvasState.previewModel
    val editPuppet = if (mode == CanvasMode.EDIT) editor.preview else null
    val previewModel = remember(sourcePreviewModel, editPuppet) {
        sourcePreviewModel?.let { source ->
            if (editPuppet != null) source.copy(rig = source.rig.copy(puppet = editPuppet)) else source
        }
    }
	val paintSession = if (mode == CanvasMode.EDIT && editor.hierarchyMode == EditHierarchyMode.PAINT)
		editor.paintSession else null
	val geometryPose = if (paintSession != null) emptyMap<org.umamo.runtime.model.ParameterId, Float>()
		else canvasState.parameterValues
	val editGeometry = remember(previewModel, geometryPose, mode) {
		if (mode == CanvasMode.EDIT && previewModel != null) RigCanvasSupport.evaluate(previewModel, geometryPose)
		else null
	}
	val warpIds = if (previewModel == null) emptySet() else if (mode == CanvasMode.EDIT) editor.activeWarpIds()
		else visibleCanvasGuideIds(previewModel, canvasState, warp = true)
	val rotationIds = if (previewModel == null) emptySet() else if (mode == CanvasMode.EDIT) editor.activeRotationIds()
		else visibleCanvasGuideIds(previewModel, canvasState, warp = false)
	val viewportFor = remember(previewModel, zoom, panX, panY) {
		val model = previewModel
		{ drawSize: IntSize ->
			if (model == null) CanvasViewport(1.0, 0.0, 0.0, 1f, 1f)
			else computeEditorViewport(model, drawSize, zoom, panX, panY)
		}
	}
	val editingPainter = remember(previewModel?.atlas) { previewModel?.atlas?.let(::SkiaRigPainter) }
	DisposableEffect(editingPainter) { onDispose { editingPainter?.close() } }
	val artworkCache = remember { CachedSkiaPicture() }
	DisposableEffect(artworkCache) { onDispose { artworkCache.close() } }
	val guideCache = remember { CanvasGuideImageCache() }
	val sdkFrame by frameFlow.collectAsState()
	val sdkBitmap = remember(sdkFrame?.image) { sdkFrame?.image?.toComposeImageBitmap() }
	val checkerboardBrush = remember(colors.checkerLight, colors.checkerDark) {
		createCheckerboardBrush(colors.checkerLight, colors.checkerDark)
	}
	// One pose for the whole tab: artwork, diagnostic geometry and hit-testing. A paused preview
	// is still live here, because the follow keeps moving the pose after the motion stops.
	val informationPose = informationPreviewPose(
		canvasState.parameterValues,
		canvasState.previewParameterValues,
		sdkFrame,
		canvasState.animationEnabled || (mode == CanvasMode.PREVIEW && canvasState.mouseTrackingEnabled),
	)
	val warpPose = if (mode == CanvasMode.PREVIEW) informationPose else canvasState.parameterValues
	val warpPoints = remember(previewModel?.rig?.puppet, warpPose, warpIds) {
		runCatching {
			if (previewModel != null && warpIds.isNotEmpty())
				io.github.psd2live.ui.RigInformationOverlay.warpPoints(previewModel.rig.puppet, warpPose, warpIds)
			else emptyMap()
		}.getOrDefault(emptyMap())
	}
	val currentZoom by rememberUpdatedState(zoom)
	val currentPanX by rememberUpdatedState(panX)
	val currentPanY by rememberUpdatedState(panY)
	val simultaneousPreviews = canvasState.activeWorkspace.canvases.count {
		it.mode == CanvasMode.PREVIEW && it.id !in canvasState.activeWorkspace.hiddenModules
	}

	LaunchedEffect(frameFlow) {
		frameFlow.collect { frame ->
			if (frame == null) {
				fpsCounter.reset()
				fps = 0f
			} else {
				val measured = fpsCounter.record(System.nanoTime())
				if (measured != null) fps = measured
			}
		}
	}

	fun editorGuard(block: () -> Unit) {
		try {
			block()
		} catch (failure: kotlinx.coroutines.CancellationException) {
			throw failure
		} catch (failure: Exception) {
			editor.error = failure.message?.takeIf { it.isNotBlank() } ?: "Failed requirement."
		}
	}
	fun notePointerActivity() {
		lastPointerActivityNanos.set(System.nanoTime())
		pointerActivity.trySend(Unit)
	}

    fun persistCamera() {
        if (!cameraDirty) return
        cameraDirty = false
        viewModel.setCanvasView(zoom.toFloat(), panX.toFloat(), panY.toFloat(), canvasId, mode)
    }

    // A mode or workspace switch can remove this viewport before it receives Release.
    DisposableEffect(viewModel, canvasId, mode, canvasState.projectOpenGeneration, canvasState.activeWorkspace.id) {
        val projectGeneration = canvasState.projectOpenGeneration
        val workspaceId = canvasState.activeWorkspace.id
        onDispose {
            val current = viewModel.state.value
            if (cameraDirty && current.projectOpenGeneration == projectGeneration && current.activeWorkspace.id == workspaceId) {
                persistCamera()
            }
        }
    }

	fun resetCamera() {
		zoom = 1.0
		panX = 0.0
		panY = 0.0
        cameraDirty = false
        viewModel.setCanvasView(zoom.toFloat(), panX.toFloat(), panY.toFloat(), canvasId, mode)
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
		zoom=next;cameraDirty=false;viewModel.setCanvasView(zoom.toFloat(),panX.toFloat(),panY.toFloat(), canvasId, mode)
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
        cameraDirty = false
        viewModel.setCanvasView(zoom.toFloat(), panX.toFloat(), panY.toFloat(), canvasId, mode)
	}

	// Vsync-driven frame pump. Cubism conflates requests while busy, so the newest
	// parameters are rendered next without building latency in a callback queue.
    // Animated previews read the latest camera each frame. Restarting their pump for
    // every pointer move stalls rendering and makes panning visibly trail the cursor.
    val pausedCameraKey = if (canvasState.animationEnabled) Unit else Triple(zoom, panX, panY)
	LaunchedEffect(renderKey, mode, previewModel, canvasState.animationEnabled, canvasState.mouseTrackingEnabled, viewSize, pausedCameraKey, canvasState.parameterValues, simultaneousPreviews) {
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
                        viewId = renderKey,
					)
				}
				if (canvasState.animationEnabled) {
					var previousFrameNanos = 0L
					// Native Cubism renders every preview on one GL thread. With multiple visible previews,
					// cap each pump at 30 FPS so they cannot saturate that thread and stall input.
					val frameIntervalNanos = if (simultaneousPreviews > 1) 30_000_000L else 0L
					while (isActive) {
						val frameNanos = withFrameNanos { it }
						if (previousFrameNanos != 0L && frameNanos - previousFrameNanos < frameIntervalNanos) continue
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
					while (isActive && canvasState.mouseTrackingEnabled) {
						pointerActivity.receive()
						var previousSettlingFrameNanos = 0L
						while (isActive &&
							System.nanoTime() - lastPointerActivityNanos.get() <= PAUSED_TRACKING_SETTLE_NANOS
						) {
							val frameNanos = withFrameNanos { it }
							if (simultaneousPreviews > 1 && previousSettlingFrameNanos != 0L &&
								frameNanos - previousSettlingFrameNanos < 30_000_000L) continue
							previousSettlingFrameNanos = frameNanos
							requestFrame(0f, frameNanos)
						}
					}
				}
			}
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			// clipToBounds() is a graphics layer. On a dock resize that layer's picture keeps the
			// previous window position until something else invalidates it, so mesh points stay
			// behind while the artwork moves. A draw-time clip follows layout immediately.
			.drawWithContent { clipRect { this@drawWithContent.drawContent() } }
			.background(colors.windowBackground)
			.focusRequester(focusRequester)
			.onFocusChanged {
                if (it.hasFocus) viewModel.focusCanvas(canvasId)
                else {
                    isDragging = false
                    persistCamera()
                    if (mode == CanvasMode.EDIT) {
                        editor.altHeld = false
                        editor.space = false
                        if (editor.inGesture) editor.cancel()
                        if (editor.adjustingBrush) editor.endBrushAdjust(cancel = false)
                    }
                }
            }
			.focusable()
			.onSizeChanged { viewSize = it }
			.onGloballyPositioned { coordinates ->
				val origin = coordinates.positionInRoot()
				if (origin != canvasOrigin) canvasOrigin = origin
			}
			.onKeyEvent { event ->
				// A capture in the settings panel owns the keyboard. The root handler already
				// swallowed the event, but stay inert anyway so nothing reaches the canvas
				// mid-recording.
				if (canvasState.keyCapture != null) return@onKeyEvent false
				// The pan latch and its release must outlive every gate below: it is a press /
				// release pair rather than a discrete command, and it stays live while an edit
				// commits. Space is deliberately not a bindable action.
				if (mode == CanvasMode.EDIT && previewModel != null && event.key == Key.Spacebar) {
					editor.space = event.type == KeyEventType.KeyDown
					return@onKeyEvent true
				}
				// Alt is a latch of the same kind: the pointer reports its modifiers only while it moves,
				// and the sampling ring has to appear the moment Alt is held, not the moment the mouse
				// happens to twitch. Not consumed - Alt belongs to whatever else wants it too.
				if (mode == CanvasMode.EDIT && previewModel != null &&
					(event.key == Key.AltLeft || event.key == Key.AltRight)
				) {
					editor.altHeld = event.type == KeyEventType.KeyDown
					return@onKeyEvent false
				}
                if (mode == CanvasMode.EDIT && !editor.busy && event.type == KeyEventType.KeyDown &&
                    event.key == Key.Backspace && (editor.tool == CanvasTool.KNIFE || editor.drawingPath)) {
                    editor.undoDraftPoint()
                    return@onKeyEvent true
                }
				val action = canvasState.keymap.match(event, ShortcutScope.CANVAS)
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
				if (editor.busy || canvasState.canvasEditBusy) return@onKeyEvent true
				return@onKeyEvent when (action) {
					ShortcutAction.SELECT_ALL -> { editor.selectAll(); true }
					ShortcutAction.INVERT_SELECTION -> { editor.selectAll(true); true }
					ShortcutAction.TOOL_SELECT -> { editor.activateTool(CanvasTool.SELECT); true }
					ShortcutAction.TOOL_LASSO_SELECT -> { editor.activateTool(CanvasTool.LASSO_SELECT); true }
					ShortcutAction.TOOL_BRUSH_SELECT -> { editor.activateTool(CanvasTool.BRUSH_SELECT); true }
					ShortcutAction.TOOL_BRUSH -> { editor.activateTool(CanvasTool.BRUSH); true }
					ShortcutAction.TOOL_SMOOTH -> { editor.activateTool(CanvasTool.SMOOTH); true }
					ShortcutAction.TOOL_INFLATE -> { editor.activateTool(CanvasTool.INFLATE); true }
					ShortcutAction.TOOL_SKELETON_POSE -> { editor.activateTool(CanvasTool.SKELETON_POSE); true }
					ShortcutAction.TOOL_SKELETON_EDIT -> { editor.activateTool(CanvasTool.SKELETON_EDIT); true }
					ShortcutAction.TOOL_CREATE_WARP -> { editor.activateTool(CanvasTool.CREATE_WARP); true }
					ShortcutAction.TOOL_CREATE_ROTATION -> { editor.activateTool(CanvasTool.CREATE_ROTATION); true }
					ShortcutAction.TOOL_CREATE_DEFORM_PATH -> { editor.activateTool(CanvasTool.CREATE_DEFORM_PATH); true }
					ShortcutAction.TOOL_GLUE -> { editor.activateTool(CanvasTool.GLUE); true }
					ShortcutAction.TOOL_SUBDIVIDE -> { editor.activateTool(CanvasTool.SUBDIVIDE); true }
					ShortcutAction.TOOL_KNIFE -> { editor.activateTool(CanvasTool.KNIFE); true }
					ShortcutAction.SELECTION_STYLE_BOX -> { editor.selectionStyle = SelectionStyle.BOX; true }
					ShortcutAction.SELECTION_STYLE_LASSO -> { editor.selectionStyle = SelectionStyle.LASSO; true }
					ShortcutAction.SELECT_LINKED -> { editor.selectLinked(); true }
					ShortcutAction.CANCEL -> {
						if (showContextMenu) {
							showContextMenu = false
							true
						} else {
							if (viewModel.swingSession != null) viewModel.endSwing()
							else if (editor.placement != null) editor.cancelPlacement() else editor.cancel()
							true
						}
					}
					ShortcutAction.FINISH_PATH -> {
						when {
							viewModel.swingSession != null -> { viewModel.commitSwing(); true }
							editor.tool == CanvasTool.KNIFE -> { editor.finishKnife(); true }
							editor.placement != null && editor.placement?.kind != CreatePlacementKind.PATH -> {
								editor.confirmPlacement(); true
							}
							editor.tool == CanvasTool.CREATE_DEFORM_PATH || editor.drawingPath ||
								editor.placement?.kind == CreatePlacementKind.PATH -> {
								editor.finishPath(); true
							}
							editor.placement != null -> { editor.confirmPlacement(); true }
							editor.tool == CanvasTool.GLUE -> { editor.applyGlue(); true }
							else -> false
						}
					}
					ShortcutAction.DELETE_SELECTION -> {
						if (editor.tool == CanvasTool.KNIFE || editor.drawingPath) editor.undoDraftPoint()
                        else if (editor.tool == CanvasTool.CREATE_DEFORM_PATH || editor.activePath != null) editor.deletePathPoint()
						else if (editor.hierarchyMode == EditHierarchyMode.EDIT) editor.topology("delete")
						true
					}
					ShortcutAction.TOOL_PAINT_BRUSH -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.activateTool(CanvasTool.PAINT_BRUSH); true
					}
					ShortcutAction.TOOL_PAINT_PENCIL -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.activateTool(CanvasTool.PAINT_PENCIL); true
					}
					ShortcutAction.TOOL_PAINT_ERASER -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.activateTool(CanvasTool.PAINT_ERASER); true
					}
					ShortcutAction.TOOL_PAINT_BUCKET -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.activateTool(CanvasTool.PAINT_BUCKET); true
					}
					ShortcutAction.TOOL_PAINT_EYEDROPPER -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.activateTool(CanvasTool.PAINT_EYEDROPPER); true
					}
					// The shape chords pick a shape, and with it the shape tool: the three are one tool
					// with three faces, so a chord is enough to start drawing with the face it names.
					ShortcutAction.TOOL_PAINT_LINE -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.selectPaintShape(PaintShape.LINE); true
					}
					ShortcutAction.TOOL_PAINT_RECT -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.selectPaintShape(PaintShape.RECTANGLE); true
					}
					ShortcutAction.TOOL_PAINT_ELLIPSE -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.selectPaintShape(PaintShape.ELLIPSE); true
					}
					ShortcutAction.PAINT_SHAPE_CYCLE -> {
						if (editor.hierarchyMode != EditHierarchyMode.PAINT) editor.setHierarchyMode(EditHierarchyMode.PAINT)
						editor.cyclePaintShape(); true
					}
					// The brush keys drive whichever brush is in hand: the paint tip in paint mode,
					// the deform brush's radius/hardness everywhere else.
					ShortcutAction.BRUSH_RADIUS_DOWN -> {
						if (editor.paintSizeActive) editor.paintSize = (editor.paintSize / 1.2f).coerceAtLeast(1f)
						else editor.radius = (editor.radius / 1.2f).coerceAtLeast(4f)
						true
					}
					ShortcutAction.BRUSH_RADIUS_UP -> {
						if (editor.paintSizeActive) editor.paintSize = (editor.paintSize * 1.2f).coerceAtMost(512f)
						else editor.radius = (editor.radius * 1.2f).coerceAtMost(500f)
						true
					}
					// Never let the deform brush's hardness reach 1.0: brushWeight divides by (1 - hardness).
					ShortcutAction.BRUSH_HARDNESS_DOWN -> {
						if (editor.paintBrushActive) editor.paintHardness = (editor.paintHardness - 0.05f).coerceIn(0f, 1f)
						else editor.hardness = (editor.hardness - 0.05f).coerceIn(0f, 0.95f)
						true
					}
					ShortcutAction.BRUSH_HARDNESS_UP -> {
						if (editor.paintBrushActive) editor.paintHardness = (editor.paintHardness + 0.05f).coerceIn(0f, 1f)
						else editor.hardness = (editor.hardness + 0.05f).coerceIn(0f, 0.95f)
						true
					}
					ShortcutAction.PAINT_OPACITY_DOWN -> {
						if (!editor.paintBrushActive) false
						else {
							editor.paintOpacity = (editor.paintOpacity - 0.05f).coerceIn(0.01f, 1f)
							true
						}
					}
					ShortcutAction.PAINT_OPACITY_UP -> {
						if (!editor.paintBrushActive) false
						else {
							editor.paintOpacity = (editor.paintOpacity + 0.05f).coerceIn(0.01f, 1f)
							true
						}
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
					ShortcutAction.AXIS_CONSTRAIN_X -> {
						// Painting has no axis to constrain, so in paint mode X is what it is in every
						// paint program: the foreground and background colours change places.
						if (editor.hierarchyMode == EditHierarchyMode.PAINT) {
							editor.swapPaintColors(); true
						} else {
							editor.axis = if (editor.axis == "x") null else "x"; true
						}
					}
					ShortcutAction.AXIS_CONSTRAIN_Y -> { editor.axis = if (editor.axis == "y") null else "y"; true }
					else -> false
				}
			}
			.pointerHoverIcon(PointerIcon(when {
                isDragging -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                mode == CanvasMode.EDIT -> editor.activeCursor()
                else -> Cursor.getDefaultCursor()
            }))
			.onPointerEvent(PointerEventType.Press) { event ->
				if (viewModel.state.value.activeCanvas.id != canvasId) {
					viewModel.focusCanvas(canvasId)
				}
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if(change.isConsumed) return@onPointerEvent
                if(mode == CanvasMode.EDIT && previewModel != null) {
                    val p=change.position/density
                    if(p.y<40f || p.y>viewSize.height/density-25f || (p.x<42f && p.y in 40f..460f)) return@onPointerEvent
                }
                focusRequester.requestFocus()
                // Photoshop parity: Alt + right-drag retunes the brush — right/left grows/shrinks the radius,
                // down/up hardens/softens. The Alt state is latched by the editor, so releasing Alt mid-drag
                // neither aborts the gesture nor changes what it is doing.
                if (mode == CanvasMode.EDIT && previewModel != null && event.button == PointerButton.Secondary &&
                    event.keyboardModifiers.isAltPressed && !isDragging && !editor.inGesture && editor.beginBrushAdjust(change.position, event.keyboardModifiers.isShiftPressed)
                ) {
                    showContextMenu = false
                    change.consume(); return@onPointerEvent
                }
                // Plain right-click opens the mode/tool context menu (parameters + topology/paint actions).
                if (mode == CanvasMode.EDIT && previewModel != null && event.button == PointerButton.Secondary &&
                    !event.keyboardModifiers.isAltPressed && !isDragging && !editor.inGesture && !editor.adjustingBrush &&
                    viewModel.swingSession == null && canvasContextMenuHasContent(editor)
                ) {
                    contextMenuOffset = change.position
                    showContextMenu = true
                    change.consume()
                    return@onPointerEvent
                }
                // Middle mouse drag or Space + Left drag -> Canvas Pan
                if (event.button == PointerButton.Tertiary ||
                    (mode == CanvasMode.EDIT && event.button == PointerButton.Primary && editor.space)) {
                    isDragging = true
                    lastDragPos = change.position
                    dragStartPos = change.position
                    change.consume()
                    return@onPointerEvent
                }
                if (mode == CanvasMode.EDIT && previewModel != null && event.button == PointerButton.Primary) {
                    if (showContextMenu) {
                        showContextMenu = false
                        change.consume()
                        return@onPointerEvent
                    }
                    var handled = false
                    editorGuard {
                        handled = editor.press(change.position,computeViewport(previewModel,viewSize.width,viewSize.height),event.keyboardModifiers.isShiftPressed,event.keyboardModifiers.isAltPressed,event.keyboardModifiers.isCtrlPressed)
                    }
                    if (handled) {
                        change.consume(); return@onPointerEvent
                    }
                }
				if (event.button == PointerButton.Primary || event.button == PointerButton.Tertiary) {
					isDragging = true
					lastDragPos = change.position
					dragStartPos = change.position
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
                if (mode == CanvasMode.EDIT && editor.adjustingBrush && event.button == PointerButton.Secondary) {
                    editor.endBrushAdjust(cancel = false)
                    return@onPointerEvent
                }
                if(change?.isConsumed==true && (mode != CanvasMode.EDIT || !editor.inGesture) && !isDragging) return@onPointerEvent
                if(mode == CanvasMode.EDIT && previewModel != null && event.button == PointerButton.Primary && !isDragging) {
                    editorGuard {
                        editor.release()
                        editor.finishSelection(computeViewport(previewModel,viewSize.width,viewSize.height))
                    }
                    return@onPointerEvent
                }
                if (isDragging) {
					isDragging = false
					persistCamera()
					if (mode == CanvasMode.PREVIEW && event.button == PointerButton.Primary && change != null && (change.position - dragStartPos).getDistance() < 6f) {
						if (canvasState.clickToSelectLayer && previewModel != null) {
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
								visibleLayerIds = canvasState.effectiveVisibleLayerIds,
								currentSelectedLayerId = canvasState.selectedLayerId,
								geometry = geometry,
								drawOrderOverrides = canvasState.drawOrderOverrides,
							)
							viewModel.updateCanvasPresentation(canvasState.activeWorkspace.id, canvasId, CanvasMode.PREVIEW) {
								it.copy(selectedLayerId = hit, selectedDeformerId = if (hit != null) null else it.selectedDeformerId)
							}
						}
					}
				}
			}
			.onPointerEvent(PointerEventType.Exit) {
                // Releasing the right button outside the window may never route a Release back here.
				if (mode == CanvasMode.EDIT && editor.adjustingBrush) editor.endBrushAdjust(cancel = false)
				if (mode == CanvasMode.EDIT) editor.clearHover()
                if (mode == CanvasMode.PREVIEW) {
					viewModel.clearPointer(renderKey)
					// One last frame puts the pose back to neutral now that the look is gone.
					notePointerActivity()
				}
			}
			.onPointerEvent(PointerEventType.Move) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
                if (mode == CanvasMode.EDIT && previewModel != null && !isDragging) {
                    // The brush gesture takes over the pointer: skipping move() here is what keeps the outline
                    // parked at the press point, so the viewport stops feeding hover updates for the duration.
                    editorGuard {
                        if (editor.adjustingBrush) editor.updateBrushAdjust(change.position)
                        else editor.move(
                            change.position,
                            computeViewport(previewModel, viewSize.width, viewSize.height),
                            event.keyboardModifiers.isShiftPressed,
                            event.keyboardModifiers.isAltPressed,
                            event.keyboardModifiers.isCtrlPressed
                        )
                    }
                }
                if (isDragging) {
					val delta = change.position - lastDragPos
					if (delta != Offset.Zero) {
						panX += delta.x
						panY += delta.y
                        cameraDirty = true
                    }
					lastDragPos = change.position
				}
				if (!isDragging && mode == CanvasMode.PREVIEW && canvasState.mouseTrackingEnabled) {
					val normX = ((change.position.x - viewSize.width * 0.5f) / (viewSize.width * 0.5f).coerceAtLeast(1f)).coerceIn(-1f, 1f)
					val normY = ((change.position.y - viewSize.height * 0.5f) / (viewSize.height * 0.5f).coerceAtLeast(1f)).coerceIn(-1f, 1f)
					viewModel.updatePointer(normX, normY, renderKey)
					notePointerActivity()
				}
			}
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				if (mode == CanvasMode.EDIT && editor.tool == CanvasTool.CREATE_WARP && !editor.warpCreateParentIsWarp()) {
					val delta = if (change.scrollDelta.y > 0) -1 else 1
                    editor.warpCreateGridRows = (editor.warpCreateGridRows + delta).coerceIn(2, 20)
                    editor.warpCreateGridCols = (editor.warpCreateGridCols + delta).coerceIn(2, 20)
					change.consume()
					return@onPointerEvent
				}
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
			// Same placement read as the mesh overlay, so both pictures invalidate together.
			canvasOrigin.x
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
			// The per-tab "Deformer Warp" option is authoritative here: an Edit tab shows the rig
			// guides because its default enables them, not because the mode forces them on. Which warps
			// that works out to is the editor's answer, asked for once and used for both the native-frame
			// choice below and the channel itself, so the two cannot disagree about whether anything is
			// being drawn.
			val informationNames = viewOptions.warpShowNames
			val informationIndices = viewOptions.warpShowIndices
			val informationSelectedOnly = filterSelectedOnly

			val targetVisibleLayerIds: Set<String> = when {
				!informationSelectedOnly -> canvasState.effectiveVisibleLayerIds
				canvasState.selectedLayerId != null -> {
					val keep = if (mode == CanvasMode.EDIT && editor.hierarchyMode == EditHierarchyMode.EDIT && editor.objects.size > 1) {
						editor.objects
					} else {
						setOf(canvasState.selectedLayerId)
					}
					canvasState.effectiveVisibleLayerIds.filter { it in keep }.toSet()
				}
				canvasState.selectedDeformerId != null -> {
					val desc = descendantLayerIds(model, canvasState.selectedDeformerId, canvasState.parentOverrides)
					canvasState.effectiveVisibleLayerIds.filter { it in desc }.toSet()
				}
				else -> canvasState.effectiveVisibleLayerIds
			}

			val hasActiveSelection = canvasState.selectedLayerId != null || canvasState.selectedDeformerId != null
			val highlightedLayerIds: Set<String>? = when {
                mode==CanvasMode.EDIT && editor.objectMode && editor.objects.isNotEmpty() -> editor.objects
				mode == CanvasMode.EDIT && editor.hierarchyMode == EditHierarchyMode.EDIT && editor.objects.size > 1 -> editor.objects
				canvasState.selectedLayerId != null -> setOf(canvasState.selectedLayerId)
				canvasState.selectedDeformerId != null -> descendantLayerIds(model, canvasState.selectedDeformerId, canvasState.parentOverrides)
				else -> null
			}
			val isDimmingActive = dimUnselected && hasActiveSelection

			// Hover annotation: a wash over the artwork in the component's own colour, not a box around
			// it. A deformer owns no texture of its own, so previewing one lights up everything it
			// deforms — which is exactly what the deformer is.
			val hoveredLayerId = canvasState.hoveredLayerId
			val hoveredDeformerId = canvasState.hoveredDeformerId
			val hoverTintLayerIds = when {
				hoveredLayerId != null -> setOf(hoveredLayerId)
				hoveredDeformerId != null -> descendantLayerIds(model, hoveredDeformerId, canvasState.parentOverrides)
				else -> null
			}
			val hoverTintColor = (hoveredLayerId ?: hoveredDeformerId)?.let { ComponentPalette.strong(it).rgb } ?: 0

			val nativeFrame = sdkFrame
			// Path guides never paint outside the Edit tab (see 3e), so they cannot force the
			// preview off its native SDK frame.
			val canUseNativeSdk = mode == CanvasMode.PREVIEW &&
                canvasState.layerVisibility.isEmpty() && canvasState.deformerVisibility.isEmpty() &&
				warpIds.isEmpty() && rotationIds.isEmpty() && !showMesh && !informationSelectedOnly && showTexture &&
				!isDimmingActive &&
				canvasState.hoveredLayerId == null && canvasState.hoveredDeformerId == null &&
				(!showSelectionBounds || !hasActiveSelection) &&
				canvasState.drawOrderOverrides.isEmpty() &&
				nativeFrame != null && sdkBitmap != null &&
				nativeFrame.image.width == w && nativeFrame.image.height == h

			val currentSdkBitmap = sdkBitmap
			if (canUseNativeSdk) {
				// Native rendering can finish several frames after a pan. Reproject its last
				// image immediately so the visible artwork follows the local camera while
				// the latest native frame is still in flight.
				val frameCamera = nativeFrame
				val desiredCamera = computeCubismViewport(model, w, h, zoom, panX, panY)
				val ratio = (desiredCamera.scale / frameCamera.cameraScale)
					.takeIf { it.isFinite() && it > 0f } ?: 1f
				val halfWidth = w * 0.5f
				val halfHeight = h * 0.5f
				val left = halfWidth * (1f - ratio + desiredCamera.offsetX - ratio * frameCamera.cameraOffsetX)
				val top = halfHeight * (1f - ratio - desiredCamera.offsetY + ratio * frameCamera.cameraOffsetY)
				if (ratio == 1f && left == 0f && top == 0f) drawImage(currentSdkBitmap)
				else withTransform({
					translate(left, top)
					scale(ratio, ratio, pivot = Offset.Zero)
				}) { drawImage(currentSdkBitmap) }
			} else {
				// Paint is an isolated document-canvas session: its live tiles belong only on the Edit
				// tab, and only until Apply writes them into RigPreviewModel. The Preview tab always
				// keeps showing the last committed atlas, never an in-progress stroke.
				// Document-space paint tiles only line up with the mesh at rest; driving the other
				// layers with the live pose would leave the stroke floating off the art.
				val geometry = editGeometry ?: RigCanvasSupport.evaluate(model, informationPose)

					// 3a. Texture Channel. The artwork always renders opaque; legibility of the
					// overlays comes from the focus/dim options instead of a global transparency.
					if (showTexture) {
						val paintLayerId = paintSession?.layerId
						val effectiveVisible = if (paintLayerId != null) {
							targetVisibleLayerIds - setOf(paintLayerId)
						} else {
							targetVisibleLayerIds
						}
						if (editingPainter != null) drawIntoCanvas { target ->
							val key = listOf(
								editingPainter, model.rig.puppet, geometry, viewport, w, h,
								effectiveVisible, canvasState.drawOrderOverrides, dimUnselected,
								highlightedLayerIds, hoverTintLayerIds, hoverTintColor,
							)
							artworkCache.draw(target.skiaCanvas, key, w, h) { recording ->
								editingPainter.paint(
									recording, model, geometry, viewport, 1.0f,
									visibleLayerIds = effectiveVisible,
									drawOrderOverrides = canvasState.drawOrderOverrides,
									dimUnselected = dimUnselected,
									highlightedLayerIds = highlightedLayerIds,
									dimmedAlphaMultiplier = 0.22f,
									tintLayerIds = hoverTintLayerIds,
									tintColor = hoverTintColor,
								)
							}
						}
					}

				val guideKey = listOf(
					model, geometryPose, editGeometry, informationPose, viewport, w, h,
					viewOptions, warpIds, rotationIds, warpPoints, targetVisibleLayerIds,
					canvasState.selectedLayerId, canvasState.selectedDeformerId,
					canvasState.hoveredLayerId, canvasState.hoveredDeformerId,
					canvasState.parentOverrides, editor.hierarchyMode, editor.objects,
					editor.glueSwapped, editor.drawsTransformBox,
				)
				val guideImage = guideCache.imageFor(guideKey, w, h) { g ->
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

					// 3b. Mesh Channel (Wireframe)
					// Outside SELECT mode, mesh wires are focus chrome for the active artmesh only —
					// every other part stays texture-only so the canvas stays readable while editing.
					// In SELECT (object) mode every mesh is drawn faded; only the selection is crisp.
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

						val selectedId = canvasState.selectedLayerId
						val meshFocusOnly = mode == CanvasMode.EDIT && !editor.objectMode
						if (meshFocusOnly) {
							// Edit mode draws its meshes on the editor overlay - every edited mesh in one style,
							// glued points merged - so the guide adds nothing there. Deform keeps the active mesh.
							if (selectedId != null && editor.hierarchyMode != EditHierarchyMode.EDIT) {
								for (drawable in model.rig.puppet.drawables) {
									val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
									if (layerId == selectedId) {
										drawMeshWireframe(drawable, selected = true, dimmed = false)
									}
								}
							}
						} else {
							// Object mode: every mesh stays faded until it is in the selection;
							// selected wires (and vertex dots for the primary) draw at full strength.
							val objectModeMeshes = mode == CanvasMode.EDIT && editor.objectMode
							for (drawable in model.rig.puppet.drawables) {
								val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
								if (layerId != selectedId) {
									val inFocus = highlightedLayerIds != null && layerId != null && layerId in highlightedLayerIds
									val isDimmed = when {
										objectModeMeshes -> !inFocus
										else -> isDimmingActive && !inFocus
									}
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
					}

					// 3c. Rotation Channel (RigInformationOverlay). Same ownership as warps: the
					// editor decides which rotations show via showRotation; the Compose overlay draws
					// the interactive needle for the edit target when that toggle is on.
					val drawableBounds = RigCanvasSupport.boundsByDrawable(geometry)
					val deformerBounds = RigCanvasSupport.boundsByDeformer(model, drawableBounds)
					val deformEditTarget = canvasState.selectedDeformerId?.takeIf {
						mode == CanvasMode.EDIT && showRotation && (
							editor.hierarchyMode == EditHierarchyMode.DEFORM ||
								editor.hierarchyMode == EditHierarchyMode.EDIT
							)
					}
					val globalRotationIds = when {
						mode != CanvasMode.EDIT -> emptySet()
						deformEditTarget != null -> rotationIds - deformEditTarget
						else -> rotationIds
					}
					if (globalRotationIds.isNotEmpty()) {
						io.github.psd2live.ui.RigInformationOverlay.paintRotations(
							g, model.rig.puppet,
							if (mode == CanvasMode.PREVIEW) informationPose else canvasState.parameterValues,
							viewport, globalRotationIds,
							labels = informationNames,
							selectedDeformerId = canvasState.selectedDeformerId,
							hoveredDeformerId = canvasState.hoveredDeformerId,
							dimUnselected = dimUnselected,
						)
					}

					// Global Selection Bounding Box (across all modes if enabled).
					// The transform tool draws its own box around `editor.objects`, which is not the
					// same set as the hierarchy selection — a multi-object pick frames several layers
					// while this one frames a single layer — so letting both draw stacks two different
					// rectangles over the same artwork. The transform box wins: it is the one that is
					// dragged. Every other tool leaves this as the only selection feedback.
					val transformBoxOwnsSelection = mode == CanvasMode.EDIT && editor.drawsTransformBox
					if (showSelectionBounds && !transformBoxOwnsSelection) {
						canvasState.selectedLayerId?.let { layerId ->
							val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
							val bounds = drawableId?.let(drawableBounds::get)
							if (bounds != null) {
								val selColor = ComponentPalette.strong(layerId).brighter()
								RigCanvasSupport.paintSelectionBounds(g, bounds, viewport, selColor, stroke = 2.0f, isDashed = false)
							}
						}
						if (mode != CanvasMode.EDIT) {
							canvasState.selectedDeformerId?.let { defId ->
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

					// Hover has no box here on purpose. It is a wash over the part's own texture (see
					// hoverTintLayerIds above), which reads as the part lighting up instead of a
					// rectangle laid over the rig — and for a deformer, its bounds are the union of
					// everything beneath it, which would box far more than the pointer is on.

					// 3d. Warp Channel (RigInformationOverlay). Which warps show is the editor's answer,
					// not this file's: the editor is what picks their corner marks, and were the two to
					// work it out separately a mark could outlive the deformer it belongs to — which is
					// exactly what it used to do.
					if (warpIds.isNotEmpty()) {
						io.github.psd2live.ui.RigInformationOverlay.paint(
							g, model.rig.puppet,
							if (mode == CanvasMode.PREVIEW) informationPose else canvasState.parameterValues,
							viewport, warpIds,
							labels = informationNames,
							pointIndices = informationIndices,
							selectedDeformerId = canvasState.selectedDeformerId,
							hoveredDeformerId = canvasState.hoveredDeformerId,
							dimUnselected = dimUnselected,
							pointsById = warpPoints,
						)
					}

					// 3e. Deform Paths (RigInformationOverlay). A path belongs to the part it
					// deforms, so it is drawn only while that part (or the part's deformer) is
					// selected -- an edit-time guide, never part of the Preview tab's render.
					if (mode == CanvasMode.EDIT && showDeformPaths && model.rig.puppet.deformPaths.isNotEmpty()) {
						val selectedLayerDescendants = if (canvasState.selectedDeformerId != null) {
							descendantLayerIds(model, canvasState.selectedDeformerId, canvasState.parentOverrides)
						} else {
							emptySet()
						}
						val selectedPathIds = model.rig.puppet.deformPaths.filter { path ->
							val layerId = model.rig.layerIdByDrawableId[path.drawableId.raw]
							(canvasState.selectedLayerId != null && layerId == canvasState.selectedLayerId) ||
								(canvasState.selectedDeformerId != null && layerId != null && layerId in selectedLayerDescendants)
						}.map { it.id }.toSet()

						val hoveredPathIds = model.rig.puppet.deformPaths.filter { path ->
							val layerId = model.rig.layerIdByDrawableId[path.drawableId.raw]
							canvasState.hoveredLayerId != null && layerId == canvasState.hoveredLayerId
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
								showWidth = canvasState.pathShowWidth,
								showHardness = canvasState.pathShowHardness,
								selectedPathIds = selectedPathIds,
								hoveredPathIds = hoveredPathIds,
							)
						}
					}
				}
				drawImage(guideImage)
				// Session tiles sit above the mesh overlays and never write into RigPreviewModel —
				// Apply (commitPaintSession) is what publishes them to the shared preview.
				if (showTexture && paintSession != null) {
					val scale = viewport.scale
					for (tile in paintSession.previewTiles) {
						val left = Math.round(viewport.offsetX + tile.x * scale)
						val top = Math.round(viewport.offsetY + tile.y * scale)
						val right = Math.round(viewport.offsetX + (tile.x + tile.width) * scale)
						val bottom = Math.round(viewport.offsetY + (tile.y + tile.height) * scale)
						drawImage(
							image = tile.image,
							dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
							dstSize = androidx.compose.ui.unit.IntSize(
								(right - left).toInt().coerceAtLeast(1),
								(bottom - top).toInt().coerceAtLeast(1)
							),
							filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
						)
					}
				}
			}
		}

		if(mode == CanvasMode.EDIT && previewModel != null) {
            val vp = viewportFor(viewSize)
            editor.viewport = vp
            CanvasEditorOverlay(
                editor = editor,
                viewport = vp,
                viewportFor = viewportFor,
                placementOrigin = canvasOrigin,
                viewModel = viewModel,
                keymap = canvasState.keymap,
                selectedLayerId = canvasState.selectedLayerId,
                selectedLayerIds = canvasState.selectedLayerIds,
                selectedDeformerId = canvasState.selectedDeformerId,
                showMesh = showMesh,
                showRotation = showRotation,
            ) { focusRequester.requestFocus() }
            CanvasContextMenu(
                editor = editor,
                expanded = showContextMenu,
                clickOffset = contextMenuOffset,
                onDismissRequest = { showContextMenu = false },
                onAction = { focusRequester.requestFocus() },
            )
        }
		if (mode == CanvasMode.PREVIEW && previewModel != null) {
			CanvasPreviewToolbar(
				animationEnabled = canvasState.animationEnabled,
				mouseTrackingEnabled = canvasState.mouseTrackingEnabled,
				enabled = true,
				onToggleAnimation = { viewModel.updateCanvasPresentation(canvasState.activeWorkspace.id, canvasId, CanvasMode.PREVIEW) { it.copy(animationEnabled = !it.animationEnabled) } },
				onToggleMouseTracking = { viewModel.updateCanvasPresentation(canvasState.activeWorkspace.id, canvasId, CanvasMode.PREVIEW) { it.copy(mouseTrackingEnabled = !it.mouseTrackingEnabled) } },
			)
		}
        // Overlay: Empty hint or Stats Badge
		if (previewModel == null) {
			EmptyCanvasStart(
				recentPaths = canvasState.recentFiles,
				enabled = !canvasState.isBusy,
				openProjectShortcut = canvasState.keymap.labelFor(ShortcutAction.OPEN_PROJECT),
				openPsdShortcut = canvasState.keymap.labelFor(ShortcutAction.OPEN_PSD),
				onStartTutorial = onStartTutorial,
				onOpenProject = onOpenProject,
				onOpenPsd = onOpenPsd,
				onOpenRecent = viewModel::openRecentFile,
				modifier = Modifier.align(Alignment.Center).fillMaxSize(),
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
					canvasState.sdkStatus != null && canvasState.sdkStatus != "ready" -> "${fpsStr}${tr("canvas.preview.softwareFallback", zoomPct)}"
					previewModel.hasRuntimePhysics -> "${fpsStr}${tr("canvas.preview.physicsOn", zoomPct)}"
					else -> "${fpsStr}${tr("canvas.preview.physicsOff", zoomPct)}"
				}
				CanvasMode.EDIT -> if (showMesh) {
					val vertexCount = previewModel.rig.puppet.drawables.sumOf { it.mesh?.vertexCount ?: 0 }
					val triangleCount = previewModel.rig.puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }
					tr("canvas.mesh.stats", previewModel.rig.puppet.drawables.size, vertexCount, triangleCount, zoomPct)
				} else {
					"$zoomPct%"
				}
			}

			// Bottom-left: zoom/FPS/physics stats pill.
			if (badgeText.isNotEmpty() && (mode != CanvasMode.EDIT || (editor.skeletonDraft == null && editor.placement == null))) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(start = 8.dp, bottom = 8.dp)
						.frostedGlass(
							shape = RoundedCornerShape(6.dp),
							isHovered = false,
							elevation = 2.dp,
							alpha = 0.78f,
						)
						.padding(horizontal = 8.dp, vertical = 4.dp),
				) {
					Text(
						text = badgeText,
						style = typography.caption.copy(fontSize = 11.sp),
						color = colors.textPrimary,
					)
				}
			}
			// Bottom-right: display-toggle rail (mirrors the left tool palette).
			CanvasViewOptionsBar(
				options = viewOptions,
				onOptionsChange = { viewModel.setCanvasViewOptions(canvasId, it, mode) },
				showPathGuides = mode == CanvasMode.EDIT,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = 8.dp, bottom = 8.dp),
			)
		}
	}
    }
}

/** The Java2D guide pass is rebuilt only when this canvas's visible inputs change. */
private class CanvasGuideImageCache {
    private var key: List<Any?>? = null
    private var image: ImageBitmap? = null

    fun imageFor(key: List<Any?>, width: Int, height: Int, paint: (Graphics2D) -> Unit): ImageBitmap {
        if (image == null || this.key != key) {
            val buffer = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = buffer.createGraphics()
            try {
                paint(graphics)
                image = buffer.toComposeImageBitmap()
                this.key = key
            } catch (_: Exception) {
                // A bad guide frame must not escape into composition and stop this canvas.
            } finally {
                graphics.dispose()
            }
            return image ?: buffer.toComposeImageBitmap()
        }
        return requireNotNull(image)
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


