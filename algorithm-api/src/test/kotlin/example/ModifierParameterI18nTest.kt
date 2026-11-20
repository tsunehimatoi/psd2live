package example

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierParameterGroups
import io.github.psd2live.core.modifier.ModifierParameterI18n
import io.github.psd2live.core.modifier.ModifierPluginAlgorithm
import io.github.psd2live.core.modifier.ModifierPluginParameter
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.GroupLabel
import io.github.psd2live.format.model.ParameterNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModifierParameterI18nTest {
    @Test
    fun `self-declared parameter falls back to plugin defaults and honors host overrides`() {
        val param = ModifierPluginParameter(
            id = "ParamEyeLSmile",
            name = "Left Eye Smile",
            min = 0f,
            max = 1f,
            default = 0f,
            group = ModifierParameterGroups.EYES,
            blend = true,
            repeat = false,
        )

        val fallback = param.definition()
        assertEquals("Left Eye Smile", fallback.name)
        assertEquals(true, fallback.blend)
        assertEquals(ModifierParameterGroups.EYES, param.resolvedGroup())

        val i18n = ModifierParameterI18n(
            groupNames = mapOf(ModifierParameterGroups.EYES to "眼睛"),
            parameterNames = mapOf("ParamEyeLSmile" to "左眼笑"),
        )
        val context = ModifierAlgorithmContext(
            values = buildJsonObject { put(ModifierParameterI18n.CONTEXT_KEY, i18n.toJsonElement()) },
        )
        val decoded = ModifierParameterI18n.fromContext(context)
        assertEquals("左眼笑", param.definition(decoded).name)
        assertEquals("眼睛", param.resolvedGroup(decoded))
    }

    @Test
    fun `manifest algorithm deserializes creates and modifies`() {
        val raw = """
            {
              "type": "example/eye",
              "name": "Eye",
              "writes": ["parameter", "deformer", "mesh"],
              "creates": ["parameters", "warpDeformers"],
              "modifies": ["mesh.offsets", "mesh.opacity"],
              "configSchema": {"type": "object"},
              "defaults": {}
            }
        """.trimIndent()
        val algorithm = Json.decodeFromString(ModifierPluginAlgorithm.serializer(), raw)
        assertEquals(setOf("parameters", "warpDeformers"), algorithm.creates)
        assertEquals(setOf("mesh.offsets", "mesh.opacity"), algorithm.modifies)
    }

    @Test
    fun `resolveId returns standard id by default and honors parameter-folder alias`() {
        val target = listOf(ModifierTarget("mesh", "body"))
        val plain = RigModifierInstance("m1", "brow/fitted", 1, target)
        assertEquals(ModifierParameterGroups.BROWS, ModifierParameterGroups.resolveId(plain, ModifierParameterGroups.BROWS))

        val aliased = RigModifierInstance(
            "m2", "brow/fitted", 1, target,
            outputAliases = mapOf("parameter-folder:${ModifierParameterGroups.BROWS}" to "legacy-brows"),
        )
        assertEquals("legacy-brows", ModifierParameterGroups.resolveId(aliased, ModifierParameterGroups.BROWS))
    }

    @Test
    fun `canonicalRank and insertGroup place standard groups in canonical order`() {
        assertEquals(0, ModifierParameterGroups.canonicalRank(ModifierParameterGroups.FACE))
        assertEquals(1, ModifierParameterGroups.canonicalRank(ModifierParameterGroups.EYES))
        assertEquals(6, ModifierParameterGroups.canonicalRank(ModifierParameterGroups.PHYSICS))
        assertEquals(8, ModifierParameterGroups.canonicalRank(ModifierParameterGroups.CUSTOM))
        assertEquals(9, ModifierParameterGroups.canonicalRank(ModifierParameterGroups.SKELETON))
        assertNull(ModifierParameterGroups.canonicalRank("UserGroup"))

        var tree: List<ParameterNode> = listOf(ParameterNode.Param("RootParam"))
        tree = ModifierParameterGroups.insertGroup(tree, ParameterNode.Group(ModifierParameterGroups.PHYSICS, "Physics", true, emptyList()))
        tree = ModifierParameterGroups.insertGroup(tree, ParameterNode.Group(ModifierParameterGroups.CUSTOM, "Custom", true, emptyList()))
        tree = ModifierParameterGroups.insertGroup(tree, ParameterNode.Group("UserGroup", "User", true, emptyList()))
        tree = ModifierParameterGroups.insertGroup(tree, ParameterNode.Group(ModifierParameterGroups.MOUTH, "Mouth", true, emptyList()))
        tree = ModifierParameterGroups.insertGroup(tree, ParameterNode.Group(ModifierParameterGroups.FACE, "Face", true, emptyList()))
        tree = ModifierParameterGroups.insertGroup(tree, ParameterNode.Group(ModifierParameterGroups.EYES, "Eyes", true, emptyList()))
        tree = ModifierParameterGroups.insertGroup(tree, ParameterNode.Group(ModifierParameterGroups.BROWS, "Brows", true, emptyList()))
        tree = ModifierParameterGroups.insertGroup(tree, ParameterNode.Group(ModifierParameterGroups.BODY, "Body", true, emptyList()))

        assertEquals(
            listOf(
                ParameterNode.Param("RootParam"),
                ParameterNode.Group(ModifierParameterGroups.FACE, "Face", true, emptyList()),
                ParameterNode.Group(ModifierParameterGroups.EYES, "Eyes", true, emptyList()),
                ParameterNode.Group(ModifierParameterGroups.BROWS, "Brows", true, emptyList()),
                ParameterNode.Group(ModifierParameterGroups.MOUTH, "Mouth", true, emptyList()),
                ParameterNode.Group(ModifierParameterGroups.BODY, "Body", true, emptyList()),
                ParameterNode.Group(ModifierParameterGroups.PHYSICS, "Physics", true, emptyList()),
                ParameterNode.Group(ModifierParameterGroups.CUSTOM, "Custom", true, emptyList()),
                ParameterNode.Group("UserGroup", "User", true, emptyList()),
            ),
            tree,
        )
    }

    @Test
    fun `appendToFolder deduplicates, updates nested groups preserving metadata, prepends, and skips placed params`() {
        val nested = ParameterNode.Group(
            id = "UserOuter",
            name = "Outer",
            open = false,
            children = listOf(
                ParameterNode.Group(
                    id = ModifierParameterGroups.FACE,
                    name = "Custom Face Label",
                    open = false,
                    children = listOf(ParameterNode.Param("ParamCheek")),
                    label = GroupLabel.Preset("RED"),
                ),
            ),
        )
        val initial = listOf<ParameterNode>(nested, ParameterNode.Param("ParamPlacedAtRoot"))

        val updated = ModifierParameterGroups.appendToFolder(
            tree = initial,
            existingParameterIds = listOf("ParamCheek", "ParamPlacedAtRoot"),
            addedIds = listOf("ParamAngleX", "ParamAngleY", "ParamPlacedAtRoot", "ParamAngleX"),
            folderId = ModifierParameterGroups.FACE,
            folderName = "Face",
            prependChildren = true,
        )

        assertEquals(2, updated.size)
        val updatedOuter = updated[0] as ParameterNode.Group
        val updatedFace = updatedOuter.children.single() as ParameterNode.Group
        assertEquals("Custom Face Label", updatedFace.name)
        assertEquals(false, updatedFace.open)
        assertEquals(GroupLabel.Preset("RED"), updatedFace.label)
        assertEquals(
            listOf(ParameterNode.Param("ParamAngleX"), ParameterNode.Param("ParamAngleY"), ParameterNode.Param("ParamCheek")),
            updatedFace.children,
        )

        val noOp = ModifierParameterGroups.appendToFolder(
            tree = updated,
            existingParameterIds = emptyList(),
            addedIds = listOf("ParamCheek", "ParamPlacedAtRoot"),
            folderId = ModifierParameterGroups.FACE,
            folderName = "Face",
        )
        assertEquals(updated, noOp)

        val materialized = ModifierParameterGroups.appendToFolder(
            tree = emptyList(),
            existingParameterIds = listOf("ParamExisting", "ParamNew"),
            addedIds = listOf("ParamNew"),
            folderId = ModifierParameterGroups.EYES,
            folderName = "Eyes",
        )
        assertEquals(
            listOf(
                ParameterNode.Param("ParamExisting"),
                ParameterNode.Group(ModifierParameterGroups.EYES, "Eyes", true, listOf(ParameterNode.Param("ParamNew"))),
            ),
            materialized,
        )
    }
}
