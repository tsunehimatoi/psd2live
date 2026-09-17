package io.github.psd2live.ui.views

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.DeformPathTools
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.*
import io.github.psd2live.ui.components.*
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import org.umamo.edit.MeshTopology

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.CanvasEditorOverlay(
    editor: CanvasEditor,
    viewport: CanvasViewport,
    viewModel: PSD2LiveViewModel,
    focus: () -> Unit
) {
    val colors = LocalToolColors.current
    val target = editor.target()
    val isPathTool = editor.tool == CanvasTool.PATH_DEFORM

    Canvas(Modifier.fillMaxSize()) {
        // 1. SELECT tool: Draw object bounding box and 8 handles + rotate stem
        if (editor.tool == CanvasTool.SELECT) {
            val bounds = editor.selectionBounds(viewport)
            if (bounds != null) {
                val rotateAngleDeg = Math.toDegrees(editor.currentRotateAngle.toDouble()).toFloat()
                val rotatePivot = editor.currentRotateCenter ?: Offset(bounds.centerX, bounds.centerY)
                rotate(rotateAngleDeg, rotatePivot) {
                    // Main bounding box rectangle
                    drawRect(
                        color = colors.accent,
                        topLeft = Offset(bounds.minX, bounds.minY),
                        size = Size(bounds.width, bounds.height),
                        style = Stroke(1.2f)
                    )

                    // Rotate handle: stem line + circle
                    drawLine(
                        color = colors.accent.copy(alpha = 0.8f),
                        start = Offset(bounds.centerX, bounds.minY),
                        end = bounds.rotateHandlePos,
                        strokeWidth = 1f
                    )
                    val isRotateHovered = editor.hoveredHandle == BoundingHandle.ROTATE
                    drawCircle(
                        color = if (isRotateHovered) colors.accent else colors.windowBackground,
                        radius = if (isRotateHovered) 5.5f else 4f,
                        center = bounds.rotateHandlePos
                    )
                    drawCircle(
                        color = colors.accent,
                        radius = if (isRotateHovered) 5.5f else 4f,
                        center = bounds.rotateHandlePos,
                        style = Stroke(1.5f)
                    )

                    // Draw 8 transform handles
                    val handles = listOf(
                        BoundingHandle.TOP_LEFT to Offset(bounds.minX, bounds.minY),
                        BoundingHandle.TOP_RIGHT to Offset(bounds.maxX, bounds.minY),
                        BoundingHandle.BOTTOM_LEFT to Offset(bounds.minX, bounds.maxY),
                        BoundingHandle.BOTTOM_RIGHT to Offset(bounds.maxX, bounds.maxY),
                        BoundingHandle.TOP to Offset(bounds.centerX, bounds.minY),
                        BoundingHandle.BOTTOM to Offset(bounds.centerX, bounds.maxY),
                        BoundingHandle.LEFT to Offset(bounds.minX, bounds.centerY),
                        BoundingHandle.RIGHT to Offset(bounds.maxX, bounds.centerY),
                    )
                    handles.forEach { (handle, pt) ->
                        val isCorner = handle in listOf(BoundingHandle.TOP_LEFT, BoundingHandle.TOP_RIGHT, BoundingHandle.BOTTOM_LEFT, BoundingHandle.BOTTOM_RIGHT)
                        val isHovered = editor.hoveredHandle == handle
                        val hs = if (isHovered) 8f else if (isCorner) 7f else 5.5f
                        drawRect(
                            color = if (isHovered) colors.accent else Color.White,
                            topLeft = pt - Offset(hs * 0.5f, hs * 0.5f),
                            size = Size(hs, hs)
                        )
                        drawRect(
                            color = if (isHovered) Color.White else colors.accent,
                            topLeft = pt - Offset(hs * 0.5f, hs * 0.5f),
                            size = Size(hs, hs),
                            style = Stroke(1f)
                        )
                    }
                }
            }
        }

        // 2. MESH tool: Draw triangle wireframe, vertices, and hover halo
        if (editor.tool == CanvasTool.MESH && target != null && target.kind == "mesh") {
            val pts = editor.screen(target.geometry.points, target, viewport)
            val edges = MeshTopology.uniqueEdges(target.indices).map { it.endpointLow to it.endpointHigh }
            edges.forEach { (a, b) ->
                drawLine(Color.Black.copy(alpha = 0.45f), pts[a], pts[b], 2.5f)
                drawLine(colors.accent.copy(alpha = 0.65f), pts[a], pts[b], 1f)
            }
            pts.forEachIndexed { i, p ->
                val isSelected = i in editor.vertices
                val isHovered = i == editor.hoveredVertex
                if (isHovered) {
                    drawCircle(Color.White, 7.5f, p, style = Stroke(1.8f))
                    drawCircle(colors.accent, 4.5f, p)
                } else if (isSelected) {
                    drawCircle(colors.windowBackground, 5f, p)
                    drawCircle(colors.accent, 3.8f, p)
                } else {
                    drawCircle(colors.windowBackground, 3.5f, p)
                    drawCircle(colors.textPrimary.copy(alpha = 0.7f), 2.2f, p)
                }
            }

            // Dragging guide line
            if (editor.inGesture && editor.vertices.isNotEmpty() && editor.marquee.isEmpty() && editor.cursor != null) {
                drawLine(
                    color = colors.accent.copy(alpha = 0.6f),
                    start = editor.dragStartPos,
                    end = editor.cursor!!,
                    strokeWidth = 1.5f
                )
            }
        }

        // 3. WARP tool: Draw deformer lattice grid lines or rotation axis
        if (editor.tool == CanvasTool.WARP && target != null && (target.kind == "warp" || target.kind == "rotation")) {
            val pts = editor.screen(target.geometry.points, target, viewport)
            if (target.kind == "rotation") {
                if (pts.size >= 2) {
                    drawLine(colors.accent, pts[0], pts[1], 2.5f)
                    drawCircle(colors.accent, 6.5f, pts[0])
                    drawCircle(Color(0xFF7BBB99), 5.5f, pts[1])
                }
            } else {
                val columns = target.geometry.columns!! + 1
                pts.indices.flatMap { i ->
                    listOfNotNull(
                        if (i % columns < columns - 1) i to i + 1 else null,
                        if (i + columns < pts.size) i to i + columns else null
                    )
                }.forEach { (a, b) ->
                    drawLine(Color.Black.copy(alpha = 0.6f), pts[a], pts[b], 3f)
                    drawLine(colors.accent.copy(alpha = 0.8f), pts[a], pts[b], 1.2f)
                }
                pts.forEachIndexed { i, p ->
                    val isHovered = i == editor.hoveredVertex
                    val isSelected = i in editor.vertices
                    if (isHovered) {
                        drawCircle(Color.White, 8f, p, style = Stroke(2f))
                        drawCircle(colors.accent, 5f, p)
                    } else if (isSelected) {
                        drawCircle(colors.windowBackground, 5.5f, p)
                        drawCircle(colors.accent, 4f, p)
                    } else {
                        drawCircle(colors.windowBackground, 4f, p)
                        drawCircle(colors.textPrimary, 2.8f, p)
                    }
                }
            }
        }

        // 4. BRUSH / SMOOTH / INFLATE mode: Circle brush outline following cursor
        if (editor.tool in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)) {
            editor.cursor?.let {
                val ring = if (editor.tool == CanvasTool.INFLATE && editor.shrinks) colors.warning else colors.textPrimary
                drawCircle(Color.Black.copy(alpha = 0.7f), editor.radius, it, style = Stroke(3f))
                drawCircle(ring, editor.radius, it, style = Stroke(1.2f))
                drawCircle(colors.accent.copy(alpha = 0.6f), editor.radius * editor.hardness, it, style = Stroke(1f))
            }
        }

        // 5. PATH_DEFORM mode: Curves and control points
        if (isPathTool && target != null && target.kind == "mesh") {
            editor.paths().forEach { path ->
                val local = DeformPathTools.positions(path, target.geometry.points)
                val curve = DeformPathTools.curve(local, path.points.map { it.corner }, path.closed)
                val points = editor.screen(curve.flatMap { listOf(it.first, it.second) }.toFloatArray(), target, viewport)
                val selected = path.id == editor.activePath
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(Color.Black.copy(alpha = 0.7f), a, b, 5f)
                    drawLine(if (selected) colors.accent else colors.textPrimary, a, b, 2f)
                }
                editor.screen(local.flatMap { listOf(it.first, it.second) }.toFloatArray(), target, viewport).forEachIndexed { i, p ->
                    val isHovered = i == editor.hoveredVertex
                    if (isHovered) {
                        drawCircle(Color.White, 8f, p, style = Stroke(2f))
                        drawCircle(colors.accent, 5f, p)
                    } else {
                        drawCircle(colors.windowBackground, 5.5f, p)
                        drawCircle(if (selected && i == editor.pathPoint) colors.accent else colors.textPrimary, 4f, p)
                    }
                }
            }
            val points = editor.screen(editor.draft.flatMap { listOf(it.first, it.second) }.toFloatArray(), target, viewport)
            points.zipWithNext().forEach { (a, b) -> drawLine(colors.accent, a, b, 2f) }
            points.forEach { drawCircle(colors.accent, 4f, it) }
            if (editor.drawingPath && points.isNotEmpty()) editor.cursor?.let { drawLine(colors.accent.copy(alpha = 0.5f), points.last(), it, 1f) }
        }

        // 6. Marquee selection box / lasso
        if (editor.marquee.isNotEmpty()) {
            val points = editor.marquee
            if (editor.selectionStyle == SelectionStyle.LASSO) {
                val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, colors.accent.copy(alpha = 0.15f))
                drawPath(path, colors.accent, style = Stroke(1.2f))
            } else {
                val a = points.first(); val b = points.last()
                val origin = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val extent = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                drawRect(colors.accent.copy(alpha = 0.15f), origin, extent)
                drawRect(colors.accent, origin, extent, style = Stroke(1.2f))
            }
        }
    }

    // Left Animated Hover Toolbar
    CanvasToolBar(editor = editor, focus = focus)

    // Top Options Bar
    Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(colors.panelBackground).border(1.dp, colors.divider)) {
        Row(
            Modifier.height(32.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(tr("editor.tool.${editor.tool.name.lowercase()}"), color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(target?.geometry?.name ?: tr("editor.select"), color = colors.textMuted, fontSize = 11.sp)

            // Selection style toggle (Box / Lasso)
            if (editor.tool in listOf(CanvasTool.SELECT, CanvasTool.MESH, CanvasTool.WARP)) {
                CompactToggleChip(
                    text = tr("editor.mode.box"),
                    selected = editor.selectionStyle == SelectionStyle.BOX,
                    onToggle = { editor.selectionStyle = SelectionStyle.BOX },
                    height = 22.dp
                )
                CompactToggleChip(
                    text = tr("editor.mode.lasso"),
                    selected = editor.selectionStyle == SelectionStyle.LASSO,
                    onToggle = { editor.selectionStyle = SelectionStyle.LASSO },
                    height = 22.dp
                )
            }

            // Target pose / parameter picker for MESH and WARP
            if (target != null && editor.tool in listOf(CanvasTool.MESH, CanvasTool.WARP)) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    CompactButton(if (editor.parameter == null) if (target.geometry.axes.isEmpty()) tr("editor.base") else tr("editor.pose") else editor.parameter!!, { menu = true }, height = 23.dp)
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ editor.parameter = null; menu = false; focus() }) { Text(if (target.geometry.axes.isEmpty()) tr("editor.base") else tr("editor.pose")) }
                        editor.model.parameters.forEach { p -> DropdownMenuItem({ editor.parameter = p.id.raw; menu = false; focus() }) { Text(p.name) } }
                    }
                }
            }

            // SELECT tool options: Precise numeric transforms & Warp creation
            if (editor.tool == CanvasTool.SELECT) {
                var posX by remember { mutableStateOf(0.0) }
                var posY by remember { mutableStateOf(0.0) }
                var scaleVal by remember { mutableStateOf(100.0) }
                var rotateVal by remember { mutableStateOf(0.0) }
                CompactNumberSpinner(posX, { posX = it }, Modifier.width(72.dp), min = -10000.0, max = 10000.0, decimals = 1, unit = "X", height = 23.dp)
                CompactNumberSpinner(posY, { posY = it }, Modifier.width(72.dp), min = -10000.0, max = 10000.0, decimals = 1, unit = "Y", height = 23.dp)
                CompactButton(tr("editor.apply"), { editor.preciseTransform(viewport, posX.toFloat(), posY.toFloat()); focus() }, enabled = editor.editable && target != null, height = 23.dp)
                CompactNumberSpinner(scaleVal, { scaleVal = it }, Modifier.width(72.dp), min = 0.1, max = 10000.0, decimals = 1, unit = "%", height = 23.dp)
                CompactButton(tr("editor.apply"), { editor.preciseTransform(viewport, scaleVal.toFloat(), scaleMode = true); focus() }, enabled = editor.editable && target != null, height = 23.dp)
                CompactNumberSpinner(rotateVal, { rotateVal = it }, Modifier.width(72.dp), min = -360.0, max = 360.0, decimals = 1, unit = "°", height = 23.dp)
                CompactButton(tr("editor.apply"), { editor.preciseTransform(viewport, rotateVal.toFloat(), rotateMode = true); focus() }, enabled = editor.editable && target != null, height = 23.dp)

                if (target?.kind == "mesh") {
                    CompactButton(tr("editor.createWarp"), { editor.createWarp(); focus() }, enabled = editor.editable, height = 23.dp)
                    TooltipArea(tooltip = { Surface(color = colors.panelElevated) { Text(tr("editor.rootRotationHint"), color = colors.textPrimary, fontSize = 11.sp, modifier = Modifier.padding(6.dp)) } }) {
                        CompactButton(tr("editor.createRotation"), { editor.createWarp(rotation = true); focus() }, enabled = editor.editable, height = 23.dp)
                    }
                }
            }

            // MESH tool options: element mode & topology
            if (editor.tool == CanvasTool.MESH) {
                listOf("vertex", "edge", "face").forEachIndexed { i, key -> CompactButton(tr("editor.$key"), { editor.elementMode = i; focus() }, isPrimary = editor.elementMode == i, height = 23.dp) }
                listOf("split", "connect", "merge", "delete").forEach { action -> CompactButton(tr("editor.$action"), { editor.topology(action); focus() }, enabled = editor.editable && editor.vertices.isNotEmpty(), height = 23.dp) }
            }

            // WARP tool options
            if (editor.tool == CanvasTool.WARP) {
                CompactButton(tr("editor.createWarp"), { editor.createWarp(); focus() }, enabled = editor.editable && target?.kind == "mesh", height = 23.dp)
                TooltipArea(tooltip = { Surface(color = colors.panelElevated) { Text(tr("editor.rootRotationHint"), color = colors.textPrimary, fontSize = 11.sp, modifier = Modifier.padding(6.dp)) } }) {
                    CompactButton(tr("editor.createRotation"), { editor.createWarp(rotation = true); focus() }, enabled = editor.editable && target?.kind == "mesh", height = 23.dp)
                }
            }

            // BRUSH / SMOOTH / INFLATE tool options
            if (editor.tool in listOf(CanvasTool.BRUSH, CanvasTool.SMOOTH, CanvasTool.INFLATE)) {
                Text(tr("editor.radius"), color = colors.textMuted, fontSize = 10.sp)
                CompactNumberSpinner(editor.radius.toDouble(), { editor.radius = it.toFloat() }, Modifier.width(72.dp), min = 4.0, max = 500.0, unit = "px", height = 23.dp)
                Text(tr("editor.strength"), color = colors.textMuted, fontSize = 10.sp)
                CompactNumberSpinner((editor.strength * 100).toDouble(), { editor.strength = it.toFloat() / 100 }, Modifier.width(65.dp), min = 1.0, max = 100.0, unit = "%", height = 23.dp)
                Text(tr("editor.hardness"), color = colors.textMuted, fontSize = 10.sp)
                CompactNumberSpinner((editor.hardness * 100).toDouble(), { editor.hardness = it.toFloat() / 100 }, Modifier.width(65.dp), min = 0.0, max = 95.0, unit = "%", height = 23.dp)
                // INFLATE direction: persistent toggle, inverted for the length of a stroke by holding Alt
                if (editor.tool == CanvasTool.INFLATE) {
                    CompactToggleChip(
                        text = tr("editor.mode.inflate"),
                        selected = !editor.inflateInvert,
                        onToggle = { editor.inflateInvert = false; focus() },
                        enabled = editor.editable,
                        height = 22.dp
                    )
                    CompactToggleChip(
                        text = tr("editor.mode.shrink"),
                        selected = editor.inflateInvert,
                        onToggle = { editor.inflateInvert = true; focus() },
                        enabled = editor.editable,
                        height = 22.dp
                    )
                }
            }

            // PATH_DEFORM tool options
            if (isPathTool) {
                CompactButton(tr("editor.newPath"), { editor.cancel(); editor.drawingPath = true; focus() }, enabled = target?.kind == "mesh" && editor.editable, height = 23.dp)
                if (editor.drawingPath) CompactButton(tr("editor.finishPath"), { editor.finishPath(); focus() }, enabled = editor.draft.size >= 2, height = 23.dp)
                val active = editor.selectedPath()
                if (active != null) {
                    if (!active.closed) CompactButton(tr("editor.extend"), { editor.extendPath(); focus() }, enabled = editor.editable, height = 23.dp)
                    CompactButton(tr("editor.delete"), { editor.deletePathPoint(); focus() }, enabled = editor.editable, height = 23.dp)
                    CompactButton(tr(if (active.closed) "editor.openPath" else "editor.closePath"), { editor.changePath { it.copy(closed = !it.closed) }; focus() }, enabled = editor.editable && active.points.size >= 3, height = 23.dp)
                    if (editor.pathPoint in active.points.indices) CompactButton(tr("editor.corner"), { editor.changePath { it.copy(points = it.points.mapIndexed { i, p -> if (i == editor.pathPoint) p.copy(corner = !p.corner) else p }) }; focus() }, enabled = editor.editable, height = 23.dp)
                    Text(tr("editor.width"), color = colors.textMuted, fontSize = 10.sp)
                    CompactNumberSpinner(active.width.toDouble(), { width -> editor.changePath { it.copy(width = width.toFloat()) } }, Modifier.width(82.dp), min = 0.001, max = 10000.0, decimals = 3, step = 0.01, enabled = editor.editable, height = 23.dp)
                    Text(tr("editor.hardness"), color = colors.textMuted, fontSize = 10.sp)
                    CompactNumberSpinner((active.hardness * 100).toDouble(), { h -> editor.changePath { it.copy(hardness = h.toFloat() / 100) } }, Modifier.width(64.dp), min = 0.0, max = 100.0, enabled = editor.editable, height = 23.dp)
                }
                CompactButton("L${editor.pathLevel}", { editor.pathLevel = if (editor.pathLevel == 2) 3 else 2; editor.activePath = null; focus() }, height = 23.dp)
            }

            CompactButton(tr("editor.undo"), { viewModel.undoHistory(); focus() }, enabled = editor.editable, height = 23.dp)
            CompactButton(tr("editor.redo"), { viewModel.redoHistory(); focus() }, enabled = editor.editable, height = 23.dp)
        }
    }

    // Bottom Status Bar
    Text(
        editor.error ?: if (editor.busy) tr("editor.saving") else tr("editor.selectionCount", editor.objects.size, editor.vertices.size) + "   ·   " + tr(when (editor.tool) {
            CanvasTool.PATH_DEFORM -> "editor.pathHint"
            CanvasTool.INFLATE -> "editor.inflateHint"
            else -> "editor.hint"
        }),
        color = if (editor.error != null) colors.error else colors.textMuted,
        fontSize = 10.sp,
        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(colors.panelBackground).padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.CanvasToolBar(
    editor: CanvasEditor,
    focus: () -> Unit,
) {
    val colors = LocalToolColors.current
    val toolbarInteractionSource = remember { MutableInteractionSource() }
    val isHoveredBySource by toolbarInteractionSource.collectIsHoveredAsState()
    var isHoveredByEvent by remember { mutableStateOf(false) }
    val isToolbarHovered = isHoveredBySource || isHoveredByEvent

    val animatedWidth by animateDpAsState(
        targetValue = if (isToolbarHovered) 156.dp else 34.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (isToolbarHovered) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isToolbarHovered) 150 else 80,
            delayMillis = if (isToolbarHovered) 40 else 0,
            easing = FastOutSlowInEasing,
        ),
    )
    val textOffset by animateDpAsState(
        targetValue = if (isToolbarHovered) 0.dp else (-6).dp,
        animationSpec = tween(
            durationMillis = if (isToolbarHovered) 180 else 80,
            delayMillis = if (isToolbarHovered) 30 else 0,
            easing = FastOutSlowInEasing,
        ),
    )
    val elevation by animateDpAsState(
        targetValue = if (isToolbarHovered) 8.dp else 2.dp,
        animationSpec = tween(durationMillis = 200),
    )

    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 8.dp, top = 44.dp)
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(4.dp),
                clip = false,
            )
            .width(animatedWidth)
            .background(colors.panelBackground.copy(alpha = 0.95f), RoundedCornerShape(4.dp))
            .border(
                1.dp,
                if (isToolbarHovered) colors.borderHover else colors.border,
                RoundedCornerShape(4.dp)
            )
            .hoverable(toolbarInteractionSource)
            .onPointerEvent(PointerEventType.Enter) { isHoveredByEvent = true }
            .onPointerEvent(PointerEventType.Exit) { isHoveredByEvent = false }
            .verticalScroll(rememberScrollState())
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CanvasTool.entries.forEach { tool ->
            ToolItemRow(
                tool = tool,
                isSelected = editor.tool == tool,
                isToolbarExpanded = animatedWidth > 42.dp,
                textAlpha = textAlpha,
                textOffset = textOffset,
                isBusy = editor.busy,
                onClick = {
                    editor.activateTool(tool)
                    focus()
                },
            )
        }
    }
}

@Composable
private fun ToolItemRow(
    tool: CanvasTool,
    isSelected: Boolean,
    isToolbarExpanded: Boolean,
    textAlpha: Float,
    textOffset: androidx.compose.ui.unit.Dp,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val itemInteractionSource = remember { MutableInteractionSource() }
    val isItemHovered by itemInteractionSource.collectIsHoveredAsState()

    val label = tr("editor.tool.${tool.name.lowercase()}")

    val bg = when {
        isSelected -> colors.accent.copy(alpha = 0.24f)
        isItemHovered -> colors.controlHover.copy(alpha = 0.7f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .semantics { contentDescription = "$label  ${tool.shortcut}" }
            .clickable(
                interactionSource = itemInteractionSource,
                indication = null,
                enabled = !isBusy,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            ToolIcon(
                tool = tool,
                color = when {
                    isSelected -> colors.accent
                    isItemHovered -> colors.textPrimary
                    else -> colors.textMuted
                },
            )
        }

        if (isToolbarExpanded) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .offset(x = textOffset)
                    .alpha(textAlpha)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = label,
                    color = when {
                        isSelected -> colors.textPrimary
                        isItemHovered -> colors.textPrimary
                        else -> colors.textMuted
                    },
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tool.shortcut.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) colors.accent.copy(alpha = 0.18f) else colors.panelElevated,
                                RoundedCornerShape(3.dp)
                            )
                            .border(
                                0.5.dp,
                                if (isSelected) colors.accent.copy(alpha = 0.4f) else colors.border.copy(alpha = 0.6f),
                                RoundedCornerShape(3.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tool.shortcut,
                            color = if (isSelected) colors.accent else colors.textDisabled,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolIcon(tool: CanvasTool, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val s = size.width / 18f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, p(x, y), p(a, b), 1.3f * s)
        when (tool) {
            CanvasTool.SELECT -> {
                val path = Path().apply { moveTo(3 * s, 2 * s); lineTo(14 * s, 10 * s); lineTo(9 * s, 11 * s); lineTo(7 * s, 16 * s); close() }
                drawPath(path, color, style = Stroke(s * 1.3f))
            }
            CanvasTool.MESH -> {
                val path = Path().apply { moveTo(9 * s, 2 * s); lineTo(16 * s, 15 * s); lineTo(2 * s, 15 * s); close() }
                drawPath(path, color, style = Stroke(s * 1.3f))
                drawCircle(color, 2 * s, p(9f, 2f))
                drawCircle(color, 2 * s, p(16f, 15f))
                drawCircle(color, 2 * s, p(2f, 15f))
            }
            CanvasTool.WARP -> {
                for (i in listOf(3f, 9f, 15f)) {
                    line(i, 3f, i, 15f)
                    line(3f, i, 15f, i)
                }
            }
            CanvasTool.BRUSH, CanvasTool.SMOOTH -> {
                line(6f, 11f, 14f, 3f)
                line(9f, 14f, 17f, 6f)
                line(14f, 3f, 17f, 6f)
                drawCircle(color, 3 * s, p(5f, 14f), style = Stroke(1.3f * s))
                if (tool == CanvasTool.SMOOTH) line(1f, 4f, 7f, 4f)
            }
            CanvasTool.INFLATE -> {
                drawCircle(color, 3.4f * s, p(9f, 9f), style = Stroke(1.3f * s))
                // Four barbs pointing outward: volume pushed away from the stroke
                listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
                    val tipX = 9f + dx * 7.8f
                    val tipY = 9f + dy * 7.8f
                    line(9f + dx * 5.2f, 9f + dy * 5.2f, tipX, tipY)
                    val perpX = -dy * 1.6f
                    val perpY = dx * 1.6f
                    line(tipX, tipY, tipX - dx * 2.2f + perpX, tipY - dy * 2.2f + perpY)
                    line(tipX, tipY, tipX - dx * 2.2f - perpX, tipY - dy * 2.2f - perpY)
                }
            }
            CanvasTool.PATH_DEFORM -> {
                val path = Path().apply { moveTo(2 * s, 14 * s); cubicTo(6 * s, -2 * s, 12 * s, 20 * s, 16 * s, 4 * s) }
                drawPath(path, color, style = Stroke(1.3f * s))
                drawCircle(color, 2 * s, p(2f, 14f))
                drawCircle(color, 2 * s, p(16f, 4f))
                drawCircle(color, 2 * s, p(9f, 9f))
            }
            CanvasTool.HAND -> {
                line(4f, 9f, 4f, 14f)
                line(4f, 14f, 8f, 17f)
                line(8f, 17f, 13f, 15f)
                line(13f, 15f, 15f, 6f)
                for (i in 6..12 step 2) line(i.toFloat(), 3f, i.toFloat(), 10f)
            }
        }
    }
}
