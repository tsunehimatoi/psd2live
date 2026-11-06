package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.DepthTurnAlgorithm
import io.github.psd2live.algorithms.binding.ModelIdRemap
import io.github.psd2live.core.modifier.ModifierUpstreamCapability
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierInstance
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DepthTurnPluginParityTest {
    @Test fun shellFallbackMovesTheMouthAsOneAndKeepsTheNativeGeometry() {
        compare(styled = false)
    }

    @Test fun styledFaceAndHairMatchTheNativeTurn() {
        compare(styled = true)
    }

    private fun compare(styled: Boolean) {
        val document = if (styled) Characters.eyed() else Characters.mouthed()
        val module = RigModules.FACE_DEPTH_TURN.id
        val bases = if (styled) mapOf("eye" to BaseChoice("eye/fitted"))
            else mapOf("mouth" to BaseChoice("mouth/fitted"))
        val turnValues = mapOf("style" to 2f, "turn" to 64f, "look" to 85f, "depth" to 130f, "hairLead" to 15f)
        fun recipe(enabled: Boolean) = RigRecipe(true, bases = bases,
            modules = RigModules.modules.associate { it.id to ModuleChoice(enabled && it.id == module,
                values = if (it.id == module) turnValues else emptyMap()) })
        val config = document.rawConfig().copy(generateDeformers = true, mouthOutlineEnabled = false,
            generatePhysics = false, recipe = recipe(false))
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val atlas = AtlasLayout.pack(analysis.layers, config)
        var raw: (() -> PuppetModel)? = null
        val baseline = RigBuilder.build(analysis, atlas, config, cache) { raw = it }
        val native = requireNotNull(raw).invoke()
        val enabled = config.copy(recipe = recipe(true))
        val expected = PuppetIr.toIr(RigBuilder.build(analysis, atlas, enabled, cache).puppet)
        val context = RigBuilder.rigContext(analysis, config, cache)
        val layersById = analysis.layers.associateBy { it.source.id.raw }
        val layers = native.drawables.associate { drawable ->
            drawable.id.raw to layersById.getValue(baseline.layerIdByDrawableId.getValue(drawable.id.raw))
        }
        val parentFrames = (native.deformers.mapNotNull { it.parent } + native.drawables.mapNotNull { it.parentDeformerId })
            .distinct().associateWith { requireNotNull(context.resolveFrame(it, config)) }
        val scaffold = RigBuilder.scaffoldMeshInputs(analysis, atlas, config.copy(recipe = RigRecipe.Legacy), cache,
            layers.entries.associate { (mesh, layer) -> layer.source.id.raw to mesh })
        val source = RigDepthTurnSource.capture(context, enabled, layers,
            native.drawables.associate { it.id.raw to it.parentDeformerId }, parentFrames,
            scaffold.resources.getValue("source_meshes"), layers.keys.associateWith { it })
        val instance = RigModifierInstance("depth-turn-test", module, 1, layers.keys.map { ModifierTarget("mesh", it) })
        val input = PuppetIr.toIr(native)
        val contextInput = source.encode().withUpstream(listOf(
            ModifierUpstreamCapability("rig.scaffold", "rig.scaffold", 2, instance.targets, "scaffold"),
            ModifierUpstreamCapability("face/legacy", "face/legacy", 1, instance.targets, "face"),
        ))
        val changes = DepthTurnAlgorithm().changes(input, instance, contextInput) {}
        val nativeIds = changes.deformers.added.associate { added ->
            val name = requireNotNull(instance.localOutputName(added.id)).removePrefix("shared/deformer:")
            DeformerId(added.id) to DeformerId(name)
        }
        val actual = PuppetIr.toIr(ModelIdRemap(deformers = nativeIds).model(PuppetIr.toPuppet(changes.apply(input))))
        assertNotEquals(PuppetIr.toIr(baseline.puppet).meshes, expected.meshes)
        if (styled) {
            val skin = expected.deformers.single { it.id == "DeformFaceContour" }
            assertEquals(16, (skin as io.github.psd2live.format.model.Deformer.Warp).columns)
        } else {
            check(native.deformers.any { it.id == DeformerId("DeformMouthShapeBoth") })
        }
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.deformers, actual.deformers)
        assertEquals(expected.meshes, actual.meshes)
    }
}
