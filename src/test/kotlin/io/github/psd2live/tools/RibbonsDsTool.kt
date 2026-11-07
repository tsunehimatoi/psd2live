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

class RibbonsDsTool {
    @Test fun compareWorkspaceRibbons(): Unit = runBlocking {
        requireTools()
        val config = PipelineConfig(meshSpacing = 40)
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val settings = WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay))
        val original = WorkspaceDocument(artwork, emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay, settings)
        val preset = WorkspaceInitialPreset()
        val (originalPlanned, _) = WorkspacePresetInitialization.prepare(original, WorkspaceRasterWork.Direct, preset)
        val originalStack = RigModifierStack.fromJson(assertNotNull(originalPlanned.rigEdits.modifierStack))
        assertTrue(originalStack.instances.none { it.type == "outfit.ribbons" })
        fun layer(id: String, x: Int, y: Int, width: Int, height: Int) =
            WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 0,
                LayerBounds(x, y, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(width, height, ByteArray(width * height * 4) { -1 }), null, null, false)
        val ribbon = layer("ds-cloth-ribbon", 565, 455, 16, 63)
        val pendant = layer("ds-head-pendant", 674, 212, 13, 31)
        val source = object : SourceArt by artwork {
            override val layers = (listOf(ribbon, pendant) + artwork.layers)
                .mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
        }
        val overrides = mapOf(ribbon.id.raw to LayerClassificationOverride(LayerType.PRESET, SemanticTag.TOPWEAR, Side.NONE, role = PartRole.RIBBON),
            pendant.id.raw to LayerClassificationOverride(LayerType.PRESET, SemanticTag.HEADWEAR, Side.NONE, role = PartRole.PENDANT))
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), overlay, settings)
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6,
            "mouth.jaw" to 8, "mouth.cheek-lid" to 8, "mouth.spring" to 9,
            "face.neck" to 3, "body.arms" to 3, "body.bust" to 3,
            "outfit.skirt" to 2, "outfit.ribbons" to 1, "outfit.creature" to 2,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5, "eye.squash" to 5,
            "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6)
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            preset, requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))

        val historicalEvaluation = assertNotNull(historical.modifierEvaluation)
        for ((type, version) in versions) {
            val provider = historicalEvaluation.entries.single { it.instance.type == type }
            assertEquals(version, provider.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, provider.status, type)
        }

        val entry = stack.instances.single { it.type == "outfit.ribbons" }
        val index = stack.index(entry.id)
        val before = historicalEvaluation.outputs[index - 1]
        val after = historicalEvaluation.outputs[index]
        val prior = before.meshes.associateBy { it.id }
        val changed = after.meshes.filter { it != prior[it.id] }
        assertEquals(setOf("ArtMeshTopwear", "ArtMeshHeadwear"), changed.mapTo(hashSetOf()) { it.id })
        assertTrue(changed.all { it.parent != prior.getValue(it.id).parent })
        val carriers = changed.map { mesh -> after.deformers.single { it.id == mesh.parent } as Deformer.Warp }
        assertEquals(2, carriers.mapTo(hashSetOf()) { it.parent }.size, "Fixture must use separate head and body frames")
        val physics = after.physics.groups.filter { it !in before.physics.groups }
        for (carrier in carriers) {
            assertTrue(before.deformers.none { it.id == carrier.id })
            assertTrue(carrier.shapes.any { binding -> binding.shapes.filterNotNull().any { it.points.shared().any { point -> point != 0f } } })
            assertTrue(physics.any { group -> group.inputs.any { it.weight > 0f } &&
                group.outputs.any { output -> output.weight > 0f && carrier.shapes.any { it.parameter == output.parameter } } })
        }

        fun select(version: Int): Pair<WorkspaceDocument, RigModifierInstance> {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val selected = entry.copy(version = version, plugin = artifact.reference)
            val next = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson())) to selected
        }
        val v2 = WorkspacePreviewBuilder().build(select(2).first)
        val plain = assertNotNull(v2.modifierEvaluation).outputs[index]
        val head = changed.single { it.id == "ArtMeshHeadwear" }
        val actualHead = plain.meshes.single { it.id == head.id }
        assertNotEquals(head.offsets, actualHead.offsets)
        assertEquals(after, plain.copy(meshes = plain.meshes.map { if (it.id == head.id) it.copy(offsets = head.offsets) else it }))
        assertContains(assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(3).first) }.message.orEmpty(),
            "Ribbons v3 requires mouth/fitted@4")
        assertContains(assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(4).first) }.message.orEmpty(),
            "Ribbons v4 requires a fitted open or closed mouth source")

        val (candidate, selected) = select(5)
        val model = WorkspacePreviewBuilder().build(candidate)
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        for (stage in stack.instances.indices) {
            assertEquals(historicalEvaluation.outputs[stage], actual.outputs[stage], stack.instances[stage].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(model, tileArt = true))

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, 5))
        val directory = Files.createTempDirectory("ribbons-ds-")
        val preferences = Preferences.userRoot().node("ribbons-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val definition = RigRibbonsModifier.sharedFrameDefinition(RigModifierSource.of(model))
            val providers = RigLegacyBindings.providers(selected) + definition.optionalUpstream.associateWith { type ->
                actual.entries.take(index).single { it.instance.type == type }.instance.id
            }
            val context = definition.authorContext(selected).withUpstream(providers.map { (capability, id) ->
                val provider = actual.entries.take(index).single { it.instance.id == id }
                assertEquals(ModifierStatus.ACTIVE, provider.status)
                val instance = provider.instance
                ModifierUpstreamCapability(capability, instance.type, instance.version, instance.targets,
                    instance.id, instance.config, instance.outputAliases, instance.plugin)
            })
            assertEquals(after, ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                selected, {}, store, context = context, declaredParameters = definition.parameters(selected)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}
