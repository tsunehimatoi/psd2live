package io.github.psd2live.core.modifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A named node is a folder; an unnamed node refers to a persistent modifier instance. */
@Serializable
internal data class ModifierPanelNode(val id: String, val parent: String? = null, val name: String? = null)

internal object RigModifierOrganization {
    private val codec = Json { encodeDefaults = true }
    private val serializer = ListSerializer(ModifierPanelNode.serializer())
    fun encode(nodes: List<ModifierPanelNode>): JsonArray = codec.encodeToJsonElement(serializer, nodes) as JsonArray
    fun decode(value: JsonArray): List<ModifierPanelNode> = codec.decodeFromJsonElement(serializer, value)

    fun category(type: String): String = when {
        type == RigSourceModifiers.SCAFFOLD -> "structure"
        type == "motion" -> "motion"
        type == "simulation" || type.startsWith("clothing") || type.startsWith("cloth") || type.startsWith("outfit") -> "clothing"
        type == "physics" || type == "swing" -> "physics"
        type.startsWith("eye") || type.startsWith("brow") -> "eyes"
        type.startsWith("mouth") -> "mouth"
        type.startsWith("face") -> "face"
        type.startsWith("hair") -> "hair"
        type.startsWith("body") -> "body"
        else -> "other"
    }

    fun category(instance: RigModifierInstance): String {
        val kind = instance.config[if (instance.type == "swing") "preset" else "kind"]?.jsonPrimitive?.contentOrNull
        if (instance.type == "simulation" || instance.type == "swing") when (kind?.lowercase()) {
            "hair" -> return "hair"
            "cloth" -> return "clothing"
        }
        if (instance.type == "physics") {
            val source = instance.config["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (source.startsWith("PhysicsHair")) return "hair"
            if (source.startsWith("PhysicsEye")) return "eyes"
            if (source.startsWith("PhysicsMouth")) return "mouth"
        }
        return category(instance.type)
    }

    /** Default categories also absorb newly added items without disturbing the user's existing folders. */
    fun nodes(stack: RigModifierStack): List<ModifierPanelNode> {
        val ids = stack.instances.mapTo(HashSet()) { it.id }
        val result = stack.organization.orEmpty().filter { it.name != null || it.id in ids }.toMutableList()
        val present = result.mapTo(HashSet()) { it.id }
        for (instance in stack.instances) if (instance.id !in present) {
            val category = category(instance)
            val folder = "folder/$category"
            if (present.add(folder)) result += ModifierPanelNode(folder, name = "@modifier.category.$category")
            result += ModifierPanelNode(instance.id, folder)
            present += instance.id
        }
        return result
    }

    fun validate(stack: RigModifierStack, nodes: List<ModifierPanelNode>) {
        require(nodes.size <= 8192 && nodes.map { it.id }.distinct().size == nodes.size) { "Folder nodes must have distinct IDs" }
        val folders = nodes.filter { it.name != null }.associateBy { it.id }
        require(nodes.filter { it.name == null }.map { it.id }.toSet() == stack.instances.map { it.id }.toSet()) {
            "Folders must contain each modifier exactly once"
        }
        for (node in nodes) {
            require(node.name == null || (node.id.startsWith("folder/") && node.name.isNotBlank() && node.name.length <= 256)) { "Invalid modifier folder" }
            var parent = node.parent
            val visited = hashSetOf(node.id)
            while (parent != null) {
                require(visited.add(parent)) { "Modifier folders cannot contain a cycle" }
                require(visited.size <= 64) { "Modifier folders are nested too deeply" }
                parent = requireNotNull(folders[parent]) { "Unknown modifier folder: $parent" }.parent
            }
        }
    }

    fun descendants(nodes: List<ModifierPanelNode>, id: String): Set<String> {
        val found = mutableSetOf<String>()
        fun visit(parent: String) { nodes.filter { it.parent == parent }.forEach { if (found.add(it.id)) visit(it.id) } }
        visit(id)
        return found
    }

    fun move(nodes: List<ModifierPanelNode>, id: String, parent: String?, before: String?): List<ModifierPanelNode> {
        val node = nodes.single { it.id == id }
        require(parent != id && parent !in descendants(nodes, id)) { "A folder cannot contain itself" }
        val result = nodes.filterNot { it.id == id }.toMutableList()
        val index = before?.let { target -> result.indexOfFirst { it.id == target }.also { require(it >= 0) } } ?: result.size
        result.add(index, node.copy(parent = parent))
        return result
    }
}
