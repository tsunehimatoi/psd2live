package io.github.psd2live.core

import io.github.psd2live.core.recipe.*
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlin.test.*

class ExpressionGeometryCacheTest {
    @Test fun sourceParameterDeclarationsDoNotBuildMeshes() {
        val document = Characters.head(physics = false)
        val recipe = RigRecipe(true, modules = RigModules.modules.associate { it.id to ModuleChoice(it == RigModules.EXPRESSION_TOGGLES) })
        val config = document.rawConfig().copy(recipe = recipe)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val declared = RigBuilder.sourceStandardParameters(analysis, config, cache)
        assertEquals(0L, cache.stages.misses(RigStageCache.MESH))
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val reference = RigBuilder.build(analysis, atlas, config, cache).puppet.parameters
        val fittedAxes = setOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN, StandardParameters.MOUTH_OPEN)
        assertEquals(reference.filter { it.id in fittedAxes }, declared.filter { it.id in fittedAxes })
        assertTrue(cache.stages.misses(RigStageCache.MESH) > 0)
    }

    @Test fun facialModulesKeepBodyMeshesWhileChestMotionInvalidatesThem() {
        val document = Characters.figure()
        val recipe = RigRecipe(true, bases = RigModules.areas.associate { it.id to BaseChoice(it.legacy.id) },
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) })
        val config = document.rawConfig().copy(recipe = recipe)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val cache = PreviewMeshCache()
        val before = RigBuilder.build(analysis, atlas, config, cache)
        val initial = cache.stages.misses(RigStageCache.MESH)
        val smile = config.copy(recipe = recipe.copy(modules = recipe.modules + (RigModules.EYE_SMILE.id to ModuleChoice(true))))
        val after = RigBuilder.build(analysis, atlas, smile, cache)
        val facialMisses = cache.stages.misses(RigStageCache.MESH)
        assertTrue(facialMisses - initial < analysis.layers.size, "facial options must not rebuild every body mesh")
        assertEquals(PuppetIr.toIr(before.puppet), PuppetIr.toIr(after.puppet))
        val bust = smile.copy(recipe = smile.recipe.copy(modules = smile.recipe.modules + (RigModules.BODY_BUST.id to ModuleChoice(true))))
        val changed = RigBuilder.build(analysis, atlas, bust, cache)
        assertTrue(cache.stages.misses(RigStageCache.MESH) > facialMisses)
        assertNotEquals(PuppetIr.toIr(after.puppet), PuppetIr.toIr(changed.puppet))
        assertEquals(PuppetIr.toIr(RigBuilder.build(analysis, atlas, bust).puppet), PuppetIr.toIr(changed.puppet))
    }

    @Test fun meshLocalModulesReuseScaffoldButGazeRebuildsIt() {
        val document = Characters.head(physics = false)
        val recipe = RigRecipe(true,
            bases = RigModules.areas.associate { it.id to BaseChoice(if (it.id == "eye") RigModules.EYE_FITTED.id else it.legacy.id) },
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) })
        val config = document.rawConfig().copy(recipe = recipe)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val before = RigBuilder.rigContext(analysis, config, cache)
        val smile = config.copy(mouthColor = 0x123456, mouthThickness = 4f,
            recipe = recipe.copy(modules = recipe.modules + (RigModules.EYE_SMILE.id to ModuleChoice(true))))
        val after = RigBuilder.rigContext(analysis, smile, cache)
        assertSame(before.deformers, after.deformers)
        val gaze = smile.copy(recipe = smile.recipe.copy(modules = smile.recipe.modules +
            (RigModules.EYE_GAZE_LID.id to ModuleChoice(true))))
        val changed = RigBuilder.rigContext(analysis, gaze, cache)
        assertNotSame(after.deformers, changed.deformers)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        assertEquals(PuppetIr.toIr(RigBuilder.build(analysis, atlas, gaze).puppet),
            PuppetIr.toIr(RigBuilder.build(analysis, atlas, gaze, cache).puppet))
    }

    @Test fun sourceEyeMeasurementIsSharedAcrossOptionsAndInvalidatedBySourceChanges() {
        val document = Characters.head(physics = false)
        val recipe = RigRecipe(true,
            bases = RigModules.areas.associate { it.id to BaseChoice(if (it.id == "eye") RigModules.EYE_FITTED.id else it.legacy.id) },
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) })
        val config = document.rawConfig().copy(recipe = recipe)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val cache = PreviewMeshCache()
        val first = RigBuilder.rigContext(analysis, config, cache).fittedEyes(config)!!
        val changed = config.copy(recipe = recipe.copy(modules = recipe.modules + (RigModules.EYE_SMILE.id to ModuleChoice(true, values = mapOf("arch" to 60f)))))
        val second = RigBuilder.rigContext(analysis, changed, cache).fittedEyes(changed)!!
        assertSame(first.fitting, second.fitting)
        assertNotEquals(first.options, second.options)
        assertEquals(1L, cache.stages.misses("source fitting: eyes"))
        assertTrue(cache.stages.hits("source fitting: eyes") > 0)
        val reordered = analysis.copy(layers = analysis.layers.reversed())
        val third = RigBuilder.rigContext(reordered, changed, cache).fittedEyes(changed)!!
        assertNotSame(second.fitting, third.fitting)
        val cold = RigBuilder.rigContext(reordered, changed).fittedEyes(changed)!!
        assertEquals(cold.fitting.fits, third.fitting.fits)
        assertEquals(cold.fitting.level, third.fitting.level)
    }

    @Test fun expressionChannelsChangeWithoutRebuildingGeometry() {
        val document = Characters.head(physics = false)
        val recipe = RigRecipe(true,
            bases = RigModules.areas.associate { it.id to BaseChoice(it.legacy.id) },
            modules = RigModules.modules.associate { it.id to ModuleChoice(false) })
        val config = document.rawConfig().copy(recipe = recipe)
        val analysis = CharacterAnalyzer.analyze(document.source, config)
        val atlas = AtlasLayout.pack(analysis.layers, config)
        val cache = PreviewMeshCache()
        val before = RigBuilder.build(analysis, atlas, config, cache)
        val misses = cache.stages.misses(RigStageCache.MESH)
        assertTrue(misses > 0)
        val changed = config.copy(recipe = recipe.copy(modules = recipe.modules + mapOf(
            RigModules.EXPRESSION_CHEEK.id to ModuleChoice(true),
            RigModules.EXPRESSION_GLOOM.id to ModuleChoice(true))))
        val after = RigBuilder.build(analysis, atlas, changed, cache)
        assertEquals(misses, cache.stages.misses(RigStageCache.MESH))
        assertNotEquals(PuppetIr.toIr(before.puppet), PuppetIr.toIr(after.puppet))
        assertEquals(PuppetIr.toIr(RigBuilder.build(analysis, atlas, changed).puppet), PuppetIr.toIr(after.puppet))
    }
}
