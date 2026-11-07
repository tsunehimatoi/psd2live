package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowLidSharedFramesTool {
    @Test fun compareDsCoupling() = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val config = PipelineConfig(meshSpacing = 64)
        val source = PSD2LivePipeline().readPsd(sample.path)
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val bindings = CliPresetBindings(analysis, config)
        val (planned, _) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
                "brow.shape" to 1, "brow.lid-coupling" to 1), cliBindings = bindings)
        val original = RigModifierStack.fromJson(planned.rigEdits.modifierStack!!)
        fun select(instance: RigModifierInstance, version: Int) = instance.copy(version = version,
            plugin = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, version)).reference)
        val stack = original
        val coupling = stack.instances.single { it.type == "brow.lid-coupling" }
        val index = stack.index(coupling.id)
        suspend fun build(version: Int): RigPreviewModel {
            val chosen = if (coupling.version == version) coupling else select(coupling, version)
            val changed = stack.copy(instances = stack.instances.map { if (it.id == coupling.id) chosen else it })
            return WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = changed.toJson())))
        }
        val reference = build(1)
        val old = assertNotNull(reference.modifierEvaluation).outputs
        val base = old[index - 1]
        val expected = old[index]
        val oldMeshes = base.meshes.associateBy { it.id }
        val affected = expected.meshes.filter { it != oldMeshes[it.id] }.map { it.id }
        println("DS COUPLING affected meshes=$affected")
        assertTrue(affected.isNotEmpty())
        assertContains(assertFailsWith<IllegalArgumentException> { build(5) }.message.orEmpty(), "requires fitted brows v1 through v3")
        val candidate = build(7)
        val actual = assertNotNull(candidate.modifierEvaluation).outputs
        val stage = actual[index]
        val expectedMeshes = expected.meshes.associateBy { it.id }
        val expectedDeformers = expected.deformers.associateBy { it.id }
        println("DS COUPLING differing meshes=" + stage.meshes.filter { it != expectedMeshes[it.id] }.map { it.id } +
            " deformers=" + stage.deformers.filter { it != expectedDeformers[it.id] }.map { it.id })
        assertTrue(old[index - 1] == actual[index - 1], "The preceding fitted stage must match")
        assertTrue(expected == stage, "The coupling stage must match exactly")
        assertTrue(RigIrCompiler.compile(reference, tileArt = true) == RigIrCompiler.compile(candidate, tileArt = true),
            "The final DS IR must match exactly")
    }
}
