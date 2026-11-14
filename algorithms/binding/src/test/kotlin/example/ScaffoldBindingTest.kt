package example

import io.github.psd2live.algorithms.binding.ScaffoldBinding
import io.github.psd2live.algorithms.binding.ScaffoldAlgorithm
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierAlgorithmResource
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import org.umamo.runtime.model.*
import kotlin.test.*

class ScaffoldBindingTest {
    @Test fun sharedAndHistoricalHierarchiesKeepDeterministicIdentities() {
        val warpId = DeformerId("frame")
        val partId = PartId("body")
        val meshes = listOf("a", "b").map { id ->
            Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(),
                DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), FloatArray(6), intArrayOf(0, 1, 2)), null)
        }
        val material = PuppetModel(emptyList(), emptyList(), emptyList(), meshes, meshes.map { OrgChild.Drawable(it.id) }, null)
        val template = material.copy(parts = listOf(Part(partId, "body", meshes.map { OrgChild.Drawable(it.id) })),
            deformers = listOf(Deformer.Warp(warpId, "frame", null, partId, 1, 1, true,
                KeyformGrid(emptyList(), listOf(KeyformCell(IntArray(0), WarpLatticeForm(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))))))),
            drawables = meshes.map { it.copy(parentDeformerId = warpId) }, rootChildren = listOf(OrgChild.Part(partId)))
        val source = ScaffoldBinding.CapturedSource(meshes.associate { it.id.raw to template }, meshes)
        assertTrue(source.originalMaterial(meshes.first(), template.drawables.first()))
        assertFalse(source.originalMaterial(meshes.first().copy(parentDeformerId = warpId), template.drawables.first()))
        for (version in 1..2) {
            val instance = RigModifierInstance("structure", "rig.scaffold", version, meshes.map { ModifierTarget("mesh", it.id.raw) })
            val output = ScaffoldBinding.apply(material, instance, source) {}
            assertEquals(if (version == 2) 1 else 2, output.deformers.size)
            assertEquals(if (version == 2) 1 else 2, output.parts.size)
            meshes.forEach { mesh ->
                val expected = ScaffoldBinding.deformerId(instance, mesh.id.raw, warpId.raw)
                assertEquals(expected, output.drawables.single { it.id == mesh.id }.parentDeformerId?.raw)
                assertSame(mesh.mesh, output.drawables.single { it.id == mesh.id }.mesh)
            }
            assertTrue(output.rootChildren.all { it is OrgChild.Part })
            assertEquals(meshes.map { it.id }.toSet(), output.parts.flatMap { it.children }.filterIsInstance<OrgChild.Drawable>().map { it.id }.toSet())
            assertFailsWith<IllegalArgumentException> { ScaffoldBinding.apply(output, instance, source) {} }
            assertFailsWith<InterruptedException> { ScaffoldBinding.apply(material, instance, source) { throw InterruptedException() } }
            assertTrue(material.deformers.isEmpty())
        }
        val rich = material.copy(drawables = material.drawables.map { it.copy(userData = "unused".repeat(500)) })
        val targets = mapOf("a" to "a")
        val slim = ScaffoldAlgorithm.capture(template, rich, targets)
        val full = ModifierAlgorithmContext(slim.values, mapOf(
            "template" to ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(template))),
            "materials" to ModifierAlgorithmResource(RigIrBinary.encode(PuppetIr.toIr(rich))),
        ))
        assertTrue(slim.resources.values.sumOf { it.size } < full.resources.values.sumOf { it.size })
        assertEquals(listOf("a"), RigIrBinary.decode(slim.resources.getValue("materials").read()).meshes.map { it.id })
        val algorithm = ScaffoldAlgorithm()
        for (version in 1..2) {
            val instance = RigModifierInstance("structure", "rig.scaffold", version, listOf(ModifierTarget("mesh", "a")))
            val input = PuppetIr.toIr(material)
            assertEquals(algorithm.evaluate(input, instance, full) {}, algorithm.evaluate(input, instance, slim) {})
            // Force the authored-coordinate transport path instead of the untouched-material shortcut.
            val authored = PuppetIr.toIr(material.copy(drawables = material.drawables.map { drawable ->
                drawable.copy(mesh = drawable.mesh!!.let { DrawableMesh(it.positions.map { v -> v + .1f }.toFloatArray(), it.uvs, it.indices) })
            }))
            assertEquals(algorithm.evaluate(authored, instance, full) {}, algorithm.evaluate(authored, instance, slim) {})
        }
    }
}
