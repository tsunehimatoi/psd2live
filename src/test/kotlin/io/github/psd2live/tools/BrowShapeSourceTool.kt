package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Compares brow shape after fitted bindings that change shared head frames. */
class BrowShapeSourceTool {
    @Test fun compareSharedFrameShape(): Unit = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 64)
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = config.rigEdits.copy(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "brow.shape" to 1)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val shape = stack.instances.single { it.type == "brow.shape" }
        val index = stack.index(shape.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        val oldMeshes = expected[index - 1].meshes.associateBy { it.id }
        for (id in listOf("ArtMeshEyebrowL", "ArtMeshEyebrowR")) {
            assertNotEquals(oldMeshes.getValue(id), expected[index].meshes.single { it.id == id })
        }
        fun candidate(version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(shape.type, version))
            val modified = stack.copy(instances = stack.instances.map {
                if (it.id == shape.id) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson()))
        }
        val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(3)) }
        assertContains(rejected.message.orEmpty(), "requires a compatible fitted brow provider")
        val preview = WorkspacePreviewBuilder().build(candidate(5))
        val result = assertNotNull(preview.modifierEvaluation)
        fun equal(reference: RigIR, observed: RigIR, stage: String) {
            if (reference == observed) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(reference) != it.get(observed) }.map { it.name }
            val meshes = reference.meshes.zip(observed.meshes).filter { (a, b) -> a != b }.map { (a, b) ->
                "${a.id}: geometry=${a.geometry != b.geometry}, offsets=${a.offsets != b.offsets}, shapes=${a.shapes != b.shapes}"
            }
            fail("$stage differs in $fields, meshes: $meshes")
        }
        for (stage in stack.instances.indices) equal(expected[stage], result.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(shape.type, 5))
        val root = Files.createTempDirectory("brow-shape-source-")
        val preferences = Preferences.userRoot().node("brow-shape-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = result.entries[index].instance
            val definition = RigBrowShapeModifier.sharedFrameDefinition(RigModifierSource.of(preview))
            val context = definition.authorContext(instance).withUpstream(result.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val fitted = context.upstream.single { it.type == "brow/fitted" }
            assertEquals(4, fitted.version)
            assertEquals(stack.instances.single { it.type == fitted.type }.plugin, fitted.plugin)
            equal(expected[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                result.outputs[index - 1], instance, {}, store, context = context, declaredParameters = definition.parameters(instance)), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareSingleEyeOpenWorkspace(): Unit = runBlocking { compareSingleEyeOpen(cli = false) }

    @Test fun compareSingleEyeOpenRawCli(): Unit = runBlocking { compareSingleEyeOpen(cli = true) }

    private suspend fun compareSingleEyeOpen(cli: Boolean) {
        requireTools()
        val document = singleEyeOpenDocument(Sample.fromEnvironment(), meshSpacing = if (cli) 64 else 40)
        val config = PipelineConfig(meshSpacing = if (cli) 64 else 40)
        val analyses = if (cli) RigGenerationSource.prepare(RigGenerationSource.analyze(document.source, config), config) else null
        val aliases = analyses?.let { CliPresetBindings(it, config) }
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3, "mouth/fitted" to 7,
            "mouth.outline" to 10, "mouth.vowel" to 9, "mouth.capture" to 8, "brow.shape" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = aliases, restoreSourceRotations = cli)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val expected = assertNotNull(baseline.modifierEvaluation)
        for ((type, version) in versions) {
            val entry = expected.entries.single { it.instance.type == type }
            assertEquals(version, entry.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, entry.status, type)
            if (type != "brow.shape") {
                assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, version)).reference, entry.instance.plugin, type)
            }
        }
        assertEquals(cli, stack.instances.single { it.type == "rig.scaffold" }.outputAliases.isNotEmpty())
        val shape = stack.instances.single { it.type == "brow.shape" }
        val stage = stack.index(shape.id)
        val before = expected.outputs[stage - 1]
        val after = expected.outputs[stage]
        val brow = after.meshes.single { it.id == "ArtMeshEyebrowL" }
        assertTrue(brow.visible && brow.opacity > 0f)
        assertNotEquals(before.meshes.single { it.id == brow.id }, brow)
        assertTrue(after.parameters.any { it.id == "ParamBrowLForm" })
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(shape.type, 4))
        val selected = shape.copy(version = 4, plugin = artifact.reference)
        val modified = stack.copy(instances = stack.instances.map { if (it.id == selected.id) selected else it })
        val candidate = planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson()))
        val preview = if (cli) PSD2LivePipeline().apply {
            deferRuntimeBundles = true
            rawSourceRotations = true
        }.buildPreview(candidate.source, config.copy(rigEdits = candidate.rigEdits))
        else WorkspacePreviewBuilder().build(candidate)
        val actual = assertNotNull(preview.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        for (index in stack.instances.indices) {
            assertTrue(expected.outputs[index] == actual.outputs[index], stack.instances[index].type)
        }
        fun exported(model: RigPreviewModel): RigIR {
            val presented = if (aliases != null) CliPresetPresentation.apply(model, assertNotNull(analyses), aliases.moduleParameters, config)
                else model
            return RigIrCompiler.compile(presented, tileArt = true)
        }
        assertTrue(exported(baseline) == exported(preview), "final IR differs")

        val root = Files.createTempDirectory("brow-shape-single-eye-")
        val preferences = Preferences.userRoot().node("brow-shape-single-eye/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigBrowShapeModifier.fallbackDefinition(RigModifierSource.of(preview))
            val context = assertNotNull(actual.readAlgorithms[shape.id]).context
            assertTrue("brow_turn" in context.resources)
            assertTrue(after == ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                before, selected, {}, store, context = context, declaredParameters = definition.parameters(selected)),
                "installed JVM IR differs")
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
