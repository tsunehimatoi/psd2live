package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.FittedFaceLattices
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.modifier.ModifierPluginStore
import io.github.psd2live.core.modifier.ModifierPluginWorkers
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Mesh
import io.github.psd2live.format.model.Parameter
import io.github.psd2live.format.model.Part
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.PuppetModel
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
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
        var captured: (() -> PuppetModel)? = null
        RigBuilder.build(analysis, AtlasLayout.pack(analysis.layers, config), config, cache) { captured = it }
        val native = assertNotNull(captured).invoke()
        val deformers = context.deformers.map(PuppetIr::deformerToIr)
        val parts = deformers.mapNotNull { it.part }.distinct().map { Part(it, it, emptyList()) }
        val face = Mesh("face", "Face", "DeformFaceContour", geometry = null, offsets = null)
        val nativeParameters = PuppetIr.parametersToIr(native)
        val nativeIds = nativeParameters.mapTo(hashSetOf()) { it.id }
        // The pre-lowering fixture retains structural axes omitted from the finished character.
        val structuralAxes = deformers.flatMap { deformer ->
            when (deformer) {
                is Deformer.Warp -> deformer.lattice?.axes.orEmpty()
                is Deformer.Rotation -> deformer.pivot?.axes.orEmpty()
            } + deformer.channels.values.flatMap { it.axes }
        }.map { it.parameter }.distinct().filterNot { it in nativeIds }
        val parameters = nativeParameters + structuralAxes.map { Parameter(it, it, -45f, 45f, 0f) }
        val before = RigIR(Canvas(420f, 420f), parameters,
            parts = parts, deformers = deformers, meshes = listOf(face))
        val changes = FittedFaceLattices.changes(before, FittedFaceLattices.Inputs.decode(source.encode()))
        assertTrue(changes.deformers.updated.any { it.before.id == "DeformFaceContour" })
        val result = changes.apply(before)
        System.getProperty("psd2live.fittedFaceLatticesPackage")?.let {
            assertEquals(result, packagedResult(it, before, source.encode()))
        }
        val candidate = PuppetIr.toPuppet(result)
        val afterLean = RigBuilder.withFaceLean(candidate.deformers, config.rigTuning).map(PuppetIr::deformerToIr)
        val expected = native.deformers.map(PuppetIr::deformerToIr).associateBy { it.id }
        val changedIds = changes.deformers.updated.mapTo(hashSetOf()) { it.after.id }
        for (deformer in afterLean.filter { it.id in changedIds }) assertEquals(expected[deformer.id], deformer, deformer.id)
    }

    private fun packagedResult(directory: String, input: RigIR, context: ModifierAlgorithmContext): RigIR {
        val root = Files.createTempDirectory("fitted-face-lattices-")
        val preferences = Preferences.userRoot().node("psd2live-test/fitted-face-lattices/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            val installed = store.install(directory)
            try {
                val algorithm = installed.manifest.algorithms.single()
                val targets = listOf(ModifierTarget("mesh", "face"))
                val instance = RigModifierInstance("styled-face", algorithm.type, algorithm.version, targets, plugin = installed.reference)
                val provided = context.withUpstream(listOf(
                    ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, targets, "scaffold"),
                ))
                return ModifierPluginWorkers.evaluate(installed, algorithm, input, instance, {}, store, context = provided)
            } finally {
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
