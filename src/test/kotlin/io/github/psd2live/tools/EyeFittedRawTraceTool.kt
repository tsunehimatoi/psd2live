package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class EyeFittedRawTraceTool {
    @Test fun traceDsRawFrames() = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 64)
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        val source = pipeline.readPsd(Sample.fromEnvironment().path)
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val aliases = CliPresetBindings(analysis, config)
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val (_, model) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), cliBindings = aliases, restoreSourceRotations = true)
        val evaluation = assertNotNull(model.modifierEvaluation)
        val native = RigIrCompiler.compile(pipeline.buildPreview(source, config), tileArt = true)
        val presented = RigIrCompiler.compile(CliPresetPresentation.apply(model, analysis, aliases.moduleParameters, config), tileArt = true)
        fun rotation(ir: RigIR) = ir.deformers.filterIsInstance<Deformer.Rotation>().single { it.id == "DeformHeadRotation" }
        val eye = evaluation.entries.indexOfFirst { it.instance.type == "eye/fitted" && it.instance.version == 5 }
        val brow = evaluation.entries.indexOfFirst { it.instance.type == "brow/fitted" && it.instance.version == 4 }
        assertTrue(eye > 0 && brow > eye)
        assertNotEquals(rotation(evaluation.outputs[eye - 1]).baseAngle, rotation(evaluation.outputs[eye]).baseAngle)
        assertNotEquals(rotation(evaluation.outputs[eye]).baseAngle, rotation(evaluation.outputs[brow]).baseAngle)
        assertEquals(rotation(native).baseAngle, rotation(evaluation.outputs[brow]).baseAngle)
        assertEquals(rotation(native), rotation(presented))
        assertEquals(native.meshes.map { it.id }, presented.meshes.map { it.id })
        for ((expected, actual) in native.meshes.zip(presented.meshes)) {
            assertEquals(expected.geometry?.positions?.size, actual.geometry?.positions?.size, expected.id)
            assertEquals(expected.geometry?.indices, actual.geometry?.indices, expected.id)
        }
    }
}
