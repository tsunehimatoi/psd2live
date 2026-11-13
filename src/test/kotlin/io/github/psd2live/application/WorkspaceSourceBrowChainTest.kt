package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.BrowSpringV8Source
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.BlendBinding
import io.github.psd2live.format.model.LatticeShape
import io.github.psd2live.format.model.PivotShape
import io.github.psd2live.format.model.ParameterNode
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

/** Complete explicit source brow chain, always compared on identical original artwork and instance IDs. */
class WorkspaceSourceBrowChainTest {
    private val versions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2,
        "face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 5,
        "brow.shape" to 7, "brow.lid-coupling" to 9, "brow.blink" to 7, "brow.spring" to 8)

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
        fun definition(stack: RigModifierStack = this.stack) = RigBrowSpringV8Modifier.definition(source(stack))
        fun context(stack: RigModifierStack = this.stack): ModifierAlgorithmContext =
            definition(stack).authorContext(stack.instances.last()).withUpstream(stack.instances.dropLast(1).map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            })

    }

    private fun fixture(art: WorkspaceDocument = artwork(), tuned: Boolean = false, eyeAddons: Boolean = false,
                        independentGaze: Boolean = false): Fixture {
        require(!independentGaze || eyeAddons)
        val selectedVersions = if (!eyeAddons) versions else linkedMapOf(
            "rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 5,
            "eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.highlight" to 3, "eye.aftershock" to 4, "eye.squash" to 4,
            "brow.shape" to 7, "brow.lid-coupling" to 9, "brow.blink" to 7, "brow.spring" to 8)
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
        assertEquals(selectedVersions.toList(), stack.instances.map { it.type to it.version }, "Only the complete explicit source chain may precede spring8")
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
        if (expected != actual) {
            val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            fields.forEach { it.isAccessible = true }
            val changed = fields.filter { it.get(expected) != it.get(actual) }.map { it.name }
            val details = buildList {
                if ("parameterTree" in changed) add("expectedTree=${expected.parameterTree}; actualTree=${actual.parameterTree}")
                if ("parameters" in changed) add("expectedParameters=${expected.parameters}; actualParameters=${actual.parameters}")
                if ("deformers" in changed) add("expectedDeformerIds=${expected.deformers.map { it.id }}; actualDeformerIds=${actual.deformers.map { it.id }}")
            }
            fail("$label: complete raw IR differs; fields=$changed; ${details.joinToString("; ")}")
        }
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

    private val oldVersions = mapOf("brow/fitted" to 4, "brow.shape" to 6,
        "brow.lid-coupling" to 8, "brow.blink" to 6, "brow.spring" to 7)

    /** Change only implementation versions and their exact package references, never IDs or authored settings. */
    private fun Fixture.withVersions(selected: Map<String, Int>) = stack.copy(instances = stack.instances.map { entry ->
        selected[entry.type]?.let { version -> entry.copy(version = version,
            plugin = assertNotNull(BuiltinModifierPlugins.reference(entry.type, version))) } ?: entry
    })

    private fun Fixture.prefix(type: String): Fixture {
        val index = stack.instances.indexOfFirst { it.type == type }
        require(index >= 0)
        return copy(stack = stack.copy(instances = stack.instances.take(index + 1)))
    }

    private fun Fixture.sourceDefinition(): RigModifierDefinition = when (brow.type) {
        "brow/fitted" -> RigBrowFittedV5Modifier.definition(source())
        "brow.shape" -> RigBrowShapeV7Modifier.definition(source())
        "brow.lid-coupling" -> RigBrowLidV9Modifier.definition(source())
        "brow.blink" -> RigBrowBlinkV7Modifier.definition(source())
        "brow.spring" -> RigBrowSpringV8Modifier.definition(source())
        else -> error("Not a source brow consumer: ${brow.type}")
    }

    private fun Fixture.sourceContext(): ModifierAlgorithmContext = sourceDefinition().authorContext(brow)
        .withUpstream(stack.instances.dropLast(1).map {
            ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
        })

    private fun assertPreview(expected: RigPreviewModel, actual: RigPreviewModel, label: String) {
        val left = assertNotNull(expected.modifierEvaluation)
        val right = assertNotNull(actual.modifierEvaluation)
        assertEquals(left.outputs.size, right.outputs.size, "$label stage count")
        assertTrue(right.entries.all { it.status == ModifierStatus.ACTIVE }, "$label all stages execute")
        left.outputs.zip(right.outputs).forEachIndexed { index, (a, b) ->
            assertRig(a, b, "$label stage $index")
            assertEquals(a.parameterTree, b.parameterTree, "$label stage $index parameter folders")
            assertEquals(a.parameterLinks, b.parameterLinks, "$label stage $index parameter pads")
            assertEquals(a.restPose, b.restPose, "$label stage $index rest pose")
        }
        assertEquals(expected.rig.modifierOutput, actual.rig.modifierOutput, "$label raw modifier output")
        assertRig(PuppetIr.toIr(expected.rig.puppet), PuppetIr.toIr(actual.rig.puppet), "$label runtime IR")
        assertRig(RigIrCompiler.compile(expected, tileArt = true), RigIrCompiler.compile(actual, tileArt = true), "$label export")
        assertEquals(expected.rig.modifierRasterAssets, actual.rig.modifierRasterAssets, "$label raster assets")
        val headId = right.stack.instances.first().outputId("shared/deformer:DeformHeadRotation")
        val head = assertIs<Deformer.Rotation>(left.outputs.last().deformers.single { it.id == headId })
        assertEquals(head, right.outputs.last().deformers.single { it.id == headId }, "$label full head frame, no normalization")
        val poses = listOf(emptyMap(), mapOf(ParameterId("ParamAngleX") to 25f, ParameterId("ParamAngleY") to -20f,
            ParameterId("ParamAngleZ") to 15f, ParameterId("ParamBrowLY") to .8f,
            ParameterId("ParamBrowLX") to -.7f, ParameterId("ParamBrowLAngle") to .7f,
            ParameterId("ParamBrowLForm") to -.6f, ParameterId("ParamBrowLSpring") to .7f,
            ParameterId("ParamEyeLOpen") to .3f, ParameterId("ParamBreath") to .8f),
            mapOf(ParameterId("ParamEyeLOpen") to 2f),
            mapOf(ParameterId("ParamEyeLOpen") to .4f, ParameterId("ParamEyeLSmile") to 1f))
        val evaluator = CpuDeformationEvaluator()
        for (pose in poses) {
            val a = evaluator.evaluate(expected.rig.puppet, pose).worldPositions
            val b = evaluator.evaluate(actual.rig.puppet, pose).worldPositions
            assertEquals(a.keys, b.keys)
            a.forEach { (id, points) -> assertContentEquals(points, b.getValue(id), "$label drawable $id at $pose") }
        }
    }

    @Test fun nativeHistoricalAndCompleteSourceChainsMatchAtEveryStageAndRuntimePose(): Unit = runBlocking {
        for (tuned in listOf(false, true)) for (eyeAddons in listOf(false, true)) {
            val f = fixture(tuned = tuned, eyeAddons = eyeAddons)
            val builder = WorkspacePreviewBuilder()
            val native = builder.build(f.document(f.withVersions(oldVersions.mapValues { 1 })))
            val historical = builder.build(f.document(f.withVersions(oldVersions)))
            val actual = builder.build(f.document())
            val label = "tuned=$tuned eyeAddons=$eyeAddons"
            assertPreview(native, historical, "$label historical control")
            assertPreview(historical, actual, "$label source chain")
            for (type in oldVersions.keys) {
                val stage = f.prefix(type)
                assertTrue(stage.sourceDefinition().explicitVersion, "$type must be explicit")
                assertNotEquals(stage.brow.version, assertNotNull(RigModifierAlgorithms.registry.defaultDefinition(type)).version)
            }
            val enabled = RigStageCache.enabled
            try {
                RigStageCache.enabled = false
                repeat(2) { assertPreview(actual, builder.build(f.document()), "$label cold repeat $it") }
            } finally { RigStageCache.enabled = enabled }
        }
    }

    @Test fun shapeLidBlinkAndSpringEachHaveObservableMotionOnTheCompleteSourceChain(): Unit = runBlocking {
        val f = fixture(tuned = true, eyeAddons = true)
        val outputs = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        val evaluator = CpuDeformationEvaluator()
        fun changed(type: String, pose: Map<ParameterId, Float>, meshName: String = "brow") {
            val index = f.stack.instances.indexOfFirst { it.type == type }
            val before = outputs[index - 1]
            val after = outputs[index]
            val id = DrawableId(after.meshes.single { it.name == meshName }.id)
            assertFalse(evaluator.evaluate(PuppetIr.toPuppet(before), pose).worldPositions.getValue(id).contentEquals(
                evaluator.evaluate(PuppetIr.toPuppet(after), pose).worldPositions.getValue(id)), "$type must move brow at $pose")
        }
        changed("brow/fitted", mapOf(ParameterId("ParamBrowLY") to .8f))
        for (parameter in listOf("ParamBrowLX", "ParamBrowLAngle", "ParamBrowLForm"))
            changed("brow.shape", mapOf(ParameterId(parameter) to .8f))
        changed("brow.lid-coupling", mapOf(ParameterId("ParamBrowLY") to -.8f, ParameterId("ParamEyeLOpen") to .4f), "lash")
        for (pose in listOf(mapOf(ParameterId("ParamEyeLOpen") to 0f), mapOf(ParameterId("ParamEyeLOpen") to 2f),
            mapOf(ParameterId("ParamEyeLOpen") to .4f, ParameterId("ParamEyeLSmile") to 1f))) changed("brow.blink", pose)
        changed("brow.spring", mapOf(ParameterId("ParamBrowLSpring") to .8f))
    }

    @Test fun allSourcePackagesInstallColdAndMatchTheHistoricalBinaryAtEachRealPredecessor(): Unit = runBlocking {
        val f = fixture(tuned = true, eyeAddons = true)
        val historical = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.withVersions(oldVersions))).modifierEvaluation).outputs
        val actual = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        val directory = Files.createTempDirectory("complete-source-brow-workers-")
        val preferences = Preferences.userRoot().node("complete-source-brow-workers/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            for (type in oldVersions.keys) {
                val stage = f.prefix(type)
                val index = stage.stack.instances.lastIndex
                val definition = stage.sourceDefinition()
                val context = stage.sourceContext()
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find(type, stage.brow.version))
                assertEquals(artifact.reference, stage.brow.plugin)
                if (type in setOf("brow.shape", "brow.lid-coupling", "brow.blink", "brow.spring")) {
                    val entries = java.util.zip.ZipInputStream(assertNotNull(artifact.open("algorithm.jar"))).use { zip ->
                        buildSet { while (true) { val entry = zip.nextEntry ?: break; add(entry.name) } }
                    }
                    val implementation = when (type) {
                        "brow.shape" -> "BrowShapeV7Algorithm"
                        "brow.lid-coupling" -> "BrowLidV9Algorithm"
                        "brow.blink" -> "BrowBlinkV7Algorithm"
                        else -> "BrowSpringV8Algorithm"
                    }
                    assertTrue(entries.any { it.endsWith("/$implementation.class") }, "$type worker implementation")
                    assertTrue(entries.none { it.endsWith("/RigBuilder.class") || it.endsWith("/BrowShapeV6Algorithm.class") ||
                        it.endsWith("/BrowLidV8Algorithm.class") || it.endsWith("/BrowBlinkV6Algorithm.class") ||
                        it.endsWith("/BrowSpringV7Algorithm.class") }, "$type has no historical implementation fallback")
                }
                val staging = Files.createDirectory(directory.resolve("package-$index"))
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                artifact.manifest.files.keys.forEach { name ->
                    assertNotNull(artifact.open(name)).use { Files.copy(it, staging.resolve(name)) }
                }
                assertRig(historical[index - 1], actual[index - 1], "$type real predecessor")
                repeat(2) { run ->
                    val installed = store.install(staging.toString())
                    val algorithm = installed.manifest.algorithms.single()
                    assertEquals(definition.requires, algorithm.requires)
                    assertEquals(definition.changeWrites, algorithm.writes)
                    val output = ModifierPluginWorkers.evaluate(installed, algorithm, actual[index - 1], stage.brow,
                        {}, store, context = context, declaredParameters = definition.parameters(stage.brow))
                    assertRig(historical[index], output, "$type installed JVM $run")
                    ModifierPluginWorkers.stop(installed.reference)
                    store.uninstall(installed.reference)
                }
                if (type in setOf("brow.shape", "brow.blink", "brow.spring"))
                    assertEquals(setOf("source_meshes"), context.resources.keys, "$type raw-source-only resource")
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun deformerAliasesPreserveAllThreeCompleteChainsAndColdReplay(): Unit = runBlocking {
        val plain = fixture(tuned = true, eyeAddons = true)
        val builder = WorkspacePreviewBuilder()
        val owned = assertNotNull(builder.build(plain.document(plain.withVersions(oldVersions))).modifierEvaluation).outputs.last()
        val f = plain.copy(stack = plain.stack.copy(instances = plain.stack.instances.map { entry ->
            val locals = owned.deformers.mapNotNull { entry.localOutputName(it.id) }
            entry.copy(outputAliases = locals.withIndex().associate { (index, local) -> local to "source-chain-${entry.id}-$index" })
        }))
        assertTrue(f.stack.instances.any { it.outputAliases.isNotEmpty() })
        val native = builder.build(f.document(f.withVersions(oldVersions.mapValues { 1 })))
        val historical = builder.build(f.document(f.withVersions(oldVersions)))
        assertPreview(native, historical, "aliased historical control")
        assertPreview(historical, builder.build(f.document()), "aliased complete source chain")
        val enabled = RigStageCache.enabled
        try {
            RigStageCache.enabled = false
            assertPreview(historical, builder.build(f.document()), "aliased cold source chain")
        } finally { RigStageCache.enabled = enabled }
    }

    @Test fun parameterFolderAliasesMatchHistoricalSourceChainWithoutErasingNativeBaselineDifferences(): Unit = runBlocking {
        val plain = fixture(tuned = true, eyeAddons = true)
        val builder = WorkspacePreviewBuilder()
        val owned = assertNotNull(builder.build(plain.document(plain.withVersions(oldVersions))).modifierEvaluation).outputs.last()
        val f = plain.copy(stack = plain.stack.copy(instances = plain.stack.instances.map { entry ->
            // Keep the actual buildable combined alias fixture: isolated folder aliases hit
            // an earlier frozen fitted4 reorder guard and cannot establish this baseline.
            val locals = owned.deformers.mapNotNull { entry.localOutputName(it.id) }
            entry.copy(outputAliases = locals.withIndex().associate { (index, local) -> local to "source-chain-${entry.id}-$index" } +
                mapOf("parameter-folder:ParamGroupBrows" to "source-chain-${entry.id}-brows",
                    "parameter-folder:ParamGroupPhysics" to "source-chain-${entry.id}-physics"))
        }))
        val native = builder.build(f.document(f.withVersions(oldVersions.mapValues { 1 })))
        val historical = builder.build(f.document(f.withVersions(oldVersions)))

        // Pin the exact field differences observed in the central frozen baseline. No
        // field is normalized or excluded from historical-versus-new comparisons below.
        val fields = RigIR::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        fields.forEach { it.isAccessible = true }
        val nativeOutputs = assertNotNull(native.modifierEvaluation).outputs
        val historicalOutputs = assertNotNull(historical.modifierEvaluation).outputs
        assertEquals(15, nativeOutputs.size, "The pinned alias baseline includes the exact six eye add-ons")
        assertEquals(nativeOutputs.size, historicalOutputs.size)
        nativeOutputs.zip(historicalOutputs).forEachIndexed { index, (a, b) ->
            val changed = fields.filter { it.get(a) != it.get(b) }.mapTo(linkedSetOf()) { it.name }
            val expected = when (index) {
                in 0..3 -> emptySet()
                in 4..10 -> setOf("parameterLinks")
                else -> setOf("parameterLinks", "parameterTree", "deformers")
            }
            assertEquals(expected, changed, "Exact frozen native-versus-historical folder alias baseline at stage $index")
        }
        // One concise diagnostic records concrete link pairs, including order, without
        // guessing missing values from the earlier field-only baseline report.
        val linkDifferences = nativeOutputs.zip(historicalOutputs)
            .filter { (a, b) -> a.parameterLinks != b.parameterLinks }
            .map { (a, b) -> a.parameterLinks to b.parameterLinks }.distinct()
        println("FROZEN_FOLDER_ALIAS_LINKS " + linkDifferences.joinToString("; ") { (a, b) ->
            "native=${a.map { it.horizontal to it.vertical }} historical=${b.map { it.horizontal to it.vertical }}"
        })
        assertPreview(historical, builder.build(f.document()), "parameter-folder aliases historical versus source")
        val enabled = RigStageCache.enabled
        try {
            RigStageCache.enabled = false
            assertPreview(historical, builder.build(f.document()), "parameter-folder aliases cold source")
        } finally { RigStageCache.enabled = enabled }

    }

    @Test fun mixedProviderTuplesRejectRatherThanBroadeningVersionRanges() {
        val f = fixture()
        // Each bit chooses old/new fitted, shape, lid. Only coherent tuples are admitted by blink7/spring8.
        for (mask in 0..7) {
            val selected = mapOf("brow/fitted" to if (mask and 1 == 0) 4 else 5,
                "brow.shape" to if (mask and 2 == 0) 6 else 7,
                "brow.lid-coupling" to if (mask and 4 == 0) 8 else 9)
            for (type in listOf("brow.blink", "brow.spring")) {
                val changed = f.copy(stack = f.withVersions(selected)).prefix(type)
                if (mask == 0 || mask == 7) {
                    assertNotNull(changed.sourceContext(), "$type coherent tuple $selected")
                } else assertFailsWith<IllegalArgumentException>("$type mixed tuple $selected") {
                    changed.sourceContext()
                }
            }
        }
        for (blink in listOf(1, 3, 4, 5, 6)) {
            val changed = f.copy(stack = f.withVersions(mapOf("brow.blink" to blink)))
            assertFailsWith<IllegalArgumentException>("new source tuple cannot use blink$blink") { changed.sourceContext() }
        }
        for (blink in listOf(6, 7)) {
            val changed = f.copy(stack = f.withVersions(oldVersions + ("brow.blink" to blink) + ("brow.spring" to 8)))
            assertNotNull(changed.sourceContext(), "old coherent tuple with blink$blink")
        }
    }

    @Test fun sourceConsumersRejectAnonymousForeignPartialAndWrongPackageOwners() {
        val complete = fixture()
        for (type in listOf("brow.shape", "brow.lid-coupling", "brow.blink", "brow.spring")) {
            val f = complete.prefix(type)
            val face = f.stack.instances.single { it.type == "face/fitted" }
            val unknown = RigModifierInstance("foreign-source-owner", "external.producer", 1, f.brow.targets,
                config = buildJsonObject { put("scaffold", f.brow.config.getValue("scaffold")) })
            val invalid = listOf(
                f.stack.copy(instances = f.stack.instances.dropLast(1) + unknown + f.brow),
                f.stack.copy(appliedCapabilities = listOf(face.capability().copy(id = null))),
                f.stack.copy(appliedCapabilities = listOf(ModifierCapability("external.producer", 1, f.brow.targets))),
                f.stack.copy(instances = f.stack.instances.map { if (it == face) it.copy(targets = it.targets.take(1)) else it }),
                f.stack.copy(instances = f.stack.instances.map { if (it == face) it.copy(plugin = null) else it }),
                f.stack.copy(instances = f.stack.instances.map { if (it == face)
                    it.copy(plugin = assertNotNull(BuiltinModifierPlugins.reference("eye/fitted", 5))) else it }),
            )
            for ((index, stack) in invalid.withIndex()) assertFailsWith<IllegalArgumentException>("$type bad owner $index") {
                f.copy(stack = stack).sourceContext()
            }
        }
    }

    @Test fun missingProvidersPauseBeforeDeclarationsOrSourceCapture(): Unit = runBlocking {
        val complete = fixture()
        val outputs = assertNotNull(WorkspacePreviewBuilder().build(complete.document()).modifierEvaluation).outputs
        for (type in listOf("brow.shape", "brow.lid-coupling", "brow.blink", "brow.spring")) {
            val f = complete.prefix(type)
            val before = outputs[f.stack.instances.lastIndex - 1]
            val definition = f.sourceDefinition()
            var declarations = 0
            var captures = 0
            val guarded = definition.copy(parameters = { declarations++; definition.parameters(it) },
                authorContext = { captures++; error("Unexpected capture without complete dependencies") })
            val registry = RigModifierRegistry(BuiltinModifierPlugins.definitions(f.source()).map {
                if (it.type == f.brow.type && it.version == f.brow.version) guarded else it
            })
            val prefix = f.stack.instances.dropLast(1)
            val applied = prefix.map { it.capability(provides = registry.definition(it).provides) }
            val face = prefix.single { it.type == "face/fitted" }
            val candidateOnly = RigModifierStack(instances = listOf(f.brow),
                retiredIds = prefix.mapTo(hashSetOf()) { it.id }, appliedCapabilities = applied.filterNot { it.id == face.id })
            val result = RigModifierEvaluator(registry).evaluate(before, candidateOnly)
            assertEquals(ModifierStatus.MISSING_DEPENDENCY, result.entries.single().status, type)
            assertEquals(setOf("face/fitted"), result.entries.single().missingDependencies, type)
            assertRig(before, result.rig, "$type missing provider preserves predecessor")
            assertEquals(0, declarations, "$type declarations")
            assertEquals(0, captures, "$type captures")
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


    @Test fun appliedPrefixArchiveRecapturesSpringEightWithCacheDisabled(): Unit = runBlocking {
        for (type in listOf("brow.shape", "brow.lid-coupling", "brow.blink")) checkAppliedPrefixArchive(type)
    }

    private suspend fun checkAppliedPrefixArchive(appliedType: String) {
        val f = fixture(tuned = true)
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
                        val appliedCount = f.stack.instances.indexOfFirst { it.type == appliedType } + 1
                        assertEquals(f.stack.instances.drop(appliedCount), stack.instances)
                        for (provider in f.stack.instances.take(appliedCount)) {
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
            val appliedEntry = f.stack.instances.single { it.type == appliedType }
            WorkspaceDocumentCommands(runtime).execute(current.projectId, current.state, "modifier_apply",
                listOf(WorkspaceDocumentOperation("modifier_apply", buildJsonObject { put("id", appliedEntry.id) })), MutationAuthor.USER)
            assertBakedRig(expected, RigIrCompiler.compile(runtime.capture().model, tileArt = true), "applied prefix live")
            saveAndReopen("applied-prefix", true)
        } finally { ModifierPluginStore.deleteTree(directory) }
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


    @Test fun supportedSourceMeshWitnessesRejectChangedDefaultsAndMissingAxes(): Unit = runBlocking {
        // Use the admitted ordinary-sized source. Dense lowering carrier admission is a
        // separate fail-closed predecessor boundary, not a successful complete-chain claim.
        val f = fixture(tuned = true).prefix("brow.blink")
        val evaluated = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation)
        assertTrue(evaluated.entries.all { it.status == ModifierStatus.ACTIVE })
        val outputs = evaluated.outputs
        val before = outputs[outputs.lastIndex - 1]
        for (selected in listOf(oldVersions, oldVersions.mapValues { 1 })) {
            val historical = assertNotNull(WorkspacePreviewBuilder().build(f.document(f.withVersions(selected))).modifierEvaluation)
            assertTrue(historical.entries.all { it.status == ModifierStatus.ACTIVE })
            assertEquals(outputs.size, historical.outputs.size)
            historical.outputs.zip(outputs).forEachIndexed { index, (expected, actual) ->
                assertRig(expected, actual, "ordinary $selected stage $index")
            }
            assertRig(before, historical.outputs[historical.outputs.lastIndex - 1], "ordinary native$selected predecessor")
            assertRig(historical.outputs.last(), outputs.last(), "ordinary native$selected blink result")
        }
        val context = f.sourceContext()
        val algorithm = f.sourceDefinition().plugin as ReadingModifierAlgorithmPlugin
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
        assertRig(outputs.last(), algorithm.evaluate(before, f.brow, context, {}), "valid real mesh witnesses")
        val axes = setOf(open)
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

    @Test fun denseSourceStopsAtTheUnresolvedHistoricalAndNewLidPredecessor(): Unit = runBlocking {
        // Actual dense source is outside this batch's demonstrated lid lowering support.
        // Keep frozen Lid8 unchanged and require Lid9 to fail closed at the same boundary.
        val f = fixture(artwork(scale = 24), tuned = true).prefix("brow.lid-coupling")
        for ((label, stack) in listOf("historical Lid8" to f.withVersions(oldVersions), "source Lid9" to f.stack)) {
            val failure = assertFailsWith<IllegalArgumentException>("$label dense predecessor must not execute silently") {
                WorkspacePreviewBuilder().build(f.document(stack))
            }
            assertContains(failure.message.orEmpty(), "Cannot resolve prior lid carrier", message = "$label actual lid boundary")
            assertTrue(failure.stackTrace.any { it.className.endsWith(
                if (label == "historical Lid8") "BrowLidV8Algorithm" else "BrowLidV9Algorithm") },
                "$label must reach the real lid algorithm, not fail in a fabricated or malformed fixture")
        }
    }

    @Test fun exactProviderOrderAliasesAndPackageIdentityAreWorkerInputs(): Unit = runBlocking {
        val complete = fixture()
        val outputs = assertNotNull(WorkspacePreviewBuilder().build(complete.document()).modifierEvaluation).outputs
        for (type in listOf("brow.shape", "brow.lid-coupling", "brow.blink", "brow.spring")) {
            val f = complete.prefix(type)
            val before = outputs[f.stack.instances.lastIndex - 1]
            val context = f.sourceContext()
            val algorithm = f.sourceDefinition().plugin as ReadingModifierAlgorithmPlugin
            val corrupted = mutableListOf<List<ModifierUpstreamCapability>>()
            for (provider in context.upstream) {
                val plugin = assertNotNull(provider.plugin)
                for (changed in listOf(provider.copy(instanceId = null),
                    provider.copy(targets = provider.targets.take(1)),
                    provider.copy(outputAliases = provider.outputAliases + ("shared/deformer:wrong" to "wrong")),
                    provider.copy(plugin = plugin.copy(digest = "0".repeat(64))),
                    provider.copy(plugin = plugin.copy(dependencyDigests = plugin.dependencyDigests + ("foreign" to "1".repeat(64)))))) {
                    corrupted += listOf(context.upstream.map { if (it == provider) changed else it })
                }
                corrupted += listOf(context.upstream.filterNot { it == provider })
            }
            for ((index, upstream) in corrupted.withIndex()) assertFailsWith<IllegalArgumentException>("$type worker contract $index") {
                algorithm.evaluate(before, f.brow, context.withUpstream(upstream), {})
            }
            val reordered = ModifierAlgorithmContext(JsonObject(context.values + ("providers" to
                JsonArray(context.values.getValue("providers").jsonArray.reversed()))), context.resources, context.upstream)
            assertFailsWith<IllegalArgumentException>("$type captured execution order") {
                algorithm.evaluate(before, f.brow, reordered, {})
            }
            assertRig(outputs[f.stack.instances.lastIndex], algorithm.evaluate(before, f.brow, context, {}), "$type intact worker contract")
        }
    }

    @Test fun changedBrowAndEyeControlDefinitionsRejectOnRealPredecessors(): Unit = runBlocking {
        val complete = fixture(tuned = true, eyeAddons = true)
        val outputs = assertNotNull(WorkspacePreviewBuilder().build(complete.document()).modifierEvaluation).outputs
        for (type in listOf("brow.shape", "brow.blink", "brow.spring")) {
            val f = complete.prefix(type)
            val before = outputs[f.stack.instances.lastIndex - 1]
            val context = f.sourceContext()
            val algorithm = f.sourceDefinition().plugin as ReadingModifierAlgorithmPlugin
            val ids = when (type) {
                "brow.shape" -> listOf("ParamBrowLY")
                "brow.blink" -> listOf("ParamBrowLY", "ParamBrowLAngle", "ParamEyeLOpen", "ParamEyeLSmile")
                else -> listOf("ParamBrowLY", "ParamBrowLAngle")
            }
            for (id in ids) {
                val parameter = before.parameters.single { it.id == id }
                for (changed in listOf(parameter.copy(min = parameter.min - .1f), parameter.copy(max = parameter.max + .1f),
                    parameter.copy(default = parameter.default + .1f), parameter.copy(blend = !parameter.blend))) {
                    assertFailsWith<IllegalArgumentException>("$type incompatible $id: $changed") {
                        algorithm.evaluate(before.copy(parameters = before.parameters.map { if (it.id == id) changed else it }), f.brow, context, {})
                    }
                }
            }
        }
    }

    @Test fun repeatedSourceConsumersAndSwappedRequiredOwnersRejectBeforeCapture() {
        val complete = fixture()
        for (type in listOf("brow.shape", "brow.lid-coupling", "brow.blink", "brow.spring")) {
            val f = complete.prefix(type)
            val duplicate = f.stack.copy(instances = f.stack.instances.dropLast(1) + f.brow.copy(id = "repeated-$type") + f.brow)
            assertFailsWith<IllegalArgumentException>("Repeated $type") { f.copy(stack = duplicate).sourceContext() }
            val reversed = f.stack.copy(instances = f.stack.instances.dropLast(1).reversed() + f.brow)
            assertFailsWith<IllegalArgumentException>("Reversed $type provider order") { f.copy(stack = reversed).sourceContext() }
        }
    }

    @Test fun completeSourceCohortRejectsMirroredRolledBilateralInteriorAndOpenMouthArt(): Unit = runBlocking {
        val ordinary = artwork()
        val open = ordinary.copy(layerOverrides = ordinary.layerOverrides + ("mouth" to
            LayerClassificationOverride(LayerType.PRESET, SemanticTag.MOUTH_OPEN, Side.NONE)))
        val variants = linkedMapOf("mirrored" to artwork(mirror = true), "rolled" to artwork(upright = false),
            "bilateral" to artwork(bilateral = true), "teeth" to artwork(interiors = 1),
            "teeth and tongue" to artwork(interiors = 2), "open mouth" to open)
        for ((label, art) in variants) {
            val f = fixture(art)
            // The full real chain must fail upstream, not proceed using fabricated provider metadata.
            assertFailsWith<IllegalArgumentException>("Full source chain: $label") {
                WorkspacePreviewBuilder().build(f.document())
            }
            val early = if (label == "open mouth") listOf("brow/fitted", "brow.shape", "brow.lid-coupling")
                else listOf("brow/fitted", "brow.shape", "brow.lid-coupling", "brow.blink", "brow.spring")
            for (type in early) assertFailsWith<IllegalArgumentException>("$type early source boundary: $label") {
                f.prefix(type).sourceContext()
            }
            // Blink/Spring share historical capture support for MOUTH_OPEN; their new-tuple
            // worker envelopes reject it in directMaterialEnvelopes... rather than during capture.
        }
    }

    @Test fun customSkeletonAndOriginalParentOverridesAreNotSanitizedAway(): Unit = runBlocking {
        val complete = fixture()
        val variants = linkedMapOf(
            "skeleton" to complete.sourceModel.config.copy(rigEdits = complete.sourceModel.config.rigEdits.copy(skeleton = SkeletonSpec())),
            "parent override" to complete.sourceModel.config.copy(parentOverrides = mapOf("brow" to null)),
        )
        for ((label, config) in variants) {
            val f = complete.copy(sourceModel = complete.sourceModel.copy(config = config))
            // Fitted5 is frozen and does not reject every authored parent override at capture.
            val types = listOf("brow.shape", "brow.lid-coupling", "brow.blink", "brow.spring") +
                if (label == "skeleton") listOf("brow/fitted") else emptyList()
            for (type in types) assertFailsWith<IllegalArgumentException>("$type original $label") {
                f.prefix(type).sourceContext()
            }
            val document = complete.document.copy(settings = WorkspaceSettingsCodec.encode(config),
                parentOverrides = config.parentOverrides,
                rigEdits = config.rigEdits.copy(modifierStack = complete.stack.toJson()))
            assertFailsWith<IllegalArgumentException>("Complete source chain with original $label") {
                WorkspacePreviewBuilder().build(document)
            }
        }
    }

    @Test fun realClassifiedLashRolesStayOutsideTheNewSourceCohort(): Unit = runBlocking {
        val ordinary = artwork()
        for (role in listOf(PartRole.LOWER, PartRole.LID, PartRole.SHADOW)) {
            val art = ordinary.copy(layerOverrides = ordinary.layerOverrides +
                ("lash" to ordinary.layerOverrides.getValue("lash").copy(role = role)))
            val complete = fixture(art)
            assertEquals(role, complete.source().analysis.layers.single { it.source.id.raw == "lash" }.semantic.role,
                "The actual analyzer must retain the tested lash role")
            assertFailsWith<IllegalArgumentException>("Full source chain rejects retained lash/$role") {
                WorkspacePreviewBuilder().build(complete.document())
            }
            // Shared Blink/Spring capture retains historical roles. The new worker envelope
            // guard is tested separately; Shape7/Lid9 enforce this boundary during capture.
            for (type in listOf("brow.shape", "brow.lid-coupling")) {
                assertFailsWith<IllegalArgumentException>("$type must reject actual lash/$role classification") {
                    complete.prefix(type).sourceContext()
                }
            }
        }
        // SHADOW is not a valid brow or white role. The real analyzer normalizes these to
        // ordinary material, so they are positive controls, not impossible rejection fixtures.
        for (id in listOf("brow", "white")) {
            val art = ordinary.copy(layerOverrides = ordinary.layerOverrides +
                (id to ordinary.layerOverrides.getValue(id).copy(role = PartRole.SHADOW)))
            val complete = fixture(art)
            assertNull(complete.source().analysis.layers.single { it.source.id.raw == id }.semantic.role)
            for (type in listOf("brow.shape", "brow.lid-coupling", "brow.blink", "brow.spring"))
                assertNotNull(complete.prefix(type).sourceContext(), "$type normalized ordinary $id remains supported")
        }
    }

    @Test fun directMaterialEnvelopesRejectOnlyForNewTuplesWithoutNarrowingTheHistoricalContract(): Unit = runBlocking {
        val complete = fixture()
        val newOutputs = assertNotNull(WorkspacePreviewBuilder().build(complete.document()).modifierEvaluation).outputs
        fun changed(context: ModifierAlgorithmContext, tag: String, field: String, value: String): ModifierAlgorithmContext {
            val ancestry = context.values.getValue("source_ancestry").jsonObject
            val layers = ancestry.getValue("layers").jsonArray
            assertEquals(1, layers.count { it.jsonObject.getValue("tag") == JsonPrimitive(tag) })
            val altered = JsonArray(layers.map { element ->
                val layer = element.jsonObject
                if (layer.getValue("tag") != JsonPrimitive(tag)) layer else JsonObject(layer + (field to JsonPrimitive(value)))
            })
            return ModifierAlgorithmContext(JsonObject(context.values + ("source_ancestry" to
                JsonObject(ancestry + ("layers" to altered)))), context.resources, context.upstream)
        }
        val mutations = listOf(Triple("MOUTH", "tag", "MOUTH_OPEN"), Triple("EYEBROW", "role", "SHADOW"),
            Triple("EYEWHITE", "role", "SHADOW"), Triple("EYELASH", "role", "LID"))
        val stages = mapOf("brow.shape" to io.github.psd2live.algorithms.binding.BrowSourceProvidersV1.Stage.SHAPE,
            "brow.blink" to io.github.psd2live.algorithms.binding.BrowSourceProvidersV1.Stage.BLINK,
            "brow.spring" to io.github.psd2live.algorithms.binding.BrowSourceProvidersV1.Stage.SPRING)
        for ((type, stage) in stages) {
            val f = complete.prefix(type)
            val before = newOutputs[f.stack.instances.lastIndex - 1]
            val context = f.sourceContext()
            val algorithm = f.sourceDefinition().plugin as ReadingModifierAlgorithmPlugin
            for ((tag, field, value) in mutations) {
                val corrupted = changed(context, tag, field, value)
                assertFailsWith<IllegalArgumentException>("$type direct new-tuple $tag/$field") {
                    io.github.psd2live.algorithms.binding.BrowSourceProvidersV1.captured(
                        ModifierEvaluationInput(before, corrupted), f.brow, stage)
                }
                assertFailsWith<IllegalArgumentException>("$type worker new-tuple $tag/$field") {
                    algorithm.evaluate(before, f.brow, corrupted, {})
                }
            }
            if (type != "brow.shape") {
                val old = f.copy(stack = f.withVersions(oldVersions + (type to f.brow.version)))
                val oldContext = old.sourceContext()
                val expected = io.github.psd2live.algorithms.binding.BrowSourceProvidersV1.captured(
                    ModifierEvaluationInput(before, oldContext), old.brow, stage)
                for ((tag, field, value) in mutations) {
                    // This checks only the provider envelope contract. Historical source decoders retain
                    // their existing material guards; the new fitted5 rule must not migrate old tuples.
                    assertEquals(expected, io.github.psd2live.algorithms.binding.BrowSourceProvidersV1.captured(
                        ModifierEvaluationInput(before, changed(oldContext, tag, field, value)), old.brow, stage),
                        "$type old-tuple provider envelope remains unchanged for $tag/$field")
                }
            }
        }
    }

    @Test fun shapeControlsReuseRenamedLabelsAndAppendOnlyMissingChildrenToAliasedNestedFolder(): Unit = runBlocking {
        val plain = fixture(tuned = true).prefix("brow.shape")
        val instance = plain.brow.copy(outputAliases = plain.brow.outputAliases +
            ("parameter-folder:ParamGroupBrows" to "authored-nested-brow-folder"))
        val f = plain.copy(stack = plain.stack.copy(instances = plain.stack.instances.dropLast(1) + instance))
        val outputs = assertNotNull(WorkspacePreviewBuilder().build(f.document()).modifierEvaluation).outputs
        val before = outputs[outputs.lastIndex - 1]
        val definition = f.sourceDefinition()
        val context = f.sourceContext()
        val algorithm = definition.plugin as ReadingModifierAlgorithmPlugin
        val declared = definition.parameters(instance)
        assertEquals(setOf("ParamBrowLX", "ParamBrowRX", "ParamBrowLAngle", "ParamBrowRAngle", "ParamBrowLForm", "ParamBrowRForm"),
            declared.mapTo(hashSetOf()) { it.id })
        assertEquals(6, declared.size)
        val ids = declared.mapTo(hashSetOf()) { it.id }
        val otherParameters = before.parameters.filterNot { it.id in ids }
        val renamed = declared.map { it.copy(name = "Authored label for ${it.id}") }
        val unrelated = ParameterNode.Group("authored-unrelated", "Unrelated controls", false,
            listOf(ParameterNode.Param("ParamAngleX")))
        val folder = ParameterNode.Group(instance.outputId("parameter-folder:ParamGroupBrows"), "Authored brows", false,
            renamed.map { ParameterNode.Param(it.id) })
        val outer = ParameterNode.Group("authored-outer", "Authored nested controls", true, listOf(unrelated, folder))
        val tree = before.parameterTree + outer
        val reusedInput = before.copy(parameters = otherParameters + renamed, parameterTree = tree)
        val reused = algorithm.changes(ModifierEvaluationInput(reusedInput, context), instance, {})
        assertEquals(renamed, reused.parameters.reused)
        assertTrue(reused.parameters.added.isEmpty())
        assertTrue(reused.parameters.updated.isEmpty())
        assertNull(reused.parameterTree, "All controls already exist, so no parameter-folder rewrite is permitted")
        val reusedOutput = algorithm.evaluate(reusedInput, instance, context, {})
        assertEquals(reusedInput.parameters, reusedOutput.parameters, "Keep authored parameter labels")
        assertEquals(tree, reusedOutput.parameterTree, "Keep exact nested tree when all controls are reused")
        assertEquals(outputs.last().meshes, reusedOutput.meshes, "Predeclared controls preserve source shape motion")
        assertEquals(outputs.last().deformers, reusedOutput.deformers)

        val partial = renamed.filterIndexed { index, _ -> index % 2 == 0 }
        val missing = declared.filter { next -> partial.none { it.id == next.id } }
        val partialFolder = folder.copy(children = listOf(ParameterNode.Param("ParamBrowLY")) +
            partial.map { ParameterNode.Param(it.id) })
        val partialOuter = outer.copy(children = listOf(unrelated, partialFolder))
        val partialTree = before.parameterTree + partialOuter
        val partialInput = before.copy(parameters = otherParameters + partial, parameterTree = partialTree)
        val changes = algorithm.changes(ModifierEvaluationInput(partialInput, context), instance, {})
        assertEquals(partial, changes.parameters.reused)
        assertEquals(missing, changes.parameters.added)
        val expectedFolder = partialFolder.copy(children = partialFolder.children + missing.map { ParameterNode.Param(it.id) })
        val expectedTree = before.parameterTree + partialOuter.copy(children = listOf(unrelated, expectedFolder))
        val treeChange = assertNotNull(changes.parameterTree)
        assertEquals(partialTree, treeChange.before)
        assertEquals(expectedTree, treeChange.after, "Append only missing controls under the exact aliased nested folder")
        val partialOutput = algorithm.evaluate(partialInput, instance, context, {})
        assertEquals(expectedTree, partialOutput.parameterTree)
        assertEquals(otherParameters + partial + missing, partialOutput.parameters)
        assertEquals(outputs.last().meshes, partialOutput.meshes)
        assertEquals(outputs.last().deformers, partialOutput.deformers)

        for (parameter in renamed) {
            val incompatible = listOf(parameter.copy(min = parameter.min - .1f), parameter.copy(max = parameter.max + .1f),
                parameter.copy(default = parameter.default + .1f), parameter.copy(blend = !parameter.blend),
                parameter.copy(repeat = !parameter.repeat),
                parameter.copy(keys = if (parameter.keys == null) Floats.values(-1f, 0f, 1f) else null))
            for (changed in incompatible) assertFailsWith<IllegalArgumentException>("Reject incompatible Shape7 ${parameter.id}: $changed") {
                algorithm.evaluate(reusedInput.copy(parameters = reusedInput.parameters.map {
                    if (it.id == changed.id) changed else it
                }), instance, context, {})
            }
        }
    }
}
