package io.github.psd2live.ui.views

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.TreeContextMenu
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.ViewOptionsMenuItems
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.*
import io.github.psd2live.ui.tutorial.TutorialTargetId
import io.github.psd2live.ui.tutorial.tutorialTarget
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.awt.MouseInfo
import java.awt.Cursor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

private data class DockHitArea(val body: Rect, val header: Rect, val edge: Float)

private class DockSession(initial: DockNode) {
    var root by mutableStateOf<DockNode?>(initial)
    var hiddenModules: Set<String> = emptySet()
    val visibleRoot: DockNode?
        get() = hiddenModules.fold(root) { layout, module -> layout?.remove(module) }
    val floating = mutableStateMapOf<String, WindowState>()
    val bounds = mutableMapOf<String, () -> DockHitArea?>()
    var workspaceBounds: (() -> Rect?)? = null
    private val returnTargets = mutableMapOf<String, String>()
    val floatingWindows = mutableMapOf<String, java.awt.Window>()
    var junctionHighlights by mutableStateOf<Set<String>>(emptySet())
    var dragging by mutableStateOf<String?>(null)
    var target by mutableStateOf<Pair<String, DockSide>?>(null)
    private var dragPreview: javax.swing.JWindow? = null
    private var dragWindow: java.awt.Window? = null
    private var grabOffset = java.awt.Point()

    fun begin(module: String, title: String, window: java.awt.Window?, colors: io.github.psd2live.ui.theme.ToolColors) {
        cancel()
        dragging = module
        val point = MouseInfo.getPointerInfo()?.location ?: return
        dragWindow = window
        if (window != null) {
            grabOffset = java.awt.Point(point.x - window.x, point.y - window.y)
        } else {
            // A non-focusable preview follows the pointer without moving/remounting the panel
            // during the gesture, preserving pointer capture and editor state.
            val area = root?.containing(module)?.let { bounds[it.id]?.invoke()?.body }
            val text = colors.textPrimary
            val panel = colors.panelBackground
            val accent = colors.accent
            dragPreview = javax.swing.JWindow().apply {
                focusableWindowState = false
                isAlwaysOnTop = true
                val label = javax.swing.JLabel(title).apply {
                    foreground = java.awt.Color(text.red, text.green, text.blue)
                    background = java.awt.Color(panel.red, panel.green, panel.blue)
                    isOpaque = true
                    verticalAlignment = javax.swing.SwingConstants.TOP
                    border = javax.swing.BorderFactory.createCompoundBorder(
                        javax.swing.BorderFactory.createLineBorder(
                            java.awt.Color(accent.red, accent.green, accent.blue),
                        ),
                        javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8))
                }
                contentPane.add(label)
                setSize(area?.width?.toInt()?.coerceIn(160, 520) ?: 260,
                    area?.height?.toInt()?.coerceIn(80, 420) ?: 160)
                setLocation(point.x + 16, point.y + 16)
                runCatching { opacity = .72f }
                isVisible = true
            }
        }
        track()
    }

    fun cancel() {
        dragPreview?.dispose()
        dragPreview = null
        dragWindow = null
        dragging = null
        target = null
    }

    fun track() {
        val point = MouseInfo.getPointerInfo()?.location ?: return
        val p = Offset(point.x.toFloat(), point.y.toFloat())
        target = null
        if (dragging == null) return
        dragPreview?.setLocation(point.x + 16, point.y + 16)
        dragWindow?.setLocation(point.x - grabOffset.x, point.y - grabOffset.y)
        // A floating window covering a dock is not a dock target.
        if (floatingWindows.any { (id, window) -> id != dragging && window.isShowing && window.bounds.contains(point) }) return
        if (visibleRoot == null && workspaceBounds?.invoke()?.contains(p) == true) {
            target = "empty" to DockSide.CENTER
            return
        }
        for ((id, read) in bounds) {
            val area = read() ?: continue
            val node = visibleRoot?.find(id) ?: continue
            if (!area.body.contains(p) || node.modules == listOf(dragging)) continue
            val r = area.body
            // Only the tab strip and narrow outer edges accept a drop. Content never does.
            val side = when {
                p.y < r.top + area.edge / 2 && r.height >= 200f -> DockSide.TOP
                area.header.contains(p) -> if (dragging in node.modules) null else DockSide.CENTER
                p.x < r.left + area.edge && r.width >= 320f -> DockSide.LEFT
                p.x > r.right - area.edge && r.width >= 320f -> DockSide.RIGHT
                p.y > r.bottom - area.edge && r.height >= 200f -> DockSide.BOTTOM
                else -> null
            }
            if (side != null) target = id to side
            return
        }
    }

    fun detach(module: String) {
        val point = MouseInfo.getPointerInfo()?.location
        root?.containing(module)?.let { returnTargets[module] = it.id }
        root = root?.remove(module)
        floating[module] = WindowState(width = 520.dp, height = 620.dp,
            position = if (point == null) WindowPosition.PlatformDefault
                else WindowPosition(point.x.dp - 60.dp, point.y.dp - 15.dp))
    }

    fun finish() {
        val module = dragging ?: return
        val destination = target
        if (destination != null) {
            root = dockModule(root, module, destination.first, destination.second)
            floating.remove(module)
        } else if (module !in floating) {
            val point = MouseInfo.getPointerInfo()?.location
            val inside = point != null && workspaceBounds?.invoke()?.contains(Offset(point.x.toFloat(), point.y.toFloat())) == true
            // An invalid location inside the workspace cancels; only outside detaches.
            if (!inside) detach(module)
        }
        cancel()
    }

    fun returnToDock(module: String) {
        val previous = returnTargets.remove(module)?.takeIf { root?.find(it) != null }
        root = dockModule(root, module, previous, if (previous != null) DockSide.CENTER else DockSide.RIGHT)
        floating.remove(module)
    }
}

private val dockJson = Json { ignoreUnknownKeys = true }

@Composable
internal fun DockWorkspaceView(
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    modifier: Modifier = Modifier,
    mainWindow: java.awt.Window? = null,
	onStartTutorial: (() -> Unit)? = null,
	onOpenProject: (() -> Unit)? = null,
	onOpenPsd: (() -> Unit)? = null,
) {
    val sessions = remember(state.projectOpenGeneration) { mutableMapOf<String, DockSession>() }
    val workspace = state.activeWorkspace
    val session = sessions.getOrPut(workspace.id) {
        val allowed = DEFAULT_DOCK_MODULES + setOf("history") + workspace.canvases.map { it.id }
        val saved = workspace.layoutJson?.let { raw ->
            runCatching { dockJson.decodeFromString<DockNode>(raw) }.getOrNull()?.remove("export")
        }?.takeIf { node -> node.allModules().all { it in allowed } }
        DockSession(saved?.let(::repairLegacyCanvasDocking) ?: presetDockLayout(workspace))
    }
    LaunchedEffect(workspace.id, workspace.layoutJson) {
        val saved = workspace.layoutJson?.let { runCatching { dockJson.decodeFromString<DockNode>(it) }.getOrNull() }
            ?: return@LaunchedEffect
        val repaired = repairLegacyCanvasDocking(saved)
        if (repaired != saved) viewModel.setWorkspaceLayout(workspace.id, dockJson.encodeToString(repaired))
    }
    val canvasIds = workspace.canvases.map { it.id }
    val reconciledRoot = remember(session.root, canvasIds, workspace.placeModules) {
        reconcileDockModules(session.root, canvasIds, workspace.placeModules)
    }
    val hiddenModules = workspace.hiddenModules
    // Visibility is a projection of the saved layout: toggling a panel must not remove
    // its tab group, split ratio, or floating-window placement from the layout.
    val visibleRoot = hiddenModules.fold(reconciledRoot) { layout, module -> layout?.remove(module) }
    SideEffect {
        session.hiddenModules = hiddenModules
        if (session.root != reconciledRoot) session.root = reconciledRoot
    }
    LaunchedEffect(state.workspaces.map { it.id }) {
        sessions.keys.retainAll(state.workspaces.map { it.id }.toSet())
    }
    LaunchedEffect(workspace.layoutJson, workspace.placeModules) {
        val pending = workspace.placeModules.filter { module ->
            val json = workspace.layoutJson
            val placed = if (json == null) module in DEFAULT_DOCK_MODULES else "\"$module\"" in json
            !placed
        }
        if (pending != workspace.placeModules) viewModel.setPlaceModules(pending)
    }
    // Debounce splitter updates. Floating panels are written back into the tree so none are lost.
    // The first pass is the layout just loaded; writing it back would dirty an unchanged project.
    var layoutReady by remember(state.projectOpenGeneration, workspace.id) { mutableStateOf(false) }
    LaunchedEffect(workspace.id, session.root, session.floating.keys.toList()) {
        if (!layoutReady) {
            layoutReady = true
            return@LaunchedEffect
        }
        delay(400)
        var saved = session.root
        session.floating.keys.forEach { saved = dockModule(saved, it, null, DockSide.RIGHT) }
        saved?.let { viewModel.setWorkspaceLayout(workspace.id, dockJson.encodeToString(it)) }
    }
    // A module's composition belongs to its current dock leaf. Moving a canvas creates a
    // fresh viewport while its durable session and editor stay keyed by workspace/canvas id.
    // Reusing movableContent across a changing split tree retained stale pointer/focus nodes,
    // especially for the canvas that existed before a second canvas was added.
    fun content(id: String): @Composable () -> Unit = {
        key(workspace.id, id) {
            DockModuleContent(id, state, viewModel, onStartTutorial, onOpenProject, onOpenPsd)
        }
    }
    DisposableEffect(session) { onDispose { session.cancel() } }
	// Tutorial / programmatic focus: select a dock module tab and bring floating modules back.
	LaunchedEffect(state.requestedDockModule, workspace.id) {
		val module = state.requestedDockModule ?: return@LaunchedEffect
		if (module in workspace.hiddenModules) viewModel.setModuleVisible(module, true)
		if (module in session.floating) {
			session.returnToDock(module)
		}
		val node = session.root?.containing(module)
		if (node != null) {
			session.root = session.root?.update(node.id) { it.copy(selected = module) }
		}
		viewModel.clearDockModuleRequest()
	}
    LaunchedEffect(session, session.dragging) {
        while (session.dragging != null) {
            session.track()
            delay(16)
        }
    }
    val colors = LocalToolColors.current
    // Overlay the rebuild prompt on a Box so its fillMaxSize scrim cannot compete for
    // Column height with the dock (which would collapse the workspace to solid black).
    Box(modifier) {
        Column(Modifier.fillMaxSize().background(colors.windowBackground)) {
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                WorkspaceStrip(
                    state,
                    viewModel,
                    layoutModules = session.root?.allModules()?.toSet().orEmpty(),
                    modifier = Modifier.weight(1f),
                )
                TabStripButton(label = tr("dock.reset")) {
                    session.floating.clear()
                    session.root = presetDockLayout(workspace)
                    viewModel.resetWorkspaceArrangement()
                }
                Spacer(Modifier.width(4.dp))
            }
            key(workspace.id) {
                Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { coordinates ->
                    session.workspaceBounds = { screenBounds(coordinates, mainWindow) }
                }) {
                    visibleRoot?.let {
                        DockTree(it, session, Modifier.fillMaxSize(), mainWindow, state, viewModel, ::content)
                        DockJunctionOverlay(it, session, mainWindow)
                    }
                        ?: Box(Modifier.fillMaxSize().background(
                            if (session.target?.first == "empty") colors.accent.copy(alpha = .08f) else Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            Text(tr("dock.empty"), color = colors.textMuted, fontSize = 11.sp)
                        }
                }
            }
        }
        val pendingPaint = viewModel.canvasAwaitingMeshRebuild()
        if (pendingPaint != null) {
            io.github.psd2live.ui.components.RebuildMeshPromptDialog(
                layerName = pendingPaint.paintSession?.layerName.orEmpty(),
                onConfirmRebuild = { pendingPaint.commitPaintSession(rebuildMesh = true) },
                onKeepExisting = { pendingPaint.commitPaintSession(rebuildMesh = false) },
                onDismiss = { pendingPaint.showRebuildMeshDialog = false })
        }
        viewModel.pendingMeshSplit?.let { offer ->
            io.github.psd2live.ui.components.MeshSplitDialog(
                offer = offer,
                onSplit = viewModel::confirmMeshSplit,
                onDismiss = viewModel::dismissMeshSplit,
                onDismissAll = viewModel::dismissAllMeshSplits,
            )
        }
        viewModel.pendingBatchMeshSplit?.let { batchOffer ->
            io.github.psd2live.ui.components.BatchMeshSplitDialog(
                batchOffer = batchOffer,
                onSplit = viewModel::confirmBatchMeshSplit,
                onDismiss = viewModel::dismissBatchMeshSplit,
            )
        }
    }
    session.floating.toMap().forEach { (id, windowState) ->
        key(workspace.id, id) {
            Window(onCloseRequest = { session.returnToDock(id) }, state = windowState,
                title = floatingTitle(id, state, viewModel), undecorated = true,
                visible = id !in hiddenModules) {
                DisposableEffect(window) {
                    session.floatingWindows[id] = window
                    onDispose { session.floatingWindows.remove(id) }
                }
                // Match the main window's custom density and theme.
                CompactToolTheme(
                    darkTheme = state.darkTheme,
                    uiScale = AppSettings.uiScale,
                    fontScale = AppSettings.fontScale,
                ) {
                    Column(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)) {
                        DockHeader(id, session, Modifier.fillMaxWidth(), state, viewModel, floating = true, floatingWindow = window)
                        Box(Modifier.weight(1f).fillMaxWidth()) { content(id)() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTree(node: DockNode, session: DockSession, modifier: Modifier, window: java.awt.Window?,
                     state: PSD2LiveState, viewModel: PSD2LiveViewModel,
                     content: (String) -> @Composable () -> Unit) {
    val colors = LocalToolColors.current
    val density = LocalDensity.current
    if (node.first != null && node.second != null) {
        BoxWithConstraints(modifier) {
            val extent = with(density) { (if (node.horizontal) maxWidth else maxHeight).toPx() }
            val interaction = remember(node.id) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            var resizing by remember(node.id) { mutableStateOf(false) }
            val splitter = Modifier.background(colors.windowBackground).hoverable(interaction)
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(
                    if (node.horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR)))
                .drawBehind {
                    val color = if (hovered || resizing || node.id in session.junctionHighlights) colors.accent else colors.windowBackground
                    if (node.horizontal) drawLine(color, Offset(size.width / 2, 0f),
                        Offset(size.width / 2, size.height), 1.dp.toPx())
                    else drawLine(color, Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2), 1.dp.toPx())
                }.pointerInput(node.id, extent) {
                detectDragGestures(
                    onDragStart = { resizing = true },
                    onDragEnd = { resizing = false },
                    onDragCancel = { resizing = false },
                ) { change, delta ->
                    change.consume()
                    val movement = if (node.horizontal) delta.x else delta.y
                    session.resizeDividers(mapOf(node.id to movement), window, with(density) { 4.dp.toPx() })
                }
            }
            if (node.horizontal) Row(Modifier.fillMaxSize()) {
                DockTree(node.first, session, Modifier.weight(node.ratio).fillMaxHeight(), window, state, viewModel, content)
                Box(splitter.width(4.dp).fillMaxHeight())
                DockTree(node.second, session, Modifier.weight(1f - node.ratio).fillMaxHeight(), window, state, viewModel, content)
            } else Column(Modifier.fillMaxSize()) {
                DockTree(node.first, session, Modifier.weight(node.ratio).fillMaxWidth(), window, state, viewModel, content)
                Box(splitter.height(4.dp).fillMaxWidth())
                DockTree(node.second, session, Modifier.weight(1f - node.ratio).fillMaxWidth(), window, state, viewModel, content)
            }
        }
        return
    }
    DisposableEffect(node.id) { onDispose { session.bounds.remove(node.id) } }
    Box(modifier
		.then(
			when (node.selected) {
				"layers" -> Modifier.tutorialTarget(TutorialTargetId.LAYERS_DOCK)
				"settings" -> Modifier.tutorialTarget(TutorialTargetId.MODEL_SETTINGS)
				"parameters" -> Modifier.tutorialTarget(TutorialTargetId.PARAMETERS_DOCK)
				"inspector" -> Modifier.tutorialTarget(TutorialTargetId.INSPECTOR_DOCK)
				"tools" -> Modifier.tutorialTarget(TutorialTargetId.TOOLS_DOCK)
				"hierarchy" -> Modifier.tutorialTarget(TutorialTargetId.HIERARCHY_DOCK)
				else -> Modifier
			},
		)
		.onGloballyPositioned { coordinates ->
        session.bounds[node.id] = {
            screenBounds(coordinates, window)?.let { r ->
                val scale = window?.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
                val headerHeight = with(density) { 22.dp.toPx() } / scale
                DockHitArea(r, Rect(r.left, r.top, r.right, r.top + headerHeight),
                    with(density) { 8.dp.toPx() } / scale)
            }
        }
    }) {
        Column(Modifier.fillMaxSize().clipWithoutLayer(RoundedCornerShape(3.dp))
            .background(colors.panelBackground)
            .border(.5.dp, colors.divider, RoundedCornerShape(3.dp))) {
            val single = node.modules.size == 1
            if (single) {
                DockHeader(node.selected, session, Modifier.fillMaxWidth(), state, viewModel, standalone = true)
            } else {
                // Same surface as a single module's header; the selected tab takes the content color
                // and covers the strip's baseline so it reads as part of its panel.
                Row(Modifier.fillMaxWidth().height(22.dp)
                    .background(colors.panelElevated)
                    .drawBehind {
                        drawLine(colors.divider, Offset(0f, size.height - .5.dp.toPx()),
                            Offset(size.width, size.height - .5.dp.toPx()), 1.dp.toPx())
                    }
                    .horizontalScroll(rememberScrollState())) {
                    node.modules.forEach { id ->
                        DockHeader(id, session, Modifier, state, viewModel, selected = id == node.selected, standalone = false,
                            onSelect = { session.root = session.root?.update(node.id) { it.copy(selected = id) } })
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content(node.selected)() }
        }
        val target = session.target
        if (target?.first == node.id && session.dragging != null) {
            val highlight = when (target.second) {
                DockSide.LEFT -> Modifier.fillMaxHeight().fillMaxWidth(.5f).align(Alignment.CenterStart)
                DockSide.RIGHT -> Modifier.fillMaxHeight().fillMaxWidth(.5f).align(Alignment.CenterEnd)
                DockSide.TOP -> Modifier.fillMaxWidth().fillMaxHeight(.5f).align(Alignment.TopCenter)
                DockSide.BOTTOM -> Modifier.fillMaxWidth().fillMaxHeight(.5f).align(Alignment.BottomCenter)
                DockSide.CENTER -> Modifier.fillMaxWidth().height(22.dp).align(Alignment.TopCenter)
            }
            Box(highlight.background(colors.accent.copy(alpha = .08f)).border(1.dp, colors.accent))
        }
    }
}

/** Keep untouched dividers at their absolute coordinates instead of scaling descendants. */
private fun DockSession.resizeDividers(movements: Map<String, Float>, window: java.awt.Window?, gap: Float) {
    val visible = visibleRoot ?: return
    val bounds = workspaceBounds?.invoke() ?: return
    val scale = window?.graphicsConfiguration?.defaultTransform
    val width = bounds.width * (scale?.scaleX?.toFloat() ?: 1f)
    val height = bounds.height * (scale?.scaleY?.toFloat() ?: 1f)
    val positions = mutableMapOf<String, Float>()
    fun capture(node: DockNode, rect: Rect) {
        val a = node.first ?: return
        val b = node.second ?: return
        val start = if (node.horizontal) rect.left else rect.top
        val extent = ((if (node.horizontal) rect.width else rect.height) - gap).coerceAtLeast(1f)
        val position = start + extent * node.ratio
        positions[node.id] = position
        if (node.horizontal) {
            capture(a, Rect(rect.left, rect.top, position, rect.bottom))
            capture(b, Rect(position + gap, rect.top, rect.right, rect.bottom))
        } else {
            capture(a, Rect(rect.left, rect.top, rect.right, position))
            capture(b, Rect(rect.left, position + gap, rect.right, rect.bottom))
        }
    }
    val workspace = Rect(0f, 0f, width, height)
    capture(visible, workspace)
    var updated = root
    fun apply(node: DockNode, rect: Rect) {
        val a = node.first ?: return
        val b = node.second ?: return
        val start = if (node.horizontal) rect.left else rect.top
        val extent = ((if (node.horizontal) rect.width else rect.height) - gap).coerceAtLeast(1f)
        fun parallelPositions(child: DockNode): List<Float> {
            if (child.first == null || child.second == null) return emptyList()
            val own = if (child.horizontal == node.horizontal)
                listOf(positions.getValue(child.id) + (movements[child.id] ?: 0f)) else emptyList()
            return own + parallelPositions(child.first) + parallelPositions(child.second)
        }
        val minimum = gap * 8f
        val lower = (parallelPositions(a).maxOrNull()?.plus(gap) ?: start) + minimum
        val upper = (parallelPositions(b).minOrNull() ?: (start + extent + gap)) - gap - minimum
        val desired = positions.getValue(node.id) + (movements[node.id] ?: 0f)
        val requested = if (node.id in movements) {
            if (lower <= upper) desired.coerceIn(lower, upper) else positions.getValue(node.id)
        } else desired
        val ratio = ((requested - start) / extent).coerceIn(.001f, .999f)
        val position = start + ratio * extent
        updated = updated?.update(node.id) { it.copy(ratio = ratio) }
        if (node.horizontal) {
            apply(a, Rect(rect.left, rect.top, position, rect.bottom))
            apply(b, Rect(position + gap, rect.top, rect.right, rect.bottom))
        } else {
            apply(a, Rect(rect.left, rect.top, rect.right, position))
            apply(b, Rect(rect.left, position + gap, rect.right, rect.bottom))
        }
    }
    apply(visible, workspace)
    root = updated
}

/** Geometry uses the same gap and weight allocation as DockTree, in workspace pixels. */
private data class DockDivider(val id: String, val vertical: Boolean, val rect: Rect, val extent: Float)
private data class DockJunction(val center: Offset, val dividers: List<DockDivider>)

private fun dockJunctions(root: DockNode, width: Float, height: Float, gap: Float): List<DockJunction> {
    val dividers = mutableListOf<DockDivider>()
    fun collect(node: DockNode, rect: Rect) {
        val first = node.first ?: return
        val second = node.second ?: return
        if (node.horizontal) {
            val x = rect.left + ((rect.width - gap).coerceAtLeast(0f) * node.ratio).roundToInt()
            dividers += DockDivider(node.id, true, Rect(x, rect.top, x + gap, rect.bottom), (rect.width - gap).coerceAtLeast(1f))
            collect(first, Rect(rect.left, rect.top, x, rect.bottom))
            collect(second, Rect(x + gap, rect.top, rect.right, rect.bottom))
        } else {
            val y = rect.top + ((rect.height - gap).coerceAtLeast(0f) * node.ratio).roundToInt()
            dividers += DockDivider(node.id, false, Rect(rect.left, y, rect.right, y + gap), (rect.height - gap).coerceAtLeast(1f))
            collect(first, Rect(rect.left, rect.top, rect.right, y))
            collect(second, Rect(rect.left, y + gap, rect.right, rect.bottom))
        }
    }
    collect(root, Rect(0f, 0f, width, height))
    val result = mutableListOf<DockJunction>()
    for (v in dividers.filter { it.vertical }) for (h in dividers.filter { !it.vertical }) {
        val x = v.rect.center.x
        val y = h.rect.center.y
        val tolerance = gap / 2 + 1f
        if (x < h.rect.left - tolerance || x > h.rect.right + tolerance ||
            y < v.rect.top - tolerance || y > v.rect.bottom + tolerance) continue
        val index = result.indexOfFirst { abs(it.center.x - x) <= 1f && abs(it.center.y - y) <= 1f }
        if (index < 0) result += DockJunction(Offset(x, y), listOf(v, h))
        else result[index] = result[index].copy(dividers = (result[index].dividers + v + h).distinctBy { it.id })
    }
    return result
}

@Composable
private fun DockJunctionOverlay(root: DockNode, session: DockSession, window: java.awt.Window?) {
    val density = LocalDensity.current
    val colors = LocalToolColors.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = with(density) { 4.dp.roundToPx().toFloat() }
        val handle = 12.dp
        val halfHandle = with(density) { handle.toPx() / 2 }
        val junctions = dockJunctions(root, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), gap)
        junctions.forEach { junction ->
            val ids = junction.dividers.map { it.id }.toSet()
            key(ids.sorted().joinToString()) {
                val currentJunction by rememberUpdatedState(junction)
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                var dragging by remember { mutableStateOf(false) }
                LaunchedEffect(hovered, dragging) {
                    if (hovered || dragging) session.junctionHighlights = ids
                    else if (session.junctionHighlights == ids) session.junctionHighlights = emptySet()
                }
                DisposableEffect(Unit) {
                    onDispose { if (session.junctionHighlights == ids) session.junctionHighlights = emptySet() }
                }
                Box(Modifier.offset {
                    IntOffset((junction.center.x - halfHandle).roundToInt(), (junction.center.y - halfHandle).roundToInt())
                }.size(handle)
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)))
                    .drawBehind {
                        if (hovered || dragging) {
                            drawCircle(colors.accent.copy(alpha = .16f))
                            val c = center
                            drawLine(colors.accent, Offset(0f, c.y), Offset(size.width, c.y), 1.dp.toPx())
                            drawLine(colors.accent, Offset(c.x, 0f), Offset(c.x, size.height), 1.dp.toPx())
                        }
                    }
                    // Keep gesture identity stable as both splitters move underneath the pointer.
                    .pointerInput(ids) {
                        var start: java.awt.Point? = null
                        var axes = emptyList<DockDivider>()
                        var scaleX = 1f
                        var scaleY = 1f
                        detectDragGestures(
                            onDragStart = {
                                start = MouseInfo.getPointerInfo()?.location
                                axes = currentJunction.dividers
                                scaleX = window?.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
                                scaleY = window?.graphicsConfiguration?.defaultTransform?.scaleY?.toFloat() ?: 1f
                                dragging = true
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            change.consume()
                            val origin = start
                            val pointer = MouseInfo.getPointerInfo()?.location
                            if (origin != null && pointer != null) {
                                session.resizeDividers(axes.associate { divider ->
                                    divider.id to if (divider.vertical) (pointer.x - origin.x) * scaleX else (pointer.y - origin.y) * scaleY
                                }, window, gap)
                                start = pointer
                            }
                        }
                    })
            }
        }
    }
}

private fun screenBounds(coordinates: androidx.compose.ui.layout.LayoutCoordinates, window: java.awt.Window?): Rect? {
    if (window == null || !window.isShowing || !coordinates.isAttached) return null
    val r = coordinates.boundsInWindow()
    val transform = window.graphicsConfiguration.defaultTransform
    val origin = window.locationOnScreen
    return Rect(origin.x + r.left / transform.scaleX.toFloat(), origin.y + r.top / transform.scaleY.toFloat(),
        origin.x + r.right / transform.scaleX.toFloat(), origin.y + r.bottom / transform.scaleY.toFloat())
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DockHeader(id: String, session: DockSession, modifier: Modifier,
                       state: PSD2LiveState, viewModel: PSD2LiveViewModel,
                       selected: Boolean = true, floating: Boolean = false, standalone: Boolean = true,
                       floatingWindow: java.awt.Window? = null, onSelect: () -> Unit = {}) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    val canvas = state.activeWorkspace.canvases.firstOrNull { it.id == id }
    val title = canvas?.let { canvasHeaderTitle(it, state.activeWorkspace) } ?: moduleTitle(id)
    val showCanvasTools = canvas != null && (standalone || floating || selected)
    Row(modifier.height(22.dp)
        .then(if (id == "history") Modifier.tutorialTarget(TutorialTargetId.HISTORY_TAB) else Modifier)
        .clip(if (standalone) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
        .background(when {
            standalone -> if (hovered) colors.controlHover else colors.panelElevated
            selected -> colors.panelBackground
            hovered -> colors.controlHover.copy(alpha = .6f)
            else -> Color.Transparent
        })
        .drawBehind {
            if (!standalone && selected) {
                drawLine(colors.accent, Offset(0f, 1.dp.toPx()),
                    Offset(size.width, 1.dp.toPx()), 2.dp.toPx())
            } else if (standalone) {
                drawLine(colors.divider, Offset(0f, size.height - .5.dp.toPx()),
                    Offset(size.width, size.height - .5.dp.toPx()), .5.dp.toPx())
            }
        }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (selected) colors.textPrimary else colors.textMuted,
            style = typography.body.copy(fontSize = if (standalone || floating) 11.sp else 10.5.sp,
                fontWeight = if (standalone || selected) FontWeight.Medium else FontWeight.Normal),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = (if (standalone || floating) Modifier.weight(1f) else Modifier.widthIn(max = 120.dp))
                // The move cursor appears only once a drag passes the slop; until then the
                // cursor reflects what a click does (switch tab vs. nothing).
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(when {
                    session.dragging == id -> Cursor.MOVE_CURSOR
                    !standalone && !floating && !selected -> Cursor.HAND_CURSOR
                    else -> Cursor.DEFAULT_CURSOR
                })))
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Secondary) { menu = true; event.changes.forEach { it.consume() } }
                }
                .pointerInput(id, session, floatingWindow, title) {
                    detectDragGestures(
                        onDragStart = { session.begin(id, title, floatingWindow, colors) },
                        onDrag = { change, _ -> change.consume(); session.track() },
                        onDragEnd = { session.track(); session.finish() },
                        onDragCancel = { session.cancel() },
                    )
                }.clickable(interactionSource = interaction, indication = null) {
                    onSelect()
                    if (canvas != null) viewModel.focusCanvas(canvas.id)
                }
                .padding(horizontal = if (standalone || floating) 7.dp else 10.dp, vertical = 2.dp))
        if (showCanvasTools) {
            CanvasModeChip(
                label = tr("tab.edit"),
                active = canvas.mode == CanvasMode.EDIT,
                modifier = Modifier.tutorialTarget(TutorialTargetId.EDIT_TAB),
                onClick = { viewModel.setCanvasMode(canvas.id, CanvasMode.EDIT) },
            )
            CanvasModeChip(
                label = tr("tab.preview"),
                active = canvas.mode == CanvasMode.PREVIEW,
                modifier = Modifier.tutorialTarget(TutorialTargetId.PREVIEW_TAB),
                onClick = { viewModel.setCanvasMode(canvas.id, CanvasMode.PREVIEW) },
            )
            Box(Modifier.tutorialTarget(TutorialTargetId.VIEW_OPTIONS_MENU)) {
                CanvasModeChip(
                    label = "${tr("tab.options.short")} \u25BE",
                    active = canvas.view != canvas.mode.defaultViewOptions(),
                    modifier = Modifier,
                    onClick = {
                        viewModel.focusCanvas(canvas.id)
                        viewMenu = true
                    },
                )
                TabStripDropdown(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    ViewOptionsMenuItems(
                        options = canvas.view,
                        onOptionsChange = { viewModel.setCanvasViewOptions(canvas.id, it, canvas.mode) },
                        onDismiss = { viewMenu = false },
                        showHeaders = true,
                        showPathGuides = canvas.mode == CanvasMode.EDIT,
                        onReset = {
                            viewMenu = false
                            viewModel.resetCanvasViewOptions(canvas.id, canvas.mode)
                        },
                    )
                }
            }
        }
        if (floating) {
            Text("↙", color = colors.textMuted,
                modifier = Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))).clickable { session.returnToDock(id) }.padding(horizontal = 7.dp))
        }
        TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
            CompactMenuItem(text = tr(if (floating) "dock.return" else "dock.detach"), onClick = {
                menu = false
                if (floating) session.returnToDock(id) else session.detach(id)
            })
            if (canvas != null) {
                CompactMenuItem(
                    text = tr("window.closeCanvas"),
                    enabled = state.activeWorkspace.canvases.size > 1,
                    onClick = {
                        menu = false
                        viewModel.closeCanvas(canvas.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun CanvasModeChip(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    Text(
        text = label,
        color = if (active) colors.textPrimary else colors.textMuted,
        style = typography.body.copy(fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
        maxLines = 1,
        modifier = modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
    )
}

private fun canvasHeaderTitle(canvas: CanvasWindowState, workspace: EditorWorkspace): String {
    val index = workspace.canvases.indexOfFirst { it.id == canvas.id }
    val base = tr("dock.canvas")
    return if (workspace.canvases.size > 1 && index >= 0) "$base ${index + 1}" else base
}

private fun floatingTitle(id: String, state: PSD2LiveState, viewModel: PSD2LiveViewModel): String {
    val canvas = state.activeWorkspace.canvases.firstOrNull { it.id == id }
    val name = canvas?.let { viewModel.canvasTitle(it) } ?: moduleTitle(id)
    return "${state.activeWorkspace.displayName()} · $name"
}

@Composable
private fun DockModuleContent(
	id: String,
	state: PSD2LiveState,
	vm: PSD2LiveViewModel,
	onStartTutorial: (() -> Unit)? = null,
	onOpenProject: (() -> Unit)? = null,
	onOpenPsd: (() -> Unit)? = null,
) {
	if (isCanvasModule(id)) {
		val canvas = state.activeWorkspace.canvases.firstOrNull { it.id == id } ?: return
		CanvasViewportComposable(
			mode = canvas.mode,
			state = state,
			viewModel = vm,
			canvasId = canvas.id,
			viewOptions = canvas.view,
			cameraZoom = canvas.camera.zoom,
			cameraPanX = canvas.camera.panX,
			cameraPanY = canvas.camera.panY,
			modifier = Modifier.fillMaxSize(),
			onStartTutorial = onStartTutorial,
			onOpenProject = onOpenProject,
			onOpenPsd = onOpenPsd,
		)
		return
	}
	when (id) {
		"hierarchy" -> DockHierarchyView(
			state,
			vm,
			state.activeCanvas.mode,
			onRequestOpenDeformPaths = { vm.selectLayer(it); vm.requestCanvasPathTool() },
			onRequestCreate = { kind, relation, isDeformer, target ->
				vm.editorForFocusedCanvas().beginTreeCreate(kind, relation, isDeformer, target)
			},
		)
		"skeleton" -> SkeletonTreeView(state, vm)
		"history" -> HistoryTreeView(state, vm, Modifier.fillMaxSize())
		"log" -> BottomLogDock(state, vm, Modifier.fillMaxSize(), fillDock = true)
		"animationEditor" -> AnimationEditorView(state, vm)
		"settings" -> {
			Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
				ModelSettingsSection(
					state,
					vm,
					state.modelSettingsExpanded,
					{ vm.setModelSettingsExpanded(!state.modelSettingsExpanded) },
				)
			}
		}
		"layers" -> LayersTableView(state, vm)
		"parameters" -> ParametersListView(state, vm)
		"tools" -> ToolDetailsView(vm.canvasEditorFor(state.activeCanvas.id), vm, state)
		"mesh" -> MeshPanelView(state, vm)
		"inspector" -> InspectorPanelView(vm.canvasEditorFor(state.activeCanvas.id), vm, state)
		"animation" -> AnimationPanelView(vm, state)
		"physics" -> PhysicsPanelView(vm, state)
	}
}

/**
 * Rounded clip without a graphics layer. [Modifier.clip] promotes a RenderNode whose window
 * position is not updated when a sibling dock is collapsed, which leaves the canvas picture
 * — mesh points in particular — at the previous layout position.
 */
private fun Modifier.clipWithoutLayer(shape: Shape): Modifier = drawWithContent {
	val outline = shape.createOutline(size, layoutDirection, this)
	clipPath(Path().apply { addOutline(outline) }) {
		this@drawWithContent.drawContent()
	}
}

