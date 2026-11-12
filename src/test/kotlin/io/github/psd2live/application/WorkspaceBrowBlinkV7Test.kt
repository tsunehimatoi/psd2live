package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.BrowBlinkV7Source
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.BlendBinding
import io.github.psd2live.format.model.LatticeShape
import io.github.psd2live.format.model.PivotShape
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.KeyAxis
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.DrawableId
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Explicit blink7 source reconstruction with untouched historical provider implementations. */
class WorkspaceBrowBlinkV7Test {
    private val versions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2,
        "face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
        "brow.shape" to 6, "brow.lid-coupling" to 8, "brow.blink" to 7)

    private data class Shape(val id: String, val tag: SemanticTag, val side: Side,
                             val x: Int, val y: Int, val width: Int, val height: Int, val role: PartRole? = null)

    /** Single-eye rectangles need aligned upper/lower centers to be upright under the source estimator. */
    private fun artwork(mirror: Boolean = false, interiors: Int = 0, bilateral: Boolean = false,
                        upright: Boolean = true, scale: Int = 1): WorkspaceDocument {
        require(scale >= 1)
        val base = listOf(
            Shape("iris", SemanticTag.IRIDES, Side.LEFT, 37, 19, 5, 10),
            Shape("lash", SemanticTag.EYELASH, Side.LEFT, 34, 19, 12, 2),
            Shape("close", SemanticTag.EYE_CLOSE, Side.LEFT, 34, 25, 12, 2),
            Shape("white", SemanticTag.EYEWHITE, Side.LEFT, 34, 20, 12, 8),
            Shape("brow", SemanticTag.EYEBROW, Side.LEFT, 34, 14, 12, 2),
            Shape("mouth", SemanticTag.MOUTH, Side.NONE, 25, 35, 14, 6),
            Shape("front", SemanticTag.FRONT_HAIR, Side.NONE, 14, 4, 36, 13),
            Shape("face", SemanticTag.FACE, Side.NONE, 15, 8, 34, 40),
            Shape("back", SemanticTag.BACK_HAIR, Side.NONE, 12, 5, 40, 50),
            Shape("top", SemanticTag.TOPWEAR, Side.NONE, 12, 46, 40, 32),
            Shape("highlight", SemanticTag.IRIDES, Side.LEFT, 39, 20, 2, 3, PartRole.HIGHLIGHT),
            Shape("nose", SemanticTag.NOSE, Side.NONE, 29, 28, 5, 3),
        )
        val teeth = if (interiors == 0) emptyList() else listOf(
            Shape("teeth-top", SemanticTag.TOOTH_T, Side.NONE, 27, 35, 10, 2),
            Shape("teeth-bottom", SemanticTag.TOOTH_B, Side.NONE, 28, 39, 8, 2))
        val tongue = if (interiors < 2) emptyList() else listOf(Shape("tongue", SemanticTag.TONGUE, Side.NONE, 29, 38, 6, 3))
        val paired = if (!bilateral) emptyList() else base.filter { it.side == Side.LEFT }.map {
            it.copy(id = "${it.id}-right", side = Side.RIGHT, x = 64 - it.x - it.width)
        }
        val aligned = (base + teeth + tongue + paired).map {
            // The old mouth/nose center was x≈31.92 while the single upper-eye/brow center is x≈39.91.
            // Translate actual artwork, not the inferred angle or frame state. Keep the old art as a rejection case.
            if (upright && it.tag in setOf(SemanticTag.MOUTH, SemanticTag.NOSE)) it.copy(x = it.x + 8) else it
        }
        val shapes = aligned.map {
            if (!mirror) it else it.copy(x = 64 - it.x - it.width,
                side = when (it.side) { Side.LEFT -> Side.RIGHT; Side.RIGHT -> Side.LEFT; else -> it.side })
        }
        val scaled = shapes.map { it.copy(x = it.x * scale, y = it.y * scale,
            width = it.width * scale, height = it.height * scale) }
        val source = WorkspaceSourceArt(64 * scale, 80 * scale, scaled.mapIndexed { index, shape ->
            WorkspaceSourceLayer(LayerId(shape.id), shape.id, "", SourceLayerKind.Raster, true, index,
                LayerBounds(shape.x, shape.y, shape.width, shape.height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(shape.width, shape.height, ByteArray(shape.width * shape.height * 4) { 255.toByte() }),
                null, null, false)
        }, emptyList())
        val config = RigModifierPipeline.ordinaryConfig(PipelineConfig(atlasSize = if (scale == 1) 128 else 2048, meshSpacing = 6,
            meshInteriorDensity = if (scale == 1) 40f else 6f,
            meshFillAlgorithm = if (scale == 1) MeshFillAlgorithm.GRADED_POISSON else MeshFillAlgorithm.SIMPLE_TRIANGLES,
            recipe = RigRecipe.Legacy, generatePhysics = false, exportMoc3 = false))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        return WorkspaceDocument(source, emptyMap(), emptySet(), shapes.associate {
            it.id to LayerClassificationOverride(LayerType.PRESET, it.tag, it.side, role = it.role)
        }, emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
    }

    private data class Fixture(val document: WorkspaceDocument, val sourceModel: RigPreviewModel, val stack: RigModifierStack) {
        val brow get() = stack.instances.last()
        fun document(stack: RigModifierStack = this.stack) = document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
        fun source(stack: RigModifierStack = this.stack): RigModifierSource = RigModifierSource.of(sourceModel.copy(
            config = sourceModel.config.copy(rigEdits = sourceModel.config.rigEdits.copy(modifierStack = stack.toJson()))))
        fun definition(stack: RigModifierStack = this.stack) = RigBrowBlinkV7Modifier.definition(source(stack))
        fun context(stack: RigModifierStack = this.stack): ModifierAlgorithmContext =
            definition(stack).authorContext(stack.instances.last()).withUpstream(stack.instances.dropLast(1).map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            })
        fun version(version: Int) = stack.copy(instances = stack.instances.dropLast(1) + brow.copy(
            version = version, plugin = assertNotNull(BuiltinModifierPlugins.reference(brow.type, version))))
    }

    private fun fixture(art: WorkspaceDocument = artwork(), tuned: Boolean = false, eyeAddons: Boolean = false,
                        independentGaze: Boolean = false): Fixture {
        require(!independentGaze || eyeAddons)
        val selectedVersions = if (!eyeAddons) versions else linkedMapOf(
            "rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4,
            "brow.shape" to 6, "brow.lid-coupling" to 8, "brow.blink" to 7)
            .filterKeys { it != "eye.gaze-lid" || independentGaze }
        val choices = WorkspaceInitialPreset(clothing = false)
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val source = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildPreview(document.source, document.config())
        val targets = source.rig.puppet.drawables.filter { it.mesh != null && it.id.raw in source.rig.ownLayerIdByDrawableId }
        val planned = WorkspaceModifierPlan.plan(source, RigModifierStack(), buildJsonObject {
            put("group", "modern-standard")
            putJsonArray("targets") { targets.forEach { addJsonObject { put("kind", "mesh"); put("id", it.id.raw) } } }
        }, selectedVersions.keys.drop(1)).first
        val stack = planned.copy(instances = planned.instances.map { entry ->
            val version = selectedVersions.getValue(entry.type)
            val extra = if (!tuned) JsonObject(emptyMap()) else when (entry.type) {
                "body/legacy" -> buildJsonObject {
                    put("strength", .8f)
                    putJsonObject("tuning") {
                        put("breathLift", 4.25f); put("bodyZDegrees", 4.5f)
                        put("faceLeanUp", 11f); put("faceLeanDown", 7f)
                    }
                }
                "face/fitted" -> buildJsonObject {
                    put("strength", .75f)
                }
                "eye/fitted" -> buildJsonObject { putJsonObject("values") { put("lowerShare", 17f); put("roundness", 115f) } }
                "brow/fitted" -> buildJsonObject { putJsonObject("values") { put("lift", 67f) } }
                "brow.shape" -> buildJsonObject { putJsonObject("values") { put("strength", 127f) } }
                "brow.lid-coupling" -> buildJsonObject { putJsonObject("values") { put("strength", 145f) } }
                "brow.blink" -> buildJsonObject { putJsonObject("values") { put("dip", 128f) } }
                else -> JsonObject(emptyMap())
            }
            entry.copy(version = version, config = JsonObject(entry.config + extra),
                plugin = assertNotNull(BuiltinModifierPlugins.reference(entry.type, version)))
        })
        assertEquals(selectedVersions.toList(), stack.instances.map { it.type to it.version }, "Only the locked source prefix may precede blink7")
        return Fixture(document, source, stack)
    }

    private fun assertBytes(expected: ByteArray, actual: ByteArray, label: String) {
        if (expected.contentEquals(actual)) return
        val limit = minOf(expected.size, actual.size)
        val first = (0 until limit).firstOrNull { expected[it] != actual[it] } ?: limit
        fun sample(bytes: ByteArray) = bytes.slice(maxOf(0, first - 4) until minOf(bytes.size, first + 5))
            .joinToString(",") { (it.toInt() and 255).toString() }
        fail("$label: bytes differ at $first; lengths ${expected.size}/${actual.size}; " +
            "nearby expected=[${sample(expected)}], actual=[${sample(actual)}]")
    }

    private fun assertRig(expected: RigIR, actual: RigIR, label: String) {
        assertTrue(expected == actual, "$label: complete raw IR differs")
        assertBytes(RigIrBinary.encode(expected), RigIrBinary.encode(actual), "$label: binary IR")
    }

    /** Applying a prefix serializes texture bindings in sorted order; all other fields remain exact. */
    private fun assertBakedRig(expected: RigIR, actual: RigIR, label: String) {
        assertTrue(expected == actual, "$label: complete baked IR differs")
        // Baked texture binding serialization sorts map entries. Keep every other field exact.
        fun ordered(ir: RigIR) = ir.copy(textures = ir.textures.copy(bindings = ir.textures.bindings.toSortedMap()))
        assertRig(ordered(expected), ordered(actual), "$label: texture-binding order normalized")
    }


    private fun referenceContext(f: Fixture): ModifierAlgorithmContext {
        val stack = f.version(6)
        return RigBrowBlinkModifier.singleEyeDefinition(f.source(stack)).authorContext(stack.instances.last())
            .withUpstream(f.context().upstream)
    }

    @Test fun nativeOneSixAndSevenMatchExactlyOnTheSameRealPredecessor(): Unit = runBlocking {
        for (tuned in listOf(false, true)) for (eyeAddons in listOf(false, true)) {
            val f = fixture(tuned = tuned, eyeAddons = eyeAddons)
            assertTrue(f.definition().explicitVersion)
            assertNotEquals(7, assertNotNull(RigModifierAlgorithms.registry.defaultDefinition("brow.blink")).version)
            val native = WorkspacePreviewBuilder().build(f.document(f.version(1)))
            val six = WorkspacePreviewBuilder().build(f.document(f.version(6)))
            val seven = WorkspacePreviewBuilder().build(f.document())
            val expected = assertNotNull(native.modifierEvaluation)
            for ((label, model) in listOf("v6" to six, "v7" to seven)) {
                val actual = assertNotNull(model.modifierEvaluation)
                assertEquals(expected.outputs.size, actual.outputs.size)
                assertTrue(actual.entries.all { it.status == ModifierStatus.ACTIVE })
                expected.outputs.zip(actual.outputs).forEachIndexed { stage, (a, b) ->
                    assertRig(a, b, "$label stage $stage tuned=$tuned eyeAddons=$eyeAddons")
                }
                assertRig(RigIrCompiler.compile(native, tileArt = true), RigIrCompiler.compile(model, tileArt = true), label)
                assertEquals(native.rig.modifierRasterAssets, model.rig.modifierRasterAssets)
            }
            val before = expected.outputs[expected.outputs.lastIndex - 1]
            val after = expected.outputs.last()
            val context = f.context()
            val algorithm = f.definition().plugin as ReadingModifierAlgorithmPlugin
            assertRig(after, algorithm.evaluate(before, f.brow, context, {}), "same predecessor")
            val evaluator = CpuDeformationEvaluator()
            val id = DrawableId(before.meshes.single { it.name == "brow" }.id)
            val open = ParameterId("ParamEyeLOpen")
            val smile = ParameterId("ParamEyeLSmile")
            val poses = mutableListOf(mapOf(open to 0f))
            if (eyeAddons) poses += listOf(mapOf(open to 2f), mapOf(open to .4f, smile to 1f))
            for (pose in poses) assertFalse(
                evaluator.evaluate(PuppetIr.toPuppet(before), pose).worldPositions.getValue(id).contentEquals(
                    evaluator.evaluate(PuppetIr.toPuppet(after), pose).worldPositions.getValue(id)),
                "Blink, wide and smile must move the brow: $pose")
            val enabled = RigStageCache.enabled
            try {
                RigStageCache.enabled = false
                assertRig(after, assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs.last(), "cache disabled")
            } finally { RigStageCache.enabled = enabled }
        }
    }

    @Test fun numericAncestryRebuildsTheShapeSnapshotWithoutCapturedMotion() {
        for (tuned in listOf(false, true)) {
            val f = fixture(tuned = tuned)
            val context = f.context()
            assertEquals(JsonPrimitive("brow-blink/7"), context.values["schema"])
            assertEquals(setOf("schema", "brow", "source_ancestry", "providers", "provider_packages", "head_turn",
                "native_ids", "mesh_names", "turn_name", "shape_name"), context.values.keys)
            assertEquals(setOf("source_meshes"), context.resources.keys)
            val expected = RigIrBinary.decode(referenceContext(f).resources.getValue("brow_turn").read()).deformers
            assertTrue(expected.isNotEmpty())
            assertEquals(expected, BrowBlinkV7Source.branch(context))
            val dipped = f.brow.copy(config = JsonObject(f.brow.config + ("values" to buildJsonObject { put("dip", 63f) })))
            val recaptured = f.definition().authorContext(dipped)
            assertEquals(context.values, recaptured.values, "Source observations precede the consumer's dip")
            assertEquals(context.resources.keys, recaptured.resources.keys)
            context.resources.forEach { (key, resource) -> assertBytes(resource.read(), recaptured.resources.getValue(key).read(), key) }
        }
    }

    @Test fun shapeAndLidTuningCannotContaminateTheirEarlierNumericalSourceSnapshot() {
        val original = fixture(tuned = true, eyeAddons = true)
        val baseline = original.context()
        val sourceValues = baseline.values - "providers"
        val baselineBranch = BrowBlinkV7Source.branch(baseline)
        for (types in listOf(setOf("brow.shape"), setOf("brow.lid-coupling"),
            setOf("brow.shape", "brow.lid-coupling"))) {
            val changedStack = original.stack.copy(instances = original.stack.instances.map { entry ->
                if (entry.type !in types) entry else entry.copy(config = JsonObject(entry.config +
                    ("values" to buildJsonObject { put("strength", if (entry.type == "brow.shape") 73f else 82f) })))
            })
            val changed = original.copy(stack = changedStack)
            val captured = changed.context()
            assertNotEquals(baseline.values.getValue("providers"), captured.values.getValue("providers"),
                "The changed provider configuration must remain in the exact provider contract: $types")
            assertEquals(sourceValues, captured.values - "providers",
                "Only provider configuration may change; neither shape nor later lid tuning belongs to shape's source snapshot: $types")
            assertEquals(baseline.resources.keys, captured.resources.keys)
            baseline.resources.forEach { (key, resource) ->
                assertBytes(resource.read(), captured.resources.getValue(key).read(), "$types raw source $key")
            }
            assertEquals(baselineBranch, BrowBlinkV7Source.branch(captured), "$types reconstructed source ancestry")
            val historical = RigIrBinary.decode(referenceContext(changed).resources.getValue("brow_turn").read())
            assertEquals(baselineBranch, historical.deformers, "$types historical shape snapshot control")
        }
    }

    @Test fun staleGeometryAncestorsAndProvidersRejectButMetadataAndSiblingsSurvive(): Unit = runBlocking {
        val f = fixture(tuned = true)
        val evaluation = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation)
        val before = evaluation.outputs[evaluation.outputs.lastIndex - 1]
        val after = evaluation.outputs.last()
        val context = f.context()
        val algorithm = f.definition().plugin as ReadingModifierAlgorithmPlugin
        val input = ModifierEvaluationInput(before, context)
        algorithm.changes(input, f.brow, {})
        val reads = input.reads()
        assertTrue(reads.matches(before, context))
        val brow = before.meshes.single { it.name == "brow" }
        fun edited(mesh: io.github.psd2live.format.model.Mesh) = before.copy(meshes = before.meshes.map { if (it.id == brow.id) mesh else it })
        fun reject(changed: RigIR) {
            assertFalse(reads.matches(changed, context))
            assertFailsWith<IllegalArgumentException> { algorithm.evaluate(changed, f.brow, context, {}) }
        }
        val geometry = assertNotNull(brow.geometry)
        for (changed in listOf(brow.copy(parent = null), brow.copy(offsets = null), brow.copy(shapes = emptyList()),
            brow.copy(geometry = geometry.copy(positions = Floats.wrap(geometry.positions.toArray().also { it[0] += .01f }))),
            brow.copy(geometry = geometry.copy(indices = Ints.wrap(geometry.indices.toArray().also { it[0] = it[1] })))).filter { it != brow }) reject(edited(changed))
        val ancestors = ModifierEvaluationInput(before).parentsOfMesh(brow.id)
        assertTrue(ancestors.isNotEmpty())
        for (carrier in ancestors) {
            val variants = when (carrier) {
                is Deformer.Warp -> listOf(carrier.copy(opacity = .37f), carrier.copy(rows = carrier.rows + 1), carrier.copy(lattice = null),
                    carrier.copy(shapes = listOf(BlendBinding<LatticeShape>("ParamAngleX", Floats.wrap(floatArrayOf(-1f, 0f, 1f)), 1, listOf(null, null, null)))))
                is Deformer.Rotation -> listOf(carrier.copy(baseAngle = carrier.baseAngle + .01f), carrier.copy(pivot = null),
                    carrier.copy(shapes = listOf(BlendBinding<PivotShape>("ParamAngleX", Floats.wrap(floatArrayOf(-1f, 0f, 1f)), 1, listOf(null, null, null)))))
            }
            for (changed in variants.filter { it != carrier }) reject(before.copy(deformers = before.deformers.map { if (it.id == carrier.id) changed else it }))
        }
        for (provider in context.upstream) {
            assertFailsWith<IllegalArgumentException> {
                algorithm.evaluate(before, f.brow, context.withUpstream(context.upstream.filterNot { it == provider }), {})
            }
            val changed = provider.copy(outputAliases = provider.outputAliases + ("shared/deformer:wrong" to "wrong"))
            assertFailsWith<IllegalArgumentException> {
                algorithm.evaluate(before, f.brow, context.withUpstream(context.upstream.map { if (it == provider) changed else it }), {})
            }
        }
        val sibling = brow.copy(id = "blink7-unrelated-sibling", name = "Unrelated sibling", parent = null)
        val siblings = before.copy(meshes = before.meshes + sibling)
        // A carrier update observes full membership for foreign-descendant safety, so even a
        // harmless sibling may invalidate that cache. Re-evaluation must still preserve it.
        val output = algorithm.evaluate(siblings, f.brow, context, {})
        assertEquals(sibling, output.meshes.single { it.id == sibling.id })
        assertRig(after, output.copy(meshes = output.meshes.filterNot { it.id == sibling.id }), "sibling preserved")
        val metadata = brow.copy(name = "Authored brow", visible = false, selectable = false, userData = "Authored metadata")
        val preserved = algorithm.evaluate(edited(metadata), f.brow, context, {}).meshes.single { it.id == brow.id }
        assertEquals(metadata.name, preserved.name)
        assertEquals(metadata.visible, preserved.visible)
        assertEquals(metadata.selectable, preserved.selectable)
        assertEquals(metadata.userData, preserved.userData)
    }

    @Test fun installedWorkerMatchesBothReferencesAfterColdReinstall(): Unit = runBlocking {
        val f = fixture(tuned = true)
        val native = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(1))).modifierEvaluation).outputs
        val six = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(6))).modifierEvaluation).outputs
        val before = native[native.lastIndex - 1]
        assertRig(before, six[six.lastIndex - 1], "worker predecessor")
        assertRig(native.last(), six.last(), "worker expected references")
        val context = f.context()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("brow.blink", 7))
        assertEquals(artifact.reference, f.brow.plugin)
        val entries = java.util.zip.ZipInputStream(assertNotNull(artifact.open("algorithm.jar"))).use { zip ->
            buildSet { while (true) { val entry = zip.nextEntry ?: break; add(entry.name) } }
        }
        assertTrue(entries.any { it.endsWith("/BrowBlinkV7Algorithm.class") })
        assertTrue(entries.any { it.endsWith("/BrowBlinkV7Source.class") })
        assertTrue(entries.any { it.endsWith("/BrowSourceAncestryV1.class") })
        assertTrue(entries.any { it.endsWith("/BrowSourceLoweringV1.class") })
        assertTrue(entries.any { it.endsWith("/BrowBlinkV6Witnesses.class") })
        assertTrue(entries.any { it.endsWith("/BrowShapeCarrierScope.class") })
        assertTrue(entries.none { it.endsWith("/RigBuilder.class") || it.endsWith("/BrowBlinkV6Algorithm.class") ||
            it.endsWith("/BrowBlinkAlgorithm.class") || it.endsWith("/BrowSpringV8Algorithm.class") })
        val directory = Files.createTempDirectory("brow-blink-v7-worker-")
        val preferences = Preferences.userRoot().node("brow-blink-v7-worker/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name -> assertNotNull(artifact.open(name)).use { Files.copy(it, staging.resolve(name)) } }
            repeat(2) { run ->
                val installed = store.install(staging.toString())
                assertEquals(f.definition().requires, installed.manifest.algorithms.single().requires)
                assertEquals(f.definition().changeWrites, installed.manifest.algorithms.single().writes)
                assertRig(native.last(), ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                    before, f.brow, {}, store, context = context, declaredParameters = f.definition().parameters(f.brow)), "installed JVM $run")
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun appliedPrefixArchiveRecapturesBlinkSevenWithCacheDisabled(): Unit = runBlocking {
        val f = fixture()
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) },
            rebuildFrom = { next, previous -> builder.build(next, previous) })
        val document = f.document()
        val model = builder.build(document)
        val expected = RigIrCompiler.compile(model, tileArt = true)
        val originalContext = assertNotNull(assertNotNull(model.modifierEvaluation).readAlgorithms[f.brow.id]).context
        runtime.install(runtime.state.value.state, "source", document, model)
        val directory = Files.createTempDirectory("brow-blink-v7-replay-")
        try {
            val repository = ProjectRepository(writeHeadCache = false)
            suspend fun saveAndReopen(name: String, applied: Boolean) {
                val capture = runtime.capture()
                val store = WorkspaceStore(directory.resolve("$name-store"))
                store.persistHistory(capture.projectId, runtime.history())
                val archive = directory.resolve("$name.psd2live")
                repository.save(ProjectSaveCapture(capture.projectId, runtime.history(), JsonObject(emptyMap()), null, store), archive)
                repository.open(archive).use { opened ->
                    val restored = opened.history.head().snapshot
                    val stack = RigModifierStack.fromJson(assertNotNull(restored.rigEdits.modifierStack))
                    assertEquals(f.brow, stack.instances.single { it.id == f.brow.id })
                    if (applied) {
                        assertEquals(listOf(f.brow), stack.instances)
                        for (provider in f.stack.instances.dropLast(1)) {
                            val retained = stack.appliedCapabilities.single { it.id == provider.id }
                            assertEquals(provider.version, retained.version)
                            assertEquals(provider.plugin, retained.plugin)
                            assertEquals(provider.config, retained.config)
                            assertEquals(provider.outputAliases, retained.outputAliases)
                        }
                    } else assertEquals(f.stack, stack)
                    val enabled = RigStageCache.enabled
                    try {
                        RigStageCache.enabled = false
                        val rebuilt = builder.build(restored)
                        assertBakedRig(expected, RigIrCompiler.compile(rebuilt, tileArt = true), name)
                        val context = assertNotNull(assertNotNull(rebuilt.modifierEvaluation).readAlgorithms[f.brow.id]).context
                        assertEquals(originalContext.values, context.values)
                        assertEquals(originalContext.resources.keys, context.resources.keys)
                        originalContext.resources.forEach { (key, resource) ->
                            assertBytes(resource.read(), context.resources.getValue(key).read(), "$name $key")
                        }
                    } finally { RigStageCache.enabled = enabled }
                }
            }
            saveAndReopen("explicit", false)
            val current = runtime.capture()
            val lid = f.stack.instances.single { it.type == "brow.lid-coupling" }
            WorkspaceDocumentCommands(runtime).execute(current.projectId, current.state, "modifier_apply",
                listOf(WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", lid.id) })), MutationAuthor.USER)
            assertBakedRig(expected, RigIrCompiler.compile(runtime.capture().model, tileArt = true), "applied prefix live")
            saveAndReopen("applied-prefix", true)
        } finally { ModifierPluginStore.deleteTree(directory) }
    }


    @Test fun aliasedProvidersReconstructTheSameCanonicalSourceBranch(): Unit = runBlocking {
        val plain = fixture(tuned = true)
        val reference = WorkspacePreviewBuilder().build(plain.document(plain.version(6)))
        val owned = assertNotNull(reference.modifierEvaluation).outputs.last()
        val aliased = plain.stack.copy(instances = plain.stack.instances.map { entry ->
            val locals = owned.deformers.mapNotNull { entry.localOutputName(it.id) }
            entry.copy(outputAliases = locals.withIndex().associate { (index, local) -> local to "blink7-${entry.id}-$index" })
        })
        assertTrue(aliased.instances.any { it.outputAliases.isNotEmpty() })
        val f = plain.copy(stack = aliased)
        assertEquals("brow-blink/7", f.context().values.getValue("schema").jsonPrimitive.content)
        val native = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(1))).modifierEvaluation).outputs
        val six = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(6))).modifierEvaluation).outputs
        val seven = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        native.zip(seven).forEachIndexed { stage, (a, b) -> assertRig(a, b, "aliased native stage $stage") }
        six.zip(seven).forEachIndexed { stage, (a, b) -> assertRig(a, b, "aliased v6 stage $stage") }
    }



    @Test fun supportedEyeAddonPrefixKeepsExactHistoricalBlinkOutput(): Unit = runBlocking {
        val f = fixture(tuned = true, eyeAddons = true)
        val context = f.context()
        assertEquals(RigIrBinary.decode(referenceContext(f).resources.getValue("brow_turn").read()).deformers,
            BrowBlinkV7Source.branch(context))
        val native = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(1))).modifierEvaluation).outputs
        val six = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(6))).modifierEvaluation).outputs
        val seven = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        assertEquals(native.size, seven.size)
        native.zip(seven).forEachIndexed { stage, (a, b) -> assertRig(a, b, "eye addons native stage $stage") }
        six.zip(seven).forEachIndexed { stage, (a, b) -> assertRig(a, b, "eye addons v6 stage $stage") }
    }

    @Test fun independentGazeAncestorShapesStayOutsideExplicitCohort(): Unit = runBlocking {
        val f = fixture(tuned = true, eyeAddons = true, independentGaze = true)
        val six = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(6))).modifierEvaluation)
        assertTrue(six.entries.all { it.status == ModifierStatus.ACTIVE })
        val before = six.outputs[six.outputs.lastIndex - 1]
        val input = ModifierEvaluationInput(before)
        val ancestors = f.brow.targets.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
        assertTrue(ancestors.filterIsInstance<Deformer.Warp>().any { node ->
            node.shapes.any { it.parameter == "ParamEyeBallLX" } && node.shapes.any { it.parameter == "ParamEyeBallLY" }
        }, "Real gaze v3 must produce the independent iris-ancestor shapes excluded by v7")
        assertFailsWith<IllegalArgumentException> { f.definition().providers(f.brow) }
        assertFailsWith<IllegalArgumentException> { f.context() }
    }

    @Test fun intersectingUnknownPartialForeignAndAnonymousAppliedOwnersRejectBeforeCapture(): Unit = runBlocking {
        val f = fixture()
        val full = f.brow.targets
        val unknown = RigModifierInstance("foreign-source-owner", "external.producer", 1, full,
            config = buildJsonObject { put("scaffold", f.brow.config.getValue("scaffold")) })
        val face = f.stack.instances.single { it.type == "face/fitted" }
        val unknowns = listOf(
            f.stack.copy(instances = f.stack.instances.dropLast(1) + unknown + f.brow),
            f.stack.copy(appliedCapabilities = listOf(ModifierCapability("external.producer", 1, full))),
            f.stack.copy(appliedCapabilities = listOf(ModifierCapability("external.producer", 1, full, id = "applied-unknown"))),
            f.stack.copy(appliedCapabilities = listOf(face.capability().copy(id = null))),
        )
        val changedOwners = listOf(face.copy(targets = full.take(1)), face.copy(plugin = null))
        val changedStacks = changedOwners.map { changed -> f.stack.copy(instances = f.stack.instances.map { if (it == face) changed else it }) }
        for ((index, stack) in (unknowns + changedStacks).withIndex()) {
            assertFailsWith<IllegalArgumentException>("owner admission $index") { f.definition(stack).providers(f.brow) }
            assertFailsWith<IllegalArgumentException>("source admission $index") { f.definition(stack).authorContext(f.brow) }
        }
        // A face referencing an unavailable scaffold is already paused, so sourcePrefix omits it.
        // Return a genuinely unavailable required provider, then pause without entering capture.
        val foreignFace = face.copy(config = JsonObject(face.config + ("scaffold" to JsonPrimitive("foreign-scaffold"))))
        val paused = f.stack.copy(instances = f.stack.instances.map { if (it == face) foreignFace else it })
        val resolved = f.definition(paused).providers(f.brow).getValue("face/fitted")
        assertTrue(paused.instances.none { it.id == resolved })
        assertFailsWith<IllegalArgumentException> { f.definition(paused).authorContext(f.brow) }
        val disjoint = f.stack.copy(appliedCapabilities = listOf(ModifierCapability("external.producer", 1,
            listOf(ModifierTarget("mesh", "unrelated-source")))))
        assertEquals(f.definition().authorContext(f.brow), f.definition(disjoint).authorContext(f.brow),
            "Disjoint anonymous owners must not contaminate this source capture")
    }

    @Test fun ambiguousCanonicalParentAndBrokenAncestorChainReject(): Unit = runBlocking {
        val f = fixture()
        val outputs = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        val before = outputs[outputs.lastIndex - 1]
        val algorithm = f.definition().plugin as ReadingModifierAlgorithmPlugin
        val context = f.context()
        val scaffold = f.stack.instances.single { it.type == "rig.scaffold" }
        val face = f.stack.instances.single { it.type == "face/fitted" }
        val canonical = "shared/deformer:DeformHeadRotation"
        val head = assertIs<Deformer.Rotation>(before.deformers.single { it.id == scaffold.outputId(canonical) })
        val alternate = head.copy(id = face.outputId(canonical), name = "Alternate canonical owner")
        assertNotEquals(head.id, alternate.id)
        assertTrue(before.deformers.none { it.id == alternate.id })
        val variants = listOf(
            before.copy(deformers = before.deformers.map { if (it.id == head.id) head.copy(parent = alternate.id) else it } + alternate),
            before.copy(deformers = before.deformers.map { if (it.id == head.id) head.copy(parent = "missing-parent") else it }),
            before.copy(deformers = before.deformers.map { if (it.id == head.id) head.copy(parent = head.id) else it }),
        )
        for ((index, changed) in variants.withIndex()) {
            assertFailsWith<IllegalArgumentException>("ancestor ambiguity $index") { algorithm.evaluate(changed, f.brow, context, {}) }
        }
        assertRig(outputs.last(), algorithm.evaluate(before, f.brow, context, {}), "original chain remains unchanged")
    }


    @Test fun nestedMotionPayloadsAndMissingSourceInventoryReject(): Unit = runBlocking {
        val f = fixture()
        val context = f.context()
        val outputs = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        val before = outputs[outputs.lastIndex - 1]
        val algorithm = f.definition().plugin as ReadingModifierAlgorithmPlugin
        fun replaceObject(root: JsonObject, path: List<String>, change: (JsonObject) -> JsonObject): JsonObject {
            if (path.isEmpty()) return change(root)
            val key = path.first()
            return JsonObject(root + (key to replaceObject(root.getValue(key).jsonObject, path.drop(1), change)))
        }
        fun captured(values: JsonObject) = ModifierAlgorithmContext(values, context.resources, context.upstream)
        val paths = listOf(
            listOf("source_ancestry", "face"),
            listOf("source_ancestry", "face", "tuning"),
            listOf("source_ancestry", "body", "tuning"),
            listOf("source_ancestry", "fallback"),
            listOf("source_ancestry", "fallback", "style"),
            listOf("head_turn"),
        )
        for (path in paths) for (forbidden in listOf("source_branch", "brow_turn", "generated_grid")) {
            val corrupted = captured(replaceObject(context.values, path) {
                JsonObject(it + (forbidden to buildJsonObject { put("motion", 1f) }))
            })
            assertFailsWith<IllegalArgumentException>("nested ${path.joinToString(".")}.$forbidden source") {
                BrowBlinkV7Source.branch(corrupted)
            }
            assertFailsWith<IllegalArgumentException>("nested ${path.joinToString(".")}.$forbidden worker") {
                algorithm.evaluate(before, f.brow, corrupted, {})
            }
        }
        val landmarkValues = replaceObject(context.values, listOf("source_ancestry", "fallback")) { fallback ->
            val landmarks = fallback.getValue("landmarks").jsonArray
            val changed = if (landmarks.isEmpty()) listOf(buildJsonObject { put("generated_grid", 1f) })
                else landmarks.mapIndexed { index, element -> if (index != 0) element else
                    JsonObject(element.jsonObject + ("generated_grid" to JsonPrimitive(1f))) }
            JsonObject(fallback + ("landmarks" to JsonArray(changed)))
        }
        assertFailsWith<IllegalArgumentException> { BrowBlinkV7Source.branch(captured(landmarkValues)) }
        assertFailsWith<IllegalArgumentException> { algorithm.evaluate(before, f.brow, captured(landmarkValues), {}) }
        val incomplete = replaceObject(context.values, listOf("source_ancestry")) { ancestry ->
            val layers = ancestry.getValue("layers").jsonArray
            val nonBrow = layers.first { it.jsonObject.getValue("tag") != JsonPrimitive("EYEBROW") }
            JsonObject(ancestry + ("layers" to JsonArray(layers.filterNot { it == nonBrow })))
        }
        assertFailsWith<IllegalArgumentException>("Missing even an unrelated source target must reject") {
            algorithm.evaluate(before, f.brow, captured(incomplete), {})
        }
        assertRig(outputs.last(), algorithm.evaluate(before, f.brow, context, {}), "uncorrupted numeric contract")
    }

    @Test fun originalParentOverridesAndInvalidAuthoredWarpsRemainBlocked() {
        val f = fixture()
        val config = f.sourceModel.config
        val source = f.source()
        val browId = source.primaryByLayer.getValue("brow")
        val alternatives = listOf(
            config.copy(rigEdits = config.rigEdits.copy(skeleton = SkeletonSpec())),
            config.copy(parentOverrides = mapOf("brow" to "DeformFace")),
            config.copy(rigEdits = config.rigEdits.copy(warpEdits = listOf(
                RigWarpEdit("blink7-authored-warp", "Authored warp", "DeformFace", listOf(browId))))),
        )
        for ((index, changed) in alternatives.withIndex()) {
            // Keep the original source model and mutate its original config only. A sanitized
            // native config would erase precisely the author structure this boundary must reject.
            val modified = f.copy(sourceModel = f.sourceModel.copy(config = changed))
            if (index < 2) {
                assertFailsWith<IllegalArgumentException>("original skeleton or parent override") {
                    modified.definition().authorContext(f.brow)
                }
            } else {
                // Ordinary source content has no DeformFace yet. Real source restoration rejects
                // this authored warp before v7 is reached; do not invent a synthetic parent to hide that.
                val failure = assertFailsWith<IllegalStateException>("invalid original authored warp") {
                    modified.definition().authorContext(f.brow)
                }
                assertTrue(failure.message.orEmpty().startsWith("Parent must be an existing Warp"))
            }
        }
        assertEquals("brow-blink/7", f.context().values.getValue("schema").jsonPrimitive.content)
    }

    @Test fun mirroredAndRolledSourceStayOutsideExplicitCohort() {
        for (art in listOf(artwork(mirror = true), artwork(upright = false), artwork(bilateral = true), artwork(interiors = 1), artwork(interiors = 2))) {
            val f = fixture(art)
            assertFailsWith<IllegalArgumentException> { f.context() }
        }
    }


    @Test fun supportedSourceWitnessesRejectCorruptionWithoutAssumingAnEyeCarrier(): Unit = runBlocking {
        // The admitted compact no-gaze source has a real ordinary eye mesh witness. Its
        // lowering layout is not required to include an eye-open carrier. Real missing-default
        // carrier coverage belongs to the separate historical source-contract test below.
        val f = fixture(tuned = true, eyeAddons = true)
        val evaluation = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation)
        assertTrue(evaluation.entries.all { it.status == ModifierStatus.ACTIVE })
        val outputs = evaluation.outputs
        val before = outputs[outputs.lastIndex - 1]
        for (version in listOf(1, 6)) {
            val historical = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(version))).modifierEvaluation)
            assertTrue(historical.entries.all { it.status == ModifierStatus.ACTIVE })
            assertRig(before, historical.outputs[historical.outputs.lastIndex - 1], "supported native$version predecessor")
            assertRig(historical.outputs.last(), outputs.last(), "supported native$version blink result")
        }
        val context = f.context()
        val algorithm = f.definition().plugin as ReadingModifierAlgorithmPlugin
        val observed = ModifierEvaluationInput(before, context)
        val changes = algorithm.changes(observed, f.brow, {})
        val reads = observed.reads()
        fun reject(changed: RigIR) {
            assertFalse(reads.matches(changed, context), "Changed witness invalidates the observation cache")
            assertFailsWith<IllegalArgumentException> { algorithm.evaluate(changed, f.brow, context, {}) }
        }
        val open = "ParamEyeLOpen"
        val mesh = before.meshes.first { it.offsets?.axes.orEmpty().any { axis -> axis.parameter == open } }
        val grid = assertNotNull(mesh.offsets)
        assertTrue(grid.axes.single { it.parameter == open }.keys.toArray().any { it == 1f })
        val changedGrid = grid.copy(axes = grid.axes.map { axis ->
            if (axis.parameter != open) axis else axis.copy(keys = Floats.wrap(axis.keys.toArray().map {
                if (it == 1f) 1.001f else it
            }.toFloatArray()))
        })
        reject(before.copy(meshes = before.meshes.map { if (it.id != mesh.id) it else mesh.copy(offsets = changedGrid) }))
        val openCarriers = before.deformers.filterIsInstance<Deformer.Warp>().filter { node ->
            node.lattice?.axes.orEmpty().any { it.parameter == open }
        }
        // Check any eye carriers this real pipeline actually produced, without inventing one
        // or claiming the loop covers a missing-default carrier when the layout has none.
        for (carrier in openCarriers) {
            val lattice = assertNotNull(carrier.lattice)
            val changedLattice = lattice.copy(axes = lattice.axes.map { axis ->
                if (axis.parameter != open) axis else axis.copy(keys = Floats.wrap(axis.keys.toArray().also {
                    it[it.lastIndex] += .01f
                }))
            })
            reject(before.copy(deformers = before.deformers.map {
                if (it.id != carrier.id) it else carrier.copy(lattice = changedLattice)
            }))
        }
        val axes = setOf(open, "ParamEyeLSmile")
        reject(before.copy(meshes = before.meshes.map { mesh ->
            if (mesh.offsets?.axes.orEmpty().any { it.parameter in axes }) mesh.copy(offsets = null) else mesh
        }, deformers = before.deformers.map { node ->
            if (node is Deformer.Warp && node.lattice?.axes.orEmpty().any { it.parameter in axes }) node.copy(lattice = null) else node
        }))
        val brow = before.meshes.single { it.name == "brow" }
        for (update in changes.deformers.updated) {
            reject(before.copy(meshes = before.meshes + brow.copy(id = "foreign-descendant", parent = update.before.id)))
            reject(before.copy(deformers = before.deformers + Deformer.Rotation("foreign-descendant", "Foreign",
                update.before.id, null, 0f, null)))
        }
        val afterById = outputs.last().deformers.associateBy { it.id }
        val expectedUpdates = before.deformers.mapNotNull { prior ->
            val after = assertNotNull(afterById[prior.id])
            ModifierObjectUpdate(prior, after).takeIf { prior != after }
        }
        assertEquals(expectedUpdates.associateBy { it.before.id }, changes.deformers.updated.associateBy { it.before.id })
        assertTrue(changes.deformers.added.isNotEmpty() || changes.deformers.updated.isNotEmpty() || changes.meshes.updated.isNotEmpty())
    }

    @Test fun denseChainRejectedByFrozenLid8(): Unit = runBlocking {
        // This exact ordinary dense source is outside the realizable frozen-lid8 cohort.
        // Keep the observed boundary explicit rather than weakening predecessor checks or
        // claiming a fabricated carrier as Blink7 end-to-end coverage.
        val f = fixture(artwork(scale = 24), tuned = true)
        val untilShape = f.stack.copy(instances = f.stack.instances.dropLast(2))
        assertEquals("brow.shape", untilShape.instances.last().type)
        val prior = assertNotNull(WorkspacePreviewBuilder().build(f.document(untilShape)).modifierEvaluation)
        assertTrue(prior.entries.all { it.status == ModifierStatus.ACTIVE }, "Real dense prefix through shape6 must succeed")
        val input = ModifierEvaluationInput(prior.outputs.last())
        val lash = prior.outputs.last().meshes.single { it.name == "lash" }
        val liveIds = input.parentsOfMesh(lash.id).mapTo(hashSetOf()) { it.id }
        val expectedSlot = "ArtMeshEyelashL_Turn"
        val lidDefinition = RigBrowLidModifier.singleEyeDefinition(f.source())
        val observedTypes = lidDefinition.requires + lidDefinition.optionalUpstream
        val candidates = untilShape.instances.filter { owner -> owner.type in observedTypes &&
            ModifierTarget("mesh", lash.id) in owner.targets &&
            owner.config["scaffold"] == f.brow.config["scaffold"] }.map { owner ->
            io.github.psd2live.algorithms.binding.ScaffoldBinding.deformerId(owner, lash.id, expectedSlot, shared = true)
        }.distinct()
        assertNotEquals(1, candidates.count { it in liveIds }, "Frozen lid8 cannot uniquely resolve its reconstructed before carrier")
        val withoutBlink = f.stack.copy(instances = f.stack.instances.dropLast(1))
        for ((label, stack) in listOf("no blink" to withoutBlink) + listOf(1, 6, 7).map { "blink$it" to f.version(it) }) {
            val failure = assertFailsWith<IllegalArgumentException>(label) {
                WorkspacePreviewBuilder().build(f.document(stack))
            }
            assertEquals("Cannot resolve prior lid carrier $expectedSlot", failure.message, label)
            assertTrue(failure.stackTrace.any { it.className.endsWith("BrowLidV8Algorithm") && it.methodName == "changes" }, label)
        }
    }

    @Test fun historicalNativeCarrierWithoutDefaultKnotKeepsFrozenWitnessContract(): Unit = runBlocking {
        // This is a genuine historical Blink6 prefix, not a synthesized v7 predecessor.
        // Gaze3 creates iris-ancestor blend shapes and is deliberately rejected by v7.
        val f = fixture(tuned = true, eyeAddons = true, independentGaze = true)
        val six = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(6))).modifierEvaluation)
        val native = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(1))).modifierEvaluation)
        assertTrue(six.entries.all { it.status == ModifierStatus.ACTIVE })
        assertTrue(native.entries.all { it.status == ModifierStatus.ACTIVE })
        val before = six.outputs[six.outputs.lastIndex - 1]
        assertRig(native.outputs[native.outputs.lastIndex - 1], before, "historical gaze predecessor")
        assertRig(native.outputs.last(), six.outputs.last(), "historical gaze blink result")
        assertFailsWith<IllegalArgumentException> { f.context() }
        val input = ModifierEvaluationInput(before)
        val brow = before.meshes.single { it.name == "brow" }
        val eye = f.stack.instances.single { it.type == "eye/fitted" }
        val parameter = before.parameters.single { it.id == "ParamEyeLOpen" }
        assertEquals(2f, parameter.max)
        assertEquals(1f, parameter.default)
        val meshes = eye.targets.filter { it.id != brow.id }.mapNotNull { input.mesh(it.id) }
            .flatMap { it.offsets?.axes.orEmpty() }.filter { it.parameter == parameter.id }
        val carriers = eye.targets.filter { it.id != brow.id }.flatMap { input.parentsOfMesh(it.id) }
            .distinctBy { it.id }.filterIsInstance<Deformer.Warp>()
            .flatMap { it.lattice?.axes.orEmpty() }.filter { it.parameter == parameter.id }
        val limited = assertNotNull(carriers.firstOrNull { axis ->
            axis.keys.toArray().none { it == parameter.default }
        }, "Expected genuine historical fade-limited carrier; actual keys=" + carriers.map { it.keys.toArray().toList() })
        assertTrue(meshes.isNotEmpty() && meshes.all { axis -> axis.keys.toArray().any { it == parameter.default } })
        io.github.psd2live.algorithms.binding.BrowBlinkV6Witnesses.check(parameter, meshes, carriers)
        io.github.psd2live.algorithms.binding.BrowBlinkV6Witnesses.check(parameter, emptyList(), listOf(limited))
        // Deliberate corruption is a negative contract probe, never called native output.
        val corrupted = limited.copy(keys = Floats.wrap(limited.keys.toArray().also { it[it.lastIndex] += .01f }))
        assertFailsWith<IllegalArgumentException> {
            io.github.psd2live.algorithms.binding.BrowBlinkV6Witnesses.check(parameter, meshes, listOf(corrupted))
        }
    }

    @Test fun incompatibleRequiredVersionsAndPriorBlinkOrSpringRejectBeforeCapture() {
        val f = fixture()
        for (provider in f.stack.instances.dropLast(1)) {
            val changed = provider.copy(version = provider.version + 100)
            val stack = f.stack.copy(instances = f.stack.instances.map { if (it.id == provider.id) changed else it })
            assertFailsWith<IllegalArgumentException>(provider.type) { f.definition(stack).providers(f.brow) }
            assertFailsWith<IllegalArgumentException>(provider.type) { f.definition(stack).authorContext(f.brow) }
        }
        for (type in listOf("brow.blink", "brow.spring", "mouth.cheek-lid", "face.depth-turn")) {
            val preceding = f.brow.copy(id = "unsupported-prefix", type = type, version = 1,
                plugin = assertNotNull(BuiltinModifierPlugins.reference(type, 1)))
            val stack = f.stack.copy(instances = f.stack.instances.dropLast(1) + preceding + f.brow)
            assertFailsWith<IllegalArgumentException>(type) { f.definition(stack).providers(f.brow) }
            assertFailsWith<IllegalArgumentException>(type) { f.definition(stack).authorContext(f.brow) }
        }
    }

    @Test fun changedCarrierScopeRejectsForeignDescendantsWithoutRejectingSharedSiblings() {
        assertChangedBrowCarrierScope()
    }

    private fun assertChangedBrowCarrierScope() {
        val shared = io.github.psd2live.format.model.Deformer.Rotation("shared", "Shared", null, null, 0f, null)
        val carrier = io.github.psd2live.format.model.Deformer.Rotation("carrier", "Carrier", shared.id, null, 0f, null)
        val inner = io.github.psd2live.format.model.Deformer.Rotation("inner", "Inner", carrier.id, null, 0f, null)
        val brow = io.github.psd2live.format.model.Mesh("brow", "Brow", inner.id, geometry = null, offsets = null)
        val sibling = brow.copy(id = "shared-sibling", parent = shared.id)
        val before = RigIR(io.github.psd2live.format.model.Canvas(1f, 1f), emptyList(),
            deformers = listOf(shared, carrier, inner), meshes = listOf(brow, sibling))
        val update = ModifierObjectUpdate<io.github.psd2live.format.model.Deformer>(carrier, carrier.copy(baseAngle = 1f))
        val branch = setOf(shared.id, carrier.id, inner.id)
        fun check(input: ModifierEvaluationInput, updates: List<ModifierObjectUpdate<io.github.psd2live.format.model.Deformer>> = listOf(update)) =
            io.github.psd2live.algorithms.binding.BrowShapeCarrierScope.check(input, updates, brow.id, branch, {})
        val observed = ModifierEvaluationInput(before)
        check(observed) // The unchanged shared ancestor already has an unrelated mesh and stays legal.
        val reads = observed.reads()
        assertTrue(ModifierReadKey("mesh") in reads.keys)
        assertTrue(ModifierReadKey("deformer") in reads.keys)
        for (parent in listOf(carrier.id, inner.id)) {
            val foreignMesh = brow.copy(id = "foreign-mesh", parent = parent)
            val withMesh = before.copy(meshes = before.meshes + foreignMesh)
            assertFalse(reads.matches(withMesh))
            val meshFailure = assertFailsWith<IllegalArgumentException> { check(ModifierEvaluationInput(withMesh)) }
            assertContains(meshFailure.message.orEmpty(), "used by foreign mesh foreign-mesh")
            val foreignDeformer = carrier.copy(id = "foreign-deformer", parent = parent)
            val withDeformer = before.copy(deformers = before.deformers + foreignDeformer)
            assertFalse(reads.matches(withDeformer))
            val deformerFailure = assertFailsWith<IllegalArgumentException> { check(ModifierEvaluationInput(withDeformer)) }
            assertContains(deformerFailure.message.orEmpty(), "used by foreign deformer foreign-deformer")
        }
        val unchanged = ModifierEvaluationInput(before)
        check(unchanged, listOf(ModifierObjectUpdate(shared, shared)))
        assertTrue(unchanged.reads().keys.isEmpty(), "Unchanged shared ancestors must not trigger the scope restriction")
    }

    @Test fun frozenWitnessGuardSeparatesMeshDefaultsFromLoweredCarrierLimits() {
        val parameter = io.github.psd2live.format.model.Parameter("ParamEyeLOpen", "Open", 0f, 2f, 1f)
        // A source eye grid has the default knot; a new lowering carrier is keyed by the
        // coupling's fade limit and parameter endpoints, not necessarily that default.
        val mesh = KeyAxis(parameter.id, Floats.values(parameter.min, .2f, parameter.default, parameter.max))
        val carrier = KeyAxis(parameter.id, Floats.values(parameter.min, .2f, .6f, parameter.max))
        fun check(meshes: List<KeyAxis> = listOf(mesh), carriers: List<KeyAxis> = listOf(carrier),
                  control: io.github.psd2live.format.model.Parameter = parameter) =
            io.github.psd2live.algorithms.binding.BrowBlinkV6Witnesses.check(control, meshes, carriers)
        check()
        check(carriers = emptyList())
        check(meshes = emptyList())
        for (keys in listOf(floatArrayOf(0f, .2f, .6f, 2f), floatArrayOf(0f, 1f, 1.9f),
            floatArrayOf(0f, 1f, 1f, 2f), floatArrayOf(0f, Float.NaN, 1f, 2f)))
            assertFailsWith<IllegalArgumentException> { check(meshes = listOf(mesh.copy(keys = Floats.wrap(keys)))) }
        for (keys in listOf(floatArrayOf(.01f, .2f, .6f, 2f), floatArrayOf(0f, .2f, .6f, 1.9f),
            floatArrayOf(0f, .6f, .2f, 2f), floatArrayOf(0f, .2f, .2f, 2f), floatArrayOf(0f, Float.NaN, 2f)))
            assertFailsWith<IllegalArgumentException> { check(carriers = listOf(carrier.copy(keys = Floats.wrap(keys)))) }
        assertFailsWith<IllegalArgumentException> { check(emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { check(control = parameter.copy(blend = true)) }
    }
}
