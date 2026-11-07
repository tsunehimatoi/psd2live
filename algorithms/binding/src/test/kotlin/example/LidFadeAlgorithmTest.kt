package example

import io.github.psd2live.algorithms.binding.LidFadeAlgorithm
import io.github.psd2live.algorithms.binding.LidFadeInputs
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class LidFadeAlgorithmTest {
    private val open = "ParamEyeLOpen"
    private val targets = listOf(ModifierTarget("mesh", "lid"), ModifierTarget("mesh", "other"))
    private val instance = RigModifierInstance("fade", LidFadeAlgorithm.TYPE, 2, targets,
        config = buildJsonObject { put("scaffold", "structure"); put("base", "eye") })
    private val scaffold = ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 1, targets, "structure")
    private val base = ModifierUpstreamCapability("eye/fitted", "eye/fitted", 2, targets, "eye")
    private val wide = ModifierUpstreamCapability("eye.wide", "eye.wide", 1, targets, "wide", instance.config)

    @Test fun opacityResidualKeepsMasksOtherChannelsAndAuthoredKeys() {
        val expression = "ParamExpression"
        val authored = KeyGrid(listOf(KeyAxis(expression, Floats.values(0f, 1f))), listOf(
            KeyCell<ChannelValue>(Ints.values(0), ChannelValue.Scalar(.6f)),
            KeyCell<ChannelValue>(Ints.values(1), ChannelValue.Scalar(.8f))))
        val order = KeyGrid.single<ChannelValue>(ChannelValue.Scalar(120f))
        val mesh = Mesh("lid", "lid", null, geometry = null, offsets = null, maskedBy = listOf("other"),
            channels = mapOf(Channel.OPACITY to authored, Channel.DRAW_ORDER to order), opacity = .6f)
        val other = Mesh("other", "other", null, geometry = null, offsets = null)
        val input = RigIR(Canvas(100f, 100f), listOf(Parameter(open, open, 0f, 2f, 1f),
            Parameter(expression, expression, 0f, 1f, 0f)), meshes = listOf(mesh, other))
        val context = LidFadeInputs.encode(mapOf("lid" to LidFadeInputs(open, .2f, .5f))).withUpstream(listOf(scaffold, base, wide))
        val result = LidFadeAlgorithm().changes(ModifierEvaluationInput(input, context), instance) {}.apply(input)
        val edited = result.meshes.first()
        assertSame(other, result.meshes.last())
        assertEquals(mesh, edited.copy(channels = mesh.channels))
        assertSame(order, edited.channels.getValue(Channel.DRAW_ORDER))
        val opacity = edited.channels.getValue(Channel.OPACITY)
        assertEquals(listOf(expression, open), opacity.axes.map { it.parameter })
        assertEquals(Floats.values(0f, .2f + .15f, .75f, 1f, 2f), opacity.axes.last().keys)
        assertEquals(.1f, (opacity.cells.first().form as ChannelValue.Scalar).value, .00001f)
        assertEquals(1.3f, (opacity.cells.last().form as ChannelValue.Scalar).value, .00001f)
    }

    @Test fun requiresAFittedEyeProvider() {
        val context = LidFadeInputs.encode(emptyMap()).withUpstream(listOf(scaffold))
        assertFailsWith<IllegalArgumentException> {
            LidFadeAlgorithm().changes(ModifierEvaluationInput(RigIR(Canvas(100f, 100f), emptyList()), context), instance) {}
        }
    }
}
