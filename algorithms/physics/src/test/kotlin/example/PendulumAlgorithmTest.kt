package example

import io.github.psd2live.algorithms.physics.PendulumAlgorithm
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.RigIR
import kotlin.test.*

class PendulumAlgorithmTest {
    private val input = RigIR(Canvas(100f, 100f), listOf(
        Parameter("in", "Input", -1f, 1f, 0f), Parameter("out", "Output", -1f, 1f, 0f)))
    private val instance = RigModifierInstance("p1", "physics", 1, listOf(ModifierTarget("mesh", "mesh1")),
        config = RigPhysicsEdit("authored", "Pendulum", listOf(PhysicsInput("in")), listOf(PhysicsOutput("out"))).toJson())

    @Test fun evaluatesIndependentlyAndRejectsCollisionsWithoutMutatingInput() {
        val algorithm = PendulumAlgorithm()
        val output = algorithm.evaluate(input, instance) {}
        val group = output.physics.groups.single()
        assertEquals(instance.outputId("physics"), group.id)
        assertEquals("in", group.inputs.single().parameter)
        assertEquals("out", group.outputs.single().parameter)
        assertTrue(input.physics.groups.isEmpty())
        assertFailsWith<IllegalArgumentException> { algorithm.evaluate(output, instance) {} }
        assertFailsWith<IllegalArgumentException> { algorithm.evaluate(output, instance.copy(id = "p2")) {} }
        assertEquals(1, output.physics.groups.size)
    }

    @Test fun missingParametersAndCancellationDoNotPublishOutput() {
        val algorithm = PendulumAlgorithm()
        assertFailsWith<IllegalArgumentException> { algorithm.evaluate(input.copy(parameters = emptyList()), instance) {} }
        assertFailsWith<java.util.concurrent.CancellationException> {
            algorithm.evaluate(input, instance) { throw java.util.concurrent.CancellationException() }
        }
        assertTrue(input.physics.groups.isEmpty())
    }
    @Test fun reportsOnlyItsPhysicsChangeAndPreservesOtherSections() {
        val algorithm = PendulumAlgorithm()
        val changes = algorithm.changes(input, instance, ModifierAlgorithmContext.Empty) {}
        val output = changes.apply(input)
        assertTrue(changes.parameters.isEmpty)
        assertTrue(changes.meshes.isEmpty)
        assertEquals(listOf(ModifierObjectContribution("physics", instance.outputId("physics"), ModifierChangeAction.ADD)),
            changes.contributions())
        assertEquals(algorithm.evaluate(input, instance) {}, output)
        assertSame(input.parameters, output.parameters)
        assertSame(input.meshes, output.meshes)
    }
}
