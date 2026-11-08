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

class CreatureDsTool {
    @Test fun compareWorkspaceCreature(): Unit = runBlocking { compareCreature(2) }
    @Test fun compareWorkspaceAnimalEarAndWing(): Unit = runBlocking { compareCreature(4, SemanticTag.HEADWEAR, SemanticTag.WINGS) }
    @Test fun compareWorkspaceWing(): Unit = runBlocking { compareCreature(2, SemanticTag.WINGS) }

    private suspend fun compareCreature(version: Int, vararg extras: SemanticTag) {
        requireTools()
        val config = PipelineConfig(meshSpacing = 40)
        val artwork = PSD2LivePipeline().readPsd(Sample.fromEnvironment().path)
        val overlay = RigEditOverlay(modifierStack = RigModifierStack().toJson())
        val fixtures = extras.associateWith { tag ->
            val id = LayerId("ds-creature-" + tag.name.lowercase())
            val bounds = if (tag == SemanticTag.HEADWEAR) LayerBounds(665, 139, 24, 65) else LayerBounds(777, 385, 41, 112)
            val raster = LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { -1 })
            WorkspaceSourceLayer(id, id.raw, "", SourceLayerKind.Raster, true, 0, bounds, 1f, false, LayerBlend.Normal,
                ChannelMask.ALL, raster, null, null, false)
        }
        val source = if (fixtures.isEmpty()) artwork else object : SourceArt by artwork {
            override val layers = (fixtures.values + artwork.layers).mapIndexed { index, layer ->
                WorkspaceSourceLayer.copyOf(layer, index)
            }
        }
        val overrides = fixtures.entries.associate { (tag, fixture) ->
            fixture.id.raw to LayerClassificationOverride(LayerType.PRESET, tag, Side.NONE,
                role = PartRole.ANIMAL.takeIf { tag == SemanticTag.HEADWEAR })
        }
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config.copy(rigEdits = overlay)))
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to 8, "mouth.cheek-lid" to 8, "mouth.spring" to 9, "face.neck" to 3, "body.arms" to 3, "body.bust" to 3, "outfit.skirt" to 2, "outfit.creature" to 1,
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

        val entry = stack.instances.single { it.type == "outfit.creature" }
        assertTrue(entry.outputAliases.isEmpty())
        val index = stack.index(entry.id)

        for (type in listOf("mouth.spring", "body.bust", "body.arms", "outfit.skirt")) {
            assertTrue(stack.index(stack.instances.single { it.type == type }.id) < index, type)
        }
        val before = historicalEvaluation.outputs[index - 1]
        val after = historicalEvaluation.outputs[index]
        val prior = before.meshes.associateBy { it.id }
        val changed = after.meshes.filter { it != prior[it.id] }
        val sourceView = RigModifierSource.of(historical)
        val expected = setOf("ArtMeshTail") + fixtures.values.map { fixture ->
            sourceView.layersByMesh.entries.single { it.value == fixture.id.raw }.key
        }
        assertEquals(expected, changed.mapTo(hashSetOf()) { it.id })
        val physics = after.physics.groups.filter { it !in before.physics.groups }
        assertEquals(2 * changed.size, physics.size)
        for (mesh in changed) {
            assertTrue(mesh.visible && mesh.opacity > 0f)
            val old = prior.getValue(mesh.id)
            assertNotEquals(old.geometry, mesh.geometry)
            assertNotEquals(old.parent, mesh.parent)
            val carrier = after.deformers.single { it.id == mesh.parent } as Deformer.Warp
            assertTrue(before.deformers.none { it.id == carrier.id })
            assertTrue(carrier.shapes.any { binding ->
                binding.shapes.filterNotNull().any { shape -> shape.points.shared().any { it != 0f } }
            })
            assertTrue(physics.any { group -> group.inputs.any { it.weight > 0f } && group.outputs.any { output ->
                output.weight > 0f && carrier.shapes.any { it.parameter == output.parameter }
            } })
        }

        fun select(version: Int): Pair<WorkspaceDocument, RigModifierInstance> {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
            val selected = entry.copy(version = version, plugin = artifact.reference)
            val next = stack.copy(instances = stack.instances.map { if (it.id == entry.id) selected else it })
            return planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = next.toJson())) to selected
        }

        val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(3).first) }
        assertContains(rejected.message.orEmpty(), "Creature v3 requires mouth/fitted@4")
        fixtures[SemanticTag.HEADWEAR]?.let { ear ->
            val earId = sourceView.layersByMesh.entries.single { it.value == ear.id.raw }.key
            val v2 = WorkspacePreviewBuilder().build(select(2).first)
            val stage = assertNotNull(v2.modifierEvaluation).outputs[index]
            val earOffsets = after.meshes.single { it.id == earId }.offsets
            assertNotEquals(earOffsets, stage.meshes.single { it.id == earId }.offsets)
            assertEquals(after, stage.copy(meshes = stage.meshes.map { mesh ->
                if (mesh.id == earId) mesh.copy(offsets = earOffsets) else mesh
            }))
        }
        val (candidate, selected) = select(version)
        val model = WorkspacePreviewBuilder().build(candidate)
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        for (stage in stack.instances.indices) {
            assertEquals(historicalEvaluation.outputs[stage], actual.outputs[stage], stack.instances[stage].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(model, tileArt = true))

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
        val directory = Files.createTempDirectory("creature-ds-")
        val preferences = Preferences.userRoot().node("creature-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val workerSource = RigModifierSource.of(model)
            val definition = if (version == 4) RigCreatureModifier.sharedFrameDefinition(workerSource)
                else RigCreatureModifier.definition(workerSource)
            val providerIds = RigLegacyBindings.providers(selected) + definition.optionalUpstream.associateWith { type ->
                actual.entries.take(index).single { it.instance.type == type }.instance.id
            }
            val providers = providerIds.map { (capability, id) ->
                val provider = actual.entries.take(index).single { it.instance.id == id }
                assertEquals(ModifierStatus.ACTIVE, provider.status)
                val instance = provider.instance
                ModifierUpstreamCapability(capability, instance.type, instance.version, instance.targets,
                    instance.id, instance.config, instance.outputAliases, instance.plugin)
            }
            val context = definition.authorContext(selected).withUpstream(providers)
            assertEquals(after, ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                selected, {}, store, context = context, declaredParameters = definition.parameters(selected)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}
