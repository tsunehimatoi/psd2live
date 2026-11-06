package example

import io.github.psd2live.algorithms.binding.Bounds
import io.github.psd2live.algorithms.binding.MouthFit
import io.github.psd2live.algorithms.binding.MouthFittedAlgorithm
import io.github.psd2live.algorithms.binding.MouthFittedInputs
import io.github.psd2live.algorithms.binding.MouthOptions
import io.github.psd2live.algorithms.binding.MouthRig
import io.github.psd2live.algorithms.binding.MouthSourceContext
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MouthFittedAlgorithmTest {
    @Test
    fun workerBuildsGeometryVisibilityMasksAndHeadTurnFromSource() {
        val frame = Bounds(0f, 0f, 100f, 100f)
        val geometry = MeshGeometry(Floats.of(floatArrayOf(0.2f, 0.45f, 0.5f, 0.65f, 0.8f, 0.45f)),
            Floats.of(floatArrayOf(0f, 0f, 0.5f, 1f, 1f, 0f)), Ints.of(intArrayOf(0, 1, 2)))
        val meshes = listOf("open", "closed", "teeth", "untouched").map { Mesh(it, it, null, geometry = geometry, offsets = null) }
        val input = RigIR(Canvas(100f, 100f), listOf(
            Parameter("ParamMouthForm", "Form", -1f, 1f, 0f),
            Parameter("ParamMouthOpenY", "Open", 0f, 1f, 0f),
            Parameter("ParamAngleX", "Yaw", -30f, 30f, 0f),
            Parameter("ParamAngleY", "Pitch", -30f, 30f, 0f),
        ), meshes = meshes)
        val upper = List(MouthFit.SAMPLES) { i -> val u = MouthFit.u(i); -0.8f * u * (1f - u) }
        val lower = List(MouthFit.SAMPLES) { i -> val u = MouthFit.u(i); 1.2f * u * (1f - u) }
        val measurement = MouthSourceContext(MouthFit(20f, 50f, 1f, 0f, 60f, upper, lower), MouthOptions(reach = 1.2f), null)
        fun layer(tag: String) = MouthFittedInputs.Layer(tag, null, "NONE", null, frame, frame, 50f, 50f,
            opaque = true, clipped = false, preset = true, turnsWithHeadMesh = true)
        val context = MouthFittedInputs(measurement, 0.6f, null, closedMouth = true, separateInside = false, teethFade = 20f,
            layers = linkedMapOf("open" to layer("MOUTH_OPEN"), "closed" to layer("MOUTH_CLOSE"), "teeth" to layer("TOOTH_T")),
            maskSources = listOf("open"), head = MouthFittedInputs.Head("head", 50f, 50f, 50f, 50f, 0.45f, 1f, 100f))
        val targets = listOf("open", "closed", "teeth").map { ModifierTarget("mesh", it) }
        val result = MouthFittedAlgorithm().evaluate(input, RigModifierInstance("mouth", "mouth/fitted", 1, targets), context.encode()) {}
        val open = result.meshes.single { it.id == "open" }
        val closed = result.meshes.single { it.id == "closed" }
        val teeth = result.meshes.single { it.id == "teeth" }
        assertEquals(1.2f, result.parameters.single { it.id == "ParamMouthOpenY" }.max)
        assertEquals(listOf("open"), teeth.maskedBy)
        assertNotNull(open.offsets)
        assertTrue(open.shapes.any { it.parameter == "ParamAngleX" } ||
            result.deformers.isNotEmpty() || open.offsets!!.axes.any { it.parameter == "ParamAngleX" })
        assertEquals(listOf(0f, MouthRig.SWAP, 1f), closed.channels.getValue(Channel.OPACITY).axes.single().keys.toArray().toList())
        assertEquals(meshes.last(), result.meshes.single { it.id == "untouched" })
    }
}
