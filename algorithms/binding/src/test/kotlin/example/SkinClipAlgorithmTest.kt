package example

import io.github.psd2live.algorithms.binding.SkinClipAlgorithm
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SkinClipAlgorithmTest {
    @Test fun clipsOnlyEligibleCapturedTargetsAndRejectsAuthoredConflicts() {
        val face = Mesh("face", "Face", null, geometry = null, offsets = null)
        val overlay = face.copy(id = "overlay")
        val rig = RigIR(Canvas(10f, 10f), emptyList(), meshes = listOf(face, overlay, overlay.copy(id = "outside")))
        fun context(prior: Boolean = false) = ModifierAlgorithmContext(buildJsonObject {
            put("schema", "skin-clip/1")
            putJsonArray("faces") { addJsonObject {
                put("id", "face"); put("bounds", JsonArray(listOf(0f, 0f, 10f, 10f).map(::JsonPrimitive)))
            } }
            putJsonObject("overlays") {
                put("overlay", JsonArray(listOf(0f, 0f, 10f, 10f).map(::JsonPrimitive)))
                put("outside", JsonArray(listOf(0f, 0f, 20f, 10f).map(::JsonPrimitive)))
            }
            put("already_active", JsonArray(if (prior) listOf(JsonPrimitive("overlay")) else emptyList()))
        })
        val instance = RigModifierInstance("skin", "face.skin-clip", 1, listOf(ModifierTarget("mesh", "overlay"), ModifierTarget("mesh", "outside")))
        val plugin = SkinClipAlgorithm()
        val result = plugin.evaluate(rig, instance, context()) {}
        assertEquals(listOf("face"), result.meshes[1].maskedBy)
        assertSame(rig.meshes[0], result.meshes[0])
        assertSame(rig.meshes[2], result.meshes[2])
        assertSame(result, plugin.evaluate(result, instance, context()) {})
        val authored = rig.copy(meshes = listOf(face, overlay.copy(invertMask = true), rig.meshes[2]))
        assertFailsWith<IllegalArgumentException> { plugin.evaluate(authored, instance, context()) {} }
        assertSame(authored, plugin.evaluate(authored, instance, context(prior = true)) {})
        assertFailsWith<java.util.concurrent.CancellationException> {
            plugin.evaluate(rig, instance, context()) { throw java.util.concurrent.CancellationException() }
        }
    }
}
