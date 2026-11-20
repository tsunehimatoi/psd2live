package io.github.psd2live.core.modifier

import io.github.psd2live.format.model.ParameterNode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Standard parameter group keys shared between host UI localization and standalone plugins. */
object ModifierParameterGroups {
    const val EYES = "ParamGroupEyes"
    const val BROWS = "ParamGroupBrows"
    const val FACE = "ParamGroupFace"
    const val BODY = "ParamGroupBody"
    const val HAIR = "ParamGroupHair"
    const val MOUTH = "ParamGroupMouth"
    const val PHYSICS = "ParamGroupPhysics"
    const val EXPRESSIONS = "ParamGroupExpressions"
    const val CUSTOM = "ParamGroupCustom"
    const val SKELETON = "ParamGroupSkeleton"

    val STANDARD_ORDER: List<String> = listOf(
        FACE, EYES, BROWS, MOUTH, BODY, HAIR, PHYSICS, EXPRESSIONS, CUSTOM, SKELETON,
    )

    private val rankById: Map<String, Int> = STANDARD_ORDER.withIndex().associate { (index, id) -> id to index }

    fun canonicalRank(groupId: String): Int? = rankById[groupId]

    fun resolveId(instance: RigModifierInstance, standardId: String): String =
        instance.outputAliases["parameter-folder:$standardId"] ?: standardId

    /** Insert a top-level [group] in canonical standard order while leaving non-standard folders appended. */
    fun insertGroup(tree: List<ParameterNode>, group: ParameterNode.Group): List<ParameterNode> {
        val rank = canonicalRank(group.id) ?: return tree + group
        val index = tree.indexOfFirst { node ->
            node is ParameterNode.Group && (canonicalRank(node.id)?.let { it > rank } == true)
        }
        return if (index < 0) tree + group else tree.toMutableList().apply { add(index, group) }
    }

    /** Append (or prepend) unplaced [addedIds] into an existing [folderId] anywhere in [tree], or insert a new folder in canonical order. */
    fun appendToFolder(
        tree: List<ParameterNode>,
        existingParameterIds: List<String>,
        addedIds: List<String>,
        folderId: String,
        folderName: String,
        prependChildren: Boolean = false,
    ): List<ParameterNode> {
        if (addedIds.isEmpty()) return tree
        val addedSet = addedIds.toHashSet()
        val base = tree.ifEmpty { existingParameterIds.filter { it !in addedSet }.map { ParameterNode.Param(it) } }
        val alreadyPlaced = HashSet<String>()
        fun collectPlaced(nodes: List<ParameterNode>) {
            for (node in nodes) when (node) {
                is ParameterNode.Param -> alreadyPlaced += node.id
                is ParameterNode.Group -> collectPlaced(node.children)
            }
        }
        collectPlaced(base)
        val toPlace = addedIds.distinct().filter { it !in alreadyPlaced }.map { ParameterNode.Param(it) }
        if (toPlace.isEmpty()) return tree
        var found = false
        fun update(nodes: List<ParameterNode>): List<ParameterNode> = nodes.map { node ->
            if (node !is ParameterNode.Group) node
            else if (node.id == folderId) {
                found = true
                node.copy(children = if (prependChildren) toPlace + node.children else node.children + toPlace)
            } else {
                val children = update(node.children)
                if (children === node.children) node else node.copy(children = children)
            }
        }
        val updated = update(base)
        return if (found) updated else insertGroup(updated, ParameterNode.Group(folderId, folderName, open = true, children = toPlace))
    }
}

/** Optional host-supplied localization overrides for plugin-declared parameters and groups. */
@Serializable
data class ModifierParameterI18n(
    val groupNames: Map<String, String> = emptyMap(),
    val parameterNames: Map<String, String> = emptyMap(),
) {
    fun resolveName(id: String, defaultName: String): String =
        parameterNames[id]?.takeIf { it.isNotBlank() } ?: defaultName

    fun resolveGroup(groupKeyOrDefault: String): String =
        groupNames[groupKeyOrDefault]?.takeIf { it.isNotBlank() } ?: groupKeyOrDefault

    fun toJsonElement(): JsonElement = Json.encodeToJsonElement(this)

    companion object {
        const val CONTEXT_KEY = "parameter_i18n"
        val Empty = ModifierParameterI18n()

        fun fromContext(context: ModifierAlgorithmContext): ModifierParameterI18n =
            context.values[CONTEXT_KEY]?.let { Json.decodeFromJsonElement<ModifierParameterI18n>(it) } ?: Empty
    }
}
