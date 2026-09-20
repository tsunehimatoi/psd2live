package io.github.psd2live.ui.views

import kotlinx.serialization.Serializable
import java.util.UUID

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

internal fun defaultDockLayout(history: Boolean): DockNode {
    val main = DockNode(modules = listOf(if (history) "history" else "canvas"))
    val canvasAndLog = DockNode(horizontal = false, ratio = .72f, first = main,
        second = DockNode(modules = listOf("log")))
    val workspace = if (history) canvasAndLog else DockNode(ratio = .28f,
        first = DockNode(modules = listOf("hierarchy")), second = canvasAndLog)
    return DockNode(ratio = .60f, first = workspace,
        second = DockNode(horizontal = false, ratio = .27f,
            first = DockNode(modules = listOf("settings")),
            second = DockNode(horizontal = false, ratio = .22f,
                first = DockNode(modules = listOf("export")),
                second = DockNode(modules = listOf("layers", "parameters", "tools", "inspector", "animation", "physics")))))
}
