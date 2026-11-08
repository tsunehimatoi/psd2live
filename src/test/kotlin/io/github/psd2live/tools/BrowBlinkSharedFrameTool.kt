package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrowBlinkSharedFrameTool {
    @Test fun compareBrowBlink() = runBlocking {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 64, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val analysis = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "brow.shape" to setting("PSD2LIVE_BROW_SHAPE", "1").toInt(),
            "brow.lid-coupling" to setting("PSD2LIVE_BROW_COUPLING", "1").toInt(), "brow.blink" to 1, "brow.spring" to 1)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = CliPresetBindings(analysis, config))
        val stack = RigModifierStack.fromJson(planned.rigEdits.modifierStack!!)
        for ((type, version) in versions) assertEquals(version, stack.instances.single { it.type == type }.version, type)
        val blink = stack.instances.single { it.type == "brow.blink" }
        val index = stack.index(blink.id)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(blink.type, 5))
        val selected = blink.copy(version = 5, plugin = artifact.reference)
        val modified = stack.copy(instances = stack.instances.map { if (it.id == blink.id) selected else it })
        val candidate = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val expected = assertNotNull(historical.modifierEvaluation).outputs
        val evaluation = assertNotNull(candidate.modifierEvaluation)
        val actual = evaluation.outputs
        for (stage in stack.instances.indices) {
            val oldMeshes = expected[stage].meshes.associateBy { it.id }
            val changed = actual[stage].meshes.filter { it != oldMeshes[it.id] }
            val oldWarps = expected[stage].deformers.associateBy { it.id }
            val changedWarps = actual[stage].deformers.filter { it != oldWarps[it.id] }
            assertTrue(expected[stage] == actual[stage], "${stack.instances[stage].type}: meshes=${changed.map { it.id }}, warps=${changedWarps.map { it.id }}")
        }
        assertTrue(RigIrCompiler.compile(historical, tileArt = true) == RigIrCompiler.compile(candidate, tileArt = true))
        val root = Files.createTempDirectory("brow-blink-shared-")
        val preferences = Preferences.userRoot().node("brow-blink-shared/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val context = assertNotNull(evaluation.readAlgorithms[selected.id]).context
            for ((type, version) in versions.filterKeys { it in setOf("brow/fitted", "brow.shape", "brow.lid-coupling", "eye/fitted") }) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(version, provider.version)
                assertEquals(stack.instances.single { it.type == type }.plugin, provider.plugin)
            }
            assertTrue(expected[index] == ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), expected[index - 1],
                selected, {}, store, context = context))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareSingleEyeOpenBlink(): Unit = runBlocking {
        compareSingleEyeBlink(cli = false)
        compareSingleEyeBlink(cli = true)
    }

    private suspend fun compareSingleEyeBlink(cli: Boolean) {
        requireTools()
        val document = singleEyeOpenDocument(Sample.fromEnvironment(), meshSpacing = if (cli) 64 else 40)
        val config = PipelineConfig(meshSpacing = if (cli) 64 else 40)
        val analyses = if (cli) RigGenerationSource.prepare(RigGenerationSource.analyze(document.source, config), config) else null
        val aliases = analyses?.let { CliPresetBindings(it, config) }
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3, "mouth/fitted" to 7,
            "mouth.outline" to 10, "mouth.vowel" to 9, "mouth.capture" to 8, "brow.shape" to 4,
            "eye.wide" to 3, "eye.gaze-lid" to 3, "brow.lid-coupling" to 6, "brow.blink" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions, cliBindings = aliases, restoreSourceRotations = cli)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val expected = assertNotNull(baseline.modifierEvaluation)
        for ((type, version) in versions) {
            val entry = expected.entries.single { it.instance.type == type }
            assertEquals(version, entry.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, entry.status, type)
            if (type != "brow.blink") {
                assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, version)).reference, entry.instance.plugin, type)
            }
        }
        assertEquals(cli, stack.instances.single { it.type == "rig.scaffold" }.outputAliases.isNotEmpty())
        val blink = stack.instances.single { it.type == "brow.blink" }
        val stage = stack.index(blink.id)
        for (type in listOf("brow.shape", "brow.lid-coupling", "eye.wide", "eye.gaze-lid")) {
            assertTrue(stack.index(stack.instances.single { it.type == type }.id) < stage, type)
        }
        val before = expected.outputs[stage - 1]
        val after = expected.outputs[stage]
        val brow = after.meshes.single { it.id == "ArtMeshEyebrowL" }
        assertTrue(brow.visible && brow.opacity > 0f)
        val oldBrow = before.meshes.single { it.id == brow.id }
        val eyeOpen = "ParamEyeLOpen"
        val warps = after.deformers.associateBy { it.id }
        val branch = generateSequence(brow.parent) { warps.getValue(it).parent }.map(warps::getValue)
        val meshMotion = (brow.shapes - oldBrow.shapes.toSet()).any { it.parameter == eyeOpen &&
            it.shapes.any { form -> form?.deltas?.shared()?.any { delta -> delta != 0f } == true } } ||
            brow.offsets?.takeIf { it != oldBrow.offsets }?.let { grid -> grid.axes.any { it.parameter == eyeOpen } &&
                grid.cells.any { it.form.deltas.shared().any { delta -> delta != 0f } } } == true
        val oldWarps = before.deformers.associateBy { it.id }
        val warpMotion = branch.filterIsInstance<Deformer.Warp>().filter { it != oldWarps[it.id] }.any { warp ->
            warp.shapes.any { it.parameter == eyeOpen &&
                it.shapes.any { form -> form?.points?.shared()?.any { delta -> delta != 0f } == true } } ||
                warp.lattice?.let { grid -> grid.axes.any { it.parameter == eyeOpen } &&
                    grid.cells.map { it.form }.distinct().size > 1 } == true
        }
        assertTrue(meshMotion || warpMotion, "The left blink must move the surviving brow")
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(blink.type, 4))
        val selected = blink.copy(version = 4, plugin = artifact.reference)
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
            assertTrue(expected.outputs[index] == actual.outputs[index], "CLI=$cli: " + stack.instances[index].type)
        }
        fun exported(model: RigPreviewModel): RigIR {
            val presented = if (aliases != null) CliPresetPresentation.apply(model, assertNotNull(analyses), aliases.moduleParameters, config)
                else model
            return RigIrCompiler.compile(presented, tileArt = true)
        }
        assertTrue(exported(baseline) == exported(preview), "final IR differs")

        val root = Files.createTempDirectory("brow-blink-single-eye-")
        val preferences = Preferences.userRoot().node("brow-blink-single-eye/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigBrowBlinkModifier.fallbackDefinition(RigModifierSource.of(preview))
            val context = assertNotNull(actual.readAlgorithms[blink.id]).context
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
