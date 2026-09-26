package io.github.psd2live.ui.views

import kotlinx.serialization.Serializable
import java.util.UUID
import io.github.psd2live.ui.state.EditorWorkspace
import io.github.psd2live.ui.state.PRIMARY_CANVAS_ID
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.isCanvasModule

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

internal fun dockModule(root: DockNode?, module: String, target: String?, side: DockSide): DockNode {
    if (root != null && root.find(target.orEmpty())?.modules == listOf(module)) return root
    val clean = root?.remove(module)
    val leaf = DockNode(modules = listOf(module))
    if (clean == null) return leaf
    // Dropping the only module onto itself must leave the layout intact.
    if (root.id == target && root.modules == listOf(module)) return root
    var found = false
    val result = clean.update(target.orEmpty()) { node ->
        found = true
        if (side == DockSide.CENTER) node.copy(modules = node.modules + module, selected = module)
        else DockNode(horizontal = side == DockSide.LEFT || side == DockSide.RIGHT,
            first = if (side == DockSide.LEFT || side == DockSide.TOP) leaf else node,
            second = if (side == DockSide.LEFT || side == DockSide.TOP) node else leaf)
    }
    return if (found) result else DockNode(first = clean, second = leaf, ratio = .75f)
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
            val side = if (module == "history") DockSide.LEFT else DockSide.BOTTOM
            result = dockBesideModule(result, module, anchor, side)
        }
    }
    return result
}

/** Repair the exact root-level split produced by the old module-ID auto-placement bug. */
internal fun repairLegacyCanvasDocking(saved: DockNode): DockNode {
    val withMesh = ensureAnimationEditorDockTab(ensureSkeletonDockTab(ensureMeshDockTab(saved)))
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

internal fun defaultDockLayout(canvas: String = PRIMARY_CANVAS_ID): DockNode {
    val canvasAndLog = DockNode(horizontal = false, ratio = .72f,
        first = DockNode(modules = listOf(canvas)),
        second = DockNode(modules = listOf("log", "animationEditor")))
    val workspace = DockNode(ratio = .28f, first = DockNode(modules = listOf("hierarchy", "skeleton")), second = canvasAndLog)
    return DockNode(ratio = .60f, first = workspace,
        second = DockNode(horizontal = false, ratio = .32f,
            first = DockNode(modules = listOf("settings")),
            second = DockNode(modules = listOf("layers", "parameters", "tools", "mesh", "inspector", "animation", "physics"))))
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
        WorkspacePreset.EDIT -> defaultDockLayout(names[0])
        WorkspacePreset.MESH -> row(.22f, leaf("layers", "hierarchy", "skeleton"),
            row(.72f, column(.78f, leaf(names[0]), leaf("log", "animationEditor")),
                column(.55f, leaf("mesh", "tools"), leaf("inspector", "parameters", "settings", "animation", "physics"))))
        WorkspacePreset.RIG -> row(.20f, leaf("hierarchy", "skeleton"),
            row(.75f, column(.76f, row(.5f, leaf(names[0]), leaf(names[1])), leaf("log", "animationEditor")),
                column(.45f, leaf("parameters"), leaf("inspector", "tools", "mesh", "layers", "settings", "animation", "physics"))))
        WorkspacePreset.ANIMATION -> row(.20f, leaf("parameters", "hierarchy", "skeleton"),
            row(.76f, column(.55f, leaf(names[0]), leaf("animationEditor", "log")),
                column(.50f, leaf("animation"), leaf("physics", "settings", "layers", "tools", "mesh", "inspector"))))
        WorkspacePreset.PREVIEW -> row(.16f, leaf("hierarchy", "skeleton"),
            row(.78f, column(.80f, leaf(names[0]), leaf("log", "animationEditor")),
                column(.50f, leaf("parameters"), leaf("animation", "physics", "settings", "layers", "tools", "mesh", "inspector"))))
    }
    return names.filterIndexed { index, _ -> slots[index] == null }
        .fold(layout) { node, placeholder -> node.remove(placeholder) ?: node }
}
