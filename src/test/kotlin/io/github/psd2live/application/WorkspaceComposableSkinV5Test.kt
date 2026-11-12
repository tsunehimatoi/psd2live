package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.CheekV5Algorithm
import io.github.psd2live.algorithms.binding.GloomV5Algorithm
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.format.compile.RigIrBinary
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
    private val historicalVersions = linkedMapOf("mouth.spring" to 8, "expression.cheek" to 1, "expression.gloom" to 1)

    private data class Case(val document: WorkspaceDocument, val historical: RigModifierStack,
                            val selected: RigModifierStack, val expected: RigPreviewModel, val actual: RigPreviewModel)

    private class Fixture(val initial: WorkspaceDocument) {
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { doc, previous -> builder.build(doc, previous) })
        val commands = WorkspaceDocumentCommands(runtime)
        val now get() = runtime.capture()
        suspend fun install() = runtime.install(runtime.state.value.state, "source", initial, builder.build(initial))
        suspend fun edit(operation: String, request: JsonObject) = now.let {
            commands.execute(it.projectId, it.state, operation, listOf(WorkspaceDocumentOperation(operation, request)), MutationAuthor.USER)
        }
        suspend fun apply(id: String) = edit("modifier_apply", buildJsonObject { put("id", id) })
    }

    private fun prepareFresh(art: WorkspaceDocument,
                             requested: Map<String, Int> = emptyMap(),
                             initialStack: RigModifierStack = RigModifierStack()): Pair<WorkspaceDocument, RigPreviewModel> {
        val choices = WorkspaceInitialPreset(clothing = false)
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = art.rigEdits.copy(modifierStack = initialStack.toJson()))
        return WorkspacePresetInitialization.prepare(art.copy(settings = WorkspaceSettingsCodec.encode(config),
            rigEdits = config.rigEdits), WorkspaceRasterWork.Direct, choices, requestedVersions = requested)
    }

    // Explicit-version tests keep their exact 8/1/1 provider contract. Rebuild that
    // historical chain from artwork; never put historical labels on promoted output.
    private fun prepare(art: WorkspaceDocument): WorkspaceDocument = prepareFresh(art).first.let { initialized ->
        withStack(initialized, select(RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!), historicalVersions))
    }

    private fun assertDefaults(stack: RigModifierStack, removed: Set<String>, trio: Map<String, Int> = historicalVersions) {
        val required = mapOf("rig.scaffold" to 2, "body/legacy" to 2, "face/fitted" to 3,
            "eye/fitted" to 5, "brow/fitted" to 4, "mouth/fitted" to 3, "mouth.outline" to 7,
            "mouth.vowel" to 6, "mouth.capture" to 5, "mouth.jaw" to 7) + trio
        assertEquals(required, stack.instances.filter { it.type in required }.associate { it.type to it.version },
            "Exact upstream and trio provider contract")
        assertEquals("tongue" !in removed, stack.instances.any { it.type == "mouth.tongue" && it.version == 5 })
        assertEquals(!removed.containsAll(setOf("teeth-top", "teeth-bottom", "tongue")),
            stack.instances.any { it.type == "mouth.interior-depth" && it.version == 3 })
        for (owner in stack.instances) assertEquals(BuiltinModifierPlugins.reference(owner.type, owner.version), owner.plugin)
    }

    private fun select(stack: RigModifierStack, selected: Map<String, Int> = versions) = stack.copy(instances = stack.instances.map { old ->
        selected[old.type]?.let { version -> old.copy(version = version,
            // Historical host-only v1 bindings have canonical compatibility references,
            // but no standalone algorithm artifact. Use their real existing identity.
            plugin = assertNotNull(BuiltinModifierPlugins.reference(old.type, version))) } ?: old
    }).also { changed ->
        assertEquals(stack.instances.map { it.id }, changed.instances.map { it.id })
        for ((old, next) in stack.instances.zip(changed.instances))
            assertEquals(old, next.copy(version = old.version, plugin = old.plugin),
                "Selecting versions preserves provider identity, ordered targets, configuration and output aliases")
    }

    private fun withStack(document: WorkspaceDocument, stack: RigModifierStack) =
        document.copy(rigEdits = document.rigEdits.copy(modifierStack = stack.toJson()))

    /** Independent eager baseline: ordinary registered definitions now use structural measurements. */
    private fun nativeCaptureDefinition(model: RigPreviewModel, type: String): RigModifierDefinition =
        nativeCaptureDefinition(RigModifierSource.of(model), type)

    private fun nativeCaptureDefinition(source: RigModifierSource, type: String): RigModifierDefinition = when (type) {
        "mouth.spring" -> RigMouthSpringV10Modifier.nativeCaptureDefinition(source)
        "expression.cheek" -> RigCheekV5Modifier.nativeCaptureDefinition(source)
        "expression.gloom" -> RigGloomV5Modifier.nativeCaptureDefinition(source)
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

    private suspend fun freshDefaultCase(removed: Set<String> = emptySet(), mirror: Boolean = false,
                                         artwork: WorkspaceDocument? = null): Case {
        val (initialized, actual) = prepareFresh(artwork ?: singleEyeOpenMouthArt(removed, mirror, expressions = true))
        val selected = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        assertDefaults(selected, removed, versions)
        assertEquals(9, selected.instances.single { it.type == "mouth.cheek-lid" }.version,
            "The default must follow real source admission, not a relabelled historical cheek/lid output")
        val historical = select(selected, historicalVersions)
        // Freeze the entire prepared stack, changing exactly the three version/package pairs,
        // then independently evaluate its real source pipeline from the original artwork.
        val expected = WorkspacePreviewBuilder().build(withStack(initialized, historical))
        assertEquals(historical, RigModifierStack.fromJson(expected.config.rigEdits.modifierStack!!))
        assertDefaults(historical, removed)
        assertEquals(versions.keys, selected.instances.zip(historical.instances)
            .filter { (a, b) -> a != b }.map { it.first.type }.toSet())
        compare(expected, actual, selected, "Automatic 10/5/5 versus fully locked historical 8/1/1")
        val evaluation = assertNotNull(actual.modifierEvaluation)
        for (type in versions.keys) {
            val entry = selected.instances.single { it.type == type }
            val index = selected.index(entry.id)
            assertNotEquals(evaluation.outputs[index - 1], evaluation.outputs[index], "$type performs real work")
            assertTrue(registeredMeasurementDefinition(RigModifierSource.of(actual), type).explicitVersion,
                "Host default admission must not change the global explicit-version contract")
        }
        val cache = RigStageCache.enabled
        val cold = try { RigStageCache.enabled = false; WorkspacePreviewBuilder().build(initialized) }
            finally { RigStageCache.enabled = cache }
        compare(expected, cold, selected, "Cold automatic source default, mirror=$mirror, removed=$removed")
        return Case(initialized, historical, selected, expected, actual)
    }

    @Test fun freshDefaultTrioMatchesBothSidesAndAllMouthInteriors(): Unit = runBlocking {
        for (mirror in listOf(false, true)) for (removed in listOf(emptySet(), setOf("tongue"),
            setOf("teeth-top", "teeth-bottom", "tongue"))) {
            val case = freshDefaultCase(removed, mirror)
            val oldRenderer = io.github.psd2live.tools.Renderer(case.expected, 128)
            val newRenderer = io.github.psd2live.tools.Renderer(case.actual, 128)
            val side = if (mirror) "R" else "L"
            val poses = listOf(emptyMap(), mapOf("ParamMouthOpenY" to .7f, "ParamMouthForm" to -.5f,
                "ParamEye${side}Open" to .3f, "ParamAngleX" to 12f, "ParamAngleY" to -10f,
                "ParamCheek" to .8f, "ParamGloom" to .6f), mapOf("ParamMouthOpenY" to 1f,
                "ParamMouthSpring" to -.7f, "ParamEye${side}Open" to 1f, "ParamCheek" to 1f,
                "ParamGloom" to 1f, "ParamAngleX" to -18f, "ParamAngleY" to 15f))
            val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
            for (pose in poses) {
                val values = pose.mapKeys { org.umamo.runtime.model.ParameterId(it.key) }
                val oldPose = evaluator.evaluate(case.expected.rig.puppet, values)
                val newPose = evaluator.evaluate(case.actual.rig.puppet, values)
                assertEquals(oldPose.worldPositions.keys, newPose.worldPositions.keys)
                for ((id, positions) in oldPose.worldPositions)
                    assertContentEquals(positions, newPose.worldPositions.getValue(id), "Exact pose geometry ${id.raw}: $pose")
                assertEquals(oldPose.opacity, newPose.opacity, "Exact pose opacity: $pose")
                val a = oldRenderer.render(pose)
                val b = newRenderer.render(pose)
                assertEquals(a.width, b.width); assertEquals(a.height, b.height)
                assertContentEquals(a.getRGB(0, 0, a.width, a.height, null, 0, a.width),
                    b.getRGB(0, 0, b.width, b.height, null, 0, b.width),
                    "Exact rendered pose $pose, mirror=$mirror, removed=$removed")
            }
        }
    }

    @Test fun freshDefaultTrioPinsActualOmissionAdmissions() {
        data class Profile(val removed: Set<String>, val expressions: Boolean, val left: Int, val right: Int)
        val profiles = listOf(Profile(emptySet(), false, 9, 9),
            Profile(setOf("highlight"), true, 9, 1), Profile(setOf("lid", "lid-shadow"), true, 9, 9),
            Profile(setOf("highlight", "lid", "lid-shadow"), true, 9, 9),
            Profile(setOf("highlight", "lid", "lid-shadow", "nose"), false, 1, 1))
        for (profile in profiles) for (mirror in listOf(false, true)) {
            val (initialized, model) = prepareFresh(singleEyeOpenMouthArt(profile.removed, mirror, profile.expressions))
            val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
            val coupling = if (mirror) profile.right else profile.left
            assertEquals(coupling, stack.instances.single { it.type == "mouth.cheek-lid" }.version)
            assertDefaults(stack, profile.removed, if (coupling == 9) versions else historicalVersions)
            assertTrue(assertNotNull(model.modifierEvaluation).entries.all { it.status == ModifierStatus.ACTIVE })
        }
    }

    @Test fun freshDefaultTrioKeepsExplicitRequestedLocks() {
        val art = singleEyeOpenMouthArt(emptySet(), expressions = true)
        for (type in listOf("face/fitted", "mouth.spring", "expression.cheek", "expression.gloom")) {
            val requested = mapOf(type to 1)
            val (initialized, model) = prepareFresh(art, requested)
            val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
            assertEquals(1, stack.instances.single { it.type == type }.version)
            assertEquals(historicalVersions.mapValues { 1 }, stack.instances.filter { it.type in versions }
                .associate { it.type to it.version }, "Requested locks retain their existing conservative path")
            assertTrue(assertNotNull(model.modifierEvaluation).entries.all { it.status == ModifierStatus.ACTIVE })
        }
    }

    @Test fun freshDefaultTrioKeepsUnsupportedAttachmentPredecessorsHistorical() {
        val cases = listOf("face.neck" to document(eyeDetails = true, mouthDetails = true, skinDetails = true),
            "body.arms" to document(eyeDetails = true, mouthDetails = true, pairedArms = true),
            "outfit.skirt" to document(eyeDetails = true, mouthDetails = true, outfitDetails = true),
            "outfit.ribbons" to document(eyeDetails = true, mouthDetails = true, ribbonDetails = true),
            "outfit.creature" to document(eyeDetails = true, mouthDetails = true, creatureDetails = true))
        for ((type, art) in cases) {
            val (initialized, model) = prepareFresh(art)
            val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
            val owner = stack.instances.single { it.type == type }
            val cheek = stack.instances.single { it.type == "expression.cheek" }
            assertTrue(stack.index(owner.id) < stack.index(cheek.id), "Exercise a real $type predecessor of the skin consumers")
            assertEquals(historicalVersions, stack.instances.filter { it.type in versions }.associate { it.type to it.version },
                "Unsupported $type must not partially promote the trio")
            assertTrue(assertNotNull(model.modifierEvaluation).entries.all { it.status == ModifierStatus.ACTIVE })
        }
    }

    @Test fun freshDefaultTrioSelectorRejectsNonfreshAndIncompatibleInputs(): Unit = runBlocking {
        val (initialized, _) = prepareFresh(singleEyeOpenMouthArt(emptySet(), expressions = true))
        val automatic = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        assertDefaults(automatic, emptySet(), versions)
        val historical = select(automatic, historicalVersions)
        val author = WorkspacePreviewBuilder().build(withStack(initialized, RigModifierStack()))
        val config = author.config
        fun selected(stack: RigModifierStack = historical, settings: PipelineConfig = config,
                     requested: Map<String, Int> = emptyMap(), pristine: Boolean = true,
                     automaticSource: Boolean = true, cli: CliPresetBindings? = null) =
            WorkspacePresetInitialization.sourceSingleEyeSkinDefault(author, settings, stack, requested, cli,
                pristineSourceStack = pristine, automaticSingleEyeOpenMouth = automaticSource)
        // The same real author model and full prefix must pass before any rejection assertion.
        assertEquals(automatic, selected(), "Positive control on the true source author model")
        val store = ModifierPluginStore.shared
        for ((type, version) in versions) {
            val reference = assertNotNull(BuiltinModifierPlugins.reference(type, version))
            val enabled = store.builtinEnabled(reference.id)
            try {
                store.setBuiltinEnabled(reference, false)
                assertFalse(ModifierPluginCatalog.available(type, version, reference))
                assertEquals(historical, selected(), "Unavailable $type@$version prevents all three promotions")
            } finally { store.setBuiltinEnabled(reference, enabled) }
        }
        assertEquals(automatic, selected(), "Restored package availability retains the positive control")
        assertEquals(historical, selected(pristine = false), "Existing or preset-switch stacks are not fresh initialization")
        assertEquals(historical, selected(automaticSource = false))
        assertEquals(historical, selected(requested = mapOf("expression.cheek" to 1)))
        val cli = CliPresetBindings(RigGenerationSource.Analyses(author.analysis, author.analysis), config)
        assertEquals(historical, selected(cli = cli), "CLI keeps its public IDs and historical package choices")
        val excludedConfigs = listOf(config.copy(generationSource = initialized.source),
            config.copy(meshSource = initialized.source), config.copy(meshOnly = true),
            config.copy(generateDeformers = false), config.copy(featureDisplacementEnabled = true),
            config.copy(headTurnStrength = 1.2f), config.copy(bodyStrength = 1.2f),
            config.copy(mouthThickness = config.mouthThickness + 1f), config.copy(mouthOutlineEnabled = false),
            config.copy(seeThroughOpacity = .6f), config.copy(hairSimulationFront = true),
            config.copy(physicsFrontHair = false), config.copy(recipe = RigRecipe.Legacy),
            config.copy(meshOverrides = mapOf("face" to MeshSettings())),
            config.copy(parentOverrides = mapOf("face" to "front")), config.copy(deferRig = true),
            config.copy(rigPendingLayerIds = setOf("face")),
            config.copy(rigEdits = config.rigEdits.copy(deletedParameterIds = setOf("authored-parameter"))))
        for ((index, settings) in excludedConfigs.withIndex()) {
            assertNotEquals(config, settings, "Exercise excluded configuration $index")
            assertEquals(historical, selected(settings = settings), "Excluded source configuration $index")
        }
        val owner = historical.instances.first()
        val metadata = listOf(historical.copy(retiredIds = setOf("retired-owner")),
            historical.copy(appliedCapabilities = listOf(owner.capability().copy(id = "applied-owner"))),
            historical.copy(overrides = mapOf(owner.id to emptyList())))
        for (stack in metadata) assertEquals(stack, selected(stack), "Existing/applied/retired metadata preserves exact locks")
        val coupling = historical.instances.single { it.type == "mouth.cheek-lid" }
        val reordered = historical.copy(instances = (historical.instances - coupling).toMutableList().apply {
            add(indexOfFirst { it.type == "mouth.spring" } + 1, coupling)
        })
        assertEquals(reordered, selected(reordered), "Real cheek/lid9 must precede Spring10, not merely exist later")
        val required = setOf("rig.scaffold", "body/legacy", "face/fitted", "eye/fitted", "brow/fitted",
            "mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture", "mouth.jaw", "mouth.cheek-lid", "eye.highlight") + versions.keys
        for (provider in historical.instances.filter { it.type in required }) {
            val plugin = assertNotNull(provider.plugin)
            val replacements = listOf(provider.copy(enabled = false),
                provider.copy(targets = provider.targets.drop(1)),
                provider.copy(config = JsonObject(provider.config + ("unexpected" to JsonPrimitive(true)))),
                provider.copy(outputAliases = mapOf("deformer:unexpected" to "different-output")),
                provider.copy(plugin = null), provider.copy(plugin = plugin.copy(id = "wrong.package")),
                provider.copy(plugin = plugin.copy(version = plugin.version + ".changed")),
                provider.copy(plugin = plugin.copy(digest = "0".repeat(64))),
                provider.copy(plugin = plugin.copy(dependencyDigests = plugin.dependencyDigests + ("unexpected" to "1".repeat(64)))))
            for (replacement in replacements) {
                assertNotEquals(provider, replacement)
                val stack = historical.copy(instances = historical.instances.map { if (it.id == provider.id) replacement else it })
                assertEquals(stack, selected(stack), "Reject changed ${provider.type} without partially promoting")
            }
            val missing = historical.copy(instances = historical.instances - provider)
            assertEquals(missing, selected(missing), "Reject missing ${provider.type}")
            val duplicate = historical.copy(instances = historical.instances.toMutableList().apply {
                add(historical.index(provider.id), provider.copy(id = "duplicate-${provider.id}"))
            })
            assertEquals(duplicate, selected(duplicate), "Reject duplicate ${provider.type}")
            val oldVersion = if (provider.version == 1) 2 else 1
            val wrongVersion = historical.copy(instances = historical.instances.map { if (it.id != provider.id) it else
                it.copy(version = oldVersion, plugin = BuiltinModifierPlugins.reference(it.type, oldVersion)) })
            assertEquals(wrongVersion, selected(wrongVersion), "Reject incompatible ${provider.type} version")
        }
    }

    @Test fun freshDefaultTrioDoesNotMigrateExistingRetiredOrPresetSwitchStacks(): Unit = runBlocking {
        val art = singleEyeOpenMouthArt(emptySet(), expressions = true)
        val retired = RigModifierStack(retiredIds = setOf("old-owner"), nextId = 2)
        val initialized = prepareFresh(art, initialStack = retired).first
        val historical = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        assertDefaults(historical, emptySet())
        assertTrue("old-owner" in historical.retiredIds)
        val fixture = Fixture(initialized)
        fixture.install()
        assertEquals(historical, RigModifierStack.fromJson(fixture.now.document.rigEdits.modifierStack!!))
        fixture.edit("modifier_switch", buildJsonObject { put("group", "legacy") })
        fixture.edit("modifier_switch", buildJsonObject { put("group", "modern") })
        val switched = RigModifierStack.fromJson(fixture.now.document.rigEdits.modifierStack!!)
        assertTrue(switched.retiredIds.containsAll(historical.instances.map { it.id }))
        assertEquals(historicalVersions, switched.instances.filter { it.type in versions }
            .associate { it.type to it.version }, "Preset switching keeps the historical source trio")
    }

    @Test fun freshDefaultTrioKeepsUnprovedSourceMaterialsAndAuthoredChannelsHistorical(): Unit = runBlocking {
        val original = document(eyeDetails = true, mouthDetails = true, expressionDetails = true)
        val source = original.source as WorkspaceSourceArt
        fun changeFace(change: (WorkspaceSourceLayer) -> WorkspaceSourceLayer) = original.copy(source = source.copy(
            layers = source.layers.map { if (it.id.raw == "face") change(it as WorkspaceSourceLayer) else it }))
        val cases = listOf(
            "authored skin parameter" to original.copy(layerOverrides = original.layerOverrides +
                ("face" to original.layerOverrides.getValue("face").copy(parameter = "SkinVariant"))),
            "source variant" to changeFace { it.copy(name = "face 2") },
            "non-Normal blend" to changeFace { it.copy(blend = LayerBlend.Multiply) },
            "source opacity" to changeFace { it.copy(opacity = .8f) },
            "clipped source" to changeFace { it.copy(clipped = true) },
            "channel mask" to changeFace { it.copy(channelMask = ChannelMask.ALL.copy(red = false)) },
            "derived source" to changeFace { it.copy(derived = true) },
            "spatial reference" to changeFace { it.copy(sourceSpatialReferenceId = "authored-reference") },
            "float source rectangle" to changeFace { it.copy(rect = LayerCanvasRect.of(it.bounds)) },
            "source transform" to changeFace { it.copy(layerTransform = LayerTransform(1f, 0f, 0f, 1f, .25f, 0f)) },
            "folder source" to changeFace { it.copy(groupPath = "head") }.let { art -> art.copy(source =
                (art.source as WorkspaceSourceArt).copy(groups = listOf(WorkspaceSourceGroup("head", "head", true,
                    1f, false, LayerBlend.Normal, true)))) },
            "MOUTH_OPEN-only source" to original.copy(layerOverrides = original.layerOverrides +
                ("mouth" to original.layerOverrides.getValue("mouth").copy(tag = SemanticTag.MOUTH_OPEN))))
        val (positive, _) = prepareFresh(original)
        assertDefaults(RigModifierStack.fromJson(positive.rigEdits.modifierStack!!), emptySet(), versions)
        for ((label, artwork) in cases) {
            val (initialized, model) = prepareFresh(artwork)
            val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
            assertEquals(historicalVersions, stack.instances.filter { it.type in versions }.associate { it.type to it.version },
                "$label must keep the complete historical trio")
            assertTrue(assertNotNull(model.modifierEvaluation).entries.all { it.status == ModifierStatus.ACTIVE }, label)
            val replay = WorkspacePreviewBuilder().build(initialized)
            compare(model, replay, stack, "$label historical source replay")
        }
    }

    @Test fun freshDefaultTrioRejectsUnmatchedEyeHighlightSourceAdmission(): Unit = runBlocking {
        for (mirror in listOf(false, true)) {
            val original = singleEyeOpenMouthArt(emptySet(), mirror, expressions = true)
            val (initialized, _) = prepareFresh(original)
            val automatic = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
            assertDefaults(automatic, emptySet(), versions)
            val historical = select(automatic, historicalVersions)
            suspend fun author(art: WorkspaceDocument) = WorkspacePreviewBuilder().build(withStack(
                initialized.copy(source = art.source, layerOverrides = art.layerOverrides), RigModifierStack()))
            fun admitted(model: RigPreviewModel) = WorkspacePresetInitialization.sourceSingleEyeSkinDefault(
                model, model.config, historical, emptyMap(), null,
                pristineSourceStack = true, automaticSingleEyeOpenMouth = true)
            assertEquals(automatic, admitted(author(original)), "Positive control uses genuine unbound artwork")
            for (side in listOf(Side.NONE, if (mirror) Side.LEFT else Side.RIGHT)) {
                val art = original.copy(layerOverrides = original.layerOverrides +
                    ("highlight" to original.layerOverrides.getValue("highlight").copy(side = side)))
                val model = author(art)
                val analysis = RigModifierSource.of(model).analysis
                assertEquals(side, analysis.layers.single { it.source.id.raw == "highlight" }.semantic.side)
                assertEquals(setOf(if (mirror) Side.RIGHT else Side.LEFT), analysis.layers
                    .filter { it.semantic.tag == SemanticTag.EYEWHITE }.map { it.semantic.side }.toSet())
                // Some unmatched highlight artwork is already invalid in the historical
                // pipeline (missing its eye-gaze parent). This is an admission-only guard:
                // do not pretend that retaining 8/1/1 repairs or makes that source valid.
                assertEquals(historical, admitted(model), "Do not promote a source with an unmatched $side highlight")
            }
        }
    }

    @Test fun freshDefaultTrioMatchesPlainAndShadowLowerFaceDetails(): Unit = runBlocking {
        for (mirror in listOf(false, true)) for (role in listOf(null, PartRole.SHADOW)) {
            val original = singleEyeOpenMouthArt(emptySet(), mirror, expressions = true)
            val art = original.copy(source = object : SourceArt by original.source {
                override val layers = original.source.layers.map { layer ->
                    if (layer.id.raw != "blush") layer else (layer as WorkspaceSourceLayer).copy(
                        name = "lower face detail", bounds = layer.bounds.copy(top = 42))
                }
            }, layerOverrides = original.layerOverrides +
                ("blush" to original.layerOverrides.getValue("blush").copy(role = role)))
            freshDefaultCase(mirror = mirror, artwork = art)
        }
    }

    @Test fun freshDefaultTrioKeepsGlobalDefinitionsExplicit() {
        val definitions = BuiltinModifierPlugins.definitions(null)
        for ((type, version) in versions) {
            assertTrue(definitions.single { it.type == type && it.version == version }.explicitVersion)
            assertEquals(BuiltinModifierPlugins.reference(type, version), BundledAlgorithmArtifacts.find(type, version)?.reference)
        }
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
            assertTrue(nativeCaptureDefinition(case.actual, entry.type).explicitVersion)
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
            val initialized = prepareFresh(singleEyeOpenMouthArt(profile.removed, mirror, profile.expressions)).first
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
        for ((profile, mirror, stack) in admissions) {
            val coupling = if (mirror) profile.rightCoupling else profile.leftCoupling
            assertDefaults(stack, profile.removed, if (coupling == 9) versions else historicalVersions)
        }
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
                val definition = nativeCaptureDefinition(case.actual, entry.type)
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

    @Test fun freshDefaultTrioSurvivesActualPrefixApplyColdReplayAndArchive(): Unit = runBlocking {
        verifyPrefixApplyColdReplayAndArchive(freshDefaultCase())
    }

    @Test fun freshDefaultTrioInstalledJvmPackagesMatchExactStages(): Unit = runBlocking {
        verifyInstalledWorkers(freshDefaultCase())
    }

    @Test fun composableSkinV5CapturesOnlyFinalSourceContextsAfterPrefixApply(): Unit = runBlocking {
        val case = case(cheekStrength = 80f, gloomStrength = 140f)
        val fixture = Fixture(case.document)
        fixture.install()
        val spring = case.selected.instances.single { it.type == "mouth.spring" }
        fixture.apply(case.selected.instances[case.selected.index(spring.id) - 1].id)
        val cache = RigStageCache.enabled
        val replay = try { RigStageCache.enabled = false; WorkspacePreviewBuilder().build(fixture.now.document) }
            finally { RigStageCache.enabled = cache }
        val source = RigModifierSource.of(replay)
        val authoredIds = source.analysis.source.layers.mapTo(hashSetOf()) { it.id.raw }
        assertTrue(source.analysis.layers.any { it.source.id.raw !in authoredIds },
            "Use actual materialized outline rasters, which require source-only measurements")
        assertExactIr(RigIrCompiler.compile(case.actual, tileArt = true), RigIrCompiler.compile(replay, tileArt = true),
            "Cold applied-prefix replay retains complete textured IR")
        val original = assertNotNull(case.actual.modifierEvaluation)
        val replayed = assertNotNull(replay.modifierEvaluation)
        val stack = RigModifierStack.fromJson(replay.config.rigEdits.modifierStack!!)

        fun <T> profile(block: () -> T): Pair<T, Map<String, Pair<Double, Long>>> {
            val recording = RigBuildProfile.recording
            val enabled = RigStageCache.enabled
            RigBuildProfile.reset()
            try {
                RigBuildProfile.recording = true
                RigStageCache.enabled = false
                val result = block()
                return result to RigBuildProfile.snapshot()
            } finally {
                RigBuildProfile.recording = recording
                RigStageCache.enabled = enabled
                RigBuildProfile.reset()
            }
        }

        for ((index, owner) in stack.instances.withIndex()) {
            val originalIndex = case.selected.index(owner.id)
            assertRawCoreAfterApply(original.outputs[originalIndex], replayed.outputs[index], "Cold suffix ${owner.type}")
            if (owner.type !in versions) continue
            val expected = assertNotNull(original.readAlgorithms[owner.id]).context
            val scaffold = source.provider(owner)
            val prefix = source.activeBefore(owner).filter { it.id != scaffold.id && it.targets.any(owner.targets::contains) }
            val module = RigModules.MOUTH_SPRING.takeIf { owner.type == "mouth.spring" }
            // The discarded pre-fix call is a real native positive control, not a fabricated counter.
            val (baseline, nativeProfile) = profile { RigFittedMouthModuleSource.context(source, scaffold, prefix, module) }
            assertTrue(baseline.second.deformers.isNotEmpty())
            assertEquals(1L, nativeProfile["context: mesh footprints + anchors"]?.second, "${owner.type} native context control")
            assertEquals(1L, nativeProfile["scaffold: deformers (built)"]?.second, "${owner.type} native motion control")

            val nativeDefinition = nativeCaptureDefinition(replay, owner.type)
            val definition = registeredMeasurementDefinition(source, owner.type)
            val (nativeCapture, eagerProfile) = profile { nativeDefinition.authorContext(owner) }
            val (captured, captureProfile) = profile { definition.authorContext(owner) }
            assertExactSourceContext(nativeCapture, captured, "${owner.type} native versus registered source capture after prefix apply")
            assertExactSourceContext(expected, captured.withUpstream(expected.upstream),
                "${owner.type} source values and resource bytes remain exact after prefix application")
            // The main framing is always measured; face details add their separate outline-free framing.
            val detailGroups = captured.values.getValue("groups").jsonArray.count { group ->
                group.jsonObject.getValue("layers").jsonObject.values.all {
                    it.jsonObject.getValue("tag") == JsonPrimitive("FACE_DETAIL")
                }
            }
            val measurements = 1L + detailGroups
            if (owner.type != "mouth.spring") assertEquals(2L, measurements, "Exercise both source framings")
            assertEquals(measurements, eagerProfile["context: mesh footprints + anchors"]?.second,
                "${owner.type} must retain the independent eager source framing control")
            assertEquals(measurements, eagerProfile["scaffold: deformers (built)"]?.second,
                "${owner.type} cache-off native capture must actually build each animated scaffold")
            assertEquals(0L, eagerProfile["source skin: measurement contexts"]?.second ?: 0L)
            assertEquals(measurements, captureProfile["context: mesh footprints + anchors"]?.second,
                "${owner.type} registered capture must construct only the final source context for each framing")
            assertEquals(measurements, captureProfile["source skin: measurement contexts"]?.second)
            assertEquals(measurements, captureProfile["source skin: static pair frames"]?.second)
            assertEquals(0L, captureProfile["scaffold: deformers (built)"]?.second ?: 0L,
                "Registered capture must not construct animated scaffold motion")
            assertExactMeasurementIr(original.outputs[originalIndex], (definition.plugin as ReadingModifierAlgorithmPlugin).evaluate(
                original.outputs[originalIndex - 1], owner, captured.withUpstream(expected.upstream), {}),
                "${owner.type} worker output remains exact with the restored registered source capture")
        }
    }

    /** Run this small control before the larger source-measurement fixture matrix. */
    @Test fun sourceSkinMeasurementsMatchNativeCaptureAndInstalledWorkers(): Unit = runBlocking {
        val model = case().actual
        assertSourceMeasurementFrames(model)
        verifySourceMeasurements(model, "single-eye source smoke")
    }

    @Test fun sourceSkinMeasurementsMatchBothSidesAndAllMouthInteriors(): Unit = runBlocking {
        for (mirror in listOf(false, true)) for (removed in listOf(emptySet(), setOf("tongue"),
            setOf("teeth-top", "teeth-bottom", "tongue"))) {
            val (_, model) = measurementModel(singleEyeOpenMouthArt(removed, mirror, expressions = true))
            verifySourceMeasurements(model, "mirror=$mirror, removed=$removed")
        }
    }

    @Test fun sourceSkinMeasurementsPreserveCorrectionsAndExplicitHeadAlignment(): Unit = runBlocking {
        for (mirror in listOf(false, true)) {
            val (_, model) = measurementModel(singleEyeOpenMouthArt(emptySet(), mirror, expressions = true), tuned = true)
            assertSourceMeasurementFrames(model, corrections = true)
            verifySourceMeasurements(model, "corrected source, mirror=$mirror")
        }
    }

    @Test fun sourceSkinMeasurementsPreserveLowerDetailOutlineFreeFraming(): Unit = runBlocking {
        for (mirror in listOf(false, true)) {
            val original = singleEyeOpenMouthArt(emptySet(), mirror, expressions = true)
            val art = original.copy(source = object : SourceArt by original.source {
                override val layers = original.source.layers.map { layer ->
                    if (layer.id.raw != "blush") layer else object : SourceLayer by layer {
                        override val bounds = layer.bounds.copy(top = 42)
                        override val name = "lower face detail"
                    }
                }
            }, layerOverrides = original.layerOverrides + ("blush" to original.layerOverrides.getValue("blush").copy(role = null)))
            val (_, model) = measurementModel(art)
            val contexts = verifySourceMeasurements(model, "lower face detail, mirror=$mirror")
            for ((type, context) in contexts) assertEquals(2, context.values.getValue("groups").jsonArray.size,
                "$type must independently measure face/nose and outline-free face details")
            val stack = RigModifierStack.fromJson(assertNotNull(model.config.rigEdits.modifierStack))
            val spring = stack.instances.single { it.type == "mouth.spring" }
            val evaluation = assertNotNull(model.modifierEvaluation)
            val index = stack.index(spring.id)
            val before = evaluation.outputs[index - 1].meshes.single { it.name == "lower face detail" }
            val after = evaluation.outputs[index].meshes.single { it.id == before.id }
            assertNotEquals(before, after, "The lower detail must actually receive source spring displacement")
        }
    }

    @Test fun sourceSkinMeasurementsPreserveSourceOrderAndCollidingStaticPairIds(): Unit = runBlocking {
        val assignments = mutableListOf<Map<String, String>>()
        for (reverse in listOf(false, true)) {
            val original = singleEyeOpenMouthArt(emptySet(), expressions = true)
            val template = original.source.layers.single { it.id.raw == "blush" } as WorkspaceSourceLayer
            // The first pair is not skin. Its name still reserves the same sanitized ID before
            // either affected detail pair, so filtering the inventory to skin cannot pass this test.
            val pairs = listOf("lower.detail" to SemanticTag.TOPWEAR,
                "lower-detail" to SemanticTag.FACE_DETAIL, "lower detail" to SemanticTag.FACE_DETAIL)
                .flatMapIndexed { pair, (name, tag) ->
                    listOf(Side.LEFT, Side.RIGHT).map { side ->
                        val id = "detail-$pair-${side.name}"
                        template.copy(id = LayerId(id), name = "$name ${if (side == Side.LEFT) "L" else "R"}",
                            bounds = template.bounds.copy(left = if (side == Side.LEFT) 18 else 38,
                                top = if (tag == SemanticTag.TOPWEAR) 58 else 42)) to
                            LayerClassificationOverride(LayerType.PRESET, tag, side)
                    }
                }.let { if (reverse) it.reversed() else it }
            val orderedLayers = (original.source.layers + pairs.map { it.first }).mapIndexed { index, layer ->
                (layer as WorkspaceSourceLayer).copy(order = index)
            }
            val art = original.copy(source = object : SourceArt by original.source { override val layers = orderedLayers },
                layerOverrides = original.layerOverrides + pairs.associate { it.first.id.raw to it.second })
            val (_, model) = measurementModel(art)
            val framing = assertSourceMeasurementFrames(model)
            val unrelated = framing.native.analysis.layers.filter { it.source.id.raw.startsWith("detail-0-") }
            assertEquals(2, unrelated.size)
            val expectedPair = org.umamo.runtime.model.DeformerId(if (reverse) "DeformPair_LowerDetail_3" else "DeformPair_LowerDetail")
            val unrelatedFrames = unrelated.map { framing.native.parentAndFrame(it, framing.config) }
            assertTrue(unrelatedFrames.all { it.first == expectedPair }, "The unrelated torso art must really reserve a native pair ID")
            assertEquals(unrelatedFrames[0], unrelatedFrames[1])
            assertEquals(unrelatedFrames[0].second, framing.measured.resolveFrame(expectedPair, framing.config),
                "The prototype must retain the unrelated pair's exact static frame")
            val contexts = verifySourceMeasurements(model, "colliding pair IDs, reversed source order=$reverse")
            val source = RigModifierSource.of(model)
            val parents = contexts.getValue("mouth.spring").values.getValue("groups").jsonArray
                .flatMap { it.jsonObject.getValue("sourceVertices").jsonObject.getValue("targets").jsonObject.entries }
                .associate { it.key to assertNotNull(it.value.jsonObject.getValue("parent").jsonPrimitive.contentOrNull) }
            val skin = pairs.filter { it.second.tag == SemanticTag.FACE_DETAIL }
            val bySource = skin.associate { (layer, _) -> layer.id.raw to parents.getValue(source.primaryByLayer.getValue(layer.id.raw)) }
            for (pair in 1..2) assertEquals(bySource.getValue("detail-$pair-LEFT"), bySource.getValue("detail-$pair-RIGHT"),
                "Both sides must use their actual common static frame")
            assertEquals("DeformPair_LowerDetail_2", bySource.getValue("detail-1-LEFT"))
            assertEquals(if (reverse) "DeformPair_LowerDetail" else "DeformPair_LowerDetail_3",
                bySource.getValue("detail-2-LEFT"), "The unrelated torso pair must participate in ordered ID allocation")
            assignments += bySource
        }
        assertNotEquals(assignments[0], assignments[1], "Reversing real source order must exercise collision allocation order")
    }

    @Test fun sourceSkinMeasurementsKeepBilateralEarHeadFrameFallback(): Unit = runBlocking {
        val initialized = prepare(extendedMeasurementArt(bilateralEars = true))
        val historical = RigModifierStack.fromJson(assertNotNull(initialized.rigEdits.modifierStack))
        val coupling = historical.instances.single { it.type == "mouth.cheek-lid" }
        assertEquals(1, coupling.version, "Keep the actual source-head fallback for the original tilted-ear drawing")
        val nativeModel = WorkspacePreviewBuilder().build(initialized)
        val source = RigModifierSource.of(nativeModel)
        val scaffold = source.provider(coupling)
        val preceding = source.activeBefore(coupling).filter { entry ->
            entry.config["scaffold"] == JsonPrimitive(scaffold.id) && entry.targets.any(coupling.targets::contains) &&
                RigLegacyBindings.find(entry.type)?.let { !it.expressionToggles && !it.seeThrough } == true
        }
        val native = RigFittedMouthModuleSource.nativeConfig(source, scaffold, preceding, null).copy(generateDeformers = false)
        val eye = preceding.single { it.type == "eye/fitted" }
        val head = preceding.single { it.type == "face/fitted" }
        val depth = RigModules.FACE_DEPTH_TURN
        val lidNative = native.copy(mouthOutlineEnabled = source.sourceConfig().mouthOutlineEnabled, recipe = native.recipe.copy(
            bases = mapOf("eye" to io.github.psd2live.core.recipe.BaseChoice(eye.type, eye.version)),
            modules = RigModules.modules.associate { module -> module.id to if (module == depth)
                io.github.psd2live.core.recipe.ModuleChoice(true, depth.version, RigLegacyBindings.values(head))
                else io.github.psd2live.core.recipe.ModuleChoice(false) },
        ))
        val mouthHead = RigBuilder.rigContext(source.analysis, native, source.meshCache).faceRig
        val lidHead = RigBuilder.rigContext(source.analysis, lidNative, source.meshCache).faceRig
        assertNotEquals(RigBuilder.headTurnMeasurements(mouthHead, native), RigBuilder.headTurnMeasurements(lidHead, lidNative),
            "The real mouth-prefix and outline-free lid head measurements explain the native fallback")
        val frames = assertSourceMeasurementFrames(nativeModel, requireRoll = false)
        val ears = org.umamo.runtime.model.DeformerId("DeformEars")
        assertEquals(assertNotNull(frames.native.resolveFrame(ears, frames.config)), frames.measured.resolveFrame(ears, frames.config))
        val selected = select(historical)
        assertEquals(coupling, selected.instances.single { it.id == coupling.id })
        val requested = withStack(initialized, selected)
        val failure = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(requested) }
        assertEquals("Mouth spring v10 requires compatible mouth.cheek-lid source providers", failure.message)
        val rejected = nativeModel.copy(config = nativeModel.config.copy(rigEdits = requested.rigEdits))
        val spring = selected.instances.single { it.type == "mouth.spring" }
        val nativeFailure = assertFailsWith<IllegalArgumentException> { nativeCaptureDefinition(rejected, spring.type).authorContext(spring) }
        val measuredFailure = assertFailsWith<IllegalArgumentException> {
            registeredMeasurementDefinition(RigModifierSource.of(rejected), spring.type).authorContext(spring)
        }
        assertEquals(nativeFailure.message, measuredFailure.message, "The prototype must not bypass the actual cheek/lid1 boundary")
    }

    @Test fun sourceSkinMeasurementsMatchAdmittedStandingBodyAndHair(): Unit = runBlocking {
        val (_, model) = measurementModel(extendedMeasurementArt(standing = true, pairedHair = true))
        val frames = assertSourceMeasurementFrames(model)
        val legArt = frames.native.analysis.layers.filter { it.semantic.tag in setOf(SemanticTag.LEGWEAR, SemanticTag.FOOTWEAR) }
        assertEquals(setOf(Side.LEFT, Side.RIGHT), legArt.map { it.semantic.side }.toSet())
        val floor = legArt.maxOf { it.bounds.bottom }
        val torso = frames.native.stance.torso
        assertTrue(floor >= torso.waistY + torso.length, "The actual legs must reach below the inferred torso: floor=$floor, waist=${torso.waistY}, torso=${torso.length}")
        assertTrue(frames.native.stance.standing, "Actual leg art must produce a standing stance")
        assertEquals(2, frames.native.stance.legs.size)
        assertNotNull(frames.native.stance.legsFrame)
        assertNotEquals(frames.native.character, frames.native.bodyFrame, "Body frame must exclude the feet")
        val hair = frames.native.analysis.layers.filter { it.source.id.raw.startsWith("source-lock-") }
        assertEquals(2, hair.size)
        for (layer in hair) assertNotNull(frames.native.parentAndFrame(layer, frames.config).first,
            "The optional source hair retains a real native owner")
        verifySourceMeasurements(model, "native-admitted standing body and paired hair")
    }

    @Test fun sourceSkinMeasurementsRejectActuallyPlannedArmProviders(): Unit = runBlocking {
        // HANDWEAR really plans body.arms, which is outside Cheek5/Gloom5's supported provider set.
        // Keep that chain and its package identity intact instead of forcing an admitted fixture.
        val initialized = prepare(extendedMeasurementArt(standing = true, arms = true, pairedHair = true))
        val historical = RigModifierStack.fromJson(assertNotNull(initialized.rigEdits.modifierStack))
        val arms = historical.instances.single { it.type == "body.arms" }
        assertTrue(arms.enabled)
        assertEquals(BuiltinModifierPlugins.reference(arms.type, arms.version), arms.plugin)
        assertFalse(arms.type in CheekV5Algorithm.supported)
        assertFalse(arms.type in GloomV5Algorithm.supported)
        val nativeModel = WorkspacePreviewBuilder().build(initialized)
        assertTrue(assertNotNull(nativeModel.modifierEvaluation).entries.all { it.status == ModifierStatus.ACTIVE },
            "The real historical source chain with arms remains supported")
        // The low-level observation still handles native arm ownership without generating its motion.
        // This does not make body.arms a supported provider for the complete source trio.
        val frames = assertSourceMeasurementFrames(nativeModel)
        assertTrue(frames.native.deformers.any { it.id.raw.startsWith("DeformArmHang_") })
        assertTrue(frames.measured.deformers.isEmpty())
        val selected = select(historical)
        val requested = withStack(initialized, selected)
        val failure = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(requested) }
        assertEquals("Cheek v5 rejects unknown or foreign source providers", failure.message)
        val rejectedModel = nativeModel.copy(config = nativeModel.config.copy(rigEdits = requested.rigEdits))
        val rejectedSource = RigModifierSource.of(rejectedModel)
        val (_, validModel) = measurementModel(singleEyeOpenMouthArt(emptySet(), expressions = true))
        val validStack = RigModifierStack.fromJson(assertNotNull(validModel.config.rigEdits.modifierStack))
        for (type in listOf("expression.cheek", "expression.gloom")) {
            val validOwner = validStack.instances.single { it.type == type }
            assertExactSourceContext(nativeCaptureDefinition(validModel, type).authorContext(validOwner),
                registeredMeasurementDefinition(RigModifierSource.of(validModel), type).authorContext(validOwner),
                "$type valid source positive control")
            val owner = selected.instances.single { it.type == type }
            assertEquals(arms, selected.instances.single { it.id == arms.id }, "Keep the actual arm provider unchanged")
            assertTrue(rejectedSource.activeBefore(owner).any { it == arms && it.targets.any(owner.targets::contains) })
            val native = assertFailsWith<IllegalArgumentException> { nativeCaptureDefinition(rejectedModel, type).authorContext(owner) }
            val measured = assertFailsWith<IllegalArgumentException> {
                registeredMeasurementDefinition(rejectedSource, type).authorContext(owner)
            }
            assertEquals(native.message, measured.message, "$type must retain the actual unsupported-arm boundary")
        }
    }

    @Test fun sourceSkinMeasurementsMatchFeatureDisplacementAndHairSwayControls(): Unit = runBlocking {
        val art = extendedMeasurementArt(pairedHair = true)
        for (feature in listOf(false, true)) for (front in listOf(false, true)) for (back in listOf(false, true)) {
            val controls = linkedMapOf("featureDisplacement" to feature, "frontHairSway" to front, "backHairSway" to back)
            val (_, model) = measurementModel(art, scaffoldControls = controls)
            val frames = assertSourceMeasurementFrames(model)
            assertEquals(feature, frames.config.featureDisplacementEnabled)
            assertEquals(!front, frames.config.hairSimulationFront)
            assertEquals(!back, frames.config.hairSimulationBack)
            assertEquals(feature, frames.native.deformers.any { it.id.raw == "DeformFeatureDisplacement" },
                "Feature displacement must alter real native scaffold construction")
            assertNotNull(frames.native.frontHair)
            assertNotNull(frames.native.backHair)
            verifySourceMeasurements(model, "real scaffold controls $controls")
        }
    }

    private fun extendedMeasurementArt(bilateralEars: Boolean = false, standing: Boolean = false,
                                       arms: Boolean = false, pairedHair: Boolean = false): WorkspaceDocument {
        val original = singleEyeOpenMouthArt(emptySet(), expressions = true)
        val template = original.source.layers.first() as WorkspaceSourceLayer
        val additions = mutableListOf<Pair<WorkspaceSourceLayer, LayerClassificationOverride>>()
        fun add(id: String, name: String, tag: SemanticTag, side: Side, bounds: LayerBounds, role: PartRole? = null) {
            additions += template.copy(id = LayerId(id), name = name, bounds = bounds,
                raster = LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { 255.toByte() })) to
                LayerClassificationOverride(LayerType.PRESET, tag, side, role = role)
        }
        if (bilateralEars) add("ear-right", "ear right", SemanticTag.EARS, Side.RIGHT, LayerBounds(49, 24, 6, 13))
        // Without waist artwork the analyzer puts the hips at 62% of the complete body bounds,
        // including these legs. Real shorts anchor the hips above the knees instead. PLAIN keeps
        // the shorts from implying a skirt module, whose source provider is outside this cohort.
        if (standing) add("source-shorts", "shorts", SemanticTag.BOTTOMWEAR, Side.NONE,
            LayerBounds(18, 68, 28, 10), PartRole.PLAIN)
        for (side in listOf(Side.LEFT, Side.RIGHT)) {
            val suffix = if (side == Side.LEFT) "L" else "R"
            if (standing) {
                add("source-leg-$suffix", "leg $suffix", SemanticTag.LEGWEAR, side,
                    LayerBounds(if (side == Side.LEFT) 18 else 36, 73, 10, 42))
                add("source-foot-$suffix", "foot $suffix", SemanticTag.FOOTWEAR, side,
                    LayerBounds(if (side == Side.LEFT) 15 else 35, 112, 14, 6))
            }
            if (arms) add("source-arm-$suffix", "arm $suffix", SemanticTag.HANDWEAR, side,
                LayerBounds(if (side == Side.LEFT) 4 else 54, 47, 6, 28))
            if (pairedHair) add("source-lock-$suffix", "side lock $suffix", SemanticTag.FRONT_HAIR, side,
                LayerBounds(if (side == Side.LEFT) 13 else 47, 10, 4, 28), PartRole.SIDE)
        }
        val layers = (original.source.layers + additions.map { it.first }).mapIndexed { index, layer ->
            (layer as WorkspaceSourceLayer).copy(order = index)
        }
        return original.copy(source = WorkspaceSourceArt(original.source.widthPx, if (standing) 128 else original.source.heightPx,
            layers, original.source.groups), layerOverrides = original.layerOverrides + additions.associate { it.first.id.raw to it.second })
    }

    @Test fun sourceSkinMeasurementsMatchRestoredOutlineAssetsAndArchive(): Unit = runBlocking {
        val case = case(tuned = true, cheekStrength = 80f, gloomStrength = 140f)
        val fixture = Fixture(case.document)
        fixture.install()
        val spring = case.selected.instances.single { it.type == "mouth.spring" }
        fixture.apply(case.selected.instances[case.selected.index(spring.id) - 1].id)
        suspend fun cold(document: WorkspaceDocument): RigPreviewModel {
            val cache = RigStageCache.enabled
            return try { RigStageCache.enabled = false; WorkspacePreviewBuilder().build(document) }
            finally { RigStageCache.enabled = cache }
        }
        fun verify(model: RigPreviewModel, label: String) {
            val source = RigModifierSource.of(model)
            val authored = source.analysis.source.layers.mapTo(hashSetOf()) { it.id.raw }
            assertTrue(source.analysis.layers.any { it.source.id.raw !in authored }, "$label contains actual restored outline rasters")
            assertExactCompiledMeasurementIr(RigIrCompiler.compile(case.actual, tileArt = true), RigIrCompiler.compile(model, tileArt = true),
                "$label complete textured output without annotation normalization")
            verifySourceMeasurements(model, label)
        }
        verify(cold(fixture.now.document), "cold applied source prefix")
        val root = Files.createTempDirectory("source-measurements-archive-")
        try {
            val history = WorkspaceStore(root.resolve("project"))
            history.persistHistory(fixture.now.projectId, fixture.runtime.history())
            val repository = ProjectRepository(writeHeadCache = false)
            val archive = root.resolve("source-measurements.psd2live")
            repository.save(ProjectSaveCapture(fixture.now.projectId, fixture.runtime.history(), JsonObject(emptyMap()), null, history), archive)
            repository.open(archive).use { opened -> verify(cold(opened.history.head().snapshot), "cold source archive") }
        } finally { ModifierPluginStore.deleteTree(root) }
    }

    /** Select only the comparison trio after real fresh-source admission, with no fabricated provider IR. */
    private suspend fun measurementModel(art: WorkspaceDocument, tuned: Boolean = false,
                                         scaffoldControls: Map<String, Boolean> = emptyMap()): Pair<WorkspaceDocument, RigPreviewModel> {
        val initialized = prepare(art)
        val defaults = RigModifierStack.fromJson(assertNotNull(initialized.rigEdits.modifierStack))
        assertEquals(9, defaults.instances.single { it.type == "mouth.cheek-lid" }.version,
            "The native source pipeline must admit the fixture before selecting source consumers")
        val selected = select(defaults).let { stack -> if (!tuned) stack else stack.copy(instances = stack.instances.map { owner ->
            val values = when (owner.type) {
                "face/fitted" -> mapOf("turn" to 63f, "look" to 120f, "depth" to 135f)
                "mouth/fitted" -> mapOf("early" to 45f, "form" to 125f, "reach" to 140f)
                "mouth.jaw" -> mapOf("share" to 22f)
                "expression.cheek" -> mapOf("strength" to 80f)
                "expression.gloom" -> mapOf("strength" to 140f)
                else -> emptyMap()
            }
            when {
                owner.type == "rig.scaffold" -> owner.copy(config = JsonObject(owner.config + ("corrections" to buildJsonObject {
                    putJsonObject("moves") {
                        putJsonObject("mouth") { putJsonArray("upper") { add(1f); add(-2f) } }
                        val side = art.layerOverrides.getValue("brow").side
                        putJsonObject("brow.${if (side == Side.LEFT) "L" else "R"}") {
                            putJsonArray("arch") { add(.5f); add(-1f) }
                        }
                    }
                })))
                values.isEmpty() -> owner
                else -> owner.copy(config = JsonObject(owner.config + ("values" to
                    JsonObject(owner.config["values"]?.jsonObject.orEmpty() + values.mapValues { JsonPrimitive(it.value) }))))
            }
        }) }
        val controlled = selected.copy(instances = selected.instances.map { owner ->
            if (owner.type != "rig.scaffold" || scaffoldControls.isEmpty()) owner else owner.copy(config =
                JsonObject(owner.config + scaffoldControls.mapValues { JsonPrimitive(it.value) }))
        })
        val document = withStack(initialized, controlled)
        return document to WorkspacePreviewBuilder().build(document)
    }

    private data class MeasurementFrames(val config: PipelineConfig, val native: RigBuilder.RigContext,
                                         val measured: RigBuilder.RigContext)

    private fun assertSourceMeasurementFrames(model: RigPreviewModel, corrections: Boolean = false,
                                             requireRoll: Boolean = true): MeasurementFrames {
        val source = RigModifierSource.of(model)
        val stack = RigModifierStack.fromJson(assertNotNull(model.config.rigEdits.modifierStack))
        val owner = stack.instances.single { it.type == "mouth.spring" }
        val scaffold = source.provider(owner)
        val prefix = source.activeBefore(owner).filter { it.id != scaffold.id && it.targets.any(owner.targets::contains) }
        val config = RigFittedMouthModuleSource.nativeConfig(source, scaffold, prefix, RigModules.MOUTH_SPRING)
        if (corrections) assertTrue(config.recipe.moves.isNotEmpty(), "Exercise actual scaffold correction moves")
        val (native, nativeProfile) = profileSourceMeasurement { RigBuilder.rigContext(source.analysis, config, source.meshCache) }
        val (measured, measuredProfile) = profileSourceMeasurement {
            RigBuilder.sourceSkinMeasurementContext(source.analysis, config, source.meshCache)
        }
        assertTrue(native.deformersEnabled && native.deformers.isNotEmpty())
        assertEquals(1L, nativeProfile["scaffold: deformers (built)"]?.second)
        assertTrue(measured.sourceMeasurementsOnly)
        assertFalse(measured.deformersEnabled)
        assertTrue(measured.deformers.isEmpty(), "Static pair grids are discarded after extracting their numeric framing")
        assertEquals(1L, measuredProfile["source skin: measurement contexts"]?.second)
        assertEquals(0L, measuredProfile["scaffold: deformers (built)"]?.second ?: 0L)
        assertEquals(native.headSpace, measured.headSpace)
        if (requireRoll) assertNotEquals(0f, native.headSpace.angleDegrees, "The fixture must exercise inferred nonzero head alignment")
        assertEquals(native.character, measured.character)
        assertEquals(native.head, measured.head)
        assertEquals(native.face, measured.face)
        assertEquals(native.bodyFrame, measured.bodyFrame)
        assertEquals(native.stance.contentKey, measured.stance.contentKey)
        assertEquals(native.stance.legsFrame, measured.stance.legsFrame)
        assertEquals(native.frontHair, measured.frontHair)
        assertEquals(native.backHair, measured.backHair)
        val layers = native.analysis.layers.filter { it.semantic.tag in setOf(SemanticTag.FACE, SemanticTag.NOSE,
            SemanticTag.EARS, SemanticTag.FACE_DETAIL) }
        assertTrue(layers.isNotEmpty())
        for (layer in layers) {
            val name = "source frame ${layer.source.id.raw}"
            assertEquals(assertNotNull(native.headSpaceFor(layer, config)), measured.headSpaceFor(layer, config), "$name explicit alignment")
            assertEquals(native.parentAndFrame(layer, config), measured.parentAndFrame(layer, config), "$name exact static parent")
            assertEquals(native.rigLayer(layer).bounds, measured.rigLayer(layer).bounds, "$name aligned bounds")
            assertEquals(native.rigLayer(layer).centroidX.toRawBits(), measured.rigLayer(layer).centroidX.toRawBits(), "$name aligned x")
            assertEquals(native.rigLayer(layer).centroidY.toRawBits(), measured.rigLayer(layer).centroidY.toRawBits(), "$name aligned y")
        }
        assertFailsWith<IllegalStateException> { measured.underFace }
        val hair = native.analysis.layers.first { it.semantic.tag == SemanticTag.FRONT_HAIR }
        assertFailsWith<IllegalArgumentException> { measured.parentAndFrame(hair, config) }
        return MeasurementFrames(config, native, measured)
    }

    @Test fun sourceSkinMeasurementsKeepNativeSourceArtworkRejections(): Unit = runBlocking {
        val (_, model) = measurementModel(singleEyeOpenMouthArt(emptySet(), expressions = true))
        val source = RigModifierSource.of(model)
        val stack = RigModifierStack.fromJson(assertNotNull(model.config.rigEdits.modifierStack))
        val analysis = source.analysis
        val mouth = analysis.layers.single { it.source.id.raw == "mouth" }
        val generated = mouth.copy(source = object : SourceLayer by mouth.source {
            override val id = LayerId("unrecognized-derived-mouth")
        })
        val invalid = linkedMapOf(
            "unrecognized generated artwork" to analysis.copy(layers = analysis.layers + generated),
            "authored skin expression" to analysis.copy(layers = analysis.layers.map { layer ->
                if (layer.source.id.raw != "face") layer else layer.copy(semantic = layer.semantic.copy(parameter = "ParamAuthoredSkin"))
            }),
            "separate closed-mouth drawing" to analysis.copy(layers = analysis.layers.map { layer ->
                if (layer.source.id.raw != "mouth") layer else layer.copy(semantic = layer.semantic.copy(tag = SemanticTag.MOUTH_CLOSE))
            }),
        )
        for (owner in stack.instances.filter { it.type in versions }) {
            assertExactSourceContext(nativeCaptureDefinition(model, owner.type).authorContext(owner),
                registeredMeasurementDefinition(source, owner.type).authorContext(owner), "${owner.type} valid source before rejection")
            for ((label, alteredAnalysis) in invalid) {
                val altered = model.copy(modifierAuthorAnalysis = alteredAnalysis)
                val native = assertFailsWith<IllegalArgumentException>("${owner.type} native rejects $label") {
                    nativeCaptureDefinition(altered, owner.type).authorContext(owner)
                }
                val (failure, profile) = profileSourceMeasurement {
                    assertFailsWith<IllegalArgumentException>("${owner.type} prototype rejects $label") {
                        registeredMeasurementDefinition(RigModifierSource.of(altered), owner.type).authorContext(owner)
                    }
                }
                assertEquals(native.message, failure.message, "${owner.type} preserves the exact $label rejection")
                assertEquals(0L, profile["scaffold: deformers (built)"]?.second ?: 0L)
            }
        }
    }

    @Test fun sourceSkinMeasurementsRejectUnsupportedNativeFraming(): Unit = runBlocking {
        val (_, model) = measurementModel(singleEyeOpenMouthArt(emptySet(), expressions = true))
        val source = RigModifierSource.of(model)
        val stack = RigModifierStack.fromJson(assertNotNull(model.config.rigEdits.modifierStack))
        val owner = stack.instances.single { it.type == "mouth.spring" }
        val scaffold = source.provider(owner)
        val prefix = source.activeBefore(owner).filter { it.id != scaffold.id && it.targets.any(owner.targets::contains) }
        val config = RigFittedMouthModuleSource.nativeConfig(source, scaffold, prefix, RigModules.MOUTH_SPRING)
        assertTrue(RigBuilder.sourceSkinMeasurementContext(source.analysis, config, source.meshCache).sourceMeasurementsOnly)
        val unsupported = linkedMapOf(
            "mesh-only" to config.copy(meshOnly = true),
            "deformers disabled" to config.copy(generateDeformers = false),
            "parent override" to config.copy(parentOverrides = mapOf("face" to "DeformFace")),
            "skeleton" to config.copy(rigEdits = config.rigEdits.copy(skeleton = SkeletonSpec())),
            "authored warp" to config.copy(rigEdits = config.rigEdits.copy(warpEdits = listOf(
                RigWarpEdit("source-test-warp", "Source test warp", "DeformFace", listOf(source.primaryByLayer.getValue("face")))))),
        )
        for ((label, invalid) in unsupported) {
            val (failure, profile) = profileSourceMeasurement {
                assertFailsWith<IllegalArgumentException>(label) {
                    RigBuilder.sourceSkinMeasurementContext(source.analysis, invalid, source.meshCache)
                }
            }
            assertEquals("Source skin measurements require unedited native source framing", failure.message)
            assertEquals(0L, profile["source skin: measurement contexts"]?.second ?: 0L, "$label must reject before capture")
        }
        // Real classified clothing/creature art makes each unsupported module applicable. These are
        // lower-level scope rejections, not a fabricated admitted source provider chain.
        val artwork = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true)
        val analysis = WorkspacePreviewBuilder().build(artwork).analysis
        val subject = io.github.psd2live.core.recipe.RecipeSubject.of(analysis)
        for (module in listOf(RigModules.OUTFIT_SKIRT, RigModules.OUTFIT_RIBBONS, RigModules.OUTFIT_CREATURE)) {
            val invalid = config.copy(recipe = config.recipe.copy(modules = config.recipe.modules +
                (module.id to io.github.psd2live.core.recipe.ModuleChoice(true))))
            assertTrue(RigModules.builds(module, invalid, subject), "Real source must actually build ${module.id}")
            val failure = assertFailsWith<IllegalArgumentException> {
                RigBuilder.sourceSkinMeasurementContext(analysis, invalid, source.meshCache)
            }
            assertEquals("Source skin measurements do not provide clothing or creature chain parents", failure.message)
        }
    }

    /** Exercise the actual registered production factory, not only the comparison alias. */
    private fun registeredMeasurementDefinition(source: RigModifierSource, type: String): RigModifierDefinition = when (type) {
        "mouth.spring" -> RigMouthSpringV10Modifier.definition(source)
        "expression.cheek" -> RigCheekV5Modifier.definition(source)
        "expression.gloom" -> RigGloomV5Modifier.definition(source)
        else -> error("Unexpected source measurement stage $type")
    }

    private fun <T> profileSourceMeasurement(block: () -> T): Pair<T, Map<String, Pair<Double, Long>>> {
        val recording = RigBuildProfile.recording
        val cache = RigStageCache.enabled
        RigBuildProfile.reset()
        try {
            RigBuildProfile.recording = true
            RigStageCache.enabled = false
            val result = block()
            return result to RigBuildProfile.snapshot()
        } finally {
            RigBuildProfile.recording = recording
            RigStageCache.enabled = cache
            RigBuildProfile.reset()
        }
    }

    private fun assertExactSourceContext(expected: ModifierAlgorithmContext, actual: ModifierAlgorithmContext, label: String) {
        assertEquals(expected.values, actual.values, "$label values")
        // JsonObject equality ignores map order. Compare its literal encoding too, including signed zero.
        assertTrue(expected.values.toString() == actual.values.toString(), "$label JSON order and numeric spelling")
        assertEquals(expected.upstream, actual.upstream, "$label complete ordered upstream identities")
        assertEquals(expected.resources.keys.toList(), actual.resources.keys.toList(), "$label ordered resource keys")
        for ((key, resource) in expected.resources) {
            val measured = actual.resources.getValue(key)
            assertEquals(key, resource.digest, "$label content-addressed native resource")
            assertEquals(resource.digest, measured.digest, "$label resource digest $key")
            assertTrue(resource.read().contentEquals(measured.read()), "$label every raw resource byte $key")
        }
    }

    private fun assertExactMeasurementIr(expected: RigIR, actual: RigIR, label: String) {
        assertExactIr(expected, actual, label)
        // Native/prototype workers receive the same pipeline input: retain transport order and all bits.
        val before = RigIrBinary.encode(expected)
        val after = RigIrBinary.encode(actual)
        if (!before.contentEquals(after)) fail("$label exact binary IR: ${measurementByteDifference(before, after)}; " +
            measurementIrDifferences(expected, actual, compareMapOrder = true).joinToString())
    }

    private fun assertExactCompiledMeasurementIr(expected: RigIR, actual: RigIR, label: String) {
        assertExactIr(expected, actual, label)
        // Baking can reconstruct unordered IR maps in a different iteration order. Compare every
        // field, map entry, ordered collection and raw float bit; do not normalize any metadata.
        assertEquals(emptyList(), measurementIrDifferences(expected, actual, compareMapOrder = false), label)
        val before = RigIrBinary.encode(expected)
        val after = RigIrBinary.encode(actual)
        if (!before.contentEquals(after)) {
            val order = measurementIrDifferences(expected, actual, compareMapOrder = true)
            assertTrue(order.isNotEmpty() && order.all { it.startsWith("map order ") },
                "$label has an unexplained binary difference: ${measurementByteDifference(before, after)}; $order")
            println("$label: ${order.joinToString()}")
        }
    }

    private fun measurementByteDifference(before: ByteArray, after: ByteArray): String {
        val first = (0 until minOf(before.size, after.size)).firstOrNull { before[it] != after[it] }
        return if (first == null) "lengths ${before.size}/${after.size}" else
            "first differing byte $first=${before[first].toInt() and 255}/${after[first].toInt() and 255}, lengths ${before.size}/${after.size}"
    }

    /** Bounded diagnostics; unordered maps retain exact entries and all ordered descendants. */
    private fun measurementIrDifferences(expected: RigIR, actual: RigIR, compareMapOrder: Boolean): List<String> {
        val differences = mutableListOf<String>()
        fun visit(path: String, left: Any?, right: Any?) {
            if (differences.size >= 12 || left === right) return
            if (left == null || right == null) { if (left != right) differences += path; return }
            when {
                left is Float && right is Float -> if (left.toRawBits() != right.toRawBits()) differences += "$path float bits"
                left is Double && right is Double -> if (left.toRawBits() != right.toRawBits()) differences += "$path double bits"
                left is FloatArray && right is FloatArray -> {
                    if (left.size != right.size) differences += "$path size"
                    else if (left.indices.any { left[it].toRawBits() != right[it].toRawBits() }) differences += "$path float bits"
                }
                left is IntArray && right is IntArray -> if (!left.contentEquals(right)) differences += path
                left is ByteArray && right is ByteArray -> if (!left.contentEquals(right)) differences += path
                left is Map<*, *> && right is Map<*, *> -> {
                    if (left.keys != right.keys) { differences += "$path keys"; return }
                    if (compareMapOrder && left.keys.toList() != right.keys.toList())
                        differences += "map order $path: ${left.keys.take(8)} -> ${right.keys.take(8)}"
                    for (key in left.keys) visit("$path[$key]", left[key], right[key])
                }
                left is List<*> && right is List<*> -> {
                    if (left.size != right.size) { differences += "$path size"; return }
                    for (index in left.indices) visit("$path[$index]", left[index], right[index])
                }
                left.javaClass != right.javaClass -> differences += "$path type"
                left.javaClass.name.startsWith("io.github.psd2live.format.model.") && left !is Enum<*> -> {
                    for (field in left.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }) {
                        field.isAccessible = true
                        visit("$path.${field.name}", field.get(left), field.get(right))
                    }
                }
                left != right -> differences += path
            }
        }
        visit("IR", expected, actual)
        return differences
    }

    /** Capture both paths independently, then send the same actual pipeline input to unchanged workers. */
    private fun verifySourceMeasurements(model: RigPreviewModel, label: String): Map<String, ModifierAlgorithmContext> {
        val source = RigModifierSource.of(model)
        val stack = RigModifierStack.fromJson(assertNotNull(model.config.rigEdits.modifierStack))
        val evaluation = assertNotNull(model.modifierEvaluation)
        val owners = stack.instances.filter { versions[it.type] == it.version }
        assertEquals(versions.keys, owners.map { it.type }.toSet(), "$label exercises all three source consumers")
        val root = Files.createTempDirectory("source-skin-measurements-")
        val preferences = Preferences.userRoot().node("source-skin-measurements/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        val captured = linkedMapOf<String, ModifierAlgorithmContext>()
        try {
            val artifacts = owners.map { assertNotNull(BundledAlgorithmArtifacts.find(it.type, it.version)) }
            BundledAlgorithmArtifacts.retain(store, artifacts)
            for ((owner, artifact) in owners.zip(artifacts)) {
                val name = "$label: ${owner.type}"
                val nativeDefinition = nativeCaptureDefinition(model, owner.type)
                val measuredDefinition = registeredMeasurementDefinition(source, owner.type)
                assertSame(nativeDefinition.plugin, measuredDefinition.plugin, "$name must keep the unchanged in-process worker")
                assertEquals(nativeDefinition.providers(owner), measuredDefinition.providers(owner), "$name provider resolution")
                val index = stack.index(owner.id)
                assertTrue(index >= 0, "$name must belong to the actual pipeline")
                assertEquals(ModifierStatus.ACTIVE, evaluation.entries[index].status, name)
                val observed = assertNotNull(evaluation.readAlgorithms[owner.id]).context
                val (native, nativeProfile) = profileSourceMeasurement { nativeDefinition.authorContext(owner) }
                val (measured, measuredProfile) = profileSourceMeasurement { measuredDefinition.authorContext(owner) }
                assertExactSourceContext(native, measured, "$name independent captures")
                assertTrue(native.resources.isNotEmpty(), "$name must capture real source bytes")
                val nativeMeasurements = assertNotNull(nativeProfile["context: mesh footprints + anchors"], name).second
                assertTrue(nativeMeasurements > 0, "$name native framing positive control")
                assertEquals(nativeMeasurements, nativeProfile["scaffold: deformers (built)"]?.second,
                    "$name native cache-off control must actually construct animated scaffolds")
                assertEquals(0L, nativeProfile["source skin: measurement contexts"]?.second ?: 0L,
                    "$name native control must not silently use the prototype")
                assertEquals(nativeMeasurements, measuredProfile["source skin: measurement contexts"]?.second,
                    "$name one source measurement per native framing")
                assertEquals(nativeMeasurements, measuredProfile["context: mesh footprints + anchors"]?.second,
                    "$name must really recompute cold source frames")
                assertEquals(nativeMeasurements, measuredProfile["source skin: static pair frames"]?.second,
                    "$name static identity pair grids are framing work, not animated scaffolds")
                val forbidden = measuredProfile.filter { (stage, value) -> value.second > 0 && (
                    stage == "scaffold: deformers (built)" || stage == "scaffold: face turn (built)" ||
                        stage.startsWith("modifier legacy") || stage.startsWith("modifier template consumer:") ||
                        stage.contains("lowering", ignoreCase = true) || stage.startsWith("binding:") ||
                        stage == "build: total" || stage.startsWith("assembly:") ||
                        stage == "mesh: layer mesh + keyforms (built)") }
                assertEquals(emptyMap(), forbidden, "$name must avoid animated scaffold, template and bound lowering work")

                // The evaluator owns capability aliases. Reuse the actual recorded envelope for both
                // captures, and independently verify each identity against the real active/applied prefix.
                for (upstream in observed.upstream) {
                    val provider = source.activeBefore(owner).single { it.id == upstream.instanceId }
                    assertEquals(ModifierUpstreamCapability(upstream.capability, provider.type, provider.version,
                        provider.targets, provider.id, provider.config, provider.outputAliases, provider.plugin), upstream, name)
                }
                val expectedContext = native.withUpstream(observed.upstream)
                val measuredContext = measured.withUpstream(observed.upstream)
                assertExactSourceContext(observed, expectedContext, "$name actual pipeline context")
                assertExactSourceContext(expectedContext, measuredContext, "$name worker envelopes")
                captured[owner.type] = measuredContext
                // A prefix-applied spring can be the first live stage; its authored input is captured
                // by the evaluator, not reconstructed or borrowed from the pre-apply pipeline.
                val before = evaluation.outputs.getOrNull(index - 1) ?: evaluation.authored
                val expected = evaluation.outputs[index]
                val algorithm = nativeDefinition.plugin as ReadingModifierAlgorithmPlugin
                assertExactMeasurementIr(expected, algorithm.evaluate(before, owner, expectedContext, {}), "$name native in-process")
                assertExactMeasurementIr(expected, algorithm.evaluate(before, owner, measuredContext, {}), "$name measured in-process")
                val installed = store.snapshot().single { it.reference == artifact.reference }
                val installedAlgorithm = installed.manifest.algorithms.single()
                assertEquals(owner.plugin, installed.reference, "$name exact published worker identity")
                assertEquals(nativeDefinition.requires, installedAlgorithm.requires)
                assertEquals(nativeDefinition.changeWrites, installedAlgorithm.writes)
                for ((mode, context) in listOf("native" to expectedContext, "measured" to measuredContext)) {
                    val output = ModifierPluginWorkers.evaluate(installed, installedAlgorithm, before, owner, {}, store,
                        context = context, declaredParameters = nativeDefinition.parameters(owner))
                    assertExactMeasurementIr(expected, output, "$name $mode installed JVM")
                }
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            try { preferences.removeNode() } finally { ModifierPluginStore.deleteTree(root) }
        }
        return captured
    }

    @Test fun composableSkinV5SurvivesActualPrefixApplyColdReplayAndArchive(): Unit = runBlocking {
        verifyPrefixApplyColdReplayAndArchive(case(cheekStrength = 80f, gloomStrength = 140f))
    }

    private suspend fun verifyPrefixApplyColdReplayAndArchive(case: Case) {
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

    @Test fun composableSkinV5RejectsChangedProviderIdentityAndSourceInputs(): Unit = runBlocking { verifySkinProviderAndInputGuards(false) }

    @Test fun sourceSkinMeasurementsRejectChangedProviderIdentityAndSourceInputs(): Unit = runBlocking { verifySkinProviderAndInputGuards(true) }

    private suspend fun verifySkinProviderAndInputGuards(prototype: Boolean) {
        val case = case(cheekStrength = 80f, gloomStrength = 140f)
        val evaluation = assertNotNull(case.actual.modifierEvaluation)
        for (entry in case.selected.instances.filter { it.type in versions }) {
            val definition = if (prototype) registeredMeasurementDefinition(RigModifierSource.of(case.actual), entry.type)
                else nativeCaptureDefinition(case.actual, entry.type)
            val algorithm = definition.plugin as ReadingModifierAlgorithmPlugin
            val index = case.selected.index(entry.id)
            val before = evaluation.outputs[index - 1]
            val after = evaluation.outputs[index]
            val recorded = assertNotNull(evaluation.readAlgorithms[entry.id]).context
            val context = definition.authorContext(entry).withUpstream(recorded.upstream)
            assertExactSourceContext(recorded, context, "Guard positive control, prototype=$prototype")
            assertEquals(after, algorithm.evaluate(before, entry, context, {}), "Positive control before identity guards")
            val canonical = context.upstream.map { it.copy(capability = it.type) }.distinct()
            val face = canonical.single { it.type == "face/fitted" }
            assertEquals(after, algorithm.evaluate(before, entry, context.withUpstream(canonical), {}))
            val faceAlias = context.withUpstream(canonical + face.copy(capability = "face/legacy"))
            if (entry.type == "mouth.spring") assertFailsWith<IllegalArgumentException> {
                algorithm.evaluate(before, entry, faceAlias, {})
            } else assertEquals(after, algorithm.evaluate(before, entry, faceAlias, {}), "Accept the real face capability alias")
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
            val spring = canonical.singleOrNull { it.type == "mouth.spring" }
            val changedProvider = spring ?: canonical.single { it.type == "mouth.jaw" }
            val wrongBinding = if (spring != null) spring.copy(config = JsonObject(spring.config + ("dependencies" to JsonObject(
                spring.config.getValue("dependencies").jsonObject + ("mouth.jaw" to JsonPrimitive("different-jaw"))))))
            else changedProvider.copy(config = JsonObject(changedProvider.config + ("base" to JsonPrimitive("different-mouth"))))
            val wrongProviders = canonical.map { if (it == changedProvider) wrongBinding else it }
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
            if (entry.type == "mouth.spring") continue
            val changedStrength = entry.copy(config = JsonObject(entry.config + ("values" to buildJsonObject { put("strength", 37f) })))
            val recaptured = definition.authorContext(changedStrength)
            assertEquals(context.values, recaptured.values, "Own strength is computed in the worker, not baked into source measurements")
            assertEquals(context.resources.keys, recaptured.resources.keys)
            for ((key, resource) in context.resources) assertContentEquals(resource.read(), recaptured.resources.getValue(key).read())
            assertNotEquals(after, algorithm.evaluate(before, changedStrength, context, {}))
        }
    }

    @Test fun composableSkinV5SuspendsMissingDisabledDuplicateOrWrongPackageBeforeCapture(): Unit = runBlocking { verifySkinProviderSuspension(false) }

    @Test fun sourceSkinMeasurementsSuspendInvalidProvidersBeforeCapture(): Unit = runBlocking { verifySkinProviderSuspension(true) }

    private suspend fun verifySkinProviderSuspension(prototype: Boolean) {
        val case = case()
        val evaluation = assertNotNull(case.actual.modifierEvaluation)
        val originalRegistry = RigModifierRegistry(BuiltinModifierPlugins.definitions(RigModifierSource.of(case.actual)))
        for (entry in case.selected.instances.filter { it.type in versions }) {
            val positiveDefinition = if (prototype) registeredMeasurementDefinition(RigModifierSource.of(case.actual), entry.type)
                else nativeCaptureDefinition(case.actual, entry.type)
            val recorded = assertNotNull(evaluation.readAlgorithms[entry.id]).context
            val positiveContext = positiveDefinition.authorContext(entry).withUpstream(recorded.upstream)
            assertExactSourceContext(recorded, positiveContext, "${entry.type} suspension positive control")
            val index = case.selected.index(entry.id)
            val before = evaluation.outputs[index - 1]
            assertExactMeasurementIr(evaluation.outputs[index], (positiveDefinition.plugin as ReadingModifierAlgorithmPlugin)
                .evaluate(before, entry, positiveContext, {}), "${entry.type} valid worker before suspension cases")
            val prefix = case.selected.instances.take(index)
            val applied = prefix.map { it.capability(provides = originalRegistry.definition(it).provides) }
            val ids = prefix.mapTo(hashSetOf()) { it.id }
            var captures = 0
            val sentinel = "Positive capture ${entry.type}"
            fun evaluate(stack: RigModifierStack): RigModifierResult {
                val source = case.actual.copy(config = case.actual.config.copy(rigEdits = case.actual.config.rigEdits.copy(modifierStack = stack.toJson())))
                val captureSource = RigModifierSource.of(source)
                val definitions = BuiltinModifierPlugins.definitions(captureSource).map { registered ->
                    val definition = when {
                        versions[registered.type] != registered.version -> registered
                        prototype -> registeredMeasurementDefinition(captureSource, registered.type)
                        else -> nativeCaptureDefinition(captureSource, registered.type)
                    }
                    if (definition.type != entry.type || definition.version != entry.version) definition else definition.copy(
                        authorContext = { captures++; error(sentinel) })
                }
                return RigModifierEvaluator(RigModifierRegistry(definitions)).evaluate(before, stack)
            }
            val positive = RigModifierStack(instances = listOf(entry), retiredIds = ids, appliedCapabilities = applied)
            assertEquals(sentinel, assertFailsWith<IllegalStateException> { evaluate(positive) }.message)
            assertEquals(1, captures)
            captures = 0
            for (required in nativeCaptureDefinition(case.actual, entry.type).requires) {
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
        val case = denseBlushCase()
        val cache = RigStageCache.enabled
        val replay = try { RigStageCache.enabled = false; WorkspacePreviewBuilder().build(case.document) }
            finally { RigStageCache.enabled = cache }
        compare(case.expected, replay, case.selected, "Cold real dense blush source")
        verifyInstalledWorkers(case)
    }

    @Test fun sourceSkinMeasurementsMatchDenseBlushAndInstalledWorkers(): Unit = runBlocking {
        verifySourceMeasurements(denseBlushCase().actual, "real dense blush carrier")
    }

    private suspend fun denseBlushCase(): Case {
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
        val (automatic, automaticModel) = WorkspacePresetInitialization.prepare(art.copy(source = stippled,
            settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits), WorkspaceRasterWork.Direct, choices)
        val automaticStack = RigModifierStack.fromJson(automatic.rigEdits.modifierStack!!)
        assertDefaults(automaticStack, emptySet(), versions)
        val historical = select(automaticStack, historicalVersions)
        val initialized = withStack(automatic, historical)
        val reference = WorkspacePreviewBuilder().build(initialized)
        compare(reference, automaticModel, automaticStack, "Real automatic dense blush source")
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
        return Case(withStack(initialized, selected), historical, selected, reference, actual)
    }

    @Test fun composableSkinV5RejectsAnonymousIntersectingAppliedProviders(): Unit = runBlocking { verifySkinAnonymousProviders(false) }

    @Test fun sourceSkinMeasurementsRejectAnonymousIntersectingAppliedProviders(): Unit = runBlocking { verifySkinAnonymousProviders(true) }

    private suspend fun verifySkinAnonymousProviders(prototype: Boolean) {
        val case = case()
        for (entry in case.selected.instances.filter { it.type in versions }) {
            fun selectedDefinition(model: RigPreviewModel) = if (prototype)
                registeredMeasurementDefinition(RigModifierSource.of(model), entry.type) else nativeCaptureDefinition(model, entry.type)
            val expected = selectedDefinition(case.actual).authorContext(entry)
            assertExactSourceContext(nativeCaptureDefinition(case.actual, entry.type).authorContext(entry), expected,
                "${entry.type} anonymous-provider positive control")
            fun capture(extra: ModifierCapability): ModifierAlgorithmContext {
                val stack = case.selected.copy(appliedCapabilities = case.selected.appliedCapabilities + extra)
                val changed = case.actual.copy(config = case.actual.config.copy(rigEdits = case.actual.config.rigEdits.copy(
                    modifierStack = stack.toJson())))
                return selectedDefinition(changed).authorContext(entry)
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
        val (automatic, _) = WorkspacePresetInitialization.prepare(art.copy(source = source,
            settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits), WorkspaceRasterWork.Direct, choices)
        val prepared = withStack(automatic, select(RigModifierStack.fromJson(automatic.rigEdits.modifierStack!!), historicalVersions))
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
