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
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
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
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import org.umamo.render.eval.DeformedGeometry
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.pow

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

	val editor = remember(state.projectOpenGeneration) { CanvasEditor(viewModel) }
    editor.state = state
    LaunchedEffect(viewModel, mode) { viewModel.canvasPathRequests.collect { if(mode == CanvasMode.EDIT) editor.activateTool(CanvasTool.PATH_DEFORM) } }
    LaunchedEffect(state.selectedLayerId, state.selectedDeformerId) {
        if (!editor.inGesture && !editor.busy) {
            editor.resetSelection()
            if(state.selectedLayerId !in editor.objects) editor.objects=setOfNotNull(state.selectedLayerId)
            if(state.selectedDeformerId!=null) { editor.objects=emptySet();editor.objectMode=false }
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
	LaunchedEffect(mode, previewModel, state.animationEnabled, viewSize, currentZoom, currentPanX, currentPanY, state.parameterValues) {
		if (previewModel != null && viewSize.width > 0 && viewSize.height > 0) {
			if (mode == CanvasMode.PREVIEW) {
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
				} else {
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
						0f,
						System.nanoTime(),
					)
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
				if (mode == CanvasMode.EDIT && previewModel != null) {
                    if (event.key == Key.Spacebar) { editor.space = event.type == KeyEventType.KeyDown; return@onKeyEvent true }
                    if (event.type == KeyEventType.KeyDown) {
                        if (editor.busy || state.canvasEditBusy) return@onKeyEvent true
                        if (event.isCtrlPressed) {
                            when(event.key) {
                                Key.Z -> { if(event.isShiftPressed) viewModel.redoHistory() else viewModel.undoHistory(); return@onKeyEvent true }
                                Key.Y -> { viewModel.redoHistory(); return@onKeyEvent true }
                                Key.A -> { editor.selectAll(); return@onKeyEvent true }
                                Key.I -> { editor.selectAll(true); return@onKeyEvent true }
                            }
                        } else {
                            val tool = when(event.key) {
                                Key.V -> CanvasTool.SELECT
                                Key.Tab, Key.E -> if (editor.tool == CanvasTool.MESH) CanvasTool.SELECT else CanvasTool.MESH
                                Key.W -> CanvasTool.WARP
                                Key.B -> if (event.isShiftPressed) CanvasTool.SMOOTH else CanvasTool.BRUSH
                                Key.I -> CanvasTool.INFLATE
                                Key.D, Key.P -> CanvasTool.PATH_DEFORM
                                Key.H -> CanvasTool.HAND
                                else -> null
                            }
                            if (tool != null) { editor.activateTool(tool); return@onKeyEvent true }
                            when(event.key) {
                                Key.Q -> { editor.selectionStyle = SelectionStyle.BOX; return@onKeyEvent true }
                                Key.L -> {
                                    if (event.isShiftPressed) editor.selectLinked()
                                    else editor.selectionStyle = SelectionStyle.LASSO
                                    return@onKeyEvent true
                                }
                                Key.Escape -> { editor.cancel(); return@onKeyEvent true }
                                Key.Enter -> { editor.finishPath(); return@onKeyEvent true }
                                Key.Delete, Key.Backspace -> {
                                    if (editor.tool == CanvasTool.PATH_DEFORM) editor.deletePathPoint()
                                    else if (editor.tool == CanvasTool.MESH) editor.topology("delete")
                                    return@onKeyEvent true
                                }
                                Key.LeftBracket -> {
                                    // Never let hardness reach 1.0: brushWeight divides by (1 - hardness).
                                    if (event.isShiftPressed) editor.hardness = (editor.hardness - 0.05f).coerceIn(0f, 0.95f)
                                    else editor.radius = (editor.radius / 1.2f).coerceAtLeast(4f)
                                    return@onKeyEvent true
                                }
                                Key.RightBracket -> {
                                    if (event.isShiftPressed) editor.hardness = (editor.hardness + 0.05f).coerceIn(0f, 0.95f)
                                    else editor.radius = (editor.radius * 1.2f).coerceAtMost(500f)
                                    return@onKeyEvent true
                                }
                                Key.X -> { editor.axis = if (editor.axis == "x") null else "x"; return@onKeyEvent true }
                                Key.Y -> { editor.axis = if (editor.axis == "y") null else "y"; return@onKeyEvent true }
                                else -> Unit
                            }
                        }
                    }
                }
                if (event.type == KeyEventType.KeyDown && !event.isCtrlPressed) {
					when (event.key) {
                        Key.F -> { if(mode==CanvasMode.EDIT)frameSelection() else resetCamera();true }
                        Key.MoveHome, Key.Zero -> {
							resetCamera()
							true
						}
						else -> false
					}
				} else false
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
                    event.keyboardModifiers.isAltPressed && !isDragging && !editor.inGesture && editor.beginBrushAdjust(change.position)
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
					if (mode == CanvasMode.PREVIEW) viewModel.clearPointer()
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
							val geometry = RigCanvasSupport.evaluate(previewModel, state.effectivePose(CanvasMode.PREVIEW))
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
				}
			}
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
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
			// Draw artwork and its diagnostic geometry from the same pose and camera. Native
			// frames use a different camera and publish UI parameter values at a lower frequency.
			val informationPose = informationPreviewPose(state.parameterValues, state.previewParameterValues, sdkFrame, state.animationEnabled)

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
			val hasActivePaths = state.showDeformPaths && model.rig.puppet.deformPaths.isNotEmpty()
			val canUseNativeSdk = mode == CanvasMode.PREVIEW &&
				!showWarp && !showMesh && !hasActivePaths && !informationSelectedOnly && showTexture &&
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

					// 3e. Deform Paths (RigInformationOverlay)
					if (state.showDeformPaths && model.rig.puppet.deformPaths.isNotEmpty()) {
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

						val pathIds = if (informationSelectedOnly) {
							selectedPathIds
						} else {
							model.rig.puppet.deformPaths.map { it.id }.toSet()
						}

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
								dimUnselected = state.dimUnselected,
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
            CanvasEditorOverlay(editor,computeViewport(previewModel,viewSize.width,viewSize.height),viewModel) { focusRequester.requestFocus() }
        } else if (mode == CanvasMode.PREVIEW && previewModel != null) {
            PreviewFloatingToolbar(state, viewModel)
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
						.padding(end=10.dp,bottom=if(mode==CanvasMode.EDIT)32.dp else 10.dp)
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

@Composable
private fun BoxScope.PreviewFloatingToolbar(
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
) {
	val colors = LocalToolColors.current
	val isAnim = state.animationEnabled
	val isTracking = state.mouseTrackingEnabled

	Row(
		modifier = Modifier
			.align(Alignment.BottomCenter)
			.padding(bottom = 12.dp)
			.background(Color(0xEE1E2024), RoundedCornerShape(6.dp))
			.border(BorderStroke(1.dp, Color(0x448892B0)), RoundedCornerShape(6.dp))
			.padding(horizontal = 8.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		// Play / Pause Button
		CompactButton(
			text = if (isAnim) tr("preview.animation.pause") else tr("preview.animation.play"),
			onClick = { viewModel.setAnimationEnabled(!isAnim) },
			leadingIcon = {
				if (isAnim) IconPause(modifier = Modifier.size(12.dp), tint = colors.accent)
				else IconPlay(modifier = Modifier.size(12.dp), tint = colors.accent)
			},
			height = 24.dp,
		)

		// Mouse Tracking Toggle
		CompactToggleChip(
			text = tr("preview.mouseTracking.on"),
			selected = isTracking,
			onToggle = { viewModel.setMouseTrackingEnabled(!isTracking) },
			leadingIcon = {
				IconMouse(
					active = isTracking,
					modifier = Modifier.size(12.dp),
					tint = if (isTracking) colors.accent else colors.textDisabled,
				)
			},
			showCheckWhenSelected = false,
			height = 24.dp,
		)

		Spacer(Modifier.width(2.dp))
		Box(modifier = Modifier.width(1.dp).height(16.dp).background(Color(0x33FFFFFF)))
		Spacer(Modifier.width(2.dp))

		// Motion triggers: Idle, Blink, Nod, Shake
		val motions = listOf(
			"Idle" to tr("export.motion.idle"),
			"Blink" to tr("export.motion.blink"),
			"Nod" to tr("export.motion.nod"),
			"Shake" to tr("export.motion.shake"),
		)
		for ((group, label) in motions) {
			CompactButton(
				text = label,
				onClick = { viewModel.triggerMotion(group) },
				height = 24.dp,
			)
		}

		Spacer(Modifier.width(2.dp))
		Box(modifier = Modifier.width(1.dp).height(16.dp).background(Color(0x33FFFFFF)))
		Spacer(Modifier.width(2.dp))

		// Reset Pose Button
		CompactButton(
			text = tr("parameters.resetAll"),
			onClick = { viewModel.resetAllParameters() },
			leadingIcon = { IconReset(modifier = Modifier.size(11.dp), tint = colors.textPrimary) },
			height = 24.dp,
		)
	}
}
