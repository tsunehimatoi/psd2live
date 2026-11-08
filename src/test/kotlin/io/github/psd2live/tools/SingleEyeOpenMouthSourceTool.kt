package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import org.umamo.format.art.SourceArt
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class SingleEyeOpenMouthSourceTool {
    @Test fun compareEyeEmotion(): Unit = runBlocking {
        val chain = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "mouth.vowel" to 9,
            "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12)
        val controls = RigModules.EYE_EMOTION.defines.mapTo(HashSet()) { it.id }
        compareShapeModule("eye.emotion", 3, chain, controls, setOf("ParamEyeLForm", "ParamEyeLSquint"),
            { source -> RigEyeEmotionModifier.definition(source, modern = true) }) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
        }
    }

    @Test fun compareEyeSmile(): Unit = runBlocking {
        val downstream = mapOf("eye.wide" to 3, "eye.gaze-lid" to 3, "mouth.vowel" to 9, "mouth.capture" to 8,
            "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12)
        val controls = RigModules.EYE_SMILE.defines.mapTo(HashSet()) { it.id }
        compareShapeModule("eye.smile", 3, downstream, controls, emptySet(), RigEyeSmileModifier::modernDefinition) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
        }
    }

    @Test fun compareWideEye(): Unit = runBlocking {
        val downstream = mapOf("eye.gaze-lid" to 3, "mouth.vowel" to 9, "mouth.capture" to 8,
            "mouth.jaw" to 11, "mouth.cheek-lid" to 11)
        compareShapeModule("eye.wide", 3, downstream, emptySet(), emptySet(), RigEyeWideModifier::modernDefinition) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
            val open = "ParamEyeLOpen"
            assertTrue(after.parameters.single { it.id == open }.max > before.parameters.single { it.id == open }.max)
        }
    }

    @Test fun compareGazeLid(): Unit = runBlocking {
        val speech = mapOf("mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11)
        val controls = RigModules.EYE_GAZE_LID.defines.mapTo(HashSet()) { it.id }
        compareShapeModule("eye.gaze-lid", 3, speech, controls, emptySet(),
            { source -> RigEyeGazeLidModifier.definition(source, modern = true) }) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
            assertNotEquals(before.parameterLinks, after.parameterLinks)
        }
    }

    @Test fun compareVowel(): Unit = runBlocking {
        val vowels = setOf("ParamA", "ParamI", "ParamU", "ParamE", "ParamO")
        compareShapeModule("mouth.vowel", 9, emptyMap(), vowels, vowels, RigMouthVowelModifier::fallbackFrameDefinition)
    }

    @Test fun compareCapture(): Unit = runBlocking {
        val controls = RigModules.MOUTH_CAPTURE.defines.mapTo(HashSet()) { it.id }
        val moved = setOf("ParamMouthX", "ParamMouthPuckerWiden", "ParamMouthFunnel")
        compareShapeModule("mouth.capture", 8, mapOf("mouth.vowel" to 9), controls, moved,
            RigMouthCaptureModifier::fallbackFrameDefinition)
    }

    @Test fun compareJaw(): Unit = runBlocking {
        compareShapeModule("mouth.jaw", 11, mapOf("mouth.vowel" to 9, "mouth.capture" to 8),
            emptySet(), setOf("ParamMouthOpenY"), RigMouthJawModifier::fallbackFrameDefinition)
    }

    @Test fun compareCheekLid(): Unit = runBlocking {
        compareShapeModule("mouth.cheek-lid", 11, mapOf("mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11),
            emptySet(), emptySet(), RigMouthCheekLidModifier::fallbackFrameDefinition, ::verifyCheekLidContribution)
    }

    @Test fun compareSpring(): Unit = runBlocking {
        val preceding = mapOf("mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11)
        val control = setOf("ParamMouthSpring")
        compareShapeModule("mouth.spring", 12, preceding, control, control, RigMouthSpringModifier::fallbackFrameDefinition) { before, after ->
            assertTrue(before.meshes.none { it.id.startsWith("ArtMeshTooth") || it.id.startsWith("ArtMeshTongue") })
            assertTrue(before.physics.groups.none { group -> group.outputs.any { it.parameter in control } })
            val group = (after.physics.groups - before.physics.groups.toSet()).single()
            assertEquals(mapOf("ParamMouthOpenY" to 80f, "ParamMouthForm" to 20f), group.inputs.associate { it.parameter to it.weight })
            val output = group.outputs.single()
            assertEquals(control.single(), output.parameter)
            assertTrue(output.scale > 0f && output.weight > 0f)
            assertEquals(2, group.segments.size)
        }
    }

    private fun verifyCheekLidContribution(before: RigIR, after: RigIR) {
        val original = before.meshes.associateBy { it.id }
        val changed = after.meshes.filter { it != original[it.id] }
        assertEquals(setOf("ArtMeshEyelashL", "ArtMeshEyewhiteL"), changed.mapTo(HashSet()) { it.id })
        val frames = after.deformers.associateBy { it.id }
        val oldFrames = before.deformers.associateBy { it.id }
        val mouth = "ParamMouthForm"
        fun <F> varies(grid: KeyGrid<F>?): Boolean {
            val axis = grid?.axes?.indexOfFirst { it.parameter == mouth } ?: return false
            if (axis < 0) return false
            return grid.cells.groupBy { cell -> cell.coordinate.toArray().filterIndexed { index, _ -> index != axis } }
                .values.any { cells -> cells.map { it.form }.distinct().size > 1 }
        }
        for (mesh in changed) {
            val previous = original.getValue(mesh.id)
            val local = mesh.offsets != previous.offsets && varies(mesh.offsets) ||
                mesh.shapes.any { it.parameter == mouth && it !in previous.shapes &&
                    it.shapes.any { shape -> shape?.deltas?.toArray()?.any { delta -> delta != 0f } == true } }
            val inherited = generateSequence(mesh.parent) { frames[it]?.parent }.any { id ->
                val warp = frames[id] as? Deformer.Warp
                warp != null && warp != oldFrames[id] && varies(warp.lattice)
            }
            assertTrue(local || inherited, "${mesh.id} must move with $mouth")
        }
    }

    private suspend fun compareShapeModule(type: String, version: Int, predecessors: Map<String, Int>,
                                           controls: Set<String>, moving: Set<String>,
                                           sourceDefinition: (RigModifierSource) -> RigModifierDefinition,
                                           verifyContribution: (RigIR, RigIR) -> Unit = { _, _ -> }) {
        requireTools()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3,
            "mouth/fitted" to 7, "mouth.outline" to 10, type to 1) + predecessors
        val (planned, baseline) = WorkspacePresetInitialization.prepare(singleEyeDocument(Sample.fromEnvironment()), WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val module = stack.instances.single { it.type == type }
        val stage = stack.index(module.id)
        val expected = assertNotNull(baseline.modifierEvaluation)
        for (entry in expected.entries.filter { it.instance.type in versions }) {
            assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        }
        val before = expected.outputs[stage - 1]
        val after = expected.outputs[stage]
        verifyContribution(before, after)
        assertEquals(controls, after.parameters.mapTo(HashSet()) { it.id } - before.parameters.map { it.id }.toSet())
        val oldMeshes = before.meshes.associateBy { it.id }
        val moved = buildSet {
            for (mesh in after.meshes) {
                val old = oldMeshes[mesh.id]
                for (binding in mesh.shapes - old?.shapes.orEmpty().toSet()) {
                    if (binding.shapes.any { form -> form?.deltas?.toArray()?.any { it != 0f } == true }) add(binding.parameter)
                }
                val offsets = mesh.offsets ?: continue
                if (offsets != old?.offsets && offsets.cells.any { it.form.deltas.toArray().any { delta -> delta != 0f } }) {
                    addAll(offsets.axes.map { it.parameter })
                }
            }
        }
        assertTrue(moved.containsAll(moving), "$type must contribute nonzero source geometry for ${moving - moved}")
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(type, version))
        val selected = module.copy(version = version, plugin = artifact.reference)
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
            "Complete single-eye $type IR differs")

        val instance = actual.entries[stage].instance
        val definition = sourceDefinition(RigModifierSource.of(candidate))
        val context = actual.readAlgorithms[instance.id]?.context ?: definition.authorContext(instance).withUpstream(actual.entries.take(stage)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        val root = Files.createTempDirectory("single-eye-open-package-")
        val preferences = Preferences.userRoot().node("single-eye-open-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val result = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), actual.outputs[stage - 1],
                instance, {}, store, context = context, declaredParameters = definition.parameters(instance))
            assertTrue(expected.outputs[stage] == result, "Installed $type worker differs from v1")
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
        assertEquals(9, stack.instances.single { it.type == "mouth.vowel" }.version)
        assertEquals(8, stack.instances.single { it.type == "mouth.capture" }.version)
        assertEquals(11, stack.instances.single { it.type == "mouth.jaw" }.version)
        assertEquals(11, stack.instances.single { it.type == "mouth.cheek-lid" }.version)
        assertEquals(12, stack.instances.single { it.type == "mouth.spring" }.version)
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
