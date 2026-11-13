package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.MouthCheekLidV3Algorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.compile.RigIrBinary
import io.github.psd2live.format.model.Canvas
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.i18n.tr
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** The unused neutral scaffold is removed only from the fitted cheek-lid brow-coverage read. */
class WorkspaceCheekBrowCoverCaptureTest {
    private val affectedVersions = listOf(3, 4, 5, 6, 7, 9)
    private enum class Cover { NONE, PARTIAL, FULL }
    private data class Shape(val id: String, val tag: SemanticTag, val side: Side,
                             val x: Int, val y: Int, val width: Int, val height: Int, val role: PartRole? = null)

    private fun artwork(version: Int, cover: Cover, mirror: Boolean): WorkspaceDocument {
        val features = listOf(
            Shape("iris", SemanticTag.IRIDES, Side.LEFT, 37, 19, 5, 10),
            Shape("lash", SemanticTag.EYELASH, Side.LEFT, 34, 19, 12, 2),
            Shape("close", SemanticTag.EYE_CLOSE, Side.LEFT, 34, 25, 12, 2),
            Shape("white", SemanticTag.EYEWHITE, Side.LEFT, 34, 20, 12, 8),
            Shape("brow", SemanticTag.EYEBROW, Side.LEFT, 34, 14, 12, 2),
        )
        val paired = if (version in 3..6) features.map {
            it.copy(id = "${it.id}-right", side = Side.RIGHT, x = 64 - it.x - it.width)
        } else emptyList()
        val detail = listOf(
            Shape("mouth", SemanticTag.MOUTH, Side.NONE, 25, 35, 14, 6),
            Shape("face", SemanticTag.FACE, Side.NONE, 15, 8, 34, 40),
            Shape("back", SemanticTag.BACK_HAIR, Side.NONE, 12, 5, 40, 50),
            Shape("top", SemanticTag.TOPWEAR, Side.NONE, 12, 46, 40, 32),
            Shape("lid", SemanticTag.EYELASH, Side.LEFT, 34, 18, 12, 2, PartRole.LID),
            Shape("lid-shadow", SemanticTag.EYELASH, Side.LEFT, 34, 20, 12, 2, PartRole.SHADOW),
            Shape("highlight", SemanticTag.IRIDES, Side.LEFT, 39, 20, 2, 3, PartRole.HIGHLIGHT),
            Shape("teeth-top", SemanticTag.TOOTH_T, Side.NONE, 27, 35, 10, 2),
            Shape("teeth-bottom", SemanticTag.TOOTH_B, Side.NONE, 28, 39, 8, 2),
            Shape("tongue", SemanticTag.TONGUE, Side.NONE, 29, 38, 6, 3),
            Shape("nose", SemanticTag.NOSE, Side.NONE, 29, 28, 5, 3),
        ) + if (version in setOf(6, 7)) listOf(
            Shape("mouth-closed", SemanticTag.MOUTH_CLOSE, Side.NONE, 25, 38, 14, 2)) else emptyList()
        // Low source order is in front. Partial bangs cover half the left brow, not just its mesh bounds.
        val hair = Shape("front", SemanticTag.FRONT_HAIR, Side.NONE, 14, 4,
            if (cover == Cover.PARTIAL) 26 else 36, 13)
        val ordered = if (cover == Cover.NONE) features + paired + detail + hair else listOf(hair) + features + paired + detail
        val shapes = ordered.map {
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

    private data class Fixture(val document: WorkspaceDocument, val model: RigPreviewModel, val stack: RigModifierStack) {
        val owner get() = stack.instances.single { it.type == "mouth.cheek-lid" }
        fun source() = RigModifierSource.of(model.copy(config = model.config.copy(
            rigEdits = model.config.rigEdits.copy(modifierStack = stack.toJson()))))
        fun document() = document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))
    }

    private fun fixture(version: Int = 9, cover: Cover = Cover.PARTIAL, mirror: Boolean = false,
                        coupling: Boolean = true): Fixture {
        val art = artwork(version, cover, mirror)
        val choices = WorkspaceInitialPreset(clothing = false)
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val document = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val model = PSD2LivePipeline().apply { deferRuntimeBundles = true }.buildPreview(document.source, document.config())
        val targets = model.rig.puppet.drawables.filter { it.mesh != null && it.id.raw in model.rig.ownLayerIdByDrawableId }
        val versions = linkedMapOf("rig.scaffold" to 2, "body/legacy" to 2,
            "face/fitted" to if (version <= 4) 1 else 3,
            "eye/fitted" to if (version <= 4) 2 else 5,
            "brow/fitted" to if (version == 9) 4 else if (version <= 4) 1 else 3,
            "mouth/fitted" to when (version) { 3 -> 1; 6 -> 4; 7 -> 5; else -> 3 },
            "brow.shape" to 1,
            "brow.lid-coupling" to if (version == 9) 8 else if (version <= 4) 3 else 6,
            "mouth.cheek-lid" to version)
        val planned = WorkspaceModifierPlan.plan(model, RigModifierStack(), buildJsonObject {
            put("group", "modern-standard")
            putJsonArray("targets") { targets.forEach { addJsonObject { put("kind", "mesh"); put("id", it.id.raw) } } }
        }, versions.keys.drop(1)).first
        val selected = if (coupling) versions else versions - "brow.lid-coupling"
        val stack = planned.copy(instances = planned.instances.filter { it.type in selected }.map { old ->
            val knobs = when (old.type) {
                "face/fitted" -> buildJsonObject { put("strength", .73f) }
                "mouth.cheek-lid" -> buildJsonObject { putJsonObject("values") { put("strength", 126f) } }
                else -> JsonObject(emptyMap())
            }
            val pinned = selected.getValue(old.type)
            old.copy(version = pinned, config = JsonObject(old.config + knobs),
                plugin = assertNotNull(BuiltinModifierPlugins.reference(old.type, pinned)))
        })
        assertEquals(selected, stack.instances.associate { it.type to it.version })
        return Fixture(document, model, stack)
    }

    private fun definition(source: RigModifierSource, version: Int) = when (version) {
        3 -> RigMouthCheekLidModifier.fittedDefinition(source)
        4 -> RigMouthCheekLidModifier.fittedMouthDefinition(source)
        5 -> RigMouthCheekLidModifier.fittedEyeDefinition(source)
        6 -> RigMouthCheekLidModifier.closedDefinition(source)
        7 -> RigMouthCheekLidModifier.closedShellDefinition(source)
        9 -> RigMouthCheekLidModifier.openShellDefinition(source)
        else -> error("Unaffected cheek-lid version $version")
    }

    private fun <T> profile(block: () -> T): Pair<T, Map<String, Pair<Double, Long>>> {
        val recording = RigBuildProfile.recording
        val cache = RigStageCache.enabled
        RigBuildProfile.reset()
        try {
            RigBuildProfile.recording = true
            RigStageCache.enabled = false
            return block() to RigBuildProfile.snapshot()
        } finally {
            RigBuildProfile.recording = recording
            RigStageCache.enabled = cache
            RigBuildProfile.reset()
        }
    }

    private fun Map<String, Pair<Double, Long>>.count(name: String) = this[name]?.second ?: 0L
    private fun cover(context: ModifierAlgorithmContext) = context.values.getValue("browCover").jsonPrimitive.float

    private fun assertContext(expected: ModifierAlgorithmContext, actual: ModifierAlgorithmContext, label: String) {
        assertEquals(expected.values, actual.values, "$label values")
        assertEquals(expected.values.toString(), actual.values.toString(), "$label literal JSON order and signed zero")
        assertEquals(cover(expected).toRawBits(), cover(actual).toRawBits(), "$label exact brow-cover float bits")
        assertEquals(expected.upstream, actual.upstream, "$label upstream")
        assertEquals(expected.resources.keys.toList(), actual.resources.keys.toList(), "$label ordered resources")
        expected.resources.forEach { (key, resource) ->
            val observed = actual.resources.getValue(key)
            assertEquals(resource.digest, observed.digest, "$label resource digest $key")
            assertContentEquals(resource.read(), observed.read(), "$label resource bytes $key")
        }
    }

    private fun assertIr(expected: RigIR, actual: RigIR, label: String) {
        assertEquals(expected, actual, "$label full IR")
        assertContentEquals(RigIrBinary.encode(expected), RigIrBinary.encode(actual), "$label binary IR")
    }

    @Test fun coldCoverageReadSkipsExactlyOneNeutralScaffoldAndPreservesFloatBits() {
        for (coverage in Cover.entries) for (mirror in listOf(false, true)) {
            val fixture = fixture(cover = coverage, mirror = mirror)
            val source = fixture.source()
            val native = source.sourceConfig()
            val (old, oldProfile) = profile {
                RigBuilder.rigContext(source.analysis, native, source.meshCache, neutralScaffold = true).browCover
            }
            val (next, nextProfile) = profile {
                RigBuilder.rigContext(source.analysis, native.copy(generateDeformers = false),
                    source.meshCache, neutralScaffold = true).browCover
            }
            val expected = when (coverage) { Cover.NONE -> 0f; Cover.PARTIAL -> .5f; Cover.FULL -> 1f }
            assertEquals(expected.toRawBits(), old.toRawBits(), "Actual ordered source coverage: $coverage, mirror=$mirror")
            assertEquals(old.toRawBits(), next.toRawBits())
            for (name in listOf("scaffold: deformers (built)", "modifier direct neutral heads")) {
                assertEquals(1L, oldProfile.count(name), "Cold legacy positive control: $name")
                assertEquals(0L, nextProfile.count(name), "Coverage must not build a neutral scaffold: $name")
            }
            for (counts in listOf(oldProfile, nextProfile))
                assertEquals(1L, counts.count("scaffold: face rig + frames"), "Both paths recompute source measurements")
        }
    }

    @Test fun everyCompleteCaptureRemovesOnlyTheCoverageScaffold() {
        for (version in affectedVersions) {
            val fixture = fixture(version)
            val source = fixture.source()
            val (old, oldProfile) = profile { FrozenCheekBrowCoverCapture.context(source, fixture.owner) }
            val (next, nextProfile) = profile { definition(source, version).authorContext(fixture.owner) }
            val label = "mouth.cheek-lid/$version"
            assertContext(old, next, label)
            assertTrue(cover(next) > 0f && cover(next) < 1f, "$label must exercise real partial coverage")
            for (name in listOf("scaffold: deformers (built)", "modifier direct neutral heads")) {
                assertTrue(nextProfile.count(name) > 0L, "$label keeps its separate source capture: $name")
                assertEquals(oldProfile.count(name) - 1L, nextProfile.count(name), "$label removes only coverage: $name")
            }
            assertEquals(oldProfile.count("scaffold: face rig + frames"), nextProfile.count("scaffold: face rig + frames"))
            if (version in setOf(7, 9)) {
                val turns = RigIrBinary.decode(next.resources.getValue("lid_turn").read())
                assertTrue(turns.meshes.any { it.shapes.isNotEmpty() }, "$label retains real lid-turn source bytes")
            }
        }
    }

    @Test fun noPrecedingBrowCouplingKeepsZeroAndSkipsTheCoverageRead() {
        for (version in affectedVersions) {
            val fixture = fixture(version, coupling = false)
            val source = fixture.source()
            assertTrue(source.activeBefore(fixture.owner).none { it.type == "brow.lid-coupling" })
            val underlying = RigBuilder.rigContext(source.analysis, source.sourceConfig().copy(generateDeformers = false),
                source.meshCache, neutralScaffold = true).browCover
            assertTrue(underlying > 0f, "The zero must come from the early path, not uncovered artwork")
            val (old, oldProfile) = profile { FrozenCheekBrowCoverCapture.context(source, fixture.owner) }
            val (next, nextProfile) = profile { definition(source, version).authorContext(fixture.owner) }
            assertContext(old, next, "No coupling/$version")
            assertEquals(0f.toRawBits(), cover(next).toRawBits())
            for (name in listOf("scaffold: deformers (built)", "modifier direct neutral heads", "scaffold: face rig + frames"))
                assertEquals(oldProfile.count(name), nextProfile.count(name), "No extra coverage read/$version: $name")
        }
    }

    @Test fun everyVersionPreservesSameInputHostAndInstalledWorkerBinaryIr(): Unit = runBlocking {
        for (version in affectedVersions) {
            val fixture = fixture(version, mirror = version in setOf(4, 7, 9))
            val model = WorkspacePreviewBuilder().build(fixture.document())
            val evaluation = assertNotNull(model.modifierEvaluation)
            assertTrue(evaluation.entries.all { it.status == ModifierStatus.ACTIVE }, "Every fixture provider must execute/$version")
            val owner = fixture.owner
            val source = RigModifierSource.of(model)
            val definition = definition(source, version)
            val observed = assertNotNull(evaluation.readAlgorithms[owner.id]).context
            val old = FrozenCheekBrowCoverCapture.context(source, owner).withUpstream(observed.upstream)
            val next = definition.authorContext(owner).withUpstream(observed.upstream)
            assertContext(observed, next, "Pipeline/$version")
            assertContext(old, next, "Legacy host/$version")
            assertTrue(cover(next) > 0f && cover(next) < 1f)
            assertTrue(next.upstream.any { it.type == "brow.lid-coupling" })
            val index = fixture.stack.index(owner.id)
            val before = evaluation.outputs[index - 1]
            val expected = evaluation.outputs[index]
            assertNotEquals(before, expected, "Cheek-lid/$version must not be a no-op")
            val host = definition.plugin as ReadingModifierAlgorithmPlugin
            assertIr(expected, host.evaluate(before, owner, old, {}), "Legacy host/$version")
            assertIr(expected, host.evaluate(before, owner, next, {}), "New host/$version")
            val root = Files.createTempDirectory("cheek-brow-cover-$version-")
            val preferences = Preferences.userRoot().node("cheek-brow-cover/" + UUID.randomUUID())
            val store = ModifierPluginStore(root.resolve("installed"), preferences)
            try {
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find(owner.type, version))
                BundledAlgorithmArtifacts.retain(store, listOf(artifact))
                val installed = store.snapshot().single()
                assertEquals(owner.plugin, installed.reference, "Unchanged installed package identity/$version")
                for ((mode, context) in listOf("legacy" to old, "measured" to next))
                    assertIr(expected, ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                        before, owner, {}, store, context = context, declaredParameters = definition.parameters(owner)),
                        "$mode installed JVM/$version")
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                try { preferences.removeNode() } finally { ModifierPluginStore.deleteTree(root) }
            }
        }
    }
}

/** Frozen pre-change capture: sourceConfig still constructs a neutral scaffold solely to read browCover. */
private object FrozenCheekBrowCoverCapture {
    fun context(capture: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext = when (instance.version) {
        7 -> closedShell(capture, instance)
        9 -> openShell(capture, instance)
        else -> fitted(capture, instance)
    }

    private fun fitted(capture: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val eye = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
        val measurements = captureLidSource(capture, instance, emptyMap(), eye)
        val layers = capture.analysis.layers.associateBy { it.source.id.raw }
        val carriers = instance.targets.associate { target ->
            val layer = capture.layersByMesh.getValue(target.id)
            target.id to (capture.primaryByLayer.getValue(layer) to tr("model.deformer.meshShape", layers.getValue(layer).source.name))
        }
        val browCover = if (capture.activeBefore(instance).any { it.type == "brow.lid-coupling" }) {
            RigBuilder.rigContext(capture.analysis, capture.sourceConfig(), capture.meshCache, neutralScaffold = true).browCover
        } else 0f
        return MouthCheekLidV3Algorithm.context(measurements, carriers, browCover)
    }

    private fun closedShell(capture: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val context = fitted(capture, instance)
        val scaffold = capture.provider(instance)
        val prefix = prefix(capture, instance, scaffold)
        val (native, measured) = RigFittedMouthModuleSource.context(capture, scaffold, prefix, null)
        return turns(capture, instance, context, native, measured)
    }

    private fun openShell(capture: RigModifierSource, instance: RigModifierInstance): ModifierAlgorithmContext {
        val scaffold = capture.provider(instance)
        require(scaffold.version == 2) { "Cheek and lid v9 requires a shared v2 scaffold" }
        val prefix = prefix(capture, instance, scaffold)
        val mouthId = instance.config.getValue("base").jsonPrimitive.content
        val eyeId = instance.config.getValue("dependencies").jsonObject.getValue("eye/fitted").jsonPrimitive.content
        for ((type, version) in mapOf("mouth/fitted" to 3, "eye/fitted" to 5, "face/fitted" to 3, "brow/fitted" to 4)) {
            val provider = prefix.singleOrNull { it.type == type }
            require(provider != null && provider.version == version && provider.targets.containsAll(instance.targets) &&
                (type != "mouth/fitted" || provider.id == mouthId) && (type != "eye/fitted" || provider.id == eyeId)) {
                "Cheek and lid v9 requires $type v$version on the same shared scaffold"
            }
        }
        val (native, measured) = RigFittedMouthModuleSource.context(capture, scaffold, prefix, null)
        val mouth = requireNotNull(measured.fittedMouth(native)?.takeIf { it.source != null }) {
            "Cheek and lid v9 requires readable fitted open-mouth source artwork"
        }
        require(!mouth.closedLine) { "Cheek and lid v9 does not support a separate closed-mouth drawing" }
        require(measured.fittedEyes(native)?.rigs?.size == 1) { "Cheek and lid v9 requires exactly one readable fitted eye" }
        val context = fitted(capture, instance)
        return turns(capture, instance, context, native, measured)
    }

    private fun prefix(capture: RigModifierSource, instance: RigModifierInstance, scaffold: RigModifierInstance) =
        capture.activeBefore(instance).filter { entry ->
            entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(instance.targets::contains) &&
                RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }

    private fun turns(capture: RigModifierSource, instance: RigModifierInstance, context: ModifierAlgorithmContext,
                      native: PipelineConfig, measured: RigBuilder.RigContext): ModifierAlgorithmContext {
        val layers = measured.analysis.layers.associateBy { it.source.id.raw }
        val turns = instance.targets.mapNotNull { target ->
            val layer = layers[capture.layersByMesh[target.id]] ?: return@mapNotNull null
            if (layer.semantic.tag !in setOf(SemanticTag.EYELASH, SemanticTag.EYEWHITE)) return@mapNotNull null
            val channels = Drawable(DrawableId(target.id), layer.source.name, null, BlendMode.Normal, emptyList(), null, null)
            val raw = RigBuilder.lidTurnLoweringSource(layer, measured, native, capture.atlas, capture.meshCache, channels)
            PuppetIr.meshToIr(raw).takeIf { it.shapes.isNotEmpty() }
        }
        val resource = ModifierAlgorithmResource(RigIrBinary.encode(RigIR(Canvas(1f, 1f), emptyList(), meshes = turns)))
        return ModifierAlgorithmContext(JsonObject(context.values + ("turnName" to JsonPrimitive(tr("model.deformer.meshTurn", "{meshName}")))),
            context.resources + ("lid_turn" to resource))
    }
}
