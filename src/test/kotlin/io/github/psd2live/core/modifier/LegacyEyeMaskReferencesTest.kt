package io.github.psd2live.core.modifier

import io.github.psd2live.algorithms.binding.EyeLegacyInputs
import io.github.psd2live.algorithms.binding.ModelIdRemap
import io.github.psd2live.core.*
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.bridge.PuppetIr
import kotlinx.serialization.json.*
import org.umamo.runtime.model.DrawableId
import kotlin.test.*

class LegacyEyeMaskReferencesTest {
    @Test fun sourceOpeningsClipIrisesToDrawableIds() = verify(renamed = false)

    @Test fun renamedDrawablesKeepSourceMatchingAndLiveMaskReferences() = verify(renamed = true)

    private fun verify(renamed: Boolean) {
        val document = Characters.eyed(legacyEyes = true)
        val config = RigModifierPipeline.ordinaryConfig(document.rawConfig())
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val original = RigOrdinaryMaterials.of(RigBuilder.build(analysis, atlas, config), analysis)
        val names = original.puppet.drawables.associate { it.id to
            if (renamed) DrawableId("authored:${it.id.raw}") else it.id }
        val materials = ModelIdRemap(drawables = names).model(original.puppet)
        val layerByMesh = original.layerIdByDrawableId.mapKeys { (id, _) -> names.getValue(DrawableId(id)).raw }
        val meshByLayer = layerByMesh.entries.associate { it.value to it.key }
        val targets = materials.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val stack = RigModifierStack().add("rig.scaffold", 2, targets, id = "structure")
            .add("eye/legacy", 2, targets, buildJsonObject { put("scaffold", "structure") }, id = "eyes")
        val owner = RigModifierSource(analysis, atlas, config.copy(rigEdits = config.rigEdits.copy(modifierStack = stack.toJson())),
            layerByMesh, materialBase = materials)
        val context = owner.workerBindingContext(stack.instances.last())
        val measured = EyeLegacyInputs.decode(ModifierAlgorithmContext(context.values.getValue("eye").jsonObject,
            mapOf("source_meshes" to context.resources.getValue("source_meshes"))))
        assertEquals(2, measured.openings.size)
        for (opening in measured.openings) {
            assertNotEquals(opening.sourceId, opening.id)
            assertEquals(meshByLayer.getValue(opening.sourceId), opening.id)
            assertNull(measured.layers.getValue(opening.id).irisSource, "An eye opening must not clip itself")
        }
        // Evaluation runs the same strict result validation as RAW CLI preset initialization.
        val result = RigModifierAlgorithms.evaluator(owner, stack).evaluate(PuppetIr.toIr(materials), stack)
        assertTrue(result.entries.all { it.status == ModifierStatus.ACTIVE })
        val whites = analysis.layers.filter { it.semantic.tag == SemanticTag.EYEWHITE }.associateBy { it.semantic.side }
        for (white in whites.values) {
            assertTrue(result.rig.meshes.single { it.id == meshByLayer.getValue(white.source.id.raw) }.maskedBy.isEmpty())
        }
        val irises = analysis.layers.filter { it.semantic.tag == SemanticTag.IRIDES }
        assertEquals(2, irises.size)
        for (iris in irises) {
            val mesh = result.rig.meshes.single { it.id == meshByLayer.getValue(iris.source.id.raw) }
            val white = whites.getValue(iris.semantic.side)
            assertEquals(listOf(meshByLayer.getValue(white.source.id.raw)), mesh.maskedBy)
            assertTrue(mesh.maskedBy.all { mask -> result.rig.meshes.any { it.id == mask } })
        }
    }
}
