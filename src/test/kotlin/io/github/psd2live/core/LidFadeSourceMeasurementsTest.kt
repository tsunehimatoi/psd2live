package io.github.psd2live.core

import io.github.psd2live.algorithms.binding.EyeRig
import io.github.psd2live.algorithms.binding.LidFadeInputs
import io.github.psd2live.core.fit.*
import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.core.recipe.*
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.serialization.json.jsonObject
import org.umamo.format.art.*
import kotlin.math.abs
import kotlin.test.*

/** Numeric source capture retains the native fit without constructing its discarded animated scaffold. */
class LidFadeSourceMeasurementsTest {
    private data class Shape(val id: String, val tag: SemanticTag, val side: Side,
                             val x: Int, val y: Int, val width: Int, val height: Int,
                             val role: PartRole? = null, val opacity: Float = 1f)
    private data class Fixture(val analysis: PipelineAnalysis, val config: PipelineConfig) {
        val targets = linkedMapOf("shadow-target" to "shadow", "lid-target" to "lid", "right-target" to "lid-right")
    }

    private fun fixture(mirror: Boolean = false, bilateral: Boolean = false, tuned: Boolean = false): Fixture {
        val left = listOf(
            Shape("white", SemanticTag.EYEWHITE, Side.LEFT, 54, 26, 24, 12),
            Shape("iris", SemanticTag.IRIDES, Side.LEFT, 62, 27, 7, 10),
            Shape("lash", SemanticTag.EYELASH, Side.LEFT, 54, 25, 24, 2),
            // The asymmetric closed stroke crosses below the corner line without clamping crease to .15 or .25.
            Shape("close", SemanticTag.EYE_CLOSE, Side.LEFT, 54, 32, 24, 3),
            Shape("lid", SemanticTag.EYELASH, Side.LEFT, 54, 23, 24, 2, PartRole.LID, .63f),
            Shape("shadow", SemanticTag.EYELASH, Side.LEFT, 54, 27, 24, 2, PartRole.SHADOW, .81f),
        )
        val right = if (bilateral) listOf(
            Shape("white-right", SemanticTag.EYEWHITE, Side.RIGHT, 18, 22, 20, 10),
            Shape("iris-right", SemanticTag.IRIDES, Side.RIGHT, 24, 23, 6, 8),
            Shape("lash-right", SemanticTag.EYELASH, Side.RIGHT, 18, 21, 20, 2),
            Shape("close-right", SemanticTag.EYE_CLOSE, Side.RIGHT, 18, 27, 20, 3),
            Shape("lid-right", SemanticTag.EYELASH, Side.RIGHT, 18, 19, 20, 2, PartRole.LID, .47f),
        ) else emptyList()
        val shapes = (left + right + listOf(
            Shape("nose", SemanticTag.NOSE, Side.NONE, 45, 42, 5, 4),
            Shape("mouth", SemanticTag.MOUTH, Side.NONE, 39, 53, 18, 5),
            Shape("face", SemanticTag.FACE, Side.NONE, 12, 10, 72, 64),
            Shape("back", SemanticTag.BACK_HAIR, Side.NONE, 8, 5, 80, 76),
            Shape("top", SemanticTag.TOPWEAR, Side.NONE, 15, 73, 66, 36),
        )).map { shape -> if (!mirror) shape else shape.copy(x = 96 - shape.x - shape.width,
            side = when (shape.side) { Side.LEFT -> Side.RIGHT; Side.RIGHT -> Side.LEFT; else -> shape.side }) }
        val source = WorkspaceSourceArt(96, 112, shapes.mapIndexed { index, shape ->
            val rgba = ByteArray(shape.width * shape.height * 4)
            for (y in 0 until shape.height) for (x in 0 until shape.width) {
                val sampleX = if (mirror) shape.width - 1 - x else x
                val closed = shape.tag == SemanticTag.EYE_CLOSE
                val row = when { sampleX < shape.width / 3 -> 0; sampleX > shape.width * 3 / 4 -> 2; else -> 1 }
                if (!closed || y == row) rgba[(y * shape.width + x) * 4 + 3] = -1
            }
            WorkspaceSourceLayer(LayerId(shape.id), shape.id, "", SourceLayerKind.Raster, true, index,
                LayerBounds(shape.x, shape.y, shape.width, shape.height), shape.opacity, false, LayerBlend.Normal,
                ChannelMask.ALL, LayerRaster(shape.width, shape.height, rgba), null, null, false)
        }, emptyList())
        val side = if (mirror) "R" else "L"
        val config = PipelineConfig(atlasSize = 128, meshSpacing = 6, generatePhysics = false, exportMoc3 = false,
            layerOverrides = shapes.associate { it.id to LayerClassificationOverride(LayerType.PRESET, it.tag, it.side, role = it.role) },
            recipe = RigRecipe(true, bases = mapOf("eye" to BaseChoice("eye/fitted", 2,
                if (tuned) mapOf("lowerShare" to 12f, "roundness" to 115f, "lashSquash" to 75f) else emptyMap())),
                moves = if (tuned) mapOf("eye.$side" to mapOf("upper" to FeatureMove(.3f, -.4f),
                    "outer" to FeatureMove(.15f, .1f))) else emptyMap()),
            rigTuning = if (tuned) RigTuning(gazeX = 13f, blinkDepth = 43f) else RigTuning())
        return Fixture(CharacterAnalyzer.analyze(source, config), config)
    }

    private fun <T> profile(cached: Boolean = false, block: () -> T): Pair<T, Map<String, Pair<Double, Long>>> {
        val recording = RigBuildProfile.recording
        val enabled = RigStageCache.enabled
        RigBuildProfile.reset()
        try {
            RigBuildProfile.recording = true
            RigStageCache.enabled = cached
            return block() to RigBuildProfile.snapshot()
        } finally {
            RigBuildProfile.recording = recording
            RigStageCache.enabled = enabled
            RigBuildProfile.reset()
        }
    }

    private fun Map<String, Pair<Double, Long>>.count(name: String) = this[name]?.second ?: 0L

    private fun assertEyes(expected: FittedEyes?, actual: FittedEyes?) {
        if (expected == null) { assertNull(actual); return }
        val next = assertNotNull(actual)
        assertEquals(expected.fitting.fits, next.fitting.fits, "Every source eye observation")
        assertEquals(expected.fitting.reasons, next.fitting.reasons, "Unreadable and missing eyes")
        assertEquals(expected.fitting.level.first.toRawBits(), next.fitting.level.first.toRawBits())
        assertEquals(expected.fitting.level.second.toRawBits(), next.fitting.level.second.toRawBits())
        assertEquals(expected.options, next.options)
        assertEquals(expected.rigs.keys.toList(), next.rigs.keys.toList())
        for ((side, old) in expected.rigs) {
            val fresh = next.rigs.getValue(side)
            assertEquals(old.fit, fresh.fit, "All corrected source/artwork observations/$side")
            assertEquals(old.crease.toRawBits(), fresh.crease.toRawBits(), "Crease bits/$side")
            assertContentEquals(old.keys, fresh.keys)
        }
    }

    private fun assertContext(expected: ModifierAlgorithmContext, actual: ModifierAlgorithmContext) {
        assertEquals(expected.values, actual.values)
        assertEquals(expected.values.toString(), actual.values.toString(), "Literal JSON, key order and signed zero")
        assertEquals(expected.values.getValue("targets").jsonObject.keys.toList(), actual.values.getValue("targets").jsonObject.keys.toList())
        assertEquals(expected.upstream, actual.upstream)
        assertTrue(expected.resources.isEmpty())
        assertTrue(actual.resources.isEmpty(), "lid-fade/1 has no resources")
        val next = LidFadeInputs.decode(actual)
        LidFadeInputs.decode(expected).forEach { (id, old) ->
            assertEquals(old.crease.toRawBits(), next.getValue(id).crease.toRawBits())
            assertEquals(old.opacity.toRawBits(), next.getValue(id).opacity.toRawBits())
        }
    }

    @Test fun coldSourceCapturePreservesRolledMirroredAndAsymmetricFitsWithoutAnimatedScaffolds() {
        var differingNaiveCreases = 0
        for (mirror in listOf(false, true)) for (bilateral in listOf(false, true)) for (tuned in listOf(false, true)) {
            val fixture = fixture(mirror, bilateral, tuned)
            val (analysis, config) = fixture
            val targets = fixture.targets
            val (old, oldCounts) = profile {
                val context = RigBuilder.rigContext(analysis, config, PreviewMeshCache())
                assertTrue(abs(context.headSpace.angleDegrees) > .1f, "The source must have real head roll")
                assertTrue(context.deformers.isNotEmpty())
                val frozen = FrozenLidFadeMeasurements.fit(context, config)
                assertEyes(frozen, context.fittedEyes(config))
                FrozenLidFadeMeasurements.encode(context, config, targets, frozen)
            }
            val (next, counts) = profile { RigBuilder.lidFadeSourceInputs(analysis, config, PreviewMeshCache(), targets) }
            assertContext(old, next)
            assertEquals(1L, oldCounts.count("scaffold: deformers (built)"), "Independent native positive control")
            assertEquals(0L, counts.count("scaffold: deformers (built)"), "No discarded animated scaffold")
            assertEquals(1L, counts.count("source fitting: eyes"))
            assertEquals(1L, counts.count("source fitting: eye artwork"))
            assertTrue(LidFadeInputs.decode(next).values.any { it.crease > .15f && it.crease < .25f },
                "Exercise a measured crease, not only endpoint clamping")
            val context = RigBuilder.rigContext(analysis, config.copy(generateDeformers = false), PreviewMeshCache())
            val aligned = assertNotNull(context.fittedEyesForSourceLidFade(config))
            val naive = assertNotNull(context.fittedEyes(config))
            assertNotEquals(naive.fitting.fits, aligned.fitting.fits, "A flag-only change loses the source coordinate system")
            assertTrue(aligned.rigs.any { (side, eye) ->
                val unaligned = naive.rigs.getValue(side).fit
                abs(eye.fit.originX - unaligned.originX) + abs(eye.fit.originY - unaligned.originY) > .1f
            }, "The alignment counterexample must change actual fitted coordinates")
            differingNaiveCreases += aligned.rigs.count { (side, eye) -> eye.crease.toRawBits() != naive.rigs.getValue(side).crease.toRawBits() }
        }
        assertTrue(differingNaiveCreases > 0, "Ignoring alignment must be detected by exact crease bits")
    }

    @Test fun liveEyePartAndHandleCorrectionsStillReachTheSharedNumericalFit() {
        val fixture = fixture(bilateral = true, tuned = true)
        val original = RigBuilder.rigContext(fixture.analysis, fixture.config, PreviewMeshCache())
        val eyes = assertNotNull(original.fittedEyes(fixture.config))
        val part = eyes.rigs.getValue(Side.LEFT).fit.artwork!!.components.first { it.layerId == "lash" }
        val region = if (part.region == EyeRegion.UPPER) EyeRegion.LOWER else EyeRegion.UPPER
        val config = fixture.config.copy(recipe = fixture.config.recipe.copy(eyeParts = mapOf("lash" to mapOf(
            part.id to EyePartOverride(part.sourceStamp, region = region)))))
        val context = RigBuilder.rigContext(fixture.analysis, config, PreviewMeshCache())
        val expected = assertNotNull(FrozenLidFadeMeasurements.fit(context, config))
        val measured = RigBuilder.rigContext(fixture.analysis, config.copy(generateDeformers = false), PreviewMeshCache())
        val actual = assertNotNull(measured.fittedEyesForSourceLidFade(config))
        assertEyes(expected, actual)
        assertEquals(region, actual.rigs.getValue(Side.LEFT).fit.artwork!!.components.single { it.id == part.id }.region)
        assertTrue(actual.rigs.getValue(Side.LEFT).fit.upperCorrection!!.any { it != 0f })
        assertContext(FrozenLidFadeMeasurements.encode(context, config, fixture.targets, expected),
            RigBuilder.lidFadeSourceInputs(fixture.analysis, config, PreviewMeshCache(), fixture.targets))
    }

    @Test fun sourceAlignmentNeverReadsOrPollutesTheConfigIdentityNativeFitCache() {
        val fixture = fixture(tuned = true)
        val config = fixture.config
        val cache = PreviewMeshCache()
        val source = RigBuilder.rigContext(fixture.analysis, config.copy(generateDeformers = false), cache)
        val unaligned = assertNotNull(source.fittedEyes(config))
        val native = RigBuilder.rigContext(fixture.analysis, config, cache)
        val nativeFit = assertNotNull(native.fittedEyes(config))
        val changed = config.copy(recipe = config.recipe.copy(moves = mapOf("eye.L" to mapOf("upper" to FeatureMove(-.2f, -.7f)))))
        for (choice in listOf(config, config.copy(generateDeformers = false), changed, config.copy(meshOnly = true), config, changed, config)) {
            val reference = RigBuilder.rigContext(fixture.analysis, choice, cache)
            assertEyes(FrozenLidFadeMeasurements.fit(reference, choice), source.fittedEyesForSourceLidFade(choice))
            assertSame(unaligned, source.fittedEyes(config), "Source-only alignment cannot overwrite the config-only cache")
            assertSame(nativeFit, native.fittedEyes(config), "Ordinary native fit identity remains unchanged")
        }
        assertNotEquals(nativeFit.fitting.fits, unaligned.fitting.fits)
        val rootParent = config.copy(parentOverrides = mapOf("white" to "root"))
        val headParent = config.copy(parentOverrides = mapOf("white" to "DeformHeadRotation"))
        for (choice in listOf(rootParent, config, headParent, rootParent, config)) {
            assertEyes(FrozenLidFadeMeasurements.fit(native, choice), native.fittedEyes(choice))
            assertSame(nativeFit, native.fittedEyes(config))
        }
        assertNotEquals(nativeFit.fitting.fits, assertNotNull(native.fittedEyes(rootParent)).fitting.fits)
    }

    @Test fun sharedSourceCachesReuseMeasurementsAndRemainExactWithCachesDisabled() {
        val fixture = fixture(bilateral = true, tuned = true)
        val cache = PreviewMeshCache()
        val (contexts, counts) = profile(cached = true) { List(3) {
            RigBuilder.lidFadeSourceInputs(fixture.analysis, fixture.config, cache, fixture.targets)
        } }
        assertEquals(1L, counts.count("source fitting: eyes"))
        assertEquals(1L, counts.count("source fitting: eye artwork"))
        assertEquals(0L, counts.count("scaffold: deformers (built)"))
        val (uncached, coldCounts) = profile { List(2) {
            RigBuilder.lidFadeSourceInputs(fixture.analysis, fixture.config, cache, fixture.targets)
        } }
        assertEquals(2L, coldCounts.count("source fitting: eyes"))
        assertEquals(2L, coldCounts.count("source fitting: eye artwork"))
        (contexts + uncached).forEach { assertContext(contexts.first(), it) }
    }

    @Test fun absentTransparentAndUnreadableEyesAndNonFittedRecipesKeepTheirEmptyOutcomes() {
        val fixture = fixture()
        val noWhite = fixture.analysis.layers.filter { it.semantic.tag != SemanticTag.EYEWHITE }
        val transparent = fixture.analysis.layers.map { if (it.semantic.tag == SemanticTag.EYEWHITE) it.copy(opaquePixels = 0) else it }
        val unreadable = fixture.analysis.layers.map { layer ->
            if (layer.semantic.tag != SemanticTag.EYEWHITE) layer else {
                val src = layer.source as WorkspaceSourceLayer
                val rgba = ByteArray(src.raster.rgba.size).also { it[3] = -1 }
                layer.copy(source = src.copy(raster = LayerRaster(src.raster.width, src.raster.height, rgba)), opaquePixels = 1)
            }
        }
        for (layers in listOf(noWhite, transparent, unreadable, emptyList())) {
            val analysis = fixture.analysis.copy(layers = layers)
            val native = RigBuilder.rigContext(analysis, fixture.config, PreviewMeshCache())
            val expected = FrozenLidFadeMeasurements.encode(native, fixture.config, fixture.targets)
            val actual = RigBuilder.lidFadeSourceInputs(analysis, fixture.config, PreviewMeshCache(), fixture.targets)
            assertContext(expected, actual)
            assertTrue(LidFadeInputs.decode(actual).isEmpty())
        }
        val legacy = fixture.config.copy(recipe = RigRecipe.Legacy)
        val native = RigBuilder.rigContext(fixture.analysis, legacy, PreviewMeshCache())
        assertNull(native.fittedEyes(legacy))
        assertContext(FrozenLidFadeMeasurements.encode(native, legacy, fixture.targets),
            RigBuilder.lidFadeSourceInputs(fixture.analysis, legacy, PreviewMeshCache(), fixture.targets))
    }

    @Test fun filteringOpacityClampingAndFirstNonemptyEyeSelectionStayLiteral() {
        val fixture = fixture()
        val firstWhite = fixture.analysis.layers.first { it.semantic.tag == SemanticTag.EYEWHITE }
        val emptyFirst = firstWhite.copy(source = (firstWhite.source as WorkspaceSourceLayer).copy(id = LayerId("empty-first")), opaquePixels = 0)
        val analysis = fixture.analysis.copy(layers = listOf(emptyFirst) + fixture.analysis.layers.map { layer ->
            val opacity = when (layer.source.id.raw) { "lid" -> -1f; "shadow" -> 2f; else -> layer.source.opacity }
            layer.copy(source = (layer.source as WorkspaceSourceLayer).copy(opacity = opacity))
        })
        val targets = linkedMapOf("second" to "shadow", "missing" to "missing", "first" to "lid",
            "lash-is-not-a-lid" to "lash", "face-is-not-a-lid" to "face")
        for ((type, parameter, kept) in listOf(Triple(LayerType.PRESET, "ParamCustom", true),
            Triple(LayerType.TOGGLE, "", true), Triple(LayerType.TOGGLE, "ParamCustom", false))) {
            val config = fixture.config.copy(layerOverrides = fixture.config.layerOverrides + ("lid" to
                fixture.config.layerOverrides.getValue("lid").copy(type = type, parameter = parameter)))
            val native = RigBuilder.rigContext(analysis, config, PreviewMeshCache())
            val expected = FrozenLidFadeMeasurements.encode(native, config, targets)
            val actual = RigBuilder.lidFadeSourceInputs(analysis, config, PreviewMeshCache(), targets)
            assertContext(expected, actual)
            val decoded = LidFadeInputs.decode(actual)
            assertEquals(if (kept) listOf("second", "first") else listOf("second"), decoded.keys.toList())
            assertEquals(1f, decoded.getValue("second").opacity)
            if (kept) assertEquals(0f, decoded.getValue("first").opacity)
        }
        for (ineligible in listOf(emptyMap(), linkedMapOf("ordinary-lash" to "lash", "face" to "face", "absent" to "absent")))
            assertTrue(LidFadeInputs.decode(RigBuilder.lidFadeSourceInputs(analysis, fixture.config, PreviewMeshCache(), ineligible)).isEmpty())
    }

    @Test fun sourceLidWrapperRejectsAncestryItCannotRepresent() {
        val fixture = fixture()
        val source = RigBuilder.rigContext(fixture.analysis, fixture.config.copy(generateDeformers = false), PreviewMeshCache())
        assertFailsWith<IllegalArgumentException> {
            source.fittedEyesForSourceLidFade(fixture.config.copy(parentOverrides = mapOf("white" to "root")))
        }
        assertFailsWith<IllegalArgumentException> {
            source.fittedEyesForSourceLidFade(fixture.config.copy(rigEdits = RigEditOverlay(skeleton = SkeletonSpec())))
        }
        val native = RigBuilder.rigContext(fixture.analysis, fixture.config, PreviewMeshCache())
        assertFailsWith<IllegalStateException> { native.fittedEyesForSourceLidFade(fixture.config) }
    }
}

/** Frozen original numerical body and target capture, independent of the extracted production helper. */
private object FrozenLidFadeMeasurements {
    fun fit(context: RigBuilder.RigContext, config: PipelineConfig): FittedEyes? {
        if (config.recipe.base("eye") !== RigModules.EYE_FITTED) return null
        val values = RigModules.EYE_FITTED.knobs.map { config.recipe.baseValue("eye", it.id) }
        val options = context.eyeOptions(config)
        val space = context.analysis.layers.firstOrNull { it.semantic.tag == SemanticTag.EYEWHITE && it.opaquePixels > 0 }
            ?.let { context.headSpaceFor(it, config) }
        val toRig = { x: Float, y: Float -> space?.toAligned(x, y) ?: (x to y) }
        val result = EyeFitting.fit(context.analysis.layers, context.faceRig.centerX, toRig)
        val fits = result.fits.mapValues { (side, drawn) ->
            drawn.moved(context.rigMoves(config, if (side == Side.LEFT) "eye.L" else "eye.R", toRig))
        }
        val artwork = EyeArtworkFitting.fit(context.analysis.layers, fits, config.recipe.eyeParts, toRig)
        return FittedEyes(result, fits.mapValues { (side, drawn) ->
            val fit = drawn.copy(artwork = artwork[side])
            EyeRig(fit, values[0] / 100f, values[1] / 100f, values[2] / 100f,
                smileArc = options.smileArc, smilePush = options.smilePush, wideLift = options.wideLift)
        }, options)
    }

    fun encode(context: RigBuilder.RigContext, config: PipelineConfig, targetLayers: Map<String, String>,
               fitted: FittedEyes? = fit(context, config)): ModifierAlgorithmContext {
        val eyes = fitted?.rigs.orEmpty()
        val layers = context.analysis.layers.associateBy { it.source.id.raw }
        val targets = targetLayers.mapNotNull { (id, layerId) ->
            val layer = layers[layerId] ?: return@mapNotNull null
            val semantic = layer.semantic
            if (semantic.tag != SemanticTag.EYELASH || (semantic.role != PartRole.LID && semantic.role != PartRole.SHADOW))
                return@mapNotNull null
            val override = config.layerOverrides[layerId]
            if ((override?.type ?: semantic.type) != LayerType.PRESET &&
                (override?.parameter ?: semantic.parameter).isNotBlank()) return@mapNotNull null
            val eye = eyes[semantic.side] ?: return@mapNotNull null
            val parameter = EyeModules.open(semantic.side).raw
            id to LidFadeInputs(parameter, eye.crease, layer.source.opacity.coerceIn(0f, 1f))
        }.toMap()
        return LidFadeInputs.encode(targets)
    }
}
