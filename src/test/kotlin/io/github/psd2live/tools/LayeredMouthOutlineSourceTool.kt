package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.coroutines.runBlocking
import org.umamo.format.art.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.math.sqrt
import kotlin.test.*

class LayeredMouthOutlineSourceTool {
    private enum class Upgrade(val type: String, val version: Int) {
        OUTLINE("mouth.outline", 9), FITTED("mouth/fitted", 8), VOWEL("mouth.vowel", 8),
        CAPTURE("mouth.capture", 7), JAW("mouth.jaw", 10),
    }

    @Test fun compareLayeredOutlineWithLegacyFittedMouth(): Unit = runBlocking { compareLayered(Upgrade.OUTLINE) }

    @Test fun compareLayeredFittedMouthWithLegacyOutline(): Unit = runBlocking { compareLayered(Upgrade.FITTED) }

    @Test fun compareLayeredVowelsWithSharedFittedMouth(): Unit = runBlocking { compareLayered(Upgrade.VOWEL) }

    @Test fun compareLayeredCaptureWithSharedVowels(): Unit = runBlocking { compareLayered(Upgrade.CAPTURE) }

    @Test fun compareLayeredJawWithSharedCapture(): Unit = runBlocking { compareLayered(Upgrade.JAW) }

    @Test fun recommendedLayeredMouthUsesSharedSources(): Unit = runBlocking {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = layeredDocument(source, config)
        val versions = mapOf("mouth/fitted" to 8, "mouth.outline" to 11, "mouth.vowel" to 8, "mouth.capture" to 7, "mouth.jaw" to 10)
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val (planned, recommended) = try {
            RigBuildProfile.recording = true
            WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct, WorkspaceInitialPreset(clothing = false))
        } finally {
            RigBuildProfile.recording = recording
        }
        assertTrue(legacyTemplateConsumers().intersect(versions.keys).isEmpty())
        val actual = assertNotNull(recommended.modifierEvaluation)
        val selected = actual.entries.filter { it.instance.type in versions }
        assertEquals(versions, selected.associate { it.instance.type to it.instance.version })
        assertTrue(selected.all { it.status == ModifierStatus.ACTIVE })
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        val legacy = stack.copy(instances = stack.instances.map { entry ->
            if (entry.type in versions) entry.copy(version = 1, plugin = BuiltinModifierPlugins.reference(entry.type, 1))
            else entry
        })
        val historical = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = legacy.toJson())))
        val reference = assertNotNull(historical.modifierEvaluation)
        for (index in stack.instances.indices) {
            assertEquals(reference.outputs[index], actual.outputs[index], stack.instances[index].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(recommended, tileArt = true))

        val outline = selected.single { it.instance.type == "mouth.outline" }.instance
        val index = stack.index(outline.id)
        val definition = RigFittedMouthOutlineDefinition.layeredSourceDefinition(RigModifierSource.of(recommended))
        val context = assertNotNull(actual.authorContexts[outline.id]).withUpstream(actual.entries.take(index)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        assertEquals(8, context.upstream.single { it.type == "mouth/fitted" }.version)
        assertEquals(setOf("before", "after"), context.resources.keys)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(outline.type, outline.version))
        val root = Files.createTempDirectory("layered-mouth-shared-outline-")
        val preferences = Preferences.userRoot().node("layered-mouth-outline/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            assertEquals(reference.outputs[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                actual.outputs[index - 1], outline, {}, store, context = context,
                declaredParameters = definition.parameters(outline)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    private suspend fun compareLayered(upgrade: Upgrade) {
        requireTools()
        val source = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = layeredDocument(source, config)
        val fittedVersion = if (upgrade in setOf(Upgrade.VOWEL, Upgrade.CAPTURE, Upgrade.JAW)) 8 else 1
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to fittedVersion,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5,
            "eye.squash" to 5, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "mouth.outline" to 1, "mouth.vowel" to (if (upgrade in setOf(Upgrade.CAPTURE, Upgrade.JAW)) 8 else 1),
            "mouth.capture" to (if (upgrade == Upgrade.JAW) 7 else 1), "mouth.jaw" to 1,
            "mouth.cheek-lid" to 1, "mouth.spring" to 1)
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val outline = stack.instances.single { it.type == "mouth.outline" }
        val fitted = stack.instances.single { it.type == "mouth/fitted" }
        val index = stack.index(outline.id)
        val fittedIndex = stack.index(fitted.id)
        val fit = assertNotNull(RigBuilder.rigContext(reference.analysis, config).fittedMouth(config))
        assertTrue(fit.layered && fit.rig?.fit?.layered != null, fit.result.reason)
        val referenceStages = assertNotNull(reference.modifierEvaluation).outputs
        assertEquals(setOf(PartRole.UPPER_LINE, PartRole.LOWER_LINE),
            reference.analysis.layers.filter { it.opaquePixels > 0 && it.source.id.raw.startsWith("test-lip-line-") }
                .mapNotNull { it.semantic.role }.toSet())
        val beforeOutline = referenceStages[index - 1]
        val afterOutline = referenceStages[index]
        assertEquals(beforeOutline.meshes.map { it.id }, afterOutline.meshes.map { it.id })
        assertEquals(beforeOutline.textures, afterOutline.textures)
        val beforeMeshes = beforeOutline.meshes.associateBy { it.id }
        val beforeNodes = beforeOutline.deformers.associateBy { it.id }
        assertEquals(19, afterOutline.meshes.count { it != beforeMeshes[it.id] })
        assertEquals(18, afterOutline.deformers.count { it != beforeNodes[it.id] })
        val fittedBefore = referenceStages[fittedIndex - 1].meshes.associateBy { it.id }
        val authored = reference.rig.layerIdByDrawableId.filterValues { it.startsWith("test-lip-line-") }.keys
        assertEquals(2, authored.size)
        assertTrue(referenceStages[fittedIndex].meshes.filter { it.id in authored }.all { it != fittedBefore[it.id] })

        val upgraded = stack.instances.single { it.type == upgrade.type }
        val stage = stack.index(upgraded.id)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(upgrade.type, upgrade.version))
        fun candidate(version: Int): WorkspaceDocument {
            val reference = assertNotNull(BundledAlgorithmArtifacts.find(upgrade.type, version)).reference
            val replaced = stack.copy(instances = stack.instances.map { entry ->
                if (entry.id == upgraded.id) entry.copy(version = version, plugin = reference) else entry
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = replaced.toJson()))
        }
        if (upgrade == Upgrade.VOWEL) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(7)) }
            assertContains(rejected.message.orEmpty(), "requires its fitted mouth v6")
            val vowels = setOf("ParamA", "ParamI", "ParamU", "ParamE", "ParamO")
            for (meshId in authored) {
                assertTrue(referenceStages[stage - 1].meshes.single { it.id == meshId }.shapes.none { it.parameter in vowels })
                val shapes = referenceStages[stage].meshes.single { it.id == meshId }.shapes.filter { it.parameter in vowels }
                assertEquals(vowels, shapes.map { it.parameter }.toSet())
                assertTrue(shapes.all { binding ->
                    binding.shapes.filterNotNull().any { shape -> shape.deltas.shared().any { it != 0f } }
                })
            }
        }
        if (upgrade == Upgrade.CAPTURE) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(6)) }
            assertContains(rejected.message.orEmpty(), "requires its fitted mouth v6")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val controls = RigModules.MOUTH_CAPTURE.defines.map { it.id }.toSet()
            assertEquals(controls, desired.parameters.map { it.id }.toSet() - previous.parameters.map { it.id }.toSet())
            for (meshId in authored) {
                assertTrue(previous.meshes.single { it.id == meshId }.shapes.none { it.parameter in controls })
                val shapes = desired.meshes.single { it.id == meshId }.shapes
                for (parameter in listOf("ParamMouthX", "ParamMouthPuckerWiden", "ParamMouthFunnel")) {
                    val binding = shapes.single { it.parameter == parameter }
                    assertTrue(binding.shapes.filterNotNull().any { shape -> shape.deltas.shared().any { it != 0f } },
                        "$meshId $parameter")
                }
            }
        }
        if (upgrade == Upgrade.JAW) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(8)) }
            assertContains(rejected.message.orEmpty(), "requires a shared fitted mouth v6")
            val previous = referenceStages[stage - 1]
            val desired = referenceStages[stage]
            val priorById = previous.meshes.associateBy { it.id }
            val changed = desired.meshes.filter { it != priorById[it.id] }.map { it.id }.toSet()
            assertTrue(changed.containsAll(setOf("ArtMeshFace", "ArtMeshNose")), changed.toString())
            assertTrue(changed.intersect(authored).isEmpty(), changed.toString())
            assertEquals(previous.deformers, desired.deformers)
            assertEquals(previous.parameters, desired.parameters)
        }
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val preview = try {
            RigBuildProfile.recording = true
            WorkspacePreviewBuilder().build(candidate(upgrade.version))
        } finally {
            RigBuildProfile.recording = recording
        }
        assertTrue(upgraded.type !in legacyTemplateConsumers())
        val actual = assertNotNull(preview.modifierEvaluation)
        assertEquals(if (upgrade == Upgrade.OUTLINE) 9 else 1, actual.entries[index].instance.version)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        assertEquals(if (upgrade == Upgrade.FITTED) 8 else fittedVersion, actual.entries[fittedIndex].instance.version)
        assertEquals(upgrade.version, actual.entries[stage].instance.version)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[fittedIndex].status)
        fun equal(expected: RigIR, next: RigIR, stage: String) {
            if (expected == next) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(expected) != it.get(next) }.map { it.name }
            val knownMeshes = expected.meshes.associateBy { it.id }
            val changedMeshes = next.meshes.filter { it != knownMeshes[it.id] }.map {
                val known = knownMeshes[it.id]
                "${it.id} (unchanged=${it == beforeMeshes[it.id]}, parent=${it.parent == known?.parent}, " +
                    "geometry=${it.geometry == known?.geometry}, offsets=${it.offsets == known?.offsets}, shapes=${it.shapes == known?.shapes})"
            }
            val knownNodes = expected.deformers.associateBy { it.id }
            val changedNodes = next.deformers.filter { it != knownNodes[it.id] }.map { "${it.name} (unchanged=${it == beforeNodes[it.id]})" }
            fail("$stage differs in $fields, meshes=$changedMeshes, deformers=$changedNodes")
        }
        for (stage in stack.instances.indices) equal(referenceStages[stage], actual.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")

        val root = Files.createTempDirectory("layered-mouth-outline-")
        val preferences = Preferences.userRoot().node("layered-mouth-outline/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = actual.entries[stage].instance
            val sourceInputs = RigModifierSource.of(preview)
            val definition = when (upgrade) {
                Upgrade.OUTLINE -> RigFittedMouthOutlineDefinition.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.FITTED -> RigFittedMouthDefinition.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.VOWEL -> RigMouthVowelModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.CAPTURE -> RigMouthCaptureModifier.sharedFrameDefinition(sourceInputs, layered = true)
                Upgrade.JAW -> RigMouthJawModifier.sharedFrameDefinition(sourceInputs, layered = true)
            }
            val context = definition.authorContext(instance).withUpstream(actual.entries.take(stage)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            assertEquals(setOf("before", "after"), context.resources.keys)
            val providerTypes = when (upgrade) {
                Upgrade.OUTLINE -> listOf("mouth/fitted")
                Upgrade.FITTED -> listOf("face/fitted", "eye/fitted", "brow/fitted")
                Upgrade.VOWEL -> listOf("mouth/fitted", "mouth.outline")
                Upgrade.CAPTURE -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel")
                Upgrade.JAW -> listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture")
            }
            for (type in providerTypes) {
                val provider = context.upstream.single { it.type == type }
                val original = stack.instances.single { it.type == type }
                assertEquals(original.version, provider.version)
                assertEquals(original.id, provider.instanceId)
                assertEquals(original.plugin, provider.plugin)
            }
            equal(referenceStages[stage], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                actual.outputs[stage - 1], instance, {}, store, context = context,
                declaredParameters = definition.parameters(instance)), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    private fun layeredDocument(source: SourceArt, config: PipelineConfig): WorkspaceDocument {
        val owner = RigGenerationSource.analyze(source, config).layers
            .filter { it.semantic.tag == SemanticTag.MOUTH && it.semantic.role == null && !it.source.clipped }
            .maxBy { it.opaquePixels }
        val bounds = owner.source.bounds
        val width = bounds.width
        val height = bounds.height
        val lines = listOf(PartRole.UPPER_LINE, PartRole.LOWER_LINE).mapIndexed { side, role ->
            val id = LayerId("test-lip-line-" + side)
            val pixels = ByteArray(width * height * 4)
            for (x in 0 until width) {
                val u = 2f * (x + 0.5f) / width - 1f
                val curve = sqrt(1f - u * u)
                val center = height * (0.5f + (if (side == 0) -0.32f else 0.32f) * curve)
                val y0 = (center.toInt() - 1).coerceAtLeast(0)
                for (y in y0..(y0 + 2).coerceAtMost(height - 1)) {
                    val i = (y * width + x) * 4
                    pixels[i] = 100; pixels[i + 1] = 60; pixels[i + 2] = 40; pixels[i + 3] = -1
                }
            }
            WorkspaceSourceLayer(id, role.word + (owner.semantic.variant?.let { " $it" } ?: ""), owner.source.groupPath,
                SourceLayerKind.Raster, true, owner.source.order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(width, height, pixels), null, null, false) to
                LayerClassificationOverride(tag = SemanticTag.MOUTH, side = owner.semantic.side, role = role)
        }
        val artwork = object : SourceArt by source {
            override val layers = source.layers.flatMap { layer ->
                if (layer.id == owner.source.id) lines.map { it.first } + layer else listOf(layer)
            }.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
        }
        return WorkspaceDocument(artwork, emptyMap(), emptySet(), lines.associate { (layer, classification) ->
            layer.id.raw to classification
        }, emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
    }
}
