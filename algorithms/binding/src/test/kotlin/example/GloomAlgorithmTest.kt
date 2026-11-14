package example

import io.github.psd2live.algorithms.binding.GloomAlgorithm
import io.github.psd2live.algorithms.binding.GloomInputs
import io.github.psd2live.algorithms.binding.MoodBinding
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class GloomAlgorithmTest {
    private val canvas = Canvas(10f, 10f)
    private val face = Mesh("face", "Face", null, geometry = null, offsets = null)
    private val light = face.copy(id = "light")
    private val other = face.copy(id = "other")
    private val gloom = Parameter("ParamGloom", "Gloom", 0f, 1f, 0f)
    private val cheek = Parameter("ParamCheek", "Cheek", 0f, 1f, 0f)
    private val instance = RigModifierInstance("gloom", "expression.gloom", 1,
        listOf(ModifierTarget("mesh", face.id), ModifierTarget("mesh", light.id)),
        outputAliases = mapOf("parameter-folder:ParamGroupFace" to "face-parameters"))

    private fun context(withCheek: Boolean, highlight: Mesh = light) = GloomInputs(true, "Gloom", "Face",
        mapOf(face.id to GloomInputs.Layer(true, false, .06f, if (withCheek) 100f else null),
            light.id to GloomInputs.Layer(false, true, 0f, null)),
        listOf(face, highlight).associateBy { it.id }).context()

    @Test fun channelsMatchLegacyMoodWithAndWithoutCheek() {
        for (withCheek in listOf(false, true)) {
            val definitions = if (withCheek) listOf(cheek, gloom) else listOf(gloom)
            val raw = RigIR(canvas, definitions.dropLast(1), meshes = listOf(face, light, other))
            val source = PuppetIr.toPuppet(raw)
            val influences = mapOf(source.drawables[0].id to MoodBinding.Influence(.06f, 1f, false),
                source.drawables[1].id to MoodBinding.Influence(0f, 0f, true))
            val binding = MoodBinding({ _, _ -> error("Neutral fixture has no carriers") })
            val legacy = binding.evaluate(source, PuppetIr.toPuppet(RigIR(canvas, definitions)).parameters,
                definitions.withIndex().associate { ParameterId(it.value.id) to it.index }, emptySet(), influences, withCheek, true)
            val prior = binding.evaluate(source, source.parameters, emptyMap(), emptySet(), influences, withCheek, false)
            val input = raw.copy(meshes = prior.model.drawables.map(PuppetIr::meshToIr))
            val changes = GloomAlgorithm().changes(input, instance, context(withCheek)) {}
            assertEquals(setOf("parameter", "hierarchy", "mesh"), changes.writeKinds())
            val actual = changes.apply(input)
            assertEquals(legacy.model.drawables.map(PuppetIr::meshToIr).map { it.channels }, actual.meshes.map { it.channels })
            assertEquals(definitions, actual.parameters)
            assertEquals("face-parameters", (actual.parameterTree.last() as ParameterNode.Group).id)
            assertSame(input.meshes.last(), actual.meshes.last())
        }
    }

    @Test fun keepsSourceOwnedOpacityAndAppliesOnlyGloomDeltaToAuthoredColor() {
        val authored = face.copy(multiply = Rgb(.8f, .9f, .95f))
        val authoredLight = light.copy(channels = mapOf(Channel.OPACITY to
            KeyGrid(emptyList(), listOf(KeyCell(Ints.values(), ChannelValue.Scalar(.6f))))))
        val shared = gloom.copy(name = "Authored gloom")
        val input = RigIR(canvas, listOf(shared), meshes = listOf(authored, authoredLight))
        val changes = GloomAlgorithm().changes(input, instance, context(false, authoredLight)) {}
        val result = changes.apply(input)
        assertEquals(listOf(shared), changes.parameters.reused)
        assertSame(shared, result.parameters.single())
        assertSame(authoredLight, result.meshes[1])
        val grid = result.meshes.first().channels.getValue(Channel.MULTIPLY_COLOR)
        val on = (grid.cells.last().form as ChannelValue.Color).color
        assertEquals(.67f, on.red, .00001f)
        assertEquals(.77f, on.green, .00001f)
        assertEquals(.872f, on.blue, .00001f)
        assertFailsWith<IllegalArgumentException> {
            changes.apply(input.copy(meshes = listOf(authored.copy(opacity = .5f), authoredLight)))
        }
    }

    @Test fun replacesEarlierGloomOnTheSameTarget() {
        val input = RigIR(canvas, emptyList(), meshes = listOf(face, light))
        val algorithm = GloomAlgorithm()
        val prior = algorithm.evaluate(input, instance, context(false)) {}
        val nextContext = GloomInputs(true, "Gloom", "Face",
            mapOf(face.id to GloomInputs.Layer(true, false, .06f, null, 100f),
                light.id to GloomInputs.Layer(false, true, 0f, null, 100f)),
            listOf(face, light).associateBy { it.id }).context()
        val nextInstance = instance.copy(id = "gloom-next", config = kotlinx.serialization.json.buildJsonObject {
            put("values", kotlinx.serialization.json.JsonObject(mapOf("strength" to kotlinx.serialization.json.JsonPrimitive(50f))))
        })
        val expected = algorithm.evaluate(input, nextInstance, context(false)) {}
        val actual = algorithm.evaluate(prior, nextInstance, nextContext) {}
        assertEquals(expected.meshes, actual.meshes)
        assertEquals(prior.parameterTree, actual.parameterTree)
        assertSame(prior.meshes[1], actual.meshes[1])
    }
}
