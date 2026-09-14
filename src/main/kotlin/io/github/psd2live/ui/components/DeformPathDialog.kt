package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.CanvasViewport
import io.github.psd2live.ui.RigCanvasSupport
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import java.awt.Cursor
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Professional DCC-grade transactional deformation workspace.
 * Cancel discards all uncommitted draft edits; Save appends one atomic history revision node.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DeformPathDialog(
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    onDismiss: () -> Unit,
) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current

    val initial = remember(state.projectOpenGeneration) { requireNotNull(state.previewModel) }
    val drawable = remember(state.selectedLayerId, initial) {
        initial.rig.puppet.drawables.firstOrNull {
            initial.rig.layerIdByDrawableId[it.id.raw] == state.selectedLayerId
        }
    }

    if (drawable == null || drawable.mesh == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val mesh = drawable.mesh
    val expectedState = remember(state.historySnapshot) { state.historySnapshot?.headNodeId }

    var model by remember { mutableStateOf(initial.rig.puppet) }
    var journal by remember { mutableStateOf(emptyList<JsonObject>()) }
    var mode by remember { mutableStateOf("edit") } // "edit" or "deform"
    var level by remember { mutableStateOf(2) } // 1 (hidden), 2 (main), 3 (aux)
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedPoint by remember { mutableStateOf(-1) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(emptyList<Pair<Float, Float>>()) }
    var draftId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showWireframe by remember { mutableStateOf(true) }

    var parameter by remember {
        mutableStateOf(
            drawable.geometryGrid?.axes?.firstOrNull()?.parameterId ?: model.parameters.firstOrNull()?.id
        )
    }
    var parameterMenu by remember { mutableStateOf(false) }
    var pose by remember { mutableStateOf(state.parameterValues.mapKeys { it.key.raw }) }

    var canvasSize by remember { mutableStateOf(IntSize(800, 520)) }
    var userZoom by remember { mutableStateOf(1.0) }
    var userPanX by remember { mutableStateOf(0.0) }
    var userPanY by remember { mutableStateOf(0.0) }
    var isPanning by remember { mutableStateOf(false) }
    var isSpacePressed by remember { mutableStateOf(false) }
    var lastPanPos by remember { mutableStateOf(Offset.Zero) }
    var lastCanvasClickTime by remember { mutableStateOf(0L) }

    var dragStart by remember { mutableStateOf<FloatArray?>(null) }
    var dragPath by remember { mutableStateOf<DeformPath?>(null) }
    var dragged by remember { mutableStateOf<List<Pair<Float, Float>>?>(null) }
    var previewVertices by remember { mutableStateOf<FloatArray?>(null) }
    var isDraggingPoint by remember { mutableStateOf(false) }
    var lastDragPos by remember { mutableStateOf(Offset.Zero) }
    var hoverPos by remember { mutableStateOf(Offset.Zero) }

    var previewPathWidth by remember(selected) { mutableStateOf<Float?>(null) }
    var previewPathHardness by remember(selected) { mutableStateOf<Float?>(null) }

    val focusRequester = remember { FocusRequester() }

    val geometry = remember(model, pose) {
        RigGeometryTools.geometry(model, "mesh", drawable.id.raw, pose)
    }
    val vertices = previewVertices ?: geometry.points
    val paths = model.deformPaths.filter { it.drawableId == drawable.id && it.editLevel == level }
    val active = paths.firstOrNull { it.id == selected }
    val restBounds = remember(drawable) { RigGeometryTools.bounds(mesh.positions) }
    val extent = max(restBounds[2], restBounds[3]).coerceAtLeast(1e-4f)

    val baseScale = remember(canvasSize, restBounds) {
        val bw = restBounds[2].coerceAtLeast(1e-4f).toDouble()
        val bh = restBounds[3].coerceAtLeast(1e-4f).toDouble()
        val padding = 44.0
        val availableW = (canvasSize.width - padding * 2.0).coerceAtLeast(50.0)
        val availableH = (canvasSize.height - padding * 2.0).coerceAtLeast(50.0)
        min(availableW / bw, availableH / bh).coerceIn(0.0001, 100000.0)
    }
    val scale = baseScale * userZoom

    val viewport = remember(scale, userPanX, userPanY, canvasSize, restBounds, initial) {
        val centerX = restBounds[0] + restBounds[2] / 2.0
        val centerY = restBounds[1] + restBounds[3] / 2.0
        val offX = canvasSize.width / 2.0 - centerX * scale + userPanX
        val offY = canvasSize.height / 2.0 - centerY * scale + userPanY
        CanvasViewport(scale, offX, offY, initial.rig.puppet.canvasWidth, initial.rig.puppet.canvasHeight)
    }

    fun screen(p: Pair<Float, Float>) = Offset(viewport.x(p.first).toFloat(), (viewport.offsetY + p.second * scale).toFloat())
    fun local(p: Offset) = ((p.x - viewport.offsetX) / scale).toFloat() to ((p.y - viewport.offsetY) / scale).toFloat()

    fun resetView() {
        userZoom = 1.0
        userPanX = 0.0
        userPanY = 0.0
    }

    fun zoomAt(mouseX: Float, mouseY: Float, delta: Float) {
        val oldScale = scale
        val nextZoom = (userZoom * 1.15.pow(-delta.toDouble())).coerceIn(0.1, 32.0)
        if (nextZoom == userZoom) return
        val nextScale = baseScale * nextZoom
        val canvasX = (mouseX - viewport.offsetX) / oldScale
        val canvasY = (mouseY - viewport.offsetY) / oldScale
        val centerX = restBounds[0] + restBounds[2] / 2.0
        val centerY = restBounds[1] + restBounds[3] / 2.0
        val baseOffX = canvasSize.width / 2.0 - centerX * nextScale
        val baseOffY = canvasSize.height / 2.0 - centerY * nextScale
        val targetOffX = mouseX - canvasX * nextScale
        val targetOffY = mouseY - canvasY * nextScale
        userZoom = nextZoom
        userPanX = targetOffX - baseOffX
        userPanY = targetOffY - baseOffY
    }

    fun commit(commands: List<JsonObject>) {
        try {
            val (next, entries) = RigAuthoringJournal.compile(model, JsonArray(commands))
            require(journal.size + entries.size <= 128) { tr("path.batchLimit") }
            model = next
            journal = journal + entries
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    fun put(path: DeformPath) = commit(listOf(DeformPathJournal.encode(path)))

    fun hit(pos: Offset): Pair<DeformPath, Int>? = paths.flatMap { p ->
        DeformPathTools.positions(p, geometry.points).mapIndexed { i, xy -> Triple(p, i, (screen(xy) - pos).getDistance()) }
    }.filter { it.third < 14f }.minByOrNull { it.third }?.let { it.first to it.second }

    fun hitCurve(pos: Offset): DeformPath? = paths.firstOrNull { p ->
        val positions = DeformPathTools.positions(p, geometry.points)
        if (positions.size < 2) false
        else {
            val sampled = DeformPathTools.curve(positions, p.points.map { it.corner }, p.closed)
            sampled.any { pt -> (screen(pt) - pos).getDistance() < 10f }
        }
    }

    fun key(): Map<String, Float> = buildMap {
        for (axis in geometry.axes) put(axis.parameterId.raw, pose[axis.parameterId.raw] ?: model.parameters.single { it.id == axis.parameterId }.default)
        parameter?.let { id -> put(id.raw, pose[id.raw] ?: model.parameters.single { it.id == id }.default) }
    }

    fun capture(points: FloatArray) {
        commit(listOf(buildJsonObject {
            put("op", "set")
            put("target", "mesh:${drawable.id.raw}")
            put("key", JsonObject(key().mapValues { JsonPrimitive(it.value) }))
            putJsonObject("geometry") {
                put("positionDeltas", JsonArray(points.indices.map { JsonPrimitive(points[it] - mesh.positions[it]) }))
            }
        }))
    }

    fun finishDraft() {
        try {
            val previous = paths.firstOrNull { it.id == draftId }
            val points = draft.mapIndexed { i, p ->
                DeformPathTools.bind(vertices, mesh.indices, p.first, p.second, previous?.points?.getOrNull(i)?.corner ?: false)
            }
            val path = previous?.copy(points = points) ?: DeformPath(UUID.randomUUID().toString(), drawable.id, points, extent * 0.12f, editLevel = level)
            put(path)
            selected = path.id
            selectedPoint = -1
            adding = false
            draft = emptyList()
            draftId = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    fun cancelDraft() {
        adding = false
        draft = emptyList()
        draftId = null
    }

    fun extendPath() {
        active?.let {
            mode = "edit"
            adding = true
            draft = DeformPathTools.positions(it, vertices)
            draftId = it.id
        }
    }

    fun deleteActivePath() {
        active?.let {
            commit(listOf(buildJsonObject { put("op", "path_delete"); put("id", it.id) }))
            selected = null
            selectedPoint = -1
        }
    }

    fun undoLastEdit() {
        if (journal.isNotEmpty() && !saving) {
            journal = journal.dropLast(1)
            model = journal.fold(initial.rig.puppet) { m, e -> RigAuthoringJournal.apply(m, e) }
            error = null
        }
    }

    fun doSave() {
        if (expectedState != null && journal.isNotEmpty() && !saving) {
            saving = true
            viewModel.saveDeformPathEdits(expectedState, JsonArray(journal)) { failure ->
                saving = false
                if (failure == null) onDismiss() else error = failure
            }
        }
    }

    fun handleDismiss() {
        if (saving) return
        if (journal.isNotEmpty()) {
            showDiscardConfirm = true
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val bitmap = remember(vertices, canvasSize, viewport.scale, viewport.offsetX, viewport.offsetY) {
        val isolated = drawable.copy(
            parentDeformerId = null,
            mesh = DrawableMesh(vertices, mesh.uvs, mesh.indices),
            geometryGrid = null,
            maskedBy = emptyList(),
            channelGrids = ChannelGrids.Empty,
            blendShapes = emptyList(),
            isVisible = true,
            opacity = 1f,
        )
        val puppet = initial.rig.puppet.copy(
            drawables = listOf(isolated),
            deformers = emptyList(),
            parts = emptyList(),
            glues = emptyList(),
            rootChildren = listOf(OrgChild.Drawable(drawable.id)),
            rootPartId = null,
            renderRoot = RenderGroup(null, DEFAULT_DRAW_ORDER, emptyList()),
        )
        val preview = initial.copy(rig = initial.rig.copy(puppet = puppet))
        BufferedImage(canvasSize.width.coerceAtLeast(1), canvasSize.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB).also { image ->
            val g = image.createGraphics()
            try {
                RigCanvasSupport.paintChecker(g, image.width, image.height)
                RigCanvasSupport.paintTexturedRig(g, preview, RigCanvasSupport.evaluate(preview), viewport)
            } finally {
                g.dispose()
            }
        }.toComposeImageBitmap()
    }

    // Modal Scrim & Workspace Window Container
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val isCtrl = event.isCtrlPressed || event.isMetaPressed
                    when {
                        isCtrl && event.key == Key.Z -> { undoLastEdit(); true }
                        isCtrl && event.key == Key.S -> { doSave(); true }
                        event.key == Key.Enter -> {
                            if (adding && draft.size >= 2) { finishDraft(); true } else false
                        }
                        event.key == Key.Escape -> {
                            if (adding) { cancelDraft(); true }
                            else if (selectedPoint != -1) { selectedPoint = -1; true }
                            else if (selected != null) { selected = null; true }
                            else { handleDismiss(); true }
                        }
                        event.key == Key.Delete || event.key == Key.Backspace -> {
                            if (selectedPoint != -1 && active != null && active.points.size > (if (active.closed) 3 else 2)) {
                                put(active.copy(points = active.points.filterIndexed { i, _ -> i != selectedPoint }))
                                selectedPoint = -1
                                true
                            } else if (active != null && !adding) {
                                deleteActivePath()
                                true
                            } else false
                        }
                        event.key == Key.F || event.key == Key.MoveHome || event.key == Key.Zero -> {
                            resetView(); true
                        }
                        event.key == Key.Spacebar -> { isSpacePressed = true; true }
                        else -> false
                    }
                } else if (event.type == KeyEventType.KeyUp) {
                    if (event.key == Key.Spacebar) { isSpacePressed = false; true } else false
                } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                handleDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 960.dp, max = 1500.dp)
                .heightIn(min = 680.dp, max = 960.dp)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.94f)
                .background(colors.panelBackground, RoundedCornerShape(6.dp))
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(6.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            // 1. Top Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(colors.panelElevated, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconDeformPath(modifier = Modifier.size(16.dp), tint = colors.accent)
                    Text(
                        text = "${tr("path.title")} — ${drawable.name}",
                        style = typography.title.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )
                    // Mesh metadata chips
                    Box(
                        modifier = Modifier
                            .background(colors.inputBackground, RoundedCornerShape(2.dp))
                            .border(BorderStroke(0.5.dp, colors.border), RoundedCornerShape(2.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${mesh.positions.size / 2} V / ${mesh.indices.size / 3} T",
                            style = typography.monoSmall.copy(fontSize = 10.sp),
                            color = colors.textMuted,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Staged Changes Indicator Badge
                    if (journal.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1E2D24), RoundedCornerShape(3.dp))
                                .border(BorderStroke(1.dp, Color(0xFF356B4A)), RoundedCornerShape(3.dp))
                                .padding(horizontal = 7.dp, vertical = 2.5.dp),
                        ) {
                            Text(
                                text = tr("path.clean"),
                                style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                                color = Color(0xFF4EC9B0),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF382914), RoundedCornerShape(3.dp))
                                .border(BorderStroke(1.dp, colors.warning), RoundedCornerShape(3.dp))
                                .padding(horizontal = 7.dp, vertical = 2.5.dp),
                        ) {
                            Text(
                                text = tr("path.stagedEdits", journal.size),
                                style = typography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                                color = colors.warning,
                            )
                        }
                    }

                    CompactIconButton(
                        onClick = ::handleDismiss,
                        enabled = !saving,
                        size = 22.dp,
                    ) {
                        IconClose(modifier = Modifier.size(11.dp), tint = colors.textMuted)
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

            // 2. Ribbon Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .background(colors.panelBackground)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Mode Toggle Segment
                Row(
                    modifier = Modifier
                        .background(colors.inputBackground, RoundedCornerShape(3.dp))
                        .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CompactButton(
                        text = tr("path.edit"),
                        onClick = { mode = "edit"; adding = false; draft = emptyList() },
                        isPrimary = mode == "edit",
                        enabled = !saving,
                        height = 22.dp,
                    )
                    CompactButton(
                        text = tr("path.deform"),
                        onClick = { mode = "deform"; adding = false; draft = emptyList() },
                        isPrimary = mode == "deform",
                        enabled = !saving,
                        height = 22.dp,
                    )
                }

                Box(modifier = Modifier.width(1.dp).height(20.dp).background(colors.divider))

                // Edit Level Toggle
                Row(
                    modifier = Modifier
                        .background(colors.inputBackground, RoundedCornerShape(3.dp))
                        .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (l in 1..3) {
                        val count = model.deformPaths.count { it.drawableId == drawable.id && it.editLevel == l }
                        val label = when (l) {
                            1 -> tr("path.level1Hint")
                            2 -> tr("path.level2Hint")
                            else -> tr("path.level3Hint")
                        } + if (count > 0) " ($count)" else ""

                        CompactButton(
                            text = label,
                            onClick = { level = l; selected = null; adding = false; draft = emptyList() },
                            isPrimary = level == l,
                            enabled = !saving,
                            height = 22.dp,
                        )
                    }
                }

                Box(modifier = Modifier.width(1.dp).height(20.dp).background(colors.divider))

                // Path Creation & Management
                if (adding) {
                    CompactButton(
                        text = "${tr("path.finish")} (Enter)",
                        onClick = ::finishDraft,
                        isPrimary = true,
                        enabled = draft.size >= 2 && !saving,
                        height = 22.dp,
                    )
                    CompactButton(
                        text = "${tr("path.cancelDraft")} (Esc)",
                        onClick = ::cancelDraft,
                        enabled = !saving,
                        height = 22.dp,
                    )
                } else {
                    CompactButton(
                        text = "+ ${tr("path.new")}",
                        onClick = { mode = "edit"; adding = true; draft = emptyList(); draftId = null; selected = null },
                        enabled = level > 1 && !saving,
                        height = 22.dp,
                    )
                    CompactButton(
                        text = tr("path.extend"),
                        onClick = ::extendPath,
                        enabled = active != null && !saving,
                        height = 22.dp,
                    )
                    CompactButton(
                        text = tr("path.delete"),
                        onClick = ::deleteActivePath,
                        enabled = active != null && !saving,
                        height = 22.dp,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Viewport & History Actions
                CompactButton(
                    text = tr("path.wireframe"),
                    onClick = { showWireframe = !showWireframe },
                    isPrimary = showWireframe,
                    enabled = !saving,
                    height = 22.dp,
                )

                CompactButton(
                    text = tr("path.fit"),
                    onClick = ::resetView,
                    enabled = !saving,
                    height = 22.dp,
                )

                CompactButton(
                    text = "${tr("path.undo")} (${journal.size})",
                    onClick = ::undoLastEdit,
                    enabled = journal.isNotEmpty() && !saving,
                    height = 22.dp,
                )
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

            // 3. Middle Main Body (Canvas + Properties Inspector)
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Canvas Area (Center/Left)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(colors.windowBackground)
                        .onSizeChanged { canvasSize = it }
                        .pointerHoverIcon(
                            PointerIcon(
                                when {
                                    isPanning || isSpacePressed -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                                    adding -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                                    isDraggingPoint -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                    else -> Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
                                }
                            )
                        )
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val change = event.changes.firstOrNull() ?: return@onPointerEvent
                            zoomAt(change.position.x, change.position.y, change.scrollDelta.y)
                        }
                        .onPointerEvent(PointerEventType.Press) { event ->
                            focusRequester.requestFocus()
                            val change = event.changes.firstOrNull() ?: return@onPointerEvent
                            hoverPos = change.position

                            if (event.button == PointerButton.Tertiary || (isSpacePressed && event.button == PointerButton.Primary)) {
                                isPanning = true
                                lastPanPos = change.position
                            } else if (event.button == PointerButton.Primary && !saving) {
                                val now = System.currentTimeMillis()
                                val hitResult = if (!adding) hit(change.position) else null
                                val curveHit = if (!adding && hitResult == null) hitCurve(change.position) else null
                                if (!adding && hitResult == null && curveHit == null && now - lastCanvasClickTime < 280L) {
                                    resetView()
                                    lastCanvasClickTime = 0L
                                } else {
                                    lastCanvasClickTime = now
                                    if (adding) {
                                        if (draft.size < 128) {
                                            draft = draft + local(change.position)
                                        }
                                    } else if (hitResult != null) {
                                        val (p, i) = hitResult
                                        selected = p.id
                                        selectedPoint = i
                                        dragPath = p
                                        dragStart = geometry.points.copyOf()
                                        dragged = DeformPathTools.positions(p, geometry.points)
                                        isDraggingPoint = true
                                        lastDragPos = change.position
                                    } else if (curveHit != null) {
                                        selected = curveHit.id
                                        selectedPoint = -1
                                    } else {
                                        selectedPoint = -1
                                    }
                                }
                            } else if (event.button == PointerButton.Secondary && !saving) {
                                if (adding) {
                                    if (draft.size >= 2) finishDraft() else cancelDraft()
                                } else {
                                    selectedPoint = -1
                                }
                            }
                        }
                        .onPointerEvent(PointerEventType.Move) { event ->
                            val change = event.changes.firstOrNull() ?: return@onPointerEvent
                            hoverPos = change.position

                            if (isPanning) {
                                val delta = change.position - lastPanPos
                                userPanX += delta.x
                                userPanY += delta.y
                                lastPanPos = change.position
                            } else if (isDraggingPoint) {
                                val path = dragPath
                                val original = dragStart
                                val previous = dragged
                                if (path != null && original != null && previous != null && selectedPoint in previous.indices) {
                                    val delta = change.position - lastDragPos
                                    val localDeltaX = (delta.x / scale).toFloat()
                                    val localDeltaY = (delta.y / scale).toFloat()
                                    val moved = previous.mapIndexed { i, p ->
                                        if (i == selectedPoint) (p.first + localDeltaX) to (p.second + localDeltaY) else p
                                    }
                                    dragged = moved
                                    lastDragPos = change.position
                                    if (mode == "deform") {
                                        try {
                                            previewVertices = DeformPathTools.deform(original, paths, path.id, moved)
                                            error = null
                                        } catch (e: Exception) {
                                            previewVertices = null
                                            error = e.message
                                        }
                                    }
                                }
                            }
                        }
                        .onPointerEvent(PointerEventType.Release) { event ->
                            if (isPanning) {
                                isPanning = false
                            }
                            if (isDraggingPoint) {
                                val path = dragPath
                                val moved = dragged
                                if (path != null && moved != null) {
                                    if (mode == "edit") {
                                        put(path.copy(points = moved.mapIndexed { i, p ->
                                            DeformPathTools.bind(geometry.points, mesh.indices, p.first, p.second, path.points[i].corner)
                                        }))
                                    } else {
                                        previewVertices?.let(::capture)
                                    }
                                }
                                isDraggingPoint = false
                                dragStart = null
                                dragPath = null
                                dragged = null
                                previewVertices = null
                            }
                        },
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawImage(bitmap)

                        // Mesh Wireframe Overlay
                        if (showWireframe) {
                            for (i in mesh.indices.indices step 3) {
                                val triangle = (0..2).map { mesh.indices[i + it] }
                                    .map { screen(vertices[it * 2] to vertices[it * 2 + 1]) }
                                for (j in 0..2) {
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.16f),
                                        start = triangle[j],
                                        end = triangle[(j + 1) % 3],
                                        strokeWidth = 1f,
                                    )
                                }
                            }
                        }

                        fun drawPathCurve(
                            points: List<Pair<Float, Float>>,
                            corners: List<Boolean>,
                            closed: Boolean,
                            curveColor: Color,
                            isSelectedPath: Boolean,
                        ) {
                            if (points.size >= 2) {
                                val curvePoints = DeformPathTools.curve(points, corners, closed)
                                curvePoints.zipWithNext().forEach { (a, b) ->
                                    drawLine(curveColor, screen(a), screen(b), if (isSelectedPath) 2.8f else 1.8f)
                                }
                            }

                            points.forEachIndexed { i, p ->
                                val pt = screen(p)
                                val isCorner = corners.getOrNull(i) == true
                                val isPtSelected = isSelectedPath && i == selectedPoint

                                if (isCorner) {
                                    // Diamond shape for sharp corner
                                    val d = 6f
                                    val diamondPath = Path().apply {
                                        moveTo(pt.x, pt.y - d)
                                        lineTo(pt.x + d, pt.y)
                                        lineTo(pt.x, pt.y + d)
                                        lineTo(pt.x - d, pt.y)
                                        close()
                                    }
                                    drawPath(diamondPath, color = Color(0xFFFFCA28), style = Fill)
                                    drawPath(diamondPath, color = Color.White, style = Stroke(width = 1.2f))
                                } else {
                                    // Circular point for smooth curvature
                                    drawCircle(color = if (isSelectedPath) Color(0xFF00E676) else curveColor, radius = 5.5f, center = pt)
                                    drawCircle(color = Color.White, radius = 5.5f, center = pt, style = Stroke(width = 1.2f))
                                }

                                if (isPtSelected) {
                                    // Outer ring indicator for selected point
                                    drawCircle(color = Color.White, radius = 9f, center = pt, style = Stroke(width = 2f))
                                }
                            }
                        }

                        // Draw existing paths in the active level
                        for (path in paths) {
                            val isPathSelected = path.id == selected
                            val points = if (dragPath?.id == path.id) dragged ?: DeformPathTools.positions(path, vertices) else DeformPathTools.positions(path, vertices)
                            val pathColor = if (isPathSelected) Color(0xFF00E676) else Color(0xFF388E9C)

                            drawPathCurve(points, path.points.map { it.corner }, path.closed, pathColor, isPathSelected)

                            // Draw Influence (Width) and Hardness Falloff Radii for active path
                            if (isPathSelected) {
                                val effWidth = previewPathWidth ?: path.width
                                val effHardness = previewPathHardness ?: path.hardness
                                val outerRadius = (effWidth * scale).toFloat()
                                val innerRadius = outerRadius * effHardness.coerceIn(0f, 1f)

                                for ((i, ptPos) in points.withIndex()) {
                                    val isPtActive = (selectedPoint == i)
                                    val isAllActive = (selectedPoint == -1)
                                    if (!isPtActive && !isAllActive) continue

                                    val center = screen(ptPos)
                                    val strokeAlpha = if (isPtActive) 1f else 0.45f
                                    val fillAlpha = if (isPtActive) 1f else 0.35f

                                    // Falloff core (Hardness)
                                    if (innerRadius > 1f) {
                                        drawCircle(
                                            color = Color(0x304B7EE8).copy(alpha = 0.18f * fillAlpha),
                                            radius = innerRadius,
                                            center = center,
                                            style = Fill,
                                        )
                                        drawCircle(
                                            color = Color(0xFF4B7EE8).copy(alpha = strokeAlpha),
                                            radius = innerRadius,
                                            center = center,
                                            style = Stroke(width = if (isPtActive) 1.4f else 1.0f),
                                        )
                                    }
                                    // Outer boundary (Width) - Dashed circle
                                    if (outerRadius > 1f) {
                                        drawCircle(
                                            color = Color(0xFFE53935).copy(alpha = if (isPtActive) 0.85f else 0.4f),
                                            radius = outerRadius,
                                            center = center,
                                            style = Stroke(
                                                width = if (isPtActive) 1.4f else 1.0f,
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f),
                                            ),
                                        )
                                    }
                                }
                            }
                        }

                        // Draw drafting path & rubber band
                        if (adding) {
                            drawPathCurve(draft, draft.map { false }, false, Color(0xFF76FF03), true)
                            if (draft.isNotEmpty()) {
                                drawLine(
                                    color = Color(0xAA76FF03),
                                    start = screen(draft.last()),
                                    end = hoverPos,
                                    strokeWidth = 1.5f,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }

                    // Floating In-Canvas HUD (Top-Left: Guidance; Bottom-Left: Camera Info)
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xCC1E1F22), RoundedCornerShape(4.dp))
                                .border(BorderStroke(1.dp, colors.border.copy(alpha = 0.6f)), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = when {
                                    adding -> tr("path.addHint")
                                    mode == "edit" -> tr("path.editHint")
                                    else -> tr("path.deformHint")
                                },
                                style = typography.caption.copy(fontSize = 11.sp),
                                color = colors.textPrimary,
                            )
                        }
                    }

                    // Bottom-Left Camera & View Controls Pill
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                            .background(Color(0xCC1E1F22), RoundedCornerShape(4.dp))
                            .border(BorderStroke(1.dp, colors.border.copy(alpha = 0.6f)), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${(userZoom * 100).toInt()}%",
                            style = typography.monoSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                            color = colors.accent,
                        )
                        Box(modifier = Modifier.width(1.dp).height(12.dp).background(colors.divider))
                        Text(
                            text = tr("path.viewHint"),
                            style = typography.caption.copy(fontSize = 10.sp),
                            color = colors.textMuted,
                        )
                    }
                }

                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))

                // Right DCC Properties Inspector (280.dp)
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .background(colors.panelElevated)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Section 1: Paths List in Active Level
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = tr("path.pathList"),
                                style = typography.title.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                color = colors.textPrimary,
                            )
                            Box(
                                modifier = Modifier
                                    .background(colors.inputBackground, RoundedCornerShape(2.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "${paths.size}",
                                    style = typography.monoSmall.copy(fontSize = 10.sp),
                                    color = colors.textMuted,
                                )
                            }
                        }

                        if (paths.isEmpty()) {
                            Text(
                                text = tr("path.noPathsInLevel"),
                                style = typography.caption.copy(fontSize = 11.sp),
                                color = colors.textMuted,
                            )
                        } else {
                            paths.forEachIndexed { index, p ->
                                val isPathSelected = p.id == selected
                                val bg = if (isPathSelected) colors.selection else colors.inputBackground
                                val borderCol = if (isPathSelected) colors.accent else colors.border

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(bg, RoundedCornerShape(3.dp))
                                        .border(BorderStroke(1.dp, borderCol), RoundedCornerShape(3.dp))
                                        .clickable {
                                            selected = p.id
                                            selectedPoint = -1
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        IconDeformPath(
                                            modifier = Modifier.size(12.dp),
                                            tint = if (isPathSelected) colors.accent else colors.textMuted,
                                        )
                                        Text(
                                            text = "路径 ${index + 1} (${p.points.size} 点${if (p.closed) ", 闭合" else ""})",
                                            style = typography.body.copy(fontSize = 11.sp, fontWeight = if (isPathSelected) FontWeight.SemiBold else FontWeight.Normal),
                                            color = if (isPathSelected) colors.accentText else colors.textPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                    // Section 2: Selected Path Properties
                    if (active != null) {
                        var currentWidth by remember(active.id, active.width) {
                            mutableStateOf((active.width / extent).coerceIn(0.001f, 1f))
                        }
                        var currentHardness by remember(active.id, active.hardness) {
                            mutableStateOf(active.hardness.coerceIn(0f, 1f))
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = tr("path.pathProperties"),
                                style = typography.title.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                color = colors.textPrimary,
                            )

                            // Width
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(tr("path.width"), style = typography.caption, color = colors.textPrimary)
                                    Text("%.1f%%".format(currentWidth * 100), style = typography.monoSmall, color = colors.textMuted)
                                }
                                CompactSlider(
                                    value = currentWidth,
                                    onValueChange = {
                                        currentWidth = it
                                        previewPathWidth = it * extent
                                    },
                                    onValueChangeFinished = {
                                        put(active.copy(width = currentWidth * extent))
                                        previewPathWidth = null
                                    },
                                    valueRange = 0.001f..1f,
                                    enabled = !saving,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            // Hardness
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(tr("path.hardness"), style = typography.caption, color = colors.textPrimary)
                                    Text("%.2f".format(currentHardness), style = typography.monoSmall, color = colors.textMuted)
                                }
                                CompactSlider(
                                    value = currentHardness,
                                    onValueChange = {
                                        currentHardness = it
                                        previewPathHardness = it
                                    },
                                    onValueChangeFinished = {
                                        put(active.copy(hardness = currentHardness))
                                        previewPathHardness = null
                                    },
                                    valueRange = 0f..1f,
                                    enabled = !saving,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            // Closed Curve Toggle
                            CompactCheckbox(
                                checked = active.closed,
                                onCheckedChange = { put(active.copy(closed = !active.closed)) },
                                enabled = active.points.size >= 3 && !saving,
                                label = tr("path.closed"),
                            )
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                        // Section 3: Selected Point Properties
                        if (selectedPoint in active.points.indices) {
                            val pt = active.points[selectedPoint]
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = tr("path.pointIndex", selectedPoint + 1, active.points.size),
                                    style = typography.title.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                    color = colors.textPrimary,
                                )

                                CompactCheckbox(
                                    checked = pt.corner,
                                    onCheckedChange = {
                                        put(active.copy(points = active.points.mapIndexed { i, p ->
                                            if (i == selectedPoint) p.copy(corner = !p.corner) else p
                                        }))
                                    },
                                    enabled = !saving,
                                    label = tr("path.corner"),
                                )

                                CompactButton(
                                    text = tr("path.removePoint"),
                                    onClick = {
                                        put(active.copy(points = active.points.filterIndexed { i, _ -> i != selectedPoint }))
                                        selectedPoint = -1
                                    },
                                    enabled = active.points.size > (if (active.closed) 3 else 2) && !saving,
                                    modifier = Modifier.fillMaxWidth(),
                                    leadingIcon = { IconTrash(modifier = Modifier.size(11.dp), tint = colors.error) },
                                )
                            }

                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        }
                    }

                    // Section 4: Target Parameter & Keyform Binding
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = tr("path.paramBinding"),
                            style = typography.title.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                            color = colors.textPrimary,
                        )

                        Text(
                            text = tr("path.paramHint"),
                            style = typography.caption.copy(fontSize = 10.5.sp),
                            color = colors.textMuted,
                        )

                        // Parameter Dropdown
                        Box {
                            CompactButton(
                                text = model.parameters.firstOrNull { it.id == parameter }?.let { "${it.name} (${it.id.raw})" }
                                    ?: parameter?.raw ?: tr("path.noParameter"),
                                onClick = { parameterMenu = true },
                                enabled = !saving,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(
                                expanded = parameterMenu,
                                onDismissRequest = { parameterMenu = false },
                            ) {
                                for (p in model.parameters) {
                                    DropdownMenuItem(
                                        onClick = {
                                            parameter = p.id
                                            parameterMenu = false
                                        }
                                    ) {
                                        Text(
                                            text = "${p.name} (${p.id.raw})",
                                            style = typography.body.copy(fontSize = 11.5.sp),
                                            color = colors.textPrimary,
                                        )
                                    }
                                }
                            }
                        }

                        // Parameter Slider
                        model.parameters.firstOrNull { it.id == parameter }?.let { p ->
                            val currentVal = pose[p.id.raw] ?: p.default
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("${p.min}", style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
                                    Text("%.3f".format(currentVal), style = typography.mono.copy(fontSize = 11.sp), color = colors.accent)
                                    Text("${p.max}", style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
                                }
                                CompactSlider(
                                    value = currentVal,
                                    onValueChange = { pose = pose + (p.id.raw to it) },
                                    valueRange = p.min..p.max,
                                    enabled = !saving && !adding,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

            // 4. Bottom Staging Dock & Commit Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(colors.panelElevated, RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left Staging Status & Error
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (journal.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.warning),
                        )
                        Text(
                            text = tr("path.stagedEdits", journal.size),
                            style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                            color = colors.warning,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4EC9B0)),
                        )
                        Text(
                            text = tr("path.clean"),
                            style = typography.caption.copy(fontSize = 11.sp),
                            color = colors.textMuted,
                        )
                    }

                    if (error != null) {
                        Text(
                            text = error ?: "",
                            style = typography.caption.copy(fontSize = 11.sp),
                            color = colors.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Right Action Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompactButton(
                        text = tr("path.discard"),
                        onClick = ::handleDismiss,
                        enabled = !saving,
                        height = 26.dp,
                    )

                    CompactButton(
                        text = if (saving) "保存中…" else "${tr("path.save")} (Ctrl+S)",
                        onClick = ::doSave,
                        isPrimary = true,
                        enabled = journal.isNotEmpty() && expectedState != null && !saving && !adding,
                        height = 26.dp,
                        leadingIcon = { IconCheck(modifier = Modifier.size(11.dp), tint = Color.White) },
                    )
                }
            }
        }

        // Discard Confirmation Dialog Popover
        if (showDiscardConfirm) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable { showDiscardConfirm = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .width(360.dp)
                        .background(colors.panelBackground, RoundedCornerShape(6.dp))
                        .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(6.dp))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = tr("path.discard"),
                        style = typography.title.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                    )

                    Text(
                        text = tr("path.discardConfirm"),
                        style = typography.body.copy(fontSize = 11.5.sp),
                        color = colors.textMuted,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        CompactButton(
                            text = tr("exportPsd.cancel"),
                            onClick = { showDiscardConfirm = false },
                            height = 24.dp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        CompactButton(
                            text = tr("path.discard"),
                            onClick = {
                                showDiscardConfirm = false
                                onDismiss()
                            },
                            isPrimary = true,
                            height = 24.dp,
                        )
                    }
                }
            }
        }
    }
}
