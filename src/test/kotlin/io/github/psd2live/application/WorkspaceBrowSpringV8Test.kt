package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.BrowSpringV8Source
import io.github.psd2live.algorithms.binding.BrowSourceAncestryV1
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.BlendBinding
import io.github.psd2live.format.model.LatticeShape
import io.github.psd2live.format.model.PivotShape
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.Floats
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

/** Explicit spring8 source reconstruction with untouched historical provider implementations. */
class WorkspaceBrowSpringV8Test {
    private val versions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2,
        "face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
        "brow.shape" to 6, "brow.lid-coupling" to 8, "brow.blink" to 6, "brow.spring" to 8)

    private data class Shape(val id: String, val tag: SemanticTag, val side: Side,
                             val x: Int, val y: Int, val width: Int, val height: Int, val role: PartRole? = null)

    /** Single-eye rectangles need aligned upper/lower centers to be upright under the source estimator. */
    private fun artwork(mirror: Boolean = false, interiors: Int = 0, bilateral: Boolean = false,
                        upright: Boolean = true): WorkspaceDocument {
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
        val source = WorkspaceSourceArt(64, 80, shapes.mapIndexed { index, shape ->
            WorkspaceSourceLayer(LayerId(shape.id), shape.id, "", SourceLayerKind.Raster, true, index,
                LayerBounds(shape.x, shape.y, shape.width, shape.height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
                LayerRaster(shape.width, shape.height, ByteArray(shape.width * shape.height * 4) { 255.toByte() }),
                null, null, false)
        }, emptyList())
        val config = RigModifierPipeline.ordinaryConfig(PipelineConfig(atlasSize = 128, meshSpacing = 6,
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
        fun definition(stack: RigModifierStack = this.stack) = RigBrowSpringV8Modifier.definition(source(stack))
        fun context(stack: RigModifierStack = this.stack): ModifierAlgorithmContext =
            definition(stack).authorContext(stack.instances.last()).withUpstream(stack.instances.dropLast(1).map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            })
        fun version(version: Int) = stack.copy(instances = stack.instances.dropLast(1) + brow.copy(
            version = version, plugin = assertNotNull(BuiltinModifierPlugins.reference(brow.type, version))))
        fun blinkVersion(version: Int) = copy(stack = stack.copy(instances = stack.instances.map { entry ->
            if (entry.type != "brow.blink") entry else entry.copy(version = version,
                plugin = assertNotNull(BuiltinModifierPlugins.reference(entry.type, version)))
        }))
    }

    private fun fixture(art: WorkspaceDocument = artwork(), tuned: Boolean = false, eyeAddons: Boolean = false,
                        independentGaze: Boolean = false): Fixture {
        require(!independentGaze || eyeAddons)
        val selectedVersions = if (!eyeAddons) versions else linkedMapOf(
            "rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 4,
            "eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4,
            "brow.shape" to 6, "brow.lid-coupling" to 8, "brow.blink" to 6, "brow.spring" to 8)
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
        assertEquals(selectedVersions.toList(), stack.instances.map { it.type to it.version }, "Only the locked source prefix may precede spring8")
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
        // The first central run proved the sole byte difference was this map's entry order:
        // [260829, 261099) held the same 12 mesh-to-page entries; all bytes outside it matched.
        fun ordered(ir: RigIR) = ir.copy(textures = ir.textures.copy(bindings = ir.textures.bindings.toSortedMap()))
        assertRig(ordered(expected), ordered(actual), "$label: texture-binding order normalized")
    }

    private fun referenceContext(f: Fixture): ModifierAlgorithmContext {
        val stack = f.version(7)
        return RigBrowSpringV7Modifier.definition(f.source(stack)).authorContext(stack.instances.last())
            .withUpstream(f.context().upstream)
    }

    @Test fun nativeOneSevenAndEightMatchOnIdenticalPredecessor(): Unit = runBlocking {
        for (tuned in listOf(false, true)) {
            val f = fixture(tuned = tuned)
            assertTrue(f.definition().explicitVersion)
            assertNotEquals(8, assertNotNull(RigModifierAlgorithms.registry.defaultDefinition("brow.spring")).version)
            val native = WorkspacePreviewBuilder().build(f.document(f.version(1)))
            val seven = WorkspacePreviewBuilder().build(f.document(f.version(7)))
            val eight = WorkspacePreviewBuilder().build(f.document())
            val expected = assertNotNull(native.modifierEvaluation)
            for ((label, model) in listOf("v7" to seven, "v8" to eight)) {
                val actual = assertNotNull(model.modifierEvaluation)
                assertEquals(expected.outputs.size, actual.outputs.size)
                assertTrue(actual.entries.all { it.status == ModifierStatus.ACTIVE })
                expected.outputs.zip(actual.outputs).forEachIndexed { stage, (a, b) -> assertRig(a, b, "$label stage $stage tuned=$tuned") }
                assertRig(RigIrCompiler.compile(native, tileArt = true), RigIrCompiler.compile(model, tileArt = true), label)
                assertEquals(native.rig.modifierRasterAssets, model.rig.modifierRasterAssets)
            }
            val outputs = assertNotNull(eight.modifierEvaluation).outputs
            val shaped = outputs.last().meshes.single { it.name == "brow" }
            val binding = shaped.shapes.single { it.parameter == "ParamBrowLSpring" }
            assertContentEquals(floatArrayOf(-1f, 0f, 1f), binding.keys.toArray())
            assertNull(binding.shapes[binding.neutralIndex])
            assertTrue(binding.shapes.filterNotNull().all { it.deltas.toArray().any { delta -> kotlin.math.abs(delta) > .00001f } })
            val puppet = PuppetIr.toPuppet(outputs.last())
            val evaluator = CpuDeformationEvaluator()
            val pose = mapOf(ParameterId("ParamBrowLY") to .8f, ParameterId("ParamBrowLAngle") to .7f,
                ParameterId("ParamAngleX") to 12f, ParameterId("ParamEyeLOpen") to .3f)
            assertFalse(evaluator.evaluate(puppet, pose).worldPositions.getValue(DrawableId(shaped.id)).contentEquals(
                evaluator.evaluate(puppet, pose + (ParameterId("ParamBrowLSpring") to .7f)).worldPositions.getValue(DrawableId(shaped.id))))
            val context = f.context()
            val algorithm = f.definition().plugin as ReadingModifierAlgorithmPlugin
            assertRig(expected.outputs.last(), algorithm.evaluate(outputs[outputs.lastIndex - 1], f.brow, context, {}), "same predecessor")
            val enabled = RigStageCache.enabled
            try {
                RigStageCache.enabled = false
                assertRig(expected.outputs.last(), assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs.last(), "cache disabled")
            } finally { RigStageCache.enabled = enabled }
        }
    }

    @Test fun numericAncestryReconstructsHistoricalRawBranchWithoutMotionResource() {
        for (tuned in listOf(false, true)) {
            val f = fixture(tuned = tuned)
            val context = f.context()
            assertEquals(JsonPrimitive("brow-spring/8"), context.values["schema"])
            assertTrue("source_ancestry" in context.values)
            assertEquals(setOf("source_meshes"), context.resources.keys)
            assertTrue("brow" in context.values)
            assertTrue(context.resources.keys.none { it in setOf("source_branch", "brow_turn", "before", "after") })
            assertFalse("brow_turn" in context.values)
            val seven = referenceContext(f)
            val expected = RigIrBinary.decode(seven.resources.getValue("source_branch").read()).deformers
            val branch = BrowSpringV8Source.branch(context)
            assertTrue(expected.isNotEmpty(), "Historical branch is a positive control")
            assertEquals(expected, branch, "Every raw carrier field and keyform must reconstruct exactly")
            val disabled = f.brow.copy(config = JsonObject(f.brow.config + ("physicsEnabled" to JsonPrimitive(false))))
            val recaptured = f.definition().authorContext(disabled)
            assertEquals(context.values, recaptured.values)
            assertEquals(context.resources.keys, recaptured.resources.keys)
            context.resources.forEach { (name, resource) -> assertBytes(resource.read(), recaptured.resources.getValue(name).read(), name) }
        }
    }

    @Test fun extractedSourceHelpersPreserveSpringContextResourcesAndHistoricalAncestors() {
        for (tuned in listOf(false, true)) for (eyeAddons in listOf(false, true)) {
            val f = fixture(tuned = tuned, eyeAddons = eyeAddons)
            val context = f.context()
            val historical = referenceContext(f)
            val decoded = BrowSpringV8Source.source(context)
            val brow = context.values.getValue("brow").jsonObject
            assertEquals(brow, JsonObject(historical.values.filterKeys { it in brow }), "Historical raw numerical fit")
            assertBytes(historical.resources.getValue("source_meshes").read(),
                context.resources.getValue("source_meshes").read(), "Historical raw topology resource")
            val common = setOf("head_turn", "native_ids", "mesh_names", "turn_name", "shape_name",
                "spring_names", "physics_names", "physics_group", "providers")
            assertEquals(historical.values.filterKeys { it in common }, context.values.filterKeys { it in common })
            val owners = f.stack.instances.dropLast(1)
            val scaffold = owners.single { it.type == "rig.scaffold" }
            val measured = RigBrowSourceMeasurementsV1.capture(f.source(), f.brow, scaffold,
                owners.filterNot { it == scaffold }, owners.single { it.type == "eye/fitted" }.id,
                owners.single { it.type == "face/fitted" }.id)
            assertEquals(measured.values, JsonObject(context.values.filterKeys { it in measured.values }),
                "Wrapper adds only its own version/provider/physics envelope")
            assertEquals(context.resources.keys, measured.resources.keys)
            context.resources.forEach { (key, resource) -> assertBytes(resource.read(), measured.resources.getValue(key).read(), key) }
            val shell = context.values.getValue("head_turn").jsonObject.getValue("shell").jsonArray.map { it.jsonPrimitive.float }
            val shared = BrowSourceAncestryV1.branch(context.values.getValue("source_ancestry").jsonObject,
                decoded, shell, context.values.getValue("native_ids").jsonObject, context.values.getValue("mesh_names").jsonObject)
            assertEquals(RigIrBinary.decode(historical.resources.getValue("source_branch").read()).deformers, shared)
            assertEquals(BrowSpringV8Source.branch(context), shared)
        }
    }

    @Test fun frozenSourceHelpersRemainOutsideHistoricalWorkerClosures() {
        fun entries(type: String, version: Int): Set<String> {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(type, version))
            return java.util.zip.ZipInputStream(assertNotNull(artifact.open("algorithm.jar"))).use { zip ->
                buildSet { while (true) { val entry = zip.nextEntry ?: break; add(entry.name) } }
            }
        }
        for ((type, version) in listOf("brow.spring" to 7, "brow.blink" to 6, "brow.shape" to 6, "rig.scaffold" to 2)) {
            val old = entries(type, version)
            assertTrue(old.none { it.substringAfterLast('/').startsWith("BrowSourceAncestryV1") ||
                it.substringAfterLast('/').startsWith("BrowSourceLoweringV1") }, "$type@$version stays frozen")
        }
        for ((type, version) in listOf("brow.spring" to 8, "brow.blink" to 7)) {
            val source = entries(type, version)
            assertTrue(source.any { it.endsWith("/BrowSourceAncestryV1.class") })
            assertTrue(source.any { it.endsWith("/BrowSourceLoweringV1.class") })
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
            brow.copy(geometry = geometry.copy(indices = Ints.wrap(geometry.indices.toArray().also { it[0] = it[1] }))))) reject(edited(changed))
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
        val sibling = brow.copy(id = "spring8-unrelated-sibling", name = "Unrelated sibling")
        val siblings = before.copy(meshes = before.meshes + sibling)
        assertTrue(reads.matches(siblings, context))
        val output = algorithm.evaluate(siblings, f.brow, context, {})
        assertEquals(sibling, output.meshes.single { it.id == sibling.id })
        assertRig(after, output.copy(meshes = output.meshes.filterNot { it.id == sibling.id }), "sibling preserved")
        val metadata = brow.copy(name = "Authored brow", visible = false, selectable = false, userData = "Authored metadata")
        assertEquals(metadata, algorithm.evaluate(edited(metadata), f.brow, context, {}).meshes.single { it.id == brow.id }.copy(shapes = brow.shapes))
    }

    @Test fun physicsOffOverrideAndCompatibleReusePreserveMeshMotion(): Unit = runBlocking {
        val f = fixture()
        val evaluation = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation)
        val before = evaluation.outputs[evaluation.outputs.lastIndex - 1]
        val after = evaluation.outputs.last()
        val context = f.context()
        val algorithm = f.definition().plugin as ReadingModifierAlgorithmPlugin
        val spring = "ParamBrowLSpring"
        val disabled = f.brow.copy(config = JsonObject(f.brow.config + ("physicsEnabled" to JsonPrimitive(false))))
        val off = algorithm.evaluate(before, disabled, context, {})
        assertEquals(after.meshes, off.meshes)
        assertEquals(before.physics, off.physics)
        assertEquals(before.deformers, after.deformers)
        val group = after.physics.groups.single { it.outputs.any { output -> output.parameter == spring } }
        val compatible = group.copy(id = "compatible-spring8", name = "Compatible spring")
        val shared = before.copy(physics = before.physics.copy(groups = before.physics.groups + compatible))
        val changes = algorithm.changes(ModifierEvaluationInput(shared, context), f.brow, {})
        assertEquals(listOf(compatible), changes.physicsGroups.reused)
        assertTrue(changes.physicsGroups.added.none { it.outputs.any { output -> output.parameter == spring } })
        val conflict = compatible.copy(segments = compatible.segments.map { it.copy(length = it.length * 2f) })
        assertFailsWith<IllegalArgumentException> {
            algorithm.evaluate(before.copy(physics = before.physics.copy(groups = before.physics.groups + conflict)), f.brow, context, {})
        }
        val override = Physics3Json.setting(group).copy(id = "PhysicsBrowLSpring", name = "Edited spring",
            segments = Physics3Json.setting(group).segments.map { it.copy(length = it.length * 1.2f) })
        val configured = f.brow.copy(config = JsonObject(f.brow.config + ("physics" to buildJsonObject { put(override.id, override.toJson()) })))
        val output = algorithm.evaluate(before, configured, context, {})
        assertEquals(after.meshes, output.meshes)
        assertEquals(Physics3Json.group(override.copy(id = f.brow.outputId("physics:${override.id}"))),
            output.physics.groups.single { it.outputs.any { output -> output.parameter == spring } })
    }

    @Test fun installedWorkerMatchesBothReferencesAfterColdReinstall(): Unit = runBlocking {
        val f = fixture(tuned = true)
        val native = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(1))).modifierEvaluation).outputs
        val seven = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(7))).modifierEvaluation).outputs
        val before = native[native.lastIndex - 1]
        assertRig(before, seven[seven.lastIndex - 1], "worker predecessor")
        assertRig(native.last(), seven.last(), "worker expected references")
        val context = f.context()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("brow.spring", 8))
        assertEquals(artifact.reference, f.brow.plugin)
        val entries = java.util.zip.ZipInputStream(assertNotNull(artifact.open("algorithm.jar"))).use { zip ->
            buildSet { while (true) { val entry = zip.nextEntry ?: break; add(entry.name) } }
        }
        assertTrue(entries.any { it.endsWith("/BrowSpringV8Algorithm.class") })
        assertTrue(entries.any { it.endsWith("/BrowSpringV8Source.class") })
        assertTrue(entries.none { it.endsWith("/RigBuilder.class") || it.endsWith("/BrowSpringV7Algorithm.class") ||
            it.endsWith("/BrowSpringAlgorithm.class") || it.substringAfterLast('/').startsWith("BrowSpringInputs") })
        val directory = Files.createTempDirectory("brow-spring-v8-worker-")
        val preferences = Preferences.userRoot().node("brow-spring-v8-worker/${UUID.randomUUID()}")
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

    @Test fun blinkSixAndSevenPrefixesGiveSpringEightTheSameRealPredecessor(): Unit = runBlocking {
        for (tuned in listOf(false, true)) for (eyeAddons in listOf(false, true)) {
            val six = fixture(tuned = tuned, eyeAddons = eyeAddons)
            val seven = six.blinkVersion(7)
            val nativeSix = assertNotNull(WorkspacePreviewBuilder().build(six.document(six.version(1))).modifierEvaluation)
            val nativeSeven = assertNotNull(WorkspacePreviewBuilder().build(seven.document(seven.version(1))).modifierEvaluation)
            val oldSpring = assertNotNull(WorkspacePreviewBuilder().build(six.document(six.version(7))).modifierEvaluation)
            val eightSix = assertNotNull(WorkspacePreviewBuilder().build(six.document()).modifierEvaluation)
            val eightSeven = assertNotNull(WorkspacePreviewBuilder().build(seven.document()).modifierEvaluation)
            for ((label, result) in listOf("native7" to nativeSeven, "spring7" to oldSpring,
                "blink6→spring8" to eightSix, "blink7→spring8" to eightSeven)) {
                assertTrue(result.entries.all { it.status == ModifierStatus.ACTIVE }, label)
                assertEquals(nativeSix.outputs.size, result.outputs.size)
                nativeSix.outputs.zip(result.outputs).forEachIndexed { stage, (before, after) ->
                    assertRig(before, after, "$label stage=$stage tuned=$tuned eyes=$eyeAddons")
                }
            }
            val realBefore = nativeSix.outputs[nativeSix.outputs.lastIndex - 1]
            for (f in listOf(six, seven)) {
                val output = (f.definition().plugin as ReadingModifierAlgorithmPlugin).evaluate(realBefore, f.brow, f.context(), {})
                assertRig(nativeSix.outputs.last(), output, "same predecessor blink${f.stack.instances.single { it.type == "brow.blink" }.version}")
            }
            val first = six.context()
            val next = seven.context()
            assertEquals(first.values - setOf("providers", "provider_packages"), next.values - setOf("providers", "provider_packages"),
                "Version-specific provider identity changes do not change numerical source observations")
            assertEquals(first.resources.keys, next.resources.keys)
            first.resources.forEach { (name, resource) -> assertBytes(resource.read(), next.resources.getValue(name).read(), name) }
        }
    }

    @Test fun installedSpringWorkerAcceptsBothExactBlinkPrefixesAfterColdReinstall(): Unit = runBlocking {
        val base = fixture(tuned = true, eyeAddons = true)
        val expected = assertNotNull(WorkspacePreviewBuilder().build(base.document(base.version(1))).modifierEvaluation).outputs
        val before = expected[expected.lastIndex - 1]
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("brow.spring", 8))
        val directory = Files.createTempDirectory("brow-source-chain-worker-")
        val preferences = Preferences.userRoot().node("brow-source-chain-worker/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (path in artifact.manifest.files.keys) {
                val output = staging.resolve(path)
                Files.createDirectories(output.parent)
                Files.write(output, assertNotNull(artifact.open(path)).use { it.readBytes() })
            }
            for (version in listOf(6, 7)) {
                val f = base.blinkVersion(version)
                val installed = store.install(staging.toString())
                assertEquals(f.definition().requires, installed.manifest.algorithms.single().requires)
                assertEquals(f.definition().changeWrites, installed.manifest.algorithms.single().writes)
                assertRig(expected.last(), ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                    before, f.brow, {}, store, context = f.context(), declaredParameters = f.definition().parameters(f.brow)),
                    "installed blink$version→spring8")
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun springEightDoesNotGeneralizeBlinkCompatibilityToOtherVersions() {
        val f = fixture()
        val context = f.context()
        val original = context.upstream.single { it.type == "brow.blink" }
        for (version in listOf(1, 3, 4, 5)) {
            val changed = original.copy(version = version,
                plugin = assertNotNull(BuiltinModifierPlugins.reference("brow.blink", version)))
            val owners = context.upstream.map { if (it.instanceId == original.instanceId) changed else it }
            assertFailsWith<IllegalArgumentException> {
                io.github.psd2live.algorithms.binding.BrowSpringV8Algorithm.validateProviders(owners, f.brow)
            }
            assertFailsWith<IllegalArgumentException> { f.blinkVersion(version).definition().providers(f.brow) }
        }
        for (version in listOf(-1, 0, 2, 8, 99, Int.MAX_VALUE))
            assertFalse(io.github.psd2live.algorithms.binding.BrowSpringV8Algorithm.acceptsVersion("brow.blink", version))
        for (version in listOf(6, 7)) {
            val valid = f.blinkVersion(version)
            valid.context()
            assertEquals(original.instanceId, valid.definition().providers(valid.brow).getValue("brow.blink"))
        }
        assertEquals(6, io.github.psd2live.algorithms.binding.BrowSpringV8Algorithm.requiredVersions.getValue("brow.blink"),
            "Default historical source selection stays pinned")
    }

    @Test fun appliedPrefixArchiveRecapturesSpringEightWithCacheDisabled(): Unit = runBlocking {
        for (blinkVersion in listOf(6, 7)) checkAppliedPrefixArchive(blinkVersion)
    }

    private suspend fun checkAppliedPrefixArchive(blinkVersion: Int) {
        val f = fixture().blinkVersion(blinkVersion)
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) },
            rebuildFrom = { next, previous -> builder.build(next, previous) })
        val document = f.document()
        val model = builder.build(document)
        val expected = RigIrCompiler.compile(model, tileArt = true)
        val originalContext = assertNotNull(assertNotNull(model.modifierEvaluation).readAlgorithms[f.brow.id]).context
        runtime.install(runtime.state.value.state, "source", document, model)
        val directory = Files.createTempDirectory("brow-spring-v8-replay-")
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
            val blink = f.stack.instances.single { it.type == "brow.blink" }
            WorkspaceDocumentCommands(runtime).execute(current.projectId, current.state, "modifier_apply",
                listOf(WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", blink.id) })), MutationAuthor.USER)
            assertBakedRig(expected, RigIrCompiler.compile(runtime.capture().model, tileArt = true), "applied prefix live")
            saveAndReopen("applied-prefix", true)
        } finally { ModifierPluginStore.deleteTree(directory) }
    }


    @Test fun aliasedProvidersReconstructTheSameCanonicalSourceBranch(): Unit = runBlocking {
        val plain = fixture(tuned = true)
        val reference = WorkspacePreviewBuilder().build(plain.document(plain.version(7)))
        val owned = assertNotNull(reference.modifierEvaluation).outputs.last()
        val aliased = plain.stack.copy(instances = plain.stack.instances.map { entry ->
            val locals = owned.deformers.mapNotNull { entry.localOutputName(it.id) }
            entry.copy(outputAliases = locals.withIndex().associate { (index, local) -> local to "spring8-${entry.id}-$index" })
        })
        assertTrue(aliased.instances.any { it.outputAliases.isNotEmpty() })
        val f = plain.copy(stack = aliased)
        assertEquals(RigIrBinary.decode(referenceContext(f).resources.getValue("source_branch").read()).deformers,
            BrowSpringV8Source.branch(f.context()))
        val native = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(1))).modifierEvaluation).outputs
        val seven = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(7))).modifierEvaluation).outputs
        val eight = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        native.zip(eight).forEachIndexed { stage, (a, b) -> assertRig(a, b, "aliased native stage $stage") }
        seven.zip(eight).forEachIndexed { stage, (a, b) -> assertRig(a, b, "aliased v7 stage $stage") }
    }



    @Test fun supportedEyeAddonPrefixKeepsExactHistoricalSpringOutput(): Unit = runBlocking {
        val f = fixture(tuned = true, eyeAddons = true)
        val context = f.context()
        assertEquals(RigIrBinary.decode(referenceContext(f).resources.getValue("source_branch").read()).deformers,
            BrowSpringV8Source.branch(context))
        val native = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(1))).modifierEvaluation).outputs
        val seven = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(7))).modifierEvaluation).outputs
        val eight = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        assertEquals(native.size, eight.size)
        native.zip(eight).forEachIndexed { stage, (a, b) -> assertRig(a, b, "eye addons native stage $stage") }
        seven.zip(eight).forEachIndexed { stage, (a, b) -> assertRig(a, b, "eye addons v7 stage $stage") }
    }

    @Test fun independentGazeAncestorShapesStayOutsideExplicitCohort(): Unit = runBlocking {
        val f = fixture(tuned = true, eyeAddons = true, independentGaze = true)
        val seven = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.version(7))).modifierEvaluation)
        assertTrue(seven.entries.all { it.status == ModifierStatus.ACTIVE })
        val before = seven.outputs[seven.outputs.lastIndex - 1]
        val input = ModifierEvaluationInput(before)
        val ancestors = f.brow.targets.flatMap { input.parentsOfMesh(it.id) }.distinctBy { it.id }
        assertTrue(ancestors.filterIsInstance<Deformer.Warp>().any { node ->
            node.shapes.any { it.parameter == "ParamEyeBallLX" } && node.shapes.any { it.parameter == "ParamEyeBallLY" }
        }, "Real gaze v3 must produce the independent iris-ancestor shapes excluded by v8")
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
        // The direct v8 provider contract returns a genuinely unavailable face provider.
        val foreignFace = face.copy(config = JsonObject(face.config + ("scaffold" to JsonPrimitive("foreign-scaffold"))))
        val paused = f.stack.copy(instances = f.stack.instances.map { if (it == face) foreignFace else it })
        val resolved = f.definition(paused).providers(f.brow).getValue("face/fitted")
        assertTrue(paused.instances.none { it.id == resolved })
        assertFailsWith<IllegalArgumentException> { f.definition(paused).authorContext(f.brow) }
        // The historical brow4 stage cannot capture this incomplete full chain. Preserve its
        // actual earlier failure rather than claiming every older consumer suspends gracefully.
        val historicalFailure = assertFailsWith<NoSuchElementException> {
            WorkspacePreviewBuilder().build(f.document(paused))
        }
        assertTrue(historicalFailure.stackTrace.any {
            it.className.endsWith("RigBrowFittedModifier") && it.methodName == "captureSharedFrames"
        }, "The malformed full chain must fail at its earlier historical brow stage")

        // Test v8's own dependency boundary independently using a real, unmodified predecessor.
        val evaluated = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation)
        val before = evaluated.outputs[evaluated.outputs.lastIndex - 1]
        val definition = f.definition()
        var declarations = 0
        var captures = 0
        val sentinel = "Reached spring v8 capture after complete dependencies"
        val guarded = definition.copy(
            parameters = { declarations++; definition.parameters(it) },
            authorContext = { captures++; error(sentinel) })
        val registry = RigModifierRegistry(BuiltinModifierPlugins.definitions(f.source()).map {
            if (it.type == f.brow.type && it.version == f.brow.version) guarded else it
        })
        val evaluator = RigModifierEvaluator(registry)
        val prefix = f.stack.instances.dropLast(1)
        val applied = prefix.map { it.capability(provides = registry.definition(it).provides) }
        val candidateOnly = RigModifierStack(instances = listOf(f.brow),
            retiredIds = prefix.mapTo(hashSetOf()) { it.id }, appliedCapabilities = applied.filterNot { it.id == face.id })
        val missing = evaluator.evaluate(before, candidateOnly)
        assertEquals(ModifierStatus.MISSING_DEPENDENCY, missing.entries.single().status)
        assertEquals(setOf("face/fitted"), missing.entries.single().missingDependencies)
        assertRig(before, missing.rig, "v8 missing face leaves its real predecessor untouched")
        assertEquals(0, declarations, "Missing face must pause before parameter declarations")
        assertEquals(0, captures, "Missing face must pause before author capture")
        val reached = assertFailsWith<IllegalStateException> {
            evaluator.evaluate(before, candidateOnly.copy(appliedCapabilities = applied))
        }
        assertEquals(sentinel, reached.message)
        assertEquals(1, declarations)
        assertEquals(1, captures)
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
                BrowSpringV8Source.branch(corrupted)
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
        assertFailsWith<IllegalArgumentException> { BrowSpringV8Source.branch(captured(landmarkValues)) }
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
            config.copy(parentOverrides = mapOf("brow" to "DeformFace")),
            config.copy(rigEdits = config.rigEdits.copy(warpEdits = listOf(
                RigWarpEdit("spring8-authored-warp", "Authored warp", "DeformFace", listOf(browId))))),
        )
        for ((index, changed) in alternatives.withIndex()) {
            // Keep the original source model and mutate its original config only. A sanitized
            // native config would erase precisely the author structure this boundary must reject.
            val modified = f.copy(sourceModel = f.sourceModel.copy(config = changed))
            if (index == 0) {
                assertFailsWith<IllegalArgumentException>("original parent override") {
                    modified.definition().authorContext(f.brow)
                }
            } else {
                // Ordinary source content has no DeformFace yet. Real source restoration rejects
                // this authored warp before v8 is reached; do not invent a synthetic parent to hide that.
                val failure = assertFailsWith<IllegalStateException>("invalid original authored warp") {
                    modified.definition().authorContext(f.brow)
                }
                assertTrue(failure.message.orEmpty().startsWith("Parent must be an existing Warp"))
            }
        }
        assertTrue(BrowSpringV8Source.branch(f.context()).isNotEmpty(), "Unedited original remains admissible")
    }

    @Test fun mirroredAndRolledSourceStayOutsideExplicitCohort() {
        for (art in listOf(artwork(mirror = true), artwork(upright = false), artwork(bilateral = true), artwork(interiors = 1), artwork(interiors = 2))) {
            val f = fixture(art)
            assertFailsWith<IllegalArgumentException> { f.context() }
        }
    }
}
