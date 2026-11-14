package example

import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.algorithms.binding.ScaffoldParts
import io.github.psd2live.algorithms.binding.ScaffoldPartsContext
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.runtime.model.*
import kotlin.test.*

class ScaffoldAlgorithmOrganizationTest {
    @Test fun capturedSourceOrganizationMatchesTheLegacyTemplateForHistoricalAndSharedIds() {
        val first = drawable("source-face")
        val second = drawable("untargeted-face")
        val other = drawable("untargeted-body")
        val copied = first.copy(id = DrawableId("copied-face"))
        val sourceDrawables = listOf(first, second, other)
        val materialDrawables = listOf(second, copied, other)
        val classifications = listOf(
            ScaffoldParts.Material(first.id, ScaffoldParts.Slot.FACE, 3),
            ScaffoldParts.Material(second.id, ScaffoldParts.Slot.FACE, 1),
            ScaffoldParts.Material(other.id, ScaffoldParts.Slot.BODY, 0),
        )
        val label = { key: String -> "part ${key.substringAfterLast('.')}" }
        val tree = ScaffoldParts.build(classifications, label)
        val frame = DeformerId("source-warp")
        val lattice = WarpLatticeForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        val template = PuppetModel(emptyList(), tree.parts, listOf(
            Deformer.Warp(frame, "source warp", null, ScaffoldParts.Slot.FACE.id, 1, 1, true,
                KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), lattice)))),
        ), sourceDrawables.map { it.copy(parentDeformerId = frame) }, tree.rootChildren, null)
        val material = PuppetModel(emptyList(), emptyList(), emptyList(), materialDrawables,
            materialDrawables.map { OrgChild.Drawable(it.id) }, null)
        val targets = mapOf(copied.id.raw to first.id.raw)
        val legacy = ScaffoldAlgorithm.capture(template, material, targets)
        val direct = ScaffoldAlgorithm.capture(template, material, targets,
            organization = ScaffoldPartsContext.capture(classifications, label))
        assertEquals("scaffold/1", legacy.values.getValue("schema").jsonPrimitive.content)
        assertEquals("scaffold/2", direct.values.getValue("schema").jsonPrimitive.content)
        val oldFrames = RigIrBinary.decode(legacy.resources.getValue("template").read())
        assertTrue(oldFrames.parts.isNotEmpty())
        assertTrue(oldFrames.rootChildren.isNotEmpty())
        val newFrames = RigIrBinary.decode(direct.resources.getValue("template").read())
        assertTrue(newFrames.parts.isEmpty())
        assertTrue(newFrames.rootChildren.isEmpty())
        assertNull(newFrames.rootPart)

        val algorithm = ScaffoldAlgorithm()
        val input = PuppetIr.toIr(material)
        for (version in 1..2) {
            val aliases = if (version == 1) emptyMap() else mapOf(
                "shared/deformer:${frame.raw}" to "kept-warp",
                "shared/part:PartHead" to "kept-head",
                "shared/part:PartFace" to "kept-face",
            )
            val instance = RigModifierInstance("structure", "rig.scaffold", version,
                listOf(ModifierTarget("mesh", copied.id.raw)), outputAliases = aliases)
            val expected = algorithm.evaluate(input, instance, legacy) {}
            val result = algorithm.evaluate(input, instance, direct) {}
            assertEquals(expected, result)
            val puppet = PuppetIr.toPuppet(result)
            assertNull(puppet.drawables.single { it.id == second.id }.parentDeformerId)
            assertNull(puppet.drawables.single { it.id == other.id }.parentDeformerId)
            if (version == 2) {
                assertEquals("kept-warp", puppet.drawables.single { it.id == copied.id }.parentDeformerId?.raw)
                val face = puppet.parts.single { it.id.raw == "kept-face" }
                assertEquals(listOf(OrgChild.Drawable(copied.id)), face.children)
                assertTrue(OrgChild.Part(face.id) in puppet.parts.single { it.id.raw == "kept-head" }.children)
            }
        }
    }

    private fun drawable(id: String) = Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(),
        DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), FloatArray(6), intArrayOf(0, 1, 2)), null)
}
