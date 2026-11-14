package example

import io.github.psd2live.algorithms.binding.ExpressionAlgorithm
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.*
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.ChannelValue
import kotlin.test.*

class ExpressionAlgorithmTest {
    @Test fun isolatedPluginPreservesAuthoredOpacityAndUntargetedMeshes() {
        val algorithm = ExpressionAlgorithm()
        val source = ModifierAlgorithmContext(buildJsonObject {
            put("schema", "expression/1")
            putJsonObject("layers") { putJsonObject("layer") { put("parameter", "Expression") } }
            putJsonObject("meshes") { put("body", "layer"); put("copy", "layer") }
            putJsonObject("opacity") { put("layer", .6f) }
            put("chain_owners", JsonArray(emptyList()))
        }, mapOf("parameters" to ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(1f, 1f),
            listOf(Parameter("Expression", "Expression", 0f, 1f, 0f)))))))
        val body = Mesh("body", "Body", null, geometry = null, offsets = null, opacity = .8f)
        val rig = RigIR(Canvas(1f, 1f), emptyList(), meshes = listOf(body, body.copy(id = "copy")))
        val instance = RigModifierInstance("m1", "expression.toggles", 1, listOf(ModifierTarget("mesh", "body")))
        assertFailsWith<IllegalArgumentException> { algorithm.evaluate(rig, instance) {} }
        val result = algorithm.evaluate(rig, instance, source) {}
        assertEquals("Expression", result.parameters.single().id)
        assertEquals(rig.meshes.last(), result.meshes.last())
        val grid = PuppetIr.toPuppet(result).drawables.first().channelGrids[FormChannel.OPACITY]!!
        assertEquals(2, grid.cells.size)
        assertEquals(.2f, (grid.cells[0].form as ChannelValue.Scalar).value, .00001f)
        assertEquals(1.2f, (grid.cells[1].form as ChannelValue.Scalar).value, .00001f)
        assertFailsWith<java.util.concurrent.CancellationException> {
            algorithm.evaluate(rig, instance, source) { throw java.util.concurrent.CancellationException() }
        }
    }
}
