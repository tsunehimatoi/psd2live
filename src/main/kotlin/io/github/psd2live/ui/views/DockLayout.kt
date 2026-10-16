package io.github.psd2live.ui.views

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlinx.serialization.json.Json
import io.github.psd2live.ui.state.*

internal enum class DockSide { CENTER, LEFT, RIGHT, TOP, BOTTOM }

@Serializable
internal data class DockNode(
    val id: String = UUID.randomUUID().toString(),
    val modules: List<String> = emptyList(),
    val selected: String = modules.firstOrNull().orEmpty(),
    val horizontal: Boolean = true,
    val ratio: Float = .5f,
    val first: DockNode? = null,
    val second: DockNode? = null,
) {
    fun allModules(): List<String> = if (first != null && second != null)
        first.allModules() + second.allModules() else modules

    fun find(target: String): DockNode? = if (id == target) this
        else first?.find(target) ?: second?.find(target)

    fun containing(module: String): DockNode? = if (module in modules) this
        else first?.containing(module) ?: second?.containing(module)

    fun update(target: String, transform: (DockNode) -> DockNode): DockNode = when {
        id == target -> transform(this)
        else -> copy(first = first?.update(target, transform), second = second?.update(target, transform))
    }

    fun remove(module: String): DockNode? {
        if (first != null && second != null) {
            val a = first.remove(module)
            val b = second.remove(module)
            return when { a == null -> b; b == null -> a; else -> copy(first = a, second = b) }
        }
        val remaining = modules - module
        return if (remaining.isEmpty()) null else copy(modules = remaining,
            selected = selected.takeIf { it in remaining } ?: remaining.first())
    }
}

/**
 * Move [module] into leaf [target]: as a tab on [DockSide.CENTER], placed before the tab [before]
 * (last when null or absent), or as a new split on any other side.
 */
internal fun dockModule(root: DockNode?, module: String, target: String?, side: DockSide, before: String? = null): DockNode {
    if (root != null && root.find(target.orEmpty())?.modules == listOf(module)) return root
    val clean = root?.remove(module)
    val leaf = DockNode(modules = listOf(module))
    if (clean == null) return leaf
    // Dropping the only module onto itself must leave the layout intact.
    if (root.id == target && root.modules == listOf(module)) return root
    var found = false
    val result = clean.update(target.orEmpty()) { node ->
        found = true
        if (side == DockSide.CENTER) {
            val at = node.modules.indexOf(before).takeIf { it >= 0 } ?: node.modules.size
            node.copy(modules = node.modules.toMutableList().apply { add(at, module) }, selected = module)
        }
        else DockNode(horizontal = side == DockSide.LEFT || side == DockSide.RIGHT,
            first = if (side == DockSide.LEFT || side == DockSide.TOP) leaf else node,
            second = if (side == DockSide.LEFT || side == DockSide.TOP) node else leaf)
    }
    return if (found) result else DockNode(first = clean, second = leaf, ratio = .75f)
}

/**
 * Move every tab of leaf [group] into leaf [target]: merged into its tabs on [DockSide.CENTER] before the tab
 * [before] (last when null or absent), keeping the group's selected tab, or as the same group split on any other side.
 */
internal fun dockGroup(root: DockNode, group: String, target: String, side: DockSide, before: String? = null): DockNode {
    val source = root.find(group)?.takeIf { it.first == null && it.second == null } ?: return root
    if (group == target) return root
    val clean = source.modules.fold<String, DockNode?>(root) { layout, module -> layout?.remove(module) } ?: return root
    if (clean.find(target) == null) return root
    return clean.update(target) { node ->
        if (side == DockSide.CENTER) {
            val at = node.modules.indexOf(before).takeIf { it >= 0 } ?: node.modules.size
            node.copy(modules = node.modules.toMutableList().apply { addAll(at, source.modules) }, selected = source.selected)
        }
        else DockNode(horizontal = side == DockSide.LEFT || side == DockSide.RIGHT,
            first = if (side == DockSide.LEFT || side == DockSide.TOP) source else node,
            second = if (side == DockSide.LEFT || side == DockSide.TOP) node else source)
    }
}

/** Auto-placement chooses a module, while [dockModule] addresses a leaf node ID. */
internal fun dockBesideModule(root: DockNode?, module: String, anchorModule: String?, side: DockSide): DockNode =
    dockModule(root, module, anchorModule?.let { root?.containing(it)?.id }, side)

/** Resolve canvas and requested panel modules before the tree is first composed. */
internal fun reconcileDockModules(
    root: DockNode?,
    canvasIds: List<String>,
    placeModules: List<String>,
): DockNode? {
    var result = root
    val validCanvasIds = canvasIds.toSet()
    result?.allModules()?.filter { isCanvasModule(it) && it !in validCanvasIds }?.forEach { id ->
        result = result?.remove(id)
    }
    canvasIds.forEach { id ->
        if (result?.allModules()?.contains(id) != true) {
            val anchor = result?.allModules()?.firstOrNull(::isCanvasModule)
            result = dockBesideModule(result, id, anchor, DockSide.RIGHT)
        }
    }
    placeModules.forEach { module ->
        if (result?.allModules()?.contains(module) != true) {
            val anchor = result?.allModules()?.firstOrNull(::isCanvasModule) ?: result?.allModules()?.firstOrNull()
            val side = when (module) {
                "history", "atlas", "materials" -> DockSide.LEFT
                "texture" -> DockSide.RIGHT
                else -> DockSide.BOTTOM
            }
            result = dockBesideModule(result, module, anchor, side)
        }
    }
    return result
}

/** Repair the exact root-level split produced by the old module-ID auto-placement bug. */
internal fun repairLegacyCanvasDocking(saved: DockNode): DockNode {
    val withMesh = ensureInteriorDockTab(ensureSimulationDockTab(ensureAnimationEditorDockTab(ensureSkeletonDockTab(ensureMeshDockTab(saved)))))
    val defaultShape = defaultDockLayout()
    fun sameDefaultShape(node: DockNode, expected: DockNode): Boolean =
        node.modules == expected.modules && node.horizontal == expected.horizontal && node.ratio == expected.ratio &&
            when {
                node.first == null && node.second == null -> expected.first == null && expected.second == null
                node.first != null && node.second != null && expected.first != null && expected.second != null ->
                    sameDefaultShape(node.first, expected.first) && sameDefaultShape(node.second, expected.second)
                else -> false
            }

    fun unwrap(node: DockNode): Pair<DockNode, List<String>>? {
        if (sameDefaultShape(node, defaultShape)) return node to emptyList()
        val added = node.second?.takeIf { it.first == null && it.second == null }
            ?.modules?.singleOrNull()?.takeIf { it.startsWith("canvas:") } ?: return null
        if (!node.horizontal || node.ratio != .75f) return null
        val (base, extras) = node.first?.let(::unwrap) ?: return null
        return base to (extras + added)
    }

    val (base, extras) = unwrap(withMesh) ?: return withMesh
    if (extras.isEmpty()) return withMesh
    var repaired = base
    var anchor = "canvas"
    for (canvas in extras) {
        repaired = dockBesideModule(repaired, canvas, anchor, DockSide.RIGHT)
        anchor = canvas
    }
    return repaired
}

/** Insert the mesh tab into the inspector leaf if an older saved layout is missing it. */
internal fun ensureMeshDockTab(root: DockNode): DockNode {
    if (root.allModules().contains("mesh")) return root
    val host = root.containing("inspector") ?: root.containing("layers") ?: return root
    return root.update(host.id) { node ->
        val modules = node.modules.toMutableList()
        val insertAt = modules.indexOf("inspector").takeIf { it >= 0 }
            ?: (modules.indexOf("tools").takeIf { it >= 0 }?.plus(1))
            ?: modules.size
        modules.add(insertAt.coerceIn(0, modules.size), "mesh")
        node.copy(modules = modules)
    }
}

/** Insert the skeleton tab beside the hierarchy if an older saved layout is missing it. */
internal fun ensureSkeletonDockTab(root: DockNode): DockNode {
    if (root.allModules().contains("skeleton")) return root
    val host = root.containing("hierarchy") ?: return root
    return root.update(host.id) { node ->
        val modules = node.modules.toMutableList()
        modules.add((modules.indexOf("hierarchy") + 1).coerceIn(0, modules.size), "skeleton")
        node.copy(modules = modules)
    }
}

/** Insert the animation editor tab beside the log if an older saved layout is missing it. */
internal fun ensureAnimationEditorDockTab(root: DockNode): DockNode {
    if (root.allModules().contains("animationEditor")) return root
    val host = root.containing("log") ?: return root
    return root.update(host.id) { node ->
        val modules = node.modules.toMutableList()
        modules.add((modules.indexOf("log") + 1).coerceIn(0, modules.size), "animationEditor")
        node.copy(modules = modules)
    }
}

internal fun ensureInteriorDockTab(root: DockNode): DockNode {
    if ("interior" in root.allModules()) return root
    val host=root.containing("simulation")?:root.containing("physics")?:return root
    return root.update(host.id) { it.copy(modules=it.modules+"interior") }
}

/** Insert the simulation tab after physics if an older saved layout is missing it. */
internal fun ensureSimulationDockTab(root: DockNode): DockNode {
    if (root.allModules().contains("simulation")) return root
    val host = root.containing("physics") ?: return root
    return root.update(host.id) { node ->
        val modules = node.modules.toMutableList()
        modules.add((modules.indexOf("physics") + 1).coerceIn(0, modules.size), "simulation")
        node.copy(modules = modules)
    }
}

internal fun defaultDockLayout(canvas: String = PRIMARY_CANVAS_ID): DockNode {
    val canvasAndLog = DockNode(horizontal = false, ratio = .72f,
        first = DockNode(modules = listOf(canvas)),
        second = DockNode(modules = listOf("log", "animationEditor")))
    val workspace = DockNode(ratio = .28f, first = DockNode(modules = listOf("hierarchy", "skeleton")), second = canvasAndLog)
    return DockNode(ratio = .60f, first = workspace,
        second = DockNode(horizontal = false, ratio = .32f,
            first = DockNode(modules = listOf("settings")),
            second = DockNode(modules = listOf("layers", "parameters", "tools", "mesh", "inspector", "animation", "physics", "simulation", "interior"))))
}

private fun leaf(vararg modules: String) = DockNode(modules = modules.toList())
private fun row(ratio: Float, first: DockNode, second: DockNode) =
    DockNode(horizontal = true, ratio = ratio, first = first, second = second)
private fun column(ratio: Float, first: DockNode, second: DockNode) =
    DockNode(horizontal = false, ratio = ratio, first = first, second = second)

/**
 * The arrangement a workspace starts from and "reset layout" restores. Each preset canvas slot
 * takes the first remaining canvas of its mode (or any remaining canvas); a slot with no canvas
 * left is dropped, and canvases beyond the slots are docked by [reconcileDockModules].
 */
internal fun presetDockLayout(workspace: EditorWorkspace): DockNode {
    val preset = workspace.preset
    val unused = workspace.canvases.toMutableList()
    val slots = preset.canvasModes.map { mode ->
        val canvas = unused.firstOrNull { it.mode == mode } ?: unused.firstOrNull()
        canvas?.let { unused.remove(it); it.id }
    }
    // Placeholders keep each tree readable; an unfilled one is pruned below.
    val names = slots.mapIndexed { index, id -> id ?: "#slot$index" }
    val layout = when (preset) {
        WorkspacePreset.EDIT, WorkspacePreset.BLANK -> defaultDockLayout(names[0])
        WorkspacePreset.MESH -> row(.22f, leaf("hierarchy", "layers", "skeleton"),
            row(.72f, column(.78f, leaf(names[0]), leaf("log", "animationEditor")),
                column(.55f, leaf("mesh", "tools"), leaf("inspector", "parameters", "settings", "animation", "physics", "simulation", "interior"))))
        WorkspacePreset.RIG -> row(.20f, leaf("hierarchy", "skeleton"),
            row(.75f, column(.76f, row(.5f, leaf(names[0]), leaf(names[1])), leaf("log", "animationEditor")),
                column(.45f, leaf("parameters"), leaf("inspector", "tools", "mesh", "layers", "settings", "animation", "physics", "simulation", "interior"))))
        WorkspacePreset.ANIMATION -> row(.78f,
            column(.62f, row(.26f, leaf("animation", "physics", "simulation", "interior", "hierarchy", "skeleton"), leaf(names[0])),
                leaf("animationEditor", "log")),
            leaf("parameters", "settings", "layers", "tools", "mesh", "inspector"))
        WorkspacePreset.PREVIEW -> row(.16f, leaf("hierarchy", "skeleton"),
            row(.78f, column(.80f, leaf(names[0]), leaf("log", "animationEditor")),
                column(.50f, leaf("animation", "physics", "simulation", "interior", "settings", "layers", "tools", "mesh", "inspector"), leaf("parameters"))))
        WorkspacePreset.PHYSICS -> row(.5f, column(.82f, leaf(names[0]), leaf("log", "animationEditor")),
            row(.5f, leaf("parameters", "hierarchy", "skeleton"),
                leaf("physics", "simulation", "interior", "animation", "settings", "layers", "tools", "mesh", "inspector")))
        WorkspacePreset.TEXTURE -> row(.40f, leaf("atlas", "hierarchy", "skeleton"),
            row(.64f, column(.80f, leaf(names[0]), leaf("log", "animationEditor")),
                column(.60f, leaf("texture"), leaf("layers", "inspector", "tools", "mesh", "parameters", "settings", "animation", "physics", "simulation", "interior"))))
        WorkspacePreset.LAYOUT -> row(.24f, leaf("materials"),
            row(.70f, column(.82f, leaf(names[0]), leaf("log", "animationEditor")),
                column(.45f, leaf("hierarchy", "skeleton"), leaf("layers", "inspector", "tools", "mesh", "parameters", "settings", "animation", "physics", "simulation", "interior"))))
        WorkspacePreset.HISTORY -> row(.5f, leaf("history"), defaultDockLayout(names[0]))
    }
    return names.filterIndexed { index, _ -> slots[index] == null }
        .fold(layout) { node, placeholder -> node.remove(placeholder) ?: node }
}

/** What a new workspace of [preset] shows: its arrangement without the panels it starts hidden. */
internal fun presetVisibleLayout(preset: WorkspacePreset): Pair<DockNode?, EditorWorkspace> {
    val workspace = presetEditorWorkspace("preset:${preset.name}", preset)
    val visible = workspace.hiddenModules.fold<String, DockNode?>(presetDockLayout(workspace)) { node, module -> node?.remove(module) }
    return visible to workspace
}

internal val dockJson = Json { ignoreUnknownKeys = true }

/** The arrangement [page] starts from and "reset layout" restores; every panel of the page is in it. */
internal fun pageDockLayout(page: AppPage): DockNode = when (page) {
    AppPage.START -> row(.17f, leaf(START_NAV), row(.72f, leaf(START_MAIN), leaf(START_DETAILS)))
    AppPage.HELP -> row(.20f, leaf(HELP_NAV), leaf(HELP_CONTENT))
    AppPage.SETTINGS -> row(.20f, leaf(SETTINGS_NAV), leaf(SETTINGS_CONTENT))
}

/**
 * The dock tree [page] is arranged by: its saved layout when that still decodes and names only the page's own panels,
 * each once, otherwise its default. A panel the page gained since joins the first group as a tab.
 */
internal fun pageDockRoot(page: AppPage, arrangement: PageArrangement): DockNode {
    val saved = arrangement.layoutJson?.let { raw -> runCatching { dockJson.decodeFromString<DockNode>(raw) }.getOrNull() }
        ?.takeIf { node -> node.allModules().let { modules -> modules.all { it in page.modules } && modules.size == modules.toSet().size } }
    val root = saved ?: pageDockLayout(page)
    return page.modules.filter { it !in root.allModules() }.fold(root) { node, module ->
        dockModule(node, module, node.firstLeaf().id, DockSide.CENTER)
    }
}

private fun DockNode.firstLeaf(): DockNode = first?.firstLeaf() ?: this

/**
 * The dock tree [workspace] is arranged by: its saved layout when that still decodes and names only
 * known modules, otherwise its preset's. Hidden modules are still in it; the dock projects them out.
 */
internal fun workspaceDockRoot(workspace: EditorWorkspace): DockNode {
    val allowed = DEFAULT_DOCK_MODULES + setOf("history") + TEXTURE_DOCK_MODULES + LAYOUT_DOCK_MODULES + workspace.canvases.map { it.id }
    val saved = workspace.layoutJson?.let { raw ->
        runCatching { dockJson.decodeFromString<DockNode>(raw) }.getOrNull()?.remove("export")
    }?.takeIf { node -> node.allModules().all { it in allowed } }
    return saved?.let(::repairLegacyCanvasDocking) ?: presetDockLayout(workspace)
}

/**
 * Panels around the canvases, by side. Walking from the root toward the smallest subtree that
 * holds every canvas, each split's other half is a sidebar on the side it sits. Whatever shares a
 * split or a tab group with a canvas is part of the canvas area, not a sidebar.
 */
internal fun dockSidebars(root: DockNode?): Map<SidebarSide, List<String>> {
    val result = linkedMapOf<SidebarSide, List<String>>()
    var node = root ?: return result
    while (true) {
        val first = node.first ?: break
        val second = node.second ?: break
        val inFirst = first.allModules().any(::isCanvasModule)
        val inSecond = second.allModules().any(::isCanvasModule)
        if (inFirst == inSecond) break
        val side = when {
            node.horizontal -> if (inFirst) SidebarSide.RIGHT else SidebarSide.LEFT
            else -> if (inFirst) SidebarSide.BOTTOM else SidebarSide.TOP
        }
        result[side] = result[side].orEmpty() + (if (inFirst) second else first).allModules()
        node = if (inFirst) first else second
    }
    return result
}

/** [dockSidebars] of the arrangement [workspace] is docked in, hidden panels included. */
internal fun EditorWorkspace.sidebars(): Map<SidebarSide, List<String>> =
    dockSidebars(reconcileDockModules(workspaceDockRoot(this), canvases.map { it.id }, placeModules))
