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

    @Test fun compareWorkspaceAnimalEarWithoutTail(): Unit = runBlocking {
        compareCreature(4, SemanticTag.HEADWEAR, includeTail = false)
    }

    @Test fun compareWorkspaceWingWithoutTail(): Unit = runBlocking {
        compareCreature(2, SemanticTag.WINGS, includeTail = false)
    }

    @Test fun compareWorkspaceAnimalEarWithoutSkirt(): Unit = runBlocking {
        compareCreature(5, SemanticTag.HEADWEAR, includeTail = false, includeSkirt = false)
    }

    @Test fun compareWorkspaceAnimalEarWithOptionalSkirt(): Unit = runBlocking {
        compareCreature(5, SemanticTag.HEADWEAR, includeTail = false)
    }

    @Test fun compareLayeredMouthTail(): Unit = runBlocking { compareCreature(2, layered = true) }

    @Test fun compareLayeredMouthTailWithoutSkirt(): Unit = runBlocking {
        compareCreature(2, includeSkirt = false, layered = true)
    }

    @Test fun compareLayeredMouthAnimalEar(): Unit = runBlocking {
        compareCreature(6, SemanticTag.HEADWEAR, layered = true)
        compareCreature(6, SemanticTag.HEADWEAR, includeSkirt = false, layered = true)
    }

    @Test fun compareLayeredMouthAnimalEarWithoutRibbons(): Unit = runBlocking {
        compareCreature(6, SemanticTag.HEADWEAR, layered = true, includeRibbons = false)
    }

    @Test fun compareLayeredMouthWings(): Unit = runBlocking {
        compareCreature(2, SemanticTag.WINGS, includeTail = false, layered = true)
        compareCreature(6, SemanticTag.HEADWEAR, SemanticTag.WINGS, includeTail = false, layered = true)
    }

    @Test fun compareLayeredMouthWingsWithoutClothingBindings(): Unit = runBlocking {
        compareCreature(2, SemanticTag.WINGS, includeTail = false, includeSkirt = false,
            layered = true, includeRibbons = false)
        compareCreature(6, SemanticTag.HEADWEAR, SemanticTag.WINGS, includeTail = false, includeSkirt = false,
            layered = true, includeRibbons = false)
    }

    private suspend fun compareCreature(
        version: Int, vararg extras: SemanticTag, includeTail: Boolean = true, includeSkirt: Boolean = true,
        layered: Boolean = false, includeRibbons: Boolean = layered,
    ) {
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
            override val layers = (fixtures.values + artwork.layers.filter { includeTail || it.name != "tail" })
                .mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
        }
        val skirts = if (includeSkirt) emptyList() else CharacterAnalyzer.analyze(artwork, config).layers.filter {
            it.opaquePixels > 0 && it.semantic.role == PartRole.SKIRT
        }
        if (!includeSkirt) assertTrue(skirts.isNotEmpty())
        val skirtOverrides = skirts.associate { layer ->
            val semantic = layer.semantic
            layer.source.id.raw to LayerClassificationOverride(semantic.type, semantic.tag, semantic.side,
                semantic.parameter, semantic.switchId, PartRole.PLAIN)
        }
        val overrides = fixtures.entries.associate { (tag, fixture) ->
            fixture.id.raw to LayerClassificationOverride(LayerType.PRESET, tag, Side.NONE,
                role = PartRole.ANIMAL.takeIf { tag == SemanticTag.HEADWEAR })
        } + skirtOverrides
        val fixtureConfig = config.copy(rigEdits = overlay)
        val document = when {
            includeRibbons -> ribbonDsDocument(source, fixtureConfig, layered = true, extraOverrides = overrides)
            layered -> layeredMouthDocument(source, fixtureConfig).let {
                it.copy(layerOverrides = it.layerOverrides + overrides)
            }
            else -> WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), overlay,
                WorkspaceSettingsCodec.encode(fixtureConfig))
        }
        val layeredPrefix = if (layered) layeredRibbonMouthVersions +
            if (includeRibbons) mapOf("outfit.ribbons" to 6) else emptyMap()
        else emptyMap()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "mouth/fitted" to 6, "mouth.outline" to 8, "mouth.vowel" to 7, "mouth.capture" to 6, "mouth.jaw" to 8, "mouth.cheek-lid" to 8, "mouth.spring" to 9, "face.neck" to 3, "body.arms" to 3, "body.bust" to 3, "outfit.creature" to 1,
            "eye.smile" to 4, "eye.wide" to 4, "eye.gaze-lid" to 4, "eye.emotion" to 4, "eye.aftershock" to 5, "eye.squash" to 5,
            "brow.shape" to 5, "brow.lid-coupling" to 7, "brow.blink" to 5, "brow.spring" to 6) + layeredPrefix + if (includeSkirt) mapOf("outfit.skirt" to 2) else emptyMap()
        val (planned, historical) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))

        if (layered) assertEquals(includeRibbons, stack.instances.any { it.type == "outfit.ribbons" })
        assertEquals(includeSkirt, stack.instances.any { it.type == "outfit.skirt" })
        assertEquals(includeSkirt, historical.analysis.layers.any { it.opaquePixels > 0 && it.semantic.role == PartRole.SKIRT })
        val analyzed = historical.analysis.layers.associateBy { it.source.id }
        for (originalLayer in skirts) {
            val retained = assertNotNull(analyzed[originalLayer.source.id])
            assertNull(retained.semantic.role)
            assertEquals(originalLayer.opaquePixels, retained.opaquePixels)
        }
        val historicalEvaluation = assertNotNull(historical.modifierEvaluation)
        for ((type, version) in versions) {
            val provider = historicalEvaluation.entries.single { it.instance.type == type }
            assertEquals(version, provider.instance.version, type)
            assertEquals(ModifierStatus.ACTIVE, provider.status, type)
        }

        val entry = stack.instances.single { it.type == "outfit.creature" }
        assertTrue(entry.outputAliases.isEmpty())
        val index = stack.index(entry.id)

        for (type in listOf("mouth.spring", "body.bust", "body.arms") + listOfNotNull("outfit.skirt".takeIf { includeSkirt })) {
            assertTrue(stack.index(stack.instances.single { it.type == type }.id) < index, type)
        }
        if (layered) {
            val fit = assertNotNull(RigBuilder.rigContext(historical.analysis, config).fittedMouth(config))
            assertTrue(fit.layered && fit.rig?.fit?.layered != null)
            val expectedCreatureTags = extras.toSet() + listOfNotNull(SemanticTag.TAIL.takeIf { includeTail })
            assertEquals(expectedCreatureTags, historical.analysis.layers.filter {
                it.opaquePixels > 0 && (it.semantic.role == PartRole.ANIMAL ||
                    it.semantic.tag in setOf(SemanticTag.TAIL, SemanticTag.WINGS))
            }.mapTo(hashSetOf()) { it.semantic.tag })
            for ((type, required) in layeredPrefix) {
                val upstream = stack.instances.single { it.type == type }
                assertTrue(stack.index(upstream.id) < index, type)
                assertEquals(assertNotNull(BundledAlgorithmArtifacts.find(type, required)).reference, upstream.plugin, type)
            }
        }
        val before = historicalEvaluation.outputs[index - 1]
        val after = historicalEvaluation.outputs[index]
        val prior = before.meshes.associateBy { it.id }
        val changed = after.meshes.filter { it != prior[it.id] }
        val sourceView = RigModifierSource.of(historical)
        val tail = setOf("ArtMeshTail").takeIf { includeTail }.orEmpty()
        assertEquals(includeTail, before.meshes.any { it.id == "ArtMeshTail" })
        val expected = tail + fixtures.values.map { fixture ->
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

        if (!includeSkirt && version == 5 || layered && version == 6 && !includeRibbons) {
            val (recommended, preview) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct, WorkspaceInitialPreset())
            val selected = assertNotNull(preview.modifierEvaluation).entries.single { it.instance.type == entry.type }
            assertEquals(version, selected.instance.version)
            assertEquals(ModifierStatus.ACTIVE, selected.status)
            val recommendedStack = RigModifierStack.fromJson(assertNotNull(recommended.rigEdits.modifierStack))
            val historicalStack = recommendedStack.copy(instances = recommendedStack.instances.map {
                if (it.id == selected.instance.id) it.copy(version = 1, plugin = BuiltinModifierPlugins.reference(it.type, 1)) else it
            })
            val historicalPreview = WorkspacePreviewBuilder().build(recommended.copy(
                rigEdits = recommended.rigEdits.copy(modifierStack = historicalStack.toJson())))
            assertEquals(RigIrCompiler.compile(historicalPreview, tileArt = true), RigIrCompiler.compile(preview, tileArt = true))
        }

        if (!layered) {
            val rejected = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(3).first) }
            assertContains(rejected.message.orEmpty(), "Creature v3 requires mouth/fitted@4")
        }
        if (layered && SemanticTag.HEADWEAR in fixtures) {
            val v5 = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(5).first) }
            assertContains(v5.message.orEmpty(), "Creature v5 requires mouth/fitted@6")
        }
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
        if (!includeSkirt && !layered) {
            val v4 = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(select(4).first) }
            assertContains(v4.message.orEmpty(), "Creature v4 requires outfit.skirt@2")
        }
        val (candidate, selected) = select(version)
        val model = WorkspacePreviewBuilder().build(candidate)
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[index].status)
        for (stage in stack.instances.indices) {
            assertEquals(historicalEvaluation.outputs[stage], actual.outputs[stage], stack.instances[stage].type)
        }
        assertEquals(RigIrCompiler.compile(historical, tileArt = true), RigIrCompiler.compile(model, tileArt = true))

        if (version == 4 && !includeTail) {
            val (_, recommended) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct)
            val selected = assertNotNull(recommended.modifierEvaluation).entries.single { it.instance.type == entry.type }
            assertEquals(version, selected.instance.version)
            assertEquals(ModifierStatus.ACTIVE, selected.status)
            assertEquals(RigIrCompiler.compile(model, tileArt = true), RigIrCompiler.compile(recommended, tileArt = true))
        }

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(entry.type, version))
        val directory = Files.createTempDirectory("creature-ds-")
        val preferences = Preferences.userRoot().node("creature-ds/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val context = assertNotNull(actual.readAlgorithms[entry.id]).context
            if (version >= 4) assertEquals(includeSkirt, context.upstream.any { it.capability == "outfit.skirt" })
            if (version == 6) {
                assertEquals(includeRibbons, context.upstream.any { it.capability == "outfit.ribbons" })
                for ((type, required) in layeredPrefix) {
                    assertEquals(required, context.upstream.single { it.capability == type }.version, type)
                }
            }
            assertEquals(after, ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), before,
                selected, {}, store, context = context))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }
}
