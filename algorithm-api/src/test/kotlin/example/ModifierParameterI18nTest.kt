package example

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierParameterGroups
import io.github.psd2live.core.modifier.ModifierParameterI18n
import io.github.psd2live.core.modifier.ModifierPluginAlgorithm
import io.github.psd2live.core.modifier.ModifierPluginParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
