package io.github.psd2live.ui.views

import io.github.psd2live.ui.views.physics.PhysicsPanelView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
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
import io.github.psd2live.ui.components.CanvasBackgroundMenuItems
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.TreeContextMenu
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.psd2live.ui.tutorial.TutorialId
import io.github.psd2live.ui.tutorial.tutorialTarget
import kotlinx.coroutines.delay
import java.awt.MouseInfo
import java.awt.Cursor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/** How far a pressed tab or header must travel before it starts dragging. */
private val DockDragSlop = 16.dp

private data class DockHitArea(val body: Rect, val header: Rect, val edge: Float)

/** Where a dragged module would land: a split on [side] of leaf [node], or a tab before [before] (last when null). */
private data class DockDrop(val node: String, val side: DockSide, val before: String? = null)

private class DockSession(initial: DockNode) {
    var root by mutableStateOf<DockNode?>(initial)
    var hiddenModules: Set<String> = emptySet()
    val visibleRoot: DockNode?
        get() = hiddenModules.fold(root) { layout, module -> layout?.remove(module) }
    val floating = mutableStateMapOf<String, WindowState>()
    val bounds = mutableMapOf<String, () -> DockHitArea?>()
    /** Screen bounds of each tab, by leaf and module, for placing a drop between tabs. */
    val tabs = mutableMapOf<Pair<String, String>, () -> Rect?>()
    var workspaceBounds: (() -> Rect?)? = null
    private val returnTargets = mutableMapOf<String, String>()
    val floatingWindows = mutableMapOf<String, java.awt.Window>()
    var junctionHighlights by mutableStateOf<Set<String>>(emptySet())
    var dragging by mutableStateOf<String?>(null)
    var target by mutableStateOf<DockDrop?>(null)
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
        val module = dragging ?: return
        dragPreview?.setLocation(point.x + 16, point.y + 16)
        dragWindow?.setLocation(point.x - grabOffset.x, point.y - grabOffset.y)
        target = dropAt(module, p)
        // Over a tab strip the insertion mark shows where the tab goes; the ghost would only cover it.
        val ghost = target?.side != DockSide.CENTER || target?.node == "empty"
        dragPreview?.let { if (it.isVisible != ghost) it.isVisible = ghost }
    }

    private fun dropAt(module: String, p: Offset): DockDrop? {
        // A floating window covering a dock is not a dock target.
        if (floatingWindows.any { (id, window) -> id != module && window.isShowing &&
                window.bounds.contains(p.x.toInt(), p.y.toInt()) }) return null
        if (visibleRoot == null && workspaceBounds?.invoke()?.contains(p) == true) return DockDrop("empty", DockSide.CENTER)
        for ((id, read) in bounds) {
            val area = read() ?: continue
            val node = visibleRoot?.find(id) ?: continue
            if (!area.body.contains(p)) continue
            if (node.modules == listOf(module)) return null
            // The tab strip takes the module as a tab, between the tabs either side of the pointer.
            if (area.header.contains(p)) {
                val others = node.modules - module
                val before = others.firstOrNull { tabs[id to it]?.invoke()?.let { r -> p.x < r.center.x } == true }
                val unchanged = module in node.modules &&
                    node.modules.getOrNull(node.modules.indexOf(module) + 1) == before
                return if (unchanged) null else DockDrop(id, DockSide.CENTER, before)
            }
            // A band along each edge splits the panel; the band grows with the panel so it is easy to
            // hit, and the middle of the content never takes a drop.
            val r = area.body
            val bandX = (r.width * .22f).coerceIn(area.edge, area.edge * 8)
            val bandY = (r.height * .22f).coerceIn(area.edge, area.edge * 8)
            val reach = buildMap {
                if (r.width >= 320f) {
                    put(DockSide.LEFT, (p.x - r.left) / bandX)
                    put(DockSide.RIGHT, (r.right - p.x) / bandX)
                }
                if (r.height >= 200f) {
                    put(DockSide.TOP, (p.y - area.header.bottom) / bandY)
                    put(DockSide.BOTTOM, (r.bottom - p.y) / bandY)
                }
            }
            val side = reach.filterValues { it < 1f }.minByOrNull { it.value }?.key ?: return null
            return DockDrop(id, side)
        }
        return null
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
            root = dockModule(root, module, destination.node, destination.side, destination.before)
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

@Composable
internal fun DockWorkspaceView(
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    modifier: Modifier = Modifier,
    mainWindow: java.awt.Window? = null,
	onOpenTutorialCatalog: (() -> Unit)? = null,
	onStartTutorial: ((TutorialId) -> Unit)? = null,
	onOpenProject: (() -> Unit)? = null,
	onOpenPsd: (() -> Unit)? = null,
) {
    val sessions = remember(state.projectOpenGeneration) { mutableMapOf<String, DockSession>() }
    val workspace = state.activeWorkspace
    val session = sessions.getOrPut(workspace.id) { DockSession(workspaceDockRoot(workspace)) }
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
            DockModuleContent(id, state, viewModel, onOpenTutorialCatalog, onStartTutorial, onOpenProject, onOpenPsd)
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
            Row(
				Modifier.fillMaxWidth().height(28.dp).tutorialTarget(TutorialTargetId.WORKSPACE_STRIP),
				verticalAlignment = Alignment.CenterVertically,
			) {
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
                Box(Modifier.weight(1f).fillMaxWidth().tutorialTarget(TutorialTargetId.DOCK_AREA).onGloballyPositioned { coordinates ->
                    session.workspaceBounds = { screenBounds(coordinates, mainWindow) }
                }) {
                    visibleRoot?.let {
                        DockTree(it, session, Modifier.fillMaxSize(), mainWindow, state, viewModel, ::content)
                        DockJunctionOverlay(it, session, mainWindow)
                    }
                        ?: Box(Modifier.fillMaxSize().background(
                            if (session.target?.node == "empty") colors.accent.copy(alpha = .08f) else Color.Transparent),
                            contentAlignment = Alignment.Center) {
                            if (session.floating.keys.any { it !in hiddenModules }) {
                                Text(tr("dock.empty"), color = colors.textMuted, fontSize = 11.sp)
                            } else {
                                EmptyDockPrompt(viewModel)
                            }
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
    // Transparency is fixed at window creation, so a floating canvas is rebuilt when it toggles.
    val transparent = state.canvasBackground.windowTransparent
    session.floating.toMap().forEach { (id, windowState) ->
        key(workspace.id, id, transparent) {
            Window(onCloseRequest = { session.returnToDock(id) }, state = windowState,
                title = floatingTitle(id, state, viewModel), undecorated = true, transparent = transparent,
                visible = id !in hiddenModules) {
                DisposableEffect(window) {
                    session.floatingWindows[id] = window
                    onDispose { session.floatingWindows.remove(id) }
                }
                // Match the main window's custom density and theme.
                CompactToolTheme(
                    colors = state.toolColors,
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
				"skeleton" -> Modifier.tutorialTarget(TutorialTargetId.SKELETON_DOCK)
				"animation" -> Modifier.tutorialTarget(TutorialTargetId.ANIMATION_DOCK)
				"animationEditor" -> Modifier.tutorialTarget(TutorialTargetId.ANIMATION_EDITOR_DOCK)
				"physics" -> Modifier.tutorialTarget(TutorialTargetId.PHYSICS_DOCK)
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
                DockTabStrip(node, session, window, state, viewModel)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content(node.selected)() }
        }
        val target = session.target
        if (target?.node == node.id && session.dragging != null) {
            // The half the new panel would take; a drop onto a tab strip is marked between its tabs instead.
            val highlight = when (target.side) {
                DockSide.LEFT -> Modifier.fillMaxHeight().fillMaxWidth(.5f).align(Alignment.CenterStart)
                DockSide.RIGHT -> Modifier.fillMaxHeight().fillMaxWidth(.5f).align(Alignment.CenterEnd)
                DockSide.TOP -> Modifier.fillMaxWidth().fillMaxHeight(.5f).align(Alignment.TopCenter)
                DockSide.BOTTOM -> Modifier.fillMaxWidth().fillMaxHeight(.5f).align(Alignment.BottomCenter)
                DockSide.CENTER -> if (node.modules.size == 1) Modifier.fillMaxWidth().height(22.dp).align(Alignment.TopCenter) else null
            }
            if (highlight != null) {
                Box(highlight.padding(3.dp).clip(RoundedCornerShape(3.dp))
                    .background(colors.accent.copy(alpha = .14f))
                    .border(1.dp, colors.accent.copy(alpha = .85f), RoundedCornerShape(3.dp)))
            }
        }
    }
}

/**
 * The tabs of a leaf holding several modules. Tabs drag within the strip to reorder and out of it to
 * dock elsewhere; a mark between tabs shows where a dragged tab would land. When the tabs overflow,
 * a list button at the end reaches every tab.
 */
@Composable
private fun DockTabStrip(node: DockNode, session: DockSession, window: java.awt.Window?,
                         state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
    val colors = LocalToolColors.current
    val scroll = rememberScrollState()
    // Each tab's left edge and width in the scrolled content, to keep the selected tab in view.
    val offsets = remember(node.id) { mutableStateMapOf<String, Pair<Int, Int>>() }
    var listMenu by remember(node.id) { mutableStateOf(false) }
    fun select(id: String) {
        session.root = session.root?.update(node.id) { it.copy(selected = id) }
    }
    LaunchedEffect(node.selected, offsets[node.selected], scroll.viewportSize) {
        val (left, width) = offsets[node.selected] ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        when {
            left < scroll.value -> scroll.animateScrollTo(left)
            left + width > scroll.value + viewport -> scroll.animateScrollTo(left + width - viewport)
        }
    }
    val drop = session.target?.takeIf { it.node == node.id && it.side == DockSide.CENTER && session.dragging != null }
    val shown = node.modules.filter { it != session.dragging || drop == null }
    // Same surface as a single module's header; the selected tab takes the content color
    // and covers the strip's baseline so it reads as part of its panel.
    Row(Modifier.fillMaxWidth().height(22.dp)
        .background(colors.panelElevated)
        .drawBehind {
            drawLine(colors.divider, Offset(0f, size.height - .5.dp.toPx()),
                Offset(size.width, size.height - .5.dp.toPx()), 1.dp.toPx())
        }, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            node.modules.forEachIndexed { index, id -> key(id) {
                DisposableEffect(node.id, id) { onDispose { session.tabs.remove(node.id to id) } }
                val selected = id == node.selected
                val next = node.modules.getOrNull(index + 1)
                val markBefore = drop != null && drop.before == id
                val markAfter = drop != null && drop.before == null && id == shown.lastOrNull()
                DockHeader(id, session,
                    Modifier
                        .onGloballyPositioned { coordinates ->
                            offsets[id] = coordinates.positionInParent().x.roundToInt() to coordinates.size.width
                            session.tabs[node.id to id] = { screenBounds(coordinates, window) }
                        }
                        .graphicsLayer { alpha = if (session.dragging == id) .4f else 1f }
                        .drawWithContent {
                            drawContent()
                            // A short divider parts two unselected tabs; the selected tab needs none.
                            if (!selected && next != null && next != node.selected) {
                                val inset = size.height * .28f
                                drawLine(colors.textMuted.copy(alpha = .28f), Offset(size.width - .5f, inset),
                                    Offset(size.width - .5f, size.height - inset), 1.dp.toPx())
                            }
                            val stroke = 2.dp.toPx()
                            if (markBefore) drawRect(colors.accent, Offset(0f, 0f),
                                androidx.compose.ui.geometry.Size(stroke, size.height))
                            if (markAfter) drawRect(colors.accent, Offset(size.width - stroke, 0f),
                                androidx.compose.ui.geometry.Size(stroke, size.height))
                        },
                    state, viewModel, selected = selected, standalone = false, siblings = node.modules,
                    onSelect = { select(id) })
            } }
        }
        if (scroll.maxValue > 0) {
            Box {
                Box(Modifier.size(22.dp)
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                    .clickable { listMenu = true }, contentAlignment = Alignment.Center) {
                    io.github.psd2live.ui.components.IconChevron(expanded = true, tint = colors.textMuted,
                        modifier = Modifier.size(10.dp))
                }
                TabStripDropdown(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                    node.modules.forEach { id ->
                        io.github.psd2live.ui.components.AppMenuItem(
                            text = dockTabTitle(id, state),
                            isChecked = id == node.selected,
                            onClick = {
                                listMenu = false
                                select(id)
                                state.activeWorkspace.canvases.firstOrNull { it.id == id }?.let { viewModel.focusCanvas(it.id) }
                            },
                        )
                    }
                }
            }
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
                       floatingWindow: java.awt.Window? = null, siblings: List<String> = listOf(id),
                       onSelect: () -> Unit = {}) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var backgroundMenu by remember { mutableStateOf(false) }
    val workspace = state.activeWorkspace
    val canvas = workspace.canvases.firstOrNull { it.id == id }
    val title = canvas?.let { canvasHeaderTitle(it, workspace) } ?: moduleTitle(id)
    val showCanvasTools = canvas != null && (standalone || floating || selected)
    // Closing (menu or middle-click) hides a panel, which the window menu brings back; it removes a
    // canvas, never the last.
    val canClose = canvas == null || workspace.canvases.size > 1
    val close = {
        if (canvas != null) viewModel.closeCanvas(canvas.id) else viewModel.setModuleVisible(id, false)
    }
    val tab = !standalone && !floating
    Row(modifier.height(22.dp)
        .then(if (id == "history") Modifier.tutorialTarget(TutorialTargetId.HISTORY_TAB) else Modifier)
        .clip(if (standalone) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
        .hoverable(interaction)
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
        Text(title, color = if (selected || hovered) colors.textPrimary else colors.textMuted,
            style = typography.body.copy(fontSize = if (standalone || floating) 11.sp else 10.5.sp,
                fontWeight = if (standalone || selected) FontWeight.Medium else FontWeight.Normal),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = (if (tab) Modifier.widthIn(max = 120.dp) else Modifier.weight(1f))
                // The move cursor appears only once a drag passes the slop; until then the
                // cursor reflects what a click does (switch tab vs. nothing).
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(when {
                    session.dragging == id -> Cursor.MOVE_CURSOR
                    tab && !selected -> Cursor.HAND_CURSOR
                    else -> Cursor.DEFAULT_CURSOR
                })))
                .onPointerEvent(PointerEventType.Press) { event ->
                    when (event.button) {
                        PointerButton.Secondary -> { menu = true; event.changes.forEach { it.consume() } }
                        PointerButton.Tertiary -> { if (canClose) close(); event.changes.forEach { it.consume() } }
                        else -> {}
                    }
                }
                .pointerInput(id, session, floatingWindow, title) {
                    val slop = DockDragSlop.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentEvent.button != null && currentEvent.button != PointerButton.Primary) return@awaitEachGesture
                        // A press only becomes a drag once it travels well past the usual slop, so a
                        // slightly shaky click still switches tabs instead of tearing one off.
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (!change.pressed) return@awaitEachGesture
                            if ((change.position - down.position).getDistance() > slop) {
                                change.consume()
                                break
                            }
                        }
                        session.begin(id, title, floatingWindow, colors)
                        try {
                            val released = drag(down.id) { it.consume(); session.track() }
                            if (released) { session.track(); session.finish() } else session.cancel()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            session.cancel()
                            throw e
                        }
                    }
                }.clickable(indication = null, interactionSource = null) {
                    onSelect()
                    if (canvas != null) viewModel.focusCanvas(canvas.id)
                }
                .padding(horizontal = if (tab) 10.dp else 7.dp, vertical = 2.dp))
        if (showCanvasTools) {
            // Edit / Preview is picked from the canvas's own mode menu, with the editing modes.
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
                        showSelectionFocus = canvas.mode == CanvasMode.EDIT,
                        onReset = {
                            viewMenu = false
                            viewModel.resetCanvasViewOptions(canvas.id, canvas.mode)
                        },
                    )
                }
            }
            Box {
                CanvasModeChip(
                    label = "${tr("canvas.background.short")} \u25BE",
                    active = state.canvasBackground != CanvasBackground(),
                    modifier = Modifier,
                    onClick = { backgroundMenu = true },
                )
                TabStripDropdown(expanded = backgroundMenu, onDismissRequest = { backgroundMenu = false }) {
                    CanvasBackgroundMenuItems(state.canvasBackground, viewModel::setCanvasBackground)
                }
            }
        }
        if (floating) {
            DockHeaderIcon({ session.returnToDock(id) }) {
                Text("↙", color = colors.textMuted, fontSize = 11.sp)
            }
        }
        TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
            CompactMenuItem(text = tr(if (floating) "dock.return" else "dock.detach"), onClick = {
                menu = false
                if (floating) session.returnToDock(id) else session.detach(id)
            })
            CompactMenuItem(
                text = tr(if (canvas != null) "window.closeCanvas" else "dock.close"),
                enabled = canClose,
                onClick = {
                    menu = false
                    close()
                },
            )
            if (siblings.size > 1) {
                // Other tabs of the group are hidden, not destroyed, so a canvas among them keeps its view.
                CompactMenuItem(text = tr("dock.closeOthers"), onClick = {
                    menu = false
                    siblings.filter { it != id }.forEach { viewModel.setModuleVisible(it, false) }
                    onSelect()
                })
            }
        }
    }
}

/** A small square button at the end of a floating dock header. */
@Composable
private fun DockHeaderIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalToolColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
        .background(if (hovered) colors.controlHover else Color.Transparent)
        .hoverable(interaction)
        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        content()
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

private fun dockTabTitle(id: String, state: PSD2LiveState): String =
    state.activeWorkspace.canvases.firstOrNull { it.id == id }?.let { canvasHeaderTitle(it, state.activeWorkspace) }
        ?: moduleTitle(id)

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
	onOpenTutorialCatalog: (() -> Unit)? = null,
	onStartTutorial: ((TutorialId) -> Unit)? = null,
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
			onOpenTutorialCatalog = onOpenTutorialCatalog,
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
				ModelPresetsSection(
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
		"simulation" -> io.github.psd2live.ui.views.simulation.SimulationPanelView(vm, state)
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


/** A dock with nothing shown (a blank workspace, or every panel hidden): offer a canvas to start from. */
@Composable
private fun EmptyDockPrompt(viewModel: PSD2LiveViewModel) {
    val colors = LocalToolColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkspacePresetIcon(WorkspacePreset.BLANK, colors.textMuted, Modifier.size(28.dp))
        Text(tr("dock.blank"), color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(tr("dock.blank.hint"), color = colors.textMuted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            io.github.psd2live.ui.components.CompactButton(tr("dock.blank.editCanvas"), { viewModel.showCanvas(CanvasMode.EDIT) }, isPrimary = true)
            io.github.psd2live.ui.components.CompactButton(tr("dock.blank.previewCanvas"), { viewModel.showCanvas(CanvasMode.PREVIEW) })
        }
    }
}
