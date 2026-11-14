package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.umamo.format.art.*
import kotlin.test.*

class WorkspacePresetInitializeTest {
    private fun document(stack: RigModifierStack = RigModifierStack()): WorkspaceDocument {
        val source = WorkspaceSourceArt(64, 64, emptyList(), emptyList())
        val config = PipelineConfig(recipe = RigRecipe.Legacy, atlasSize = 128, meshSpacing = 9,
            headTurnStrength = .3f, hairSimulationFront = true)
        return WorkspaceDocument(source, mapOf("hidden" to false), setOf("original"),
            mapOf("left-piece" to LayerClassificationOverride(LayerType.PRESET, SemanticTag.EARS, Side.LEFT)),
            emptyMap(), RigEditOverlay(modifierStack = stack.toJson(),
                splitBaselineLayerIds = setOf("original"), splitDrawableIds = mapOf("left-piece" to "piece-mesh")),
            WorkspaceSettingsCodec.encode(config))
    }

    @Test fun preparingBindingsPreservesSplitArtworkAndAuthoredOverrides() {
        val original = document()
        val custom = WorkspaceInitialPreset(headStrength = .7f, bodyStrength = .6f,
            frontHair = WorkspaceInitialHair.OFF, backHair = WorkspaceInitialHair.SIMULATION,
            clothing = false, eyeJelly = false)
        val (prepared, choices) = WorkspacePresetInitialize.prepare(original, WorkspaceInitialModifiers.CUSTOM, custom)
        assertEquals(custom, choices)
        assertEquals(original, prepared.copy(settings = original.settings))
        val config = prepared.config()
        assertEquals(RigRecipe.Legacy, config.recipe)
        assertEquals(.7f, config.headTurnStrength)
        assertEquals(.6f, config.bodyStrength)
        assertFalse(config.hairSimulationFront)
        assertTrue(config.hairSimulationBack)
        assertFalse(config.physicsFrontHair)
        assertFalse(config.physicsEyeJelly)
        assertEquals(128, config.atlasSize)
        assertEquals(9, config.meshSpacing)
    }

    @Test fun recommendedAndShortcutsMatchSourceImportConfiguration() {
        val original = document()
        for (mode in listOf(WorkspaceInitialModifiers.RECOMMENDED, WorkspaceInitialModifiers.MINIMAL,
            WorkspaceInitialModifiers.DEFAULT, WorkspaceInitialModifiers.FULL)) {
            val choices = if (mode == WorkspaceInitialModifiers.RECOMMENDED) WorkspaceModelPresetLibrary.shared.get("modern").choices
                else WorkspaceInitialPreset.forMode(mode)
            val baseline = if (mode == WorkspaceInitialModifiers.RECOMMENDED)
                WorkspacePresetBaseline.configure(original.config(), "modern") else original.config()
            val expected = choices.configure(baseline).copy(
                hairSimulationFront = choices.frontHair == WorkspaceInitialHair.SIMULATION,
                hairSimulationBack = choices.backHair == WorkspaceInitialHair.SIMULATION)
            val (prepared, captured) = WorkspacePresetInitialize.prepare(original, mode)
            assertEquals(choices, captured)
            assertEquals(WorkspaceSettingsCodec.encode(expected), prepared.settings)
            assertEquals(original, prepared.copy(settings = original.settings))
        }
    }

    @Test fun initializationRejectsExistingAppliedOrPreviouslyRemovedModifiers() {
        val target = ModifierTarget("mesh", "piece-mesh")
        val used = RigModifierStack().add("test/binding", 1, listOf(target))
        val rejected = listOf(used, used.remove(used.instances.single().id),
            RigModifierStack(appliedCapabilities = listOf(ModifierCapability("test/binding", 1, listOf(target)))),
            RigModifierStack(nextId = 2))
        for (stack in rejected) assertFailsWith<IllegalArgumentException> {
            WorkspacePresetInitialize.prepare(document(stack), WorkspaceInitialModifiers.RECOMMENDED)
        }
        val original = document()
        assertFailsWith<IllegalArgumentException> {
            WorkspacePresetInitialize.prepare(original.copy(rigEdits = original.rigEdits.copy(modifierStack = null)), WorkspaceInitialModifiers.RECOMMENDED)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkspacePresetInitialize.prepare(original.copy(rigEdits = original.rigEdits.copy(importedCmo3 = "imported")), WorkspaceInitialModifiers.RECOMMENDED)
        }
    }

    @Test fun emptyIsANoopAndCustomChoicesMustMatchTheSelectedMode() {
        val original = document()
        assertEquals(original to null, WorkspacePresetInitialize.prepare(original, WorkspaceInitialModifiers.EMPTY))
        assertFailsWith<IllegalArgumentException> { WorkspacePresetInitialize.prepare(original, WorkspaceInitialModifiers.CUSTOM) }
        assertFailsWith<IllegalArgumentException> {
            WorkspacePresetInitialize.prepare(original, WorkspaceInitialModifiers.DEFAULT, WorkspaceInitialPreset())
        }
        assertEquals(original, WorkspacePresetInitialize.apply(original, buildJsonObject { put("modifiers", "empty") }, WorkspaceRasterWork.Direct))
    }

    @Test fun splitUnsidedIrisBeforeInitializationKeepsNamedSidesAndPrimitiveRecords(): Unit = runBlocking {
        data class Shape(val id: String, val tag: SemanticTag, val side: Side, val x: Int, val y: Int, val w: Int, val h: Int)
        val shapes = listOf(
            Shape("iris", SemanticTag.IRIDES, Side.NONE, 0, 0, 64, 80),
            Shape("white-left", SemanticTag.EYEWHITE, Side.LEFT, 38, 22, 14, 10),
            Shape("white-right", SemanticTag.EYEWHITE, Side.RIGHT, 12, 22, 14, 10),
            Shape("lash-left", SemanticTag.EYELASH, Side.LEFT, 38, 20, 14, 2),
            Shape("lash-right", SemanticTag.EYELASH, Side.RIGHT, 12, 20, 14, 2),
            Shape("brow-left", SemanticTag.EYEBROW, Side.LEFT, 38, 14, 14, 2),
            Shape("brow-right", SemanticTag.EYEBROW, Side.RIGHT, 12, 14, 14, 2),
            Shape("face", SemanticTag.FACE, Side.NONE, 8, 8, 48, 52))
        val art = WorkspaceSourceArt(64, 80, shapes.mapIndexed { index, shape ->
            val rgba = ByteArray(shape.w * shape.h * 4)
            for (y in 0 until shape.h) for (x in 0 until shape.w) {
                if (shape.id != "iris" || y in 23..30 && (x in 16..21 || x in 42..47))
                    for (c in 0..3) rgba[(y * shape.w + x) * 4 + c] = -1
            }
            WorkspaceSourceLayer(LayerId(shape.id), shape.id, "", SourceLayerKind.Raster, true, index,
                LayerBounds(shape.x, shape.y, shape.w, shape.h), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(shape.w, shape.h, rgba), null, null, false)
        }, emptyList())
        val config = PipelineConfig(atlasSize = 128, meshSpacing = 3, generatePhysics = false,
            rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val imported = WorkspaceDocument(art, emptyMap(), emptySet(), shapes.associate { shape ->
            shape.id to LayerClassificationOverride(LayerType.PRESET, shape.tag, shape.side)
        }, emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val before = builder.build(imported)
        val plan = assertNotNull(WorkspacePartitionEdits.componentPlan(before, "iris"))
        assertEquals(2, plan.components.size)
        val sides = plan.components.map { if (it.centerX > 32f) Side.LEFT else Side.RIGHT }
        val ids = sides.map { "iris-${it.name.lowercase()}" }
        val split = WorkspacePartitionEdits.apply(WorkspaceDocumentOperation("source_split_components", buildJsonObject {
            put("layer_id", "iris")
            put("piece_ids", JsonArray(ids.map(::JsonPrimitive)))
            put("names", JsonArray(sides.map { JsonPrimitive("Iris ${it.name}") }))
            put("sides", JsonArray(sides.map { JsonPrimitive(it.name.lowercase()) }))
        }), imported, before)
        val splitRecords = ArtPrimitiveJournal.commands(split.rigEdits)
        assertTrue(splitRecords.isNotEmpty())
        val splitPreview = builder.build(split)
        // Splitting an unbound source must not smuggle legacy feature warps into ordinary content.
        assertTrue(splitPreview.authored.rig.puppet.drawables.all { it.parentDeformerId == null })
        assertTrue(splitPreview.authored.rig.puppet.deformers.isEmpty())
        val initialized = WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_initialize",
            buildJsonObject { put("modifiers", "recommended") }), split, splitPreview)
        assertEquals(split.layerOverrides, initialized.layerOverrides)
        assertEquals(split.source, initialized.source)
        assertEquals(split.rigEdits.splitDrawableIds, initialized.rigEdits.splitDrawableIds)
        assertEquals(splitRecords, ArtPrimitiveJournal.commands(initialized.rigEdits))
        RigBuildProfile.reset()
        RigBuildProfile.recording = true
        val rebuilt = try {
            builder.build(initialized)
        } finally {
            RigBuildProfile.recording = false
        }
        val nonMouthLegacyTemplates = RigBuildProfile.snapshot().keys.filter {
            it.startsWith("modifier legacy template:") && !it.contains("mouth")
        }
        assertTrue(nonMouthLegacyTemplates.isEmpty(), "Non-mouth legacy templates: $nonMouthLegacyTemplates")
        for ((id, side) in ids.zip(sides)) {
            assertEquals(side, initialized.layerOverrides.getValue(id).side)
            assertEquals(side, rebuilt.analysis.layers.single { it.source.id.raw == id }.semantic.side)
        }
        assertFalse(rebuilt.analysis.layers.any { it.source.id.raw == "iris" && it.opaquePixels > 0 })
        assertTrue(RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack)).instances.isNotEmpty())
    }

    @Test fun initializationIsRegisteredWithImportCompatibleRequestFields() {
        assertTrue("modifier_initialize" in WorkspaceModifierEdits.supported)
        val schema = WorkspaceModifierSchemas.request("modifier_initialize")
        assertEquals(setOf("state", "modifiers", "preset"), schema.getValue("properties").jsonObject.keys)
        assertEquals(setOf("state", "modifiers"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test fun recommendedInitializationSucceedsWithOpenMouthAndFullHeadLayers(): Unit = runBlocking {
        val base = Characters.head(physics = false)
        val mouthLayer = base.source.layers.first()
        val extraLayers = listOf(
            "mouth-open-test" to LayerClassificationOverride(LayerType.PRESET, SemanticTag.MOUTH_OPEN),
            "tongue-test" to LayerClassificationOverride(LayerType.PRESET, SemanticTag.TONGUE),
            "face-detail-test" to LayerClassificationOverride(LayerType.PRESET, SemanticTag.FACE_DETAIL),
            "topwear-test" to LayerClassificationOverride(LayerType.PRESET, SemanticTag.TOPWEAR, role = PartRole.SKIRT),
            "tail-test" to LayerClassificationOverride(LayerType.PRESET, SemanticTag.TAIL),
            "neck-test" to LayerClassificationOverride(LayerType.PRESET, SemanticTag.NECK),
            "handwear-test" to LayerClassificationOverride(LayerType.PRESET, SemanticTag.HANDWEAR),
        ).mapIndexed { idx, (id, override) ->
            WorkspaceSourceLayer(
                LayerId(id), id, "", SourceLayerKind.Raster, true, base.source.layers.size + idx,
                mouthLayer.bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                mouthLayer.raster, null, null, false,
            ) to (id to override)
        }
        val emptyDoc = base.copy(
            source = WorkspaceSourceArt(
                base.source.widthPx, base.source.heightPx,
                base.source.layers + extraLayers.map { it.first }, base.source.groups,
            ),
            layerOverrides = base.layerOverrides + extraLayers.map { it.second },
            rigEdits = base.rigEdits.copy(modifierStack = RigModifierStack().toJson()),
        )
        val builder = WorkspacePreviewBuilder()
        val preview = builder.build(emptyDoc)
        for (mode in listOf(
            WorkspaceInitialModifiers.RECOMMENDED,
            WorkspaceInitialModifiers.MINIMAL,
            WorkspaceInitialModifiers.DEFAULT,
            WorkspaceInitialModifiers.FULL,
        )) {
            val initialized = WorkspaceModifierEdits.apply(
                WorkspaceDocumentOperation("modifier_initialize", buildJsonObject { put("modifiers", mode.name.lowercase()) }),
                emptyDoc,
                preview,
            )
            val stack = RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack))
            assertTrue(stack.instances.isNotEmpty(), "Expected non-empty stack for $mode")
        }
    }

    @Test fun initializationSucceedsWithLegacyRecipeAndDisabledDepthTurn(): Unit = runBlocking {
        val base = Characters.head(physics = false)
        val builder = WorkspacePreviewBuilder()
        val legacySettings = WorkspaceSettingsCodec.encode(base.config().copy(recipe = io.github.psd2live.core.recipe.RigRecipe.Legacy))
        val legacyDoc = base.copy(settings = legacySettings, rigEdits = base.rigEdits.copy(modifierStack = RigModifierStack().toJson()))
        val legacyPreview = builder.build(legacyDoc)
        for (mode in listOf(
            WorkspaceInitialModifiers.MINIMAL,
            WorkspaceInitialModifiers.DEFAULT,
            WorkspaceInitialModifiers.FULL,
        )) {
            val initialized = WorkspaceModifierEdits.apply(
                WorkspaceDocumentOperation("modifier_initialize", buildJsonObject { put("modifiers", mode.name.lowercase()) }),
                legacyDoc,
                legacyPreview,
            )
            assertTrue(RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack)).instances.isNotEmpty())
        }

        val noDepthTurnRecipe = io.github.psd2live.core.recipe.RigRecipe.Modern.let { r ->
            r.copy(modules = r.modules + (io.github.psd2live.core.recipe.RigModules.FACE_DEPTH_TURN.id to io.github.psd2live.core.recipe.ModuleChoice(on = false)))
        }
        val noDepthDoc = base.copy(
            settings = WorkspaceSettingsCodec.encode(base.config().copy(recipe = noDepthTurnRecipe)),
            rigEdits = base.rigEdits.copy(modifierStack = RigModifierStack().toJson()),
        )
        val noDepthPreview = builder.build(noDepthDoc)
        val noDepthInit = WorkspaceModifierEdits.apply(
            WorkspaceDocumentOperation("modifier_initialize", buildJsonObject { put("modifiers", "default") }),
            noDepthDoc,
            noDepthPreview,
        )
        assertTrue(RigModifierStack.fromJson(requireNotNull(noDepthInit.rigEdits.modifierStack)).instances.isNotEmpty())
    }

    @Test fun modernBasesProvideLegacyEquivalentsInRegistryAndBundledManifests() {
        val pairs = listOf(
            "face/fitted" to "face/legacy",
            "eye/fitted" to "eye/legacy",
            "brow/fitted" to "brow/legacy",
            "mouth/fitted" to "mouth/legacy",
            "hair/chains" to "hair/legacy",
        )
        for ((modern, legacy) in pairs) {
            val def = assertNotNull(RigModifierAlgorithms.registry.defaultDefinition(modern), "Missing $modern")
            assertTrue(legacy in def.provides, "$modern must provide $legacy in registry")
            val bundled = assertNotNull(BundledAlgorithmArtifacts.find(modern, 1), "Missing bundled $modern")
            assertTrue(legacy in bundled.manifest.algorithms.single().provides, "$modern must provide $legacy in manifest")
        }
    }

    @Test fun initializationSucceedsWithDuplicateLayerNamesAcrossAllModes(): Unit = runBlocking {
        val base = Characters.head(physics = false)
        val template = base.source.layers.first()
        val duplicates = listOf(
            "eyelash-dup-l" to "eyelash",
            "eyelash-dup-r" to "eyelash",
            "eyebrow-dup-l" to "eyebrow",
            "eyebrow-dup-r" to "eyebrow",
            "eyewhite-dup-l" to "eyewhite",
            "eyewhite-dup-r" to "eyewhite",
            "irides-dup-l" to "irides",
            "irides-dup-r" to "irides",
            "ears-dup-l" to "ears",
            "ears-dup-r" to "ears",
            "handwear-dup-l" to "handwear",
            "handwear-dup-r" to "handwear",
        ).mapIndexed { idx, (id, name) ->
            WorkspaceSourceLayer(
                LayerId(id), name, "", SourceLayerKind.Raster, true, base.source.layers.size + idx,
                template.bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                template.raster, null, null, false,
            )
        }
        val doc = base.copy(
            source = WorkspaceSourceArt(base.source.widthPx, base.source.heightPx, base.source.layers + duplicates, base.source.groups),
            rigEdits = base.rigEdits.copy(modifierStack = RigModifierStack().toJson()),
        )
        val preview = WorkspacePreviewBuilder().build(doc)
        for (mode in listOf(
            WorkspaceInitialModifiers.RECOMMENDED,
            WorkspaceInitialModifiers.MINIMAL,
            WorkspaceInitialModifiers.DEFAULT,
            WorkspaceInitialModifiers.FULL,
        )) {
            val initialized = WorkspaceModifierEdits.apply(
                WorkspaceDocumentOperation("modifier_initialize", buildJsonObject { put("modifiers", mode.name.lowercase()) }),
                doc,
                preview,
            )
            assertTrue(RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack)).instances.isNotEmpty())
        }
    }

    @Test fun gloomAndCheekAcceptV2InstancesWithoutVersionRequirementFailure(): Unit = runBlocking {
        val base = Characters.head(physics = false)
        val doc = base.copy(rigEdits = base.rigEdits.copy(modifierStack = RigModifierStack().toJson()))
        val preview = WorkspacePreviewBuilder().build(doc)
        val initialized = WorkspaceModifierEdits.apply(
            WorkspaceDocumentOperation("modifier_initialize", buildJsonObject { put("modifiers", "recommended") }),
            doc,
            preview,
        )
        val stack = RigModifierStack.fromJson(requireNotNull(initialized.rigEdits.modifierStack))
        val upgraded = stack.copy(instances = stack.instances.map { entry ->
            if (entry.type in setOf("expression.gloom", "expression.cheek", "face.depth-turn", "body/legacy", "brow/legacy", "brow/fitted")) {
                entry.copy(version = 2)
            } else {
                entry
            }
        })
        val updatedDoc = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = upgraded.toJson()))
        val rebuilt = WorkspacePreviewBuilder().build(updatedDoc)
        assertTrue(rebuilt.rig.puppet.drawables.isNotEmpty())
    }

    @Test fun bundledPluginRetainPreservesExactSameVersionHistoryInRealStore() {
        val tempRoot = java.nio.file.Files.createTempDirectory("psd2live-store-test-")
        val prefs = java.util.prefs.Preferences.userRoot().node("io.github.psd2live.test/${java.util.UUID.randomUUID()}")
        try {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find("mouth/fitted", 1))
            val runtimeBytes = "stale-dev-build".encodeToByteArray()
            val runtimeDigest = ModifierPluginStore.hash(runtimeBytes)
            val staleManifest = artifact.manifest.copy(
                files = artifact.manifest.files.mapValues { (name, old) ->
                    if (name.endsWith(".jar")) runtimeDigest else old
                },
            )
            val staleBytes = kotlinx.serialization.json.Json.encodeToString(
                ModifierPluginManifest.serializer(),
                staleManifest,
            ).encodeToByteArray()
            val staleDigest = ModifierPluginStore.hash(buildString {
                append(ModifierPluginStore.hash(staleBytes))
                staleManifest.files.toSortedMap().forEach { (name, digest) -> append("\n$name:$digest") }
            }.encodeToByteArray())
            assertNotEquals(artifact.reference.digest, staleDigest)
            val staleDir = tempRoot.resolve(staleDigest)
            java.nio.file.Files.createDirectories(staleDir)
            java.nio.file.Files.write(staleDir.resolve("plugin.json"), staleBytes)
            artifact.manifest.files.keys.forEach { name ->
                val target = staleDir.resolve(name)
                java.nio.file.Files.createDirectories(target.parent)
                if (name.endsWith(".jar")) {
                    java.nio.file.Files.write(target, runtimeBytes)
                } else {
                    artifact.open(name)!!.use { java.nio.file.Files.copy(it, target) }
                }
            }
            java.nio.file.Files.writeString(staleDir.resolve(".dependencies"), "[]")

            val store = ModifierPluginStore(tempRoot, prefs, bundledWorkers = true)
            val historical = store.snapshot().single().reference
            assertEquals(staleDigest, historical.digest)
            assertTrue(store.resolution(historical).available)
            // Populate the catalog before archiving, which must also refresh it without changing the revision.
            val revision = store.revision.value
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val retained = store.packages().filter { it.reference.id == artifact.reference.id && it.reference.version == artifact.reference.version }
            assertEquals(setOf(staleDigest, artifact.reference.digest), retained.map { it.reference.digest }.toSet())
            assertEquals(revision, store.revision.value)
            assertTrue(java.nio.file.Files.isDirectory(staleDir))
            for (reopened in listOf(store, ModifierPluginStore(tempRoot, prefs, bundledWorkers = true))) {
                assertTrue(reopened.problems().isEmpty())
                assertTrue(reopened.resolution(historical).available)
                assertEquals(historical, reopened.verify(historical).reference)
                assertFalse(ModifierPluginCatalog.available("unrelated", 1, historical, reopened))
                assertFalse(ModifierPluginCatalog.available(staleManifest.algorithms.single().type, 99, historical, reopened))
                assertEquals(artifact.reference, reopened.defaultPackages().single { it.reference.id == historical.id }.reference)
                val type = artifact.manifest.algorithms.single().type
                assertEquals(artifact.reference, ModifierPluginCatalog.algorithm(type, store = reopened)?.first?.reference)
                val registry = RigModifierRegistry(ModifierPluginCatalog.definitions(reopened))
                assertEquals(artifact.reference, registry.defaultDefinition(type)?.pluginReference)
                val instance = RigModifierInstance("historical", type, 1, listOf(ModifierTarget("mesh", "mesh")), plugin = historical)
                val restored = RigModifierStack.fromJson(RigModifierStack(listOf(instance)).toJson()).instances.single()
                assertEquals(historical, registry.definition(restored).pluginReference)
                assertNull(registry.definitionOrNull(instance.copy(plugin = historical.copy(digest = "0".repeat(64)))))
            }
        } finally {
            runCatching { prefs.removeNode() }
            ModifierPluginStore.deleteTree(tempRoot)
        }
    }

    @Test fun realHostFlowInitializesPresetsAndAppliesMeshSplitModifier() = runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("psd2live-host-flow-")
        try {
            HeadlessPresentation().use { presentation ->
                WorkspaceHost(presentation, tempDir).use { host ->
                    presentation.attach(host)
                    val base = Characters.head(physics = false)
                    val emptyDoc = base.copy(rigEdits = base.rigEdits.copy(modifierStack = RigModifierStack().toJson()))
                    val preview = WorkspacePreviewBuilder().build(emptyDoc)
                    val initialized = WorkspaceModifierEdits.apply(
                        WorkspaceDocumentOperation("modifier_initialize", buildJsonObject { put("modifiers", "recommended") }),
                        emptyDoc,
                        preview,
                    )
                    val initPreview = WorkspacePreviewBuilder().build(initialized)
                    val targetId = initPreview.rig.puppet.drawables.first { it.mesh != null && it.id.raw in initPreview.rig.ownLayerIdByDrawableId }.id.raw
                    val withSplit = WorkspaceModifierEdits.apply(
                        WorkspaceDocumentOperation("modifier_add", buildJsonObject {
                            put("type", "mesh.split")
                            put("config", JsonObject(emptyMap()))
                            putJsonArray("targets") {
                                addJsonObject { put("kind", "mesh"); put("id", targetId) }
                            }
                        }),
                        initialized,
                        initPreview,
                    )
                    val finalPreview = WorkspacePreviewBuilder().build(withSplit)
                    assertTrue(finalPreview.rig.puppet.drawables.isNotEmpty())
                    assertTrue(RigModifierStack.fromJson(requireNotNull(withSplit.rigEdits.modifierStack)).instances.any { it.type == "mesh.split" })
                }
            }
        } finally {
            ModifierPluginStore.deleteTree(tempDir)
        }
    }
}
