package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import org.umamo.format.art.SourceArt
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class SingleEyeOpenMouthSourceTool {
    @Test fun compareFittedMouth(): Unit = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val document = singleEyeDocument(sample)
        val measured = RigGenerationSource.analyze(document.source, PipelineConfig(meshSpacing = 40))
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3, "mouth/fitted" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val mouth = stack.instances.single { it.type == "mouth/fitted" }
        val stage = stack.index(mouth.id)
        val expected = assertNotNull(baseline.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, expected.entries[stage].status)
        val mouthLayers = measured.layers.filter { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) }
            .mapTo(HashSet()) { it.source.id.raw }
        val beforeMouth = expected.outputs[stage - 1].meshes.associateBy { it.id }
        assertTrue(expected.outputs[stage].meshes.any {
            baseline.rig.ownLayerIdByDrawableId[it.id] in mouthLayers && it != beforeMouth[it.id]
        }, "Fitting must affect the source mouth mesh")
        val beforeFrames = expected.outputs[stage - 1].deformers.associateBy { it.id }
        val changedMeshes = expected.outputs[stage].meshes.filter { it != beforeMouth[it.id] }.map { it.id }
        val changedFrames = expected.outputs[stage].deformers.filter { it != beforeFrames[it.id] }.map { it.id }
        output("single-eye-open-mouth").resolve("${sample.name}.txt").writeText(
            "Fitting v1 changes meshes: $changedMeshes\nFitting v1 changes deformers: $changedFrames\n")
        val sourceVersion = assertNotNull(BundledAlgorithmArtifacts.find(mouth.type, 7))
        val modified = stack.copy(instances = stack.instances.map { entry ->
            if (entry.id == mouth.id) entry.copy(version = 7, plugin = sourceVersion.reference) else entry
        })
        val evaluated = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val actual = assertNotNull(evaluated.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        for (index in stack.instances.indices) {
            val before = expected.outputs[index]
            val after = actual.outputs[index]
            if (before != after) {
                val originalMeshes = before.meshes.associateBy { it.id }
                val changed = after.meshes.filter { it != originalMeshes[it.id] }.map { it.id }
                fail("${stack.instances[index].type} differs, changed meshes=$changed, deformers match=${before.deformers == after.deformers}")
            }
        }
        assertTrue(RigIrCompiler.compile(baseline, tileArt = true) == RigIrCompiler.compile(evaluated, tileArt = true),
            "Complete workspace IR differs")

        val instance = actual.entries[stage].instance
        val definition = RigFittedMouthDefinition.fallbackFrameDefinition(RigModifierSource.of(evaluated))
        val context = definition.authorContext(instance).withUpstream(actual.entries.take(stage)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        val root = Files.createTempDirectory("single-eye-open-mouth-package-")
        val preferences = Preferences.userRoot().node("single-eye-open-mouth/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(sourceVersion))
            val worker = store.snapshot().single()
            val result = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), actual.outputs[stage - 1],
                instance, {}, store, context = context, declaredParameters = definition.parameters(instance))
            assertTrue(expected.outputs[stage] == result, "Installed worker differs from v1")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareOutline(): Unit = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3, "mouth/fitted" to 7, "mouth.outline" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(singleEyeDocument(sample), WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val outline = stack.instances.single { it.type == "mouth.outline" }
        val stage = stack.index(outline.id)
        val expected = assertNotNull(baseline.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, expected.entries[stage].status)
        assertTrue(expected.outputs[stage].meshes.size > expected.outputs[stage - 1].meshes.size)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(outline.type, 10))
        val modified = stack.copy(instances = stack.instances.map {
            if (it.id == outline.id) it.copy(version = 10, plugin = artifact.reference) else it
        })
        val model = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        val mismatch = expected.outputs.zip(actual.outputs).indexOfFirst { (before, after) -> before != after }
        if (mismatch >= 0) {
            val old = expected.outputs[mismatch]
            val new = actual.outputs[mismatch]
            val fields = old.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(old) != it.get(new) }.map { it.name }
            val beforeMeshes = old.meshes.associateBy { it.id }
            val beforeFrames = old.deformers.associateBy { it.id }
            fail("${stack.instances[mismatch].type} differs in $fields, meshes=" +
                new.meshes.filter { it != beforeMeshes[it.id] }.map { it.id } + ", frames=" +
                new.deformers.filter { it != beforeFrames[it.id] }.map { it.id })
        }
        assertTrue(RigIrCompiler.compile(baseline, tileArt = true) == RigIrCompiler.compile(model, tileArt = true))
        assertEquals(baseline.rig.modifierRasterAssets, model.rig.modifierRasterAssets)

        val instance = actual.entries[stage].instance
        val definition = RigFittedMouthOutlineDefinition.fallbackFrameDefinition(RigModifierSource.of(model))
        val context = definition.authorContext(instance).withUpstream(actual.entries.take(stage)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        val root = Files.createTempDirectory("single-eye-open-outline-package-")
        val preferences = Preferences.userRoot().node("single-eye-open-outline/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val result = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), actual.outputs[stage - 1],
                instance, {}, store, context = context, resolveRasters = { rig, rasters, checkpoint ->
                    RigModifierRasterAssets.attach(rig, rasters, model.config, checkpoint)
                })
            assertTrue(expected.outputs[stage] == result, "Installed outline worker differs from v1")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    private fun singleEyeDocument(sample: Sample): WorkspaceDocument {
        val artwork = PSD2LivePipeline().readPsd(sample.path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val eyeTags = setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.IRIDES, SemanticTag.EYE_CLOSE, SemanticTag.EYEBROW)
        val original = RigGenerationSource.analyze(artwork, config)
        val removed = original.layers.filter { it.semantic.tag in eyeTags && it.semantic.side == Side.RIGHT }
            .mapTo(HashSet()) { it.source.id }
        assertTrue(removed.isNotEmpty())
        val singleEye = object : SourceArt by artwork {
            override val layers = artwork.layers.filterNot { it.id in removed }
        }
        val measured = RigGenerationSource.analyze(singleEye, config)
        val subject = RecipeSubject.of(measured)
        for (tag in listOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYEBROW)) {
            assertEquals(setOf(Side.LEFT), subject.sides(tag), tag.name)
        }
        val mouthFit = assertNotNull(RigBuilder.rigContext(measured, config, PreviewMeshCache()).fittedMouth(config))
        assertNotNull(mouthFit.source)
        assertFalse(mouthFit.closedLine || mouthFit.layered)
        return WorkspaceDocument(singleEye, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
    }
}
