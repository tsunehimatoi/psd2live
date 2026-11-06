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
        val untargeted = overlay.copy(id = "untargeted")
        val rig = RigIR(Canvas(10f, 10f), emptyList(),
            meshes = listOf(face, overlay, overlay.copy(id = "outside"), untargeted))
        fun context(prior: Boolean = false) = ModifierAlgorithmContext(buildJsonObject {
            put("schema", "skin-clip/1")
            putJsonArray("faces") { addJsonObject {
                put("id", "face"); put("bounds", JsonArray(listOf(0f, 0f, 10f, 10f).map(::JsonPrimitive)))
            } }
            putJsonObject("overlays") {
                put("overlay", JsonArray(listOf(0f, 0f, 10f, 10f).map(::JsonPrimitive)))
                put("outside", JsonArray(listOf(0f, 0f, 20f, 10f).map(::JsonPrimitive)))
                put("untargeted", JsonArray(listOf(0f, 0f, 10f, 10f).map(::JsonPrimitive)))
            }
            put("already_active", JsonArray(if (prior) listOf(JsonPrimitive("overlay")) else emptyList()))
        })
        val instance = RigModifierInstance("skin", "face.skin-clip", 1, listOf(ModifierTarget("mesh", "overlay"), ModifierTarget("mesh", "outside")))
        val plugin = SkinClipAlgorithm()
        val input = ModifierEvaluationInput(rig, context())
        val changes = plugin.changes(input, instance) {}
        val result = changes.apply(rig)
        assertEquals(setOf("mesh"), changes.writeKinds())
        assertEquals(listOf(ModifierObjectUpdate(overlay, result.meshes[1])), changes.meshes.updated)
        assertEquals(setOf(ModifierReadKey("context"), ModifierReadKey("meshIds"),
            ModifierReadKey("mesh", "overlay"), ModifierReadKey("mesh", "outside")), input.reads().keys)
        assertEquals(listOf("face"), result.meshes[1].maskedBy)
        assertSame(rig.meshes[0], result.meshes[0])
        assertSame(rig.meshes[2], result.meshes[2])
        assertSame(untargeted, result.meshes[3])
        val unrelatedEdit = rig.copy(meshes = rig.meshes.map {
            if (it.id == untargeted.id) it.copy(opacity = .5f) else it
        })
        assertTrue(input.reads().matches(unrelatedEdit, context()))
        assertSame(unrelatedEdit.meshes[3], changes.apply(unrelatedEdit).meshes[3])
        assertSame(result, plugin.evaluate(result, instance, context()) {})
        val authored = rig.copy(meshes = rig.meshes.map {
            if (it.id == overlay.id) it.copy(invertMask = true) else it
        })
        assertFalse(input.reads().matches(authored, context()))
        assertFailsWith<IllegalArgumentException> { changes.apply(authored) }
        assertFailsWith<IllegalArgumentException> { plugin.evaluate(authored, instance, context()) {} }
        assertSame(authored, plugin.evaluate(authored, instance, context(prior = true)) {})
        val conflictingMask = rig.copy(meshes = rig.meshes.map {
            if (it.id == overlay.id) it.copy(maskedBy = listOf(untargeted.id)) else it
        })
        assertFailsWith<IllegalArgumentException> { plugin.evaluate(conflictingMask, instance, context()) {} }
        val missingFace = rig.copy(meshes = rig.meshes.drop(1))
        assertFalse(input.reads().matches(missingFace, context()))
        assertFailsWith<IllegalArgumentException> { plugin.evaluate(missingFace, instance, context()) {} }
        assertFailsWith<java.util.concurrent.CancellationException> {
            plugin.evaluate(rig, instance, context()) { throw java.util.concurrent.CancellationException() }
        }
    }
}
