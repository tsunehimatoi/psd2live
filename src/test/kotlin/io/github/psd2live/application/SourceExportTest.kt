package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.rawConfig
import io.github.psd2live.scenario.Characters
import kotlin.test.*

class SourceExportTest {
    @Test fun standaloneExportKeepsCliAliasesAndParameterOrder() {
        val document = Characters.head(physics = false)
        val config = document.rawConfig().copy(allowEmptyArt = false)
        val expected = CliPresetBindings.build(PSD2LivePipeline(), document.source, config, ProgressListener { _, _ -> })
        val actual = PSD2LivePipeline().buildSourceExport(document.source, config)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(actual))
        assertEquals(expected.config, actual.config)
        assertTrue(RigModifierStack.fromJson(actual.config.rigEdits.modifierStack!!).instances.any { it.outputAliases.isNotEmpty() })
    }

    @Test fun explicitStackAndStaticMeshKeepTheirOwnExportPaths() {
        val document = Characters.head(physics = false)
        val config = document.rawConfig()
        val explicit = config.copy(rigEdits = config.rigEdits.copy(modifierStack = RigModifierStack().toJson()))
        val actual = PSD2LivePipeline().buildSourceExport(document.source, explicit)
        assertEquals(RigIrCompiler.compile(PSD2LivePipeline().buildPreview(document.source, explicit)), RigIrCompiler.compile(actual))
        assertTrue(RigModifierStack.fromJson(actual.config.rigEdits.modifierStack!!).instances.isEmpty())
        val mesh = config.copy(meshOnly = true)
        assertEquals(RigIrCompiler.compile(PSD2LivePipeline().buildRawMeshExport(document.source, mesh)),
            RigIrCompiler.compile(PSD2LivePipeline().buildSourceExport(document.source, mesh)))
        val motion = config.copy(generateDeformers = false)
        assertEquals(RigIrCompiler.compile(PSD2LivePipeline().buildRawWithoutDeformers(document.source, motion, ProgressListener { _, _ -> })),
            RigIrCompiler.compile(PSD2LivePipeline().buildSourceExport(document.source, motion)))
    }
}
