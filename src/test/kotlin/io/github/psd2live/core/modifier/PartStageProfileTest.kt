package io.github.psd2live.core.modifier

import io.github.psd2live.core.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import kotlin.test.*

class PartStageProfileTest {
    @Test fun eyeStagesPreserveOutput() = compare(Characters.eyed(), "eye", "eye/fitted", "eye.wide")
    @Test fun browStagesPreserveOutput() = compare(Characters.browed(), "brow", "brow/fitted", "brow.shape")
    @Test fun mouthRasterStagesPreserveOutput() = compare(Characters.mouthed(), "mouth", "mouth/fitted", "mouth.outline")

    private fun compare(document: WorkspaceDocument, part: String, base: String, child: String) {
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig()).copy(meshSpacing = 40)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val ordinary = RigOrdinaryMaterials.of(RigBuilder.build(analysis, atlas, config), analysis)
        val input = PuppetIr.toIr(ordinary.puppet)
        val targets = ordinary.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val expanded = RigModifierStack().add("rig.scaffold", 2, targets, id = "structure")
            .add(base, 1, targets, buildJsonObject { put("scaffold", "structure") }, id = "base")
            .add(child, 1, targets, buildJsonObject {
                put("scaffold", "structure")
                if (RigLegacyBindings.spec(child).module?.base != null) put("base", "base")
                if (child == "mouth.outline") put("color", 0x563421)
            }, id = "child")
        val stack = expanded.copy(instances = RigPartFolding.foldAll(expanded.instances,
            freshInstanceIds = expanded.instances.mapTo(hashSetOf()) { it.id }))
        assertEquals(2, stack.instances.size)
        fun evaluate() = RigModifierSource(analysis, atlas,
            config.copy(rigEdits = RigEditOverlay(modifierStack = stack.toJson())), ordinary.layerIdByDrawableId,
            materialBase = ordinary.puppet).let { source ->
            RigModifierAlgorithms.evaluator(source, stack).evaluate(input, stack).rig
        }
        val recording = RigBuildProfile.recording
        val observer = ModifierAlgorithmStages.observer
        try {
            RigBuildProfile.recording = false
            val expected = evaluate()
            RigBuildProfile.recording = true
            val measured = RigBuildProfile.measure(RigBuildProfile.Operation("part-stage", "before", "after", part,
                input.meshes.size, input.meshes.sumOf { (it.geometry?.positions?.size?.toLong() ?: 0) / 2 })) { evaluate() }
            assertEquals(expected, measured.value, "Stage observation must preserve every RigIR field and raster")
            val stages = measured.stages.filterKeys { it.startsWith("algorithm stage:") }
            assertEquals(setOf("algorithm stage: part $part base: $base", "algorithm stage: part $part sub: $child"), stages.keys)
            assertTrue(stages.values.all { it.second == 1L })
            RigBuildProfile.recording = false
            ModifierAlgorithmStages.observer = { _, _ -> error("broken observer") }
            assertEquals(expected, evaluate(), "Observer failure must not change part output")
        } finally {
            RigBuildProfile.recording = recording
            ModifierAlgorithmStages.observer = observer
            RigBuildProfile.reset()
        }
    }
}
