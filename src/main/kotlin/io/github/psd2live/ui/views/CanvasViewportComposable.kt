package io.github.psd2live.ui.views

import io.github.psd2live.ui.SELECTION_TOOLS

import io.github.psd2live.core.RigInformationOverlay

import io.github.psd2live.ui.PanShift
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import io.github.psd2live.render.ArtworkDrawList
import io.github.psd2live.render.ArtworkOptions
import io.github.psd2live.render.CanvasRenderService
import io.github.psd2live.render.CanvasScene
import io.github.psd2live.render.MeshWireframe
import io.github.psd2live.render.OverlayItem
import io.github.psd2live.render.OverlayScene
import io.github.psd2live.render.RigGuides
import io.github.psd2live.render.WireItem
import io.github.psd2live.ui.state.AppSettings
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.psd2live.ui.utils.toImageBitmapFast
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
import androidx.compose.ui.graphics.BlendMode
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
import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.ComponentPalette
import io.github.psd2live.core.CubismViewport
import io.github.psd2live.core.RigCanvasSupport
import io.github.psd2live.ui.CachedSkiaPicture
import io.github.psd2live.ui.SkiaRigPainter
import io.github.psd2live.ui.SourcePixelImages
import io.github.psd2live.ui.visibleCanvasGuideIds
import io.github.psd2live.ui.state.forCanvas
import io.github.psd2live.ui.state.FramePacer
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.state.previewValues
import kotlinx.coroutines.delay
import io.github.psd2live.ui.state.frameIntervalNanos
import io.github.psd2live.ui.state.CanvasBackgroundKind
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PRIMARY_CANVAS_ID
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.ShortcutAction
import io.github.psd2live.ui.state.ShortcutScope
import io.github.psd2live.ui.state.MouseInput
import io.github.psd2live.ui.state.buttonBindingOf
import io.github.psd2live.ui.state.wheelBindingOf
import io.github.psd2live.ui.state.TabViewOptions
import io.github.psd2live.ui.state.TabCamera
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.tutorialTarget
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
/** A restarted paused-physics pump renders at least this long before it may sleep on a settled model. */
private const val PAUSED_PHYSICS_WARMUP_NANOS = 400_000_000L
/** How often a paused-physics pump at rest looks for a new pose or a swing. */
private const val PAUSED_PHYSICS_POLL_MILLIS = 16L

/** Pointer travel, in dp, that a zoom drag counts as one wheel notch. */
private const val ZOOM_DRAG_NOTCH_PX = 24f

/** The tools whose tip has an angle a wheel notch can turn. */
private val ANGLED_BRUSH_TOOLS = setOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)

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
	onOpenTutorialCatalog: (() -> Unit)? = null,
	onStartTutorial: ((TutorialId) -> Unit)? = null,
	onOpenProject: (() -> Unit)? = null,
	onOpenPsd: (() -> Unit)? = null,
) {
    val editor = viewModel.canvasEditorFor(canvasId)
    val ownerState = state.forCanvas(canvasId)
    var temporarySelectKey by remember(canvasId, ownerState.activeWorkspace.id) { mutableStateOf<Key?>(null) }
    // Not keyed on [mode]: a switch between editing and preview keeps this viewport, its measured size, camera,
    // painter and caches, and the state below follows the mode instead of being built again.
    key(ownerState.projectOpenGeneration, ownerState.activeWorkspace.id, canvasId) {
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

	// Zero until the first layout: nothing is framed, hit-tested or rendered against a guessed size.
	var viewSize by remember { mutableStateOf(IntSize.Zero) }
	val sized = viewSize.width > 0 && viewSize.height > 0
	// Window position of this canvas. Dock toggles move the canvas without changing zoom or pan;
	// the mesh overlay has to redraw on that move or its picture stays at the old place.
	var canvasOrigin by remember { mutableStateOf(Offset.Zero) }
	val showMesh = viewOptions.showMesh
	val showTexture = viewOptions.showTexture
	val showRotation = viewOptions.showRotation
	val showDeformPaths = viewOptions.showDeformPaths
	// Preview is view-only: selection, hover focus and selection-gated overlays stay on Edit.
	val allowSelectionChrome = mode == CanvasMode.EDIT
	val showSelectionBounds = allowSelectionChrome && viewOptions.showSelectionBounds &&
        !(editor.objectMode && editor.tool in SELECTION_TOOLS)
	val dimUnselected = allowSelectionChrome && viewOptions.dimUnselected
	val filterSelectedOnly = allowSelectionChrome && viewOptions.filterSelectedOnly
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
	// Set while a camera drag zooms (ShortcutAction.ZOOM_DRAG) rather than pans: the point it zooms about.
	var zoomDragAnchor by remember { mutableStateOf<Offset?>(null) }
	// The buttons that started a brush adjustment and a held temporary selection, so their own release ends them.
	var brushAdjustButton by remember { mutableStateOf<PointerButton?>(null) }
	var temporarySelectButton by remember { mutableStateOf<PointerButton?>(null) }
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
            // Interaction state of the mode being left; the viewport itself stays.
            showContextMenu = false
            brushAdjustButton = null
            temporarySelectButton = null
            zoomDragAnchor = null
            isDragging = false
            if (mode == CanvasMode.EDIT) {
                editor.endTemporarySelection()
                temporarySelectKey = null
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
        if (canvasState.keyCapture != null || canvasState.showSettingsDialog || canvasState.showExportDialog) {
            editor.endTemporarySelection()
            temporarySelectKey = null
        }
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
	// The edit canvas follows the same pose the sliders show, frame by frame while a motion plays. A preview canvas
	// renders its own frames and does not recompose for this.
	val livePose = if (mode == CanvasMode.EDIT) viewModel.livePose.collectAsState().value else emptyMap()
	val geometryPose = if (paintSession != null) emptyMap<org.umamo.runtime.model.ParameterId, Float>()
		else viewModel.canvasPose(canvasState, livePose)
	val editGeometry = remember(previewModel, geometryPose, mode) {
		if (mode == CanvasMode.EDIT && previewModel != null) RigCanvasSupport.evaluate(previewModel, geometryPose)
		else null
	}
	val snapshotPreview = viewModel.parameterSnapshotPreviewFor(canvasId)
	val snapshotGeometry = remember(previewModel, snapshotPreview) {
		if (previewModel != null && snapshotPreview != null)
			RigCanvasSupport.evaluate(previewModel, snapshotPreview.previewValues(previewModel.rig.puppet.parameters))
		else null
	}
	val guideState = if (allowSelectionChrome) canvasState else canvasState.copy(
		selectedLayerId = null,
		selectedLayerIds = emptySet(),
		selectedDeformerId = null,
		hoveredLayerId = null,
		hoveredDeformerId = null,
	)
	val warpIds = if (previewModel == null) emptySet() else if (mode == CanvasMode.EDIT) editor.activeWarpIds()
		else visibleCanvasGuideIds(previewModel, guideState, warp = true)
	val rotationIds = if (previewModel == null) emptySet() else if (mode == CanvasMode.EDIT) editor.activeRotationIds()
		else visibleCanvasGuideIds(previewModel, guideState, warp = false)
	val viewportFor = remember(previewModel, zoom, panX, panY) {
		val model = previewModel
		{ drawSize: IntSize ->
			if (model == null) CanvasViewport(1.0, 0.0, 0.0, 1f, 1f)
			else computeEditorViewport(model, drawSize, zoom, panX, panY)
		}
	}
	val editingPainter = remember(previewModel?.atlas) { previewModel?.atlas?.let(::SkiaRigPainter) }
	DisposableEffect(editingPainter) { onDispose { editingPainter?.close() } }
	// Source pixels: the edit canvas samples each layer's raster instead of its atlas tile. Only the software
	// painter can, so such a canvas skips the GPU renderer while the option is on.
	val sourcePixels = mode == CanvasMode.EDIT && viewOptions.sourcePixels
	val sourceImages = remember(previewModel, sourcePixels) { if (sourcePixels) previewModel?.let(::SourcePixelImages) else null }
	DisposableEffect(sourceImages) { onDispose { sourceImages?.close() } }
	val artworkCache = remember { CachedSkiaPicture() }
	DisposableEffect(artworkCache) { onDispose { artworkCache.close() } }
	val snapshotArtworkCache = remember { CachedSkiaPicture() }
	DisposableEffect(snapshotArtworkCache) { onDispose { snapshotArtworkCache.close() } }
	val guideCache = remember { CanvasGuideImageCache() }
	// The GPU renderer draws this canvas's artwork when it can; the Skia painter above stays the fallback.
	val softwareCanvas by AppSettings.softwareCanvasFlow.collectAsState()
	LaunchedEffect(softwareCanvas) { if (!softwareCanvas) CanvasRenderService.ensureStarted() }
	val gpuStatus by CanvasRenderService.status.collectAsState()
	val gpuReady = !softwareCanvas && !sourcePixels && gpuStatus is CanvasRenderService.Status.Ready
	// One GPU view per canvas whatever its mode: a switch keeps its meshes and page textures on the GPU.
	val gpuKey = viewModel.canvasRenderKey(canvasId, CanvasMode.EDIT)
	val gpuFrame by remember(gpuKey) { CanvasRenderService.frames(gpuKey) }.collectAsState()
	val gpuImage = remember(gpuFrame) { gpuFrame?.bitmap?.asComposeImageBitmap() }
	val gpuSubmission = remember(gpuKey) { GpuSceneSubmission(gpuKey) }
	// The last snapshot ghost's geometry: its frame must not show at full strength once the hover has moved on.
	val ghostGeometry = remember(gpuKey) { arrayOfNulls<org.umamo.render.eval.DeformedGeometry>(1) }
	// The last regular frame and its image, shown while a ghost frame is the newest one.
	val lastArtwork = remember(gpuKey) { arrayOfNulls<Pair<io.github.psd2live.render.RenderedFrame, ImageBitmap>>(1) }
	// The paint session this view's GPU texture holds in full; another session, or a new view, uploads all of it.
	val paintUploaded = remember(gpuKey) { arrayOfNulls<Any>(1) }
	DisposableEffect(gpuKey) { onDispose { CanvasRenderService.release(gpuKey) } }
	val drawnGeometry = remember { DrawnGeometryMemo() }
	// A session shown by the GPU hands it changed areas instead of painting preview tiles.
	LaunchedEffect(paintSession, gpuReady) { paintSession?.gpuPreview = gpuReady }
	// Brushes start at a size that suits the open document, and keep their share of it across documents.
	val documentLongSide = editor.documentLongSide
	LaunchedEffect(editor, documentLongSide) { editor.fitBrushesToDocument() }
	val guideLabelMeasurer = rememberTextMeasurer(cacheSize = 128)
	val guideLabels = remember { GuideLabelMemo() }
	val sdkFrame by frameFlow.collectAsState()
	val simulatedFrame by viewModel.simulationFrames.collectAsState()
	// The live simulation replaces its meshes' vertices, which only the software painter can draw.
	val simulated = simulatedFrame?.takeIf { mode == CanvasMode.PREVIEW && it.simulationId == canvasState.simulationPreviewId }
	val sdkBitmap = remember(sdkFrame?.image) { sdkFrame?.image?.toImageBitmapFast() }
	val background = canvasState.canvasBackground
	val checkerLight = background.checkerLight?.let(::opaqueColor) ?: colors.checkerLight
	val checkerDark = background.checkerDark?.let(::opaqueColor) ?: colors.checkerDark
	val checkerboardBrush = remember(checkerLight, checkerDark, background.checkerSize) {
		createCheckerboardBrush(checkerLight, checkerDark, background.checkerSize)
	}
	val solidBackground = background.solidColor?.let(::opaqueColor) ?: colors.checkerDark
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
				io.github.psd2live.core.RigInformationOverlay.warpPoints(previewModel.rig.puppet, warpPose, warpIds)
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

    // The document learns the camera once the wheel settles; a pan still hands it over on release.
    // Until then the view model knows the camera too, so a mode or workspace switch carries it.
    LaunchedEffect(zoom, panX, panY, cameraDirty, isDragging) {
        if (cameraDirty) viewModel.notePendingCanvasCamera(canvasState.projectOpenGeneration, canvasState.activeWorkspace.id,
            canvasId, TabCamera(zoom.toFloat(), panX.toFloat(), panY.toFloat()))
        if (cameraDirty && !isDragging) {
            delay(CAMERA_PERSIST_DELAY_MILLIS)
            persistCamera()
        }
    }

    // A workspace switch can remove this viewport before it receives Release.
    DisposableEffect(viewModel, canvasId, canvasState.projectOpenGeneration, canvasState.activeWorkspace.id) {
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
		val nextZoom = CanvasNavigation.wheelZoom(zoom, wheelDelta)
		if (nextZoom == zoom) return
		zoom = nextZoom
		val centered = computeViewport(model, viewSize.width, viewSize.height)
		panX += mouseX - (centered.offsetX + canvasX * centered.scale)
		panY += mouseY - (centered.offsetY + canvasY * centered.scale)
		// Kept local while the wheel turns, as a pan is while it drags: writing the camera into the document on
		// every notch recomposes every dock panel (the hierarchy tree most of all) once per notch.
		cameraDirty = true
	}

	// Vsync-driven frame pump. Cubism conflates requests while busy, so the newest
	// parameters are rendered next without building latency in a callback queue.
    // Animated previews read the latest camera each frame. Restarting their pump for
    // every pointer move stalls rendering and makes panning visibly trail the cursor.
    // A live simulation needs frames like physics does, paused or not.
    val physicsLive = (canvasState.generatePhysics && !canvasState.meshOnly) || canvasState.simulationPreviewId != null
    // A continuous pump (playing, or paused physics) reads the latest camera and pose each frame. Restarting
    // it for every pan step or slider sample re-requests a frame out of pace and makes a drag stutter.
    val parameterScrubActive = viewModel.parameterScrubActive
    val continuousPump = canvasState.animationEnabled || physicsLive || parameterScrubActive
    val pausedCameraKey = if (continuousPump) Unit else Triple(zoom, panX, panY)
    val pausedPoseKey = if (continuousPump) Unit else canvasState.parameterValues
	// The project's frame rate paces the pump; unlimited follows the display. Native Cubism renders every
	// preview on one GL thread, so several visible previews never go past 30 FPS each.
	val projectFps = canvasState.rigEdits.physicsFps
	val pumpInterval = maxOf(frameIntervalNanos(projectFps), if (simultaneousPreviews > 1) 33_333_333L else 0L)
	LaunchedEffect(renderKey, mode, previewModel, canvasState.animationEnabled, canvasState.mouseTrackingEnabled, viewSize, pausedCameraKey, pausedPoseKey, pumpInterval, physicsLive, parameterScrubActive) {
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
				if (canvasState.animationEnabled || (parameterScrubActive && !physicsLive)) {
					var previousFrameNanos = 0L
					val pacer = FramePacer(pumpInterval)
					while (isActive) {
						val frameNanos = withFrameNanos { it }
						if (!pacer.due(frameNanos)) continue
						val deltaTime = if (previousFrameNanos == 0L) {
							1f / 60f
						} else {
							((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
						}
						previousFrameNanos = frameNanos
						requestFrame(if (canvasState.animationEnabled) deltaTime else 0f, frameNanos)
					}
				} else if (physicsLive) {
					// Paused with physics on: the pose the user sets swings it, so render until it comes to rest.
					// At rest the motion clock keeps stepping physics without frames; a slider edit or the pointer
					// moves it again, and this wakes on that without rendering in between.
					val started = System.nanoTime()
					val pacer = FramePacer(pumpInterval)
					var lastPose = viewModel.state.value.previewPanelState().parameterValues
					while (isActive) {
						while (isActive && !viewModel.parameterScrubActive && System.nanoTime() - started > PAUSED_PHYSICS_WARMUP_NANOS && viewModel.previewSettled &&
							System.nanoTime() - lastPointerActivityNanos.get() > PAUSED_TRACKING_SETTLE_NANOS) {
							val pose = viewModel.state.value.previewPanelState().parameterValues
							if (pose != lastPose) { lastPose = pose; break }
							delay(PAUSED_PHYSICS_POLL_MILLIS)
						}
						val frameNanos = withFrameNanos { it }
						if (!pacer.due(frameNanos)) continue
						requestFrame(0f, frameNanos)
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
						val pacer = FramePacer(pumpInterval)
						while (isActive &&
							System.nanoTime() - lastPointerActivityNanos.get() <= PAUSED_TRACKING_SETTLE_NANOS
						) {
							val frameNanos = withFrameNanos { it }
							if (!pacer.due(frameNanos)) continue
							requestFrame(0f, frameNanos)
						}
					}
				}
			}
		}
	}

	// One canvas command, from a key press, a wheel notch ([wheel]) or a button click. False lets the input
	// through: a key to the views behind the canvas, a wheel notch to the zoom.
	fun runCanvasCommand(action: ShortcutAction, shift: Boolean, wheel: Boolean): Boolean {
		if (action == ShortcutAction.QUICK_PREVIEW) {
			if (previewModel != null && !canvasState.workspaceEditBusy) editor.toggleQuickPreview()
			return true
		}
		// Camera commands sit outside the mode and busy gates, as they always have: a
		// long commit must not take the view controls away.
		when (action) {
			ShortcutAction.FRAME_VIEW -> {
				if (mode == CanvasMode.EDIT) frameSelection() else resetCamera()
				return true
			}
			ShortcutAction.RESET_CAMERA -> {
				resetCamera()
				return true
			}
			else -> Unit
		}
		CanvasModeChoice.entries.firstOrNull { it.shortcut == action }?.let { choice ->
			if (previewModel == null) return false
			if (!editor.busy && !canvasState.workspaceEditBusy) editor.chooseCanvasMode(choice)
			return true
		}
		if (mode != CanvasMode.EDIT || previewModel == null) return false
		// Consumes rather than falls through while a commit is running.
		if (editor.busy || canvasState.workspaceEditBusy) return true
		return when (action) {
			ShortcutAction.SELECT_ALL -> { editor.selectAll(); true }
			ShortcutAction.INVERT_SELECTION -> { editor.selectAll(true); true }
			ShortcutAction.TOOL_SELECT -> { editor.activateTool(CanvasTool.SELECT); true }
			ShortcutAction.TOOL_TRANSFORM -> { editor.activateTool(CanvasTool.TRANSFORM); true }
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
			ShortcutAction.TOOL_WEIGHT_PAINT -> { editor.activateTool(CanvasTool.WEIGHT_PAINT); true }
			ShortcutAction.TOOL_WEIGHT_GRADIENT -> { editor.activateTool(CanvasTool.WEIGHT_GRADIENT); true }
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
				if (editor.paintSizeActive) editor.paintSize = (editor.paintSize * 1.2f).coerceAtMost(editor.brushSizeLimit)
				else editor.radius = (editor.radius * 1.2f).coerceAtMost(editor.brushSizeLimit)
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
			// A wheel notch only turns a deform brush's tip; with any other tool the wheel keeps zooming.
			ShortcutAction.BRUSH_ROTATE_LEFT -> if (wheel && editor.tool !in ANGLED_BRUSH_TOOLS) false else {
				val step = if (shift) 45f else 15f
				editor.brushAngle = (editor.brushAngle - step).mod(360f)
				true
			}
			ShortcutAction.BRUSH_ROTATE_RIGHT -> if (wheel && editor.tool !in ANGLED_BRUSH_TOOLS) false else {
				val step = if (shift) 45f else 15f
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

	Box(
		modifier = modifier
			.fillMaxSize()
			.tutorialTarget(TutorialTargetId.CANVAS_VIEWPORT)
			// clipToBounds() is a graphics layer. On a dock resize that layer's picture keeps the
			// previous window position until something else invalidates it, so mesh points stay
			// behind while the artwork moves. A draw-time clip follows layout immediately.
			.drawWithContent { clipRect { this@drawWithContent.drawContent() } }
			.background(colors.windowBackground)
			.focusRequester(focusRequester)
			.onFocusChanged {
                if (it.hasFocus) viewModel.focusCanvas(canvasId)
                else {
                    editor.endTemporarySelection()
                    temporarySelectKey = null
                    temporarySelectButton = null
                    isDragging = false
                    zoomDragAnchor = null
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
                // Match release by the physical key even if modifiers changed while it was held.
                if (event.type == KeyEventType.KeyUp && event.key == temporarySelectKey) {
                    temporarySelectKey = null
                    editor.endTemporarySelection()
                    return@onKeyEvent true
                }

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
				// and the eyedropper pointer has to appear the moment Alt is held, not the moment the mouse
				// happens to twitch. Not consumed - Alt belongs to whatever else wants it too.
				if (mode == CanvasMode.EDIT && previewModel != null &&
					(event.key == Key.AltLeft || event.key == Key.AltRight)
				) {
					editor.altHeld = event.type == KeyEventType.KeyDown
					return@onKeyEvent false
				}
				val action = canvasState.keymap.match(event, ShortcutScope.CANVAS)
					?: return@onKeyEvent false
                if (action == ShortcutAction.TEMPORARY_SELECT) {
                    if (event.type == KeyEventType.KeyDown && mode == CanvasMode.EDIT &&
                        !canvasState.workspaceEditBusy && editor.beginTemporarySelection()) temporarySelectKey = event.key
                    return@onKeyEvent true
                }
                if (action == ShortcutAction.QUICK_PREVIEW) {
                    // Toggle on release so holding the key never flips repeatedly through both modes.
                    if (event.type == KeyEventType.KeyUp) runCanvasCommand(action, shift = false, wheel = false)
                    return@onKeyEvent true
                }
				// The camera commands' releases are swallowed with their presses.
				if (event.type != KeyEventType.KeyDown)
					return@onKeyEvent action == ShortcutAction.FRAME_VIEW || action == ShortcutAction.RESET_CAMERA
				runCanvasCommand(action, event.isShiftPressed, wheel = false)
			}
			.pointerHoverIcon(PointerIcon(when {
                isDragging && zoomDragAnchor != null -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
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
                if (mode == CanvasMode.EDIT) editor.altHeld = event.keyboardModifiers.isAltPressed
                // The press as a binding: a drag gesture it starts, or a command a click of it fires.
                val pressed = buttonBindingOf(event.button, event.keyboardModifiers)
                val gesture = if (canvasState.keyCapture == null) canvasState.keymap.gesture(pressed) else null
                // Photoshop parity: Alt + right-drag retunes the brush — right/left grows/shrinks the radius,
                // down/up hardens/softens, and the Shift variant takes the tip's third parameter. The modifiers
                // are latched by the editor, so releasing them mid-drag neither aborts the gesture nor changes
                // what it is doing.
                if (mode == CanvasMode.EDIT && previewModel != null &&
                    (gesture == ShortcutAction.BRUSH_ADJUST_DRAG || gesture == ShortcutAction.BRUSH_ADJUST_ALT_DRAG) &&
                    !isDragging && !editor.inGesture &&
                    editor.beginBrushAdjust(change.position, shift = gesture == ShortcutAction.BRUSH_ADJUST_ALT_DRAG)
                ) {
                    brushAdjustButton = event.button
                    showContextMenu = false
                    change.consume(); return@onPointerEvent
                }
                // A right press that starts no gesture opens the mode/tool context menu (parameters + topology/paint actions).
                if (mode == CanvasMode.EDIT && previewModel != null && event.button == PointerButton.Secondary &&
                    gesture == null && !isDragging && !editor.inGesture && !editor.adjustingBrush &&
                    viewModel.swingSession == null && canvasContextMenuHasContent(editor)
                ) {
                    contextMenuOffset = change.position
                    showContextMenu = true
                    change.consume()
                    return@onPointerEvent
                }
                // The pan gesture (the middle button by default) or Space + left drag pans; the zoom gesture
                // zooms about the press point as the pointer moves up and down.
                if (gesture == ShortcutAction.ZOOM_DRAG || CanvasNavigation.pans(gesture, event.button, mode == CanvasMode.EDIT && editor.space)) {
                    isDragging = true
                    zoomDragAnchor = if (gesture == ShortcutAction.ZOOM_DRAG) change.position else null
                    lastDragPos = change.position
                    dragStartPos = change.position
                    change.consume()
                    return@onPointerEvent
                }
                // A middle or side button bound to a canvas command fires it; a held temporary selection lasts
                // until that button's release.
                val command = if (canvasState.keyCapture == null) canvasState.keymap.mouseCommand(pressed, ShortcutScope.CANVAS) else null
                if (command == ShortcutAction.TEMPORARY_SELECT) {
                    if (mode == CanvasMode.EDIT && !canvasState.workspaceEditBusy && editor.beginTemporarySelection())
                        temporarySelectButton = event.button
                    change.consume(); return@onPointerEvent
                }
                if (command != null && runCanvasCommand(command, event.keyboardModifiers.isShiftPressed, wheel = false)) {
                    change.consume(); return@onPointerEvent
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
				if (event.button == PointerButton.Primary) {
					isDragging = true
					zoomDragAnchor = null
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
                // Must be tested before the consumed check below, and the button test is load-bearing: a release of
                // another button during an adjustment has to fall through to the pan-end block, otherwise
                // isDragging stays true and the canvas pans forever.
                if (mode == CanvasMode.EDIT && editor.adjustingBrush && event.button == brushAdjustButton) {
                    brushAdjustButton = null
                    editor.endBrushAdjust(cancel = false)
                    return@onPointerEvent
                }
                if (temporarySelectButton != null && event.button == temporarySelectButton) {
                    temporarySelectButton = null
                    editor.endTemporarySelection()
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
					zoomDragAnchor = null
					persistCamera()
				}
			}
			.onPointerEvent(PointerEventType.Exit) {
                // Releasing the right button outside the window may never route a Release back here.
				if (mode == CanvasMode.EDIT && editor.adjustingBrush) {
					brushAdjustButton = null
					editor.endBrushAdjust(cancel = false)
				}
				if (mode == CanvasMode.EDIT) editor.clearHover()
                if (mode == CanvasMode.PREVIEW) {
					viewModel.clearPointer(renderKey)
					// One last frame puts the pose back to neutral now that the look is gone.
					notePointerActivity()
				}
			}
			.onPointerEvent(PointerEventType.Move) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
                // The key latch misses a release that lands elsewhere (Alt+Tab, a swallowed key-up) and
                // would leave every paint tool picking; the pointer's own modifiers are the truth.
                if (mode == CanvasMode.EDIT) editor.altHeld = event.keyboardModifiers.isAltPressed
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
					val anchor = zoomDragAnchor
					if (anchor != null) {
						// Up zooms in, as the wheel turned away does; one notch per ZOOM_DRAG_NOTCH_PX.
						if (delta.y != 0f) zoomAt(anchor.x, anchor.y, delta.y / (ZOOM_DRAG_NOTCH_PX * density))
					} else if (delta != Offset.Zero) {
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
				if (change.isConsumed) return@onPointerEvent
				// A wheel notch bound to a canvas command (Alt + wheel turns the brush by default) fires it; one
				// the command does not take keeps zooming below.
				val notch = wheelBindingOf(change.scrollDelta, event.keyboardModifiers)
				val command = if (canvasState.keyCapture == null) canvasState.keymap.mouseCommand(notch, ShortcutScope.CANVAS) else null
				if (command != null && command != ShortcutAction.TEMPORARY_SELECT &&
					runCanvasCommand(command, event.keyboardModifiers.isShiftPressed, wheel = true)
				) {
					change.consume()
					return@onPointerEvent
				}
				if (mode == CanvasMode.EDIT && editor.tool == CanvasTool.CREATE_WARP && !editor.warpCreateParentIsWarp()) {
					val delta = if (notch?.mouse == MouseInput.WHEEL_DOWN || notch?.mouse == MouseInput.WHEEL_RIGHT) -1 else 1
                    editor.warpCreateGridRows = (editor.warpCreateGridRows + delta).coerceIn(2, 20)
                    editor.warpCreateGridCols = (editor.warpCreateGridCols + delta).coerceIn(2, 20)
					change.consume()
					return@onPointerEvent
				}
				if (mode == CanvasMode.EDIT && (editor.inGesture || editor.adjustingBrush)) return@onPointerEvent
                // Compose turns Shift + wheel into a horizontal scroll; it zooms the same.
                val delta = if (change.scrollDelta.y != 0f) change.scrollDelta.y else change.scrollDelta.x
                zoomAt(change.position.x, change.position.y, delta)
			},
	) {
		Canvas(modifier = Modifier.fillMaxSize()) {
			// Same placement read as the mesh overlay, so both pictures invalidate together.
			canvasOrigin.x
			val w = size.width.toInt().coerceAtLeast(1)
			val h = size.height.toInt().coerceAtLeast(1)

			val model = previewModel
			if (model == null) {
				// The welcome surface is an application page, not a transparent artwork canvas.
				drawRect(color = colors.windowBackground)
				return@Canvas
			}

			when (background.kind) {
				// One cached texture fill replaces thousands of per-frame checkerboard draw calls.
				CanvasBackgroundKind.CHECKER -> drawRect(brush = checkerboardBrush)
				CanvasBackgroundKind.SOLID -> drawRect(color = solidBackground)
				// Src overwrites the chrome already painted underneath, so the transparent window shows
				// the desktop here. Not quite zero alpha: Windows passes clicks on fully clear pixels
				// through to whatever is behind the window, which would take pan and zoom with them.
				CanvasBackgroundKind.TRANSPARENT -> drawRect(color = TransparentCanvasFill, blendMode = BlendMode.Src)
			}

			val viewport = computeViewport(model, w, h)
			// While the camera pans, cached passes are drawn shifted rather than drawn again every step;
			// the pan's release draws them once more at the final camera.
			val panning = isDragging
			fun panShift(key: List<Any?>): PanShift = PanShift(
				key.map { if (it === viewport) viewport.copy(offsetX = 0.0, offsetY = 0.0) else it },
				viewport.offsetX, viewport.offsetY, panning,
			)

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
			val selectedLayerId = if (allowSelectionChrome) canvasState.selectedLayerId else null
			val selectedDeformerId = if (allowSelectionChrome) canvasState.selectedDeformerId else null

			val targetVisibleLayerIds: Set<String> = when {
				!informationSelectedOnly -> canvasState.effectiveVisibleLayerIds
				selectedLayerId != null -> {
					val keep = if (mode == CanvasMode.EDIT && editor.hierarchyMode == EditHierarchyMode.EDIT && editor.objects.size > 1) {
						editor.objects
					} else {
						setOf(selectedLayerId)
					}
					canvasState.effectiveVisibleLayerIds.filter { it in keep }.toSet()
				}
				selectedDeformerId != null -> {
					val desc = descendantLayerIds(model, selectedDeformerId, canvasState.hierarchyParentOverrides)
					canvasState.effectiveVisibleLayerIds.filter { it in desc }.toSet()
				}
				else -> canvasState.effectiveVisibleLayerIds
			}

			val hasActiveSelection = selectedLayerId != null || selectedDeformerId != null
			val highlightedLayerIds: Set<String>? = when {
                mode==CanvasMode.EDIT && editor.objectMode && editor.objects.isNotEmpty() -> editor.objects
				mode == CanvasMode.EDIT && editor.hierarchyMode == EditHierarchyMode.EDIT && editor.objects.size > 1 -> editor.objects
				selectedLayerId != null -> setOf(selectedLayerId)
				selectedDeformerId != null -> descendantLayerIds(model, selectedDeformerId, canvasState.hierarchyParentOverrides)
				else -> null
			}
			val isDimmingActive = dimUnselected && hasActiveSelection

			// Hover annotation: a wash over the artwork in the component's own colour, not a box around
			// it. A deformer owns no texture of its own, so previewing one lights up everything it
			// deforms — which is exactly what the deformer is.
			val hoveredLayerId = if (allowSelectionChrome) canvasState.hoveredLayerId else null
			val hoveredDeformerId = if (allowSelectionChrome) canvasState.hoveredDeformerId else null
			val hoverTintLayerIds = when {
				hoveredLayerId != null -> setOf(hoveredLayerId)
				hoveredDeformerId != null -> descendantLayerIds(model, hoveredDeformerId, canvasState.hierarchyParentOverrides)
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
				hoveredLayerId == null && hoveredDeformerId == null &&
				(!showSelectionBounds || !hasActiveSelection) &&
				canvasState.drawOrderOverrides.isEmpty() && simulated == null &&
				nativeFrame != null && sdkBitmap != null &&
				nativeFrame.image.width == w && nativeFrame.image.height == h

			val currentSdkBitmap = sdkBitmap
			// A snapshot hover temporarily replaces the current model, including its guides and paint tiles.
			if (canUseNativeSdk && snapshotGeometry == null) {
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
			} else if (snapshotGeometry == null) {
				// Paint is an isolated document-canvas session: its live tiles belong only on the Edit
				// tab, and only until Apply writes them into RigPreviewModel. The Preview tab always
				// keeps showing the last committed atlas, never an in-progress stroke.
				// Document-space paint tiles only line up with the mesh at rest; driving the other
				// layers with the live pose would leave the stroke floating off the art.
				// One geometry per (model, pose, simulation frame): every redraw (a GPU frame arriving, a hover) must
				// see the same instance, or the passes keyed on it would draw again and again.
				val geometry = drawnGeometry.geometry(model, editGeometry, informationPose, simulated)

					val guideKey = listOf(
						model, geometryPose, editGeometry, informationPose, viewport, w, h,
						viewOptions, warpIds, rotationIds, warpPoints, targetVisibleLayerIds,
						canvasState.selectedLayerId, canvasState.selectedDeformerId,
						canvasState.hoveredLayerId, canvasState.hoveredDeformerId,
						canvasState.hierarchyParentOverrides, editor.hierarchyMode, editor.objects,
						editor.glueSwapped, editor.drawsTransformBox,
					)
					val drawableBounds = RigCanvasSupport.boundsByDrawable(geometry)
					val deformerBounds = RigCanvasSupport.boundsByDeformer(model, drawableBounds)
					val deformEditTarget = selectedDeformerId?.takeIf {
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
					val transformBoxOwnsSelection = mode == CanvasMode.EDIT && editor.drawsTransformBox
					// The rig guides the GPU draws itself; their names and indices are Compose text over its frame.
					// 3e's paths. A path belongs to the part it deforms, so it is drawn only while that part (or the part's
					// deformer) is selected -- an edit-time guide, never part of the Preview tab's render. Hovering a
					// part in the tree previews its path, the instant feedback the warp channel gives.
					val pathsShown = mode == CanvasMode.EDIT && showDeformPaths && model.rig.puppet.deformPaths.isNotEmpty()
					val selectedPathIds: Set<String> = if (!pathsShown) emptySet() else {
						val selectedLayerDescendants = if (selectedDeformerId != null) {
							descendantLayerIds(model, selectedDeformerId, canvasState.hierarchyParentOverrides)
						} else emptySet()
						model.rig.puppet.deformPaths.filter { path ->
							val layerId = model.rig.layerIdByDrawableId[path.drawableId.raw]
							(selectedLayerId != null && layerId == selectedLayerId) ||
								(selectedDeformerId != null && layerId != null && layerId in selectedLayerDescendants)
						}.mapTo(HashSet()) { it.id }
					}
					val hoveredPathIds: Set<String> = if (!pathsShown) emptySet() else model.rig.puppet.deformPaths.filter { path ->
						hoveredLayerId != null && model.rig.layerIdByDrawableId[path.drawableId.raw] == hoveredLayerId
					}.mapTo(HashSet()) { it.id }
					val pathIds = selectedPathIds + hoveredPathIds
					val gpuWarpGuides = gpuReady
					val gpuRotationGuides = gpuReady
					val gpuBoxGuides = gpuReady
					fun gpuRigGuides(): List<OverlayItem> {
						if (!gpuReady) return emptyList()
						val guides = RigGuides(viewport)
						val guidePose = if (mode == CanvasMode.PREVIEW) informationPose else canvasState.parameterValues
						if (gpuRotationGuides && globalRotationIds.isNotEmpty()) {
							guides.rotations(io.github.psd2live.core.RigInformationOverlay.rotationNeedles(model.rig.puppet, guidePose,
								viewport, globalRotationIds, selectedDeformerId, hoveredDeformerId, dimUnselected))
						}
						if (gpuBoxGuides && showSelectionBounds && !transformBoxOwnsSelection) {
							selectedLayerId?.let { layerId ->
								val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
								drawableId?.let(drawableBounds::get)?.let { guides.selectionBox(it, ComponentPalette.strong(layerId).brighter()) }
							}
							if (mode == CanvasMode.EDIT) selectedDeformerId?.let { defId ->
								val def = model.rig.puppet.deformers.firstOrNull { it.id.raw == defId }
								if (def !is org.umamo.runtime.model.Deformer.Warp) {
									deformerBounds[defId]?.let { guides.selectionBox(it, ComponentPalette.strong(defId).brighter()) }
								}
							}
						}
						if (gpuWarpGuides && warpIds.isNotEmpty()) {
							val corners = RigCanvasSupport.deformerCorners(RigCanvasSupport.deformerOutlines(model.rig.puppet, warpPoints), viewport)
							guides.warps(io.github.psd2live.core.RigInformationOverlay.warpLayers(model.rig.puppet, warpPoints, warpIds,
								selectedDeformerId, hoveredDeformerId, dimUnselected), corners)
						}
						if (pathIds.isNotEmpty()) {
							guides.paths(io.github.psd2live.core.RigInformationOverlay.deformPathLooks(model.rig.puppet, geometry, viewport,
								pathIds, showWidth = canvasState.pathShowWidth, showHardness = canvasState.pathShowHardness,
								selectedPathIds = selectedPathIds, hoveredPathIds = hoveredPathIds))
						}
						return guides.items
					}

					// 3b's choice of meshes. The GPU draws them in its frame; the Java2D guide pass draws them in software.
					// Outside SELECT mode, mesh wires are focus chrome for the active artmesh only - every other part
					// stays texture-only so the canvas stays readable while editing. In SELECT (object) mode every mesh
					// is drawn faded; only the selection is crisp.
					val wireItems: List<WireItem> = if (!showMesh) emptyList() else buildList {
						fun wire(drawable: org.umamo.runtime.model.Drawable, selected: Boolean, dimmed: Boolean) {
							if (drawable.mesh == null || drawable.id !in geometry.worldPositions) return
							val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: return
							if (layerId !in targetVisibleLayerIds) return
							add(WireItem(drawable, layerId, selected, dimmed))
						}
						val selectedId = selectedLayerId
						val meshFocusOnly = mode == CanvasMode.EDIT && !editor.objectMode
						if (meshFocusOnly) {
							// Edit mode draws its meshes on the editor overlay - every edited mesh in one style,
							// glued points merged - so the guide adds nothing there. Deform keeps the active mesh.
							// Deform and Simulate draw a mesh target on the overlay too. A second copy from the GPU frame
							// trails it by a frame during a drag, and shows as bright wires that fade as the pointer slows.
							val overlayDrawsMesh = editor.hierarchyMode == EditHierarchyMode.EDIT ||
								((editor.hierarchyMode == EditHierarchyMode.DEFORM || editor.hierarchyMode == EditHierarchyMode.SIMULATE) &&
									editor.target()?.kind == "mesh")
							if (selectedId != null && !overlayDrawsMesh) {
								for (drawable in model.rig.puppet.drawables) {
									if (model.rig.layerIdByDrawableId[drawable.id.raw] == selectedId) wire(drawable, selected = true, dimmed = false)
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
									val isDimmed = if (objectModeMeshes) !inFocus else isDimmingActive && !inFocus
									wire(drawable, selected = false, dimmed = isDimmed)
								}
							}
							if (selectedId != null) {
								for (drawable in model.rig.puppet.drawables) {
									if (model.rig.layerIdByDrawableId[drawable.id.raw] == selectedId) wire(drawable, selected = true, dimmed = false)
								}
							}
						}
					}

					// 3a. Texture Channel. The artwork always renders opaque; legibility of the
					// overlays comes from the focus/dim options instead of a global transparency.
					val paintLayerId = paintSession?.layerId
					val effectiveVisible = if (paintLayerId != null) {
						targetVisibleLayerIds - setOf(paintLayerId)
					} else {
						targetVisibleLayerIds
					}
					if (gpuReady) {
						val options = ArtworkOptions(
							visibleLayerIds = effectiveVisible,
							drawOrderOverrides = canvasState.drawOrderOverrides,
							dimUnselected = dimUnselected,
							highlightedLayerIds = highlightedLayerIds,
							tintLayerIds = hoverTintLayerIds,
							tintColor = hoverTintColor,
						)
						val wireKey = wireItems.map { Triple(it.drawable.id, it.selected, it.dimmed) }
						val gpuPaint = paintSession?.takeIf { showTexture && it.gpuPreview }
						gpuSubmission.submit(listOf(model.rig.puppet, model.atlas, geometry, viewport, w, h, options, showTexture, wireKey,
							guideKey, pathIds, canvasState.pathShowWidth, canvasState.pathShowHardness, gpuPaint, gpuPaint?.gpuVersion)) {
							val paint = gpuPaint?.let { session ->
								val full = paintUploaded[0] !== session
								paintUploaded[0] = session
								io.github.psd2live.render.PaintScene(session, session.docWidth, session.docHeight,
									listOfNotNull(session.takeGpuUpload(full)), session.originX, session.originY,
									session.docWidth / session.scaleX, session.docHeight / session.scaleY)
							}
							CanvasScene(w, h, viewport, model, geometry,
								if (showTexture) ArtworkDrawList.build(model, geometry, options) else emptyList(),
								OverlayScene(MeshWireframe.overlay(geometry, wireItems, showTexture).items + gpuRigGuides()),
								paint)
						}
						val latest = gpuFrame
						val latestImage = gpuImage
						if (latest != null && latestImage != null && (latest.scene as? CanvasScene)?.geometry !== ghostGeometry[0]) {
							lastArtwork[0] = latest to latestImage
						}
						// A ghost frame still in flight: keep the last regular one, unless the service has released it.
						val shown = lastArtwork[0]?.takeIf { !it.first.bitmap.isClosed }
						val frame = shown?.first
						val image = shown?.second
						if (frame != null && image != null) {
							// The frame may be a step behind the camera: move it to where the camera is now, so a pan
							// or zoom follows the pointer at once and the exact frame replaces it when it lands.
							val k = (viewport.scale / frame.viewport.scale).toFloat()
							val tx = (viewport.offsetX - frame.viewport.offsetX * k).toFloat()
							val ty = (viewport.offsetY - frame.viewport.offsetY * k).toFloat()
							if (k == 1f && tx == 0f && ty == 0f) drawImage(image)
							else withTransform({
								translate(tx, ty)
								scale(k, k, pivot = Offset.Zero)
							}) { drawImage(image) }
						}
					} else if (showTexture && editingPainter != null) drawIntoCanvas { target ->
						val key = listOf(
							editingPainter, model.rig.puppet, geometry, viewport, w, h,
							effectiveVisible, canvasState.drawOrderOverrides, dimUnselected,
							highlightedLayerIds, hoverTintLayerIds, hoverTintColor, sourceImages,
						)
						artworkCache.draw(target.skiaCanvas, key, w, h, panShift(key)) { recording ->
							editingPainter.paint(
								recording, model, geometry, viewport, 1.0f,
								visibleLayerIds = effectiveVisible,
								drawOrderOverrides = canvasState.drawOrderOverrides,
								dimUnselected = dimUnselected,
								highlightedLayerIds = highlightedLayerIds,
								dimmedAlphaMultiplier = 0.22f,
								tintLayerIds = hoverTintLayerIds,
								tintColor = hoverTintColor,
								sources = sourceImages,
							)
						}
					}

				// The Java2D guide pass is the software path's: with the GPU every guide is in its frame.
				val guideImage = if (gpuReady) null else guideCache.imageFor(guideKey, w, h, panShift(guideKey)) { g ->
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
					// Whether any section drew: an empty pass is neither converted nor composited.
					var painted = false

					// 3b. Mesh Channel (Wireframe), in software only; the GPU draws it in its frame.
					if (!gpuReady && wireItems.isNotEmpty()) {
						painted = true
						for (item in wireItems) {
							val mesh = item.drawable.mesh ?: continue
							val positions = geometry.worldPositions[item.drawable.id] ?: continue
							val strokeWidth = MeshWireframe.strokeWidth(item)
							if (item.selected) {
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
								val edges = MeshWireframe.uniqueEdges(mesh.indices)
								for (e in edges.indices step 2) {
									val a = edges[e]
									val b = edges[e + 1]
									g.drawLine(
										viewport.x(positions[a * 2]).toInt(),
										viewport.yFromWorld(positions[a * 2 + 1]).toInt(),
										viewport.x(positions[b * 2]).toInt(),
										viewport.yFromWorld(positions[b * 2 + 1]).toInt(),
									)
								}
							}
							if (showTexture && !item.dimmed) drawEdges(java.awt.Color(12, 13, 16, 150), strokeWidth + 1.6f)
							drawEdges(MeshWireframe.wireColor(item), strokeWidth)
						}
					}

					// 3c. Rotation Channel (RigInformationOverlay). Same ownership as warps: the
					// editor decides which rotations show via showRotation; the Compose overlay draws
					// the interactive needle for the edit target when that toggle is on.
					if (!gpuRotationGuides && globalRotationIds.isNotEmpty()) {
						painted = true
						io.github.psd2live.core.RigInformationOverlay.paintRotations(
							g, model.rig.puppet,
							if (mode == CanvasMode.PREVIEW) informationPose else canvasState.parameterValues,
							viewport, globalRotationIds,
							labels = informationNames,
							selectedDeformerId = selectedDeformerId,
							hoveredDeformerId = hoveredDeformerId,
							dimUnselected = dimUnselected,
						)
					}

					// Global Selection Bounding Box (across all modes if enabled).
					// The transform tool draws its own box around `editor.objects`, which is not the
					// same set as the hierarchy selection — a multi-object pick frames several layers
					// while this one frames a single layer — so letting both draw stacks two different
					// rectangles over the same artwork. The transform box wins: it is the one that is
					// dragged. Every other tool leaves this as the only selection feedback.
					if (!gpuBoxGuides && showSelectionBounds && !transformBoxOwnsSelection) {
						selectedLayerId?.let { layerId ->
							val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
							val bounds = drawableId?.let(drawableBounds::get)
							if (bounds != null) {
								painted = true
								val selColor = ComponentPalette.strong(layerId).brighter()
								RigCanvasSupport.paintSelectionBounds(g, bounds, viewport, selColor, stroke = 2.0f, isDashed = false)
							}
						}
						if (mode == CanvasMode.EDIT) {
							selectedDeformerId?.let { defId ->
								val def = model.rig.puppet.deformers.firstOrNull { it.id.raw == defId }
								if (def !is org.umamo.runtime.model.Deformer.Warp) {
									val bounds = deformerBounds[defId]
									if (bounds != null) {
										painted = true
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
					if (!gpuWarpGuides && warpIds.isNotEmpty()) {
						painted = true
						io.github.psd2live.core.RigInformationOverlay.paint(
							g, model.rig.puppet,
							if (mode == CanvasMode.PREVIEW) informationPose else canvasState.parameterValues,
							viewport, warpIds,
							labels = informationNames,
							pointIndices = informationIndices,
							selectedDeformerId = selectedDeformerId,
							hoveredDeformerId = hoveredDeformerId,
							dimUnselected = dimUnselected,
							pointsById = warpPoints,
						)
					}

					// 3e. Deform Paths (RigInformationOverlay). A path belongs to the part it
					// deforms, so it is drawn only while that part (or the part's deformer) is
					// selected -- an edit-time guide, never part of the Preview tab's render.
					if (!gpuReady && pathIds.isNotEmpty()) {
						painted = true
						io.github.psd2live.core.RigInformationOverlay.paintDeformPaths(
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
					painted
				}
				if (guideImage != null) drawImage(guideImage, topLeft = guideCache.offset)
				if (gpuReady && (informationNames || informationIndices)) {
					// guideKey misses the simulated geometry the paths follow, so the label key adds it.
					val guidePose = if (mode == CanvasMode.PREVIEW) informationPose else canvasState.parameterValues
					val labels = guideLabels.labels(guideKey + listOf(geometry, pathIds)) {
						buildList {
							for (layer in io.github.psd2live.core.RigInformationOverlay.warpLayers(model.rig.puppet, warpPoints,
								warpIds, selectedDeformerId, hoveredDeformerId, dimUnselected)) {
								if (layer.isDimmed) continue
								val p = layer.points
								val w = layer.warp
								if (informationIndices) for (i in 0 until minOf(p.size / 2, (w.rows + 1) * (w.columns + 1))) {
									add(GuideLabel(i.toString(), viewport.x(p[i * 2]).toFloat() + 3f,
										viewport.yFromWorld(p[i * 2 + 1]).toFloat() - 3f, layer.wireColor.rgb, plate = false))
								}
								if (informationNames && p.size >= 2) add(GuideLabel("${w.name} [${w.id.raw}] ${w.columns}×${w.rows}",
									viewport.x(p[0]).toFloat().coerceAtLeast(0f) + 3f, viewport.yFromWorld(p[1]).toFloat().coerceAtLeast(16f),
									layer.wireColor.rgb, plate = true))
							}
							if (informationIndices && pathIds.isNotEmpty()) {
								for (look in io.github.psd2live.core.RigInformationOverlay.deformPathLooks(model.rig.puppet, geometry, viewport,
										pathIds, selectedPathIds = selectedPathIds, hoveredPathIds = hoveredPathIds)) {
									if (look.isDimmed && !look.isSelected && !look.isHovered) continue
									look.screenPoints.forEachIndexed { i, (x, y) ->
										add(GuideLabel(i.toString(), x + 6f, y - 4f, -1, plate = true))
									}
								}
							}
							if (informationNames) for (needle in io.github.psd2live.core.RigInformationOverlay.rotationNeedles(model.rig.puppet,
									guidePose, viewport, globalRotationIds, selectedDeformerId, hoveredDeformerId, dimUnselected)) {
								if (needle.dimmed) continue
								add(GuideLabel("${needle.rotation.name} [${needle.rotation.id.raw}]", needle.pivot.x.coerceAtLeast(0f) + 3f,
									(needle.pivot.y - 10f).coerceAtLeast(16f), needle.color.rgb, plate = true))
							}
						}
					}
					for (label in labels) {
						val layout = guideLabelMeasurer.measure(label.text, GuideLabelStyle)
						val top = label.baseline - layout.firstBaseline
						if (label.plate) drawRect(Color(20, 20, 24, 220), Offset(label.x - 3f, label.baseline - 14f),
							Size(layout.size.width + 6f, 17f))
						drawText(layout, color = Color(label.argb), topLeft = Offset(label.x, top))
					}
				}
				// Session tiles sit above the mesh overlays and never write into RigPreviewModel —
				// Apply (commitPaintSession) is what publishes them to the shared preview.
				if (showTexture && paintSession != null && !paintSession.gpuPreview) {
					val scale = viewport.scale
					// Tiles are raster pixels; a dense layer's raster is stretched over its canvas rectangle.
					val ox = paintSession.originX; val oy = paintSession.originY
					val sx = paintSession.scaleX; val sy = paintSession.scaleY
					for (tile in paintSession.previewTiles) {
						val left = Math.round(viewport.offsetX + (ox + tile.x / sx) * scale)
						val top = Math.round(viewport.offsetY + (oy + tile.y / sy) * scale)
						val right = Math.round(viewport.offsetX + (ox + (tile.x + tile.width) / sx) * scale)
						val bottom = Math.round(viewport.offsetY + (oy + (tile.y + tile.height) / sy) * scale)
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
			// One faded layer for the whole saved pose, sharing the canvas camera and visibility.
			// Composite after rendering its parts so overlapping meshes do not darken the ghost.
			if (snapshotGeometry != null && gpuReady) {
				ghostGeometry[0] = snapshotGeometry
				// The GPU frame is already one flattened layer, so fading the whole of it is the ghost.
				val options = ArtworkOptions(visibleLayerIds = targetVisibleLayerIds, drawOrderOverrides = canvasState.drawOrderOverrides)
				gpuSubmission.submit(listOf(model.rig.puppet, model.atlas, snapshotGeometry, viewport, w, h, options, "snapshot")) {
					CanvasScene(w, h, viewport, model, snapshotGeometry, ArtworkDrawList.build(model, snapshotGeometry, options))
				}
				val frame = gpuFrame
				val image = gpuImage
				if (frame != null && image != null && (frame.scene as? CanvasScene)?.geometry === snapshotGeometry) {
					val k = (viewport.scale / frame.viewport.scale).toFloat()
					withTransform({
						translate((viewport.offsetX - frame.viewport.offsetX * k).toFloat(), (viewport.offsetY - frame.viewport.offsetY * k).toFloat())
						scale(k, k, pivot = Offset.Zero)
					}) { drawImage(image, alpha = 0.6f) }
				}
			} else if (snapshotGeometry != null && editingPainter != null) drawIntoCanvas { target ->
				val key = listOf(editingPainter, model, snapshotGeometry, viewport, w, h,
					targetVisibleLayerIds, canvasState.drawOrderOverrides)
				snapshotArtworkCache.draw(target.skiaCanvas, key, w, h) { recording ->
					org.jetbrains.skia.Paint().use { fade ->
						fade.setAlphaf(0.6f)
						val saved = recording.saveLayer(org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), fade)
						try {
							editingPainter.paint(recording, model, snapshotGeometry, viewport,
								visibleLayerIds = targetVisibleLayerIds, drawOrderOverrides = canvasState.drawOrderOverrides)
						} finally { recording.restoreToCount(saved) }
					}
				}
			}
		}

		if(mode == CanvasMode.EDIT && previewModel != null && snapshotGeometry == null && sized) {
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
			// The same mode menu as the edit canvas, on Preview: any other row goes back to editing.
			PreviewModeBar(editor) { focusRequester.requestFocus() }
			CanvasPreviewToolbar(
				animationEnabled = canvasState.animationEnabled,
				mouseTrackingEnabled = canvasState.mouseTrackingEnabled,
				smoothMouseTracking = canvasState.smoothMouseTracking,
				physicsEnabled = canvasState.generatePhysics,
				physicsAvailable = !canvasState.meshOnly,
				fps = canvasState.rigEdits.physicsFps,
				enabled = true,
				onToggleAnimation = { viewModel.togglePreviewPlayback() },
				onToggleMouseTracking = { viewModel.setMouseTrackingEnabled(!canvasState.mouseTrackingEnabled) },
				onToggleSmoothTracking = { viewModel.setSmoothMouseTracking(!canvasState.smoothMouseTracking) },
				onTogglePhysics = { viewModel.setGeneratePhysics(!canvasState.generatePhysics) },
				onSelectFps = viewModel::setProjectFps,
			)
		}
        // Overlay: Empty hint or Stats Badge
		if (previewModel == null) {
			EmptyCanvasStart(
				recentPaths = canvasState.recentFiles,
				enabled = !canvasState.isBusy,
				openProjectShortcut = canvasState.keymap.labelFor(ShortcutAction.OPEN_PROJECT),
				openPsdShortcut = canvasState.keymap.labelFor(ShortcutAction.OPEN_PSD),
				onOpenTutorialCatalog = onOpenTutorialCatalog,
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
			// The reference simulation is not what exports: say so wherever it is on screen.
			val referenceSimulation = simulated != null
			val badgeText = when (mode) {
				CanvasMode.PREVIEW -> when {
					referenceSimulation -> "${fpsStr}${tr("canvas.preview.simReference", zoomPct)}"
					sdkFrame != null -> "${fpsStr}${tr(
						if (previewModel.hasRuntimePhysics) "canvas.preview.cubismPhysicsOn" else "canvas.preview.cubismPhysicsOff",
						zoomPct,
					)}"
					canvasState.sdkStatus != null && canvasState.sdkStatus != "ready" -> "${fpsStr}${tr("canvas.preview.softwareFallback", zoomPct)}"
					previewModel.hasRuntimePhysics -> "${fpsStr}${tr("canvas.preview.physicsOn", zoomPct)}"
					else -> "${fpsStr}${tr("canvas.preview.physicsOff", zoomPct)}"
				} + if (!referenceSimulation && canvasState.rigEdits.simEdits.any { it.enabled && it.bake == null }) tr("canvas.preview.simUnbaked") else ""
				CanvasMode.EDIT -> if (showMesh) {
					val vertexCount = previewModel.rig.puppet.drawables.sumOf { it.mesh?.vertexCount ?: 0 }
					val triangleCount = previewModel.rig.puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }
					tr("canvas.mesh.stats", previewModel.rig.puppet.drawables.size, vertexCount, triangleCount, zoomPct)
				} else {
					"$zoomPct%"
				}
			}

			// Bottom-left: zoom/FPS/physics stats pill.
			if (badgeText.isNotEmpty() && (mode != CanvasMode.EDIT || (editor.skeletonDraft == null && editor.placement == null && viewModel.swingSession == null))) {
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
						color = if (referenceSimulation && mode == CanvasMode.PREVIEW) colors.warning else colors.textPrimary,
					)
				}
			}
			if (mode == CanvasMode.EDIT && editor.hierarchyMode == EditHierarchyMode.SKELETON && editor.committedSkeleton == null) {
				SkeletonCreatePrompt(editor, Modifier.align(Alignment.Center))
			}
			// Bottom-right: display-toggle rail (mirrors the left tool palette).
			CanvasViewOptionsBar(
				options = viewOptions,
				onOptionsChange = { viewModel.setCanvasViewOptions(canvasId, it, mode) },
				showPathGuides = mode == CanvasMode.EDIT,
				showSelectionFocus = mode == CanvasMode.EDIT,
				showSourcePixels = mode == CanvasMode.EDIT,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(end = 8.dp, bottom = 8.dp),
			)
		}
	}
    }
}

/** One guide's name or point index, drawn as text over the GPU frame; [baseline] as Java2D's drawString takes it. */
private class GuideLabel(val text: String, val x: Float, val baseline: Float, val argb: Int, val plate: Boolean)

private val GuideLabelStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)

/** The guide labels laid out for one set of guide inputs, kept across redraws that change nothing they show. */
private class GuideLabelMemo {
    private var key: List<Any?>? = null
    private var labels: List<GuideLabel> = emptyList()
    fun labels(key: List<Any?>, build: () -> List<GuideLabel>): List<GuideLabel> {
        if (this.key != key) { labels = build(); this.key = key }
        return labels
    }
}

/** Hands the GPU renderer a new scene only when what it shows changed; redraws in between submit nothing. */
private class GpuSceneSubmission(private val viewId: String) {
    private var key: List<Any?>? = null

    fun submit(key: List<Any?>, scene: () -> CanvasScene) {
        if (this.key == key) return
        this.key = key
        CanvasRenderService.submit(viewId, scene())
    }
}

/**
 * The geometry the canvas draws, kept while its inputs stay the same: the edit tab's own, or the pose evaluated
 * here, with a live simulation's vertices laid over it.
 */
private class DrawnGeometryMemo {
    private var puppet: org.umamo.runtime.model.PuppetModel? = null
    private var edit: org.umamo.render.eval.DeformedGeometry? = null
    private var pose: Map<org.umamo.runtime.model.ParameterId, Float>? = null
    private var simulation: Any? = null
    private var geometry: org.umamo.render.eval.DeformedGeometry? = null

    fun geometry(
        model: RigPreviewModel,
        editGeometry: org.umamo.render.eval.DeformedGeometry?,
        pose: Map<org.umamo.runtime.model.ParameterId, Float>,
        simulated: io.github.psd2live.core.sim.SimulatedFrame?,
    ): org.umamo.render.eval.DeformedGeometry {
        val cached = geometry
        if (cached != null && puppet === model.rig.puppet && edit === editGeometry && simulation === simulated &&
            (editGeometry != null || this.pose == pose)) return cached
        val evaluated = editGeometry ?: RigCanvasSupport.evaluate(model, pose)
        val next = if (simulated == null) evaluated else org.umamo.render.eval.DeformedGeometry(
            evaluated.worldPositions + simulated.positions.filterKeys { it in evaluated.worldPositions },
            evaluated.drawOrder, evaluated.opacity,
        )
        puppet = model.rig.puppet
        edit = editGeometry
        this.pose = pose
        simulation = simulated
        geometry = next
        return next
    }
}

/** The Java2D guide pass is rebuilt only when this canvas's visible inputs change. */
private class CanvasGuideImageCache {
    private var key: List<Any?>? = null
    private var image: ImageBitmap? = null
    private var built = false
    /** Reused between rebuilds: a canvas-sized allocation per hover change or pan step adds up. */
    private var buffer: BufferedImage? = null
    private var shift: PanShift? = null
    /** Where the last image goes this frame: shifted by the pan since it was drawn, or in place. */
    var offset: Offset = Offset.Zero
        private set

    /** The guides for [key], or null when [paint] drew nothing, so there is nothing to composite. */
    fun imageFor(key: List<Any?>, width: Int, height: Int, pan: PanShift? = null, paint: (Graphics2D) -> Boolean): ImageBitmap? {
        val cached = shift
        if (pan != null && pan.panning && cached != null && built && cached.stableKey == pan.stableKey) {
            offset = Offset((pan.offsetX - cached.offsetX).toFloat(), (pan.offsetY - cached.offsetY).toFloat())
            return image
        }
        offset = Offset.Zero
        if (!built || this.key != key) {
            val buffer = buffer?.takeIf { it.width == width && it.height == height }
                ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE).also { buffer = it }
            java.util.Arrays.fill((buffer.raster.dataBuffer as java.awt.image.DataBufferInt).data, 0)
            val graphics = buffer.createGraphics()
            try {
                image = if (paint(graphics)) buffer.toImageBitmapFast() else null
                this.key = key
                shift = pan
                built = true
            } catch (_: Exception) {
                // A bad guide frame must not escape into composition and stop this canvas.
            } finally {
                graphics.dispose()
            }
        }
        return image
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

private val TransparentCanvasFill = Color(0f, 0f, 0f, 1f / 255f)

/** How long the camera must rest before a wheel zoom is written into the document. */
private const val CAMERA_PERSIST_DELAY_MILLIS = 250L

private fun opaqueColor(rgb: Int): Color = Color(0xFF000000L or (rgb.toLong() and 0xFFFFFF))

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
	return RepeatedImageBrush(tile.toImageBitmapFast())
}

private class RepeatedImageBrush(private val image: ImageBitmap) : ShaderBrush() {
	override fun createShader(size: Size): Shader = ImageShader(
		image = image,
		tileModeX = TileMode.Repeated,
		tileModeY = TileMode.Repeated,
	)
}

/**
 * The native frame's camera for the same view the edit canvas shows: one zoom and pan frame the model alike in
 * both modes, so switching between them does not resize or move it. Cubism spans the model's height over two
 * units and the shorter side of the view over two units, centred; the edit canvas fits the model inside its margin.
 */
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
	val edit = computeEditorViewport(model, IntSize(width.coerceAtLeast(1), height.coerceAtLeast(1)), zoom, panX, panY)
	return CubismViewport(
		(edit.canvasHeight * edit.scale / minOf(safeWidth, safeHeight)).toFloat(),
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
