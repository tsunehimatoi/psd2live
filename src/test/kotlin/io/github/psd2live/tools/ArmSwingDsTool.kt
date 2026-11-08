package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class ArmSwingDsTool {
    @Test fun compareWorkspaceArms(): Unit = runBlocking { compareArms(layered = false) }

    @Test fun compareLayeredMouthArms(): Unit = runBlocking { compareArms(layered = true) }

    private suspend fun compareArms(layered: Boolean) {
        requireTools()
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val config = PipelineConfig(meshSpacing = 40, rigEdits = overlay)
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val document = if (layered) layeredMouthDocument(source, config) else
            WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay,
                WorkspaceSettingsCodec.encode(config))
        val layeredPrefix = if (layered) mapOf("mouth/fitted" to 8, "mouth.outline" to 11, "mouth.vowel" to 8,
            "mouth.capture" to 7, "mouth.jaw" to 10) else emptyMap()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to 8, "mouth.cheek-lid" to 8, "body.arms" to 1,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5, "eye.squash" to 5,
            "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6) +
            if (layered) layeredPrefix + mapOf("mouth.cheek-lid" to 1, "mouth.spring" to 1, "body.bust" to 2) else emptyMap()
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))

        val historicalEvaluation = assertNotNull(historical.modifierEvaluation)
        for ((type, version) in versions) {
            val provider = historicalEvaluation.entries.single { it.instance.type == type }
            assertEquals(version, provider.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, provider.status, type)
        }
        if (layered) {
            val fit = assertNotNull(RigBuilder.rigContext(historical.analysis, config).fittedMouth(config))
            assertTrue(fit.layered && fit.rig?.fit?.layered != null)
        }
        val arms = historical.analysis.layers.filter { it.semantic.tag == SemanticTag.HANDWEAR && it.opaquePixels > 0 }
        assertEquals(listOf(Side.LEFT, Side.RIGHT), arms.mapNotNull { it.semantic.side }.sortedBy { it.name })
        val entry = stack.instances.single { it.type == "body.arms" }
        val index = stack.index(entry.id)
        for (type in listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.cheek-lid")) {
            assertTrue(stack.index(stack.instances.single { it.type == type }.id) < index, type)
        }
        for ((type, version) in layeredPrefix) {
            assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, version)).reference,
                stack.instances.single { it.type == type }.plugin, type)
        }
        assertTrue(entry.outputAliases.isEmpty())
        val scaffold = stack.instances.single { it.type == "rig.scaffold" }
        val before = historicalEvaluation.outputs[index - 1]
        val after = historicalEvaluation.outputs[index]
        val oldWarps = before.deformers.associateBy { it.id }
        val changed = after.deformers.filter { it != oldWarps[it.id] }.map { it as Deformer.Warp }
            .sortedBy { scaffold.localOutputName(it.id) }
        assertEquals(listOf("shared/deformer:DeformArmHang_L", "shared/deformer:DeformArmHang_R"),
            changed.map { scaffold.localOutputName(it.id) })
        val swing = listOf("ParamArmLSwing", "ParamArmRSwing")
        for ((warp, parameter) in changed.zip(swing)) {
            val original = oldWarps.getValue(warp.id) as Deformer.Warp
            assertEquals(original, warp.copy(shapes = warp.shapes.filterNot { it.parameter == parameter }))
            val shape = warp.shapes.single { it.parameter == parameter }
            assertTrue(shape.shapes.filterNotNull().any { it.points.shared().any { point -> point != 0f } })
            assertTrue(after.meshes.any { it.parent == warp.id && it.visible && it.opacity > 0f })
        }
        assertEquals(before.meshes, after.meshes)
        val additions = after.physics.groups.filter { it !in before.physics.groups }
        assertEquals(swing, additions.flatMap { group -> group.outputs.map { it.parameter } }.sorted())
        assertTrue(additions.all { group -> group.inputs.any { it.weight > 0f } && group.outputs.single().weight > 0f })

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, 3))
        val selected = entry.copy(version = 3, plugin = artifact.reference)
        val next = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
        val model = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson())))
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        for (stage in stack.instances.indices) {
            assertTrue(historicalEvaluation.outputs[stage] == actual.outputs[stage], stack.instances[stage].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(model, tileArt = true))

        val directory = Files.createTempDirectory("arm-swing-ds-")
        val preferences = Preferences.userRoot().node("arm-swing-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigArmSwingModifier.definition(RigModifierSource.of(model), modern = true)
            val context = assertNotNull(actual.readAlgorithms[entry.id]).context
            assertEquals(after, ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                selected, {}, store, context = context, declaredParameters = definition.parameters(selected)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun compareSingleEyeOpenArms(): Unit = runBlocking {
        compareSingleEyeArms(cli = false)
        compareSingleEyeArms(cli = true)
    }

    private suspend fun compareSingleEyeArms(cli: Boolean) {
        requireTools()
        val document = singleEyeOpenDocument(Sample.fromEnvironment(), meshSpacing = if (cli) 64 else 40)
        val config = PipelineConfig(meshSpacing = if (cli) 64 else 40)
        val analyses = if (cli) RigGenerationSource.prepare(RigGenerationSource.analyze(document.source, config), config) else null
        val aliases = analyses?.let { CliPresetBindings(it, config) }
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3, "mouth/fitted" to 7,
            "mouth.outline" to 10, "mouth.vowel" to 9, "mouth.capture" to 8, "brow.shape" to 4,
            "eye.wide" to 3, "eye.gaze-lid" to 3, "brow.lid-coupling" to 6, "brow.blink" to 4, "brow.spring" to 5, "body.arms" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(), requestedVersions = versions, cliBindings = aliases, restoreSourceRotations = cli)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val expected = assertNotNull(baseline.modifierEvaluation)
        for ((type, version) in versions) {
            val entry = expected.entries.single { it.instance.type == type }
            assertEquals(version, entry.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, entry.status, type)
            if (type != "body.arms") {
                assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, version)).reference, entry.instance.plugin, type)
            }
        }
        assertEquals(cli, stack.instances.single { it.type == "rig.scaffold" }.outputAliases.isNotEmpty())
        val armLayers = baseline.analysis.layers.filter { it.semantic.tag == SemanticTag.HANDWEAR && it.opaquePixels > 0 }
        assertEquals(setOf(Side.LEFT, Side.RIGHT), armLayers.map { it.semantic.side }.toSet(),
            "CLI=$cli visible handwear: " + armLayers.map { it.source.id to it.semantic.side })
        val arms = stack.instances.single { it.type == "body.arms" }
        val stage = stack.index(arms.id)
        val before = expected.outputs[stage - 1]
        val after = expected.outputs[stage]
        val oldWarps = before.deformers.associateBy { it.id }
        val swing = listOf("ParamArmLSwing", "ParamArmRSwing")
        for ((side, parameter) in listOf(Side.LEFT, Side.RIGHT).zip(swing)) {
            val warp = after.deformers.filterIsInstance<Deformer.Warp>().single {
                it != oldWarps[it.id] && it.shapes.any { shape -> shape.parameter == parameter }
            }
            assertTrue(warp.shapes.single { it.parameter == parameter }.shapes.filterNotNull()
                .any { it.points.shared().any { point -> point != 0f } })
            val layerIds = armLayers.filter { it.semantic.side == side }.map { it.source.id.raw }
            assertTrue(after.meshes.any { it.parent == warp.id && it.visible && it.opacity > 0f &&
                baseline.rig.ownLayerIdByDrawableId[it.id] in layerIds }, "CLI=$cli visible $side arm carrier")
        }
        val additions = after.physics.groups.filter { it !in before.physics.groups }
        assertEquals(swing, additions.flatMap { group -> group.outputs.map { it.parameter } }.sorted())
        assertTrue(additions.all { group -> group.inputs.any { it.weight > 0f } && group.outputs.single().weight > 0f })

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(arms.type, 3))
        val selected = arms.copy(version = 3, plugin = artifact.reference)
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

        val root = Files.createTempDirectory("arm-single-eye-")
        val preferences = Preferences.userRoot().node("arm-single-eye/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigArmSwingModifier.definition(RigModifierSource.of(preview), modern = true)
            val context = assertNotNull(actual.readAlgorithms[arms.id]).context
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
