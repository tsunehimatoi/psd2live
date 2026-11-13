package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class FaceNeckDsTool {
    @Test fun compareWorkspaceNeck() = runBlocking {
        requireTools()
        val config = WorkspacePresetBaseline.configure(PipelineConfig(meshSpacing = 40), "modern")
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val (planned, defaultModel) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset.forMode(WorkspaceInitialModifiers.DEFAULT).copy(clothing = false))
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val entry = stack.instances.single { it.type == "face.neck" }
        val versions = stack.instances.associate { it.type to it.version }
        assertEquals(4, entry.version, "The verified fresh DS cohort must select source neck v4")
        assertEquals(6, versions["mouth/fitted"])
        assertEquals(8, versions["mouth.outline"])
        assertEquals(BuiltinModifierPlugins.reference(entry.type, 4), entry.plugin)
        val cache = RigStageCache.enabled
        val cold = try {
            RigStageCache.enabled = false
            WorkspacePreviewBuilder().build(planned)
        } finally { RigStageCache.enabled = cache }
        assertEquals(stack, RigModifierStack.fromJson(assertNotNull(cold.config.rigEdits.modifierStack)))
        assertEquals(assertNotNull(defaultModel.modifierEvaluation).outputs, assertNotNull(cold.modifierEvaluation).outputs)
        assertEquals(RigIrCompiler.compile(defaultModel, tileArt = true), RigIrCompiler.compile(cold, tileArt = true))
        val index = stack.index(entry.id)
        suspend fun select(version: Int): RigPreviewModel {
            val reference = assertNotNull(BuiltinModifierPlugins.reference(entry.type, version))
            val selected = entry.copy(version = version, plugin = reference)
            val modified = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
            return WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        }
        val v2Model = if (entry.version == 2) defaultModel else select(2)
        val expected = assertNotNull(v2Model.modifierEvaluation)
        val local = assertNotNull(expected.readAlgorithms[entry.id]).context
        val before = expected.outputs[index - 1]
        val after = expected.outputs[index]
        val touched = after.meshes.filter { mesh ->
            before.meshes.singleOrNull { it.id == mesh.id } != mesh
        }.map { it.id }
        val report = output("neck-ds").resolve("baseline.txt")
        report.writeText("Versions: $versions\nTouched meshes: $touched\nContext: ${local.resources.keys}\nFinal neck tracks: " +
            expected.outputs.last().meshes.filter { it.id in touched }.associate { it.id to it.shapes.map { shape -> shape.parameter } })
        assertEquals(listOf("ArtMeshNeck"), touched)
        assertTrue("source_meshes" in local.resources)
        val finalNeck = expected.outputs.last().meshes.single { it.id == touched.single() }
        assertTrue(finalNeck.shapes.any { it.parameter == "ParamAngleZ" }, "The final neck must retain the tilt")
        val historical = select(1)
        val legacy = assertNotNull(historical.modifierEvaluation)
        val compiled = RigIrCompiler.compile(historical, tileArt = true)
        for (version in listOf(2, 3, 4)) {
            val model = if (version == 2) v2Model else if (entry.version == version) defaultModel else select(version)
            val evaluated = assertNotNull(model.modifierEvaluation)
            val selected = evaluated.entries[index].instance
            assertEquals(version, selected.version)
            assertEquals(ModifierStatus.ACTIVE, evaluated.entries[index].status)
            val actual = evaluated.outputs[index]
            val stage = legacy.outputs.indices.firstOrNull { legacy.outputs[it] != evaluated.outputs[it] }
            val changes = actual.meshes.zip(legacy.outputs[index].meshes).filter { (a, b) -> a != b }.map { (a, b) ->
                val fields = a.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .filter { it.isAccessible = true; it.get(a) != it.get(b) }.map { it.name }
                "${a.id}:$fields"
            }
            val complete = compiled == RigIrCompiler.compile(model, tileArt = true)
            report.appendText("\nV$version vs v1: first difference=$stage meshes=$changes complete=$complete")
            if (version >= 3) {
                assertNull(stage, "v$version differs at stage $stage: $changes")
                assertTrue(complete, "v$version final IR differs")
            }
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val capture = assertNotNull(evaluated.readAlgorithms[entry.id]).context
            report.appendText(" resources=" + capture.resources.keys)
            val root = Files.createTempDirectory("neck-ds-")
            val preferences = Preferences.userRoot().node("neck-ds/" + UUID.randomUUID())
            val store = ModifierPluginStore(root.resolve("installed"), preferences)
            try {
                BundledAlgorithmArtifacts.retain(store, listOf(artifact))
                val worker = store.snapshot().single()
                val source = RigModifierSource.of(model)
                val definition = when (version) {
                    2 -> RigNeckModifier.definition(source)
                    3 -> RigNeckModifier.foldedDefinition(source)
                    else -> RigNeckSourceModifier.definition(source)
                }
                if (version == 4) assertEquals(setOf("source_meshes"), capture.resources.keys)
                val replay = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), evaluated.outputs[index - 1],
                    selected, {}, store, context = capture, declaredParameters = definition.parameters(selected))
                assertTrue(actual == replay, "installed JVM v$version: IR differs")
            } finally {
                ModifierPluginWorkers.stop(artifact.reference)
                preferences.removeNode()
                ModifierPluginStore.deleteTree(root)
            }
        }
    }

    @Test fun compareSingleEyeOpenNeck(): Unit = runBlocking {
        compareSingleEyeNeck(cli = false)
        compareSingleEyeNeck(cli = true)
    }

    private suspend fun compareSingleEyeNeck(cli: Boolean) {
        requireTools()
        val document = singleEyeOpenDocument(Sample.fromEnvironment(), meshSpacing = if (cli) 64 else 40)
        val config = PipelineConfig(meshSpacing = if (cli) 64 else 40)
        val analyses = if (cli) RigGenerationSource.prepare(RigGenerationSource.analyze(document.source, config), config) else null
        val aliases = analyses?.let { CliPresetBindings(it, config) }
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3, "mouth/fitted" to 7,
            "mouth.outline" to 10, "mouth.vowel" to 9, "mouth.capture" to 8, "brow.shape" to 4,
            "eye.wide" to 3, "eye.gaze-lid" to 3, "brow.lid-coupling" to 6, "brow.blink" to 4, "brow.spring" to 5, "face.neck" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = aliases, restoreSourceRotations = cli)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val expected = assertNotNull(baseline.modifierEvaluation)
        for ((type, version) in versions) {
            val entry = expected.entries.single { it.instance.type == type }
            assertEquals(version, entry.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, entry.status, type)
            if (type != "face.neck") {
                assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, version)).reference, entry.instance.plugin, type)
            }
        }
        assertEquals(cli, stack.instances.single { it.type == "rig.scaffold" }.outputAliases.isNotEmpty())
        val neck = stack.instances.single { it.type == "face.neck" }
        val stage = stack.index(neck.id)
        val priorBindings = stack.instances.take(stage).map { it.type }.toSet()
        assertTrue("face/fitted" in priorBindings)
        assertTrue("brow.shape" !in priorBindings)
        val before = expected.outputs[stage - 1]
        val after = expected.outputs[stage]
        val neckLayers = baseline.analysis.layers.filter { it.semantic.tag == SemanticTag.NECK && it.opaquePixels > 0 }
            .mapTo(hashSetOf()) { it.source.id.raw }
        assertTrue(neckLayers.isNotEmpty(), "The single-eye fixture must have a visible neck")
        val byMesh = baseline.rig.ownLayerIdByDrawableId
        val oldMeshes = before.meshes.associateBy { it.id }
        val neckMeshes = after.meshes.filter { byMesh[it.id] in neckLayers }
        val axis = "ParamAngleZ"
        val moved = neckMeshes.any { mesh ->
            val old = oldMeshes[mesh.id]
            mesh.visible && mesh.opacity > 0f && (
                (mesh.shapes - old?.shapes.orEmpty().toSet()).any { binding ->
                    binding.parameter == axis && binding.shapes.any { form -> form?.deltas?.shared()?.any { it != 0f } == true }
                } || mesh.offsets?.takeIf { it != old?.offsets }?.let { grid ->
                    grid.axes.any { it.parameter == axis } && grid.cells.any { it.form.deltas.shared().any { delta -> delta != 0f } }
                } == true
            )
        }
        assertTrue(moved, "CLI=$cli neck meshes: " + neckMeshes.map { mesh ->
            "${mesh.id}: visible=${mesh.visible}, changed=${mesh != oldMeshes[mesh.id]}, " +
                "shapes=${mesh.shapes.map { it.parameter }}, offsets=${mesh.offsets?.axes?.map { it.parameter }}"
        })
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(neck.type, 3))
        val selected = neck.copy(version = 3, plugin = artifact.reference)
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
            val prior = expected.outputs[index]
            val next = actual.outputs[index]
            if (prior == next) continue
            val old = prior.meshes.associateBy { it.id }
            val changes = next.meshes.filter { it != old[it.id] }.map { mesh ->
                val previous = old[mesh.id]
                "${mesh.id} (parent=${mesh.parent != previous?.parent}, geometry=${mesh.geometry != previous?.geometry}, " +
                    "offsets=${mesh.offsets != previous?.offsets}, shapes=${mesh.shapes != previous?.shapes})"
            }
            fail("CLI=$cli ${stack.instances[index].type} differs, meshes=$changes")
        }
        fun exported(model: RigPreviewModel): RigIR {
            val presented = if (aliases != null) CliPresetPresentation.apply(model, assertNotNull(analyses), aliases.moduleParameters, config)
                else model
            return RigIrCompiler.compile(presented, tileArt = true)
        }
        assertTrue(exported(baseline) == exported(preview), "final IR differs")

        val root = Files.createTempDirectory("neck-single-eye-")
        val preferences = Preferences.userRoot().node("neck-single-eye/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigNeckModifier.foldedDefinition(RigModifierSource.of(preview))
            val context = assertNotNull(actual.readAlgorithms[neck.id]).context
            assertEquals(setOf("before", "after"), context.resources.keys)
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
