package io.github.psd2live.core

import io.github.psd2live.core.recipe.*
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlin.test.*

class ExpressionGeometryCacheTest {
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
