package io.github.psd2live.tools

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.project.*
import org.umamo.format.art.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class SkirtDsTool {
    @Test fun compareWorkspaceAtypicalSkirts(): Unit = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 40)
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val settings = WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay))
        fun layer(id: String, x: Int, y: Int, width: Int, height: Int) =
            WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 0,
                LayerBounds(x, y, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(width, height, ByteArray(width * height * 4) { -1 }), null, null, false)
        val objectSkirt = layer("ds-object-skirt", 565, 455, 29, 63)
        val headSkirt = layer("ds-head-skirt", 674, 212, 27, 49)
        val source = object : SourceArt by artwork {
            override val layers = (listOf(objectSkirt, headSkirt) + artwork.layers)
                .mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
        }
        val overrides = mapOf(objectSkirt.id.raw to LayerClassificationOverride(LayerType.PRESET, SemanticTag.OBJECTS, Side.NONE, role = PartRole.SKIRT),
            headSkirt.id.raw to LayerClassificationOverride(LayerType.PRESET, SemanticTag.HEADWEAR, Side.NONE, role = PartRole.SKIRT))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), overlay, settings)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6,
            "mouth.jaw" to 8, "mouth.cheek-lid" to 8, "mouth.spring" to 9,
            "face.neck" to 3, "body.arms" to 3, "body.bust" to 3,
            "outfit.skirt" to 1, "outfit.creature" to 2,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5, "eye.squash" to 5,
            "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))

        val historicalEvaluation = assertNotNull(historical.modifierEvaluation)
        for ((type, version) in versions) {
            val provider = historicalEvaluation.entries.single { it.instance.type == type }
            assertEquals(version, provider.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, provider.status, type)
        }

        val entry = stack.instances.single { it.type == "outfit.skirt" }
        assertTrue(entry.outputAliases.isEmpty())
        val index = stack.index(entry.id)
        val before = historicalEvaluation.outputs[index - 1]
        val after = historicalEvaluation.outputs[index]
        val prior = before.meshes.associateBy { it.id }
        val changed = after.meshes.filter { it != prior[it.id] }
        val fixtures = setOf(objectSkirt.id, headSkirt.id)
        val classified = historical.analysis.layers.filter { it.source.id in fixtures }
        assertEquals(setOf(SemanticTag.HEADWEAR, SemanticTag.OBJECTS), classified.mapTo(hashSetOf()) { it.semantic.tag })
        assertTrue(classified.all { it.opaquePixels > 0 && it.semantic.role == null })
        val sourceView = RigModifierSource.of(historical)
        val fixtureMeshes = sourceView.layersByMesh.filterValues { value -> fixtures.any { it.raw == value } }.keys
        assertEquals(2, fixtureMeshes.size)
        assertTrue(before.meshes.filter { it.id in fixtureMeshes }.all { it.visible && it.opacity > 0f })
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.none { it.id in fixtureMeshes })
        val addedPhysics = after.physics.groups.filterNot { it in before.physics.groups }
        for (mesh in changed) {
            assertNotEquals(prior.getValue(mesh.id).parent, mesh.parent)
            val carrier = after.deformers.single { it.id == mesh.parent } as Deformer.Warp
            assertTrue(addedPhysics.any { group ->
                group.outputs.any { output -> output.weight > 0f && carrier.shapes.any { it.parameter == output.parameter } }
            })
        }

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, 2))
        val selected = entry.copy(version = 2, plugin = artifact.reference)
        val next = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
        val candidate = planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson()))
        val model = WorkspacePreviewBuilder().build(candidate)
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        for (stage in stack.instances.indices) {
            assertEquals(historicalEvaluation.outputs[stage], actual.outputs[stage], stack.instances[stage].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(model, tileArt = true))

        val directory = Files.createTempDirectory("skirt-ds-")
        val preferences = Preferences.userRoot().node("skirt-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val context = assertNotNull(actual.readAlgorithms[entry.id]).context
            assertEquals(after, ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                selected, {}, store, context = context))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}
