package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.CheekV5Algorithm
import io.github.psd2live.algorithms.binding.GloomV5Algorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

/** Real source pipelines: no relabelled provider IR and no tolerance-based parity. */
class WorkspaceComposableSkinV5Test {
    /** Small public procedural art with distinct eye, brow, mouth, hair and torso inputs. */
    private fun document(eyeDetails: Boolean = false, mouthDetails: Boolean = false, expressionDetails: Boolean = false, skinDetails: Boolean = false, outfitDetails: Boolean = false, sideLock: Boolean = false, bangOverlay: Boolean = false,
                         chestDetail: Boolean = false, pairedArms: Boolean = false, thinMouth: Boolean = false, pairedBrows: Boolean = false, bilateralEyes: Boolean = false,
                         closedMouth: Boolean = false, ribbonDetails: Boolean = false, creatureDetails: Boolean = false, denseBlush: Boolean = false): WorkspaceDocument {
        data class Shape(val id: String, val tag: SemanticTag, val side: Side, val x: Int, val y: Int, val w: Int, val h: Int, val role: PartRole? = null)
        val shapes = listOf(
            Shape("iris", SemanticTag.IRIDES, Side.LEFT, 37, 19, 5, 10),
            Shape("lash", SemanticTag.EYELASH, Side.LEFT, 34, 19, 12, 2),
            Shape("close", SemanticTag.EYE_CLOSE, Side.LEFT, 34, 25, 12, 2),
            Shape("white", SemanticTag.EYEWHITE, Side.LEFT, 34, 20, 12, 8),
            Shape("brow", SemanticTag.EYEBROW, Side.LEFT, 34, 14, 12, 2),
            Shape("mouth", SemanticTag.MOUTH, Side.NONE, 25, 35, 14, if (thinMouth) 1 else 6),
            if (sideLock) Shape("front", SemanticTag.FRONT_HAIR, Side.LEFT, 39, 9, 12, 32, PartRole.SIDE)
            else Shape("front", SemanticTag.FRONT_HAIR, Side.NONE, 14, 4, 36, 13),
            Shape("face", SemanticTag.FACE, Side.NONE, 15, 8, 34, 40),
            Shape("back", SemanticTag.BACK_HAIR, Side.NONE, 12, 5, 40, 50),
            Shape("top", SemanticTag.TOPWEAR, Side.NONE, 12, 46, 40, 32),
        )
        val rightTags = buildSet {
            if (pairedBrows || bilateralEyes) addAll(listOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH))
            if (pairedBrows) add(SemanticTag.EYEBROW)
            if (bilateralEyes) addAll(listOf(SemanticTag.EYE_CLOSE, SemanticTag.IRIDES))
        }
        val rightFeatures = shapes.filter { it.tag in rightTags }
            .map { it.copy(id = "${it.id}-right", side = Side.RIGHT, x = 64 - it.x - it.w) }
        val closedDrawing = if (closedMouth) listOf(Shape("mouth-closed", SemanticTag.MOUTH_CLOSE, Side.NONE, 25, 38, 14, 2)) else emptyList()
        val allShapes = shapes + rightFeatures + closedDrawing + (if (eyeDetails) listOf(
            Shape("lid", SemanticTag.EYELASH, Side.LEFT, 34, 18, 12, 2, PartRole.LID),
            Shape("lid-shadow", SemanticTag.EYELASH, Side.LEFT, 34, 20, 12, 2, PartRole.SHADOW),
            Shape("highlight", SemanticTag.IRIDES, Side.LEFT, 39, 20, 2, 3, PartRole.HIGHLIGHT),
        ) else emptyList()) + (if (mouthDetails) listOf(
            Shape("teeth-top", SemanticTag.TOOTH_T, Side.NONE, 27, 35, 10, 2),
            Shape("teeth-bottom", SemanticTag.TOOTH_B, Side.NONE, 28, 39, 8, 2),
            Shape("tongue", SemanticTag.TONGUE, Side.NONE, 29, 38, 6, 3),
            Shape("nose", SemanticTag.NOSE, Side.NONE, 29, 28, 5, 3),
        ) else emptyList()) + (if (expressionDetails) listOf(
            if (denseBlush) Shape("blush", SemanticTag.FACE_DETAIL, Side.LEFT, 12, 10, 48, 40, PartRole.BLUSH)
            else Shape("blush", SemanticTag.FACE_DETAIL, Side.LEFT, 18, 31, 8, 3, PartRole.BLUSH),
            Shape("skin-highlight", SemanticTag.FACE_DETAIL, Side.NONE, 24, 11, 7, 2, PartRole.HIGHLIGHT),
            Shape("ear", SemanticTag.EARS, Side.LEFT, 9, 21, 6, 13),
        ) else emptyList())
        val skinShapes = if (skinDetails) listOf(
            Shape("neck", SemanticTag.NECK, Side.NONE, 25, 41, 14, 14),
            Shape("neck-shadow", SemanticTag.NECK, Side.NONE, 25, 42, 14, 5, PartRole.SHADOW),
            Shape("hair-shadow", SemanticTag.FRONT_HAIR, Side.NONE, 20, 10, 24, 5, PartRole.SHADOW),
        ) else emptyList()
        val outfitShapes = if (outfitDetails) listOf(
            Shape("skirt", SemanticTag.BOTTOMWEAR, Side.NONE, 13, 62, 38, 17, PartRole.SKIRT),
            Shape("ribbon", SemanticTag.NECKWEAR, Side.NONE, 23, 48, 5, 17, PartRole.RIBBON),
            Shape("pendant", SemanticTag.NECKWEAR, Side.NONE, 33, 48, 4, 9, PartRole.PENDANT),
            Shape("arm", SemanticTag.HANDWEAR, Side.LEFT, 5, 47, 6, 26),
            Shape("animal-ear", SemanticTag.EARS, Side.LEFT, 17, 1, 8, 12, PartRole.ANIMAL),
            Shape("tail", SemanticTag.TAIL, Side.NONE, 53, 51, 9, 24),
            Shape("wing", SemanticTag.WINGS, Side.RIGHT, 48, 45, 13, 15),
        ) else emptyList()
        val ribbonShapes = if (ribbonDetails) listOf(
            Shape("head-ribbon", SemanticTag.HEADWEAR, Side.NONE, 18, 3, 5, 16, PartRole.RIBBON),
            Shape("ear-pendant", SemanticTag.EARWEAR, Side.LEFT, 11, 27, 3, 10, PartRole.PENDANT),
            Shape("matched-left", SemanticTag.TOPWEAR, Side.LEFT, 18, 50, 3, 8, PartRole.RIBBON),
            Shape("matched-right", SemanticTag.TOPWEAR, Side.RIGHT, 43, 50, 3, 8, PartRole.RIBBON),
            Shape("hair-ribbon", SemanticTag.BACK_HAIR, Side.NONE, 38, 24, 5, 20, PartRole.RIBBON),
        ) else emptyList()
        val creatureShapes = if (creatureDetails) listOf(
            Shape("head-animal-ear", SemanticTag.HEADWEAR, Side.NONE, 35, 1, 8, 12, PartRole.ANIMAL),
            Shape("wing-left", SemanticTag.WINGS, Side.LEFT, 3, 45, 13, 15),
        ) else emptyList()
        val chestShapes = if (chestDetail) listOf(Shape("chest-panel", SemanticTag.TOPWEAR, Side.NONE, 26, 48, 12, 15)) else emptyList()
        val armShapes = if (pairedArms) listOf(
            Shape("glove-l", SemanticTag.HANDWEAR, Side.LEFT, 4, 51, 6, 26),
            Shape("glove-r", SemanticTag.HANDWEAR, Side.RIGHT, 54, 51, 6, 26),
        ) else emptyList()
        val classified = if (bangOverlay) listOf(allShapes.single { it.id == "front" }.copy(h = 24)) +
            allShapes.filterNot { it.id == "front" } + skinShapes + outfitShapes
        else allShapes + skinShapes + outfitShapes + chestShapes + armShapes + ribbonShapes + creatureShapes
        val source = WorkspaceSourceArt(64, 80, classified.mapIndexed { index, shape ->
            val color = if (!bangOverlay) 0xffffff else when (shape.tag) {
                SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR -> 0x725672
                SemanticTag.EYEBROW, SemanticTag.EYELASH, SemanticTag.EYE_CLOSE -> 0x2d2439
                SemanticTag.IRIDES -> 0x337d91
                SemanticTag.FACE -> 0xf0cba8
                else -> 0xffffff
            }
            val channels = byteArrayOf((color shr 16).toByte(), (color shr 8).toByte(), color.toByte(), 255.toByte())
            WorkspaceSourceLayer(
                LayerId(shape.id), shape.id, "", SourceLayerKind.Raster, true, index, LayerBounds(shape.x, shape.y, shape.w, shape.h),
                1f, shape.id == "iris-right", LayerBlend.Normal, ChannelMask.ALL, LayerRaster(shape.w, shape.h, ByteArray(shape.w * shape.h * 4) { channels[it % 4] }),
                null, null, false)
        }, emptyList())
        val config = RigModifierPipeline.ordinaryConfig(PipelineConfig(atlasSize = 128, meshSpacing = 6, recipe = RigRecipe.Legacy,
            generatePhysics = false, exportMoc3 = false)).copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        return WorkspaceDocument(source, emptyMap(), emptySet(), classified.associate { it.id to LayerClassificationOverride(LayerType.PRESET, it.tag, it.side, role = it.role) },
            emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
    }

    private fun singleEyeOpenMouthArt(removed: Set<String>, mirror: Boolean = false,
                                      expressions: Boolean = false, outfits: Boolean = false): WorkspaceDocument {
        var original = document(eyeDetails = true, mouthDetails = true, expressionDetails = expressions, outfitDetails = outfits)
        if (mirror) {
            val art = original.source as WorkspaceSourceArt
            // This procedural fixture uses uniform raster colors, so reflecting bounds also reflects its pixels.
            original = original.copy(source = art.copy(layers = art.layers.map { layer ->
                (layer as WorkspaceSourceLayer).copy(bounds = layer.bounds.copy(left = art.widthPx - layer.bounds.left - layer.bounds.width))
            }), layerOverrides = original.layerOverrides.mapValues { (_, value) ->
                value.copy(side = when (value.side) { Side.LEFT -> Side.RIGHT; Side.RIGHT -> Side.LEFT; else -> value.side })
            })
        }
        val artwork = object : SourceArt by original.source {
            override val layers = original.source.layers.filterNot { it.id.raw in removed }
        }
        return original.copy(source = artwork, layerOverrides = original.layerOverrides - removed)
    }

    private val versions = linkedMapOf("mouth.spring" to 10, "expression.cheek" to 5, "expression.gloom" to 5)

    private data class Case(val document: WorkspaceDocument, val historical: RigModifierStack,
                            val selected: RigModifierStack, val expected: RigPreviewModel, val actual: RigPreviewModel)

    private class Fixture(val initial: WorkspaceDocument) {
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { doc, previous -> builder.build(doc, previous) })
        val commands = WorkspaceDocumentCommands(runtime)
        val now get() = runtime.capture()
        suspend fun install() = runtime.install(runtime.state.value.state, "source", initial, builder.build(initial))
        suspend fun apply(id: String) = now.let {
            commands.execute(it.projectId, it.state, "modifier_apply", listOf(WorkspaceDocumentOperation("modifier_apply",
                buildJsonObject { put("id", id) })), MutationAuthor.USER)
        }
    }

    private fun prepare(art: WorkspaceDocument): WorkspaceDocument {
        val choices = WorkspaceInitialPreset(clothing = false)
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = art.rigEdits.copy(modifierStack = RigModifierStack().toJson()))
        return WorkspacePresetInitialization.prepare(art.copy(settings = WorkspaceSettingsCodec.encode(config),
            rigEdits = config.rigEdits), WorkspaceRasterWork.Direct, choices).first
    }

    private fun assertDefaults(stack: RigModifierStack, removed: Set<String>) {
        val required = mapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3,
            "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 3, "mouth.outline" to 7,
            "mouth.vowel" to 6, "mouth.capture" to 5, "mouth.jaw" to 7, "mouth.spring" to 8,
            "expression.cheek" to 1, "expression.gloom" to 1)
        assertEquals(required, stack.instances.filter { it.type in required }.associate { it.type to it.version },
            "V5 is an explicit choice; real fresh-source defaults remain conservative")
        assertEquals("tongue" !in removed, stack.instances.any { it.type == "mouth.tongue" && it.version == 5 })
        assertEquals(!removed.containsAll(setOf("teeth-top", "teeth-bottom", "tongue")),
            stack.instances.any { it.type == "mouth.interior-depth" && it.version == 3 })
        for (owner in stack.instances) assertEquals(BuiltinModifierPlugins.reference(owner.type, owner.version), owner.plugin)
    }

    private fun select(stack: RigModifierStack, selected: Map<String, Int> = versions) = stack.copy(instances = stack.instances.map { old ->
        selected[old.type]?.let { version -> old.copy(version = version,
            plugin = assertNotNull(BundledAlgorithmArtifacts.find(old.type, version)).reference) } ?: old
    }).also { changed ->
        assertEquals(stack.instances.map { it.id }, changed.instances.map { it.id })
        for ((old, next) in stack.instances.zip(changed.instances))
            assertEquals(old, next.copy(version = old.version, plugin = old.plugin),
                "Selecting versions preserves provider identity, ordered targets, configuration and output aliases")
    }

    private fun withStack(document: WorkspaceDocument, stack: RigModifierStack) =
        document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))

    private fun definition(model: RigPreviewModel, type: String): RigModifierDefinition = when (type) {
        "mouth.spring" -> RigMouthSpringV10Modifier.definition(RigModifierSource.of(model))
        "expression.cheek" -> RigCheekV5Modifier.definition(RigModifierSource.of(model))
        "expression.gloom" -> RigGloomV5Modifier.definition(RigModifierSource.of(model))
        else -> error("Unexpected composable stage $type")
    }

    private fun compare(expected: RigPreviewModel, actual: RigPreviewModel, stack: RigModifierStack, label: String) {
        val old = assertNotNull(expected.modifierEvaluation)
        val next = assertNotNull(actual.modifierEvaluation)
        assertEquals(stack, RigModifierStack.fromJson(actual.config.rigEdits.modifierStack!!))
        assertEquals(stack.instances.size, next.entries.size)
        assertTrue(next.entries.all { it.status == ModifierStatus.ACTIVE }, "$label must evaluate every real stage")
        assertEquals(old.outputs.size, next.outputs.size)
        for (index in old.outputs.indices) assertEquals(old.outputs[index], next.outputs[index],
            "$label: exact stage ${stack.instances[index].type}")
        assertEquals(expected.rig.modifierRasterAssets, actual.rig.modifierRasterAssets, "$label raster assets")
        assertEquals(expected.rig.modifierOutput, actual.rig.modifierOutput, "$label final authored IR")
        assertEquals(RigIrCompiler.compile(expected, tileArt = true), RigIrCompiler.compile(actual, tileArt = true),
            "$label textured IR")
        for ((type, version) in versions) if (stack.instances.single { it.type == type }.version == version) {
            val owner = stack.instances.single { it.type == type }
            val context = assertNotNull(next.readAlgorithms[owner.id]).context
            assertEquals("$type/$version", context.values.getValue("schema").jsonPrimitive.content)
            assertTrue(context.resources.isNotEmpty())
            assertTrue(context.resources.all { (name, resource) -> name == resource.digest })
            for (provider in context.upstream) {
                val source = stack.instances.single { it.id == provider.instanceId }
                assertEquals(ModifierUpstreamCapability(provider.capability, source.type, source.version, source.targets,
                    source.id, source.config, source.outputAliases, source.plugin), provider)
            }
        }
    }

    private suspend fun case(removed: Set<String> = emptySet(), mirror: Boolean = false,
                     cheekStrength: Float = 100f, gloomStrength: Float = 100f,
                     tuned: Boolean = false, expressions: Boolean = true, mixed: Boolean = false): Case {
        val initialized = prepare(singleEyeOpenMouthArt(removed, mirror, expressions))
        val defaults = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        assertDefaults(defaults, removed)
        assertEquals(9, defaults.instances.single { it.type == "mouth.cheek-lid" }.version,
            "Positive cohorts must be admitted by real source initialization before selecting new skin versions")
        val historical = defaults.copy(instances = defaults.instances.map { entry ->
            val values = when (entry.type) {
                "expression.cheek" -> mapOf("strength" to cheekStrength)
                "expression.gloom" -> mapOf("strength" to gloomStrength)
                "mouth.jaw" -> if (tuned) mapOf("share" to 22f) else emptyMap()
                "face/fitted" -> if (tuned) mapOf("turn" to 63f, "look" to 120f, "depth" to 135f) else emptyMap()
                "mouth/fitted" -> if (tuned) mapOf("early" to 45f, "form" to 125f, "reach" to 140f) else emptyMap()
                else -> emptyMap()
            }
            if (values.isEmpty()) entry else entry.copy(config = JsonObject(entry.config + ("values" to
                JsonObject(entry.config["values"]?.jsonObject.orEmpty() + values.mapValues { JsonPrimitive(it.value) }))))
        })
        val expected = WorkspacePreviewBuilder().build(withStack(initialized, historical))
        var previous = expected
        val steps = if (mixed) (1..versions.size).map { versions.entries.take(it).associate { entry -> entry.toPair() } }
            else listOf(versions)
        var selected = historical
        for (step in steps) {
            selected = select(historical, step)
            val actual = WorkspacePreviewBuilder().build(withStack(initialized, selected))
            compare(expected, actual, selected, "Historical spring8/cheek1/gloom1, $step, mirror=$mirror, removed=$removed")
            compare(previous, actual, selected, "Real preceding mixed source pipeline $step")
            previous = actual
        }
        return Case(withStack(initialized, selected), historical, selected, expected, previous)
    }

    @Test fun composableSkinV5MatchesRealDefaultAndMixedStages(): Unit = runBlocking {
        val case = case(mixed = true)
        val evaluation = assertNotNull(case.actual.modifierEvaluation)
        val cheek = case.selected.instances.single { it.type == "expression.cheek" }
        val gloom = case.selected.instances.single { it.type == "expression.gloom" }
        assertEquals(10, CheekV5Algorithm.requiredVersions.getValue("mouth.spring"))
        assertEquals(10, GloomV5Algorithm.requiredVersions.getValue("mouth.spring"))
        assertEquals(5, GloomV5Algorithm.requiredVersions.getValue("expression.cheek"))
        for (entry in listOf(cheek, gloom)) {
            val index = case.selected.index(entry.id)
            assertNotEquals(evaluation.outputs[index - 1], evaluation.outputs[index], "${entry.type} must perform real work")
            assertTrue(definition(case.actual, entry.type).explicitVersion)
        }
    }

    @Test fun composableSkinV5MatchesBothSidesAndAllMouthInteriors(): Unit = runBlocking {
        for (mirror in listOf(false, true)) for (removed in listOf(emptySet(), setOf("tongue"),
            setOf("teeth-top", "teeth-bottom", "tongue"))) case(removed, mirror)
    }

    @Test fun composableSkinV5PreservesIndependentStrengthsAndTunedProviders(): Unit = runBlocking {
        for ((cheek, gloom) in listOf(0f to 0f, 80f to 140f, 100f to 100f, 200f to 200f, 0f to 200f, 200f to 0f))
            case(cheekStrength = cheek, gloomStrength = gloom)
        case(tuned = true, cheekStrength = 80f, gloomStrength = 140f)
    }

    @Test fun composableSkinV5AllowsAbsentExpressionDetails(): Unit = runBlocking {
        // Admission is about actual source-head measurements, not an "optional artwork" label.
        for (mirror in listOf(false, true)) case(emptySet(), mirror, expressions = false)
    }

    @Test fun composableSkinV5PinsRealSourceOmissionAdmissions() {
        data class Profile(val name: String, val removed: Set<String>, val expressions: Boolean,
                           val leftCoupling: Int, val rightCoupling: Int)
        // Observed real initialization, including its asymmetric highlight-only source boundary.
        val profiles = listOf(
            Profile("no expression details", emptySet(), false, 9, 9),
            Profile("no eye highlight", setOf("highlight"), true, 9, 1),
            Profile("no lid details", setOf("lid", "lid-shadow"), true, 9, 9),
            Profile("no eye highlight or lids", setOf("highlight", "lid", "lid-shadow"), true, 9, 9),
            Profile("minimal artwork", setOf("highlight", "lid", "lid-shadow", "nose"), false, 1, 1),
        )
        val admissions = profiles.flatMap { profile -> listOf(false, true).map { mirror ->
            val initialized = prepare(singleEyeOpenMouthArt(profile.removed, mirror, profile.expressions))
            Triple(profile, mirror, RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!))
        } }
        val expected = admissions.map { (profile, mirror, _) ->
            "${profile.name}, side=${if (mirror) "R" else "L"}: mouth.cheek-lid@${if (mirror) profile.rightCoupling else profile.leftCoupling}"
        }
        val actual = admissions.map { (profile, mirror, stack) ->
            val coupling = stack.instances.single { it.type == "mouth.cheek-lid" }
            "${profile.name}, side=${if (mirror) "R" else "L"}: ${coupling.type}@${coupling.version}"
        }
        assertEquals(expected, actual, "Pin actual source-head admission without forcing source provider versions")
        for ((profile, _, stack) in admissions) assertDefaults(stack, profile.removed)
    }

    @Test fun composableSkinV5AllowsAdmittedEyeDetailOmissions(): Unit = runBlocking {
        case(setOf("highlight"), mirror = false)
        for (mirror in listOf(false, true)) {
            case(setOf("lid", "lid-shadow"), mirror)
            case(setOf("highlight", "lid", "lid-shadow"), mirror)
        }
    }

    @Test fun composableSkinV5RejectsMinimalArtworkWithHistoricalCheekLid(): Unit = runBlocking {
        for (mirror in listOf(false, true)) assertHistoricalCheekLidFallbackRejected(
            setOf("highlight", "lid", "lid-shadow", "nose"), mirror, expressions = false, label = "Minimal artwork")
    }

    @Test fun composableSkinV5RejectsMissingRightEyeHighlightWithHistoricalCheekLid(): Unit = runBlocking {
        // The measured right-eye cohort selects cheek/lid1; its left-eye counterpart selects9.
        // Preserve this actual source-head boundary rather than forcing the same version on both.
        assertHistoricalCheekLidFallbackRejected(setOf("highlight"), mirror = true,
            expressions = true, label = "No right eye highlight")
    }

    private suspend fun assertHistoricalCheekLidFallbackRejected(removed: Set<String>, mirror: Boolean,
                                                                 expressions: Boolean, label: String) {
        val initialized = prepare(singleEyeOpenMouthArt(removed, mirror, expressions))
        val defaults = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        assertDefaults(defaults, removed)
        assertEquals(1, defaults.instances.single { it.type == "mouth.cheek-lid" }.version,
            "$label: keep the actual source-head fallback rather than stamping cheek/lid9 on old output")
        if ("highlight" in removed) assertTrue(defaults.instances.none { it.type == "eye.highlight" })
        if (removed.containsAll(setOf("lid", "lid-shadow"))) assertTrue(defaults.instances.none { it.type == "eye.lid-fade" })
        val historical = WorkspacePreviewBuilder().build(initialized)
        val cache = RigStageCache.enabled
        val cold = try { RigStageCache.enabled = false; WorkspacePreviewBuilder().build(initialized) }
            finally { RigStageCache.enabled = cache }
        compare(historical, cold, defaults, "$label remains valid on its actual historical default prefix")
        val selected = select(defaults)
        assertEquals(1, selected.instances.single { it.type == "mouth.cheek-lid" }.version)
        val failure = assertFailsWith<IllegalArgumentException> {
            WorkspacePreviewBuilder().build(withStack(initialized, selected))
        }
        assertEquals("Mouth spring v10 requires compatible mouth.cheek-lid source providers", failure.message)
    }

    @Test fun composableSkinV5InstalledJvmPackagesMatchExactStages(): Unit = runBlocking {
        val case = case(cheekStrength = 80f, gloomStrength = 140f)
        verifyInstalledWorkers(case)
    }

    private fun verifyInstalledWorkers(case: Case) {
        val root = Files.createTempDirectory("composable-skin-v5-worker-")
        val preferences = Preferences.userRoot().node("composable-skin-v5-worker/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            val entries = case.selected.instances.filter { it.type in versions }
            val artifacts = entries.map { assertNotNull(BundledAlgorithmArtifacts.find(it.type, it.version)) }
            BundledAlgorithmArtifacts.retain(store, artifacts)
            val evaluation = assertNotNull(case.actual.modifierEvaluation)
            for ((entry, artifact) in entries.zip(artifacts)) {
                val installed = store.snapshot().single { it.reference == artifact.reference }
                val algorithm = installed.manifest.algorithms.single()
                val definition = definition(case.actual, entry.type)
                assertEquals(entry.plugin, installed.reference)
                assertEquals(definition.requires, algorithm.requires)
                assertEquals(definition.changeWrites, algorithm.writes)
                val index = case.selected.index(entry.id)
                val context = assertNotNull(evaluation.readAlgorithms[entry.id]).context
                assertEquals(evaluation.outputs[index], ModifierPluginWorkers.evaluate(installed, algorithm,
                    evaluation.outputs[index - 1], entry, {}, store, context = context,
                    declaredParameters = definition.parameters(entry)), "${entry.type} exact installed JVM result")
                val classes = java.util.zip.ZipInputStream(assertNotNull(artifact.open("algorithm.jar"))).use { zip ->
                    buildSet { while (true) { val file = zip.nextEntry ?: break; add(file.name) } }
                }
                val ownClass = when (entry.type) {
                    "mouth.spring" -> "MouthSpringV10Algorithm.class"
                    "expression.cheek" -> "CheekV5Algorithm.class"
                    else -> "GloomV5Algorithm.class"
                }
                assertTrue(classes.any { it.endsWith("/$ownClass") })
                for (forbidden in listOf("RigBuilder.class", "SkinBinding.class", "CheekV4Algorithm.class", "GloomV4Algorithm.class"))
                    assertTrue(classes.none { it.endsWith("/$forbidden") }, "${entry.type} must not bundle $forbidden")
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode(); ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun composableSkinV5SurvivesActualPrefixApplyColdReplayAndArchive(): Unit = runBlocking {
        val case = case(cheekStrength = 80f, gloomStrength = 140f)
        val fixture = Fixture(case.document)
        fixture.install()
        val expected = RigIrCompiler.compile(case.actual, tileArt = true)
        val evaluation = assertNotNull(case.actual.modifierEvaluation)
        val registry = RigModifierRegistry(BuiltinModifierPlugins.definitions(RigModifierSource.of(case.actual)))
        val spring = case.selected.instances.single { it.type == "mouth.spring" }
        val cheek = case.selected.instances.single { it.type == "expression.cheek" }
        val cuts = listOf(case.selected.instances[case.selected.index(spring.id) - 1].id, spring.id, cheek.id)
        for (cut in cuts) {
            fixture.apply(cut)
            val stack = RigModifierStack.fromJson(fixture.now.document.rigEdits.modifierStack!!)
            val prefix = case.selected.instances.take(case.selected.index(cut) + 1)
            assertEquals(case.selected.instances.drop(prefix.size), stack.instances, "Keep exact live suffix after $cut")
            assertTrue(stack.instances.any { it.type == "expression.gloom" })
            for (owner in prefix) assertEquals(owner.capability(provides = registry.definition(owner).provides),
                stack.appliedCapabilities.single { it.id == owner.id }, "Full applied provider identity after $cut")
            RigModifierAlgorithms.evaluator().validateOrder(stack)
            val cache = RigStageCache.enabled
            val replay = try { RigStageCache.enabled = false; WorkspacePreviewBuilder().build(fixture.now.document) }
                finally { RigStageCache.enabled = cache }
            assertExactIr(expected, RigIrCompiler.compile(fixture.now.model, tileArt = true), "Apply $cut")
            assertExactIr(expected, RigIrCompiler.compile(replay, tileArt = true), "Cold replay $cut")
            assertEquals(case.actual.rig.modifierRasterAssets, replay.rig.modifierRasterAssets, "Raster assets after $cut")
            assertRawCoreAfterApply(assertNotNull(case.actual.rig.modifierOutput), assertNotNull(replay.rig.modifierOutput),
                "Final raw output after $cut")
            val replayed = assertNotNull(replay.modifierEvaluation)
            for ((index, owner) in stack.instances.withIndex()) {
                assertEquals(ModifierStatus.ACTIVE, replayed.entries[index].status)
                assertRawCoreAfterApply(evaluation.outputs[case.selected.index(owner.id)], replayed.outputs[index],
                    "Live suffix ${owner.type} after $cut")
                if (owner.type !in versions) continue
                val originalContext = assertNotNull(evaluation.readAlgorithms[owner.id]).context
                val restoredContext = assertNotNull(replayed.readAlgorithms[owner.id]).context
                assertEquals(originalContext.upstream, restoredContext.upstream)
                assertEquals(originalContext.values, restoredContext.values, "Applied source outputs are not recaptured as artwork")
                assertEquals(originalContext.resources.keys, restoredContext.resources.keys)
                for ((key, resource) in originalContext.resources)
                    assertContentEquals(resource.read(), restoredContext.resources.getValue(key).read(), key)
            }
            val analysis = assertNotNull(replay.modifierAuthorAnalysis)
            val sourceIds = analysis.source.layers.mapTo(hashSetOf()) { it.id.raw }
            assertTrue(analysis.layers.any { it.source.id.raw !in sourceIds }, "Exercise actual restored outline rasters")
            val root = Files.createTempDirectory("composable-skin-v5-archive-")
            try {
                val history = WorkspaceStore(root.resolve("project"))
                history.persistHistory(fixture.now.projectId, fixture.runtime.history())
                val repository = ProjectRepository(writeHeadCache = false)
                val archive = root.resolve("skin.psd2live")
                repository.save(ProjectSaveCapture(fixture.now.projectId, fixture.runtime.history(),
                    JsonObject(emptyMap()), null, history), archive)
                repository.open(archive).use { opened ->
                    val restored = opened.history.head().snapshot
                    assertEquals(stack, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!))
                    val openedModel = try { RigStageCache.enabled = false; WorkspacePreviewBuilder().build(restored) }
                        finally { RigStageCache.enabled = cache }
                    assertExactIr(expected, RigIrCompiler.compile(openedModel, tileArt = true), "Archive after $cut")
                }
            } finally { ModifierPluginStore.deleteTree(root) }
        }
    }

    private fun assertRawCoreAfterApply(original: RigIR, replayed: RigIR, label: String) {
        // Applying a prefix materializes compiler-derived role/expression metadata in the raw
        // evaluator input. Full compiled textured IR remains exact above. Normalize only these
        // two known annotations, never geometry, textures, motion or other advanced fields.
        val normalized = original.copy(parameterRoles = replayed.parameterRoles,
            advanced = original.advanced.copy(expressions = replayed.advanced.expressions))
        assertExactIr(normalized, replayed, "$label: unexpected raw changes beyond materialized roles/expressions")
    }

    private fun assertExactIr(expected: RigIR, actual: RigIR, label: String) {
        val changed = RigIR::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { field ->
                field.isAccessible = true
                field.get(expected) != field.get(actual)
            }.map { it.name }
        // Every field uses exact equality, but failures report field names instead of megabytes of IR.
        assertEquals(emptyList(), changed, label)
    }

    @Test fun composableSkinV5RejectsChangedProviderIdentityAndSourceInputs(): Unit = runBlocking {
        val case = case(cheekStrength = 80f, gloomStrength = 140f)
        val evaluation = assertNotNull(case.actual.modifierEvaluation)
        for (entry in case.selected.instances.filter { it.type in setOf("expression.cheek", "expression.gloom") }) {
            val definition = definition(case.actual, entry.type)
            val algorithm = definition.plugin as ReadingModifierAlgorithmPlugin
            val index = case.selected.index(entry.id)
            val before = evaluation.outputs[index - 1]
            val after = evaluation.outputs[index]
            val context = assertNotNull(evaluation.readAlgorithms[entry.id]).context
            assertEquals(after, algorithm.evaluate(before, entry, context, {}), "Positive control before identity guards")
            val canonical = context.upstream.map { it.copy(capability = it.type) }.distinct()
            val face = canonical.single { it.type == "face/fitted" }
            assertEquals(after, algorithm.evaluate(before, entry, context.withUpstream(canonical), {}))
            assertEquals(after, algorithm.evaluate(before, entry,
                context.withUpstream(canonical + face.copy(capability = "face/legacy")), {}), "Accept the real face capability alias")
            assertFailsWith<IllegalArgumentException> {
                algorithm.evaluate(before, entry, context.withUpstream(canonical + face.copy(capability = "unknown.capability")), {})
            }
            for (provider in canonical) {
                val plugin = assertNotNull(provider.plugin)
                val altered = listOf(
                    provider.copy(instanceId = "different-owner"),
                    provider.copy(targets = provider.targets.reversed()),
                    provider.copy(config = JsonObject(provider.config + ("unexpected" to JsonPrimitive(true)))),
                    provider.copy(outputAliases = provider.outputAliases + ("deformer:unexpected" to "different-output")),
                    provider.copy(plugin = null),
                    provider.copy(plugin = plugin.copy(id = "different.package")),
                    provider.copy(plugin = plugin.copy(version = plugin.version + ".changed")),
                    provider.copy(plugin = plugin.copy(digest = "0".repeat(64))),
                    provider.copy(plugin = plugin.copy(dependencyDigests = plugin.dependencyDigests + ("unexpected" to "1".repeat(64))))
                )
                for (replacement in altered) {
                    assertNotEquals(provider, replacement, "Actually alter ${provider.type}")
                    assertFailsWith<IllegalArgumentException>("Reject full ${provider.type} identity edit") {
                        algorithm.evaluate(before, entry, context.withUpstream(canonical.map { if (it == provider) replacement else it }), {})
                    }
                }
                assertFailsWith<IllegalArgumentException>("Reject missing ${provider.type}") {
                    algorithm.evaluate(before, entry, context.withUpstream(canonical - provider), {})
                }
                assertFailsWith<IllegalArgumentException>("Reject duplicate ${provider.type}") {
                    algorithm.evaluate(before, entry, context.withUpstream(canonical + provider.copy(instanceId = "duplicate-owner")), {})
                }
            }
            val spring = canonical.single { it.type == "mouth.spring" }
            val wrongJaw = spring.copy(config = JsonObject(spring.config + ("dependencies" to JsonObject(
                spring.config.getValue("dependencies").jsonObject + ("mouth.jaw" to JsonPrimitive("different-jaw"))))))
            val wrongProviders = canonical.map { if (it == spring) wrongJaw else it }
            // Mutate both envelope copies: same-source validation, not only snapshot inequality, must reject this.
            val wrongContext = ModifierAlgorithmContext(JsonObject(context.values + ("providers" to JsonArray(wrongProviders.map {
                Json.encodeToJsonElement(ModifierUpstreamCapability.serializer(), it)
            }))), context.resources, wrongProviders)
            assertFailsWith<IllegalArgumentException> { algorithm.evaluate(before, entry, wrongContext, {}) }
            val observed = ModifierEvaluationInput(before, context)
            algorithm.changes(observed, entry, {})
            val reads = observed.reads()
            assertTrue(reads.matches(before, context))
            val faceMesh = before.meshes.single { it.name == "face" }
            val geometry = assertNotNull(faceMesh.geometry)
            val changed = before.copy(meshes = before.meshes.map { if (it.id != faceMesh.id) it else it.copy(
                geometry = geometry.copy(positions = Floats.wrap(geometry.positions.toArray().also { positions -> positions[0] += .01f }))) })
            assertFalse(reads.matches(changed, context), "Edited live source geometry invalidates reads")
            assertFailsWith<IllegalArgumentException> { algorithm.evaluate(changed, entry, context, {}) }
            for ((key, resource) in context.resources) {
                val replacement = ModifierAlgorithmResource(resource.read().copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                val altered = ModifierAlgorithmContext(context.values, context.resources + (key to replacement), context.upstream)
                assertFalse(reads.matches(before, altered), "Edited raw source measurement invalidates reads")
                assertFailsWith<IllegalArgumentException> { algorithm.evaluate(before, entry, altered, {}) }
            }
            val changedStrength = entry.copy(config = JsonObject(entry.config + ("values" to buildJsonObject { put("strength", 37f) })))
            val recaptured = definition.authorContext(changedStrength)
            assertEquals(context.values, recaptured.values, "Own strength is computed in the worker, not baked into source measurements")
            assertEquals(context.resources.keys, recaptured.resources.keys)
            for ((key, resource) in context.resources) assertContentEquals(resource.read(), recaptured.resources.getValue(key).read())
            assertNotEquals(after, algorithm.evaluate(before, changedStrength, context, {}))
        }
    }

    @Test fun composableSkinV5SuspendsMissingDisabledDuplicateOrWrongPackageBeforeCapture(): Unit = runBlocking {
        val case = case()
        val evaluation = assertNotNull(case.actual.modifierEvaluation)
        val originalRegistry = RigModifierRegistry(BuiltinModifierPlugins.definitions(RigModifierSource.of(case.actual)))
        for (entry in case.selected.instances.filter { it.type in setOf("expression.cheek", "expression.gloom") }) {
            val index = case.selected.index(entry.id)
            val before = evaluation.outputs[index - 1]
            val prefix = case.selected.instances.take(index)
            val applied = prefix.map { it.capability(provides = originalRegistry.definition(it).provides) }
            val ids = prefix.mapTo(hashSetOf()) { it.id }
            var captures = 0
            val sentinel = "Positive capture ${entry.type}"
            fun evaluate(stack: RigModifierStack): RigModifierResult {
                val source = case.actual.copy(config = case.actual.config.copy(rigEdits = case.actual.config.rigEdits.copy(modifierStack = stack.toJson())))
                val definitions = BuiltinModifierPlugins.definitions(RigModifierSource.of(source)).map { definition ->
                    if (definition.type != entry.type || definition.version != entry.version) definition else definition.copy(
                        authorContext = { captures++; error(sentinel) })
                }
                return RigModifierEvaluator(RigModifierRegistry(definitions)).evaluate(before, stack)
            }
            val positive = RigModifierStack(instances = listOf(entry), retiredIds = ids, appliedCapabilities = applied)
            assertEquals(sentinel, assertFailsWith<IllegalStateException> { evaluate(positive) }.message)
            assertEquals(1, captures)
            captures = 0
            for (required in definition(case.actual, entry.type).requires) {
                val owner = prefix.single { it.type == required }
                val capability = applied.single { it.id == owner.id }
                val plugin = assertNotNull(capability.plugin)
                val absent = positive.copy(appliedCapabilities = applied - capability)
                val disabled = absent.copy(instances = listOf(owner.copy(enabled = false), entry), retiredIds = ids - owner.id)
                val duplicate = positive.copy(appliedCapabilities = applied + capability.copy(id = "duplicate-${owner.id}"),
                    retiredIds = ids + "duplicate-${owner.id}")
                val invalidPackages = listOf(plugin.copy(id = "wrong.package"), plugin.copy(digest = "0".repeat(64)),
                    plugin.copy(dependencyDigests = plugin.dependencyDigests + ("unexpected" to "1".repeat(64))))
                    .map { wrong -> positive.copy(appliedCapabilities = applied.map { if (it == capability) it.copy(plugin = wrong) else it }) }
                for (stack in listOf(absent, disabled, duplicate) + invalidPackages) {
                    val result = evaluate(stack)
                    val consumer = result.entries.single { it.instance.id == entry.id }
                    assertEquals(ModifierStatus.MISSING_DEPENDENCY, consumer.status, "$required must suspend ${entry.type}")
                    assertTrue(required in consumer.missingDependencies)
                    assertEquals(before, result.rig)
                    assertEquals(0, captures, "$required must fail before source capture")
                }
            }
        }
    }

    @Test fun composableSkinV5ComposesRealDenseBlushCarrierAndInstalledWorkers(): Unit = runBlocking {
        val original = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, denseBlush = true)
        val sourceArt = original.source as WorkspaceSourceArt
        // Keep the blush inside the actual face and the complete mouth low enough that
        // its small outline footprint change cannot move the clamped source head center.
        val art = original.copy(source = sourceArt.copy(layers = sourceArt.layers.map { layer ->
            val source = layer as WorkspaceSourceLayer
            when (layer.name) {
                "blush" -> source.copy(bounds = LayerBounds(16, 14, 32, 28),
                    raster = LayerRaster(32, 28, ByteArray(32 * 28 * 4) { 255.toByte() }))
                "mouth", "teeth-top", "teeth-bottom", "tongue" ->
                    source.copy(bounds = source.bounds.copy(top = source.bounds.top + 6))
                else -> source
            }
        }))
        val choices = WorkspaceInitialPreset(clothing = false)
        val ordinary = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
        // Power-of-two scaling preserves source landmark arithmetic. Scale ordinary mesh spacing
        // too, rather than accidentally making the entire eye/head fixture a dense-mesh stress test.
        val scale = 16
        val config = ordinary.copy(atlasSize = 2048, meshOuterMargin = ordinary.meshOuterMargin * scale,
            meshEdgeWidth = ordinary.meshEdgeWidth * scale, meshMaxEdgeDistance = ordinary.meshMaxEdgeDistance * scale,
            meshInteriorDensity = ordinary.meshInteriorDensity * scale,
            rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val scaled = io.github.psd2live.tools.upscaled(art.source, scale)
        val stippled = object : SourceArt by scaled {
            override val layers = scaled.layers.map { layer ->
                if (layer.name != "blush") layer else object : SourceLayer by layer {
                    override val raster = layer.raster.let { original ->
                        val rgba = ByteArray(original.width * original.height * 4)
                        for (y in 0 until original.height) for (x in 0 until original.width) {
                            val dx = x % 40 - 20
                            val dy = y % 40 - 20
                            if (dx * dx + dy * dy <= 36) {
                                val i = (y * original.width + x) * 4
                                rgba[i] = 240.toByte(); rgba[i + 1] = 140.toByte()
                                rgba[i + 2] = 140.toByte(); rgba[i + 3] = 180.toByte()
                            }
                        }
                        LayerRaster(original.width, original.height, rgba)
                    }
                }
            }
        }
        val (initialized, reference) = WorkspacePresetInitialization.prepare(art.copy(source = stippled,
            settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits), WorkspaceRasterWork.Direct, choices)
        val historical = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        assertDefaults(historical, emptySet())
        val selected = select(historical)
        val actual = WorkspacePreviewBuilder().build(withStack(initialized, selected))
        compare(reference, actual, selected, "Real dense blush source")
        val evaluation = assertNotNull(actual.modifierEvaluation)
        val cheek = selected.instances.single { it.type == "expression.cheek" }
        val index = selected.index(cheek.id)
        val before = evaluation.outputs[index - 1]
        val after = evaluation.outputs[index]
        val blush = before.meshes.single { it.name == "blush" }
        val moved = after.meshes.single { it.id == blush.id }
        assertNotEquals(blush.parent, moved.parent, "Dense blush must allocate a real Cheek5 carrier")
        assertTrue(after.deformers.any { it.id == moved.parent && before.deformers.none { prior -> prior.id == it.id } })
        val gloom = selected.instances.single { it.type == "expression.gloom" }
        assertEquals(ModifierStatus.ACTIVE, evaluation.entries[selected.index(gloom.id)].status,
            "Gloom5 must consume the actual new Cheek carrier")
        val case = Case(withStack(initialized, selected), historical, selected, reference, actual)
        val cache = RigStageCache.enabled
        val replay = try { RigStageCache.enabled = false; WorkspacePreviewBuilder().build(case.document) }
            finally { RigStageCache.enabled = cache }
        compare(reference, replay, selected, "Cold real dense blush source")
        verifyInstalledWorkers(case)
    }

    @Test fun composableSkinV5RejectsAnonymousIntersectingAppliedProviders(): Unit = runBlocking {
        val case = case()
        for (entry in case.selected.instances.filter { it.type in setOf("expression.cheek", "expression.gloom") }) {
            val expected = definition(case.actual, entry.type).authorContext(entry)
            fun capture(extra: ModifierCapability): ModifierAlgorithmContext {
                val stack = case.selected.copy(appliedCapabilities = case.selected.appliedCapabilities + extra)
                val changed = case.actual.copy(config = case.actual.config.copy(rigEdits = case.actual.config.rigEdits.copy(
                    modifierStack = stack.toJson())))
                return definition(changed, entry.type).authorContext(entry)
            }
            val unknown = ModifierCapability("unknown.source", 1, entry.targets, id = null)
            assertFailsWith<IllegalArgumentException>("Anonymous intersecting provider must not disappear from ${entry.type} validation") {
                capture(unknown)
            }
            val unrelated = capture(unknown.copy(targets = listOf(ModifierTarget("mesh", "unrelated-mesh"))))
            assertEquals(expected.values, unrelated.values, "Nonintersecting anonymous capability does not alter source capture")
            assertEquals(expected.resources.keys, unrelated.resources.keys)
            for ((key, resource) in expected.resources) assertContentEquals(resource.read(), unrelated.resources.getValue(key).read())
        }
    }

    @Test fun composableSkinV5DenseFaceStopsAtActualJaw7BeforeSourceCapture(): Unit = runBlocking {
        val art = singleEyeOpenMouthArt(emptySet(), expressions = true)
        val choices = WorkspaceInitialPreset(clothing = false)
        val ordinary = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val scale = 16
        val config = ordinary.copy(atlasSize = 2048,
            meshOuterMargin = ordinary.meshOuterMargin * scale, meshEdgeWidth = ordinary.meshEdgeWidth * scale,
            meshMaxEdgeDistance = ordinary.meshMaxEdgeDistance * scale, meshInteriorDensity = ordinary.meshInteriorDensity * scale)
        val source = io.github.psd2live.tools.upscaled(object : SourceArt by art.source {
            override val layers = art.source.layers.map { layer ->
                if (layer.name !in setOf("mouth", "teeth-top", "teeth-bottom", "tongue")) layer else object : SourceLayer by layer {
                    override val bounds = layer.bounds.copy(top = layer.bounds.top + 6)
                }
            }
        }, scale)
        val (prepared, _) = WorkspacePresetInitialization.prepare(art.copy(source = source,
            settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits), WorkspaceRasterWork.Direct, choices)
        // Densify a real normally initialized face; do not manufacture new provider identities.
        val initialized = prepared.copy(meshOverrides = prepared.meshOverrides + ("face" to MeshSettings(
            outerMargin = ordinary.meshOuterMargin * scale, edgeWidth = ordinary.meshEdgeWidth * scale,
            maxEdgeDistance = 6f, interiorDensity = 6f)))
        assertEquals(prepared.rigEdits.modifierStack, initialized.rigEdits.modifierStack)
        val defaults = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        assertDefaults(defaults, emptySet())
        val selected = select(defaults)
        val jaw = selected.instances.single { it.type == "mouth.jaw" }
        assertEquals(7, jaw.version)
        val index = selected.index(jaw.id)
        val prefix = selected.instances.take(index)
        val prior = WorkspacePreviewBuilder().build(withStack(initialized, selected.copy(instances = prefix)))
        val before = assertNotNull(prior.modifierEvaluation).outputs.last()
        val face = before.meshes.single { it.name == "face" }
        val coordinates = assertNotNull(face.geometry).positions.size
        val headCells = face.offsets?.axes.orEmpty().fold(1L) { count, axis -> count * axis.keys.size }
        assertTrue(coordinates * headCells * 2 > 120_000, "Cross the actual jaw-open × head-grid budget")
        val captured = prior.copy(config = prior.config.copy(rigEdits = prior.config.rigEdits.copy(modifierStack = selected.toJson())))
        var captures = 0
        val registry = RigModifierRegistry(BuiltinModifierPlugins.definitions(RigModifierSource.of(captured)).map { definition ->
            if (versions[definition.type] != definition.version) definition else definition.copy(authorContext = {
                captures++; error("Source capture must not follow unsupported Jaw7 lowering")
            })
        })
        val applied = prefix.map { it.capability(provides = registry.definition(it).provides) }
        val remaining = RigModifierStack(instances = selected.instances.drop(index),
            retiredIds = prefix.mapTo(hashSetOf()) { it.id }, appliedCapabilities = applied)
        val failure = assertFailsWith<IllegalArgumentException> { RigModifierEvaluator(registry).evaluate(before, remaining) }
        assertEquals("Use mouth jaw v1 when the lower-face grid needs a carrier: ${face.id}", failure.message)
        assertEquals(0, captures, "Real Jaw7 rejection precedes Spring10, Cheek5 and Gloom5 capture")
    }
}
