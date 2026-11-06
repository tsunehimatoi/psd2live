package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.FittedFaceLattices
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FittedFaceLatticeParityTest {
    @Test fun transportedLatticesMatchTheExistingStyledBindingBeforeLowering() {
        val document = Characters.eyed()
        val depth = RigModules.FACE_DEPTH_TURN
        val config = document.rawConfig().copy(generateDeformers = true, mouthOutlineEnabled = false,
            recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice("eye/fitted")),
                modules = RigModules.modules.associate {
                    it.id to ModuleChoice(it == depth, values = if (it == depth) mapOf("style" to 4f) else emptyMap())
                }))
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val context = RigBuilder.rigContext(analysis, config, cache)
        val parents = context.deformers.mapNotNullTo(linkedSetOf()) { it.parent }
        val source = assertNotNull(RigFittedFaceSource.capture(context, config, parents))
        assertNotNull(source.source.styledTurn())
        // The legacy pre-style skeleton is used here to isolate the fitted replacement from nine-pose generation.
        val before = RigIR(Canvas(420f, 420f), emptyList(), deformers = context.deformers.map(PuppetIr::deformerToIr))
        val changes = FittedFaceLattices.changes(before, FittedFaceLattices.Inputs.decode(source.encode()))
        assertTrue(changes.deformers.updated.any { it.before.id == "DeformFaceContour" })
        val candidate = PuppetIr.toPuppet(changes.apply(before))
        val afterLean = RigBuilder.withFaceLean(candidate.deformers, config.rigTuning).map(PuppetIr::deformerToIr)
        var captured: (() -> PuppetModel)? = null
        RigBuilder.build(analysis, AtlasLayout.pack(analysis.layers, config), config, cache) { captured = it }
        val expected = assertNotNull(captured).invoke().deformers.map(PuppetIr::deformerToIr).associateBy { it.id }
        val changedIds = changes.deformers.updated.mapTo(hashSetOf()) { it.after.id }
        for (deformer in afterLean.filter { it.id in changedIds }) assertEquals(expected[deformer.id], deformer, deformer.id)
    }
}
