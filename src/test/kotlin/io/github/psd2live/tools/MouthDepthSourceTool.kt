package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceLayer
import org.umamo.format.art.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class MouthDepthSourceTool {
    @Test fun compareDsWorkspaceTeethDepth(): Unit = runBlocking { compareDepth(tongue = false) }

    @Test fun compareDsWorkspaceTongueDepth(): Unit = runBlocking { compareDepth(tongue = true) }

    private suspend fun compareDepth(tongue: Boolean) {
        requireTools()
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val config = PipelineConfig(meshSpacing = 40, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val sourceAnalysis = RigGenerationSource.analyze(artwork, config)
        val inside = setOf(SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE, SemanticTag.MOUTH_OPEN)
        assertTrue(sourceAnalysis.layers.none { it.opaquePixels > 0 && it.semantic.tag in inside })
        val owner = sourceAnalysis.layers.filter { it.semantic.tag == SemanticTag.MOUTH && it.semantic.role == null &&
            !it.source.clipped }.maxBy { it.opaquePixels }
        val bounds = owner.source.bounds
        val additions = (listOf(SemanticTag.TOOTH_T) + listOfNotNull(SemanticTag.TONGUE.takeIf { tongue })).mapIndexed { index, tag ->
            val id = LayerId("source-mouth-interior-" + tag.name.lowercase())
            val width = (bounds.width / 3).coerceAtLeast(5)
            val height = (bounds.height / 5).coerceAtLeast(4)
            val top = bounds.top + if (index == 0) bounds.height / 4 else bounds.height / 2
            val shape = LayerBounds(bounds.left + (bounds.width - width) / 2, top, width, height)
            val raster = LayerRaster(width, height, ByteArray(width * height * 4) { -1 })
            WorkspaceSourceLayer(id, id.raw + (owner.semantic.variant?.let { " $it" } ?: ""), owner.source.groupPath, SourceLayerKind.Raster, true, owner.source.order,
                shape, 1f, true, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false) to
                LayerClassificationOverride(LayerType.PRESET, tag, owner.semantic.side)
        }
        val source = object : SourceArt by artwork {
            override val layers = artwork.layers.flatMap { layer ->
                if (layer.id == owner.source.id) additions.map { it.first } + layer else listOf(layer)
            }.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
        }
        val overrides = additions.associate { (layer, classification) -> layer.id.raw to classification }
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), config.rigEdits,
            WorkspaceSettingsCodec.encode(config))
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 6,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5,
            "eye.squash" to 5, "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6,
            "mouth.outline" to 8,
            "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to (if (tongue) 1 else 8), "mouth.cheek-lid" to (if (tongue) 1 else 8),
            "mouth.interior-depth" to 1, "mouth.spring" to 1) + if (tongue) mapOf("mouth.tongue" to 1) else emptyMap()
        val (planned, reference) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val depth = stack.instances.single { it.type == "mouth.interior-depth" }
        val index = stack.index(depth.id)
        val expected = assertNotNull(reference.modifierEvaluation).outputs
        val old = expected[index - 1].meshes.associateBy { it.id }
        val changes = expected[index].meshes.filter { it != old[it.id] }
        assertEquals(if (tongue) setOf("ArtMeshToothT", "ArtMeshTongue") else setOf("ArtMeshToothT"),
            changes.mapTo(hashSetOf()) { it.id })
        if (tongue) assertTrue(changes.single { it.id == "ArtMeshTongue" }.channels.keys.any { it.name == "MULTIPLY_COLOR" })
        fun candidate(type: String, version: Int): WorkspaceDocument {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(type, version))
            val modified = stack.copy(instances = stack.instances.map {
                if (it.type == type) it.copy(version = version, plugin = artifact.reference) else it
            })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson()))
        }
        for (version in listOf(3, 5)) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate(depth.type, version)) }
            assertContains(rejected.message.orEmpty(), "requires a compatible fitted mouth")
        }
        if (tongue) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate("mouth.jaw", 8)) }
            assertContains(rejected.message.orEmpty(), "requires the shared open-mouth outline, vowel and capture chain")
        } else {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(candidate("mouth.spring", 9)) }
            assertContains(rejected.message.orEmpty(), "requires the shared open-mouth source chain")
        }
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val preview = try {
            RigBuildProfile.recording = true
            WorkspacePreviewBuilder().build(candidate(depth.type, 6))
        } finally {
            RigBuildProfile.recording = recording
        }
        assertTrue(RigBuildProfile.snapshot().keys.none { it.startsWith("modifier template consumer: mouth.interior-depth ->") })
        val actual = assertNotNull(preview.modifierEvaluation)
        for (entry in actual.entries.filter { it.instance.type in versions }) {
            assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        }
        fun equal(a: RigIR, b: RigIR, stage: String) {
            if (a == b) return
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(a) != it.get(b) }.map { it.name }
            val byId = a.meshes.associateBy { it.id }
            val meshes = b.meshes.filter { it != byId[it.id] }.map { it.id }
            fail("$stage differs in $fields, meshes=$meshes")
        }
        for (stage in stack.instances.indices) equal(expected[stage], actual.outputs[stage], stack.instances[stage].type)
        equal(RigIrCompiler.compile(reference, tileArt = true), RigIrCompiler.compile(preview, tileArt = true), "compiled IR")

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(depth.type, 6))
        val root = Files.createTempDirectory("mouth-depth-source-")
        val preferences = Preferences.userRoot().node("mouth-depth-source/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val instance = actual.entries[index].instance
            val definition = RigMouthDepthModifier.sharedFrameDefinition(RigModifierSource.of(preview))
            val context = definition.authorContext(instance).withUpstream(actual.entries.take(index)
                .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
                .map { entry -> entry.instance.let {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                } })
            val types = listOf("mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.cheek-lid") +
                listOfNotNull("mouth.tongue".takeIf { tongue })
            for (type in types) {
                val provider = context.upstream.single { it.type == type }
                assertEquals(versions.getValue(type), provider.version)
                assertEquals(stack.instances.single { it.type == type }.plugin, provider.plugin)
            }
            equal(expected[index], ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(),
                actual.outputs[index - 1], instance, {}, store, context = context), "installed JVM")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}
