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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
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
import io.github.psd2live.ui.state.*
import io.github.psd2live.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.awt.MouseInfo
import java.awt.Cursor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.util.prefs.Preferences

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

    fun begin(module: String, title: String, window: java.awt.Window?) {
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
            dragPreview = javax.swing.JWindow().apply {
                focusableWindowState = false
                isAlwaysOnTop = true
                val label = javax.swing.JLabel(title).apply {
                    foreground = java.awt.Color(223, 225, 229)
                    background = java.awt.Color(43, 45, 48)
                    isOpaque = true
                    verticalAlignment = javax.swing.SwingConstants.TOP
                    border = javax.swing.BorderFactory.createCompoundBorder(
                        javax.swing.BorderFactory.createLineBorder(java.awt.Color(75, 126, 232)),
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

private val dockPreferences by lazy { Preferences.userRoot().node("io.github.psd2live.docking.v1") }
private val dockJson = Json { ignoreUnknownKeys = true }

@Composable
internal fun DockWorkspaceView(
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    modifier: Modifier = Modifier,
    mainWindow: java.awt.Window? = null,
) {
    val sessions = remember { mutableMapOf<String, DockSession>() }
    val tab = state.activeWorkspaceTab
    val preferenceKey = "${tab.kind.name}-${tab.ordinal}"
    val session = sessions.getOrPut(tab.id) {
        val default = defaultDockLayout(tab.kind == WorkspaceTabKind.HISTORY)
        val saved = runCatching {
            dockJson.decodeFromString<DockNode>(dockPreferences.get(preferenceKey, ""))
        }.getOrNull()?.remove("export")
        DockSession(saved?.takeIf { it.allModules().toSet() == default.allModules().toSet() &&
            it.allModules().size == default.allModules().size } ?: default)
    }
    val hiddenModules = buildSet {
        if (state.hierarchyCollapsed) add("hierarchy")
        if (!state.logPanelExpanded) add("log")
        if (state.inspectorCollapsed) addAll(listOf("settings", "layers", "parameters",
            "tools", "inspector", "animation", "physics"))
    }
    // Visibility is a projection of the saved layout: toggling a panel must not remove
    // its tab group, split ratio, or floating-window placement from the layout.
    val visibleRoot = hiddenModules.fold(session.root) { layout, module -> layout?.remove(module) }
    SideEffect { session.hiddenModules = hiddenModules }
    LaunchedEffect(state.workspaceTabs.map { it.id }) {
        sessions.keys.retainAll(state.workspaceTabs.map { it.id }.toSet())
    }
    // Debounce splitter updates; persist a docked fallback for floating panels so no panel is lost.
    LaunchedEffect(session.root, session.floating.keys.toList()) {
        delay(400)
        var saved = session.root
        session.floating.keys.forEach { saved = dockModule(saved, it, null, DockSide.RIGHT) }
        saved?.let { runCatching { dockPreferences.put(preferenceKey, dockJson.encodeToString(it)) } }
    }
    val latestState by rememberUpdatedState(state)
    val contents = remember(tab.id) { mutableMapOf<String, @Composable () -> Unit>() }
    fun content(id: String): @Composable () -> Unit = contents.getOrPut(id) {
        movableContentOf { DockModuleContent(id, latestState, viewModel) }
    }
    DisposableEffect(session) { onDispose { session.cancel() } }
    LaunchedEffect(session, session.dragging) {
        while (session.dragging != null) {
            session.track()
            delay(16)
        }
    }
    val colors = LocalToolColors.current
    Column(modifier.background(colors.windowBackground)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceTabStrip(state, viewModel, Modifier.weight(1f))
            Text(tr("dock.reset"), color = colors.textMuted, fontSize = 11.sp,
                modifier = Modifier.clickable {
                    session.floating.clear()
                    session.root = defaultDockLayout(tab.kind == WorkspaceTabKind.HISTORY)
                }.padding(horizontal = 8.dp))
        }
        key(tab.id) {
            Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { coordinates ->
                session.workspaceBounds = { screenBounds(coordinates, mainWindow) }
            }) {
                visibleRoot?.let {
                    DockTree(it, session, Modifier.fillMaxSize(), mainWindow, ::content)
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
    if (viewModel.canvasEditor.showRebuildMeshDialog) {
        io.github.psd2live.ui.components.RebuildMeshPromptDialog(
            layerName = viewModel.canvasEditor.paintSession?.layerName.orEmpty(),
            onConfirmRebuild = { viewModel.canvasEditor.commitPaintSession(rebuildMesh = true) },
            onKeepExisting = { viewModel.canvasEditor.commitPaintSession(rebuildMesh = false) },
            onDismiss = { viewModel.canvasEditor.showRebuildMeshDialog = false })
    }
    session.floating.toMap().forEach { (id, windowState) ->
        key(tab.id, id) {
            Window(onCloseRequest = { session.returnToDock(id) }, state = windowState,
                title = "${viewModel.tabTitle(tab)} · ${moduleTitle(id)}", undecorated = true,
                visible = id !in hiddenModules) {
                DisposableEffect(window) {
                    session.floatingWindows[id] = window
                    onDispose { session.floatingWindows.remove(id) }
                }
                // Match the main window's custom density and theme.
                CompactToolTheme(uiScale = AppSettings.uiScale, fontScale = AppSettings.fontScale) {
                    Column(Modifier.fillMaxSize().background(LocalToolColors.current.panelBackground)) {
                        DockHeader(id, session, Modifier.fillMaxWidth(), floating = true, floatingWindow = window)
                        Box(Modifier.weight(1f).fillMaxWidth()) { content(id)() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTree(node: DockNode, session: DockSession, modifier: Modifier, window: java.awt.Window?,
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
                DockTree(node.first, session, Modifier.weight(node.ratio).fillMaxHeight(), window, content)
                Box(splitter.width(4.dp).fillMaxHeight())
                DockTree(node.second, session, Modifier.weight(1f - node.ratio).fillMaxHeight(), window, content)
            } else Column(Modifier.fillMaxSize()) {
                DockTree(node.first, session, Modifier.weight(node.ratio).fillMaxWidth(), window, content)
                Box(splitter.height(4.dp).fillMaxWidth())
                DockTree(node.second, session, Modifier.weight(1f - node.ratio).fillMaxWidth(), window, content)
            }
        }
        return
    }
    DisposableEffect(node.id) { onDispose { session.bounds.remove(node.id) } }
    Box(modifier.onGloballyPositioned { coordinates ->
        session.bounds[node.id] = {
            screenBounds(coordinates, window)?.let { r ->
                val scale = window?.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
                val headerHeight = with(density) { 22.dp.toPx() } / scale
                DockHitArea(r, Rect(r.left, r.top, r.right, r.top + headerHeight),
                    with(density) { 8.dp.toPx() } / scale)
            }
        }
    }) {
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp))
            .background(colors.panelBackground)
            .border(.5.dp, colors.divider, RoundedCornerShape(3.dp))) {
            val single = node.modules.size == 1
            if (single) {
                DockHeader(node.selected, session, Modifier.fillMaxWidth(), standalone = true)
            } else {
                Row(Modifier.fillMaxWidth().height(22.dp)
                    .background(colors.windowBackground)
                    .horizontalScroll(rememberScrollState())) {
                    node.modules.forEach { id ->
                        DockHeader(id, session, Modifier, selected = id == node.selected, standalone = false,
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
                       selected: Boolean = true, floating: Boolean = false, standalone: Boolean = true,
                       floatingWindow: java.awt.Window? = null, onSelect: () -> Unit = {}) {
    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    Row(modifier.height(22.dp)
        .clip(if (standalone) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
        .background(when {
            hovered -> colors.controlHover
            standalone -> colors.panelElevated
            selected -> colors.panelBackground
            else -> colors.windowBackground
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
        Text(moduleTitle(id), color = if (selected) colors.textPrimary else colors.textMuted,
            style = typography.body.copy(fontSize = 11.sp,
                fontWeight = if (standalone || selected) FontWeight.Medium else FontWeight.Normal),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = (if (standalone || floating) Modifier.weight(1f) else Modifier.widthIn(min = 54.dp, max = 140.dp))
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)))
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Secondary) { menu = true; event.changes.forEach { it.consume() } }
                }
                .pointerInput(id, session, floatingWindow) {
                    detectDragGestures(
                        onDragStart = { session.begin(id, moduleTitle(id), floatingWindow) },
                        onDrag = { change, _ -> change.consume(); session.track() },
                        onDragEnd = { session.track(); session.finish() },
                        onDragCancel = { session.cancel() },
                    )
                }.clickable(interactionSource = interaction, indication = null, onClick = onSelect)
                .padding(horizontal = 7.dp, vertical = 2.dp))
        if (floating) {
            Text("↙", color = colors.textMuted,
                modifier = Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))).clickable { session.returnToDock(id) }.padding(horizontal = 7.dp))
        }
        TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
            CompactMenuItem(text = tr(if (floating) "dock.return" else "dock.detach"), onClick = {
                menu = false
                if (floating) session.returnToDock(id) else session.detach(id)
            })
        }
    }
}

private fun moduleTitle(id: String): String = tr(when (id) {
    "canvas" -> "dock.canvas"; "hierarchy" -> "dock.hierarchy"; "history" -> "dock.history"
    "log" -> "dock.log"; "settings" -> "dock.settings"
    "layers" -> "tab.layers"; "parameters" -> "tab.parameters"; "tools" -> "tab.toolDetails"
    "inspector" -> "tab.inspector"; "animation" -> "tab.animation"; "physics" -> "tab.physics"
    else -> id
})

@Composable
private fun DockModuleContent(id: String, state: PSD2LiveState, vm: PSD2LiveViewModel) {
    when (id) {
        "canvas" -> CanvasViewportComposable(mode = state.activeTabKind.canvasMode ?: CanvasMode.EDIT,
            state = state, viewModel = vm, modifier = Modifier.fillMaxSize(), onLayerClicked = vm::selectLayer)
        "hierarchy" -> DockHierarchyView(state, vm, state.activeTabKind.canvasMode ?: CanvasMode.EDIT,
            onRequestOpenDeformPaths = { vm.selectLayer(it); vm.requestCanvasPathTool() },
            onRequestCreate = { kind, relation, isDeformer, target -> vm.canvasEditor.beginTreeCreate(kind, relation, isDeformer, target) })
        "history" -> HistoryTreeView(state, vm, Modifier.fillMaxSize())
        "log" -> BottomLogDock(state, vm, Modifier.fillMaxSize(), fillDock = true)
        "settings" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ModelSettingsSection(state, vm, state.modelSettingsExpanded, { vm.setModelSettingsExpanded(!state.modelSettingsExpanded) })
        }
        "layers" -> LayersTableView(state, vm)
        "parameters" -> ParametersListView(state, vm)
        "tools" -> ToolDetailsView(vm.canvasEditor, vm, state)
        "inspector" -> InspectorPanelView(vm.canvasEditor, vm, state)
        "animation" -> AnimationPanelView(vm, state)
        "physics" -> PhysicsPanelView(vm, state)
    }
}
