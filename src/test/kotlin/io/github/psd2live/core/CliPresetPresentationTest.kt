package io.github.psd2live.core

import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.format.model.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlin.test.*

class CliPresetPresentationTest {
    @Test fun repeatedLoweringPreservesEveryCarrierAndReservesExistingExportNames() {
        val owners = (1..3).map { RigModifierInstance("m$it", "eye.gaze-lid", 1, listOf(ModifierTarget("mesh", "lid"))) }
        val stack = RigModifierStack(instances = owners)
        val canonical = "ArtMeshEyelashL_Shape0"
        val first = owners[0].outputId("shared/deformer:$canonical")
        val second = owners[1].outputId("shared/deformer:$canonical")
        val numbered = owners[2].outputId("shared/deformer:${canonical}_2")
        val geometry = MeshGeometry(Floats.values(0f, 0f, 1f, 0f, 0f, 1f),
            Floats.values(0f, 0f, 1f, 0f, 0f, 1f), Ints.wrap(intArrayOf(0, 1, 2)))
        fun carrier(id: String, parent: String?) = Deformer.Rotation(id, id, parent, null, 0f, null)
        for (occupied in listOf(false, true)) {
            val source = PuppetIr.toPuppet(RigIR(Canvas(100f, 100f), emptyList(),
                deformers = listOfNotNull(carrier(canonical, null).takeIf { occupied }) + listOf(
                    carrier(first, canonical.takeIf { occupied }), carrier(second, first), carrier(numbered, second)),
                meshes = listOf(Mesh("lid", "lid", numbered, geometry = geometry, offsets = null))))
            val restored = CliPresetPresentation.restoreGeneratedFrameIds(source, stack)
            val expected = if (occupied) listOf(canonical, "${canonical}_3", "${canonical}_4", "${canonical}_2")
                else listOf(canonical, "${canonical}_3", "${canonical}_2")
            assertEquals(expected, restored.deformers.map { it.id.raw })
            assertEquals(listOf(null) + expected.dropLast(1), restored.deformers.map { it.parent?.raw })
            assertEquals("${canonical}_2", restored.drawables.single().parentDeformerId?.raw)
            assertEquals(geometry, PuppetIr.toIr(restored).meshes.single().geometry)
            assertEquals(source.deformers.map { it.name }, restored.deformers.map { it.name })
            assertEquals(restored, CliPresetPresentation.restoreGeneratedFrameIds(source, stack))
            assertSame(restored, CliPresetPresentation.restoreGeneratedFrameIds(restored, stack))
        }
    }
}
