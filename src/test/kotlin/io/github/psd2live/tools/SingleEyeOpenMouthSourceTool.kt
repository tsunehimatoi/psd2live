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
    @Test fun compareVowel(): Unit = runBlocking {
        requireTools()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3,
            "mouth/fitted" to 7, "mouth.outline" to 10, "mouth.vowel" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(singleEyeDocument(Sample.fromEnvironment()), WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val vowel = stack.instances.single { it.type == "mouth.vowel" }
        val stage = stack.index(vowel.id)
        val expected = assertNotNull(baseline.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, expected.entries[stage].status)
        val ids = setOf("ParamA", "ParamI", "ParamU", "ParamE", "ParamO")
        assertTrue(expected.outputs[stage - 1].parameters.none { it.id in ids })
        assertEquals(ids, expected.outputs[stage].parameters.filter { it.id in ids }.mapTo(HashSet()) { it.id })
        val voiced = expected.outputs[stage].meshes.flatMap { it.shapes }.filter { binding ->
            binding.parameter in ids && binding.shapes.any { form -> form?.deltas?.toArray()?.any { it != 0f } == true }
        }.mapTo(HashSet()) { it.parameter }
        assertEquals(ids, voiced, "Every vowel must contribute nonzero source geometry")
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(vowel.type, 9))
        val selected = vowel.copy(version = 9, plugin = artifact.reference)
        val modified = stack.copy(instances = stack.instances.map { if (it.id == selected.id) selected else it })
        val candidate = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val actual = assertNotNull(candidate.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        for (index in stack.instances.indices) {
            val before = expected.outputs[index]
            val after = actual.outputs[index]
            if (before == after) continue
            val meshes = before.meshes.associateBy { it.id }
            fail("${stack.instances[index].type} differs, meshes=${after.meshes.filter { it != meshes[it.id] }.map { it.id }}")
        }
        assertTrue(RigIrCompiler.compile(baseline, tileArt = true) == RigIrCompiler.compile(candidate, tileArt = true),
            "Complete single-eye vowel IR differs")

        val instance = actual.entries[stage].instance
        val definition = RigMouthVowelModifier.fallbackFrameDefinition(RigModifierSource.of(candidate))
        val context = definition.authorContext(instance).withUpstream(actual.entries.take(stage)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        val root = Files.createTempDirectory("single-eye-open-vowel-package-")
        val preferences = Preferences.userRoot().node("single-eye-open-vowel/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val result = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), actual.outputs[stage - 1],
                instance, {}, store, context = context, declaredParameters = definition.parameters(instance))
            assertTrue(expected.outputs[stage] == result, "Installed vowel worker differs from v1")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareRawSingleEyePreset() {
        requireTools()
        val sample = Sample.fromEnvironment()
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        val config = PipelineConfig()
        val source = singleEyeArtwork(pipeline.readPsd(sample.path), config)
        val expected = RigIrCompiler.compile(pipeline.buildPreview(source, config), tileArt = true)
        val preview = pipeline.buildSourceExport(source, config)
        val actual = RigIrCompiler.compile(preview, tileArt = true)
        val stack = RigModifierStack.fromJson(assertNotNull(preview.config.rigEdits.modifierStack))
        assertEquals(7, stack.instances.single { it.type == "mouth/fitted" }.version)
        assertEquals(10, stack.instances.single { it.type == "mouth.outline" }.version)
        fun differences(a: Any, b: Any): List<String> = a.javaClass.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { it.isAccessible = true; it.get(a) != it.get(b) }.map { it.name }
        val report = buildString {
            appendLine("IR: ${differences(expected, actual)}")
            val meshes = expected.meshes.associateBy { it.id }
            for (mesh in actual.meshes) meshes[mesh.id]?.takeIf { it != mesh }?.let { old ->
                appendLine("${mesh.id}: ${differences(old, mesh)}; geometry: ${old.geometry?.let { a -> mesh.geometry?.let { b -> differences(a, b) } }}; parents: ${old.parent} -> ${mesh.parent}")
                appendLine("  offsets ${old.offsets?.axes?.map { it.parameter }} -> ${mesh.offsets?.axes?.map { it.parameter }}")
                appendLine("  shapes ${old.shapes.map { it.parameter }} -> ${mesh.shapes.map { it.parameter }}")
                appendLine("  positions ${old.geometry?.positions?.toArray()?.take(8)} -> ${mesh.geometry?.positions?.toArray()?.take(8)}")
            }
            val deformers = expected.deformers.associateBy { it.id }
            for (frame in actual.deformers) deformers[frame.id]?.takeIf { it != frame }?.let { old ->
                appendLine("${frame.id}: ${differences(old, frame)}")
                if (old is io.github.psd2live.format.model.Deformer.Rotation && frame is io.github.psd2live.format.model.Deformer.Rotation)
                    appendLine("  angle ${old.baseAngle} -> ${frame.baseAngle}; handle ${old.handleLength} -> ${frame.handleLength}")
            }
            val order = expected.deformers.map { it.id }.zip(actual.deformers.map { it.id })
                .mapIndexedNotNull { index, (a, b) -> if (a == b) null else "$index: $a -> $b" }
            if (order.isNotEmpty()) appendLine("Deformer order: $order")
            appendLine("Missing deformers: ${deformers.keys - actual.deformers.map { it.id }.toSet()}")
            appendLine("Extra deformers: ${actual.deformers.map { it.id }.toSet() - deformers.keys}")
        }
        output("single-eye-open-mouth").resolve("raw-${sample.name}.txt").writeText(report)
        assertTrue(expected == actual, report)
        val exported = pipeline.export(preview, sample.path.fileName.toString(), output("single-eye-open-mouth/readback").toPath())
        assertTrue(exported.exportedFiles.any { it.path.fileName.toString().endsWith(".cmo3") })
        assertTrue(exported.exportedFiles.any { it.path.fileName.toString().endsWith(".moc3") })
    }

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
        val singleEye = singleEyeArtwork(artwork, config)
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

    private fun singleEyeArtwork(artwork: SourceArt, config: PipelineConfig): SourceArt {
        val eyeTags = setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.IRIDES, SemanticTag.EYE_CLOSE, SemanticTag.EYEBROW)
        val original = RigGenerationSource.analyze(artwork, config)
        val removed = original.layers.filter { it.semantic.tag in eyeTags && it.semantic.side == Side.RIGHT }
            .mapTo(HashSet()) { it.source.id }
        assertTrue(removed.isNotEmpty())
        return object : SourceArt by artwork {
            override val layers = artwork.layers.filterNot { it.id in removed }
        }
    }
}
