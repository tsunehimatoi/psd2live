package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
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
import io.github.psd2live.ui.CanvasCamera
import io.github.psd2live.ui.CanvasViewport
import io.github.psd2live.ui.ComponentPalette
import io.github.psd2live.ui.RigCanvasSupport
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspaceTab
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
	mode: WorkspaceTab,
	state: PSD2LiveState,
	viewModel: PSD2LiveViewModel,
	modifier: Modifier = Modifier,
	onLayerClicked: ((String?) -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val focusRequester = remember { FocusRequester() }

	var viewSize by remember { mutableStateOf(IntSize(600, 600)) }
	var zoom by remember(state.projectOpenGeneration) { mutableStateOf(state.canvasZoom.toDouble()) }
	var panX by remember(state.projectOpenGeneration) { mutableStateOf(state.canvasPanX.toDouble()) }
	var panY by remember(state.projectOpenGeneration) { mutableStateOf(state.canvasPanY.toDouble()) }
	var isDragging by remember { mutableStateOf(false) }
	var lastDragPos by remember { mutableStateOf(Offset.Zero) }
	var fps by remember { mutableStateOf(0f) }
	val fpsCounter = remember { ActualFpsCounter() }

	val previewModel = state.previewModel
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


	fun computeViewport(model: RigPreviewModel, width: Int, height: Int, margin: Int = 34): CanvasViewport {
		val canvasWidth = model.analysis.source.widthPx.toFloat().coerceAtLeast(1f)
		val canvasHeight = model.analysis.source.heightPx.toFloat().coerceAtLeast(1f)
		val availableWidth = (width - margin * 2).coerceAtLeast(1)
		val availableHeight = (height - margin * 2).coerceAtLeast(1)
		val fitScale = minOf(availableWidth / canvasWidth.toDouble(), availableHeight / canvasHeight.toDouble())
		val scale = fitScale * zoom
		return CanvasViewport(
			scale,
			(width - canvasWidth * scale) * 0.5 + panX,
			(height - canvasHeight * scale) * 0.5 + panY,
			canvasWidth,
			canvasHeight,
		)
	}

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
	LaunchedEffect(mode, previewModel, state.animationEnabled, viewSize) {
		if (previewModel != null && viewSize.width > 0 && viewSize.height > 0) {
			if (mode == WorkspaceTab.PREVIEW) {
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
			}
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.clipToBounds()
			.background(colors.windowBackground)
			.focusRequester(focusRequester)
			.focusable()
			.onSizeChanged { viewSize = it }
			.onKeyEvent { event ->
				if (event.type == KeyEventType.KeyDown) {
					when (event.key) {
						Key.F, Key.MoveHome, Key.Zero -> {
							resetCamera()
							true
						}
						else -> false
					}
				} else false
			}
			.pointerHoverIcon(PointerIcon(if (isDragging) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.onPointerEvent(PointerEventType.Press) { event ->
				focusRequester.requestFocus()
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				if (event.button == PointerButton.Primary || event.button == PointerButton.Tertiary) {
					isDragging = true
					lastDragPos = change.position
					if (mode == WorkspaceTab.PREVIEW) viewModel.clearPointer()
				}
			}
			.onPointerEvent(PointerEventType.Release) { event ->
				val change = event.changes.firstOrNull()
				if (isDragging) {
					isDragging = false
					if (event.button == PointerButton.Primary && change != null && (change.position - lastDragPos).getDistance() < 6f) {
						if (previewModel != null && onLayerClicked != null) {
							val viewport = computeViewport(previewModel, viewSize.width, viewSize.height)
							val geometry = RigCanvasSupport.evaluate(previewModel, state.parameterValues)
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
				if (mode == WorkspaceTab.PREVIEW) {
					viewModel.clearPointer()
				}
			}
			.onPointerEvent(PointerEventType.Move) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
				if (isDragging) {
					val delta = change.position - lastDragPos
					panX += delta.x
					panY += delta.y
                    viewModel.setCanvasView(zoom.toFloat(), panX.toFloat(), panY.toFloat())
					lastDragPos = change.position
				}
				if (!isDragging && mode == WorkspaceTab.PREVIEW && state.mouseTrackingEnabled) {
					val normX = ((change.position.x - viewSize.width * 0.5f) / (viewSize.width * 0.5f).coerceAtLeast(1f)).coerceIn(-1f, 1f)
					val normY = ((change.position.y - viewSize.height * 0.5f) / (viewSize.height * 0.5f).coerceAtLeast(1f)).coerceIn(-1f, 1f)
					viewModel.updatePointer(normX, normY)
				}
			}
			.onPointerEvent(PointerEventType.Scroll) { event ->
				val change = event.changes.firstOrNull() ?: return@onPointerEvent
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
			val informationPose = informationPreviewPose(state.parameterValues, sdkFrame, state.animationEnabled)

			// 2. Draw canvas boundary
			drawRect(
				color = Color(198, 205, 216, 105),
				topLeft = Offset(viewport.offsetX.toFloat(), viewport.offsetY.toFloat()),
				size = Size((viewport.canvasWidth * viewport.scale).toFloat(), (viewport.canvasHeight * viewport.scale).toFloat()),
				style = Stroke(width = 1f),
			)

			// 3. Multi-channel Rendering: Texture, Mesh, Warp
			val showWarp = state.showWarp || (mode == WorkspaceTab.HIERARCHY)
			val showMesh = state.showMesh || (mode == WorkspaceTab.TOPOLOGY)
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

			val nativeFrame = sdkFrame
			val canUseNativeSdk = mode == WorkspaceTab.PREVIEW &&
				!showWarp && !showMesh && !informationSelectedOnly && showTexture &&
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
					val geometry = RigCanvasSupport.evaluate(model, if (mode == WorkspaceTab.PREVIEW) informationPose else state.parameterValues)

					// 3a. Texture Channel
					if (showTexture) {
						val textureAlpha = when (mode) {
							WorkspaceTab.TOPOLOGY -> 0.26f
							WorkspaceTab.HIERARCHY -> 0.43f
							else -> 1.0f
						}
						RigCanvasSupport.paintTexturedRig(g, model, geometry, viewport, textureAlpha, visibleLayerIds = targetVisibleLayerIds)
					}

					// 3b. Mesh Channel (Wireframe)
					if (showMesh) {
						fun drawMeshWireframe(drawable: org.umamo.runtime.model.Drawable, selected: Boolean) {
							val mesh = drawable.mesh ?: return
							val positions = geometry.worldPositions[drawable.id] ?: return
							val layerId = model.rig.layerIdByDrawableId[drawable.id.raw] ?: return
							if (layerId !in targetVisibleLayerIds) return
							val awtColor = ComponentPalette.strong(layerId)
							g.color = if (selected) awtColor.brighter() else awtColor
							g.stroke = BasicStroke(if (selected) 2.2f else 0.85f)
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

						val selectedId = state.selectedLayerId
						for (drawable in model.rig.puppet.drawables) {
							val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
							if (layerId != selectedId) {
								drawMeshWireframe(drawable, selected = false)
							}
						}
						if (selectedId != null) {
							for (drawable in model.rig.puppet.drawables) {
								val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
								if (layerId == selectedId) {
									drawMeshWireframe(drawable, selected = true)
								}
							}
						}
					}

					// 3c. Hierarchy Mode Bounds
					if (mode == WorkspaceTab.HIERARCHY) {
						val drawableBounds = RigCanvasSupport.boundsByDrawable(geometry)
						val deformerBounds = RigCanvasSupport.boundsByDeformer(model, drawableBounds)
						for (deformer in model.rig.puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Rotation>()) {
							val bounds = deformerBounds[deformer.id.raw] ?: continue
							val selected = deformer.id.raw == state.selectedDeformerId
							val color = ComponentPalette.strong(deformer.id.raw)
							RigCanvasSupport.paintBounds(g, bounds, viewport, color, if (selected) 2.8f else 1.15f)

							g.font = java.awt.Font(java.awt.Font.SANS_SERIF, if (selected) java.awt.Font.BOLD else java.awt.Font.PLAIN, 11)
							val lx = viewport.x(bounds.left).toInt() + 2
							val ly = (viewport.offsetY + bounds.top * viewport.scale).toInt() - 3
							val metrics = g.fontMetrics
							val labelY = ly.coerceAtLeast(metrics.ascent + 2)
							g.color = java.awt.Color(24, 26, 30, 205)
							g.fillRoundRect(lx - 2, labelY - metrics.ascent, metrics.stringWidth(deformer.name) + 7, metrics.height, 5, 5)
							g.color = color.brighter()
							g.drawString(deformer.name, lx + 1, labelY)
						}
						state.selectedLayerId?.let { layerId ->
							val drawableId = model.rig.layerIdByDrawableId.entries.firstOrNull { it.value == layerId }?.key
							val bounds = drawableId?.let(drawableBounds::get)
							if (bounds != null) RigCanvasSupport.paintBounds(
								g, bounds, viewport, ComponentPalette.strong(layerId).brighter(), 3.0f,
							)
						}
					}

					// 3d. Warp Channel (RigInformationOverlay)
					if (showWarp) {
						val ids = informationWarpIds(model.rig.puppet, state.selectedDeformerId, informationSelectedOnly)
							.filter { state.isDeformerVisible(it) }.toSet()
						io.github.psd2live.ui.RigInformationOverlay.paint(
							g, model.rig.puppet,
							if (mode == WorkspaceTab.PREVIEW) informationPose else state.parameterValues,
							viewport, ids, labels = informationNames, pointIndices = informationIndices,
						)
					}
				} finally {
					g.dispose()
				}
				drawImage(buffer.toComposeImageBitmap())
			}
		}

		// Overlay: Empty hint or Stats Badge
		if (previewModel == null) {
			Text(
				text = when (mode) {
					WorkspaceTab.HIERARCHY -> tr("canvas.hierarchy.empty")
					WorkspaceTab.TOPOLOGY -> tr("canvas.topology.empty")
					else -> tr("canvas.preview.empty")
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
				WorkspaceTab.PREVIEW -> when {
					sdkFrame != null -> "${fpsStr}${tr(
						if (previewModel.hasRuntimePhysics) "canvas.preview.cubismPhysicsOn" else "canvas.preview.cubismPhysicsOff",
						zoomPct,
					)}"
					state.sdkStatus != null && state.sdkStatus != "ready" -> "${fpsStr}${tr("canvas.preview.softwareFallback", zoomPct)}"
					previewModel.hasRuntimePhysics -> "${fpsStr}${tr("canvas.preview.physicsOn", zoomPct)}"
					else -> "${fpsStr}${tr("canvas.preview.physicsOff", zoomPct)}"
				}
				WorkspaceTab.TOPOLOGY -> {
					val vertexCount = previewModel.rig.puppet.drawables.sumOf { it.mesh?.vertexCount ?: 0 }
					val triangleCount = previewModel.rig.puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }
					tr("canvas.topology.stats", previewModel.rig.puppet.drawables.size, vertexCount, triangleCount, zoomPct)
				}
				WorkspaceTab.HIERARCHY -> "${zoomPct}%"
				else -> ""
			}

			if (badgeText.isNotEmpty()) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(10.dp)
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
): CanvasCamera.CubismViewport {
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
	return CanvasCamera.CubismViewport(
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
