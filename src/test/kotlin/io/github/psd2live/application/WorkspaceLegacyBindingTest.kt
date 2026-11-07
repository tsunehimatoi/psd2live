package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.BrowLegacyV2Algorithm
import io.github.psd2live.algorithms.binding.BrowSpringAlgorithm
import io.github.psd2live.algorithms.binding.MouthFittedV2Algorithm
import io.github.psd2live.algorithms.binding.FittedFaceV2Algorithm
import io.github.psd2live.algorithms.binding.DepthTurnV2Algorithm
import io.github.psd2live.algorithms.binding.DepthTurnInputs
import io.github.psd2live.algorithms.binding.DepthTurnMotion
import io.github.psd2live.algorithms.binding.EyeSmileAlgorithm
import io.github.psd2live.algorithms.binding.EyeAftershockAlgorithm
import io.github.psd2live.algorithms.binding.EyeEmotionAlgorithm
import io.github.psd2live.algorithms.binding.EyeHighlightAlgorithm
import io.github.psd2live.algorithms.binding.MouthOutlineV2Algorithm
import io.github.psd2live.algorithms.binding.LegacyFaceV2Algorithm
import io.github.psd2live.algorithms.binding.LegacyBodyMotion
import io.github.psd2live.core.*
import io.github.psd2live.core.fit.FaceTurn
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.FeatureMove
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.model.Channel
import io.github.psd2live.format.model.ChannelValue
import io.github.psd2live.format.model.Floats
import io.github.psd2live.format.model.Ints
import io.github.psd2live.format.model.KeyAxis
import io.github.psd2live.format.model.KeyCell
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.Rgb
import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import io.github.psd2live.targets.cubism.PuppetIr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.math.abs
import kotlin.test.*

class WorkspaceLegacyBindingTest {
    private class Fixture(val document: WorkspaceDocument, val scaffoldVersion: Int = 2) {
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { doc, previous -> builder.build(doc, previous) })
        val commands = WorkspaceDocumentCommands(runtime)
        val now get() = runtime.capture()
        suspend fun install() { runtime.install(runtime.state.value.state, "source", document, builder.build(document)) }
        suspend fun edit(operation: String, request: JsonObject) = now.let {
            commands.execute(it.projectId, it.state, operation, listOf(WorkspaceDocumentOperation(operation, request)), MutationAuthor.USER)
        }
        fun addition(type: String, id: String, targets: List<ModifierTarget> = now.model.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) },
                     config: JsonObject = if (type == RigSourceModifiers.SCAFFOLD) JsonObject(emptyMap()) else buildJsonObject { put("scaffold", "structure") }) = buildJsonObject {
            put("type", type); put("id", id); put("config", config)
            if (type == RigSourceModifiers.SCAFFOLD) put("version", scaffoldVersion)
            put("targets", JsonArray(targets.map { buildJsonObject { put("kind", it.kind); put("id", it.id) } }))
        }
        suspend fun group(types: List<String>) = edit("modifier_add_group", buildJsonObject {
            put("group", "legacy")
            put("entries", JsonArray(listOf(addition(RigSourceModifiers.SCAFFOLD, "structure")) + types.map { type -> addition(type, type.substringBefore('/'), config = buildJsonObject {
                put("scaffold", "structure")
                val spec = RigLegacyBindings.spec(type)
                spec.module?.base?.let { put("base", it.substringBefore('/')) }
                if (spec.related.isNotEmpty()) putJsonObject("dependencies") { spec.related.forEach { put(it, it.substringBefore('/')) } }
            }) }))
        })
    }

    /** Small public procedural art with distinct eye, brow, mouth, hair and torso inputs. */
    private fun document(eyeDetails: Boolean = false, mouthDetails: Boolean = false, expressionDetails: Boolean = false, skinDetails: Boolean = false, outfitDetails: Boolean = false, sideLock: Boolean = false, bangOverlay: Boolean = false,
                         chestDetail: Boolean = false, pairedArms: Boolean = false, thinMouth: Boolean = false, pairedBrows: Boolean = false, bilateralEyes: Boolean = false,
                         closedMouth: Boolean = false, ribbonDetails: Boolean = false, creatureDetails: Boolean = false): WorkspaceDocument {
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
            Shape("blush", SemanticTag.FACE_DETAIL, Side.LEFT, 18, 31, 8, 3, PartRole.BLUSH),
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

    private fun native(document: WorkspaceDocument, recipe: RigRecipe = RigRecipe.Legacy): RigPreviewModel = PSD2LivePipeline().buildPreview(document.source,
        RigModifierPipeline.ordinaryConfig(document.config()).copy(meshOnly = false, generateDeformers = true, recipe = recipe))

    private fun fittedRecipe(choices: Map<String, BaseChoice>) = RigRecipe(true,
        bases = RigModules.areas.associate { it.id to (choices[it.id] ?: BaseChoice(it.legacy.id, it.legacy.version)) },
        modules = RigModules.modules.associate { it.id to ModuleChoice(false, it.version) })

    @Test fun fittedBrowWorkerMatchesNativeHeightAndHeadMotion(): Unit = runBlocking {
        val document = document()
        val artifact = requireNotNull(BundledAlgorithmArtifacts.find("brow/fitted", 2))
        val algorithm = artifact.manifest.algorithms.single()
        for (hasEye in listOf(false, true)) {
            val f = Fixture(document)
            f.install()
            f.group(listOf("body/legacy", "face/legacy") + if (hasEye) listOf("eye/fitted") else emptyList())
            val before = f.now.model.rig.puppet
            val brow = before.drawables.single { f.now.model.rig.layerIdByDrawableId[it.id.raw] == "brow" }
            f.edit("modifier_add", JsonObject(f.addition(algorithm.type, "brow") +
                ("version" to JsonPrimitive(2))))
            val actual = f.now.model
            val recipe = fittedRecipe(buildMap {
                put("brow", BaseChoice("brow/fitted"))
                if (hasEye) put("eye", BaseChoice("eye/fitted", 2))
            })
            val neutralHead = listOf(-1f, 0f, .45f, 1f).map { mapOf(StandardParameters.BROW_L_Y to it) }
            val native = native(document, recipe)
            val legacy = Fixture(document)
            legacy.install()
            legacy.group(listOf("body/legacy", "face/legacy") + if (hasEye) listOf("eye/fitted") else emptyList())
            legacy.edit("modifier_add", JsonObject(legacy.addition("brow/fitted", "brow") +
                ("version" to JsonPrimitive(1))))
            val turns = neutralHead.flatMap { pose -> listOf(pose,
                pose + mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f),
                pose + mapOf(StandardParameters.ANGLE_X to -25f, StandardParameters.ANGLE_Y to 20f))
            }
            val oldRig = RigIrCompiler.compile(legacy.now.model)
            val newRig = RigIrCompiler.compile(actual)
            assertEquals(oldRig.meshes.single { it.id == brow.id.raw }, newRig.meshes.single { it.id == brow.id.raw })
            val oldNodes = oldRig.deformers.associateBy { it.id }
            val newNodes = newRig.deformers.associateBy { it.id }
            for (parent in generateSequence(brow.parentDeformerId?.raw) { newNodes.getValue(it).parent })
                assertEquals(oldNodes.getValue(parent), newNodes.getValue(parent), parent)
            try { compare(legacy.now.model, actual, turns, targets = setOf(brow.id)) }
            catch (failure: AssertionError) { throw AssertionError("Mixed stack with fitted eye $hasEye: ${failure.message}", failure) }
            try { compare(native, actual, neutralHead, targets = setOf(brow.id)) }
            catch (failure: AssertionError) { throw AssertionError("Native rig with fitted eye $hasEye: ${failure.message}", failure) }
            val after = actual.rig.puppet.drawables.single { it.id == brow.id }
            assertEquals(brow.parentDeformerId, after.parentDeformerId)
            assertEquals(listOf(StandardParameters.BROW_L_Y), after.geometryGrid?.axes?.map { it.parameterId })
            val group = actual.rig.puppet.parameterTree.filterIsInstance<ParameterNode.Group>().single {
                it.children.contains(ParameterNode.Param(StandardParameters.BROW_L_Y))
            }
            val instance = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single { it.id == "brow" }
            assertEquals(instance.outputId("parameter-folder:ParamGroupBrows"), group.id.raw)
            assertEquals(RigIrCompiler.compile(actual), RigIrCompiler.compile(f.builder.build(f.now.document)))
        }
    }

    @Test fun fittedBrowPreservesStyledHeadTurnInMixedStack(): Unit = runBlocking {
        val document = document(pairedBrows = true)
        suspend fun bind(version: Int, bodyVersion: Int): RigPreviewModel {
            val f = Fixture(document)
            f.install()
            f.edit("modifier_add_group", buildJsonObject {
                put("group", "legacy")
                putJsonArray("entries") {
                    add(f.addition("rig.scaffold", "structure"))
                    add(JsonObject(f.addition("body/legacy", "body") + ("version" to JsonPrimitive(bodyVersion))))
                    add(f.addition("face/fitted", "face"))
                    add(f.addition("eye/fitted", "eye"))
                }
            })
            val before = f.now.model
            val face = before.rig.puppet.deformers.filterIsInstance<Deformer.Warp>().single { it.name == io.github.psd2live.i18n.tr("model.deformer.face") }
            assertTrue(face.geometryGrid!!.axes.single { it.parameterId == StandardParameters.ANGLE_X }.keys.size > 3)
            f.edit("modifier_add", JsonObject(f.addition("brow/fitted", "brow") + ("version" to JsonPrimitive(version))))
            val shape = f.addition("brow.shape", "shape", config = buildJsonObject {
                put("scaffold", "structure")
                put("base", "brow")
            })
            f.edit("modifier_add", shape)
            if (version == 2) {
                val beforeApply = RigIrCompiler.compile(f.now.model)
                f.edit("modifier_apply", buildJsonObject { put("id", "brow") })
                assertEquals(beforeApply, RigIrCompiler.compile(f.now.model))
            }
            return f.now.model
        }

        val legacy = RigIrCompiler.compile(bind(version = 1, bodyVersion = 1))
        assertEquals(legacy, RigIrCompiler.compile(bind(version = 2, bodyVersion = 1)))
        assertEquals(legacy, RigIrCompiler.compile(bind(version = 2, bodyVersion = 2)))
    }

    @Test fun fittedBrowV3MatchesCompleteRecommendedPresetAndItsInstalledWorker(): Unit = runBlocking {
        val art = document(pairedBrows = true, eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern")
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, baseline) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = true), preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val brow = stack.instances.single { it.type == "brow/fitted" }
        val face = stack.instances.single { it.type == "face/fitted" }
        val index = stack.index(brow.id)
        val expected = assertNotNull(baseline.modifierEvaluation).outputs
        fun select(instance: RigModifierInstance, version: Int) =
            instance.copy(version = version, plugin = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, version)).reference)
        fun withVersions(vararg replacements: RigModifierInstance): WorkspaceDocument {
            val byId = replacements.associateBy { it.id }
            val updated = stack.copy(instances = stack.instances.map { byId[it.id] ?: it })
            return initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = updated.toJson()))
        }

        val previous = select(brow, 2)
        val modern = select(brow, 3)
        val independentFace = select(face, 3)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(brow.type, 3))
        val previousModel = WorkspacePreviewBuilder().build(withVersions(previous))
        assertFaceEquals(expected[index], assertNotNull(previousModel.modifierEvaluation).outputs[index])
        assertFaceEquals(RigIrCompiler.compile(baseline), RigIrCompiler.compile(previousModel))
        assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(withVersions(previous, independentFace)) }
        val actual = WorkspacePreviewBuilder().build(withVersions(modern, independentFace))
        val evaluated = assertNotNull(actual.modifierEvaluation)
        assertFaceEquals(expected[index], evaluated.outputs[index])
        assertFaceEquals(RigIrCompiler.compile(baseline), RigIrCompiler.compile(actual))

        val directory = Files.createTempDirectory("fitted-brow-v3-")
        val preferences = Preferences.userRoot().node("fitted-brow-v3/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val installed = store.install(staging.toString())
            assertEquals(artifact.reference, installed.reference)
            val definition = RigBrowFittedModifier.modernDefinition(RigModifierSource.of(actual))
            val upstream = evaluated.stack.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = definition.authorContext(modern).withUpstream(upstream)
            assertFaceEquals(expected[index], ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                evaluated.outputs[index - 1], modern, {}, store, context = context,
                declaredParameters = definition.parameters(modern)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun fittedBrowRetainsAuthoredGeometryAfterHeadTurnIsApplied(): Unit = runBlocking {
        val f = Fixture(document())
        f.install()
        f.group(listOf("face/fitted", "eye/fitted"))
        f.edit("modifier_apply", buildJsonObject { put("id", "eye") })
        val brow = mesh(f, "brow")
        val baseline = requireNotNull(brow.geometryGrid)
        val key = key(f, baseline.axes)
        val neutral = baseline.cells.single { cell -> baseline.axes.indices.all { index ->
            baseline.axes[index].keys[cell.coordinate[index]] == key.getValue(baseline.axes[index].parameterId.raw).jsonPrimitive.float
        } }.form.positionDeltas
        f.edit("keyform_apply", buildJsonObject { putJsonArray("changes") { addJsonObject {
            put("op", "set"); put("target", "mesh:${brow.id.raw}"); put("key", key)
            putJsonObject("geometry") {
                put("positionDeltas", JsonArray(neutral.mapIndexed { index, value -> JsonPrimitive(value + if (index == 0) .0125f else 0f) }))
            }
        } } })
        val before = f.now.model.rig.puppet
        val poses = listOf(emptyMap(), mapOf(StandardParameters.ANGLE_X to 24f, StandardParameters.ANGLE_Y to -18f))
        val addition = f.addition("brow/fitted", "brow")
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        val after = f.now.model.rig.puppet
        val drawable = after.drawables.single { it.id == brow.id }
        assertEquals(brow.parentDeformerId, drawable.parentDeformerId)
        assertEquals(baseline.axes.map { it.parameterId } + StandardParameters.BROW_L_Y,
            drawable.geometryGrid?.axes?.map { it.parameterId })
        val evaluator = CpuDeformationEvaluator()
        for (pose in poses) {
            val original = evaluator.evaluate(before, pose).worldPositions.getValue(brow.id)
            val rest = evaluator.evaluate(after, pose).worldPositions.getValue(brow.id)
            assertTrue(original.indices.all { abs(original[it] - rest[it]) < .001f })
            val raised = evaluator.evaluate(after, pose + (StandardParameters.BROW_L_Y to 1f)).worldPositions.getValue(brow.id)
            assertTrue(raised.indices.any { abs(raised[it] - rest[it]) > .1f })
        }
        val compiled = RigIrCompiler.compile(f.now.model)
        assertEquals(compiled, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "brow") })
        assertEquals(compiled, RigIrCompiler.compile(f.now.model))
    }

    @Test fun browShapeWorkerMatchesLockedBindingAndReopensWithItsHistory(): Unit = runBlocking {
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("brow.shape", 2))
        for (head in listOf("face/legacy", "face/fitted")) {
            val f = Fixture(document(pairedBrows = head == "face/fitted"))
            f.install()
            f.group(listOf("body/legacy", head, "eye/fitted"))
            f.edit("modifier_add", JsonObject(f.addition("brow/fitted", "brow", config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("values") { put("lift", 67f) }
            }) + ("version" to JsonPrimitive(if (head == "face/legacy") 1 else 2))))
            val addition = f.addition("brow.shape", "shape", config = buildJsonObject {
                put("scaffold", "structure")
                put("base", "brow")
                putJsonObject("values") { put("strength", 138f) }
            })
            val before = f.now
            val reference = Fixture(before.document)
            reference.install()
            reference.edit("modifier_add", addition)
            val locked = RigModifierStack.fromJson(reference.now.document.rigEdits.modifierStack!!).instances.last()
            assertEquals(1, locked.version)
            val expected = RigIrCompiler.compile(reference.now.model)
            f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
            val after = f.now
            val actual = RigIrCompiler.compile(after.model)
            assertEquals(expected.parameters, actual.parameters)
            assertEquals(expected.parameterTree, actual.parameterTree)
            for ((old, next) in expected.meshes.zip(actual.meshes)) {
                assertEquals(old.offsets, next.offsets, old.id)
                assertEquals(old.shapes, next.shapes, old.id)
            }
            assertEquals(expected, actual)

            val stack = RigModifierStack.fromJson(after.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            assertEquals(artifact.reference, instance.plugin)
            val directory = Files.createTempDirectory("brow-shape-package-")
            val preferences = Preferences.userRoot().node("brow-shape-package/" + UUID.randomUUID())
            val store = ModifierPluginStore(directory.resolve("installed"), preferences)
            try {
                val staging = Files.createDirectory(directory.resolve("package"))
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                for (name in artifact.manifest.files.keys) {
                    assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                }
                val providers = stack.instances.dropLast(1).map { provider ->
                    ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                        provider.id, provider.config, provider.outputAliases, provider.plugin)
                }
                val adapter = RigBrowShapeModifier.definition(RigModifierSource.of(after.model))
                val context = adapter.authorContext(instance).withUpstream(providers)
                repeat(2) {
                    val installed = store.install(staging.toString())
                    assertEquals(artifact.reference, installed.reference)
                    val result = ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                        assertNotNull(before.model.rig.modifierOutput), instance, {}, store, context = context,
                        declaredParameters = adapter.parameters(instance))
                    assertEquals(after.model.rig.modifierOutput, result)
                    ModifierPluginWorkers.stop(installed.reference)
                    store.uninstall(installed.reference)
                    assertTrue(store.snapshot().none { it.reference == installed.reference })
                }
                val undone = f.runtime.checkout(after.projectId, after.state, before.historyHead)
                assertEquals(RigIrCompiler.compile(before.model), RigIrCompiler.compile(undone.model))
                val redone = f.runtime.checkout(after.projectId, undone.state, after.historyHead)
                assertEquals(actual, RigIrCompiler.compile(redone.model))
                val historyStore = WorkspaceStore(directory.resolve("project"))
                val archive = directory.resolve("brow-shape.psd2live")
                val repository = ProjectRepository(writeHeadCache = false)
                repository.save(ProjectSaveCapture(after.projectId, f.runtime.history(), JsonObject(emptyMap()), null, historyStore), archive)
                repository.open(archive).use { opened ->
                    val document = opened.history.head().snapshot
                    assertEquals(instance, RigModifierStack.fromJson(document.rigEdits.modifierStack!!).instances.last())
                    assertEquals(actual, RigIrCompiler.compile(WorkspacePreviewBuilder().build(document)))
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                preferences.removeNode()
                ModifierPluginStore.deleteTree(directory)
            }
            f.edit("modifier_apply", buildJsonObject { put("id", instance.id) })
            assertEquals(actual, RigIrCompiler.compile(f.now.model))
            assertEquals(actual, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
            if (head == "face/legacy") {
                val files = mutableMapOf<String, ByteArray>()
                io.github.psd2live.format.compile.Compiler.export(io.github.psd2live.targets.cubism.Cmo3Target(),
                    RigIrCompiler.compile(f.now.model, tileArt = true),
                    io.github.psd2live.format.compile.ExportOptions("brow-shape")) { path, bytes -> files[path] = bytes }
                val imported = Cmo3ModelImport.read(files.getValue("brow-shape.cmo3")).puppet
                val brow = imported.drawables.single { it.id == mesh(f, "brow").id }
                assertEquals(listOf("ParamBrowLY", "ParamBrowLAngle"), brow.geometryGrid?.axes?.map { it.parameterId.raw })
                assertEquals(setOf("ParamBrowLX", "ParamBrowLForm"), brow.blendShapes.map { it.parameterId.raw }.toSet())
            }
        }
    }

    @Test fun independentBrowBlinkMatchesLockedVersionWithWideSmileAndHeadTurn(): Unit = runBlocking {
        for (head in listOf("face/legacy", "face/fitted")) {
            val f = Fixture(document(pairedBrows = true, bilateralEyes = true))
            f.install()
            f.group(listOf("body/legacy", head, "eye/fitted", "brow/fitted"))
            fun config(base: String) = buildJsonObject { put("scaffold", "structure"); put("base", base) }
            if (head == "face/fitted") {
                for (type in listOf("eye.wide", "eye.smile")) {
                    val eye = f.addition(type, type, config = config("eye"))
                    f.edit("modifier_add", JsonObject(eye + ("version" to JsonPrimitive(2))))
                }
                val shape = f.addition("brow.shape", "shape", config = config("brow"))
                f.edit("modifier_add", JsonObject(shape + ("version" to JsonPrimitive(2))))
            }
            val prefix = f.now
            val previous = assertNotNull(prefix.model.rig.modifierOutput)
            val addition = f.addition("brow.blink", "blink", config = JsonObject(config("brow") + ("values" to buildJsonObject { put("dip", 158f) })))
            f.edit("modifier_add", addition)
            val locked = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last()
            assertEquals(1, locked.version)
            val legacy = f.now.model
            val expected = assertNotNull(legacy.rig.modifierOutput)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
            val current = f.now.model
            val actual = assertNotNull(current.rig.modifierOutput)
            assertEquals(expected.parameters, actual.parameters)
            assertEquals(expected.parameterTree, actual.parameterTree)
            for ((old, next) in expected.meshes.zip(actual.meshes)) assertEquals(old, next, old.id)
            assertEquals(expected, actual)
            assertEquals(RigIrCompiler.compile(legacy), RigIrCompiler.compile(current))
            val evaluator = CpuDeformationEvaluator()
            val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
                StandardParameters.EYE_L_OPEN to .36f, StandardParameters.EYE_R_OPEN to 1.7f,
                ParameterId("ParamEyeLSmile") to .67f, ParameterId("ParamBrowRAngle") to -.7f)
            val brow = mesh(f, "brow").id
            val oldPose = evaluator.evaluate(legacy.rig.puppet, pose)
            val newPose = evaluator.evaluate(current.rig.puppet, pose)
            assertContentEquals(oldPose.worldPositions.getValue(brow), newPose.worldPositions.getValue(brow))
            assertFalse(evaluator.evaluate(prefix.model.rig.puppet, pose).worldPositions.getValue(brow).contentEquals(newPose.worldPositions.getValue(brow)))

            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find("brow.blink", 2))
            assertEquals(artifact.reference, instance.plugin)
            if (head == "face/fitted") {
                val directory = Files.createTempDirectory("brow-blink-package-")
                val preferences = Preferences.userRoot().node("brow-blink-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val providers = stack.instances.dropLast(1).map { provider ->
                        ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                            provider.id, provider.config, provider.outputAliases, provider.plugin)
                    }
                    val definition = RigBrowBlinkModifier.definition(RigModifierSource.of(current))
                    repeat(2) {
                        val installed = store.install(staging.toString())
                        assertEquals(artifact.reference, installed.reference)
                        val algorithm = installed.manifest.algorithms.single()
                        val external = instance.copy(type = algorithm.type)
                        val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                            it.type == external.type && it.version == external.version
                        }
                        val context = adapter.authorContext(external).withUpstream(providers.filter {
                            it.capability in adapter.requires + adapter.optionalUpstream
                        })
                        assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, context) {})
                        ModifierPluginWorkers.stop(installed.reference)
                        store.uninstall(installed.reference)
                    }
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            val compiled = RigIrCompiler.compile(current)
            f.edit("modifier_apply", buildJsonObject { put("id", "blink") })
            assertEquals(compiled, RigIrCompiler.compile(f.now.model))
            assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentBrowBlinkPreservesTheRecommendedPreset(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), WorkspaceModifierPlan.RECOMMENDED_GROUP)
        val locked = stack.instances.single { it.type == "brow.blink" }
        assertEquals(1, locked.version)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 2))
        suspend fun preview(candidate: RigModifierStack) = builder.build(art.copy(
            rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()),
        ))
        val expected = preview(stack)
        val current = preview(stack.copy(instances = stack.instances.map {
            if (it.id == locked.id) it.copy(version = 2, plugin = artifact.reference) else it
        }))
        assertEquals(expected.rig.modifierOutput, current.rig.modifierOutput)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))
    }

    @Test fun independentMouthDepthMatchesLockedVersionWithHeadTurn(): Unit = runBlocking {
        for (head in listOf("face/fitted", "face/legacy")) {
            val f = Fixture(document(mouthDetails = true))
            f.install()
            f.group(listOf("body/legacy", head, "mouth/fitted"))
            if (head == "face/fitted") {
                f.edit("modifier_add", f.addition("mouth.capture", "capture", config = buildJsonObject {
                    put("scaffold", "structure"); put("base", "mouth")
                }))
                f.edit("modifier_add", f.addition("mouth.tongue", "tongue", config = buildJsonObject {
                    put("scaffold", "structure"); put("base", "mouth")
                    putJsonObject("dependencies") { put("mouth.capture", "capture") }
                }))
            }
            val prefix = f.now
            val addition = f.addition("mouth.interior-depth", "mouth-depth", config = buildJsonObject {
                put("scaffold", "structure"); put("base", "mouth")
                val provider = RigModifierStack.fromJson(prefix.document.rigEdits.modifierStack!!).instances.single { it.type == head }
                putJsonObject("dependencies") { put("face/legacy", provider.id) }
                putJsonObject("values") { put("depth", 143f) }
            })
            f.edit("modifier_add", addition)
            val expected = assertNotNull(f.now.model.rig.modifierOutput)
            val compiled = RigIrCompiler.compile(f.now.model)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
            val actual = assertNotNull(f.now.model.rig.modifierOutput)
            val tongue = mesh(f, "tongue").id.raw
            assertTrue(Channel.MULTIPLY_COLOR in actual.meshes.single { it.id == tongue }.channels)
            assertTrue(assertNotNull(prefix.model.rig.modifierOutput).meshes.single { it.id == tongue } != actual.meshes.single { it.id == tongue })
            for ((before, after) in expected.meshes.zip(actual.meshes)) {
                assertEquals(before.parent, after.parent, "$head parent for ${before.id}")
                assertEquals(before.channels, after.channels, "$head channels for ${before.id}")
                assertEquals(before.offsets?.axes, after.offsets?.axes, "$head axes for ${before.id}")
                assertEquals(before.offsets, after.offsets, "$head offsets for ${before.id}")
                assertEquals(before, after, "$head mesh ${before.id}")
            }
            assertEquals(expected, actual)
            assertEquals(compiled, RigIrCompiler.compile(f.now.model))
            assertTrue(actual.deformers.any { it.id.startsWith("modifier:mouth-depth:") })
            if (head == "face/fitted") {
                val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
                val instance = stack.instances.last()
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, 2))
                assertEquals(artifact.reference, instance.plugin)
                val directory = Files.createTempDirectory("mouth-depth-package-")
                val preferences = Preferences.userRoot().node("mouth-depth-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val providers = stack.instances.dropLast(1).flatMap { provider ->
                        val capabilities = if (provider.type == "face/fitted") listOf(provider.type, "face/legacy") else listOf(provider.type)
                        capabilities.map { capability -> ModifierUpstreamCapability(capability, provider.type, provider.version,
                            provider.targets, provider.id, provider.config, provider.outputAliases, provider.plugin) }
                    }
                    val definition = RigMouthDepthModifier.definition(RigModifierSource.of(f.now.model))
                    val installed = store.install(staging.toString())
                    val external = instance.copy(type = installed.manifest.algorithms.single().type)
                    val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                        it.type == external.type && it.version == external.version
                    }
                    val context = adapter.authorContext(external).withUpstream(providers.filter {
                        it.capability in adapter.requires + adapter.optionalUpstream
                    })
                    val previous = assertNotNull(prefix.model.rig.modifierOutput)
                    assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, context) {})
                    ModifierPluginWorkers.stop(installed.reference)
                    store.uninstall(installed.reference)
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            f.edit("modifier_apply", buildJsonObject { put("id", "mouth-depth") })
            assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentMouthCaptureMatchLockedVersionWithHeadTurnAndInstalledWorker(): Unit = runBlocking {
        for (head in listOf("face/legacy", "face/fitted")) {
            val f = Fixture(document(mouthDetails = true))
            f.install()
            f.group(listOf("body/legacy", head, "mouth/fitted"))
            if (head == "face/fitted") {
                val vowel = f.addition("mouth.vowel", "vowels", config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth") })
                f.edit("modifier_add", JsonObject(vowel + ("version" to JsonPrimitive(2))))
            }
            val prefix = f.now
            val previous = assertNotNull(prefix.model.rig.modifierOutput)
            val add = f.addition("mouth.capture", "capture", config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth") })
            f.edit("modifier_add", add)
            val legacy = f.now.model
            val expected = assertNotNull(legacy.rig.modifierOutput)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2))))
            val current = f.now.model
            val actual = assertNotNull(current.rig.modifierOutput)
            assertEquals(expected.parameters, actual.parameters)
            assertEquals(expected.parameterTree, actual.parameterTree)
            for ((before, after) in expected.meshes.zip(actual.meshes)) {
                assertEquals(before.shapes, after.shapes, before.id)
                assertEquals(before, after, before.id)
            }
            assertEquals(expected, actual)
            val compiled = RigIrCompiler.compile(current)
            assertEquals(RigIrCompiler.compile(legacy), compiled)
            val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
                StandardParameters.MOUTH_OPEN to .25f, StandardParameters.MOUTH_FORM to .44f,
                ParameterId("ParamMouthShrug") to .7f, ParameterId("ParamMouthFunnel") to .4f)
            val evaluator = CpuDeformationEvaluator()
            val mouth = mesh(f, "mouth").id
            val changed = evaluator.evaluate(current.rig.puppet, pose).worldPositions.getValue(mouth)
            assertContentEquals(evaluator.evaluate(legacy.rig.puppet, pose).worldPositions.getValue(mouth), changed)
            assertFalse(evaluator.evaluate(prefix.model.rig.puppet, pose).worldPositions.getValue(mouth).contentEquals(changed))

            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, 2))
            assertEquals(artifact.reference, instance.plugin)
            if (head == "face/fitted") {
                val directory = Files.createTempDirectory("mouth-capture-package-")
                val preferences = Preferences.userRoot().node("mouth-capture-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val providers = stack.instances.dropLast(1).map { provider ->
                        ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                            provider.id, provider.config, provider.outputAliases, provider.plugin)
                    }
                    val definition = RigMouthCaptureModifier.definition(RigModifierSource.of(current))
                    repeat(2) {
                        val installed = store.install(staging.toString())
                        assertEquals(artifact.reference, installed.reference)
                        val external = instance.copy(type = installed.manifest.algorithms.single().type)
                        val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                            it.type == external.type && it.version == external.version
                        }
                        val context = adapter.authorContext(external).withUpstream(providers.filter {
                            it.capability in adapter.requires + adapter.optionalUpstream
                        })
                        assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, context) {})
                        ModifierPluginWorkers.stop(installed.reference)
                        store.uninstall(installed.reference)
                    }
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            f.edit("modifier_apply", buildJsonObject { put("id", "capture") })
            assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentMouthTongueMatchesLockedVersionWithHeadTurnAndInstalledWorker(): Unit = runBlocking {
        for (head in listOf("face/legacy", "face/fitted")) {
            val f = Fixture(document(mouthDetails = true))
            f.install()
            f.group(listOf("body/legacy", head, "mouth/fitted"))
            if (head == "face/fitted") {
                val vowel = f.addition("mouth.vowel", "vowels", config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth") })
                f.edit("modifier_add", JsonObject(vowel + ("version" to JsonPrimitive(2))))
            }
            val capture = f.addition("mouth.capture", "capture", config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth") })
            f.edit("modifier_add", if (head == "face/fitted") JsonObject(capture + ("version" to JsonPrimitive(2))) else capture)
            val prefix = f.now
            val previous = assertNotNull(prefix.model.rig.modifierOutput)
            val add = f.addition("mouth.tongue", "tongue-out", config = buildJsonObject {
                put("scaffold", "structure"); put("base", "mouth")
                putJsonObject("dependencies") { put("mouth.capture", "capture") }
            })
            f.edit("modifier_add", add)
            assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
            val legacy = f.now.model
            val expected = assertNotNull(legacy.rig.modifierOutput)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2))))
            val current = f.now.model
            val actual = assertNotNull(current.rig.modifierOutput)
            assertEquals(expected.parameters, actual.parameters)
            assertTrue(previous.parameters.none { it.id == "ParamTongueOut" })
            assertTrue(actual.meshes.single { it.name == "tongue" }.shapes.any { it.parameter == "ParamTongueOut" })
            assertEquals(expected.parameterTree, actual.parameterTree)
            for ((before, after) in expected.meshes.zip(actual.meshes)) {
                assertEquals(before.shapes, after.shapes, before.id)
                assertEquals(before, after, before.id)
            }
            assertEquals(expected, actual)
            val compiled = RigIrCompiler.compile(current)
            assertEquals(RigIrCompiler.compile(legacy), compiled)
            val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
                StandardParameters.MOUTH_OPEN to .25f, StandardParameters.MOUTH_FORM to .44f,
                ParameterId("ParamTongueOut") to .7f)
            val evaluator = CpuDeformationEvaluator()
            val mouth = mesh(f, "tongue").id
            val changed = evaluator.evaluate(current.rig.puppet, pose).worldPositions.getValue(mouth)
            assertContentEquals(evaluator.evaluate(legacy.rig.puppet, pose).worldPositions.getValue(mouth), changed)
            assertFalse(evaluator.evaluate(prefix.model.rig.puppet, pose).worldPositions.getValue(mouth).contentEquals(changed))

            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, 2))
            assertEquals(artifact.reference, instance.plugin)
            if (head == "face/fitted") {
                val directory = Files.createTempDirectory("mouth-tongue-package-")
                val preferences = Preferences.userRoot().node("mouth-tongue-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val providers = stack.instances.dropLast(1).map { provider ->
                        ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                            provider.id, provider.config, provider.outputAliases, provider.plugin)
                    }
                    val definition = RigMouthTongueModifier.definition(RigModifierSource.of(current))
                    repeat(2) {
                        val installed = store.install(staging.toString())
                        assertEquals(artifact.reference, installed.reference)
                        val external = instance.copy(type = installed.manifest.algorithms.single().type)
                        val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                            it.type == external.type && it.version == external.version
                        }
                        val context = adapter.authorContext(external).withUpstream(providers.filter {
                            it.capability in adapter.requires + adapter.optionalUpstream
                        })
                        assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, context) {})
                        ModifierPluginWorkers.stop(installed.reference)
                        store.uninstall(installed.reference)
                    }
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            f.edit("modifier_apply", buildJsonObject { put("id", "tongue-out") })
            assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentMouthSpringPreservesFullModernPreset(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), "modern-full")
        val locked = stack.instances.single { it.type == "mouth.spring" }
        assertEquals(1, locked.version)
        assertTrue(stack.instances.takeWhile { it != locked }.any { it.type == "mouth.cheek-lid" })
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 3))
        fun withStack(candidate: RigModifierStack) = art.copy(rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()))
        val expected = builder.build(withStack(stack))
        val upgraded = stack.copy(instances = stack.instances.map {
            if (it.id == locked.id) it.copy(version = 3, plugin = artifact.reference) else it
        })
        val current = builder.build(withStack(upgraded))
        val before = assertNotNull(expected.rig.modifierOutput)
        val after = assertNotNull(current.rig.modifierOutput)
        assertTrue(before.meshes.any { mesh -> mesh.shapes.any { it.parameter == "ParamMouthSpring" } })
        for ((legacy, independent) in before.meshes.zip(after.meshes)) {
            assertEquals(legacy.shapes.map { it.parameter }, independent.shapes.map { it.parameter }, legacy.id)
            assertEquals(legacy, independent, legacy.id)
        }
        assertEquals(before, after)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))

        val prefix = stack.instances.takeWhile { it != locked }
        val input = assertNotNull(builder.build(withStack(stack.copy(instances = prefix))).rig.modifierOutput)
        val output = assertNotNull(builder.build(withStack(stack.copy(instances = prefix + locked))).rig.modifierOutput)
        val directory = Files.createTempDirectory("mouth-spring-v3-package-")
        val preferences = Preferences.userRoot().node("mouth-spring-v3-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val installed = store.install(staging.toString())
            assertEquals(artifact.reference, installed.reference)
            val definition = RigMouthSpringModifier.fullDefinition(RigModifierSource.of(current))
            val instance = locked.copy(type = installed.manifest.algorithms.single().type, version = 3, plugin = installed.reference)
            val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single { it.type == instance.type }
            val providers = prefix.filter { it.type in adapter.requires + adapter.optionalUpstream }.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = adapter.authorContext(instance).withUpstream(providers)
            assertEquals(output, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(input, instance, context) {})
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun independentMouthSpringMatchesLegacyAndInstalledWorker(): Unit = runBlocking {
        data class Mode(val head: String, val physics: Boolean, val capture: Boolean = false)
        for ((head, physics, capture) in listOf(Mode("face/legacy", true), Mode("face/fitted", false, capture = true))) {
            val f = Fixture(document(mouthDetails = true))
            f.install()
            val mouth = if (capture) listOf("mouth.outline", "mouth/fitted", "mouth.vowel", "mouth.capture") else listOf("mouth/fitted")
            f.group(listOf("body/legacy", head) + mouth)
            val targets = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.first().targets
            val jawEntry = f.addition("mouth.jaw", "jaw", targets, buildJsonObject {
                put("scaffold", "structure"); put("base", "mouth")
            })
            f.edit("modifier_add", JsonObject(jawEntry + ("version" to JsonPrimitive(if (capture) 2 else 1))))
            val prefix = f.now
            val previous = assertNotNull(prefix.model.rig.modifierOutput)
            val providers = RigModifierStack.fromJson(prefix.document.rigEdits.modifierStack!!).instances
            val jaw = providers.single { it.type == "mouth.jaw" }
            val scaffold = providers.single { it.type == RigSourceModifiers.SCAFFOLD }
            val add = f.addition("mouth.spring", "spring", targets = scaffold.targets, config = buildJsonObject {
                put("scaffold", "structure")
                put("base", "mouth")
                put("dependencies", buildJsonObject { put("mouth.jaw", jaw.id) })
                put("physicsEnabled", physics)
            })
            f.edit("modifier_add", add)
            val legacy = f.now.model
            val expected = assertNotNull(legacy.rig.modifierOutput)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2))))
            val current = f.now.model
            val actual = assertNotNull(current.rig.modifierOutput)
            assertEquals(expected.parameters, actual.parameters)
            assertEquals(expected.parameterTree, actual.parameterTree)
            for ((before, after) in expected.meshes.zip(actual.meshes)) {
                assertEquals(before.shapes, after.shapes, before.id)
                assertEquals(before, after, before.id)
            }
            assertEquals(expected, actual)
            val compiled = RigIrCompiler.compile(current)
            assertEquals(RigIrCompiler.compile(legacy), compiled)
            val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
                StandardParameters.MOUTH_OPEN to .25f, StandardParameters.MOUTH_FORM to .44f,
                ParameterId("ParamMouthSpring") to .7f)
            val evaluator = CpuDeformationEvaluator()
            val affected = actual.meshes.filter { mesh -> mesh.shapes.any { it.parameter == "ParamMouthSpring" } }
            assertTrue(affected.isNotEmpty(), f.now.model.modifierEvaluation?.entries?.joinToString {
                "${it.instance.id}:${it.status}:${it.missingDependencies}"
            } ?: "No modifier evaluation")
            val changed = evaluator.evaluate(current.rig.puppet, pose).worldPositions
            val baseline = evaluator.evaluate(legacy.rig.puppet, pose).worldPositions
            for (mesh in affected) assertContentEquals(baseline.getValue(DrawableId(mesh.id)), changed.getValue(DrawableId(mesh.id)))

            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, 2))
            assertEquals(artifact.reference, instance.plugin)
            assertEquals(physics, actual.physics.groups.any { it.id == instance.outputId("physics:PhysicsMouthSpring") })
            if (physics) {
                val directory = Files.createTempDirectory("mouth-spring-package-")
                val preferences = Preferences.userRoot().node("mouth-spring-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val providers = stack.instances.dropLast(1).map { provider ->
                        ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                            provider.id, provider.config, provider.outputAliases, provider.plugin)
                    }
                    val definition = RigMouthSpringModifier.definition(RigModifierSource.of(current))
                    val installed = store.install(staging.toString())
                    assertEquals(artifact.reference, installed.reference)
                    val external = instance.copy(type = installed.manifest.algorithms.single().type)
                    val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                        it.type == external.type && it.version == external.version
                    }
                    val context = adapter.authorContext(external).withUpstream(providers.filter {
                        it.capability in adapter.requires + adapter.optionalUpstream
                    })
                    assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, context) {})
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            f.edit("modifier_apply", buildJsonObject { put("id", "spring") })
            assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentMouthCapturePreservesPrecedingOutlineAndVowels(): Unit = runBlocking {
        for (head in listOf("face/legacy", "face/fitted")) {
            val f = Fixture(document(mouthDetails = true))
            f.install()
            f.group(listOf("body/legacy", head, "mouth.outline", "mouth/fitted", "mouth.vowel"))
            val prefix = f.now
            val outline = RigModifierStack.fromJson(prefix.document.rigEdits.modifierStack!!).instances.single { it.type == "mouth.outline" }
            val owner = mesh(f, "mouth").id.raw
            val ribbons = (0..1).map { outline.outputId("mouth-lip:$owner:$it") }
            val before = assertNotNull(prefix.model.rig.modifierOutput)
            assertTrue(ribbons.all { id -> before.meshes.any { it.id == id } })
            val add = f.addition("mouth.capture", "capture", targets = outline.targets, config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth") })
            f.edit("modifier_add", add)
            val expected = f.now.model
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2))))
            val current = f.now.model
            assertEquals(expected.rig.modifierOutput, current.rig.modifierOutput)
            assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))
        }
    }

    @Test fun independentMouthCaptureRejectsSeparateClosedDrawingAtomically(): Unit = runBlocking {
        val f = Fixture(document(mouthDetails = true, closedMouth = true))
        f.install()
        f.group(listOf("body/legacy", "face/legacy", "mouth/fitted"))
        val prefix = f.now
        val add = f.addition("mouth.capture", "capture", config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth") })
        assertFails { f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2)))) }
        assertEquals(prefix.historyHead, f.now.historyHead)
        assertEquals(prefix.document, f.now.document)
        f.edit("modifier_add", add)
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
    }

    @Test fun independentMouthCapturePreservesRecommendedPreset(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), WorkspaceModifierPlan.RECOMMENDED_GROUP)
        val locked = stack.instances.single { it.type == "mouth.capture" }
        assertEquals(1, locked.version)
        assertTrue(stack.instances.takeWhile { it.id != locked.id }.any { it.type == "mouth.vowel" })
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 2))
        suspend fun preview(candidate: RigModifierStack) = builder.build(art.copy(
            rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()),
        ))
        val expected = preview(stack)
        val current = preview(stack.copy(instances = stack.instances.map {
            if (it.id == locked.id) it.copy(version = 2, plugin = artifact.reference) else it
        }))
        assertEquals(expected.rig.modifierOutput, current.rig.modifierOutput)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))
    }

    @Test fun independentMouthJawMatchLockedVersionWithHeadTurnAndInstalledWorker(): Unit = runBlocking {
        for (head in listOf("face/legacy", "face/fitted")) {
            val f = Fixture(document(mouthDetails = true))
            f.install()
            f.group(listOf("body/legacy", head, "mouth/fitted", "mouth.outline", "mouth.vowel", "mouth.capture"))
            val prefix = f.now
            val previous = assertNotNull(prefix.model.rig.modifierOutput)
            val targets = RigModifierStack.fromJson(prefix.document.rigEdits.modifierStack!!).instances.first().targets
            val add = f.addition("mouth.jaw", "jaw", targets, config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth"); putJsonObject("values") { put("share", 22f) } })
            f.edit("modifier_add", add)
            val legacy = f.now.model
            val expected = assertNotNull(legacy.rig.modifierOutput)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2))))
            val current = f.now.model
            val actual = assertNotNull(current.rig.modifierOutput)
            val report = assertNotNull(current.rig.modifierReport).getValue("entries").jsonArray.last().jsonObject
            assertEquals("active", report.getValue("status").jsonPrimitive.content, report.toString())
            assertEquals(expected.parameters, actual.parameters)
            assertEquals(expected.parameterTree, actual.parameterTree)
            for ((before, after) in expected.meshes.zip(actual.meshes)) {
                assertEquals(before.shapes, after.shapes, before.id)
                assertEquals(before, after, before.id)
            }
            assertEquals(expected, actual)
            val compiled = RigIrCompiler.compile(current)
            assertEquals(RigIrCompiler.compile(legacy), compiled)
            val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
                StandardParameters.MOUTH_OPEN to .25f, StandardParameters.MOUTH_FORM to .44f,
                ParameterId("ParamA") to .7f, ParameterId("ParamU") to .4f)
            val evaluator = CpuDeformationEvaluator()
            val mouth = mesh(f, "face").id
            val changed = evaluator.evaluate(current.rig.puppet, pose).worldPositions.getValue(mouth)
            assertContentEquals(evaluator.evaluate(legacy.rig.puppet, pose).worldPositions.getValue(mouth), changed)
            assertFalse(evaluator.evaluate(prefix.model.rig.puppet, pose).worldPositions.getValue(mouth).contentEquals(changed))

            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, 2))
            assertEquals(artifact.reference, instance.plugin)
            if (head == "face/fitted") {
                val directory = Files.createTempDirectory("mouth-jaw-package-")
                val preferences = Preferences.userRoot().node("mouth-jaw-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val providers = stack.instances.dropLast(1).map { provider ->
                        ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                            provider.id, provider.config, provider.outputAliases, provider.plugin)
                    }
                    val definition = RigMouthJawModifier.definition(RigModifierSource.of(current))
                    repeat(2) {
                        val installed = store.install(staging.toString())
                        assertEquals(artifact.reference, installed.reference)
                        val external = instance.copy(type = installed.manifest.algorithms.single().type)
                        val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                            it.type == external.type && it.version == external.version
                        }
                        val context = adapter.authorContext(external).withUpstream(providers.filter {
                            it.capability in adapter.requires + adapter.optionalUpstream
                        })
                        assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, context) {})
                        ModifierPluginWorkers.stop(installed.reference)
                        store.uninstall(installed.reference)
                    }
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            f.edit("modifier_apply", buildJsonObject { put("id", "jaw") })
            assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentMouthJawRejectsEarlierTongueModuleAtomically(): Unit = runBlocking {
        val f = Fixture(document(mouthDetails = true))
        f.install()
        f.group(listOf("face/legacy", "mouth/fitted", "mouth.capture", "mouth.tongue"))
        val prefix = f.now
        val targets = RigModifierStack.fromJson(prefix.document.rigEdits.modifierStack!!).instances.first().targets
        val add = f.addition("mouth.jaw", "jaw", targets, config = buildJsonObject {
            put("scaffold", "structure"); put("base", "mouth")
        })
        assertFails { f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2)))) }
        assertEquals(prefix.historyHead, f.now.historyHead)
        assertEquals(prefix.document, f.now.document)
    }

    @Test fun independentMouthVowelsMatchLockedVersionWithHeadTurnAndInstalledWorker(): Unit = runBlocking {
        for (head in listOf("face/legacy", "face/fitted")) {
            val f = Fixture(document(mouthDetails = true))
            f.install()
            f.group(listOf("body/legacy", head, "mouth/fitted"))
            val prefix = f.now
            val previous = assertNotNull(prefix.model.rig.modifierOutput)
            val add = f.addition("mouth.vowel", "vowels", config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth") })
            f.edit("modifier_add", add)
            val legacy = f.now.model
            val expected = assertNotNull(legacy.rig.modifierOutput)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2))))
            val current = f.now.model
            val actual = assertNotNull(current.rig.modifierOutput)
            assertEquals(expected.parameters, actual.parameters)
            assertEquals(expected.parameterTree, actual.parameterTree)
            for ((before, after) in expected.meshes.zip(actual.meshes)) {
                assertEquals(before.shapes, after.shapes, before.id)
                assertEquals(before, after, before.id)
            }
            assertEquals(expected, actual)
            val compiled = RigIrCompiler.compile(current)
            assertEquals(RigIrCompiler.compile(legacy), compiled)
            val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
                StandardParameters.MOUTH_OPEN to .25f, StandardParameters.MOUTH_FORM to .44f,
                ParameterId("ParamA") to .7f, ParameterId("ParamU") to .4f)
            val evaluator = CpuDeformationEvaluator()
            val mouth = mesh(f, "mouth").id
            val changed = evaluator.evaluate(current.rig.puppet, pose).worldPositions.getValue(mouth)
            assertContentEquals(evaluator.evaluate(legacy.rig.puppet, pose).worldPositions.getValue(mouth), changed)
            assertFalse(evaluator.evaluate(prefix.model.rig.puppet, pose).worldPositions.getValue(mouth).contentEquals(changed))

            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(instance.type, 2))
            assertEquals(artifact.reference, instance.plugin)
            if (head == "face/fitted") {
                val directory = Files.createTempDirectory("mouth-vowel-package-")
                val preferences = Preferences.userRoot().node("mouth-vowel-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val providers = stack.instances.dropLast(1).map { provider ->
                        ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                            provider.id, provider.config, provider.outputAliases, provider.plugin)
                    }
                    val definition = RigMouthVowelModifier.definition(RigModifierSource.of(current))
                    repeat(2) {
                        val installed = store.install(staging.toString())
                        assertEquals(artifact.reference, installed.reference)
                        val external = instance.copy(type = installed.manifest.algorithms.single().type)
                        val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                            it.type == external.type && it.version == external.version
                        }
                        val context = adapter.authorContext(external).withUpstream(providers.filter {
                            it.capability in adapter.requires + adapter.optionalUpstream
                        })
                        assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, context) {})
                        ModifierPluginWorkers.stop(installed.reference)
                        store.uninstall(installed.reference)
                    }
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            f.edit("modifier_apply", buildJsonObject { put("id", "vowels") })
            assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentMouthVowelsRejectASeparateClosedDrawingAtomically(): Unit = runBlocking {
        val f = Fixture(document(mouthDetails = true, closedMouth = true))
        f.install()
        f.group(listOf("body/legacy", "face/legacy", "mouth/fitted"))
        val prefix = f.now
        val add = f.addition("mouth.vowel", "vowels", config = buildJsonObject { put("scaffold", "structure"); put("base", "mouth") })
        assertFails { f.edit("modifier_add", JsonObject(add + ("version" to JsonPrimitive(2)))) }
        assertEquals(prefix.historyHead, f.now.historyHead)
        assertEquals(prefix.document, f.now.document)
        f.edit("modifier_add", add)
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
    }

    @Test fun independentCheekPreservesRecommendedPreset(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), WorkspaceModifierPlan.RECOMMENDED_GROUP)
        val locked = stack.instances.single { it.type == "expression.cheek" }
        assertEquals(1, locked.version)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 2))
        fun withStack(candidate: RigModifierStack) = art.copy(rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()))
        val expected = builder.build(withStack(stack))
        val upgraded = stack.copy(instances = stack.instances.map {
            if (it.id == locked.id) it.copy(version = 2, plugin = artifact.reference) else it
        })
        val current = builder.build(withStack(upgraded))
        val report = assertNotNull(current.rig.modifierReport).getValue("entries").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == locked.id }.jsonObject
        assertEquals("active", report.getValue("status").jsonPrimitive.content, report.toString())
        val before = assertNotNull(expected.rig.modifierOutput)
        val after = assertNotNull(current.rig.modifierOutput)
        assertEquals(before.parameterTree, after.parameterTree)
        for ((original, upgradedMesh) in before.meshes.zip(after.meshes)) {
            assertEquals(original, upgradedMesh, original.id)
        }
        assertEquals(before, after)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))

        val prefix = stack.instances.takeWhile { it.id != locked.id }
        val preceding = assertNotNull(builder.build(withStack(stack.copy(instances = prefix))).rig.modifierOutput)
        val legacy = assertNotNull(builder.build(withStack(stack.copy(instances = prefix + locked))).rig.modifierOutput)
        val instance = upgraded.instances.single { it.id == locked.id }
        val definition = RigCheekModifier.definition(RigModifierSource.of(current))
        val directory = Files.createTempDirectory("cheek-package-")
        val preferences = Preferences.userRoot().node("cheek-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val installed = store.install(staging.toString())
            assertEquals(instance.plugin, installed.reference)
            val external = instance.copy(type = installed.manifest.algorithms.single().type)
            val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                it.type == external.type && it.version == external.version
            }
            val providers = prefix.filter { it.type in adapter.requires }.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = adapter.authorContext(external).withUpstream(providers)
            assertTrue(legacy.meshes.any { mesh -> mesh.channels.values.any { grid -> grid.axes.any { it.parameter == "ParamCheek" } } })
            assertEquals(legacy, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(preceding, external, context) {})
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun independentMouthJawPreservesRecommendedPreset(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), WorkspaceModifierPlan.RECOMMENDED_GROUP)
        val locked = stack.instances.single { it.type == "mouth.jaw" }
        assertEquals(1, locked.version)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 2))
        fun withStack(candidate: RigModifierStack) = art.copy(rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()))
        val expected = builder.build(withStack(stack))
        val upgraded = stack.copy(instances = stack.instances.map {
            if (it.id == locked.id) it.copy(version = 2, plugin = artifact.reference) else it
        })
        val current = builder.build(withStack(upgraded))
        val before = assertNotNull(expected.rig.modifierOutput)
        val after = assertNotNull(current.rig.modifierOutput)
        for ((original, upgradedMesh) in before.meshes.zip(after.meshes)) {
            assertEquals(original.offsets?.axes, upgradedMesh.offsets?.axes, original.id)
            assertEquals(original.shapes.map { it.parameter }, upgradedMesh.shapes.map { it.parameter }, original.id)
            assertEquals(original, upgradedMesh, original.id)
        }
        assertEquals(before, after)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))
    }

    @Test fun fittedVowelsReplayAfterIndependentOutline(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val choices = WorkspaceInitialPreset()
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, model) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct, choices,
            preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val vowel = stack.instances.single { it.type == "mouth.vowel" }
        val outline = stack.instances.single { it.type == "mouth.outline" }
        val index = stack.index(vowel.id)
        assertTrue(stack.index(outline.id) < index)
        fun locked(outlineVersion: Int, vowelVersion: Int): RigModifierStack = stack.copy(instances = stack.instances.map {
            val version = when (it.id) {
                outline.id -> outlineVersion
                vowel.id -> vowelVersion
                else -> return@map it
            }
            it.copy(version = version, plugin = assertNotNull(BundledAlgorithmArtifacts.find(it.type, version)).reference)
        })
        fun replay(candidate: RigModifierStack) = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = candidate.toJson()))
        assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(replay(locked(3, 2))) }
        val replay = WorkspacePreviewBuilder().build(replay(locked(3, 3)))
        val output = assertNotNull(model.modifierEvaluation).outputs[index]
        val evaluated = assertNotNull(replay.modifierEvaluation)
        assertEquals(output, evaluated.outputs[index])
        assertEquals(RigIrCompiler.compile(model, tileArt = true), RigIrCompiler.compile(replay, tileArt = true))

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(vowel.type, 3))
        val directory = Files.createTempDirectory("mouth-vowel-v3-package-")
        val preferences = Preferences.userRoot().node("mouth-vowel-v3-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val installed = store.install(staging.toString())
            val entry = evaluated.entries[index].instance.copy(type = installed.manifest.algorithms.single().type)
            val definition = RigMouthVowelModifier.outlineDefinition(RigModifierSource.of(replay))
            val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                it.type == entry.type && it.version == entry.version
            }
            val context = assertNotNull(evaluated.readAlgorithms[entry.id]).context
            assertEquals(output, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(
                evaluated.outputs[index - 1], entry, context) {})
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun independentMouthVowelsPreserveRecommendedPresetAndNaturalTalk(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), WorkspaceModifierPlan.RECOMMENDED_GROUP)
        val locked = stack.instances.single { it.type == "mouth.vowel" }
        assertEquals(1, locked.version)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 2))
        fun withStack(candidate: RigModifierStack) = art.copy(rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()))
        val expected = builder.build(withStack(stack))
        val upgraded = stack.copy(instances = stack.instances.map {
            if (it.id == locked.id) it.copy(version = 2, plugin = artifact.reference) else it
        })
        val current = builder.build(withStack(upgraded))
        assertEquals(expected.rig.modifierOutput, current.rig.modifierOutput)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))
        fun speech(model: RigPreviewModel, candidate: RigModifierStack): RigModifierStack {
            val request = buildJsonObject {
                put("type", "motion"); put("preset", "natural:Talk")
                putJsonArray("targets") {
                    locked.targets.forEach { target -> addJsonObject { put("kind", target.kind); put("id", target.id) } }
                }
            }
            return WorkspaceModifierPlan.plan(model, candidate, request).first
        }
        val oldSpeech = RigIrCompiler.compile(builder.build(withStack(speech(expected, stack))))
        val newSpeech = RigIrCompiler.compile(builder.build(withStack(speech(current, upgraded))))
        assertEquals(MotionGenerator.talkTracks.map { it.parameterId }.toSet(), newSpeech.clips.single().curves.map { it.parameter }.toSet())
        assertEquals(oldSpeech, newSpeech)
    }

    @Test fun browShapeV2RejectsEditedHeightGridAtomically(): Unit = runBlocking {
        val f = Fixture(document())
        f.install()
        f.group(listOf("face/legacy", "brow/fitted"))
        val brow = mesh(f, "brow")
        val height = assertNotNull(brow.geometryGrid)
        val offsets = height.cells.first().form.positionDeltas.map { JsonPrimitive(it + .01f) }
        f.edit("keyform_apply", buildJsonObject { putJsonArray("changes") { addJsonObject {
            put("op", "set")
            put("target", "mesh:${brow.id.raw}")
            putJsonObject("key") { put("ParamBrowLY", -1f) }
            putJsonObject("geometry") { put("positionDeltas", JsonArray(offsets)) }
        } } })
        val before = f.now
        val error = assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_add", JsonObject(f.addition("brow.shape", "shape", config = buildJsonObject {
                put("scaffold", "structure")
                put("base", "brow")
            }) + ("version" to JsonPrimitive(2))))
        }
        assertContains(error.message.orEmpty(), "requires the unedited fitted height grid")
        assertEquals(before, f.now)
    }

    private fun compare(native: RigPreviewModel, actual: RigPreviewModel, poses: List<Map<ParameterId, Float>>,
                        aliases: Map<String, String> = emptyMap(), targets: Set<DrawableId>? = null) {
        val evaluator = CpuDeformationEvaluator()
        fun canvas(model: PuppetModel, mesh: Drawable): DrawableMesh {
            val geometry = requireNotNull(mesh.mesh)
            val uv = RasterMeshJournal.TextureCoordinates(model, mesh).toCanvas(geometry.uvs)
            return DrawableMesh(uv, uv, geometry.indices)
        }
        var referenceRig = RigIdRemap(parameters = aliases.mapKeys { ParameterId(it.key) }.mapValues { ParameterId(it.value) }).model(native.rig.puppet)
        for (mesh in actual.rig.puppet.drawables) {
            if (targets != null && mesh.id !in targets) continue
            val original = native.rig.puppet.drawables.single { it.id == mesh.id }
            val plan = RasterMeshJournal.prepare(canvas(native.rig.puppet, original), canvas(actual.rig.puppet, mesh))
            val texture = RasterMeshJournal.TextureCoordinates(native.rig.puppet, original)
            val migrated = DrawableMesh(PrimitiveResidual.transfer(requireNotNull(original.mesh).positions, plan.sources),
                texture.toUvs(canvas(actual.rig.puppet, mesh).uvs), requireNotNull(mesh.mesh).indices)
            // Compare on the same material topology through the engine's normal migration primitive. Interpolating
            // already-deformed world vertices would interpolate AFTER a nonlinear Warp instead of before it.
            referenceRig = RasterMeshJournal.apply(referenceRig, mesh.id, plan.copy(mesh = migrated))
        }
        for (pose in poses) {
            val expectedFrame = evaluator.evaluate(referenceRig, pose)
            val shownFrame = evaluator.evaluate(actual.rig.puppet, pose)
            val expected = expectedFrame.worldPositions
            val shown = shownFrame.worldPositions
            for ((id, points) in shown) {
                if (targets != null && id !in targets) continue
                val reference = expected.getValue(id)
                val error = points.indices.maxOf { abs(points[it] - reference[it]) }
                assertTrue(error < .03f, "${id.raw}: difference $error at $pose")
                assertTrue(abs(shownFrame.opacity.getValue(id) - expectedFrame.opacity.getValue(id)) < .0001f, "${id.raw}: opacity differs at $pose")
                assertTrue(abs(shownFrame.drawOrder.getValue(id) - expectedFrame.drawOrder.getValue(id)) < .001f, "${id.raw}: draw order differs at $pose")
                val expectedColor = expectedFrame.multiplyColor[id] ?: ColorRgb.MultiplyIdentity
                val shownColor = shownFrame.multiplyColor[id] ?: ColorRgb.MultiplyIdentity
                assertTrue(abs(shownColor.red - expectedColor.red) < .0001f && abs(shownColor.green - expectedColor.green) < .0001f &&
                    abs(shownColor.blue - expectedColor.blue) < .0001f, "${id.raw}: multiply color differs at $pose")
                val expectedScreen = expectedFrame.screenColor[id] ?: ColorRgb.ScreenIdentity
                val shownScreen = shownFrame.screenColor[id] ?: ColorRgb.ScreenIdentity
                assertTrue(abs(shownScreen.red - expectedScreen.red) < .0001f && abs(shownScreen.green - expectedScreen.green) < .0001f &&
                    abs(shownScreen.blue - expectedScreen.blue) < .0001f, "${id.raw}: screen color differs at $pose")
            }
        }
    }

    @Test fun independentLegacyMouthMatchesVersionOneGeometryFadeMasksAndParameterIdentity(): Unit = runBlocking<Unit> {
        val document = document(mouthDetails = true)
        suspend fun evaluate(version: Int): io.github.psd2live.format.model.RigIR {
            val f = Fixture(document)
            f.install()
            f.edit("modifier_add_group", buildJsonObject {
                put("group", "mouth-only")
                val mouth = f.addition("mouth/legacy", "mouth", config = buildJsonObject {
                    put("scaffold", "structure")
                    putJsonObject("tuning") { put("teethFade", 28f); put("mouthSeam", 62f) }
                })
                put("entries", JsonArray(listOf(f.addition(RigSourceModifiers.SCAFFOLD, "structure"),
                    JsonObject(mouth + ("version" to JsonPrimitive(version))))))
            })
            val result = requireNotNull(f.now.model.rig.modifierOutput)
            if (version == 2) {
                f.edit("modifier_enable", buildJsonObject { put("id", "structure"); put("enabled", false) })
                val report = requireNotNull(f.now.model.rig.modifierReport)
                val entry = report.getValue("entries").jsonArray.map { it.jsonObject }
                    .single { it.getValue("id").jsonPrimitive.content == "mouth" }
                assertEquals("missing_dependency", entry.getValue("status").jsonPrimitive.content)
            }
            return result
        }
        val legacy = evaluate(1)
        val current = evaluate(2)
        assertEquals(legacy.parameters, current.parameters)
        assertEquals(legacy.parameterTree, current.parameterTree)
        assertEquals(legacy.restPose, current.restPose)
        assertEquals(legacy.meshes, current.meshes)
        val tongue = current.meshes.single { it.name == "tongue" }
        assertTrue(tongue.maskedBy.isNotEmpty())
        assertTrue(tongue.channels.isNotEmpty())
    }

    @Test fun legacyBrowV2KeepsV1DefaultAndMatchesTheLiveHeadBindings(): Unit = runBlocking {
        suspend fun evaluate(head: String?, headVersion: Int, scaffoldVersion: Int, version: Int?, withBrow: Boolean = true): Fixture {
            val art = document().let { if (withBrow) it else it.copy(deletedLayerIds = setOf("brow")) }
            val fixture = Fixture(art, scaffoldVersion)
            fixture.install()
            val entries = listOf(fixture.addition("rig.scaffold", "structure")) +
                listOfNotNull(head?.let {
                    JsonObject(fixture.addition(it, "head") + ("version" to JsonPrimitive(headVersion)))
                }) + fixture.addition("brow/legacy", "brow", config = buildJsonObject {
                    put("scaffold", "structure")
                    putJsonObject("tuning") { put("browLift", 24f) }
                }).let { if (version == null) it else JsonObject(it + ("version" to JsonPrimitive(version))) }
            fixture.edit("modifier_add_group", buildJsonObject {
                put("group", "brow"); put("entries", JsonArray(entries))
            })
            return fixture
        }

        for ((head, headVersion, scaffoldVersion) in listOf(
            Triple(null, 1, 1), Triple("face/legacy", 1, 2), Triple("face/fitted", 1, 2), Triple("face/fitted", 2, 2),
        )) {
            val old = evaluate(head, headVersion, scaffoldVersion, null)
            val current = evaluate(head, headVersion, scaffoldVersion, 2)
            assertEquals(1, RigModifierStack.fromJson(old.now.document.rigEdits.modifierStack!!).instances.last().version)
            val expected = assertNotNull(old.now.model.rig.modifierOutput)
            val actual = assertNotNull(current.now.model.rig.modifierOutput)
            val meshes = expected.meshes.associateBy { it.id }
            val deformers = expected.deformers.associateBy { it.id }
            val label = "head=$head@$headVersion, scaffold=$scaffoldVersion"
            assertEquals(expected.copy(meshes = emptyList(), deformers = emptyList()),
                actual.copy(meshes = emptyList(), deformers = emptyList()), label)
            assertEquals(expected.meshes.map { it.id }, actual.meshes.map { it.id }, label)
            assertEquals(expected.deformers.map { it.id }, actual.deformers.map { it.id }, label)
            actual.meshes.forEach {
                val previous = meshes.getValue(it.id)
                assertTrue(previous == it, "$label mesh ${it.name}")
            }
            actual.deformers.forEach { assertEquals(deformers[it.id], it, "$label deformer ${it.name}") }
            if (head == "face/legacy" || head == "face/fitted" && headVersion == 2) verifyLegacyBrowWorker(current)
        }

        val old = evaluate(null, 1, 2, null, withBrow = false)
        val current = evaluate(null, 1, 2, 2, withBrow = false)
        val output = assertNotNull(current.now.model.rig.modifierOutput)
        assertEquals(old.now.model.rig.modifierOutput, output)
        assertTrue(output.meshes.none { it.name == "brow" })
        assertTrue(setOf("ParamBrowLY", "ParamBrowRY").all { id -> output.parameters.any { it.id == id } })
    }

    private suspend fun verifyLegacyBrowWorker(fixture: Fixture) {
        val evaluated = assertNotNull(fixture.now.model.modifierEvaluation).outputs
        val before = evaluated[evaluated.lastIndex - 1]
        val after = evaluated.last()
        val stack = RigModifierStack.fromJson(fixture.now.document.rigEdits.modifierStack!!)
        val (scaffold, head, brow) = stack.instances
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(BrowLegacyV2Algorithm.TYPE, BrowLegacyV2Algorithm.VERSION))
        assertEquals(artifact.reference, brow.plugin)
        val owner = RigModifierSource.of(fixture.now.model)
        val directory = Files.createTempDirectory("legacy-brow-v2-")
        val preferences = Preferences.userRoot().node("legacy-brow-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val installed = store.install(staging.toString())
            val worker = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(owner))
                .single { it.type == BrowLegacyV2Algorithm.ENTRYPOINT }
            val instance = brow.copy(type = worker.type, plugin = installed.reference)
            val providers = listOf(scaffold, head).map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = worker.authorContext(instance).withUpstream(providers)
            val algorithm = worker.plugin as ContextualModifierAlgorithmPlugin
            assertEquals(after, algorithm.evaluate(before, instance, context, {}))
            assertFailsWith<IllegalArgumentException> {
                BrowLegacyV2Algorithm.changes(before, brow, context.withUpstream(emptyList())) {}
            }
            assertFailsWith<IllegalArgumentException> {
                BrowLegacyV2Algorithm.changes(before, brow, context.withUpstream(listOf(providers.first().copy(version = 3)))) {}
            }
            val mesh = before.meshes.single { it.name == "brow" }
            val geometry = assertNotNull(mesh.geometry)
            val moved = geometry.copy(positions = io.github.psd2live.format.model.Floats.of(
                geometry.positions.toArray().also { it[0] += .01f }))
            val edited = before.copy(meshes = before.meshes.map { if (it.id == mesh.id) it.copy(geometry = moved, opacity = .6f) else it })
            val newMesh = algorithm.evaluate(edited, instance, context, {}).meshes.single { it.id == mesh.id }
            assertEquals(moved, newMesh.geometry)
            assertEquals(.6f, newMesh.opacity)
            assertEquals(after.meshes.single { it.id == mesh.id }.offsets, newMesh.offsets)
            assertFailsWith<IllegalArgumentException> {
                val reparented = before.copy(meshes = before.meshes.map { if (it.id == mesh.id) it.copy(parent = null) else it })
                BrowLegacyV2Algorithm.changes(reparented, brow, context) {}
            }
            val turnTuning = JsonObject(brow.config + ("tuning" to buildJsonObject { put("browTilt", 18f) }))
            assertFailsWith<IllegalArgumentException> {
                BrowLegacyV2Algorithm.changes(before, brow.copy(config = turnTuning), context) {}
            }
            store.uninstall(installed.reference)
            assertEquals(installed.reference, store.install(staging.toString()).reference)

            val current = fixture.now
            val history = fixture.runtime.history()
            val historyStore = WorkspaceStore(directory.resolve("project"))
            historyStore.persistHistory(current.projectId, history)
            val archive = directory.resolve("brow.psd2live")
            val repository = ProjectRepository(writeHeadCache = false)
            repository.save(ProjectSaveCapture(current.projectId, history, JsonObject(emptyMap()), null, historyStore), archive)
            repository.open(archive).use { opened ->
                val restored = opened.history.head().snapshot
                assertEquals(brow, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.last())
                assertEquals(after, fixture.builder.build(restored).rig.modifierOutput)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }

        val expected = RigIrCompiler.compile(fixture.now.model)
        fixture.edit("modifier_apply", buildJsonObject { put("id", "brow") })
        assertEquals(expected, RigIrCompiler.compile(WorkspacePreviewBuilder().build(fixture.now.document)))
    }

    @Test fun legacyBasesKeepTheOriginalSingleAxisAndCombinedMotions(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        val original = native(f.document)
        f.group(RigLegacyBindings.specs.filter { it.type.endsWith("/legacy") }.map { it.type })
        assertEquals(original.rig.puppet.parameterLinks.toSet(), f.now.model.rig.puppet.parameterLinks.toSet())
        assertEquals(original.rig.puppet.drawables.associate { it.id to it.maskedBy }, f.now.model.rig.puppet.drawables.associate { it.id to it.maskedBy })
        val poses = listOf(emptyMap(), mapOf(StandardParameters.ANGLE_X to 30f, StandardParameters.ANGLE_Y to -15f),
            mapOf(StandardParameters.EYE_L_OPEN to 0f, StandardParameters.MOUTH_OPEN to .7f, StandardParameters.BROW_L_Y to .6f),
            mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f, StandardParameters.ANGLE_Z to 10f,
                StandardParameters.BODY_LEAN to 3f, StandardParameters.BODY_X to -6f, StandardParameters.BREATH to .6f,
                StandardParameters.EYE_L_OPEN to .4f, StandardParameters.EYE_BALL_X to -.5f, StandardParameters.EYE_BALL_Y to .4f,
                StandardParameters.MOUTH_OPEN to .7f, StandardParameters.MOUTH_FORM to -.5f, StandardParameters.HAIR_FRONT to .6f,
                StandardParameters.HAIR_BACK to -.3f))
        compare(original, f.now.model, poses)
        val expected = RigIrCompiler.compile(f.now.model)
        val before = f.now
        f.edit("modifier_apply", buildJsonObject { put("id", "face") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        assertTrue(RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).appliedCapabilities.any {
            it.id == "body" && it.config["scaffold"]?.jsonPrimitive?.content == "structure"
        })
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    @Test fun applyingAProviderKeepsTheActualScopeAndSubsequentFacialBindings(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        f.group(listOf("eye/legacy", "face/legacy", "body/legacy", "mouth/legacy", "hair/legacy", "brow/legacy"))
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "eye") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "brow") })
        assertTrue(RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.isEmpty())
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    private fun mesh(f: Fixture, layer: String) = f.now.model.rig.puppet.drawables.single {
        f.now.model.rig.layerIdByDrawableId[it.id.raw] == layer
    }

    private fun key(f: Fixture, axes: List<KeyformAxis>): JsonObject {
        val defaults = f.now.model.rig.puppet.parameters.associate { it.id to it.default }
        return JsonObject(axes.associate { it.parameterId.raw to JsonPrimitive(defaults.getValue(it.parameterId)) })
    }

    @Test fun editsOfTheGeneratedClosedMouthDefaultBelongToTheLatestBinding(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/legacy", "mouth/legacy"))
        val target = mesh(f, "mouth")
        val grid = requireNotNull(target.geometryGrid)
        val pose = key(f, grid.axes)
        val coordinate = grid.axes.map { axis -> axis.keys.indices.single { abs(axis.keys[it] - pose.getValue(axis.parameterId.raw).jsonPrimitive.float) < .00001f } }
        val points = grid.cells.single { it.coordinate.toList() == coordinate }.form.positionDeltas
        val journal = f.now.document.rigEdits.authoringJournal
        f.edit("keyform_apply", buildJsonObject { putJsonArray("changes") { addJsonObject {
            put("op", "set"); put("target", "mesh:${target.id.raw}"); put("key", pose)
            putJsonObject("geometry") { put("positionDeltas", JsonArray(points.mapIndexed { index, value -> JsonPrimitive(if (index == 0) value + .05f else value) })) }
        } } })
        var stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        assertEquals(setOf("mouth"), stack.overrides.keys)
        assertEquals(journal, f.now.document.rigEdits.authoringJournal)
        val expected = RigIrCompiler.compile(f.now.model)
        assertEquals(1f, expected.restPose[StandardParameters.MOUTH_OPEN.raw])
        f.edit("modifier_enable", buildJsonObject { put("id", "mouth"); put("enabled", false) })
        assertNotEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_enable", buildJsonObject { put("id", "mouth"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_apply", buildJsonObject { put("id", "mouth") })
        stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        assertTrue(stack.instances.isEmpty() && stack.overrides.isEmpty())
        assertEquals(expected.restPose, f.now.model.authored.rig.authoredRestPose)
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun generatedOpacityAtTheOpenEyeDefaultIsAnInstanceOverrideRatherThanAnAuthorAxis(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        f.group(listOf("eye/legacy"))
        val target = mesh(f, "close")
        val opacity = requireNotNull(target.channelGrids[FormChannel.OPACITY])
        val pose = key(f, opacity.axes)
        val journal = f.now.document.rigEdits.authoringJournal
        f.edit("keyform_apply", buildJsonObject { putJsonArray("changes") { addJsonObject {
            put("op", "set"); put("target", "mesh:${target.id.raw}"); put("key", pose)
            putJsonObject("channels") { put("opacity", .3f) }
        } } })
        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        assertEquals(setOf("eye"), stack.overrides.keys)
        assertEquals("opacity", stack.overrides.getValue("eye").single().getValue("channel").jsonPrimitive.content)
        assertEquals(journal, f.now.document.rigEdits.authoringJournal)
        assertEquals(.3f, CpuDeformationEvaluator().evaluate(f.now.model.rig.puppet, emptyMap()).opacity.getValue(target.id), .0001f)
        validateOperationSchema(WorkspaceModifierQueries.describe(f.now), WorkspaceModifierSchemas.stack, "result")
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_enable", buildJsonObject { put("id", "eye"); put("enabled", false) })
        f.edit("modifier_enable", buildJsonObject { put("id", "eye"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_apply", buildJsonObject { put("id", "eye") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun twoObjectsSharingOneSourceLayerStillHaveIndependentBindingInstances(): Unit = runBlocking<Unit> {
        val f = Fixture(document(), scaffoldVersion = 1); f.install()
        val original = mesh(f, "face").id.raw
        f.edit("object_paste", buildJsonObject {
            putJsonArray("sources") { add("mesh:$original") }
            putJsonObject("ids") { put("mesh:$original", "FaceCopy") }
        })
        val targets = listOf(ModifierTarget("mesh", original), ModifierTarget("mesh", "FaceCopy"))
        f.edit("modifier_add", f.addition(RigSourceModifiers.SCAFFOLD, "structure", targets))
        f.edit("modifier_add", f.addition("body/legacy", "first", targets.take(1), buildJsonObject { put("scaffold", "structure"); put("strength", .5f) }))
        f.edit("modifier_add", f.addition("body/legacy", "second", targets.drop(1), buildJsonObject { put("scaffold", "structure"); put("strength", 1.5f) }))
        val frame = CpuDeformationEvaluator().evaluate(f.now.model.rig.puppet, mapOf(StandardParameters.BODY_X to 8f, StandardParameters.BODY_LEAN to 4f))
        val first = frame.worldPositions.getValue(DrawableId(original))
        val second = frame.worldPositions.getValue(DrawableId("FaceCopy"))
        assertTrue(first.indices.any { abs(first[it] - second[it]) > .001f })
        val expected = RigIrCompiler.compile(f.now.model)
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "first") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun fittedEyesBrowsAndMouthUseTheirNativeAlgorithmsAndPersistTheirVersions(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        val recipe = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
        val original = native(f.document, recipe)
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy"))
        val eye = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single { it.type == "eye/fitted" }
        assertEquals(2, eye.version, "an omitted version must select the registered current algorithm")
        compare(original, f.now.model, listOf(emptyMap(),
            mapOf(StandardParameters.EYE_L_OPEN to .4f, StandardParameters.MOUTH_OPEN to .6f, StandardParameters.MOUTH_FORM to -.5f, StandardParameters.BROW_L_Y to .7f),
            mapOf(StandardParameters.ANGLE_X to 20f, StandardParameters.ANGLE_Y to -12f, StandardParameters.BODY_LEAN to 3f,
                StandardParameters.EYE_L_OPEN to 0f, StandardParameters.MOUTH_OPEN to 1f, StandardParameters.BROW_L_Y to -.4f)))
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "brow") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "hair") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    @Test fun fittedMouthMatchesNativeBeforeAddingHair(): Unit = runBlocking<Unit> {
        val cases = listOf(Triple(false, false, false), Triple(true, false, false), Triple(true, true, false), Triple(false, true, true))
        for ((details, depthTurn, thinMouth) in cases) {
            val f = Fixture(document(mouthDetails = details, thinMouth = thinMouth)); f.install()
            val recipe = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
                .let { if (depthTurn) it.copy(modules = it.modules + (RigModules.FACE_DEPTH_TURN.id to ModuleChoice(true))) else it }
            val original = native(f.document, recipe)
            val face = if (depthTurn) "face/fitted" else "face/legacy"
            f.group(listOf("body/legacy", face, "eye/fitted", "brow/fitted", "mouth/fitted"))
            val targets = original.rig.layerIdByDrawableId.filterValues { it in setOf("mouth", "teeth-top", "teeth-bottom", "tongue") }
                .keys.mapTo(hashSetOf(), ::DrawableId)
            compare(original, f.now.model, listOf(emptyMap(),
                mapOf(StandardParameters.ANGLE_X to 20f, StandardParameters.ANGLE_Y to -12f,
                    StandardParameters.MOUTH_OPEN to .6f, StandardParameters.MOUTH_FORM to -.5f)), targets = targets)
        }
    }

    @Test fun fittedBaseValuesAreIndependentOfLegacySlidersAndMutuallyExclusiveWithOtherBases(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        f.group(listOf("eye/fitted"))
        val initial = f.now
        val invalid = f.addition("eye/legacy", "another_eye")
        assertFailsWith<WorkspaceBatchEditException> { f.edit("modifier_add", invalid) }
        assertEquals(initial.state, f.now.state)
        val config = buildJsonObject {
            put("scaffold", "structure")
            putJsonObject("values") { put("lowerShare", 25f); put("roundness", 80f); put("lashSquash", 40f) }
        }
        f.edit("modifier_update", buildJsonObject { put("id", "eye"); put("type", "eye/fitted"); put("config", config) })
        assertNotEquals(RigIrCompiler.compile(initial.model), RigIrCompiler.compile(f.now.model))
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_enable", buildJsonObject { put("id", "eye"); put("enabled", false) })
        f.edit("modifier_enable", buildJsonObject { put("id", "eye"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }
    @Test fun independentEyeSmileMatchesLockedVersionWithWideLids(): Unit = runBlocking {
        compareEyeSmile("face/legacy", composed = true)
    }

    @Test fun independentEyeSmileMatchesLockedVersionWithFittedHead(): Unit = runBlocking {
        compareEyeSmile("face/fitted")
    }

    private suspend fun compareEyeSmile(head: String, composed: Boolean = false) {
        val f = Fixture(document(eyeDetails = true, bilateralEyes = true))
        f.install()
        f.group(listOf("body/legacy", head, "eye/fitted"))
        val dependency = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        if (composed) for (type in listOf("eye.wide", "eye.squash", "eye.lid-fade")) {
            f.edit("modifier_add", f.addition(type, type, config = dependency))
        }
        val config = JsonObject(dependency + ("values" to buildJsonObject {
            put("arch", 138f); put("push", 71f)
        }))
        val addition = f.addition("eye.smile", "smile", config = config)
        val prefix = f.now
        val previous = assertNotNull(prefix.model.rig.modifierOutput)
        f.edit("modifier_add", addition)
        val legacy = f.now.model
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
        val expected = assertNotNull(legacy.rig.modifierOutput)
        f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        val current = f.now.model
        val actual = assertNotNull(current.rig.modifierOutput)
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.parameterTree, actual.parameterTree)
        for ((old, new) in expected.meshes.zip(actual.meshes)) {
            assertEquals(old, new, old.id)
        }
        assertTrue(expected == actual, "The complete generated IR must match the locked smile algorithm")

        val evaluator = CpuDeformationEvaluator()
        val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
            StandardParameters.EYE_L_OPEN to .36f, StandardParameters.EYE_R_OPEN to 0f,
            ParameterId("ParamEyeLSmile") to .67f, ParameterId("ParamEyeRSmile") to 1f)
        val oldPose = evaluator.evaluate(legacy.rig.puppet, pose)
        val newPose = evaluator.evaluate(current.rig.puppet, pose)
        for ((id, positions) in oldPose.worldPositions) assertContentEquals(positions, newPose.worldPositions.getValue(id), id.raw)
        assertEquals(oldPose.opacity, newPose.opacity)
        val plainPose = evaluator.evaluate(current.rig.puppet, pose + (ParameterId("ParamEyeLSmile") to 0f))
        val white = mesh(f, "white").id
        assertFalse(plainPose.worldPositions.getValue(white).contentEquals(newPose.worldPositions.getValue(white)))

        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val instance = stack.instances.last()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("eye.smile", 2))
        assertEquals(artifact.reference, instance.plugin)
        if (composed) {
            val directory = Files.createTempDirectory("eye-smile-package-")
            val preferences = Preferences.userRoot().node("eye-smile-package/" + UUID.randomUUID())
            val store = ModifierPluginStore(directory.resolve("installed"), preferences)
            try {
                val staging = Files.createDirectory(directory.resolve("package"))
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                for (name in artifact.manifest.files.keys) {
                    assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                }
                val providers = stack.instances.dropLast(1).map { provider ->
                    ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                        provider.id, provider.config, provider.outputAliases, provider.plugin)
                }
                val definition = RigEyeSmileModifier.definition(RigModifierSource.of(current))
                val context = definition.authorContext(instance).withUpstream(providers)
                val geometry = assertNotNull(previous.meshes.single { it.id == white.raw }.geometry)
                val edited = previous.copy(meshes = previous.meshes.map {
                    if (it.id != white.raw) it else it.copy(geometry = geometry.copy(uvs = Floats.wrap(geometry.uvs.toArray().also { uv ->
                        uv[0] += .01f
                    })))
                })
                val topologyFailure = assertFailsWith<IllegalArgumentException> {
                    EyeSmileAlgorithm().changes(edited, instance, context) {}
                }
                assertContains(topologyFailure.message.orEmpty(), "requires the captured lid topology")
                val reparented = previous.copy(meshes = previous.meshes.map {
                    if (it.id != white.raw) it else it.copy(parent = null)
                })
                val carrierFailure = assertFailsWith<IllegalArgumentException> {
                    EyeSmileAlgorithm().changes(reparented, instance, context) {}
                }
                assertContains(carrierFailure.message.orEmpty(), "cannot migrate a fitted lid carrier")

                repeat(2) {
                    val installed = store.install(staging.toString())
                    assertEquals(artifact.reference, installed.reference)
                    val algorithm = installed.manifest.algorithms.single()
                    val external = instance.copy(type = algorithm.type)
                    val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                        it.type == external.type && it.version == external.version
                    }
                    assertEquals(definition.optionalUpstream, adapter.optionalUpstream)
                    val workerContext = adapter.authorContext(external).withUpstream(providers.filter {
                        it.capability in adapter.requires + adapter.optionalUpstream
                    })
                    val worker = adapter.plugin as ContextualModifierAlgorithmPlugin
                    assertEquals(actual, worker.evaluate(previous, external, workerContext) {})
                    val coupled = providers.first { it.capability == "eye.wide" }.copy(
                        capability = "eye.gaze-lid", type = "eye.gaze-lid", instanceId = "gaze",
                    )
                    val conflict = assertFailsWith<IllegalStateException> {
                        worker.evaluate(previous, external, workerContext.withUpstream(workerContext.upstream + coupled)) {}
                    }
                    assertContains(conflict.message.orEmpty(), "cannot lower preceding coupled lid or brow bindings")
                    ModifierPluginWorkers.stop(installed.reference)
                    store.uninstall(installed.reference)
                }

                val now = f.now
                val history = f.runtime.history()
                val historyStore = WorkspaceStore(directory.resolve("project"))
                historyStore.persistHistory(now.projectId, history)
                val archive = directory.resolve("eye-smile.psd2live")
                val repository = ProjectRepository(writeHeadCache = false)
                repository.save(ProjectSaveCapture(now.projectId, history, JsonObject(emptyMap()), null, historyStore), archive)
                repository.open(archive).use { opened ->
                    val restored = opened.history.head().snapshot
                    assertEquals(instance, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.last())
                    assertEquals(actual, WorkspacePreviewBuilder().build(restored).rig.modifierOutput)
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                preferences.removeNode()
                ModifierPluginStore.deleteTree(directory)
            }
        }
        val compiled = RigIrCompiler.compile(current)
        f.edit("modifier_apply", buildJsonObject { put("id", "smile") })
        assertEquals(compiled, RigIrCompiler.compile(f.now.model))
        assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
    }

    @Test fun independentEyeGazeLidPreservesTheRecommendedPreset(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), WorkspaceModifierPlan.RECOMMENDED_GROUP)
        val locked = stack.instances.single { it.type == "eye.gaze-lid" }
        assertEquals(1, locked.version)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 2))
        suspend fun preview(candidate: RigModifierStack) = builder.build(art.copy(
            rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()),
        ))
        val expected = preview(stack)
        val current = preview(stack.copy(instances = stack.instances.map {
            if (it.id == locked.id) it.copy(version = 2, plugin = artifact.reference) else it
        }))
        assertEquals(expected.rig.modifierOutput, current.rig.modifierOutput)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))
    }

    @Test fun independentEyeGazeLidMatchesLockedVersionWithWideLids(): Unit = runBlocking {
        compareEyeGazeLid("face/legacy", installWorker = true)
    }

    @Test fun independentEyeGazeLidMatchesLockedVersionWithFittedHead(): Unit = runBlocking {
        compareEyeGazeLid("face/fitted", installWorker = false)
    }

    private suspend fun compareEyeGazeLid(head: String, installWorker: Boolean) {
        val f = Fixture(document(eyeDetails = true, bilateralEyes = true))
        f.install()
        f.group(listOf("body/legacy", head, "eye/fitted"))
        val dependency = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        for (type in listOf("eye.wide", "eye.smile")) {
            f.edit("modifier_add", f.addition(type, type, config = dependency))
        }
        val config = JsonObject(dependency + ("values" to buildJsonObject {
            put("follow", 138f)
        }))
        val addition = f.addition("eye.gaze-lid", "gaze", config = config)
        val prefix = f.now
        val previous = assertNotNull(prefix.model.rig.modifierOutput)
        f.edit("modifier_add", addition)
        val legacy = f.now.model
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
        assertEquals("0ddd69ed8ca39842ce106a3c87866feec27d8704758aadc0a68ba85a5c51417d",
            RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().plugin?.digest)
        val expected = assertNotNull(legacy.rig.modifierOutput)
        f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        val current = f.now.model
        val actual = assertNotNull(current.rig.modifierOutput)
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.parameterTree, actual.parameterTree)
        assertEquals(expected.parameterLinks, actual.parameterLinks)
        assertEquals(expected.deformers.map { it.id }, actual.deformers.map { it.id })
        for ((old, new) in expected.deformers.zip(actual.deformers)) assertEquals(old, new, old.id)
        for ((old, new) in expected.meshes.zip(actual.meshes)) {
            assertEquals(old, new, old.id)
        }
        assertTrue(expected == actual, "The complete generated IR must match the locked gaze algorithm")

        val evaluator = CpuDeformationEvaluator()
        val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
            StandardParameters.EYE_L_OPEN to 1.5f, StandardParameters.EYE_R_OPEN to 0f,
            ParameterId("ParamEyeLSmile") to .67f,
            ParameterId("ParamEyeBallLY") to .67f, ParameterId("ParamEyeBallRX") to 1f,
            StandardParameters.EYE_BALL_X to .2f)
        val oldPose = evaluator.evaluate(legacy.rig.puppet, pose)
        val newPose = evaluator.evaluate(current.rig.puppet, pose)
        for ((id, positions) in oldPose.worldPositions) assertContentEquals(positions, newPose.worldPositions.getValue(id), id.raw)
        assertEquals(oldPose.opacity, newPose.opacity)
        val plainPose = evaluator.evaluate(current.rig.puppet, pose + (ParameterId("ParamEyeBallLY") to 0f))
        val white = mesh(f, "white").id
        assertFalse(plainPose.worldPositions.getValue(white).contentEquals(newPose.worldPositions.getValue(white)))

        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val instance = stack.instances.last()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("eye.gaze-lid", 2))
        assertEquals(artifact.reference, instance.plugin)
        if (installWorker) {
            val directory = Files.createTempDirectory("eye-gaze-package-")
            val preferences = Preferences.userRoot().node("eye-gaze-package/" + UUID.randomUUID())
            val store = ModifierPluginStore(directory.resolve("installed"), preferences)
            try {
                val staging = Files.createDirectory(directory.resolve("package"))
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                for (name in artifact.manifest.files.keys) {
                    assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                }
                val providers = stack.instances.dropLast(1).map { provider ->
                    ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                        provider.id, provider.config, provider.outputAliases, provider.plugin)
                }
                val definition = RigEyeGazeLidModifier.definition(RigModifierSource.of(current))
                repeat(2) {
                    val installed = store.install(staging.toString())
                    assertEquals(artifact.reference, installed.reference)
                    val algorithm = installed.manifest.algorithms.single()
                    val external = instance.copy(type = algorithm.type)
                    val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                        it.type == external.type && it.version == external.version
                    }
                    assertEquals(definition.optionalUpstream, adapter.optionalUpstream)
                    val workerContext = adapter.authorContext(external).withUpstream(providers.filter {
                        it.capability in adapter.requires + adapter.optionalUpstream
                    })
                    val worker = adapter.plugin as ContextualModifierAlgorithmPlugin
                    assertEquals(actual, worker.evaluate(previous, external, workerContext) {})
                    ModifierPluginWorkers.stop(installed.reference)
                    store.uninstall(installed.reference)
                }

                val now = f.now
                val history = f.runtime.history()
                val historyStore = WorkspaceStore(directory.resolve("project"))
                historyStore.persistHistory(now.projectId, history)
                val archive = directory.resolve("eye-gaze.psd2live")
                val repository = ProjectRepository(writeHeadCache = false)
                repository.save(ProjectSaveCapture(now.projectId, history, JsonObject(emptyMap()), null, historyStore), archive)
                repository.open(archive).use { opened ->
                    val restored = opened.history.head().snapshot
                    assertEquals(instance, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.last())
                    assertEquals(actual, WorkspacePreviewBuilder().build(restored).rig.modifierOutput)
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                preferences.removeNode()
                ModifierPluginStore.deleteTree(directory)
            }
        }
        val compiled = RigIrCompiler.compile(current)
        f.edit("modifier_apply", buildJsonObject { put("id", "gaze") })
        assertEquals(compiled, RigIrCompiler.compile(f.now.model))
        assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
    }

    @Test fun independentEyeEmotionMatchesLockedVersionAndInstalledWorker(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true, bilateralEyes = true))
        f.install()
        f.group(listOf("body/legacy", "face/fitted"))
        f.edit("modifier_add", JsonObject(f.addition("eye/fitted", "eye") + ("version" to JsonPrimitive(4))))
        val dependency = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        for (type in listOf("eye.wide", "eye.squash", "eye.lid-fade", "eye.smile")) {
            f.edit("modifier_add", f.addition(type, type, config = dependency))
        }
        val config = JsonObject(dependency + ("values" to buildJsonObject { put("strength", 148f) }))
        val addition = f.addition("eye.emotion", "emotion", config = config)
        val prefix = f.now
        val previous = assertNotNull(prefix.model.rig.modifierOutput)
        f.edit("modifier_add", addition)
        val expected = assertNotNull(f.now.model.rig.modifierOutput)
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
        f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        val current = f.now.model
        val actual = assertNotNull(current.rig.modifierOutput)
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.parameterTree, actual.parameterTree)
        for ((old, new) in expected.meshes.zip(actual.meshes)) assertEquals(old, new, old.id)
        assertEquals(expected, actual)

        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val instance = stack.instances.last()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("eye.emotion", 2))
        assertEquals(artifact.reference, instance.plugin)
        val directory = Files.createTempDirectory("eye-emotion-package-")
        val preferences = Preferences.userRoot().node("eye-emotion-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val providers = stack.instances.dropLast(1).map { provider ->
                ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                    provider.id, provider.config, provider.outputAliases, provider.plugin)
            }
            val definition = RigEyeEmotionModifier.definition(RigModifierSource.of(current))
            val context = definition.authorContext(instance).withUpstream(providers)
            val white = mesh(f, "white").id.raw
            val editedKeys = previous.copy(meshes = previous.meshes.map {
                if (it.id == white) it.copy(offsets = null) else it
            })
            val failure = assertFailsWith<IllegalArgumentException> {
                EyeEmotionAlgorithm().changes(editedKeys, instance, context) {}
            }
            assertContains(failure.message.orEmpty(), "cannot migrate edited or lowered lid geometry")

            repeat(2) {
                val installed = store.install(staging.toString())
                assertEquals(artifact.reference, installed.reference)
                val algorithm = installed.manifest.algorithms.single()
                val external = instance.copy(type = algorithm.type)
                val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                    it.type == external.type && it.version == external.version
                }
                val workerContext = adapter.authorContext(external).withUpstream(providers.filter {
                    it.capability in adapter.requires + adapter.optionalUpstream
                })
                assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, workerContext) {})
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
        val compiled = RigIrCompiler.compile(current)
        f.edit("modifier_apply", buildJsonObject { put("id", "emotion") })
        assertEquals(compiled, RigIrCompiler.compile(f.now.model))
        assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
    }

    @Test fun independentEyeEmotionPreservesTheRecommendedPreset(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), WorkspaceModifierPlan.RECOMMENDED_GROUP)
        val locked = stack.instances.single { it.type == "eye.emotion" }
        assertEquals(1, locked.version)
        val downstream = stack.instances.dropWhile { it.id != locked.id }.drop(1)
        assertTrue(downstream.map { it.type }.containsAll(listOf("eye.gaze-lid", "brow.blink", "brow.lid-coupling")))
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 2))
        suspend fun preview(candidate: RigModifierStack) = builder.build(art.copy(
            rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()),
        ))
        val expected = preview(stack)
        val current = preview(stack.copy(instances = stack.instances.map {
            if (it.id == locked.id) it.copy(version = 2, plugin = artifact.reference) else it
        }))
        assertEquals(expected.rig.modifierOutput, current.rig.modifierOutput)
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))

        val full = WorkspaceModifierPlan.initial(builder.build(art), "modern-full")
        val aftershock = full.instances.single { it.type == "eye.aftershock" }
        val fullEmotion = full.instances.single { it.type == "eye.emotion" }
        assertTrue(full.instances.indexOf(aftershock) < full.instances.indexOf(fullEmotion))
        val independentFull = full.copy(instances = full.instances.map {
            if (it.id == fullEmotion.id) it.copy(version = 2, plugin = artifact.reference) else it
        })
        assertEquals(preview(full).rig.modifierOutput, preview(independentFull).rig.modifierOutput)

        val downstreamAftershock = stack.copy(instances = stack.instances + aftershock.copy(id = "aftershock-after-emotion"))
        val independentDownstream = downstreamAftershock.copy(instances = downstreamAftershock.instances.map {
            if (it.id == locked.id) it.copy(version = 2, plugin = artifact.reference) else it
        })
        assertEquals(preview(downstreamAftershock).rig.modifierOutput, preview(independentDownstream).rig.modifierOutput)
    }

    @Test fun independentEyeEmotionKeepsTheUndrivenSideOrdinary(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true))
        f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted"))
        val addition = f.addition("eye.emotion", "emotion", config = buildJsonObject {
            put("scaffold", "structure"); put("base", "eye")
        })
        val prefix = f.now
        f.edit("modifier_add", addition)
        val expected = assertNotNull(f.now.model.rig.modifierOutput)
        assertFalse(expected.parameters.single { it.id == "ParamEyeRForm" }.blend)
        assertTrue(expected.parameters.single { it.id == "ParamEyeLForm" }.blend)
        f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        assertEquals(expected, f.now.model.rig.modifierOutput)
    }

    @Test fun independentEyeAftershockMatchesLockedVersionWithWideLids(): Unit = runBlocking {
        compareEyeAftershock("face/legacy", composed = true)
    }

    @Test fun independentEyeAftershockMatchesLockedVersionWithFittedHead(): Unit = runBlocking {
        compareEyeAftershock("face/fitted")
    }

    @Test fun independentEyeAftershockMatchesLockedVersionAfterSourceEyeBinding(): Unit = runBlocking {
        compareEyeAftershock("face/fitted", fittedVersion = 4)
    }

    private suspend fun compareEyeAftershock(head: String, composed: Boolean = false, fittedVersion: Int = 2) {
        val f = Fixture(document(eyeDetails = true, bilateralEyes = true))
        f.install()
        f.group(listOf("body/legacy", head) + if (fittedVersion == 2) listOf("eye/fitted") else emptyList())
        if (fittedVersion != 2) {
            f.edit("modifier_add", JsonObject(f.addition("eye/fitted", "eye") + ("version" to JsonPrimitive(fittedVersion))))
        }
        val dependency = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        if (composed) for (type in listOf("eye.wide", "eye.squash", "eye.lid-fade", "eye.smile")) {
            f.edit("modifier_add", f.addition(type, type, config = dependency))
        }
        val config = JsonObject(dependency + ("values" to buildJsonObject {
            put("softness", 138f)
        }))
        val addition = f.addition("eye.aftershock", "aftershock", config = config)
        val prefix = f.now
        val previous = assertNotNull(prefix.model.rig.modifierOutput)
        f.edit("modifier_add", addition)
        val legacy = f.now.model
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
        val expected = assertNotNull(legacy.rig.modifierOutput)
        f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        val current = f.now.model
        val actual = assertNotNull(current.rig.modifierOutput)
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.parameterTree, actual.parameterTree)
        assertEquals(expected.physics, actual.physics)
        assertEquals(listOf("PhysicsEyeLBounce", "PhysicsEyeRBounce").map { slot ->
            RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().outputId("physics:$slot")
        }, actual.physics.groups.map { it.id })
        for ((old, new) in expected.meshes.zip(actual.meshes)) {
            assertEquals(old, new, old.id)
        }
        assertTrue(expected == actual, "The complete generated IR must match the locked aftershock algorithm")

        val evaluator = CpuDeformationEvaluator()
        val pose = mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f,
            StandardParameters.EYE_L_OPEN to .36f, StandardParameters.EYE_R_OPEN to 0f,
            ParameterId("ParamEyeLBounce") to .67f, ParameterId("ParamEyeRBounce") to 1f)
        val oldPose = evaluator.evaluate(legacy.rig.puppet, pose)
        val newPose = evaluator.evaluate(current.rig.puppet, pose)
        for ((id, positions) in oldPose.worldPositions) assertContentEquals(positions, newPose.worldPositions.getValue(id), id.raw)
        assertEquals(oldPose.opacity, newPose.opacity)
        val plainPose = evaluator.evaluate(current.rig.puppet, pose + (ParameterId("ParamEyeLBounce") to 0f))
        val white = mesh(f, "white").id
        assertFalse(plainPose.worldPositions.getValue(white).contentEquals(newPose.worldPositions.getValue(white)))

        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val instance = stack.instances.last()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("eye.aftershock", 2))
        assertEquals(artifact.reference, instance.plugin)
        if (composed) {
            val directory = Files.createTempDirectory("eye-aftershock-package-")
            val preferences = Preferences.userRoot().node("eye-aftershock-package/" + UUID.randomUUID())
            val store = ModifierPluginStore(directory.resolve("installed"), preferences)
            try {
                val staging = Files.createDirectory(directory.resolve("package"))
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                for (name in artifact.manifest.files.keys) {
                    assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                }
                val providers = stack.instances.dropLast(1).map { provider ->
                    ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                        provider.id, provider.config, provider.outputAliases, provider.plugin)
                }
                val definition = RigEyeAftershockModifier.definition(RigModifierSource.of(current))
                val context = definition.authorContext(instance).withUpstream(providers)
                val geometry = assertNotNull(previous.meshes.single { it.id == white.raw }.geometry)
                val edited = previous.copy(meshes = previous.meshes.map {
                    if (it.id != white.raw) it else it.copy(geometry = geometry.copy(uvs = Floats.wrap(geometry.uvs.toArray().also { uv ->
                        uv[0] += .01f
                    })))
                })
                val topologyFailure = assertFailsWith<IllegalArgumentException> {
                    EyeAftershockAlgorithm().changes(edited, instance, context) {}
                }
                assertContains(topologyFailure.message.orEmpty(), "requires the captured lid topology and positions")
                val reparented = previous.copy(meshes = previous.meshes.map {
                    if (it.id != white.raw) it else it.copy(parent = null)
                })
                val carrierFailure = assertFailsWith<IllegalArgumentException> {
                    EyeAftershockAlgorithm().changes(reparented, instance, context) {}
                }
                assertContains(carrierFailure.message.orEmpty(), "cannot migrate a fitted lid carrier")

                val editedKeys = previous.copy(meshes = previous.meshes.map {
                    if (it.id != white.raw) it else it.copy(offsets = null)
                })
                val keysFailure = assertFailsWith<IllegalArgumentException> {
                    EyeAftershockAlgorithm().changes(editedKeys, instance, context) {}
                }
                assertContains(keysFailure.message.orEmpty(), "cannot migrate edited or lowered lid geometry")
                val editedParent = previous.meshes.single { it.id == white.raw }.parent
                val movedParent = previous.copy(deformers = previous.deformers.map {
                    if (it.id != editedParent || it !is io.github.psd2live.format.model.Deformer.Warp) it
                    else it.copy(lattice = it.lattice?.let { grid ->
                        grid.copy(cells = grid.cells.map { cell ->
                            cell.copy(form = cell.form.copy(points = Floats.wrap(cell.form.points.toArray().also { points ->
                                for (i in points.indices step 2) points[i] += .1f
                            })))
                        })
                    })
                })
                val frameFailure = assertFailsWith<IllegalArgumentException> {
                    EyeAftershockAlgorithm().changes(movedParent, instance, context) {}
                }
                assertContains(frameFailure.message.orEmpty(), "cannot migrate an edited neutral lid frame")

                repeat(2) {
                    val installed = store.install(staging.toString())
                    assertEquals(artifact.reference, installed.reference)
                    val algorithm = installed.manifest.algorithms.single()
                    val external = instance.copy(type = algorithm.type)
                    val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                        it.type == external.type && it.version == external.version
                    }
                    assertEquals(definition.optionalUpstream, adapter.optionalUpstream)
                    val workerContext = adapter.authorContext(external).withUpstream(providers.filter {
                        it.capability in adapter.requires + adapter.optionalUpstream
                    })
                    val worker = adapter.plugin as ContextualModifierAlgorithmPlugin
                    assertEquals(actual, worker.evaluate(previous, external, workerContext) {})
                    ModifierPluginWorkers.stop(installed.reference)
                    store.uninstall(installed.reference)
                }

                val now = f.now
                val history = f.runtime.history()
                val historyStore = WorkspaceStore(directory.resolve("project"))
                historyStore.persistHistory(now.projectId, history)
                val archive = directory.resolve("eye-aftershock.psd2live")
                val repository = ProjectRepository(writeHeadCache = false)
                repository.save(ProjectSaveCapture(now.projectId, history, JsonObject(emptyMap()), null, historyStore), archive)
                repository.open(archive).use { opened ->
                    val restored = opened.history.head().snapshot
                    assertEquals(instance, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.last())
                    assertEquals(actual, WorkspacePreviewBuilder().build(restored).rig.modifierOutput)
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                preferences.removeNode()
                ModifierPluginStore.deleteTree(directory)
            }
        }
        val compiled = RigIrCompiler.compile(current)
        f.edit("modifier_apply", buildJsonObject { put("id", "aftershock") })
        assertEquals(compiled, RigIrCompiler.compile(f.now.model))
        assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
    }

    @Test fun independentEyeAftershockMatchesConfiguredAndDisabledPhysics(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true, bilateralEyes = true))
        f.install()
        f.group(listOf("body/legacy", "face/fitted", "eye/fitted"))
        val prefix = f.now
        val slot = "PhysicsEyeLBounce"
        val physics = PhysicsGenerator.moduleRules(setOf("ParamEyeLOpen", "ParamEyeLBounce")).single()
        for (enabled in listOf(true, false)) {
            val config = buildJsonObject {
                put("scaffold", "structure")
                put("base", "eye")
                put("physicsEnabled", enabled)
                putJsonObject("physics") {
                    put(slot, physics.copy(name = "Custom blink spring", outputs = physics.outputs.map { it.copy(scale = 2f) }).toJson())
                }
            }
            val addition = f.addition("eye.aftershock", "spring", config = config)
            f.edit("modifier_add", addition)
            val expected = assertNotNull(f.now.model.rig.modifierOutput)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
            f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
            val actual = assertNotNull(f.now.model.rig.modifierOutput)
            assertEquals(expected.parameters, actual.parameters)
            assertEquals(expected.physics, actual.physics)
            assertEquals(enabled, actual.physics.groups.isNotEmpty())
            if (enabled) assertTrue(actual.physics.groups.any { it.name == "Custom blink spring" })
            assertEquals(expected, actual)
            f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
        }
    }

    @Test fun independentEyeSmileRejectsEarlierCoupledLidsAtomically(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true))
        f.install()
        f.group(listOf("face/legacy", "eye/fitted"))
        val config = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        f.edit("modifier_add", f.addition("eye.gaze-lid", "gaze", config = config))
        val before = f.now
        val addition = f.addition("eye.smile", "smile", config = config)
        val failure = assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        }
        assertContains(failure.message.orEmpty(), "cannot lower preceding coupled lid or brow bindings")
        assertEquals(before, f.now)
    }

    @Test fun dependentEyeModulesPauseWithoutTheirChosenBaseAndSurvivePrefixApplication(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "mouth/fitted", "hair/legacy"))
        f.edit("modifier_add", f.addition("eye.smile", "smile", config = buildJsonObject {
            put("scaffold", "structure"); put("base", "eye")
        }))
        f.edit("modifier_add", f.addition("eye.squash", "squash", config = buildJsonObject {
            put("scaffold", "structure"); put("base", "eye")
        }))
        f.edit("modifier_add", f.addition("brow/fitted", "brow"))
        val recipe = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "mouth" to BaseChoice("mouth/fitted"), "brow" to BaseChoice("brow/fitted"))).let {
            it.copy(modules = it.modules + ("eye.smile" to ModuleChoice(true)) + ("eye.squash" to ModuleChoice(true)))
        }
        val smile = ParameterId("ParamEyeLSmile")
        val poses = listOf(emptyMap(), mapOf(StandardParameters.EYE_L_OPEN to 0f, smile to 1f),
            mapOf(StandardParameters.EYE_L_OPEN to .4f, smile to .7f, StandardParameters.EYE_BALL_FORM to -.6f, StandardParameters.ANGLE_X to 18f, StandardParameters.BROW_L_Y to .5f))
        val active = f.now.model
        compare(native(f.document, recipe), active, poses)
        val config = buildJsonObject { put("scaffold", "structure"); put("base", "eye")
            putJsonObject("values") { put("arch", 150f); put("push", 75f) } }
        f.edit("modifier_update", buildJsonObject { put("id", "smile"); put("type", "eye.smile"); put("config", config) })
        assertNotEquals(RigIrCompiler.compile(active), RigIrCompiler.compile(f.now.model))
        val tunedRecipe = recipe.copy(modules = recipe.modules + ("eye.smile" to ModuleChoice(true, values = mapOf("arch" to 150f, "push" to 75f))))
        compare(native(f.document, tunedRecipe), f.now.model, poses)
        val expected = RigIrCompiler.compile(f.now.model)
        val report = requireNotNull(active.rig.modifierReport)
        assertTrue(report.getValue("entries").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "smile" }.jsonObject.getValue("contributions").jsonArray.isNotEmpty())
        val before = f.now
        assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_move", buildJsonObject { put("id", "smile"); put("index", 1) })
        }
        assertEquals(before.state, f.now.state)
        f.edit("modifier_enable", buildJsonObject { put("id", "eye"); put("enabled", false) })
        assertEquals("missing_dependency", f.now.model.rig.modifierReport!!.getValue("entries").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "smile" }.jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("missing_dependency", f.now.model.rig.modifierReport!!.getValue("entries").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == "squash"
        }.jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(f.now.model.rig.puppet.parameters.none { it.id == smile }, "a later brow modifier must not reactivate a paused eye module")
        assertEquals(RigIrCompiler.compile(f.now.model), RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_enable", buildJsonObject { put("id", "eye"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_apply", buildJsonObject { put("id", "eye") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals("active", f.now.model.rig.modifierReport!!.getValue("entries").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "smile" }.jsonObject.getValue("status").jsonPrimitive.content)
        f.edit("modifier_apply", buildJsonObject { put("id", "smile") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun shapeModulesKeepIndependentMorphsAndCombinedPosesWhenApplied(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        f.edit("modifier_add", f.addition(RigSourceModifiers.SCAFFOLD, "structure"))
        f.edit("modifier_apply", buildJsonObject { put("id", "structure") })
        val lash = mesh(f, "lash").id.raw
        f.edit("rig_create_warp", buildJsonObject {
            put("id", "authored_lash"); put("name", "Authored lash parent"); put("fit_local", false); put("rows", 4); put("columns", 4)
            putJsonArray("targets") { add("mesh:$lash") }
        })
        f.edit("modifier_add_group", buildJsonObject { put("group", "fitted")
            put("entries", JsonArray(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy").map {
                f.addition(it, it.substringBefore('/'))
            }))
        })
        val modules = listOf("brow.shape", "eye.emotion", "mouth.vowel", "eye.smile", "eye.gaze-lid", "brow.blink", "brow.lid-coupling", "mouth.cheek-lid")
        val recipe = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted"))).let {
            it.copy(modules = it.modules + modules.associateWith { ModuleChoice(true) })
        }
        val poses = listOf(emptyMap(),
            mapOf(ParameterId("ParamEyeBallLX") to .7f, ParameterId("ParamEyeBallLY") to -.4f),
            mapOf(ParameterId("ParamBrowLX") to .7f, ParameterId("ParamBrowLAngle") to -.5f, ParameterId("ParamBrowLForm") to .4f),
            mapOf(StandardParameters.EYE_L_OPEN to .4f, ParameterId("ParamEyeLForm") to .7f, ParameterId("ParamEyeLSquint") to .5f),
            mapOf(StandardParameters.MOUTH_OPEN to .6f, ParameterId("ParamA") to .4f, ParameterId("ParamU") to .6f),
            mapOf(StandardParameters.ANGLE_X to 18f, StandardParameters.ANGLE_Y to -12f, StandardParameters.BODY_LEAN to 3f,
                StandardParameters.EYE_L_OPEN to .3f, ParameterId("ParamEyeLForm") to -.6f, ParameterId("ParamEyeLSquint") to .3f,
                ParameterId("ParamEyeLSmile") to .6f, ParameterId("ParamEyeBallLX") to .7f, ParameterId("ParamEyeBallLY") to -.4f,
                ParameterId("ParamBrowLX") to -.6f, ParameterId("ParamBrowLAngle") to .7f, ParameterId("ParamBrowLForm") to -.5f,
                StandardParameters.MOUTH_OPEN to .4f, StandardParameters.MOUTH_FORM to .7f, ParameterId("ParamO") to .8f))
        for ((index, type) in modules.withIndex()) {
            f.edit("modifier_add", f.addition(type, type, config = buildJsonObject {
                put("scaffold", "structure"); put("base", type.substringBefore('.'))
                if (RigLegacyBindings.spec(type).related.isNotEmpty()) putJsonObject("dependencies") { put("eye/fitted", "eye") }
            }))
            val prefixRecipe = recipe.copy(modules = recipe.modules.mapValues { (id, choice) -> choice.copy(on = id in modules.take(index + 1)) })
            try { compare(native(f.document, prefixRecipe), f.now.model, poses) }
            catch (error: Throwable) { throw AssertionError("Stage $type: ${error.message}", error) }
        }
        assertTrue(mesh(f, "brow").blendShapes.any { it.parameterId == ParameterId("ParamBrowLX") })
        assertTrue(mesh(f, "mouth").blendShapes.any { it.parameterId == ParameterId("ParamA") })
        assertTrue(f.now.model.rig.modifierReport!!.getValue("entries").jsonArray.any { entry ->
            entry.jsonObject.getValue("contributions").jsonArray.any { raw ->
                val change = raw.jsonObject
                change.getValue("kind").jsonPrimitive.content == "deformer" && change.getValue("change").jsonPrimitive.content == "added"
            }
        }, "combined modules must exercise native carrier creation")
        val byId = f.now.model.rig.puppet.deformers.associateBy { it.id }
        var parent = mesh(f, "lash").parentDeformerId
        val ancestors = linkedSetOf<String>()
        while (parent != null) { ancestors += parent.raw; parent = byId.getValue(parent).parent }
        assertTrue("authored_lash" in ancestors, "native carriers must retain the authored parent")
        val exported = linkedMapOf<String, ByteArray>()
        io.github.psd2live.format.compile.Compiler.export(io.github.psd2live.targets.cubism.Cmo3Target(),
            RigIrCompiler.compile(f.now.model, tileArt = true), io.github.psd2live.format.compile.ExportOptions("modifiers")) { path, bytes -> exported[path] = bytes }
        val imported = Cmo3ModelImport.read(exported.getValue("modifiers.cmo3")).puppet
        val evaluator = CpuDeformationEvaluator()
        for (pose in poses) {
            val expectedFrame = evaluator.evaluate(f.now.model.rig.puppet, pose)
            val readFrame = evaluator.evaluate(imported, pose)
            for ((id, points) in expectedFrame.worldPositions) {
                val read = readFrame.worldPositions.getValue(id)
                assertEquals(points.size, read.size)
                assertTrue(points.indices.all { abs(points[it] - read[it]) < .03f }, "exported morph geometry differs on ${id.raw} at $pose (maximum ${points.indices.maxOf { abs(points[it] - read[it]) }})")
                assertEquals(expectedFrame.opacity.getValue(id), readFrame.opacity.getValue(id), .0001f)
            }
        }
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "brow.shape") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "mouth.cheek-lid") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun legacyEyeSourceMatchesLockedBindingWithAndWithoutOrdinaryHeadTurn(): Unit = runBlocking {
        for (head in listOf(false, true)) {
            val source = document(bilateralEyes = true)
            val f = Fixture(source)
            val reference = Fixture(source)
            for (fixture in listOf(f, reference)) {
                fixture.install()
                fixture.group(if (head) listOf("body/legacy", "face/legacy") else emptyList())
            }
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("tuning") { put("gazeX", 17f); put("gazeY", 22f) }
            }
            val addition = f.addition("eye/legacy", "eye", config = config)
            reference.edit("modifier_add", addition)
            val original = reference.now.model
            val oldStack = RigModifierStack.fromJson(reference.now.document.rigEdits.modifierStack!!)
            val locked = oldStack.instances.last()
            assertEquals(1, locked.version)
            assertEquals(BuiltinModifierPlugins.reference("eye/legacy", 1), locked.plugin)
            val input = assertNotNull(f.now.model.rig.modifierOutput)
            assertEquals(listOf(mesh(f, "lash-right").id), mesh(f, "iris-right").maskedBy)
            f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
            val actual = f.now.model
            val expected = RigIrCompiler.compile(original)
            val compiled = RigIrCompiler.compile(actual)
            assertEquals(expected.parameters, compiled.parameters)
            assertEquals(expected.parameterTree, compiled.parameterTree)
            assertEquals(expected.parameterLinks, compiled.parameterLinks)
            for ((before, after) in expected.meshes.zip(compiled.meshes)) assertEquals(before, after, before.id)
            for ((before, after) in expected.deformers.zip(compiled.deformers)) assertEquals(before, after, before.id)
            assertEquals(expected, compiled)
            assertEquals(listOf(mesh(f, "white-right").id), mesh(f, "iris-right").maskedBy)
            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find("eye/legacy", 2))
            assertEquals(artifact.reference, instance.plugin)
            val poses = listOf(
                mapOf(StandardParameters.EYE_L_OPEN to 0f, StandardParameters.EYE_R_OPEN to .63f,
                    StandardParameters.EYE_BALL_X to -.7f, StandardParameters.EYE_BALL_Y to .4f, StandardParameters.EYE_BALL_FORM to 1f),
                mapOf(StandardParameters.EYE_L_OPEN to .2f, StandardParameters.EYE_R_OPEN to 0f,
                    StandardParameters.EYE_BALL_X to .9f, StandardParameters.EYE_BALL_Y to -.35f, StandardParameters.EYE_BALL_FORM to -1f),
            ).map { pose -> if (!head) pose else pose + mapOf(StandardParameters.ANGLE_X to 29f,
                StandardParameters.ANGLE_Y to -17f, StandardParameters.ANGLE_Z to 12f) }
            val evaluator = CpuDeformationEvaluator()
            for (pose in poses) {
                val before = evaluator.evaluate(original.rig.puppet, pose)
                val after = evaluator.evaluate(actual.rig.puppet, pose)
                assertEquals(before.worldPositions.keys, after.worldPositions.keys)
                for ((id, points) in before.worldPositions) assertContentEquals(points, after.worldPositions.getValue(id), id.raw)
                assertEquals(before.opacity, after.opacity)
            }
            if (head) {
                val directory = Files.createTempDirectory("legacy-eye-package-")
                val preferences = Preferences.userRoot().node("legacy-eye-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val providers = stack.instances.dropLast(1).map { provider ->
                        ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                            provider.id, provider.config, provider.outputAliases, provider.plugin)
                    }
                    val definition = RigLegacyEyeDefinition.definition(RigModifierSource.of(actual))
                    val context = definition.authorContext(instance).withUpstream(providers)
                    repeat(2) {
                        val installed = store.install(staging.toString())
                        assertEquals(artifact.reference, installed.reference)
                        val result = ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(), input,
                            instance, {}, store, context = context, declaredParameters = definition.parameters(instance))
                        assertEquals(actual.rig.modifierOutput, result)
                        ModifierPluginWorkers.stop(installed.reference)
                        store.uninstall(installed.reference)
                        assertTrue(store.snapshot().none { it.reference == installed.reference })
                    }
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            f.edit("modifier_apply", buildJsonObject { put("id", "eye") })
            assertEquals(expected, RigIrCompiler.compile(f.now.model))
            assertEquals(expected, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentMouthCheekLidMatchesRecommendedImportAndInstalledWorker(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val choices = WorkspaceInitialPreset()
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, model) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct, choices,
            preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val locked = stack.instances.single { it.type == "mouth.cheek-lid" }
        assertEquals(1, locked.version)
        val index = stack.index(locked.id)
        assertTrue(stack.instances.take(index).map { it.type }.containsAll(listOf("face/fitted", "eye.gaze-lid", "brow.lid-coupling")))
        val outputs = assertNotNull(model.modifierEvaluation).outputs
        val input = outputs[index - 1]
        val expected = outputs[index]
        assertTrue(expected.deformers.size > input.deformers.size)
        assertTrue(expected.deformers.take(input.deformers.size) != input.deformers)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 3))
        val instance = locked.copy(version = 3, plugin = artifact.reference)
        val independent = stack.copy(instances = stack.instances.map { if (it.id == locked.id) instance else it })
        val updated = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = independent.toJson()))
        val actual = WorkspacePreviewBuilder().build(updated)
        assertEquals(expected, assertNotNull(actual.modifierEvaluation).outputs[index])
        assertEquals(model.rig.modifierOutput, actual.rig.modifierOutput)
        assertEquals(RigIrCompiler.compile(model), RigIrCompiler.compile(actual))

        val directory = Files.createTempDirectory("mouth-cheek-lid-v3-package-")
        val preferences = Preferences.userRoot().node("mouth-cheek-lid-v3-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val definition = RigMouthCheekLidModifier.fittedDefinition(RigModifierSource.of(actual))
            val upstream = stack.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = definition.authorContext(instance).withUpstream(upstream)
            val installed = store.install(staging.toString())
            assertEquals(artifact.reference, installed.reference)
            assertEquals(expected, ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(), input,
                instance, {}, store, context = context))
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun independentMouthCheekLidMatchesFittedHead(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true))
        f.install()
        f.group(listOf("body/legacy", "face/fitted", "eye/fitted", "brow/fitted", "mouth/fitted"))
        val config = buildJsonObject {
            put("scaffold", "structure"); put("base", "mouth")
            putJsonObject("dependencies") { put("eye/fitted", "eye") }
        }
        val prefix = f.now
        val addition = f.addition("mouth.cheek-lid", "cheek-lid", config = config)
        f.edit("modifier_add", addition)
        val expected = assertNotNull(f.now.model.rig.modifierOutput)
        f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(3))))
        val actual = assertNotNull(f.now.model.rig.modifierOutput)
        assertEquals(expected.deformers.map { it.id }, actual.deformers.map { it.id })
        for ((before, after) in expected.meshes.zip(actual.meshes)) assertEquals(before, after, before.id)
        assertEquals(expected, actual)
    }

    @Test fun independentMouthCheekLidMatchesLockedVersionAndInstalledWorker(): Unit = runBlocking {
        for (paired in listOf(false, true)) {
            val art = document(eyeDetails = true, bilateralEyes = true, mouthDetails = true)
            val source = if (!paired) art else {
                val sourceArt = art.source as WorkspaceSourceArt
                val white = sourceArt.layers.single { it.id.raw == "white" } as WorkspaceSourceLayer
                val shared = white.copy(bounds = LayerBounds(18, 20, 28, 8), raster = LayerRaster(28, 8,
                    ByteArray(28 * 8 * 4) { i -> if ((i / 4) % 28 in 12..15) 0 else 255.toByte() }))
                art.copy(source = sourceArt.copy(layers = sourceArt.layers.filterNot { it.id.raw == "white-right" }
                    .map { if (it == white) shared else it }),
                    layerOverrides = (art.layerOverrides - "white-right") +
                        ("white" to art.layerOverrides.getValue("white").copy(side = Side.NONE)))
            }
            suspend fun setup() = Fixture(source).also { fixture ->
                fixture.install()
                fixture.group(listOf("body/legacy", "face/legacy", "eye/fitted", "mouth/fitted"))
                if (paired) {
                    val eyeConfig = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
                    for (type in listOf("eye.smile", "eye.wide", "eye.lid-fade", "eye.emotion")) {
                        fixture.edit("modifier_add", fixture.addition(type, type, config = eyeConfig))
                    }
                }
            }
            val reference = setup()
            val fixture = setup()
            val input = assertNotNull(fixture.now.model.rig.modifierOutput)
            val neutral = fixture.now.model.rig.puppet
            val config = buildJsonObject {
                put("scaffold", "structure"); put("base", "mouth")
                putJsonObject("dependencies") { put("eye/fitted", "eye") }
                putJsonObject("values") { put("strength", 145f) }
            }
            val addition = fixture.addition("mouth.cheek-lid", "cheek-lid", config = config)
            reference.edit("modifier_add", addition)
            fixture.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
            val old = RigModifierStack.fromJson(reference.now.document.rigEdits.modifierStack!!).instances.last()
            assertEquals(1, old.version)
            assertEquals(BuiltinModifierPlugins.reference(old.type, 1), old.plugin)
            val actual = fixture.now.model
            val expected = assertNotNull(reference.now.model.rig.modifierOutput)
            val output = assertNotNull(actual.rig.modifierOutput)
            assertEquals(expected.deformers.map { it.id }, output.deformers.map { it.id })
            for ((before, after) in expected.meshes.zip(output.meshes)) {
                assertTrue(before == after, "Cheek and lid mesh differs: ${before.id}, paired=$paired")
            }
            assertTrue(expected == output, "Complete cheek and lid IR differs")
            if (paired) assertTrue(output.deformers.size > input.deformers.size, "Paired lids must exercise a coupling carrier")
            val pose = mapOf(StandardParameters.MOUTH_FORM to 1f, StandardParameters.EYE_L_OPEN to .9f,
                StandardParameters.EYE_R_OPEN to .8f)
            val evaluator = CpuDeformationEvaluator()
            val before = evaluator.evaluate(neutral, pose)
            val after = evaluator.evaluate(actual.rig.puppet, pose)
            val lash = actual.rig.puppet.drawables.single { it.name == "lash" }.id
            assertFalse(before.worldPositions.getValue(lash).contentEquals(after.worldPositions.getValue(lash)),
                "Smiling must move a fitted lid")

            if (paired) {
                val stack = RigModifierStack.fromJson(fixture.now.document.rigEdits.modifierStack!!)
                val instance = stack.instances.last()
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find("mouth.cheek-lid", 2))
                assertEquals(artifact.reference, instance.plugin)
                val directory = Files.createTempDirectory("mouth-cheek-lid-package-")
                val preferences = Preferences.userRoot().node("mouth-cheek-lid-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val upstream = stack.instances.dropLast(1).map {
                        ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                    }
                    val context = RigMouthCheekLidModifier.definition(RigModifierSource.of(actual)).authorContext(instance).withUpstream(upstream)
                    val installed = store.install(staging.toString())
                    assertEquals(artifact.reference, installed.reference)
                    assertTrue(output == ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(), input,
                        instance, {}, store, context = context), "Installed JVM worker differs")
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            fixture.edit("modifier_apply", buildJsonObject { put("id", "cheek-lid") })
            assertEquals(RigIrCompiler.compile(reference.now.model), RigIrCompiler.compile(fixture.now.model))
            assertEquals(RigIrCompiler.compile(reference.now.model), RigIrCompiler.compile(WorkspacePreviewBuilder().build(fixture.now.document)))
        }
    }

    @Test fun independentBrowLidMatchesRecommendedImportAndInstalledWorker(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val choices = WorkspaceInitialPreset()
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, model) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct, choices,
            preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val locked = stack.instances.single { it.type == "brow.lid-coupling" }
        assertEquals(1, locked.version)
        val index = stack.index(locked.id)
        assertTrue(stack.instances.take(index).map { it.type }.containsAll(listOf("face/fitted", "eye.gaze-lid")))
        val outputs = assertNotNull(model.modifierEvaluation).outputs
        val input = outputs[index - 1]
        val expected = outputs[index]
        assertTrue(expected.deformers.size > input.deformers.size)
        assertEquals(input.deformers.map { it.id }, expected.deformers.take(input.deformers.size).map { it.id })
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 3))
        val instance = locked.copy(version = 3, plugin = artifact.reference)
        val independent = stack.copy(instances = stack.instances.map { if (it.id == locked.id) instance else it })
        val updated = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = independent.toJson()))
        val actual = WorkspacePreviewBuilder().build(updated)
        val actualStage = assertNotNull(actual.modifierEvaluation).outputs[index]
        assertEquals(expected.deformers.map { it.id }, actualStage.deformers.map { it.id })
        for ((old, new) in expected.meshes.zip(actualStage.meshes)) {
            assertEquals(old.parent, new.parent, old.id)
            assertEquals(old.shapes.map { it.parameter }, new.shapes.map { it.parameter }, old.id)
            assertTrue(old == new, "Brow and lid mesh differs: ${old.id}")
        }
        assertTrue(expected == actualStage, "Complete brow and lid stage IR differs")
        assertEquals(model.rig.modifierOutput, actual.rig.modifierOutput)
        assertEquals(RigIrCompiler.compile(model), RigIrCompiler.compile(actual))

        val directory = Files.createTempDirectory("brow-lid-v3-package-")
        val preferences = Preferences.userRoot().node("brow-lid-v3-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val definition = RigBrowLidModifier.fittedDefinition(RigModifierSource.of(actual))
            val upstream = stack.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = definition.authorContext(instance).withUpstream(upstream)
            val installed = store.install(staging.toString())
            assertEquals(artifact.reference, installed.reference)
            assertEquals(expected, ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(), input,
                instance, {}, store, context = context))
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun independentBrowLidMatchesLockedVersionAndInstalledWorker(): Unit = runBlocking {
        for (composed in listOf(false, true)) {
            val art = document(eyeDetails = true, pairedBrows = true, bilateralEyes = true, bangOverlay = composed)
            val source = if (!composed) art else {
                val sourceArt = art.source as WorkspaceSourceArt
                val white = sourceArt.layers.single { it.id.raw == "white" } as WorkspaceSourceLayer
                val paired = white.copy(bounds = LayerBounds(18, 20, 28, 8), raster = LayerRaster(28, 8,
                    ByteArray(28 * 8 * 4) { i -> if ((i / 4) % 28 in 12..15) 0 else 255.toByte() }))
                art.copy(source = sourceArt.copy(layers = sourceArt.layers.filterNot { it.id.raw == "white-right" }
                    .map { if (it == white) paired else it }),
                    layerOverrides = (art.layerOverrides - "white-right") +
                        ("white" to art.layerOverrides.getValue("white").copy(side = Side.NONE)))
            }
            suspend fun setup(): Fixture {
                val f = Fixture(source)
                f.install()
                f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted"))
                val eyeConfig = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
                val browConfig = buildJsonObject { put("scaffold", "structure"); put("base", "brow") }
                if (composed) f.edit("modifier_add", f.addition("brow.shape", "shape", config = browConfig))
                if (composed) {
                    for (type in listOf("eye.smile", "eye.squash", "eye.wide", "eye.lid-fade", "eye.emotion", "eye.aftershock")) {
                        f.edit("modifier_add", f.addition(type, type, config = eyeConfig))
                    }

                }
                return f
            }
            val f = setup()
            val reference = setup()
            val config = buildJsonObject {
                put("scaffold", "structure"); put("base", "brow")
                putJsonObject("dependencies") { put("eye/fitted", "eye") }
                putJsonObject("values") { put("strength", 145f) }
            }
            val input = assertNotNull(f.now.model.rig.modifierOutput)
            val addition = f.addition("brow.lid-coupling", "coupling", config = config)
            reference.edit("modifier_add", addition)
            val original = reference.now.model
            val old = RigModifierStack.fromJson(reference.now.document.rigEdits.modifierStack!!).instances.last()
            assertEquals(1, old.version)
            assertEquals(BuiltinModifierPlugins.reference(old.type, 1), old.plugin)
            f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
            val actual = f.now.model
            val expected = assertNotNull(original.rig.modifierOutput)
            val output = assertNotNull(actual.rig.modifierOutput)
            if (composed) assertTrue(expected.deformers.size > input.deformers.size, "Paired lids must exercise a coupling carrier")
            assertEquals(expected.deformers.map { it.id }, output.deformers.map { it.id })
            for ((before, after) in expected.meshes.zip(output.meshes)) {
                assertEquals(before.parent, after.parent, before.id)
                assertEquals(before.offsets?.axes, after.offsets?.axes, before.id)
                assertEquals(before.geometry, after.geometry, "Rest geometry: ${before.id}, composed=$composed")
                assertEquals(before.shapes.map { it.parameter }, after.shapes.map { it.parameter }, "Blend shapes: ${before.id}, composed=$composed")
                assertEquals(before.channels, after.channels, "Channels: ${before.id}, composed=$composed")
                val cells = before.offsets?.cells.orEmpty().zip(after.offsets?.cells.orEmpty())
                val mismatch = cells.indexOfFirst { (old, new) -> old != new }
                assertTrue(mismatch == -1, "Offsets: ${before.id}, composed=$composed, cell=$mismatch")
                assertTrue(before == after, "Mesh differs: ${before.id}, composed=$composed")
            }
            assertTrue(expected == output, "Complete brow and lid IR differs")
            val pose = mapOf(
                StandardParameters.ANGLE_X to 19f, StandardParameters.ANGLE_Y to -11f,
                StandardParameters.EYE_L_OPEN to .84f, StandardParameters.EYE_R_OPEN to .15f,
                StandardParameters.BROW_L_Y to -.8f, StandardParameters.BROW_R_Y to .6f,
                ParameterId("ParamBrowLAngle") to -.55f, ParameterId("ParamBrowRAngle") to .4f,
            )
            val evaluator = CpuDeformationEvaluator()
            val before = evaluator.evaluate(original.rig.puppet, pose)
            val after = evaluator.evaluate(actual.rig.puppet, pose)
            for ((id, positions) in before.worldPositions) assertContentEquals(positions, after.worldPositions.getValue(id), id.raw)
            assertEquals(before.opacity, after.opacity)

            if (composed) {
                val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
                val instance = stack.instances.last()
                val artifact = assertNotNull(BundledAlgorithmArtifacts.find("brow.lid-coupling", 2))
                assertEquals(artifact.reference, instance.plugin)
                val directory = Files.createTempDirectory("brow-lid-package-")
                val preferences = Preferences.userRoot().node("brow-lid-package/" + UUID.randomUUID())
                val store = ModifierPluginStore(directory.resolve("installed"), preferences)
                try {
                    val staging = Files.createDirectory(directory.resolve("package"))
                    Files.write(staging.resolve("plugin.json"), artifact.bytes)
                    for (name in artifact.manifest.files.keys) {
                        assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                    }
                    val upstream = stack.instances.dropLast(1).map {
                        ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                    }
                    val context = RigBrowLidModifier.definition(RigModifierSource.of(actual)).authorContext(instance).withUpstream(upstream)
                    repeat(2) {
                        val installed = store.install(staging.toString())
                        assertEquals(artifact.reference, installed.reference)
                        assertTrue(output == ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(), input,
                            instance, {}, store, context = context), "Installed worker differs")
                        ModifierPluginWorkers.stop(installed.reference)
                        store.uninstall(installed.reference)
                    }
                    val history = f.runtime.history()
                    val historyStore = WorkspaceStore(directory.resolve("project"))
                    historyStore.persistHistory(f.now.projectId, history)
                    val archive = directory.resolve("brow-lid.psd2live")
                    val repository = ProjectRepository(writeHeadCache = false)
                    repository.save(ProjectSaveCapture(f.now.projectId, history, JsonObject(emptyMap()), null, historyStore), archive)
                    repository.open(archive).use { opened ->
                        val restored = opened.history.head().snapshot
                        assertEquals(instance, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.last())
                        assertTrue(output == WorkspacePreviewBuilder().build(restored).rig.modifierOutput, "Saved coupling differs")
                    }
                } finally {
                    store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                    preferences.removeNode()
                    ModifierPluginStore.deleteTree(directory)
                }
            }
            if (composed) {
                val browConfig = buildJsonObject { put("scaffold", "structure"); put("base", "brow") }
                for (fixture in listOf(reference, f)) for (type in listOf("brow.blink", "brow.spring")) {
                    fixture.edit("modifier_add", fixture.addition(type, type, config = browConfig))
                }
                assertTrue(reference.now.model.rig.modifierOutput == f.now.model.rig.modifierOutput, "Following brow modules differ")
            }
            f.edit("modifier_apply", buildJsonObject { put("id", "coupling") })
            assertEquals(RigIrCompiler.compile(reference.now.model), RigIrCompiler.compile(f.now.model))
            assertEquals(RigIrCompiler.compile(reference.now.model), RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        }
    }

    @Test fun independentEyeWideMatchesLockedVersionWithPreviousEyeModules(): Unit = runBlocking {
        compareEyeWide("face/legacy", composed = true, worker = true)
    }

    @Test fun independentEyeWideMatchesLockedVersionWithFittedHead(): Unit = runBlocking {
        compareEyeWide("face/fitted")
    }

    private suspend fun compareEyeWide(head: String, composed: Boolean = false, worker: Boolean = false) {
        val f = Fixture(document(eyeDetails = true, bilateralEyes = true)); f.install()
        f.group(listOf("body/legacy", head, "eye/fitted"))
        val config = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        if (composed) for (type in listOf("eye.smile", "eye.squash", "eye.lid-fade", "eye.emotion", "eye.aftershock")) {
            f.edit("modifier_add", f.addition(type, type, config = config))
        }
        val wideConfig = JsonObject(config + ("values" to buildJsonObject { put("lift", 32f) }))
        val previous = assertNotNull(f.now.model.rig.modifierOutput)
        f.edit("modifier_add", f.addition("eye.wide", "old-wide", config = wideConfig))
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
        val expected = assertNotNull(f.now.model.rig.modifierOutput)
        val legacyPreview = f.now.model
        f.edit("modifier_remove", buildJsonObject { put("id", "old-wide") })
        val addition = f.addition("eye.wide", "wide", config = wideConfig)
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        val actual = assertNotNull(f.now.model.rig.modifierOutput)
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.meshes.map { it.id }, actual.meshes.map { it.id })
        for ((old, new) in expected.meshes.zip(actual.meshes)) {
            assertEquals(old.parent, new.parent, old.id)
            assertEquals(old.offsets?.axes, new.offsets?.axes, old.id)
            assertEquals(old.offsets, new.offsets, "geometry of " + old.id)
            assertEquals(old.channels, new.channels, old.id)
        }
        assertTrue(expected == actual, "The complete generated IR must match the historical widening")
        val evaluator = CpuDeformationEvaluator()
        val pose = mapOf(StandardParameters.ANGLE_X to 20f, StandardParameters.ANGLE_Y to -12f,
            StandardParameters.EYE_BALL_X to .6f, StandardParameters.EYE_BALL_Y to -.4f,
            StandardParameters.EYE_L_OPEN to 1.75f, StandardParameters.EYE_R_OPEN to 2f)
        val left = evaluator.evaluate(legacyPreview.rig.puppet, pose)
        val right = evaluator.evaluate(f.now.model.rig.puppet, pose)
        for ((id, positions) in left.worldPositions) assertContentEquals(positions, right.worldPositions.getValue(id), id.raw)
        assertEquals(left.opacity, right.opacity)
        val white = mesh(f, "white")
        val neutral = evaluator.evaluate(f.now.model.rig.puppet, pose + (StandardParameters.EYE_L_OPEN to 1f))
        assertTrue(!neutral.worldPositions.getValue(white.id).contentEquals(right.worldPositions.getValue(white.id)))
        assertEquals(0f, right.opacity.getValue(mesh(f, "close").id))
        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val instance = stack.instances.last()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("eye.wide", 2))
        assertEquals(artifact.reference, instance.plugin)
        if (worker) {
            val directory = Files.createTempDirectory("eye-wide-package-")
            val preferences = Preferences.userRoot().node("eye-wide-package/" + UUID.randomUUID())
            val store = ModifierPluginStore(directory.resolve("installed"), preferences)
            try {
                val staging = Files.createDirectory(directory.resolve("package"))
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                for (name in artifact.manifest.files.keys) {
                    assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                }
                val providers = stack.instances.dropLast(1).map { provider ->
                    ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                        provider.id, provider.config, provider.outputAliases, provider.plugin)
                }
                val definition = RigEyeWideModifier.definition(RigModifierSource.of(f.now.model))
                val context = definition.authorContext(instance).withUpstream(providers)
                repeat(2) {
                    val installed = store.install(staging.toString())
                    assertEquals(artifact.reference, installed.reference)
                    val result = ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                        previous, instance, {}, store, context = context, declaredParameters = definition.parameters(instance))
                    assertEquals(actual, result)
                    ModifierPluginWorkers.stop(installed.reference)
                    store.uninstall(installed.reference)
                    assertTrue(store.snapshot().none { it.reference == installed.reference })
                }
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                preferences.removeNode()
                ModifierPluginStore.deleteTree(directory)
            }
        }
        val compiled = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "wide") })
        assertEquals(compiled, RigIrCompiler.compile(f.now.model))
        assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
    }


    @Test fun eyeSquashSourceMatchesLockedVersionAndAppliesWithWideLids(): Unit = runBlocking {
        compareEyeSquash()
    }

    @Test fun packagedEyeSquashMatchesLockedVersionAndAppliesWithWideLids(): Unit = runBlocking {
        compareEyeSquash(worker = true)
    }

    @Test fun eyeSquashSourceRejectsFittedHeadTurnsAtomically(): Unit = runBlocking {
        compareEyeSquash(head = "face/fitted")
    }

    private suspend fun compareEyeSquash(worker: Boolean = false, head: String = "face/legacy") {
        val f = Fixture(document(eyeDetails = true)); f.install()
        f.group(listOf("body/legacy", head, "eye/fitted"))
        val config = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        f.edit("modifier_add", f.addition("eye.wide", "wide", config = config))
        val iris = mesh(f, "iris").id.raw
        val original = mesh(f, "iris")
        val authored = original.geometryGrid!!.cells.first().form.positionDeltas.mapIndexed { i, value ->
            JsonPrimitive(value + if (i % 2 == 1) .005f else 0f)
        }
        f.edit("keyform_apply", buildJsonObject { putJsonArray("changes") { addJsonObject {
            put("op", "set"); put("target", "mesh:$iris")
            putJsonObject("key") {
                val grid = original.geometryGrid!!
                grid.axes.forEachIndexed { axis, entry -> put(entry.parameterId.raw, entry.keys[grid.cells.first().coordinate[axis]]) }
            }
            putJsonObject("geometry") { put("positionDeltas", JsonArray(authored)) }
        } } })
        val targets = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single { it.id == "structure" }.targets
        fun addition(name: String, version: Int) = JsonObject(f.addition(name, if (version == 1) "old-squash" else "squash", targets, config) +
            ("version" to JsonPrimitive(version)))
        f.edit("modifier_add", addition("eye.squash", 1))
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_remove", buildJsonObject { put("id", "old-squash") })
        if (head == "face/fitted") {
            val before = f.now
            val error = assertFailsWith<WorkspaceBatchEditException> { f.edit("modifier_add", addition("eye.squash", 2)) }
            assertContains(error.message.orEmpty(), "cannot lower fitted head-turn carriers")
            assertEquals(before, f.now)
            return
        }
        val input = requireNotNull(f.now.model.rig.modifierOutput)
        f.edit("modifier_add", addition("eye.squash", 2))
        val actual = RigIrCompiler.compile(f.now.model)
        assertEquals(expected, actual)
        if (worker) {
            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val artifact = requireNotNull(BundledAlgorithmArtifacts.find("eye.squash", 2))
            val entry = ModifierPluginStore.shared.snapshot().single { it.reference == artifact.reference }
            val providers = stack.instances.dropLast(1).map { provider ->
                ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                    provider.id, provider.config, provider.outputAliases, provider.plugin)
            }
            val definition = RigEyeSquashModifier.definition(RigModifierSource.of(f.now.model))
            val context = definition.authorContext(instance).withUpstream(providers)
            try {
                val result = ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(),
                    input, instance, {}, context = context)
                assertEquals(f.now.model.rig.modifierOutput, result)
            } finally {
                ModifierPluginWorkers.stop(entry.reference)
            }
        }
        f.edit("modifier_apply", buildJsonObject { put("id", "squash") })
        assertEquals(actual, RigIrCompiler.compile(f.now.model))
        assertEquals(actual, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
    }

    @Test fun independentEyeHighlightMatchesLockedVersionAndInstalledWorker(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true, bilateralEyes = true))
        f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted"))
        val dependency = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        for (type in listOf("eye.wide", "eye.lid-fade")) {
            f.edit("modifier_add", f.addition(type, type, config = dependency))
        }
        val config = JsonObject(dependency + mapOf(
            "values" to buildJsonObject { put("parallax", 148f) },
            "dependencies" to buildJsonObject { put("face/legacy", "face") },
        ))
        val addition = f.addition("eye.highlight", "highlight", config = config)
        val prefix = f.now
        val previous = assertNotNull(prefix.model.rig.modifierOutput)
        f.edit("modifier_add", addition)
        val expected = assertNotNull(f.now.model.rig.modifierOutput)
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
        f.runtime.checkout(f.now.projectId, f.now.state, prefix.historyHead)
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(2))))
        val current = f.now.model
        val actual = assertNotNull(current.rig.modifierOutput)
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.parameterTree, actual.parameterTree)
        for ((old, new) in expected.meshes.zip(actual.meshes)) assertEquals(old, new, old.id)
        assertEquals(expected, actual)

        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val instance = stack.instances.last()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("eye.highlight", 2))
        assertEquals(artifact.reference, instance.plugin)
        val directory = Files.createTempDirectory("eye-highlight-package-")
        val preferences = Preferences.userRoot().node("eye-highlight-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val providers = stack.instances.dropLast(1).map { provider ->
                ModifierUpstreamCapability(provider.type, provider.type, provider.version, provider.targets,
                    provider.id, provider.config, provider.outputAliases, provider.plugin)
            }
            val definition = RigEyeHighlightModifier.definition(RigModifierSource.of(current))
            val context = definition.authorContext(instance).withUpstream(providers)
            val id = mesh(f, "highlight").id.raw
            val original = previous.meshes.single { it.id == id }
            val authored = original.copy(opacity = .65f)
            val withAuthoredOpacity = previous.copy(meshes = previous.meshes.map { if (it.id == id) authored else it })
            val residual = EyeHighlightAlgorithm().changes(withAuthoredOpacity, instance, context) {}.meshes.updated.single()
            assertEquals(authored, residual.before)
            val fade = residual.after.channels.getValue(Channel.OPACITY)
            assertEquals(.65f, (fade.cells.last().form as ChannelValue.Scalar).value, .00001f)
            assertEquals(actual.meshes.single { it.id == id }.offsets, residual.after.offsets)

            repeat(2) {
                val installed = store.install(staging.toString())
                assertEquals(artifact.reference, installed.reference)
                val algorithm = installed.manifest.algorithms.single()
                val external = instance.copy(type = algorithm.type)
                val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single {
                    it.type == external.type && it.version == external.version
                }
                val workerContext = adapter.authorContext(external).withUpstream(providers.filter {
                    it.capability in adapter.requires + adapter.optionalUpstream
                })
                assertEquals(actual, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(previous, external, workerContext) {})
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
        val second = f.addition("eye.highlight", "highlight-again", config = config)
        f.edit("modifier_add", JsonObject(second + ("version" to JsonPrimitive(2))))
        assertEquals(actual, f.now.model.rig.modifierOutput)
        val compiled = RigIrCompiler.compile(current)
        f.edit("modifier_apply", buildJsonObject { put("id", "highlight") })
        assertEquals(compiled, RigIrCompiler.compile(f.now.model))
        assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
    }

    @Test fun independentEyeHighlightUsesFittedHeadTurnAndKeepsPriorHighlightFade(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true, bilateralEyes = true)); f.install()
        f.group(listOf("body/legacy", "face/fitted"))
        f.edit("modifier_add", JsonObject(f.addition("eye/fitted", "eye") + ("version" to JsonPrimitive(4))))
        val config = buildJsonObject {
            put("scaffold", "structure"); put("base", "eye")
            putJsonObject("dependencies") { put("face/legacy", "face") }
        }
        fun addition(id: String, parallax: Float, version: Int) = JsonObject(f.addition("eye.highlight", id,
            config = JsonObject(config + ("values" to buildJsonObject { put("parallax", parallax) }))) +
            ("version" to JsonPrimitive(version)))
        val before = f.now
        f.edit("modifier_add", addition("highlight", 70f, 1))
        f.edit("modifier_add", addition("second", 153f, 1))
        val expected = f.now.model
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        f.edit("modifier_add", addition("highlight", 70f, 2))
        f.edit("modifier_apply", buildJsonObject { put("id", "highlight") })
        f.edit("modifier_add", addition("second", 153f, 2))
        compare(expected, f.now.model, listOf(
            emptyMap(),
            mapOf(StandardParameters.EYE_L_OPEN to .4f, StandardParameters.ANGLE_X to -26f, StandardParameters.ANGLE_Y to 16f),
            mapOf(StandardParameters.EYE_L_OPEN to .7f, StandardParameters.ANGLE_X to 29f, StandardParameters.ANGLE_Y to -23f),
        ))
    }

    @Test fun widenedLidsFadingLinesAndHighlightsComposeWithHeadTurnsAndApplyAsOrdinaryContent(): Unit = runBlocking {
        widenedLidsFadingLinesAndHighlightsCompose("eye.lid-fade", 2)
    }

    @Test fun independentEyeWideComposesWithLaterFadeAndHighlight(): Unit = runBlocking {
        widenedLidsFadingLinesAndHighlightsCompose("eye.lid-fade", 2, wideVersion = 2)
    }

    @Test fun independentEyeWideRejectsEarlierHighlightAtomically(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true)); f.install()
        f.group(listOf("face/legacy", "eye/fitted"))
        val config = buildJsonObject { put("scaffold", "structure"); put("base", "eye") }
        f.edit("modifier_add", f.addition("eye.highlight", "highlight", config = JsonObject(config + ("dependencies" to buildJsonObject {
            put("face/legacy", "face")
        }))))
        val before = f.now
        val error = assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_add", JsonObject(f.addition("eye.wide", "wide", config = config) + ("version" to JsonPrimitive(2))))
        }
        assertContains(error.message.orEmpty(), "cannot lower coupled lid, highlight or brow bindings")
        assertEquals(before, f.now)
    }

    @Test fun packagedLidFadeMatchesTheNativeRecipeAndSurvivesApplyingItsPrefix(): Unit = runBlocking {
        val artifact = requireNotNull(BundledAlgorithmArtifacts.find("eye.lid-fade", 2))
        widenedLidsFadingLinesAndHighlightsCompose(artifact.manifest.algorithms.single().type, 2)
    }

    @Test fun historicalLidFadeMatchesTheNativeRecipeAndSurvivesApplyingItsPrefix(): Unit = runBlocking {
        widenedLidsFadingLinesAndHighlightsCompose("eye.lid-fade", 1)
    }

    private suspend fun widenedLidsFadingLinesAndHighlightsCompose(fadeType: String, fadeVersion: Int, wideVersion: Int = 1) {
        val f = Fixture(document(eyeDetails = true)); f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy"))
        val modules = listOf("eye.wide", "eye.lid-fade", "eye.highlight")
        val base = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
        val poses = listOf(emptyMap(), mapOf(StandardParameters.EYE_L_OPEN to 0f), mapOf(StandardParameters.EYE_L_OPEN to .35f),
            mapOf(StandardParameters.EYE_L_OPEN to 1.7f),
            mapOf(StandardParameters.EYE_L_OPEN to .6f, StandardParameters.ANGLE_X to 20f, StandardParameters.ANGLE_Y to -14f,
                StandardParameters.EYE_BALL_X to .6f, StandardParameters.EYE_BALL_Y to -.5f),
            mapOf(StandardParameters.EYE_L_OPEN to 2f, StandardParameters.ANGLE_X to -25f, StandardParameters.ANGLE_Y to 18f))
        fun recipe(active: List<String>) = base.copy(modules = base.modules + active.associateWith { ModuleChoice(true) })
        for ((index, type) in modules.withIndex()) {
            val addition = f.addition(if (type == "eye.lid-fade") fadeType else type, type, config = buildJsonObject {
                put("scaffold", "structure"); put("base", "eye")
                if (type == "eye.highlight") putJsonObject("dependencies") { put("face/legacy", "face") }
            })
            val version = when (type) {
                "eye.lid-fade" -> fadeVersion
                "eye.wide" -> wideVersion
                else -> null
            }
            f.edit("modifier_add", if (version == null) addition else JsonObject(addition + ("version" to JsonPrimitive(version))))
            try { compare(native(f.document, recipe(modules.take(index + 1))), f.now.model, poses) }
            catch (error: Throwable) { throw AssertionError("Stage $type: ${error.message}", error) }
        }
        validateOperationSchema(WorkspaceModifierQueries.describe(f.now), WorkspaceModifierSchemas.stack, "result")
        assertEquals(2f, f.now.model.rig.puppet.parameters.single { it.id == StandardParameters.EYE_L_OPEN }.max)
        val wide = f.now.model.rig.modifierReport!!.getValue("entries").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == "eye.wide" }.jsonObject
        assertTrue(wide.getValue("contributions").jsonArray.any { it.jsonObject["kind"]?.jsonPrimitive?.content == "parameter" &&
            it.jsonObject["id"]?.jsonPrimitive?.content == StandardParameters.EYE_L_OPEN.raw && it.jsonObject["change"]?.jsonPrimitive?.content == "modified" })
        val owned = wide.getValue("owned_keyforms").jsonArray.map { it.jsonObject }.first {
            it["channel"] == null &&
                it["key"]?.jsonObject?.get(StandardParameters.EYE_L_OPEN.raw)?.jsonPrimitive?.float == 1.5f
        }
        val target = owned.getValue("id").jsonPrimitive.content
        val kind = owned.getValue("kind").jsonPrimitive.content
        val key = owned.getValue("key").jsonObject.mapValues { it.value.jsonPrimitive.float }
        fun <F> points(grid: KeyformGrid<F>, values: (F) -> FloatArray) = values(grid.cells.single { cell ->
            grid.axes.indices.all { index -> grid.axes[index].keys[cell.coordinate[index]] == key.getValue(grid.axes[index].parameterId.raw) }
        }.form)
        val points = if (kind == "warp") points(f.now.model.rig.puppet.deformers.filterIsInstance<Deformer.Warp>().single { it.id.raw == target }.geometryGrid!!) { it.controlPoints }
            else points(f.now.model.rig.puppet.drawables.single { it.id.raw == target }.geometryGrid!!) { it.positionDeltas }
        f.edit("keyform_apply", buildJsonObject { putJsonArray("changes") { addJsonObject {
            put("op", "set"); put("target", "$kind:$target"); put("key", owned.getValue("key"))
            putJsonObject("geometry") { put(if (kind == "warp") "controlPoints" else "positionDeltas", JsonArray(points.mapIndexed { index, value -> JsonPrimitive(value + if (index % 2 == 0) .005f else 0f) })) }
        } } })
        val edits = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).overrides
        assertTrue(edits.getValue("eye.wide").isNotEmpty(), "new stops must belong to the widening instance")
        assertTrue(edits["eye"].isNullOrEmpty(), "the original fitted base must not inherit the new stop's override")
        val withOverride = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_enable", buildJsonObject { put("id", "eye.wide"); put("enabled", false) })
        compare(native(f.document, recipe(modules.drop(1))), f.now.model, poses)
        assertEquals(1f, f.now.model.rig.puppet.parameters.single { it.id == StandardParameters.EYE_L_OPEN }.max)
        f.edit("modifier_enable", buildJsonObject { put("id", "eye.wide"); put("enabled", true) })
        assertEquals(withOverride, RigIrCompiler.compile(f.now.model))
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "eye.wide") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "eye.highlight") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun blinkAndBrowPendulumsReuseCompatibleSharedOutputsAndRemainLiveAfterPrefixApplication(): Unit = runBlocking<Unit> {
        val originalDocument = document(eyeDetails = true)
        val source = originalDocument.source as WorkspaceSourceArt
        val ids = setOf("face", "iris", "lash", "close", "white", "brow", "lid", "lid-shadow", "highlight")
        val f = Fixture(originalDocument.copy(source = source.copy(layers = source.layers.filter { it.id.raw in ids })), scaffoldVersion = 1); f.install()
        f.group(listOf("face/legacy", "eye/fitted", "brow/fitted"))
        val targets = f.now.model.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val iris = mesh(f, "iris").id.raw
        fun addition(type: String, id: String, chosen: List<ModifierTarget> = targets) = f.addition(type, id, chosen,
            buildJsonObject { put("scaffold", "structure"); put("base", type.substringBefore('.')) })
        f.edit("modifier_add_group", buildJsonObject { put("group", "spring")
            put("entries", JsonArray(listOf(addition("brow.shape", "brow.shape"),
                addition("eye.aftershock", "iris-bounce", targets.filter { it.id == iris }),
                addition("eye.aftershock", "lid-bounce", targets.filter { it.id != iris }), addition("brow.spring", "brow.spring"))))
        })
        validateOperationSchema(WorkspaceModifierQueries.describe(f.now), WorkspaceModifierSchemas.stack, "result")
        val output = RigIrCompiler.compile(f.now.model)
        assertEquals(4, output.physics.groups.size)
        val available = output.parameters.mapTo(HashSet()) { it.id }
        val original = PhysicsGenerator.moduleRules(available).filter { it.id in setOf(PhysicsGenerator.EYE_L_BOUNCE_ID, PhysicsGenerator.EYE_R_BOUNCE_ID, "PhysicsBrowLSpring", "PhysicsBrowRSpring") }.map(Physics3Json::group)
        assertEquals(original.map { it.copy(id = "", name = "") }, output.physics.groups.map { it.copy(id = "", name = "") })
        val reports = f.now.model.rig.modifierReport!!.getValue("entries").jsonArray.map { it.jsonObject }
        val reused = reports.single { it.getValue("id").jsonPrimitive.content == "lid-bounce" }.getValue("contributions").jsonArray.map { it.jsonObject }
        assertEquals(2, reused.count { it["kind"]?.jsonPrimitive?.content == "physics" && it["change"]?.jsonPrimitive?.content == "reused" })
        val recipe = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted"))).let {
            it.copy(modules = it.modules + listOf("brow.shape", "eye.aftershock", "brow.spring").associateWith { ModuleChoice(true) })
        }
        compare(native(f.document, recipe), f.now.model, listOf(emptyMap(),
            mapOf(StandardParameters.EYE_L_OPEN to .35f, ParameterId("ParamEyeLBounce") to .8f, ParameterId("ParamBrowLSpring") to -.7f),
            mapOf(StandardParameters.ANGLE_X to 20f, StandardParameters.ANGLE_Y to -10f, StandardParameters.EYE_L_OPEN to .6f,
                ParameterId("ParamEyeLBounce") to -.6f, ParameterId("ParamBrowLAngle") to .4f, ParameterId("ParamBrowLSpring") to .7f)))
        val ranges = output.parameters.associate { it.id to PhysicsEngine.Range(it.min, it.max, it.default) }
        val expectedPhysics = PhysicsEngine(original.map(Physics3Json::setting), ranges)
        val actualPhysics = PhysicsEngine(output.physics.groups.map(Physics3Json::setting), ranges)
        var moved = false
        repeat(90) { frame ->
            val input = mapOf("ParamEyeLOpen" to if (frame in 10..15) 0f else 1f, "ParamBrowLY" to if (frame < 30) .7f else -.4f,
                "ParamBrowLAngle" to if (frame < 50) .6f else -.6f)
            val expected = expectedPhysics.step(input, 1f / 60)
            val actual = actualPhysics.step(input, 1f / 60)
            assertEquals(expected, actual)
            if (actual.values.any { abs(it) > .01f }) moved = true
        }
        assertTrue(moved, "the preserved pendulums must actually move")
        val baseConfig = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single { it.id == "iris-bounce" }.config
        val changedRule = Physics3Json.setting(output.physics.groups.first()).let { rule -> rule.copy(segments = rule.segments.mapIndexed { index, segment ->
            if (index == 0) segment.copy(length = segment.length * 1.2f) else segment
        }) }
        val changedConfig = JsonObject(baseConfig + ("physics" to buildJsonObject { put(PhysicsGenerator.EYE_L_BOUNCE_ID, changedRule.toJson()) }))
        val beforeConflict = f.now
        assertFails { f.edit("modifier_update", buildJsonObject { put("id", "iris-bounce"); put("type", "eye.aftershock"); put("config", changedConfig) }) }
        assertEquals(beforeConflict, f.now, "incompatible shared outputs must not publish any candidate")
        f.edit("modifier_enable", buildJsonObject { put("id", "lid-bounce"); put("enabled", false) })
        f.edit("modifier_update", buildJsonObject { put("id", "iris-bounce"); put("type", "eye.aftershock"); put("config", changedConfig) })
        assertEquals(changedRule.segments.first().length, f.now.model.rig.modifierOutput!!.physics.groups.first().segments.first().length)
        assertTrue(f.now.document.rigEdits.physicsEdits.isEmpty(), "module controls must edit their instance, not ordinary physics")
        validateOperationSchema(WorkspaceModifierQueries.describe(f.now), WorkspaceModifierSchemas.stack, "result")
        val secondConfig = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single { it.id == "lid-bounce" }.config
        f.edit("modifier_update", buildJsonObject { put("id", "lid-bounce"); put("type", "eye.aftershock")
            put("config", JsonObject(secondConfig + ("physics" to buildJsonObject { put(PhysicsGenerator.EYE_L_BOUNCE_ID, changedRule.toJson()) }))) })
        f.edit("modifier_enable", buildJsonObject { put("id", "lid-bounce"); put("enabled", true) })
        val edited = RigIrCompiler.compile(f.now.model)
        assertEquals(output.copy(physics = edited.physics), edited, "pendulum edits must preserve every other field")
        assertNotEquals(output.physics, edited.physics)
        f.edit("modifier_apply", buildJsonObject { put("id", "iris-bounce") })
        assertEquals(edited, RigIrCompiler.compile(f.now.model))
        assertEquals(edited, RigIrCompiler.compile(f.builder.build(f.now.document)))
        assertEquals(2, f.now.document.rigEdits.physicsEdits.size)
        assertEquals(changedRule.segments.first().length, f.now.document.rigEdits.physicsEdits.first().segments.first().length)
        f.edit("modifier_apply", buildJsonObject { put("id", "brow.spring") })
        assertEquals(edited, RigIrCompiler.compile(f.now.model))
        assertEquals(4, f.now.document.rigEdits.physicsEdits.size)
        assertEquals(edited, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun independentBrowSpringV2MatchesLegacyAcrossCompatibleBrowAndHeadBindings(): Unit = runBlocking {
        data class Mode(val head: String?, val fitted: Int, val shape: Int?, val body: Int? = null)
        val modes = buildList {
            add(Mode(null, 1, null))
            add(Mode("face/legacy", 1, 1, body = 1))
            add(Mode("face/legacy", 2, 1, body = 2))
            add(Mode("face/fitted", 1, 1))
            if (BundledAlgorithmArtifacts.find("brow.shape", 2) != null) add(Mode("face/legacy", 2, 2))
        }
        for (mode in modes) {
            val document = document(pairedBrows = mode.head != null, bilateralEyes = mode.head != null)
            suspend fun bind(version: Int?): Fixture {
                val f = Fixture(document)
                f.install()
                f.group(emptyList())
                if (mode.body != null) f.edit("modifier_add", JsonObject(f.addition("body/legacy", "body") +
                    ("version" to JsonPrimitive(mode.body))))
                if (mode.head != null) {
                    f.edit("modifier_add", f.addition(mode.head, "face"))
                    f.edit("modifier_add", f.addition("eye/fitted", "eye"))
                }
                f.edit("modifier_add", JsonObject(f.addition("brow/fitted", "brow", config = buildJsonObject {
                    put("scaffold", "structure"); putJsonObject("values") { put("lift", 52f) }
                }) + ("version" to JsonPrimitive(mode.fitted))))
                if (mode.shape != null) f.edit("modifier_add", JsonObject(f.addition("brow.shape", "shape", config = buildJsonObject {
                    put("scaffold", "structure"); put("base", "brow")
                    putJsonObject("values") { put("strength", 81f) }
                }) + ("version" to JsonPrimitive(mode.shape))))
                val spring = f.addition("brow.spring", "spring", config = buildJsonObject {
                    put("scaffold", "structure"); put("base", "brow")
                    if (mode.head == null) put("physicsEnabled", false)
                    if (mode.head == "face/legacy" && mode.fitted == 1) putJsonObject("physics") {
                        val edited = RigPhysicsEdit("PhysicsBrowLSpring", "Edited spring",
                            inputs = listOf(PhysicsInput("ParamBrowLY", 100f, PhysicsSourceType.X)),
                            outputs = listOf(PhysicsOutput("ParamBrowLSpring", 1, 1.7f)),
                            segments = listOf(PhysicsSegment(1.4f, .8f, .5f, 1.2f)))
                        put(edited.id, edited.toJson())
                    }
                })
                f.edit("modifier_add", if (version == null) spring else JsonObject(spring + ("version" to JsonPrimitive(version))))
                return f
            }
            val old = bind(null)
            val current = bind(2)
            val expected = assertNotNull(old.now.model.rig.modifierOutput)
            val actual = assertNotNull(current.now.model.rig.modifierOutput)
            val oldInstance = RigModifierStack.fromJson(old.now.document.rigEdits.modifierStack!!).instances.last()
            assertEquals(1, oldInstance.version)
            for ((before, after) in expected.meshes.zip(actual.meshes))
                assertTrue(before == after, "$mode mesh ${before.name} differs")
            assertEquals(expected.physics, actual.physics, mode.toString())
            assertTrue(expected == actual, "$mode differs outside mesh and physics outputs")
            val compiled = RigIrCompiler.compile(current.now.model)
            assertTrue(RigIrCompiler.compile(old.now.model) == compiled, "$mode compiled output differs")
            if (mode.head == "face/legacy" && mode.fitted == 1) verifyBrowSpringWorker(current)
            current.edit("modifier_apply", buildJsonObject { put("id", "spring") })
            assertEquals(compiled, RigIrCompiler.compile(current.now.model))
            assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(current.now.document)))
        }
    }

    @Test fun independentBrowSpringComposesWithBlinkAndLidCouplingInFullModernPreset(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern")
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config))
        val (initialized, expected) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct,
            preferSourceBindings = true)
        val builder = WorkspacePreviewBuilder()
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val spring = stack.instances.single { it.type == "brow.spring" }
        assertEquals(1, spring.version)
        val versions = mapOf("brow.blink" to 2, "brow.lid-coupling" to 3, "brow.spring" to 3)
        val index = stack.index(spring.id)
        assertTrue((versions.keys - spring.type).all { type -> stack.instances.take(index).any { it.type == type } })
        suspend fun preview(candidate: RigModifierStack) = builder.build(initialized.copy(
            rigEdits = initialized.rigEdits.copy(modifierStack = candidate.toJson()),
        ))
        val upgraded = stack.copy(instances = stack.instances.map { item ->
            versions[item.type]?.let { item.copy(version = it, plugin = assertNotNull(BuiltinModifierPlugins.reference(item.type, it))) } ?: item
        })
        val current = preview(upgraded)
        val old = assertNotNull(expected.modifierEvaluation).outputs
        val actual = assertNotNull(current.modifierEvaluation).outputs
        assertTrue(old[index].meshes.any { mesh -> mesh.shapes.any { it.parameter == "ParamBrowLSpring" } })
        for ((before, after) in old[index].meshes.zip(actual[index].meshes)) assertEquals(before, after, before.id)
        assertEquals(old[index], actual[index])
        assertEquals(old.last(), actual.last())
        assertEquals(RigIrCompiler.compile(expected), RigIrCompiler.compile(current))

        val instance = upgraded.instances[index]
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(spring.type, instance.version))
        val directory = Files.createTempDirectory("brow-spring-v3-package-")
        val preferences = Preferences.userRoot().node("brow-spring-v3/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val definition = RigBrowSpringModifier.composedDefinition(RigModifierSource.of(current))
            val providers = upgraded.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            repeat(2) {
                val installed = store.install(staging.toString())
                assertEquals(artifact.reference, installed.reference)
                val worker = ModifierPluginCatalog.definitions(store, captured = listOf(definition))
                    .single { it.type == installed.manifest.algorithms.single().type }
                val external = instance.copy(type = worker.type)
                val context = worker.authorContext(external).withUpstream(providers)
                assertEquals(actual[index], (worker.plugin as ContextualModifierAlgorithmPlugin)
                    .evaluate(actual[index - 1], external, context) {})
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    private suspend fun verifyBrowSpringWorker(fixture: Fixture) {
        val evaluated = assertNotNull(fixture.now.model.modifierEvaluation).outputs
        val before = evaluated[evaluated.lastIndex - 1]
        val after = evaluated.last()
        val stack = RigModifierStack.fromJson(fixture.now.document.rigEdits.modifierStack!!)
        val spring = stack.instances.last()
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(BrowSpringAlgorithm.TYPE, BrowSpringAlgorithm.VERSION))
        assertEquals(artifact.reference, spring.plugin)
        val directory = Files.createTempDirectory("brow-spring-v2-")
        val preferences = Preferences.userRoot().node("brow-spring-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val providers = stack.instances.dropLast(1).map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            repeat(2) {
                val installed = store.install(staging.toString())
                assertEquals(artifact.reference, installed.reference)
                val owner = RigModifierSource.of(fixture.now.model)
                val worker = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(owner))
                    .single { it.type == BrowSpringAlgorithm.ENTRYPOINT }
                val instance = spring.copy(type = worker.type, plugin = installed.reference)
                val context = worker.authorContext(instance).withUpstream(providers)
                val plugin = worker.plugin as ContextualModifierAlgorithmPlugin
                assertEquals(after, plugin.evaluate(before, instance, context, {}))
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
            val owner = RigModifierSource.of(fixture.now.model)
            val definition = RigBrowSpringModifier.definition(owner)
            val context = definition.authorContext(spring).withUpstream(providers)
            val physics = after.physics.groups.single { it.outputs.any { output -> output.parameter == "ParamBrowLSpring" } }
            val existing = physics.copy(id = "shared", name = "Shared")
            val algorithm = BrowSpringAlgorithm()
            val reused = algorithm.changes(before.copy(physics = before.physics.copy(groups = listOf(existing))), spring, context) {}
            assertEquals(listOf(existing), reused.physicsGroups.reused)
            assertFailsWith<IllegalArgumentException> {
                val incompatible = existing.copy(segments = existing.segments.map { it.copy(length = it.length * 2f) })
                algorithm.changes(before.copy(physics = before.physics.copy(groups = listOf(incompatible))), spring, context) {}
            }
            val mesh = before.meshes.single { it.name == "brow" }
            val edited = before.copy(meshes = before.meshes.map { if (it.id == mesh.id) it.copy(parent = null) else it })
            val failure = assertFailsWith<IllegalArgumentException> { algorithm.changes(edited, spring, context) {} }
            assertContains(failure.message.orEmpty(), "native fitted brow mesh and parent branch")

            val snapshot = fixture.now
            val history = fixture.runtime.history()
            val historyStore = WorkspaceStore(directory.resolve("project"))
            historyStore.persistHistory(snapshot.projectId, history)
            val archive = directory.resolve("spring.psd2live")
            val repository = ProjectRepository(writeHeadCache = false)
            repository.save(ProjectSaveCapture(snapshot.projectId, history, JsonObject(emptyMap()), null, historyStore), archive)
            repository.open(archive).use { opened ->
                val restored = opened.history.head().snapshot
                assertEquals(spring, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.last())
                assertEquals(after, fixture.builder.build(restored).rig.modifierOutput)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun mouthCaptureJawTongueDepthAndMoodsComposeAndKeepTheirColorChannelsThroughApplication(): Unit = runBlocking<Unit> {
        val f = Fixture(document(mouthDetails = true, expressionDetails = true)); f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy"))
        val modules = listOf("mouth.capture", "mouth.tongue", "mouth.jaw", "mouth.interior-depth", "mouth.spring", "expression.cheek", "expression.gloom")
        fun values(type: String): Map<String, Float> = when (type) {
            "mouth.jaw" -> mapOf("share" to 22f)
            "mouth.interior-depth" -> mapOf("depth" to 150f)
            "expression.cheek" -> mapOf("strength" to 150f)
            "expression.gloom" -> mapOf("strength" to 80f)
            else -> emptyMap()
        }
        val base = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
        val poses = listOf(emptyMap(), mapOf(StandardParameters.MOUTH_OPEN to .7f, ParameterId("ParamJawOpen") to .8f),
            mapOf(StandardParameters.MOUTH_OPEN to .55f, ParameterId("ParamMouthPuckerWiden") to -.6f, ParameterId("ParamCheekPuff") to .5f),
            mapOf(StandardParameters.MOUTH_OPEN to .8f, ParameterId("ParamTongueOut") to .7f, ParameterId("ParamMouthX") to -.5f,
                ParameterId("ParamMouthCornerL") to .6f, ParameterId("ParamMouthPuckerWidenR") to -.4f),
            mapOf(StandardParameters.ANGLE_X to 22f, StandardParameters.ANGLE_Y to -14f, StandardParameters.MOUTH_OPEN to .6f,
                ParameterId("ParamJawOpen") to .8f, ParameterId("ParamMouthSpring") to -.7f),
            mapOf(ParameterId("ParamCheek") to .8f, ParameterId("ParamGloom") to .6f, StandardParameters.MOUTH_OPEN to .5f,
                ParameterId("ParamTongueOut") to .5f, ParameterId("ParamMouthFunnel") to .7f))
        for ((index, type) in modules.withIndex()) {
            val spec = RigLegacyBindings.spec(type)
            f.edit("modifier_add", f.addition(type, type, config = buildJsonObject {
                put("scaffold", "structure")
                spec.module?.base?.let { put("base", it.substringBefore('/')) }
                if (values(type).isNotEmpty()) put("values", JsonObject(values(type).mapValues { JsonPrimitive(it.value) }))
                if (spec.related.isNotEmpty()) putJsonObject("dependencies") { spec.related.forEach { required ->
                    put(required, if ('/' in required) required.substringBefore('/') else required)
                } }
            }))
            val recipe = base.copy(modules = base.modules + modules.take(index + 1).associateWith { ModuleChoice(true, values = values(it)) })
            try { compare(native(f.document, recipe), f.now.model, poses) }
            catch (error: Throwable) { throw AssertionError("Stage $type: ${error.message}", error) }
        }
        validateOperationSchema(WorkspaceModifierQueries.describe(f.now), WorkspaceModifierSchemas.stack, "result")
        assertEquals(1, f.now.model.rig.modifierOutput!!.physics.groups.size)
        val actualPhysics = WorkspaceDocumentEdits.physicsCatalog(f.now.document, f.now.model)
        assertEquals(f.now.model.rig.modifierOutput!!.physics.groups.map { it.id }, actualPhysics.map { it.id })
        assertTrue(actualPhysics.all { it.origin == PhysicsOrigin.MODIFIER && it.active })
        val face = mesh(f, "face").id.raw
        f.edit("keyform_apply", buildJsonObject { putJsonArray("changes") { addJsonObject {
            put("op", "set"); put("target", "mesh:$face")
            putJsonObject("key") { put("ParamCheek", 1f); put("ParamGloom", 1f) }
            putJsonObject("channels") { put("multiplyColor", JsonArray(listOf(.65f, .45f, .55f).map(::JsonPrimitive))) }
        } } })
        val overrides = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).overrides
        assertTrue(overrides.getValue("expression.gloom").any { it["channel"]?.jsonPrimitive?.content == "multiply_color" })
        val colored = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_enable", buildJsonObject { put("id", "expression.gloom"); put("enabled", false) })
        val withoutGloom = base.copy(modules = base.modules + modules.dropLast(1).associateWith { ModuleChoice(true, values = values(it)) })
        compare(native(f.document, withoutGloom), f.now.model, poses)
        f.edit("modifier_enable", buildJsonObject { put("id", "expression.gloom"); put("enabled", true) })
        assertEquals(colored, RigIrCompiler.compile(f.now.model))
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "mouth.jaw") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "expression.gloom") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun packagedGloomMatchesLegacyWithAnAuthoredHighlightTrack(): Unit = runBlocking {
        val gloom = ParameterId("ParamGloom")
        val authored = ParameterId("ParamAuthoredOpacity")
        suspend fun frame(version: Int): Triple<ColorRgb, Float, io.github.psd2live.format.model.RigIR> {
            val f = Fixture(document(expressionDetails = true)); f.install()
            f.group(listOf("face/legacy"))
            f.edit("modifier_add", f.addition("expression.cheek", "cheek"))
            val face = mesh(f, "face").id
            val highlight = mesh(f, "skin-highlight").id
            f.edit("object_edit_appearance", buildJsonObject {
                putJsonArray("edits") { addJsonObject {
                    put("action", "static"); put("kind", "mesh"); put("id", face.raw)
                    put("multiply_color", JsonArray(listOf(.8f, .9f, .95f).map(::JsonPrimitive)))
                } }
            })
            f.edit("parameter_create", buildJsonObject {
                put("parameter_id", authored.raw); put("name", "Authored opacity")
                put("min", 0f); put("max", 1f); put("default", 0f)
            })
            f.edit("keyform_apply", buildJsonObject {
                putJsonArray("changes") {
                    for ((key, opacity) in listOf(0f to 1f, 1f to .95f)) addJsonObject {
                        put("op", "set"); put("target", "mesh:${highlight.raw}")
                        putJsonObject("key") { put(authored.raw, key) }
                        putJsonObject("channels") { put("opacity", opacity) }
                    }
                }
            })
            val request = f.addition("expression.gloom", "gloom")
            f.edit("modifier_add", JsonObject(request + ("version" to JsonPrimitive(version))))
            val evaluated = CpuDeformationEvaluator().evaluate(f.now.model.rig.puppet, mapOf(gloom to 1f, authored to 1f))
            return Triple(evaluated.multiplyColor.getValue(face), evaluated.opacity.getValue(highlight),
                RigIrCompiler.compile(f.now.model))
        }

        val (legacyColor, legacyOpacity, legacyIr) = frame(1)
        val (candidateColor, candidateOpacity, candidateIr) = frame(2)
        assertFaceEquals(legacyIr, candidateIr)
        assertEquals(legacyColor, candidateColor)
        assertEquals(.67f, candidateColor.red, .0001f)
        assertEquals(.1f, legacyOpacity, .0001f)
        assertEquals(legacyOpacity, candidateOpacity, .0001f)
    }

    @Test fun packagedGloomReplacesLegacyGloomOnlyOnSelectedMeshes(): Unit = runBlocking {
        val type = "expression.gloom"
        val oldReference = requireNotNull(BuiltinModifierPlugins.reference(type, 1))
        assertEquals("builtin.65787072657373696f6e2e676c6f6f6d.v1", oldReference.id)
        assertEquals("1.0.0", oldReference.version)
        assertNull(BundledAlgorithmArtifacts.find(type, 1))
        val artifact = requireNotNull(BundledAlgorithmArtifacts.find(type, 2))
        val entrypoint = artifact.manifest.algorithms.single().type
        val f = Fixture(document(expressionDetails = true)); f.install()
        f.group(listOf("face/legacy"))
        f.edit("modifier_add", f.addition("expression.cheek", "cheek"))
        fun gloomConfig(strength: Float) = buildJsonObject {
            put("scaffold", "structure")
            putJsonObject("values") { put("strength", strength) }
        }
        val old = f.addition(type, "old-gloom", config = gloomConfig(100f))
        f.edit("modifier_add", JsonObject(old + ("version" to JsonPrimitive(1))))
        fun stack() = RigModifierStack.fromJson(requireNotNull(f.now.document.rigEdits.modifierStack))
        assertEquals(oldReference, stack().instances.last().plugin)
        val before = RigIrCompiler.compile(f.now.model)
        val prior = assertNotNull(f.now.model.rig.modifierOutput)
        val faceId = mesh(f, "face").id.raw
        val face = listOf(ModifierTarget("mesh", faceId))
        f.edit("modifier_add", f.addition(entrypoint, "new-gloom", face, gloomConfig(50f)))
        val installed = stack().instances.last()
        assertEquals(2, installed.version)
        assertEquals(artifact.reference, installed.plugin)
        val directory = Files.createTempDirectory("gloom-package-")
        val preferences = Preferences.userRoot().node("gloom-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val packageRef = store.install(staging.toString())
            assertEquals(artifact.reference, packageRef.reference)
            val adapter = ModifierPluginCatalog.definitions(store,
                captured = listOf(RigGloomModifier.definition(RigModifierSource.of(f.now.model))))
                .single { it.type == entrypoint && it.version == 2 }
            val external = installed.copy(type = entrypoint)
            val worker = adapter.plugin as ContextualModifierAlgorithmPlugin
            assertEquals(f.now.model.rig.modifierOutput, worker.evaluate(prior, external, adapter.authorContext(external)) {})
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
        val after = RigIrCompiler.compile(f.now.model)
        val beforeFace = before.meshes.single { it.id == faceId }
        val afterFace = after.meshes.single { it.id == faceId }
        assertNotEquals(beforeFace.channels, afterFace.channels)
        assertEquals(before, after.copy(meshes = after.meshes.map { if (it.id == faceId) it.copy(channels = beforeFace.channels) else it }))
        val pose = mapOf(ParameterId("ParamCheek") to 1f, ParameterId("ParamGloom") to 1f)
        val frame = CpuDeformationEvaluator().evaluate(f.now.model.rig.puppet, pose)
        val color = frame.multiplyColor.getValue(DrawableId(faceId))
        assertEquals(.935f, color.red, .0001f)
        assertEquals(.875f, color.green, .0001f)
        assertEquals(.901f, color.blue, .0001f)
        assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_add", f.addition(type, "overlapping-gloom", face))
        }
        assertEquals(after, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "old-gloom") })
        assertEquals(after, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "new-gloom") })
        assertEquals(after, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun packagedGloomRejectsCompletePresetWhenLegacyAddsCarriers(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern")
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, model) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = true), preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val gloom = stack.instances.single { it.type == "expression.gloom" }
        assertEquals(1, gloom.version)
        val plugin = assertNotNull(BuiltinModifierPlugins.reference(gloom.type, 2))
        val independent = stack.copy(instances = stack.instances.map {
            if (it.id == gloom.id) it.copy(version = 2, plugin = plugin) else it
        })
        val updated = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = independent.toJson()))
        val outputs = assertNotNull(model.modifierEvaluation).outputs
        val index = stack.index(gloom.id)
        assertTrue(outputs[index].deformers.size > outputs[index - 1].deformers.size)
        val failure = assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(updated) }
        assertContains(failure.message.orEmpty(), "use Gloom v1")
    }

    @Test fun packagedGloomV3ReplaysCompletePresetCarriers(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern")
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, expectedModel) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = true), preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val gloom = stack.instances.single { it.type == "expression.gloom" }
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(gloom.type, 3))
        val instance = gloom.copy(version = 3, plugin = artifact.reference)
        val independent = stack.copy(instances = stack.instances.map {
            if (it.id == gloom.id) instance else it
        })
        val updated = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = independent.toJson()))
        val actualModel = WorkspacePreviewBuilder().build(updated)
        val index = stack.index(gloom.id)
        val expected = assertNotNull(expectedModel.modifierEvaluation).outputs[index]
        val actual = assertNotNull(actualModel.modifierEvaluation).outputs[index]
        assertEquals(expected.deformers.map { it.id }, actual.deformers.map { it.id })
        assertEquals(expected.meshes.map { it.id }, actual.meshes.map { it.id })
        for ((expectedMesh, actualMesh) in expected.meshes.zip(actual.meshes)) {
            assertTrue(expectedMesh == actualMesh, "Mesh differs: ${expectedMesh.id}")
        }
        for ((expectedDeformer, actualDeformer) in expected.deformers.zip(actual.deformers)) {
            if (expectedDeformer is io.github.psd2live.format.model.Deformer.Warp && actualDeformer is io.github.psd2live.format.model.Deformer.Warp) {
                assertEquals(expectedDeformer.copy(lattice = null), actualDeformer.copy(lattice = null), "Carrier metadata")
                assertTrue(expectedDeformer.lattice == actualDeformer.lattice, "Carrier lattice differs: ${expectedDeformer.id}")
            } else assertTrue(expectedDeformer == actualDeformer, "Deformer differs: ${expectedDeformer.id}")
        }
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.parameterTree, actual.parameterTree)
        assertTrue(expected == actual, "Gloom prefix differs")
        assertTrue(assertNotNull(expectedModel.modifierEvaluation).outputs.last() == assertNotNull(actualModel.modifierEvaluation).outputs.last(),
            "Complete generated rig differs")

        val directory = Files.createTempDirectory("gloom-v3-package-")
        val preferences = Preferences.userRoot().node("gloom-v3-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val definition = RigGloomModifier.carrierDefinition(RigModifierSource.of(actualModel))
            val upstream = stack.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = definition.authorContext(instance).withUpstream(upstream)
            val installed = store.install(staging.toString())
            assertEquals(artifact.reference, installed.reference)
            val input = assertNotNull(expectedModel.modifierEvaluation).outputs[index - 1]
            assertEquals(expected, ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(), input,
                instance, {}, store, context = context))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun skinClippingAndLegacyNeckFollowSurvivePrefixApplication(): Unit = runBlocking {
        skinClippingAndNeckFollowSurvivePrefixApplication(1)
    }

    @Test fun skinClippingAndSourceNeckFollowSurvivePrefixApplication(): Unit = runBlocking {
        skinClippingAndNeckFollowSurvivePrefixApplication(2)
    }

    @Test fun skinClippingAndPackagedNeckFollowSurvivePrefixApplication(): Unit = runBlocking {
        val artifact = requireNotNull(BundledAlgorithmArtifacts.find("face.neck", 2))
        skinClippingAndNeckFollowSurvivePrefixApplication(2, artifact.manifest.algorithms.single().type)
    }

    private suspend fun skinClippingAndNeckFollowSurvivePrefixApplication(neckVersion: Int, neckType: String = "face.neck") {
        val f = Fixture(document(expressionDetails = true, skinDetails = true)); f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy"))
        val poses = listOf(emptyMap(), mapOf(StandardParameters.ANGLE_Z to 25f),
            mapOf(StandardParameters.ANGLE_Z to -25f, StandardParameters.ANGLE_X to 20f,
                StandardParameters.ANGLE_Y to -10f, StandardParameters.BODY_X to 5f))
        val base = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
        val skin = listOf("blush", "skin-highlight", "hair-shadow")
        assertTrue(skin.all { mesh(f, it).maskedBy.isEmpty() })
        assertTrue(mesh(f, "neck-shadow").maskedBy.isEmpty())
        val beforeSkin = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_add", f.addition("face.skin-clip", "skin"))
        val afterSkin = RigIrCompiler.compile(f.now.model)
        assertEquals(beforeSkin, afterSkin.copy(meshes = afterSkin.meshes.map { mesh ->
            val original = beforeSkin.meshes.single { it.id == mesh.id }
            mesh.copy(maskedBy = original.maskedBy, invertMask = original.invertMask)
        }), "clipping must not rewrite authored geometry, channels, deformers or other sidecars")
        val face = mesh(f, "face").id
        skin.forEach { assertEquals(listOf(face), mesh(f, it).maskedBy, it) }
        val addition = f.addition(neckType, "neck", config = buildJsonObject {
            put("scaffold", "structure"); putJsonObject("dependencies") { put("face/legacy", "face") }
        })
        f.edit("modifier_add", JsonObject(addition + ("version" to JsonPrimitive(neckVersion))))
        val neck = RigModifierStack.fromJson(requireNotNull(f.now.document.rigEdits.modifierStack)).instances.last()
        assertEquals(neckVersion, neck.version)
        assertEquals("builtin.666163652e6e65636b.v$neckVersion", neck.plugin?.id)
        assertEquals(listOf(mesh(f, "neck").id), mesh(f, "neck-shadow").maskedBy)
        val recipe = base.copy(modules = base.modules + mapOf("face.skin-clip" to ModuleChoice(true), "face.neck" to ModuleChoice(true)))
        val original = native(f.document, recipe)
        compare(original, f.now.model, poses)
        assertEquals(original.rig.puppet.drawables.associate { it.id to it.maskedBy }, f.now.model.rig.puppet.drawables.associate { it.id to it.maskedBy })
        val observed = WorkspaceModifierQueries.describe(f.now)
        validateOperationSchema(observed, WorkspaceModifierSchemas.stack, "result")
        val skinReport = observed.getValue("entries").jsonArray.map { it.jsonObject }.single { it["id"]?.jsonPrimitive?.content == "skin" }
        val masks = skinReport.getValue("contributions").jsonArray.map { it.jsonObject }.mapNotNull { it["mask"]?.jsonObject }
        assertEquals(skin.map { mesh(f, it).id.raw }.toSet(), masks.map { it.getValue("target").jsonPrimitive.content }.toSet())
        assertTrue(masks.all { it.getValue("before").jsonArray.isEmpty() && it.getValue("after").jsonArray.single().jsonPrimitive.content == face.raw })
        val expected = RigIrCompiler.compile(f.now.model)
        val before = f.now
        assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_move", buildJsonObject { put("id", "neck"); put("index", 1) })
        }
        assertEquals(before.state, f.now.state)
        f.edit("modifier_enable", buildJsonObject { put("id", "face"); put("enabled", false) })
        val row = WorkspaceModifierQueries.describe(f.now).getValue("entries").jsonArray.last().jsonObject
        assertEquals("missing_dependency", row.getValue("status").jsonPrimitive.content)
        assertTrue(mesh(f, "neck-shadow").maskedBy.isEmpty())
        assertTrue(skin.all { mesh(f, it).maskedBy == listOf(face) }, "independent clipping keeps working")
        f.edit("modifier_enable", buildJsonObject { put("id", "face"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_apply", buildJsonObject { put("id", "skin") })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "neck") })
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("rig_edit_structure", buildJsonObject { putJsonArray("edits") { addJsonObject {
            put("action", "static"); put("kind", "mesh"); put("id", mesh(f, "neck-shadow").id.raw)
            putJsonArray("masked_by") {}; put("invert_mask", false)
        } } })
        assertTrue(mesh(f, "neck-shadow").maskedBy.isEmpty(), "applied masks are ordinary editable content")
        assertTrue(f.builder.build(f.now.document).rig.puppet.drawables.single { it.id == mesh(f, "neck-shadow").id }.maskedBy.isEmpty())
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
    }

    @Test fun clippingRejectsIncompatibleAuthoredInversionWithoutPublishingItsScaffold(): Unit = runBlocking<Unit> {
        val f = Fixture(document(expressionDetails = true)); f.install()
        f.edit("rig_edit_structure", buildJsonObject { putJsonArray("edits") { addJsonObject {
            put("action", "static"); put("kind", "mesh"); put("id", mesh(f, "blush").id.raw); put("invert_mask", true)
        } } })
        val before = f.now
        val error = assertFailsWith<WorkspaceBatchEditException> { f.group(listOf("face.skin-clip")) }
        assertTrue(error.message.orEmpty().contains("face.skin-clip"))
        assertEquals(before, f.now)
        assertTrue(RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.isEmpty())
    }

    @Test fun bodyAndOutfitModulesKeepNativeGeometryPhysicsAndStableChainParameters(): Unit = runBlocking<Unit> {
        val f = Fixture(document(outfitDetails = true)); f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy"))
        val types = listOf("body.bust", "body.arms", "outfit.skirt", "outfit.ribbons", "outfit.creature")
        fun values(type: String) = when (type) { "body.bust" -> mapOf("bounce" to 130f)
            "outfit.skirt", "outfit.ribbons", "outfit.creature" -> mapOf("swing" to 140f)
            else -> emptyMap() }
        for (type in types) f.edit("modifier_add", f.addition(type, type, config = buildJsonObject {
            put("scaffold", "structure"); putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
            if (values(type).isNotEmpty()) put("values", JsonObject(values(type).mapValues { JsonPrimitive(it.value) }))
        }))
        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val result = RigIrCompiler.compile(f.now.model)
        val capturedSource = RigModifierSource.of(f.now.model)
        val aliases = stack.instances.filter { RigModifierChains.kinds(it.type).isNotEmpty() }
            .flatMap { capturedSource.chains(it).flatMap { chain -> chain.aliases.entries } }.associate { it.key to it.value }
        assertTrue(aliases.isNotEmpty())
        assertTrue(result.parameters.none { it.id in aliases }, "chain outputs must be named by persisted instances")
        val base = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
        val recipe = base.copy(modules = base.modules + types.associateWith { ModuleChoice(true, values = values(it)) })
        val original = native(f.document, recipe)
        val swing = aliases.entries.mapIndexed { index, entry -> ParameterId(entry.value) to if (index % 2 == 0) .65f else -.45f }.toMap()
        val poses = listOf(emptyMap(), swing, swing + mapOf(StandardParameters.ANGLE_X to 20f, StandardParameters.ANGLE_Y to -12f,
            StandardParameters.ANGLE_Z to 15f, StandardParameters.BODY_X to -6f, StandardParameters.BREATH to .8f,
            ParameterId("ParamBustX") to .5f, ParameterId("ParamBustY") to -.6f, ParameterId("ParamArmLSwing") to .7f))
        compare(original, f.now.model, poses, aliases)
        val expectedRules = PhysicsGenerator.moduleRules(original.rig.puppet.parameters.mapTo(HashSet()) { it.id.raw }).map { rule ->
            rule.copy(inputs = rule.inputs.map { it.copy(parameter = aliases[it.parameter] ?: it.parameter) },
                outputs = rule.outputs.map { it.copy(parameter = aliases[it.parameter] ?: it.parameter) })
        }
        val actualRules = result.physics.groups.map(Physics3Json::setting)
        assertTrue(actualRules.size > 10)
        assertEquals(expectedRules.associateBy { it.outputs.map { output -> output.parameter } }.keys,
            actualRules.associateBy { it.outputs.map { output -> output.parameter } }.keys)
        for (rule in expectedRules) {
            val actual = actualRules.single { it.outputs.map { output -> output.parameter } == rule.outputs.map { output -> output.parameter } }
            assertEquals(rule.copy(id = actual.id), actual)
        }
        val ranges = PhysicsEngine.ranges(f.now.model.rig.puppet.parameters)
        val expectedEngine = PhysicsEngine(expectedRules, ranges); val actualEngine = PhysicsEngine(actualRules, ranges)
        repeat(90) { frame ->
            val input = mapOf("ParamAngleX" to if (frame < 30) 20f else -15f, "ParamBodyAngleY" to if (frame < 45) 4f else -3f,
                "ParamBreath" to (frame % 30) / 30f, "ParamEyeLOpen" to if (frame % 30 < 3) 0f else 1f)
            assertEquals(expectedEngine.step(input, 1f / 60), actualEngine.step(input, 1f / 60))
        }
        validateOperationSchema(WorkspaceModifierQueries.describe(f.now), WorkspaceModifierSchemas.stack, "result")
        f.edit("modifier_apply", buildJsonObject { put("id", "body.arms") })
        assertEquals(result, RigIrCompiler.compile(f.now.model))
        assertEquals(result, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "outfit.creature") })
        assertEquals(result, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun independentArmSwingPreservesRecommendedBindingAndRunsInstalledWorker(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true,
            pairedArms = true, chestDetail = true)
        val builder = WorkspacePreviewBuilder()
        val stack = WorkspaceModifierPlan.initial(builder.build(art), WorkspaceModifierPlan.RECOMMENDED_GROUP)
        val body = stack.instances.single { it.type == "body/legacy" }
        val arms = stack.instances.single { it.type == "body.arms" }
        assertEquals(1, arms.version)
        val bodyPackage = assertNotNull(BundledAlgorithmArtifacts.find(body.type, 2))
        val armPackage = assertNotNull(BundledAlgorithmArtifacts.find(arms.type, 2))
        val baseline = stack.copy(instances = stack.instances.map {
            if (it.id == body.id) it.copy(version = 2, plugin = bodyPackage.reference) else it
        })
        val direct = baseline.copy(instances = baseline.instances.map {
            if (it.id == arms.id) it.copy(version = 2, plugin = armPackage.reference) else it
        })
        fun documentFor(candidate: RigModifierStack) = art.copy(rigEdits = art.rigEdits.copy(modifierStack = candidate.toJson()))
        val old = builder.build(documentFor(baseline))
        val current = builder.build(documentFor(direct))
        val expected = assertNotNull(old.rig.modifierOutput)
        val actual = assertNotNull(current.rig.modifierOutput)
        assertEquals(expected.parameters, actual.parameters)
        assertEquals(expected.parameterTree, actual.parameterTree)
        assertEquals(expected.physics, actual.physics)
        for ((a, b) in expected.deformers.zip(actual.deformers)) assertEquals(a, b, a.id)
        assertEquals(expected, actual)
        assertEquals(RigIrCompiler.compile(old), RigIrCompiler.compile(current))
        val evaluation = assertNotNull(current.modifierEvaluation)
        val index = direct.instances.indexOfFirst { it.id == arms.id }
        val before = evaluation.outputs[index - 1]
        val after = evaluation.outputs[index]
        assertEquals(before.meshes, after.meshes)
        assertEquals(before.deformers.filterIsInstance<io.github.psd2live.format.model.Deformer.Warp>().map { it.lattice },
            after.deformers.filterIsInstance<io.github.psd2live.format.model.Deformer.Warp>().map { it.lattice })
        val pose = mapOf(StandardParameters.BODY_X to -6f, StandardParameters.BODY_Z to 7f, StandardParameters.BODY_LEAN to 3f,
            StandardParameters.PROPORTION to -5f, StandardParameters.BREATH to .6f, StandardParameters.ANGLE_X to 12f,
            ParameterId("ParamArmLSwing") to .7f, ParameterId("ParamArmRSwing") to -.5f)
        compare(old, current, listOf(pose))

        val directory = Files.createTempDirectory("arm-swing-package-")
        val preferences = Preferences.userRoot().node("arm-swing-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), armPackage.bytes)
            for (name in armPackage.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(armPackage.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val instance = direct.instances[index]
            val definition = RigArmSwingModifier.definition(RigModifierSource.of(current))
            val dependencies = RigLegacyBindings.providers(instance)
            val providers = dependencies.map { (capability, id) ->
                val provider = direct.instances.single { it.id == id }
                ModifierUpstreamCapability(capability, provider.type, provider.version, provider.targets, provider.id,
                    provider.config, provider.outputAliases, provider.plugin)
            }
            repeat(2) {
                val installed = store.install(staging.toString())
                assertEquals(armPackage.reference, installed.reference)
                val external = instance.copy(type = installed.manifest.algorithms.single().type)
                val adapter = ModifierPluginCatalog.definitions(store, captured = listOf(definition)).single { it.type == external.type }
                val context = adapter.authorContext(external).withUpstream(providers)
                assertEquals(after, (adapter.plugin as ContextualModifierAlgorithmPlugin).evaluate(before, external, context) {})
                ModifierPluginWorkers.stop(installed.reference)
                store.uninstall(installed.reference)
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }

        val fixture = Fixture(documentFor(direct))
        fixture.install()
        fixture.edit("modifier_apply", buildJsonObject { put("id", arms.id) })
        assertEquals(RigIrCompiler.compile(current), RigIrCompiler.compile(WorkspacePreviewBuilder().build(fixture.now.document)))
    }

    @Test fun independentArmSwingRejectsAnEditedNeutralLatticeAtomically(): Unit = runBlocking {
        val fixture = Fixture(document(outfitDetails = true))
        fixture.install()
        fixture.group(listOf("body/legacy", "face/legacy"))
        val arm = fixture.now.model.rig.puppet.deformers.filterIsInstance<Deformer.Warp>().single {
            it.id == mesh(fixture, "arm").parentDeformerId
        }
        val lattice = assertNotNull(arm.geometryGrid)
        val pose = key(fixture, lattice.axes)
        val coordinate = lattice.axes.map { axis ->
            axis.keys.indexOfFirst { it == pose.getValue(axis.parameterId.raw).jsonPrimitive.float }
        }
        val points = lattice.cells.single { it.coordinate.toList() == coordinate }.form.controlPoints
        fixture.edit("keyform_apply", buildJsonObject { putJsonArray("changes") { addJsonObject {
            put("op", "set")
            put("target", "warp:${arm.id.raw}")
            put("key", pose)
            putJsonObject("geometry") {
                put("controlPoints", JsonArray(points.mapIndexed { index, point -> JsonPrimitive(if (index == 0) point + .03f else point) }))
            }
        } } })
        val before = fixture.now
        val request = fixture.addition("body.arms", "arms", config = buildJsonObject {
            put("scaffold", "structure")
            putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
        })
        val failure = assertFailsWith<WorkspaceBatchEditException> {
            fixture.edit("modifier_add", JsonObject(request + ("version" to JsonPrimitive(2))))
        }
        assertTrue(failure.message.orEmpty().contains("source neutral arm lattice"))
        assertEquals(before, fixture.now)
    }

    @Test fun chestBounceV2MatchesLegacyWorkerAndAppliedOutput(): Unit = runBlocking<Unit> {
        suspend fun fixture(version: Int): Fixture {
            val f = Fixture(document(outfitDetails = true, chestDetail = true)); f.install()
            f.group(listOf("body/legacy", "face/legacy"))
            val request = f.addition("body.bust", "chest", config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") {
                    put("body/legacy", "body")
                    put("face/legacy", "face")
                }
                putJsonObject("values") { put("bounce", 140f) }
            })
            f.edit("modifier_add", JsonObject(request + ("version" to JsonPrimitive(version))))
            return f
        }
        val legacy = fixture(1)
        val direct = fixture(2)
        val expected = RigIrCompiler.compile(legacy.now.model)
        val actual = RigIrCompiler.compile(direct.now.model)
        val ids = setOf("ParamBustY", "ParamBustX")
        val snapshot = assertNotNull(direct.now.model.modifierEvaluation)
        val instance = snapshot.stack.instances.last()
        val before = snapshot.outputs[snapshot.outputs.lastIndex - 1]
        assertEquals(expected.parameters.filter { it.id in ids }, actual.parameters.filter { it.id in ids })
        assertEquals(expected.parameterTree, actual.parameterTree)
        assertEquals(expected.physics, actual.physics)
        assertTrue(actual.physics.groups.any { it.id == instance.outputId("physics:PhysicsBustY") })
        val top = mesh(direct, "chest-panel").id.raw
        val actualTop = actual.meshes.single { it.id == top }
        val originalTop = before.meshes.single { it.id == top }
        assertEquals(originalTop, actualTop.copy(shapes = originalTop.shapes))
        assertEquals(ids, expected.meshes.single { it.id == top }.shapes.filter { it.parameter in ids }.map { it.parameter }.toSet(), "legacy fixture needs a chest shape")
        assertEquals(ids, actualTop.shapes.filter { it.parameter in ids }.map { it.parameter }.toSet())
        assertTrue(actual.meshes.single { it.id == mesh(direct, "ribbon").id.raw }.shapes.none { it.parameter in ids })
        assertTrue(actual.meshes.single { it.id == mesh(direct, "pendant").id.raw }.shapes.none { it.parameter in ids })
        val pose = mapOf(ParameterId("ParamBustY") to -.6f, ParameterId("ParamBustX") to .5f)
        compare(legacy.now.model, direct.now.model, listOf(emptyMap(), pose, pose + (StandardParameters.BODY_X to 5f)))

        val reference = assertNotNull(instance.plugin)
        val packageEntry = ModifierPluginStore.shared.snapshot().single { it.reference == reference }
        val providers = RigLegacyBindings.providers(instance).map { (capability, id) ->
            val provider = snapshot.stack.instances.single { it.id == id }
            ModifierUpstreamCapability(capability, provider.type, provider.version, provider.targets, provider.id,
                provider.config, provider.outputAliases, provider.plugin)
        }
        val source = RigModifierSource.of(direct.now.model)
        val definition = RigBustModifier.definition(source)
        val context = definition.authorContext(instance).withUpstream(providers)
        try {
            val fromWorker = ModifierPluginWorkers.evaluate(packageEntry, packageEntry.manifest.algorithms.single(),
                before, instance, {}, context = context, declaredParameters = definition.parameters(instance))
            assertEquals(snapshot.outputs.last(), fromWorker)
        } finally {
            ModifierPluginWorkers.stop(reference)
        }

        val (slot, group) = RigModifierPhysics.entries(instance, actual).entries.first()
        val rule = Physics3Json.setting(group)
        val editedRule = rule.copy(segments = rule.segments.map { it.copy(delay = it.delay * 1.2f) })
        direct.edit("modifier_update", buildJsonObject {
            put("id", instance.id)
            put("type", instance.type)
            put("config", JsonObject(instance.config + ("physics" to buildJsonObject { put(slot, editedRule.toJson()) })))
        })
        val edited = RigIrCompiler.compile(direct.now.model)
        assertEquals(Physics3Json.group(editedRule), edited.physics.groups.single { it.id == group.id })
        direct.edit("modifier_apply", buildJsonObject { put("id", instance.id) })
        assertEquals(edited, RigIrCompiler.compile(direct.now.model))
        assertEquals(edited, RigIrCompiler.compile(direct.builder.build(direct.now.document)))
    }

    @Test fun skirtV2KeepsLegacyBindingAndRunsInstalledWorker(): Unit = runBlocking {
        suspend fun fixture(version: Int): Fixture {
            val f = Fixture(document(outfitDetails = true, expressionDetails = true))
            f.install()
            f.group(listOf("body/legacy", "face/legacy", "expression.toggles", "outfit.ribbons"))
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
                putJsonObject("values") { put("swing", 140f) }
            }
            f.edit("modifier_add", JsonObject(f.addition("outfit.skirt", "skirt", config = config) +
                ("version" to JsonPrimitive(version))))
            return f
        }
        val legacy = fixture(1)
        val direct = fixture(2)
        val snapshot = assertNotNull(direct.now.model.modifierEvaluation)
        val instance = snapshot.stack.instances.last()
        val before = snapshot.outputs[snapshot.outputs.lastIndex - 1]
        val expected = RigIrCompiler.compile(legacy.now.model)
        val actual = RigIrCompiler.compile(direct.now.model)
        val axes = actual.parameters.filter { it.id.startsWith("modifier:skirt:") }
        assertEquals(7, axes.size)
        assertEquals(4, RigModifierPhysics.entries(instance, actual).size)
        assertFaceEquals(expected, actual)
        val pose = axes.mapIndexed { i, parameter -> ParameterId(parameter.id) to if (i % 2 == 0) .65f else -.75f }.toMap()
        compare(legacy.now.model, direct.now.model, listOf(emptyMap(), pose,
            pose + mapOf(StandardParameters.BODY_X to 7f, StandardParameters.ANGLE_X to 23f, StandardParameters.BREATH to .6f)))

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("outfit.skirt", 2))
        assertEquals(artifact.reference, instance.plugin)
        val directory = Files.createTempDirectory("skirt-v2-")
        val preferences = Preferences.userRoot().node("skirt-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val providers = RigLegacyBindings.providers(instance).map { (capability, id) ->
                val provider = snapshot.stack.instances.single { it.id == id }
                ModifierUpstreamCapability(capability, provider.type, provider.version, provider.targets, provider.id,
                    provider.config, provider.outputAliases, provider.plugin)
            }
            val context = RigSkirtModifier.capture(RigModifierSource.of(direct.now.model), instance).withUpstream(providers)
            repeat(2) {
                val entry = store.install(staging.toString())
                val worker = entry.manifest.algorithms.single()
                assertEquals(snapshot.outputs.last(),
                    ModifierPluginWorkers.evaluate(entry, worker, before, instance, {}, context = context))
                ModifierPluginWorkers.stop(entry.reference)
                store.uninstall(entry.reference)
                assertTrue(store.snapshot().isEmpty())
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
        val (slot, group) = RigModifierPhysics.entries(instance, actual).entries.first()
        val override = Physics3Json.setting(group).copy(name = "Adjusted skirt")
        direct.edit("modifier_update", buildJsonObject {
            put("id", instance.id)
            put("type", instance.type)
            put("config", JsonObject(instance.config + ("physics" to buildJsonObject { put(slot, override.toJson()) })))
        })
        val edited = RigIrCompiler.compile(direct.now.model)
        assertEquals(override.name, edited.physics.groups.single { it.id == group.id }.name)
        direct.edit("modifier_apply", buildJsonObject { put("id", instance.id) })
        assertEquals(edited, RigIrCompiler.compile(WorkspacePreviewBuilder().build(direct.now.document)))
    }

    @Test fun skirtV2MatchesTheRecommendedPresetWithClothingSimulation(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern")
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, model) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = true), preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val skirt = stack.instances.single { it.type == "outfit.skirt" }
        assertEquals(1, skirt.version)
        assertTrue(stack.instances.any { it.type == "simulation" })
        val plugin = assertNotNull(BuiltinModifierPlugins.reference(skirt.type, 2))
        val independent = stack.copy(instances = stack.instances.map {
            if (it.id == skirt.id) it.copy(version = 2, plugin = plugin) else it
        })
        val updated = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = independent.toJson()))
        val expected = RigIrCompiler.compile(model, tileArt = true)
        val checked = WorkspaceModifierSimulation.prepare(updated, independent, WorkspaceRasterWork.Direct, allowRebake = false)
        assertEquals(independent, checked)
        val current = WorkspacePreviewBuilder().build(updated)
        val report = assertNotNull(current.rig.modifierReport).getValue("entries").jsonArray
        assertTrue(report.all { it.jsonObject.getValue("status").jsonPrimitive.content == "active" })
        assertFaceEquals(expected, RigIrCompiler.compile(current, tileArt = true))
    }

    @Test fun creatureV2KeepsLegacyBindingAndRunsInstalledWorker(): Unit = runBlocking {
        suspend fun fixture(version: Int, withClothing: Boolean): Fixture {
            val f = Fixture(document(outfitDetails = true, expressionDetails = true))
            f.install()
            f.group(listOf("body/legacy", "face/legacy", "eye/legacy", "expression.toggles") + if (withClothing) listOf("hair/chains", "outfit.skirt", "outfit.ribbons") else emptyList())
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
                putJsonObject("values") { put("swing", 140f) }
            }
            f.edit("modifier_add", JsonObject(f.addition("outfit.creature", "creature", config = config) +
                ("version" to JsonPrimitive(version))))
            return f
        }
        val plainLegacy = fixture(1, false)
        val plain = fixture(2, false)
        assertFaceEquals(RigIrCompiler.compile(plainLegacy.now.model), RigIrCompiler.compile(plain.now.model))
        val legacy = fixture(1, true)
        val direct = fixture(2, true)
        val snapshot = assertNotNull(direct.now.model.modifierEvaluation)
        val instance = snapshot.stack.instances.last()
        val before = snapshot.outputs[snapshot.outputs.lastIndex - 1]
        val expected = RigIrCompiler.compile(legacy.now.model)
        val actual = RigIrCompiler.compile(direct.now.model)
        val axes = actual.parameters.filter { it.id.startsWith("modifier:creature:") }
        assertEquals(10, axes.size)
        assertEquals(axes.map { it.id }, RigIrCompiler.compile(plain.now.model).parameters
            .filter { it.id.startsWith("modifier:creature:") }.map { it.id })
        assertTrue(axes.any { it.name.contains("AnimalTail") })
        val physics = RigModifierPhysics.entries(instance, actual)
        val ear = physics.entries.single { (slot, _) -> slot.startsWith("chain:Ear:") && !slot.endsWith(":Y") }.value
        assertTrue(Physics3Json.setting(ear).inputs.any { it.parameter == StandardParameters.EYE_L_OPEN.raw && it.weight == 15f })
        val tail = physics.entries.single { (slot, _) -> slot.startsWith("chain:AnimalTail:") && !slot.endsWith(":Y") }.value
        assertTrue(Physics3Json.setting(tail).inputs.none { it.parameter == StandardParameters.EYE_L_OPEN.raw })
        assertEquals(6, physics.size)
        assertFaceEquals(expected, actual)
        val pose = axes.mapIndexed { i, parameter -> ParameterId(parameter.id) to if (i % 2 == 0) .65f else -.75f }.toMap()
        compare(legacy.now.model, direct.now.model, listOf(emptyMap(), pose,
            pose + mapOf(StandardParameters.BODY_X to 7f, StandardParameters.ANGLE_X to 23f, StandardParameters.BREATH to .6f, StandardParameters.EYE_L_OPEN to .1f)))

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("outfit.creature", 2))
        assertEquals(artifact.reference, instance.plugin)
        val directory = Files.createTempDirectory("creature-v2-")
        val preferences = Preferences.userRoot().node("creature-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val providers = RigLegacyBindings.providers(instance).map { (capability, id) ->
                val provider = snapshot.stack.instances.single { it.id == id }
                ModifierUpstreamCapability(capability, provider.type, provider.version, provider.targets, provider.id,
                    provider.config, provider.outputAliases, provider.plugin)
            }
            val context = RigCreatureModifier.capture(RigModifierSource.of(direct.now.model), instance).withUpstream(providers)
            repeat(2) {
                val entry = store.install(staging.toString())
                val worker = entry.manifest.algorithms.single()
                assertEquals(snapshot.outputs.last(),
                    ModifierPluginWorkers.evaluate(entry, worker, before, instance, {}, context = context))
                ModifierPluginWorkers.stop(entry.reference)
                store.uninstall(entry.reference)
                assertTrue(store.snapshot().isEmpty())
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
        val (slot, group) = RigModifierPhysics.entries(instance, actual).entries.first()
        val override = Physics3Json.setting(group).copy(name = "Adjusted creature pendulum")
        direct.edit("modifier_update", buildJsonObject {
            put("id", instance.id)
            put("type", instance.type)
            put("config", JsonObject(instance.config + ("physics" to buildJsonObject { put(slot, override.toJson()) })))
        })
        val edited = RigIrCompiler.compile(direct.now.model)
        assertEquals(override.name, edited.physics.groups.single { it.id == group.id }.name)
        direct.edit("modifier_apply", buildJsonObject { put("id", instance.id) })
        assertEquals(edited, RigIrCompiler.compile(WorkspacePreviewBuilder().build(direct.now.document)))
    }

    @Test fun creatureV2LeavesPairedWingsAndSkeletonLimbsToTheirOwners(): Unit = runBlocking {
        val base = document(outfitDetails = true, creatureDetails = true)
        suspend fun fixture(version: Int, skeleton: Boolean): Fixture {
            val doc = if (skeleton) base.copy(rigEdits = base.rigEdits.copy(skeleton = SkeletonSpec())) else base
            val f = Fixture(doc)
            f.install()
            f.group(listOf("body/legacy", "face/legacy"))
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
                put("physicsEnabled", false)
            }
            f.edit("modifier_add", JsonObject(f.addition("outfit.creature", "creature", config = config) +
                ("version" to JsonPrimitive(version))))
            return f
        }
        val legacy = fixture(1, false)
        val current = fixture(2, false)
        val rig = RigIrCompiler.compile(current.now.model)
        assertFaceEquals(RigIrCompiler.compile(legacy.now.model), rig)
        val axes = rig.parameters.filter { it.id.startsWith("modifier:creature:") }
        assertTrue(axes.any { it.name.startsWith("head-animal-ear ") })
        assertTrue(axes.none { it.name.startsWith("wing ") || it.name.startsWith("wing-left ") })
        val snapshot = assertNotNull(current.now.model.modifierEvaluation)
        assertTrue(RigModifierPhysics.entries(snapshot.stack.instances.last(), rig).isEmpty())

        val withSkeleton = fixture(2, true)
        val skeletonRig = RigIrCompiler.compile(withSkeleton.now.model)
        val skeletonAxes = skeletonRig.parameters.filter { it.id.startsWith("modifier:creature:") }
        assertEquals(6, skeletonAxes.size)
        assertTrue(skeletonAxes.all { it.name.startsWith("animal-ear ") || it.name.startsWith("head-animal-ear ") })
    }

    @Test fun ribbonsV2KeepsLegacyBindingAndRunsInstalledWorker(): Unit = runBlocking {
        suspend fun fixture(version: Int, withSkirt: Boolean): Fixture {
            val f = Fixture(document(outfitDetails = true, expressionDetails = true))
            f.install()
            f.group(listOf("body/legacy", "face/legacy", "expression.toggles") + if (withSkirt) listOf("outfit.skirt") else emptyList())
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
                putJsonObject("values") { put("swing", 140f) }
            }
            f.edit("modifier_add", JsonObject(f.addition("outfit.ribbons", "ribbons", config = config) +
                ("version" to JsonPrimitive(version))))
            return f
        }
        val plainLegacy = fixture(1, false)
        val plain = fixture(2, false)
        assertFaceEquals(RigIrCompiler.compile(plainLegacy.now.model), RigIrCompiler.compile(plain.now.model))
        val legacy = fixture(1, true)
        val direct = fixture(2, true)
        val snapshot = assertNotNull(direct.now.model.modifierEvaluation)
        val instance = snapshot.stack.instances.last()
        val before = snapshot.outputs[snapshot.outputs.lastIndex - 1]
        val expected = RigIrCompiler.compile(legacy.now.model)
        val actual = RigIrCompiler.compile(direct.now.model)
        val axes = actual.parameters.filter { it.id.startsWith("modifier:ribbons:") }
        assertEquals(6, axes.size)
        assertEquals(axes.map { it.id }, RigIrCompiler.compile(plain.now.model).parameters
            .filter { it.id.startsWith("modifier:ribbons:") }.map { it.id })
        assertTrue(axes.any { it.name.contains("Ribbon2") })
        assertEquals(4, RigModifierPhysics.entries(instance, actual).size)
        assertFaceEquals(expected, actual)
        val pose = axes.mapIndexed { i, parameter -> ParameterId(parameter.id) to if (i % 2 == 0) .65f else -.75f }.toMap()
        compare(legacy.now.model, direct.now.model, listOf(emptyMap(), pose,
            pose + mapOf(StandardParameters.BODY_X to 7f, StandardParameters.ANGLE_X to 23f, StandardParameters.BREATH to .6f)))

        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("outfit.ribbons", 2))
        assertEquals(artifact.reference, instance.plugin)
        val directory = Files.createTempDirectory("ribbons-v2-")
        val preferences = Preferences.userRoot().node("ribbons-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val providers = RigLegacyBindings.providers(instance).map { (capability, id) ->
                val provider = snapshot.stack.instances.single { it.id == id }
                ModifierUpstreamCapability(capability, provider.type, provider.version, provider.targets, provider.id,
                    provider.config, provider.outputAliases, provider.plugin)
            }
            val context = RigRibbonsModifier.capture(RigModifierSource.of(direct.now.model), instance).withUpstream(providers)
            repeat(2) {
                val entry = store.install(staging.toString())
                val worker = entry.manifest.algorithms.single()
                assertEquals(snapshot.outputs.last(),
                    ModifierPluginWorkers.evaluate(entry, worker, before, instance, {}, context = context))
                ModifierPluginWorkers.stop(entry.reference)
                store.uninstall(entry.reference)
                assertTrue(store.snapshot().isEmpty())
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
        val (slot, group) = RigModifierPhysics.entries(instance, actual).entries.first()
        val override = Physics3Json.setting(group).copy(name = "Adjusted ribbon")
        direct.edit("modifier_update", buildJsonObject {
            put("id", instance.id)
            put("type", instance.type)
            put("config", JsonObject(instance.config + ("physics" to buildJsonObject { put(slot, override.toJson()) })))
        })
        val edited = RigIrCompiler.compile(direct.now.model)
        assertEquals(override.name, edited.physics.groups.single { it.id == group.id }.name)
        direct.edit("modifier_apply", buildJsonObject { put("id", instance.id) })
        assertEquals(edited, RigIrCompiler.compile(WorkspacePreviewBuilder().build(direct.now.document)))
    }

    @Test fun ribbonsV2MatchesTheRecommendedPresetWithClothingSimulation(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern")
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, model) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = true), preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val clothing = stack.instances.filter { it.type in setOf("outfit.skirt", "outfit.ribbons") }
        assertEquals(2, clothing.size)
        assertTrue(clothing.all { it.version == 1 })
        assertTrue(stack.instances.any { it.type == "simulation" })
        val independent = stack.copy(instances = stack.instances.map {
            if (it in clothing) it.copy(version = 2, plugin = assertNotNull(BuiltinModifierPlugins.reference(it.type, 2))) else it
        })
        val updated = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = independent.toJson()))
        val expected = RigIrCompiler.compile(model, tileArt = true)
        val checked = WorkspaceModifierSimulation.prepare(updated, independent, WorkspaceRasterWork.Direct, allowRebake = false)
        assertEquals(independent, checked)
        val current = WorkspacePreviewBuilder().build(updated)
        val report = assertNotNull(current.rig.modifierReport).getValue("entries").jsonArray
        assertTrue(report.all { it.jsonObject.getValue("status").jsonPrimitive.content == "active" })
        assertFaceEquals(expected, RigIrCompiler.compile(current, tileArt = true))
    }

    @Test fun ribbonsV2MatchesHeadAndEarAttachmentsAndLeavesPairedArtAlone(): Unit = runBlocking {
        suspend fun fixture(version: Int): Fixture {
            val f = Fixture(document(ribbonDetails = true, expressionDetails = true))
            f.install()
            f.group(listOf("body/legacy", "face/legacy"))
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
            }
            f.edit("modifier_add", JsonObject(f.addition("outfit.ribbons", "ribbons", config = config) +
                ("version" to JsonPrimitive(version))))
            return f
        }
        val previous = fixture(1)
        val current = fixture(2)
        val expected = RigIrCompiler.compile(previous.now.model)
        val actual = RigIrCompiler.compile(current.now.model)
        assertFaceEquals(expected, actual)
        val axes = actual.parameters.filter { it.id.startsWith("modifier:ribbons:") }
        assertEquals(6, axes.size)
        val snapshot = assertNotNull(current.now.model.modifierEvaluation)
        for (name in listOf("matched-left", "matched-right", "hair-ribbon")) {
            val id = mesh(current, name).id.raw
            assertEquals(snapshot.outputs[snapshot.outputs.lastIndex - 1].meshes.single { it.id == id },
                snapshot.outputs.last().meshes.single { it.id == id })
        }
    }

    @Test fun separateClothingInstancesOwnOnlyTheirTargetsAndTheirOwnPhysics(): Unit = runBlocking {
        separateClothingInstances(1)
    }

    @Test fun ribbonsV2InstancesOwnOnlyTheirTargetsAndTheirOwnPhysics(): Unit = runBlocking {
        separateClothingInstances(2)
    }

    private suspend fun separateClothingInstances(version: Int) {
        val f = Fixture(document(outfitDetails = true), scaffoldVersion = 1); f.install()
        f.group(listOf("body/legacy", "face/legacy"))
        val ribbon = ModifierTarget("mesh", mesh(f, "ribbon").id.raw)
        val pendant = ModifierTarget("mesh", mesh(f, "pendant").id.raw)
        val configuration = buildJsonObject {
            put("scaffold", "structure"); putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
        }
        suspend fun add(id: String, target: ModifierTarget) =
            f.edit("modifier_add", JsonObject(f.addition("outfit.ribbons", id, listOf(target), configuration) +
                ("version" to JsonPrimitive(version))))
        add("ribbon-a", ribbon)
        fun stack() = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        fun owned(id: String) = RigIrCompiler.compile(f.now.model).parameters.map { it.id }.filter { it.startsWith("modifier:$id:") }.toSet()
        val first = owned("ribbon-a")
        assertEquals(4, first.size)
        add("pendant-b", pendant)
        val second = owned("pendant-b")
        assertEquals(2, second.size)
        assertTrue(first.intersect(second).isEmpty())
        val original = RigIrCompiler.compile(f.now.model)
        val before = f.now
        assertFailsWith<WorkspaceBatchEditException> {
            add("overlap", ribbon)
        }
        assertEquals(before.state, f.now.state)
        val evaluator = CpuDeformationEvaluator()
        val pose = (first + second).associate { ParameterId(it) to .65f }
        val pendantBefore = evaluator.evaluate(f.now.model.rig.puppet, pose).worldPositions.getValue(DrawableId(pendant.id))
        f.edit("modifier_enable", buildJsonObject { put("id", "ribbon-a"); put("enabled", false) })
        assertEquals(second, owned("pendant-b"))
        assertTrue(evaluator.evaluate(f.now.model.rig.puppet, pose).worldPositions.getValue(DrawableId(pendant.id)).contentEquals(pendantBefore))
        f.edit("modifier_enable", buildJsonObject { put("id", "ribbon-a"); put("enabled", true) })
        assertEquals(original, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_remove", buildJsonObject { put("id", "ribbon-a") })
        add("ribbon-new", ribbon)
        val replacement = owned("ribbon-new")
        assertEquals(first.size, replacement.size)
        assertTrue(first.intersect(replacement).isEmpty(), "a new instance cannot adopt a retired chain's parameter identity")
        val instance = stack().instances.single { it.id == "ribbon-new" }
        val initial = RigIrCompiler.compile(f.now.model)
        val (slot, group) = RigModifierPhysics.entries(instance, initial).entries.first()
        val edited = Physics3Json.setting(group).let { it.copy(segments = it.segments.map { segment -> segment.copy(delay = segment.delay * 1.2f) }) }
        val changed = JsonObject(configuration + ("physics" to buildJsonObject { put(slot, edited.toJson()) }))
        f.edit("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type); put("config", changed) })
        val after = RigIrCompiler.compile(f.now.model)
        assertEquals(initial, after.copy(physics = initial.physics), "physics edits do not rewrite geometry")
        assertEquals(edited, Physics3Json.setting(after.physics.groups.single { it.id == group.id }))
        val captured = f.now
        assertFailsWith<WorkspaceBatchEditException> {
            f.edit("modifier_update", buildJsonObject { put("id", instance.id); put("type", instance.type)
                put("config", JsonObject(configuration + ("physics" to buildJsonObject { put("unknown-chain", edited.toJson()) }))) })
        }
        assertEquals(captured.state, f.now.state)
        f.edit("modifier_apply", buildJsonObject { put("id", instance.id) })
        assertEquals(after, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun legacyHairSourceAndWorkerMatchVersionOne(): Unit = runBlocking<Unit> {
        for ((scaffoldVersion, face) in listOf(1 to null, 1 to "face/fitted", 2 to "face/legacy", 2 to "face/fitted")) {
            suspend fun fixture(version: Int): Pair<Fixture, io.github.psd2live.format.model.RigIR> {
                val f = Fixture(document(sideLock = true), scaffoldVersion)
                f.install()
                f.edit("modifier_add", f.addition(RigSourceModifiers.SCAFFOLD, "structure", config = buildJsonObject {
                    put("backHairSway", face == null)
                }))
                face?.let { f.edit("modifier_add", f.addition(it, "face")) }
                val input = requireNotNull(f.now.model.rig.modifierOutput)
                val config = buildJsonObject {
                    put("scaffold", "structure")
                    putJsonObject("tuning") {
                        put("frontHairSway", 27f)
                        put("frontHairPerspective", 19f)
                        put("backHairTurn", 4f)
                        put("hairRollHold", 57f)
                    }
                }
                f.edit("modifier_add", JsonObject(f.addition("hair/legacy", "hair", config = config) +
                    ("version" to JsonPrimitive(version))))
                return f to input
            }
            val (legacy) = fixture(1)
            val (direct, input) = fixture(2)
            val expected = RigIrCompiler.compile(legacy.now.model)
            val actual = RigIrCompiler.compile(direct.now.model)
            assertEquals(expected.parameters, actual.parameters)
            assertEquals(expected.parameterTree, actual.parameterTree)
            assertEquals(expected.deformers, actual.deformers)
            assertEquals(expected.meshes, actual.meshes)
            assertEquals(expected.physics, actual.physics)
            val stack = RigModifierStack.fromJson(direct.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.last()
            val reference = requireNotNull(instance.plugin)
            val entry = ModifierPluginStore.shared.snapshot().single { it.reference == reference }
            val context = RigModifierSource.of(direct.now.model).hairLegacyContext(instance)
            try {
                val result = ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input, instance, {}, context = context,
                    declaredParameters = RigHairLegacySource.parameters(stack.instances.first()))
                assertEquals(direct.now.model.rig.modifierOutput, result)
            } finally {
                ModifierPluginWorkers.stop(reference)
            }
        }
    }

    @Test fun hairChainsReplaceTheNativeSwayBranchAndKeepOutputsAfterApplication(): Unit = runBlocking<Unit> {
        for (version in 1..2) {
            val f = Fixture(document()); f.install()
            f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted"))
            val beforeHair = requireNotNull(f.now.model.rig.modifierOutput)
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
            }
            f.edit("modifier_add", JsonObject(f.addition("hair/chains", "hair", config = config) +
                ("version" to JsonPrimitive(version))))
            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            val instance = stack.instances.single { it.type == "hair/chains" }
            val expected = RigIrCompiler.compile(f.now.model)
            val source = RigModifierSource.of(f.now.model)
            val aliases = source.chains(instance).flatMap { it.aliases.entries }.associate { it.key to it.value }
            val hairMeshes = if (version == 1) null else
                io.github.psd2live.algorithms.binding.HairChainsInputs.decode(source.hairChainsContext(instance))
                    .layers.flatMapTo(HashSet()) { layer -> layer.meshes.map(::DrawableId) }
            if (hairMeshes != null) {
                val afterHair = requireNotNull(f.now.model.rig.modifierOutput)
                assertEquals(beforeHair.meshes.filter { DrawableId(it.id) !in hairMeshes },
                    afterHair.meshes.filter { DrawableId(it.id) !in hairMeshes })
            }
            assertTrue(aliases.isNotEmpty())
            assertTrue(expected.parameters.none { it.id in setOf("ParamHairFront", "ParamHairBack") || it.id in aliases })
            val nativeRecipe = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"),
                "mouth" to BaseChoice("mouth/fitted"), "hair" to BaseChoice("hair/chains")))
            val swing = aliases.values.mapIndexed { index, value -> ParameterId(value) to if (index % 2 == 0) .7f else -.6f }.toMap()
            compare(native(f.document, nativeRecipe), f.now.model, listOf(emptyMap(), swing,
                swing + mapOf(StandardParameters.ANGLE_X to 20f, StandardParameters.ANGLE_Z to -15f, StandardParameters.BODY_X to 5f)), aliases, hairMeshes)
            assertTrue(expected.physics.groups.isNotEmpty())
            val capture = f.now
            assertFailsWith<WorkspaceBatchEditException> { f.edit("modifier_add", f.addition("hair/legacy", "competing")) }
            assertEquals(capture.state, f.now.state)
            f.edit("modifier_apply", buildJsonObject { put("id", "mouth") })
            assertEquals(expected, RigIrCompiler.compile(f.now.model))
            assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
            f.edit("modifier_apply", buildJsonObject { put("id", "hair") })
            assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        }
    }

    @Test fun independentHairChainsMatchLegacyHierarchyAndMotion(): Unit = runBlocking<Unit> {
        suspend fun fixture(version: Int): Pair<Fixture, io.github.psd2live.format.model.RigIR> {
            val f = Fixture(document(sideLock = true, bangOverlay = true))
            f.install()
            f.group(listOf("body/legacy", "face/legacy"))
            val input = requireNotNull(f.now.model.rig.modifierOutput)
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
                putJsonObject("values") { put("swing", 145f); put("softness", 70f) }
                putJsonObject("tuning") { put("frontHairTurn", 3f); put("backHairTilt", -1.5f) }
            }
            f.edit("modifier_add", JsonObject(f.addition("hair/chains", "hair", config = config) +
                ("version" to JsonPrimitive(version))))
            return f to input
        }
        val (legacy) = fixture(1)
        val (direct, input) = fixture(2)
        val expected = RigIrCompiler.compile(legacy.now.model)
        val actual = RigIrCompiler.compile(direct.now.model)
        val instances = RigModifierStack.fromJson(direct.now.document.rigEdits.modifierStack!!).instances
        val instance = instances.last()
        fun owned(id: String) = instance.localOutputName(id) != null
        val parameters = actual.parameters.filter { owned(it.id) }
        assertTrue(parameters.isNotEmpty())
        assertEquals(expected.parameters.filter { owned(it.id) }, parameters)
        val branches = actual.deformers.filter { owned(it.id) }
        assertTrue(branches.size >= 2)
        assertEquals(expected.deformers.filter { owned(it.id) }.associate { it.id to it.parent },
            branches.associate { it.id to it.parent })
        assertEquals(expected.meshes.associate { it.id to it.parent }, actual.meshes.associate { it.id to it.parent })
        assertEquals(expected.physics.groups, actual.physics.groups)
        val parameterIds = actual.parameters.mapTo(HashSet()) { it.id }
        assertTrue(actual.physics.groups.all { group ->
            group.outputs.all { it.parameter in parameterIds && owned(it.parameter) } &&
                group.inputs.all { it.parameter in parameterIds }
        })
        val sway = parameters.mapIndexed { index, p -> ParameterId(p.id) to if (index % 2 == 0) .7f else -.6f }.toMap()
        compare(legacy.now.model, direct.now.model, listOf(emptyMap(), sway,
            sway + mapOf(StandardParameters.ANGLE_X to 20f, StandardParameters.ANGLE_Z to -15f, StandardParameters.BODY_X to 5f)))
        val reference = requireNotNull(instance.plugin)
        val entry = ModifierPluginStore.shared.snapshot().single { it.reference == reference }
        val context = RigModifierSource.of(direct.now.model).hairChainsContext(instance).withUpstream(instances.dropLast(1).map {
            ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
        })
        try {
            val fromWorker = ModifierPluginWorkers.evaluate(entry, entry.manifest.algorithms.single(), input, instance, {}, context = context)
            assertEquals(direct.now.model.rig.modifierOutput, fromWorker)
        } finally {
            ModifierPluginWorkers.stop(reference)
        }
        val (slot, group) = RigModifierPhysics.entries(instance, actual).entries.first()
        val rule = Physics3Json.setting(group)
        val edit = rule.copy(segments = rule.segments.map { it.copy(delay = it.delay * 1.2f) })
        direct.edit("modifier_update", buildJsonObject {
            put("id", instance.id)
            put("type", instance.type)
            put("config", JsonObject(instance.config + ("physics" to buildJsonObject { put(slot, edit.toJson()) })))
        })
        val edited = RigIrCompiler.compile(direct.now.model)
        assertEquals(actual, edited.copy(physics = actual.physics))
        assertEquals(edit, Physics3Json.setting(edited.physics.groups.single { it.id == group.id }))
        direct.edit("modifier_apply", buildJsonObject { put("id", "hair") })
        assertEquals(edited, RigIrCompiler.compile(direct.builder.build(direct.now.document)))
    }

    @Test fun chainIdentitiesAndEditedPhysicsSurviveUnrelatedNativeRenumbering(): Unit = runBlocking<Unit> {
        val f = Fixture(document(outfitDetails = true)); f.install()
        f.group(listOf("body/legacy", "face/legacy"))
        val config = buildJsonObject {
            put("scaffold", "structure"); putJsonObject("dependencies") { put("body/legacy", "body"); put("face/legacy", "face") }
        }
        f.edit("modifier_add", f.addition("outfit.skirt", "skirt", config = config))
        f.edit("modifier_add", f.addition("outfit.ribbons", "ribbons", config = config))
        fun instance() = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single { it.id == "ribbons" }
        fun owned() = RigIrCompiler.compile(f.now.model).let { rig ->
            Triple(rig.parameters.map { it.id }.filter { it.startsWith("modifier:ribbons:") }.toSet(),
                rig.deformers.map { it.id }.filter { it.startsWith("modifier:ribbons:") }.toSet(),
                RigModifierPhysics.entries(instance(), rig).mapValues { it.value.id })
        }
        val identities = owned()
        assertEquals(6, identities.first.size)
        assertEquals(2, identities.second.size)
        assertEquals(4, identities.third.size)
        val report = WorkspaceModifierQueries.describe(f.now)
        validateOperationSchema(report, WorkspaceModifierSchemas.stack, "result")
        val observed = report.getValue("entries").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "ribbons" }
        assertEquals(identities.third.keys, observed.getValue("contributions").jsonArray.mapNotNull { it.jsonObject["physics_slot"]?.jsonPrimitive?.content }.toSet())
        val originalNumbers = RigModifierSource.of(f.now.model).chains(instance()).map { it.binding.native.index }
        val (slot, group) = RigModifierPhysics.entries(instance(), RigIrCompiler.compile(f.now.model)).entries.first()
        val setting = Physics3Json.setting(group).copy(name = "Edited ribbon pendulum")
        f.edit("modifier_update", buildJsonObject { put("id", "ribbons"); put("type", "outfit.ribbons")
            put("config", JsonObject(config + ("physics" to buildJsonObject { put(slot, setting.toJson()) }))) })
        val edited = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_enable", buildJsonObject { put("id", "skirt"); put("enabled", false) })
        assertNotEquals(originalNumbers, RigModifierSource.of(f.now.model).chains(instance()).map { it.binding.native.index },
            "the fixture must actually renumber the original algorithm's chains")
        assertEquals(identities, owned())
        assertEquals(setting, Physics3Json.setting(RigModifierPhysics.entries(instance(), RigIrCompiler.compile(f.now.model)).getValue(slot)))
        f.edit("modifier_enable", buildJsonObject { put("id", "skirt"); put("enabled", true) })
        assertEquals(edited, RigIrCompiler.compile(f.now.model))
        f.edit("modifier_move", buildJsonObject { put("id", "ribbons"); put("index", 3) })
        assertEquals(identities, owned())
        assertEquals(setting, Physics3Json.setting(RigModifierPhysics.entries(instance(), RigIrCompiler.compile(f.now.model)).getValue(slot)))
        val moved = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "ribbons") })
        assertEquals(moved, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun fittedMouthV2UsesTheLiveScaffoldWithoutSwitchingTheDefault(): Unit = runBlocking<Unit> {
        val f = Fixture(document())
        f.install()
        f.edit("modifier_add", f.addition("rig.scaffold", "structure"))
        val scaffold = RigIrCompiler.compile(f.now.model)
        val request = f.addition("mouth/fitted", "mouth")
        f.edit("modifier_add", request)
        val legacy = RigIrCompiler.compile(f.now.model)
        assertEquals(1, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.last().version)
        f.edit("modifier_remove", buildJsonObject { put("id", "mouth") })
        f.edit("modifier_add", JsonObject(request + mapOf("id" to JsonPrimitive("mouth-v2"), "version" to JsonPrimitive(2))))
        val actual = RigIrCompiler.compile(f.now.model)
        val mouthId = f.now.model.rig.layerIdByDrawableId.entries.single { it.value == "mouth" }.key
        assertEquals(legacy.meshes.single { it.id == mouthId }, actual.meshes.single { it.id == mouthId })
        assertEquals(scaffold.meshes.filter { it.id != mouthId }, actual.meshes.filter { it.id != mouthId })
        assertEquals(scaffold.deformers, actual.deformers)
        assertEquals(actual, RigIrCompiler.compile(WorkspacePreviewBuilder().build(f.now.document)))
        val evaluation = requireNotNull(f.now.model.modifierEvaluation)
        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val structure = stack.instances.first()
        val instance = stack.instances.last()
        val upstream = ModifierUpstreamCapability(structure.type, structure.type, structure.version, structure.targets,
            structure.id, structure.config, structure.outputAliases, structure.plugin)
        val directory = Files.createTempDirectory("fitted-mouth-v2-")
        val preferences = Preferences.userRoot().node("fitted-mouth-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(MouthFittedV2Algorithm.TYPE, MouthFittedV2Algorithm.VERSION))
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val entry = store.install(staging.toString())
            val owner = RigModifierSource.of(f.now.model)
            val worker = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(owner))
                .single { it.type == MouthFittedV2Algorithm.ENTRYPOINT }
            val installed = instance.copy(type = worker.type, plugin = entry.reference)
            val context = worker.authorContext(installed).withUpstream(listOf(upstream))
            val plugin = worker.plugin as ContextualModifierAlgorithmPlugin
            val before = evaluation.outputs.first()
            assertEquals(evaluation.outputs.last(), plugin.evaluate(before, installed, context, {}))
            val mouth = before.meshes.single { it.id == mouthId }
            val geometry = requireNotNull(mouth.geometry)
            val moved = geometry.copy(positions = io.github.psd2live.format.model.Floats.of(
                geometry.positions.toArray().also { it[0] += .01f }))
            val authored = before.copy(meshes = before.meshes.map { if (it.id == mouthId) it.copy(geometry = moved) else it })
            assertEquals(moved, plugin.evaluate(authored, installed, context, {}).meshes.single { it.id == mouthId }.geometry)
            val head = ModifierUpstreamCapability("face/fitted", "face/fitted", 1, instance.targets, "head")
            assertFailsWith<IllegalArgumentException> {
                MouthFittedV2Algorithm().changes(before, instance, context.withUpstream(listOf(upstream, head))) {}
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun fittedFaceV2KeepsV1DefaultAndMatchesItsInstalledWorker(): Unit = runBlocking<Unit> {
        val source = Characters.eyed(legacyEyes = true)
        val document = source.copy(
            settings = WorkspaceSettingsCodec.encode(RigModifierPipeline.ordinaryConfig(source.rawConfig())),
            rigEdits = source.rigEdits.copy(modifierStack = RigModifierStack().toJson()),
        )
        val prior = Fixture(document)
        prior.install()
        prior.edit("modifier_add", prior.addition("rig.scaffold", "structure"))
        prior.edit("modifier_add", prior.addition("face/fitted", "face"))
        assertEquals(1, RigModifierStack.fromJson(prior.now.document.rigEdits.modifierStack!!).instances.last().version)
        val legacy = assertNotNull(prior.now.model.rig.modifierOutput)
        val fixture = Fixture(document)
        fixture.install()
        fixture.edit("modifier_add", fixture.addition("rig.scaffold", "structure"))
        val request = fixture.addition("face/fitted", "face")
        fixture.edit("modifier_add", JsonObject(request + ("version" to JsonPrimitive(2))))
        val actual = assertNotNull(fixture.now.model.rig.modifierOutput)
        val stack = RigModifierStack.fromJson(fixture.now.document.rigEdits.modifierStack!!)
        val (scaffold, face) = stack.instances
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(FittedFaceV2Algorithm.TYPE, 2))
        assertEquals(artifact.reference, face.plugin)
        assertEquals(2, face.version)
        val evaluation = assertNotNull(fixture.now.model.modifierEvaluation)
        val root = Files.createTempDirectory("fitted-face-v2-")
        val preferences = Preferences.userRoot().node("fitted-face-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(root.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val installed = store.install(staging.toString())
            val workerType = artifact.manifest.algorithms.single().type
            val worker = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(RigModifierSource.of(fixture.now.model)))
                .single { it.type == workerType }
            val instance = face.copy(type = workerType, plugin = installed.reference)
            val context = worker.authorContext(instance).withUpstream(listOf(
                ModifierUpstreamCapability(scaffold.type, scaffold.type, scaffold.version, scaffold.targets,
                    scaffold.id, scaffold.config, scaffold.outputAliases, scaffold.plugin),
            ))
            val plugin = worker.plugin as ContextualModifierAlgorithmPlugin
            assertEquals(actual, plugin.evaluate(evaluation.outputs.first(), instance, context, {}))
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
        val legacyMeshes = legacy.meshes.associateBy { it.id }
        val legacyDeformers = legacy.deformers.associateBy { it.id }
        val differences = buildList {
            if (actual.parameters != legacy.parameters) add("parameters")
            if (actual.parameterTree != legacy.parameterTree) add("parameterTree")
            if (actual.parameterLinks != legacy.parameterLinks) add("parameterLinks")
            addAll(actual.meshes.filter { legacyMeshes[it.id] != it }.map { "mesh:${it.id}" })
            addAll(actual.deformers.filter { legacyDeformers[it.id] != it }.map { "deformer:${it.name}" })
            if (actual.meshes.map { it.id } != legacy.meshes.map { it.id }) add("mesh order")
            if (actual.deformers.map { it.id } != legacy.deformers.map { it.id }) add("deformer order")
        }
        assertTrue(differences.isEmpty(), differences.joinToString())
    }

    @Test fun fittedFaceV3MatchesCompleteRecommendedPresetAndItsInstalledWorker(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern")
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, baseline) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = true), preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val face = stack.instances.single { it.type == "face/fitted" }
        val body = stack.instances.single { it.type == "body/legacy" }
        assertEquals(1, face.version)
        assertEquals(2, body.version)
        val index = stack.index(face.id)
        val expected = assertNotNull(baseline.modifierEvaluation).outputs
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(face.type, 3))
        val independent = face.copy(version = 3, plugin = artifact.reference)
        fun withFace(replacement: RigModifierInstance): WorkspaceDocument {
            val updated = stack.copy(instances = stack.instances.map { if (it.id == face.id) replacement else it })
            return initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = updated.toJson()))
        }
        val oldOptIn = face.copy(version = 2, plugin = assertNotNull(BuiltinModifierPlugins.reference(face.type, 2)))
        assertFailsWith<IllegalArgumentException> { WorkspacePreviewBuilder().build(withFace(oldOptIn)) }
        val actual = WorkspacePreviewBuilder().build(withFace(independent))
        assertFaceEquals(expected[index], assertNotNull(actual.modifierEvaluation).outputs[index])
        assertFaceEquals(RigIrCompiler.compile(baseline), RigIrCompiler.compile(actual))

        val directory = Files.createTempDirectory("fitted-face-v3-")
        val preferences = Preferences.userRoot().node("fitted-face-v3/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val installed = store.install(staging.toString())
            assertEquals(artifact.reference, installed.reference)
            val definition = RigFittedFaceDefinition.bodyDefinition(RigModifierSource.of(actual))
            val upstream = stack.instances.take(index).filter { it.type in definition.requires + definition.optionalUpstream }.map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = definition.authorContext(independent).withUpstream(upstream)
            assertFaceEquals(expected[index], ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                expected[index - 1], independent, {}, store, context = context,
                declaredParameters = definition.parameters(independent)))
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun fittedEyeV5MatchesCompleteRecommendedPresetAndItsInstalledWorker(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern")
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, baseline) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = true), preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val face = stack.instances.single { it.type == "face/fitted" }
        val body = stack.instances.single { it.type == "body/legacy" }
        val eye = stack.instances.single { it.type == "eye/fitted" }
        assertEquals(1, face.version)
        assertEquals(2, body.version)
        assertEquals(2, eye.version)
        val index = stack.index(eye.id)
        val expected = assertNotNull(baseline.modifierEvaluation).outputs
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(eye.type, 5))
        val independent = eye.copy(version = 5, plugin = artifact.reference)

        val directory = Files.createTempDirectory("fitted-eye-v5-")
        val preferences = Preferences.userRoot().node("fitted-eye-v5/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val installed = store.install(staging.toString())
            assertEquals(artifact.reference, installed.reference)
            for (faceVersion in listOf(1, 3)) {
                val candidate = stack.copy(instances = stack.instances.map {
                    when (it.id) {
                        eye.id -> independent
                        face.id -> if (faceVersion == 1) face else face.copy(version = faceVersion,
                            plugin = assertNotNull(BuiltinModifierPlugins.reference(face.type, faceVersion)))
                        else -> it
                    }
                })
                fun candidateDocument(value: RigModifierStack) =
                    initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = value.toJson()))
                val prefix = candidate.copy(instances = candidate.instances.take(index + 1))
                val partial = WorkspacePreviewBuilder().build(candidateDocument(prefix))
                val outputs = assertNotNull(partial.modifierEvaluation).outputs
                assertFaceEquals(expected[index - 1], outputs[index - 1])
                assertFaceEquals(expected[index], outputs[index])
                val actual = WorkspacePreviewBuilder().build(candidateDocument(candidate))
                assertFaceEquals(RigIrCompiler.compile(baseline), RigIrCompiler.compile(actual))

                val definition = RigFittedEyeDefinition.modernDefinition(RigModifierSource.of(actual))
                val upstream = candidate.instances.take(index).filter {
                    it.type in definition.requires
                }.map {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                }
                val context = definition.authorContext(independent).withUpstream(upstream)
                assertFaceEquals(expected[index], ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                    outputs[index - 1], independent, {}, store, context = context,
                    declaredParameters = definition.parameters(independent)))
            }
        } finally {
            ModifierPluginWorkers.stop(artifact.reference)
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun independentLegacyFaceKeepsHeadMotionWithOrWithoutBody(): Unit = runBlocking {
        for (bodyVersion in listOf(null, 1, 2)) {
            val previous = legacyFaceFixture(null, bodyVersion)
            val current = legacyFaceFixture(2, bodyVersion)
            val prior = assertNotNull(previous.now.model.modifierEvaluation)
            val direct = assertNotNull(current.now.model.modifierEvaluation)
            assertEquals(1, prior.stack.instances.last().version)
            assertEquals(BuiltinModifierPlugins.reference("face/legacy", 1), prior.stack.instances.last().plugin)
            assertEquals(BundledAlgorithmArtifacts.find("face/legacy", 2)?.reference, direct.stack.instances.last().plugin)
            assertFaceEquals(prior.outputs.last(), direct.outputs.last())
            val expected = RigIrCompiler.compile(previous.now.model)
            assertFaceEquals(expected, RigIrCompiler.compile(current.now.model))
            current.edit("modifier_apply", buildJsonObject { put("id", if (bodyVersion == null) "structure" else "body") })
            assertFaceEquals(expected, RigIrCompiler.compile(WorkspacePreviewBuilder().build(current.now.document)))
            current.edit("modifier_apply", buildJsonObject { put("id", "face") })
            assertFaceEquals(expected, RigIrCompiler.compile(WorkspacePreviewBuilder().build(current.now.document)))
        }
    }

    @Test fun independentLegacyFaceRejectsAnOlderTemplateScaffold(): Unit = runBlocking {
        val fixture = legacyFaceFixture(2, null)
        val model = fixture.now.model
        val stack = assertNotNull(model.modifierEvaluation).stack
        val (scaffold, face) = stack.instances
        val oldScaffold = scaffold.copy(plugin = assertNotNull(scaffold.plugin).copy(version = "1.0.40"))
        val oldStack = stack.copy(instances = listOf(oldScaffold, face))
        val config = model.config.copy(rigEdits = model.config.rigEdits.copy(modifierStack = oldStack.toJson()))
        val definition = RigLegacyFaceDefinition.definition(RigModifierSource.of(model.copy(config = config)))
        val failure = assertFailsWith<IllegalArgumentException> { definition.authorContext(face) }
        assertEquals("Legacy face v2 requires a source-aware shared scaffold package", failure.message)
    }

    @Test fun installedLegacyFaceV2MergesAuthoredGeometryLikeV1(): Unit = runBlocking {
        val fixture = legacyFaceFixture(2, 2)
        val evaluation = assertNotNull(fixture.now.model.modifierEvaluation)
        val (scaffold, body, face) = evaluation.stack.instances
        val source = RigModifierSource.of(fixture.now.model)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("face/legacy", 2))
        val directory = Files.createTempDirectory("face-legacy-v2-")
        val preferences = Preferences.userRoot().node("face-legacy-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val entry = store.install(staging.toString())
            val worker = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(source))
                .single { it.type == LegacyFaceV2Algorithm.ENTRYPOINT }
            val instance = face.copy(type = worker.type, plugin = entry.reference)
            val upstream = listOf(scaffold, body).map {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            }
            val context = worker.authorContext(instance).withUpstream(upstream)
            assertEquals(setOf("materials", "source_meshes"), context.resources.keys)
            val plugin = worker.plugin as ContextualModifierAlgorithmPlugin
            val before = evaluation.outputs[evaluation.outputs.lastIndex - 1]
            assertFaceEquals(evaluation.outputs.last(), plugin.evaluate(before, instance, context, {}))
            val id = RigSourceModifiers.deformerId(scaffold, face.targets.first().id, io.github.psd2live.algorithms.binding.ScaffoldHead.faceId.raw)
            val warp = before.deformers.single { it.id == id } as io.github.psd2live.format.model.Deformer.Warp
            val lattice = requireNotNull(warp.lattice)
            val authored = warp.copy(lattice = lattice.copy(cells = lattice.cells.map { cell ->
                cell.copy(form = cell.form.copy(points = io.github.psd2live.format.model.Floats.of(
                    cell.form.points.toArray().also { it[0] += .01f })))
            }))
            val faceMesh = before.meshes.single { it.id == mesh(fixture, "face").id.raw }
            val geometry = requireNotNull(faceMesh.geometry)
            val moved = geometry.copy(positions = io.github.psd2live.format.model.Floats.of(
                geometry.positions.toArray().also { it[0] += .01f }))
            val edited = before.copy(deformers = before.deformers.map { if (it.id == id) authored else it },
                meshes = before.meshes.map { if (it.id == faceMesh.id) it.copy(geometry = moved) else it })
            val legacyInstance = face.copy(version = 1, plugin = BuiltinModifierPlugins.reference("face/legacy", 1))
            val legacy = RigSourceBinding.apply(edited, legacyInstance, source, {})
            val fromWorker = plugin.evaluate(edited, instance, context, {})
            assertFaceEquals(legacy, fromWorker)
            assertNotEquals(evaluation.outputs.last().deformers.single { it.id == id }, fromWorker.deformers.single { it.id == id })
            assertNotEquals(evaluation.outputs.last().meshes.single { it.id == faceMesh.id }, fromWorker.meshes.single { it.id == faceMesh.id })
            store.uninstall(entry.reference)
            assertTrue(store.snapshot().none { it.reference == entry.reference })
            assertEquals(entry.reference, store.install(staging.toString()).reference)
            val reinstalled = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(source))
                .single { it.type == LegacyFaceV2Algorithm.ENTRYPOINT }.plugin as ContextualModifierAlgorithmPlugin
            assertFaceEquals(fromWorker, reinstalled.evaluate(edited, instance, context, {}))
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    private suspend fun legacyFaceFixture(faceVersion: Int?, bodyVersion: Int?): Fixture {
        val fixture = Fixture(document(eyeDetails = true, mouthDetails = true, expressionDetails = true, outfitDetails = true))
        fixture.install()
        fixture.edit("modifier_add", fixture.addition("rig.scaffold", "structure", config = buildJsonObject {
            put("featureDisplacement", true)
        }))
        if (bodyVersion != null) {
            val body = fixture.addition("body/legacy", "body", config = buildJsonObject {
                put("scaffold", "structure"); put("strength", .75f)
                putJsonObject("tuning") { put("faceLeanUp", 11f); put("breathLift", 4.25f) }
            })
            fixture.edit("modifier_add", JsonObject(body + ("version" to JsonPrimitive(bodyVersion))))
        }
        val face = fixture.addition("face/legacy", "face", config = buildJsonObject {
            put("scaffold", "structure"); put("strength", .85f)
            putJsonObject("tuning") { put("faceTurn", 13f); put("earFade", 43f) }
        })
        fixture.edit("modifier_add", if (faceVersion == null) face else JsonObject(face + ("version" to JsonPrimitive(faceVersion))))
        return fixture
    }

    private fun assertFaceEquals(expected: io.github.psd2live.format.model.RigIR, actual: io.github.psd2live.format.model.RigIR) {
        assertEquals(expected.deformers.map { it.id }, actual.deformers.map { it.id })
        for ((left, right) in expected.deformers.zip(actual.deformers)) assertEquals(left, right, left.name)
        assertEquals(expected.meshes.map { it.id }, actual.meshes.map { it.id })
        for ((left, right) in expected.meshes.zip(actual.meshes)) assertEquals(left, right, left.name)
        assertEquals(expected.copy(deformers = emptyList(), meshes = emptyList()), actual.copy(deformers = emptyList(), meshes = emptyList()))
    }

    @Test fun independentBodyKeepsLegacyHeadAndBodyMotion(): Unit = runBlocking<Unit> {
        suspend fun fixture(version: Int?): Fixture {
            val f = Fixture(document(outfitDetails = true))
            f.install()
            f.edit("modifier_add", f.addition("rig.scaffold", "structure"))
            val body = f.addition("body/legacy", "body", config = buildJsonObject {
                put("scaffold", "structure")
                put("strength", .75f)
                putJsonObject("tuning") { put("faceLeanUp", 11f); put("breathLift", 4.25f) }
            })
            f.edit("modifier_add", if (version == null) body else JsonObject(body + ("version" to JsonPrimitive(version))))
            f.edit("modifier_add", f.addition("face/legacy", "face"))
            return f
        }
        val previous = fixture(null)
        val current = fixture(2)
        val stack = RigModifierStack.fromJson(current.now.document.rigEdits.modifierStack!!)
        val (scaffold, body) = stack.instances
        assertEquals(1, RigModifierStack.fromJson(previous.now.document.rigEdits.modifierStack!!).instances[1].version)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find("body/legacy", 2))
        assertEquals(artifact.reference, body.plugin)
        val output = assertNotNull(current.now.model.modifierEvaluation).outputs
        val previousOutput = assertNotNull(previous.now.model.modifierEvaluation).outputs
        assertEquals(previousOutput[1], output[1])
        val expected = RigIrCompiler.compile(previous.now.model)
        assertEquals(expected, RigIrCompiler.compile(current.now.model))

        val directory = Files.createTempDirectory("body-legacy-v2-")
        val preferences = Preferences.userRoot().node("body-legacy-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val entry = store.install(staging.toString())
            val algorithm = entry.manifest.algorithms.single()
            val worker = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(RigModifierSource.of(current.now.model)))
                .single { it.type == algorithm.type }
            val instance = body.copy(type = algorithm.type, plugin = entry.reference)
            val context = worker.authorContext(instance).withUpstream(listOf(
                ModifierUpstreamCapability(scaffold.type, scaffold.type, scaffold.version, scaffold.targets,
                    scaffold.id, scaffold.config, scaffold.outputAliases, scaffold.plugin),
            ))
            assertEquals(output[1], (worker.plugin as ContextualModifierAlgorithmPlugin)
                .evaluate(output[0], instance, context, {}))
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
        current.edit("modifier_apply", buildJsonObject { put("id", "body") })
        assertEquals(expected, RigIrCompiler.compile(WorkspacePreviewBuilder().build(current.now.document)))
    }

    @Test fun fittedOutlineMatchesRecommendedImport(): Unit = runBlocking {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val choices = WorkspaceInitialPreset()
        val config = choices.configure(WorkspacePresetBaseline.configure(art.config(), "modern"))
            .copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, model) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct, choices,
            preferSourceBindings = true)
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        val locked = stack.instances.single { it.type == "mouth.outline" }
        assertEquals(1, locked.version)
        val index = stack.index(locked.id)
        val expected = assertNotNull(model.modifierEvaluation).outputs[index]
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(locked.type, 3))
        val instance = locked.copy(version = 3, plugin = artifact.reference)
        val independent = stack.copy(instances = stack.instances.map { if (it.id == locked.id) instance else it })
        val updated = initialized.copy(rigEdits = initialized.rigEdits.copy(modifierStack = independent.toJson()))
        val actual = WorkspacePreviewBuilder().build(updated)
        val evaluation = assertNotNull(actual.modifierEvaluation)
        val result = evaluation.outputs[index]
        assertEquals(expected.deformers.map { it.id }, result.deformers.map { it.id })
        val wrongDeformers = expected.deformers.zip(result.deformers).filter { it.first != it.second }.map { it.first.name }
        val wrongMeshes = expected.meshes.zip(result.meshes).filter { it.first != it.second }.map { (a, b) ->
            a.name + " geometry=${a.geometry == b.geometry} offsets=${a.offsets == b.offsets}" +
                " channels=${a.channels == b.channels} shapes=${a.shapes == b.shapes}"
        }
        assertTrue(expected == result, "Differing deformers: $wrongDeformers. Differing meshes: $wrongMeshes.")
        assertEquals(model.rig.modifierOutput, actual.rig.modifierOutput)
        val legacyCompiled = RigIrCompiler.compile(model, tileArt = true)
        val sourceCompiled = RigIrCompiler.compile(actual, tileArt = true)
        assertEquals(legacyCompiled, sourceCompiled)
        assertContentEquals(io.github.psd2live.format.compile.RigIrBinary.encode(legacyCompiled),
            io.github.psd2live.format.compile.RigIrBinary.encode(sourceCompiled))

        val directory = Files.createTempDirectory("fitted-outline-v3-package-")
        val preferences = Preferences.userRoot().node("fitted-outline-v3-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            for (name in artifact.manifest.files.keys) {
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val context = assertNotNull(evaluation.readAlgorithms[instance.id]).context
            val installed = store.install(staging.toString())
            assertEquals(artifact.reference, installed.reference)
            assertEquals(expected, ModifierPluginWorkers.evaluate(installed, installed.manifest.algorithms.single(),
                evaluation.outputs[index - 1], instance, {}, store, context = context,
                resolveRasters = { rig, outputs, checkpoint -> RigModifierRasterAssets.attach(rig, outputs, actual.config, checkpoint) }))
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
    }

    @Test fun mouthOutlineV2UsesCapturedLegacyMouthAndReplaysWithoutSwitchingTheDefault(): Unit = runBlocking {
        suspend fun fixture(): Fixture = Fixture(document()).also { it.install(); it.group(listOf("mouth/legacy")) }
        suspend fun add(f: Fixture, type: String) {
            val plan = WorkspaceModifierPlan.prepare(f.now, buildJsonObject {
                put("type", type)
                putJsonArray("targets") { f.now.model.rig.puppet.drawables.forEach { drawable ->
                    addJsonObject { put("kind", "mesh"); put("id", drawable.id.raw) }
                } }
            })
            f.edit("modifier_add_group", buildJsonObject {
                put("group", plan.getValue("group")); put("entries", plan.getValue("entries"))
            })
        }
        val legacy = fixture()
        add(legacy, "mouth.outline")
        val previous = RigModifierStack.fromJson(legacy.now.document.rigEdits.modifierStack!!).instances.last()
        assertEquals(1, previous.version)
        val source = fixture()
        val beforeOutline = source.now
        add(source, MouthOutlineV2Algorithm.ENTRYPOINT)
        val afterOutline = source.now
        val selected = RigModifierStack.fromJson(source.now.document.rigEdits.modifierStack!!).instances.last()
        assertEquals("mouth.outline", selected.type)
        assertEquals(2, selected.version)
        assertEquals(BuiltinModifierPlugins.reference("mouth.outline", 2), selected.plugin)
        val compiled = RigIrCompiler.compile(source.now.model, tileArt = true)
        assertEquals(compiled, RigIrCompiler.compile(WorkspacePreviewBuilder().build(source.now.document), tileArt = true))

        val mouth = source.now.model.rig.layerIdByDrawableId.entries.single { it.value == "mouth" }.key
        fun points(model: PuppetModel, id: String, pose: Map<ParameterId, Float>): FloatArray {
            val drawable = DrawableId(id)
            val local = requireNotNull(org.umamo.render.eval.drawableLocalPosed(model, pose, drawable))
            return requireNotNull(org.umamo.render.eval.drawableSpaceMapping(model, pose, drawable)).localToWorld(local)
        }
        val old = legacy.now.model.rig.puppet
        val new = source.now.model.rig.puppet
        val ribbons = (0..1).map { side -> previous.outputId("mouth-lip:$mouth:$side") to selected.outputId("mouth-lip:$mouth:$side") }
        val pairs = listOf(mouth to mouth) + ribbons
        for ((a, b) in pairs) {
            val left = old.drawables.single { it.id.raw == a }
            val right = new.drawables.single { it.id.raw == b }
            assertContentEquals(left.mesh!!.indices, right.mesh!!.indices)
            assertEquals(left.maskedBy, right.maskedBy)
        }
        assertEquals(ribbons.map { it.second }.toSet(), new.deformPaths.map { it.drawableId.raw }.toSet())
        assertEquals(2, source.now.model.rig.modifierRasterAssets.size)
        for ((left, right) in legacy.now.model.rig.modifierRasterAssets.zip(source.now.model.rig.modifierRasterAssets)) {
            assertEquals(left.width, right.width)
            assertEquals(left.height, right.height)
            assertEquals(left.rgba, right.rgba)
        }
        for (form in listOf(-1f, -.5f, 0f, .5f, 1f)) for (open in listOf(0f, .25f, .5f, .75f, 1f)) {
            val pose = mapOf(StandardParameters.MOUTH_FORM to form, StandardParameters.MOUTH_OPEN to open)
            for ((a, b) in pairs) {
                val left = points(old, a, pose)
                val right = points(new, b, pose)
                assertEquals(left.size, right.size, "$a at $pose")
                val maximum = left.indices.maxOf { abs(left[it] - right[it]) }
                assertTrue(maximum < .001f, "$a at $pose differs by $maximum pixels")
            }
        }

        val evaluation = requireNotNull(source.now.model.modifierEvaluation)
        val prefix = RigModifierStack.fromJson(source.now.document.rigEdits.modifierStack!!).instances.dropLast(1)
        val upstream = prefix.map { ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin) }
        val directory = Files.createTempDirectory("mouth-outline-v2-")
        val preferences = Preferences.userRoot().node("mouth-outline-v2/${UUID.randomUUID()}")
        val store = ModifierPluginStore(directory.resolve("installed"), preferences)
        try {
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find("mouth.outline", 2))
            val staging = Files.createDirectory(directory.resolve("package"))
            Files.write(staging.resolve("plugin.json"), artifact.bytes)
            artifact.manifest.files.keys.forEach { name ->
                assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
            }
            val entry = store.install(staging.toString())
            val author = RigModifierSource.of(source.now.model)
            val worker = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(author), author::resolveRasters)
                .single { it.type == MouthOutlineV2Algorithm.ENTRYPOINT }
            val installed = selected.copy(type = worker.type, plugin = entry.reference)
            val context = worker.authorContext(installed).withUpstream(upstream)
            val before = evaluation.outputs[evaluation.outputs.lastIndex - 1]
            val plugin = worker.plugin as ContextualModifierAlgorithmPlugin
            assertEquals(evaluation.outputs.last(), plugin.evaluate(before, installed, context, {}))
            assertFailsWith<IllegalArgumentException> {
                MouthOutlineV2Algorithm().changes(before, selected, context.withUpstream(upstream.dropLast(1))) {}
            }
            val historyStore = WorkspaceStore(directory.resolve("project"))
            historyStore.persistHistory(afterOutline.projectId, source.runtime.history())
            val archive = directory.resolve("outline.psd2live")
            val repository = ProjectRepository(writeHeadCache = false)
            repository.save(ProjectSaveCapture(afterOutline.projectId, source.runtime.history(), JsonObject(emptyMap()), null, historyStore), archive)
            repository.open(archive).use { opened ->
                val restored = opened.history.head().snapshot
                assertEquals(selected, RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.last())
                assertEquals(compiled, RigIrCompiler.compile(source.builder.build(restored), tileArt = true))
            }
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(directory)
        }
        val undone = source.runtime.checkout(afterOutline.projectId, source.now.state, beforeOutline.historyHead)
        assertEquals(RigIrCompiler.compile(beforeOutline.model), RigIrCompiler.compile(undone.model))
        val redone = source.runtime.checkout(afterOutline.projectId, undone.state, afterOutline.historyHead)
        assertEquals(compiled, RigIrCompiler.compile(redone.model, tileArt = true))
        source.edit("modifier_apply", buildJsonObject { put("id", selected.id) })
        val rebuilt = RigIrCompiler.compile(source.builder.build(source.now.document), tileArt = true)
        assertEquals(compiled, rebuilt)
        val files = mutableMapOf<String, ByteArray>()
        io.github.psd2live.format.compile.Compiler.export(io.github.psd2live.targets.cubism.Cmo3Target(), rebuilt,
            io.github.psd2live.format.compile.ExportOptions("outline")) { path, bytes -> files[path] = bytes }
        val imported = Cmo3ModelImport.read(files.getValue("outline.cmo3")).puppet
        assertTrue(ribbons.all { (_, id) -> imported.drawables.any { it.id.raw == id && it.mesh != null } })
    }

    @Test fun fittedEyeV5PlanAddsItsActualBodyAndFaceProviders(): Unit = runBlocking {
        val f = Fixture(document(eyeDetails = true, pairedBrows = true, bilateralEyes = true))
        f.install()
        val before = f.now
        val entrypoint = assertNotNull(BundledAlgorithmArtifacts.find("eye/fitted", 5)).manifest.algorithms.single().type
        val plan = WorkspaceModifierPlan.prepare(before, buildJsonObject {
            put("type", entrypoint)
            put("targets", f.addition("eye/fitted", "eye").getValue("targets"))
        })
        val entries = plan.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(listOf("rig.scaffold", "body/legacy", "face/fitted", "eye/fitted"),
            entries.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(5, entries.last().getValue("version").jsonPrimitive.int)
        assertEquals(before.historyHead, f.now.historyHead)
        f.edit("modifier_add_group", buildJsonObject {
            put("group", plan.getValue("group")); put("entries", plan.getValue("entries"))
        })
        val stack = RigModifierStack.fromJson(requireNotNull(f.now.document.rigEdits.modifierStack))
        assertEquals(BuiltinModifierPlugins.reference("eye/fitted", 5), stack.instances.last().plugin)
        assertEquals("active", assertNotNull(f.now.model.rig.modifierReport).getValue("entries").jsonArray.last()
            .jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test fun depthTurnPlanAddsTheLegacyFaceAndRejectsFittedFace(): Unit = runBlocking {
        val fixture = Fixture(document())
        fixture.install()
        val type = DepthTurnV2Algorithm.ENTRYPOINT
        assertTrue(type in WorkspaceModifierPlan.types)
        val request = buildJsonObject {
            put("type", type)
            put("targets", fixture.addition(type, "depth").getValue("targets"))
        }
        val plan = WorkspaceModifierPlan.prepare(fixture.now, request)
        val entries = plan.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(listOf("rig.scaffold", "face/legacy", DepthTurnV2Algorithm.TYPE),
            entries.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(2, entries.last().getValue("version").jsonPrimitive.int)
        fixture.edit("modifier_add_group", buildJsonObject {
            put("group", plan.getValue("group")); put("entries", plan.getValue("entries"))
        })
        assertEquals(ModifierStatus.ACTIVE, fixture.now.model.modifierEvaluation!!.entries.last().status)
        val fitted = Fixture(document())
        fitted.install()
        fitted.group(listOf("face/fitted"))
        assertFailsWith<IllegalArgumentException> { WorkspaceModifierPlan.prepare(fitted.now, request) }
    }

    @Test fun independentDepthTurnMatchesLockedStackAndItsInstalledWorker(): Unit = runBlocking {
        val cases = listOf(Triple(null, 1, 2f), Triple(1, 1, 0f), Triple(2, 1, 2f), Triple(2, 2, 2f))
        for ((body, faceVersion, style) in cases) {
            val art = document(sideLock = true)
            val f = Fixture(art)
            f.install()
            f.edit("modifier_add", f.addition("rig.scaffold", "structure"))
            body?.let { f.edit("modifier_add", JsonObject(f.addition("body/legacy", "body") + ("version" to JsonPrimitive(it)))) }
            val face = f.addition("face/legacy", "face")
            f.edit("modifier_add", JsonObject(face + ("version" to JsonPrimitive(faceVersion))))
            val before = f.now
            val config = buildJsonObject {
                put("scaffold", "structure")
                putJsonObject("dependencies") { put("face/legacy", "face") }
                putJsonObject("values") { put("style", style); put("turn", 64f); put("depth", 125f) }
            }
            val stack = RigModifierStack.fromJson(before.document.rigEdits.modifierStack!!)
            val targets = stack.instances.first().targets
            val locked = stack.add(DepthTurnV2Algorithm.TYPE, 1, targets, config, id = "depth")
            val expected = requireNotNull(f.builder.build(before.document.copy(
                rigEdits = before.document.rigEdits.copy(modifierStack = locked.toJson()))).rig.modifierOutput)
            val request = f.addition(DepthTurnV2Algorithm.TYPE, "depth", targets, config)
            f.edit("modifier_add", JsonObject(request + ("version" to JsonPrimitive(2))))
            val current = f.now
            val actual = requireNotNull(current.model.rig.modifierOutput)
            val selected = RigModifierStack.fromJson(current.document.rigEdits.modifierStack!!).instances.last()
            val artifact = assertNotNull(BundledAlgorithmArtifacts.find(DepthTurnV2Algorithm.TYPE, 2))
            assertEquals(artifact.reference, selected.plugin)
            val differences = buildList {
                if (actual.parameters != expected.parameters) add("parameters")
                val meshes = expected.meshes.associateBy { it.id }
                addAll(actual.meshes.filter { meshes[it.id] != it }.map { "mesh:${it.name}" })
                val deformers = expected.deformers.associateBy { it.id }
                addAll(actual.deformers.filter { deformers[it.id] != it }.map { "deformer:${it.name}" })
                if (actual.meshes.map { it.id } != expected.meshes.map { it.id }) add("mesh order")
                if (actual.deformers.map { it.id } != expected.deformers.map { it.id }) add("deformer order")
            }
            assertTrue(differences.isEmpty(), "body=$body face=$faceVersion style=$style: $differences")
            assertEquals(expected, actual)
            if (body != 2 || faceVersion != 2) continue
            val directory = Files.createTempDirectory("depth-turn-v2-")
            val preferences = Preferences.userRoot().node("depth-turn-v2/${UUID.randomUUID()}")
            val store = ModifierPluginStore(directory.resolve("installed"), preferences)
            try {
                val staging = Files.createDirectory(directory.resolve("package"))
                Files.write(staging.resolve("plugin.json"), artifact.bytes)
                artifact.manifest.files.keys.forEach { name ->
                    assertNotNull(javaClass.getResourceAsStream(artifact.resource + name)).use { Files.copy(it, staging.resolve(name)) }
                }
                val installed = store.install(staging.toString())
                val worker = ModifierPluginCatalog.definitions(store, BuiltinModifierPlugins.definitions(RigModifierSource.of(current.model)))
                    .single { it.type == DepthTurnV2Algorithm.ENTRYPOINT }
                val instance = selected.copy(type = worker.type, plugin = installed.reference)
                val context = worker.authorContext(instance).withUpstream(stack.instances.map {
                    ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
                })
                val plugin = worker.plugin as ContextualModifierAlgorithmPlugin
                val prior = requireNotNull(before.model.rig.modifierOutput)
                assertEquals(actual, plugin.evaluate(prior, instance, context, {}))
                val head = prior.meshes.single { it.name == "face" }
                val tinted = head.copy(opacity = .6f, multiply = Rgb(.8f, .9f, 1f))
                val authored = prior.copy(meshes = prior.meshes.map { if (it.id == head.id) tinted else it })
                val result = plugin.evaluate(authored, instance, context, {}).meshes.single { it.id == head.id }
                assertEquals(tinted.opacity, result.opacity)
                assertEquals(tinted.multiply, result.multiply)
                val keys = FloatArray(29) { (it - 14) / 14f }
                val opacity = KeyGrid<ChannelValue>(listOf(KeyAxis(StandardParameters.BODY_X.raw, Floats.of(keys))),
                    keys.mapIndexed { i, key -> KeyCell(Ints.values(i), ChannelValue.Scalar(.6f + key * .1f)) })
                val keyed = tinted.copy(channels = tinted.channels + (Channel.OPACITY to opacity))
                val dense = authored.copy(meshes = authored.meshes.map { if (it.id == head.id) keyed else it })
                val outputName = "shared/deformer:${head.id}_Turn"
                for (invocation in listOf(instance, instance.copy(outputAliases = mapOf(outputName to "pinned-depth-carrier")))) {
                    val lowered = plugin.evaluate(dense, invocation, context, {})
                    val mesh = lowered.meshes.single { it.id == head.id }
                    assertEquals(opacity, mesh.channels[Channel.OPACITY])
                    assertEquals(invocation.outputId(outputName), mesh.parent)
                    assertEquals(head.parent, lowered.deformers.single { it.id == mesh.parent }.parent)
                }
                val geometry = requireNotNull(head.geometry)
                val edited = head.copy(geometry = geometry.copy(positions = Floats.of(
                    geometry.positions.toArray().also { it[0] += .01f })))
                val rejection = assertFailsWith<IllegalStateException> {
                    plugin.evaluate(prior.copy(meshes = prior.meshes.map { if (it.id == head.id) edited else it }),
                        instance, context, {})
                }
                assertTrue(rejection.message.orEmpty().contains("unedited source geometry"))
                store.uninstall(installed.reference)
                assertTrue(store.snapshot().none { it.reference == installed.reference })
                assertEquals(installed.reference, store.install(staging.toString()).reference)
                assertEquals(actual, plugin.evaluate(prior, instance, context, {}))
            } finally {
                store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
                preferences.removeNode()
                ModifierPluginStore.deleteTree(directory)
            }
            val compiled = RigIrCompiler.compile(current.model, tileArt = true)
            f.edit("modifier_enable", buildJsonObject { put("id", "face"); put("enabled", false) })
            assertEquals(ModifierStatus.MISSING_DEPENDENCY, f.now.model.modifierEvaluation!!.entries.last().status)
            f.edit("modifier_enable", buildJsonObject { put("id", "face"); put("enabled", true) })
            f.edit("modifier_apply", buildJsonObject { put("id", "face") })
            assertEquals(compiled, RigIrCompiler.compile(f.builder.build(f.now.document), tileArt = true))
            f.edit("modifier_apply", buildJsonObject { put("id", selected.id) })
            assertEquals(compiled, RigIrCompiler.compile(f.builder.build(f.now.document), tileArt = true))
        }
    }

    @Test fun depthTurnMatchesNativeAcrossHeadAndBodyPoses(): Unit = runBlocking<Unit> {
        val f = Fixture(document(sideLock = true)); f.install()
        f.group(listOf("body/legacy", "face/fitted", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy"))
        val values = mapOf("turn" to 60f, "depth" to 125f)
        f.edit("modifier_update", buildJsonObject {
            put("id", "face"); put("type", "face/fitted")
            putJsonObject("config") {
                put("scaffold", "structure")
                put("values", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
            }
        })
        val base = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
        val recipe = base.copy(modules = base.modules + ("face.depth-turn" to ModuleChoice(true, values = values)))
        compare(native(f.document, recipe), f.now.model, listOf(emptyMap(),
            mapOf(StandardParameters.ANGLE_X to -45f),
            mapOf(StandardParameters.ANGLE_X to 45f, StandardParameters.ANGLE_Y to -14f,
                StandardParameters.ANGLE_Z to 12f, StandardParameters.BODY_X to 5f)))
        assertTrue(FormChannel.DRAW_ORDER in mesh(f, "front").channelGrids.gridsByChannel)
        fun fittedMouthOutput(model: RigPreviewModel): io.github.psd2live.format.model.RigIR {
            val evaluated = requireNotNull(model.modifierEvaluation)
            val index = evaluated.entries.indexOfFirst { it.instance.type == "mouth/fitted" }
            return evaluated.outputs[index]
        }
        val warmMouth = fittedMouthOutput(f.now.model)
        val cacheEnabled = RigStageCache.enabled
        val coldMouth = try {
            RigStageCache.enabled = false
            fittedMouthOutput(PSD2LivePipeline().buildPreview(f.now.document.source, f.now.document.config()))
        } finally {
            RigStageCache.enabled = cacheEnabled
        }
        assertEquals(warmMouth.deformers, coldMouth.deformers)
        assertEquals(warmMouth.meshes, coldMouth.meshes)
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "hair") })
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun fittedMouthHeadLatticesCanBeReplayedFromSourceMeasurements() {
        val art = document(eyeDetails = true, mouthDetails = true, pairedBrows = true, bilateralEyes = true)
        val config = art.config().copy(featureDisplacementEnabled = true)
        val document = art.copy(settings = WorkspaceSettingsCodec.encode(config))
        val turn = ModuleChoice(true, values = mapOf("turn" to 60f, "depth" to 125f))
        val neutralMouth = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted")))
        val beforeRecipe = neutralMouth.copy(modules = neutralMouth.modules + ("face.depth-turn" to turn),
            moves = mapOf("mouth" to mapOf("upper" to FeatureMove(1f, -2f))))
        val afterRecipe = beforeRecipe.copy(bases = beforeRecipe.bases + ("mouth" to BaseChoice("mouth/fitted")))
        val cache = PreviewMeshCache()
        fun verify(recipe: RigRecipe): List<io.github.psd2live.format.model.Deformer> {
            val reference = native(document, recipe)
            val config = reference.config
            val measured = RigBuilder.rigContext(reference.analysis, config, cache)
            val parentFrames = measured.deformers.mapNotNull { deformer ->
                deformer.parent?.let { id -> measured.resolveFrame(id, config)?.let { id to it } }
            }.toMap()
            val captured = RigDepthTurnSource.capture(measured, config, emptyMap(), emptyMap(), parentFrames).encode()
            val source = DepthTurnInputs.decode(captured)
            if (recipe.base("mouth") == RigModules.MOUTH_FITTED) assertNotNull(source.mouth)
            val field = FaceTurn(source.landmarks, source.style)
            assertTrue(field.usable)
            val projected = LegacyBodyMotion.withFaceLean(DepthTurnMotion.deformers(
                measured.deformers, field, source.head, source.face, source.container, source.frames,
                measured.underFace, source.fittedEyes, source.fittedBrows, source.landmarks,
            ), source.leanDown, source.leanUp).map(PuppetIr::deformerToIr)
            val expected = reference.rig.puppet.deformers.associateBy { it.id.raw }
            assertTrue(projected.any { it.id == "DeformFaceNinePose" })
            for (deformer in projected) {
                assertEquals(PuppetIr.deformerToIr(expected.getValue(deformer.id)), deformer, deformer.id)
            }
            return projected
        }
        val before = verify(beforeRecipe).associateBy { it.id }
        val after = verify(afterRecipe).associateBy { it.id }
        assertEquals(before.keys, after.keys)
        assertTrue(before.any { (id, frame) -> frame != after.getValue(id) }, "The fitted mouth must change the head projection")
    }

    @Test fun naturalSpeechIncludesVowelsWhenTheSourceHasAMouth(): Unit = runBlocking<Unit> {
        val f = Fixture(document()); f.install()
        val plan = WorkspaceModifierPlan.prepare(f.now, buildJsonObject {
            put("type", "motion"); put("preset", "natural:Talk")
            put("targets", JsonArray(f.now.model.rig.puppet.drawables.map { mesh ->
                buildJsonObject { put("kind", "mesh"); put("id", mesh.id.raw) }
            }))
        })
        assertTrue(plan.getValue("entries").jsonArray.any { it.jsonObject.getValue("type").jsonPrimitive.content == "mouth.vowel" })
        f.edit("modifier_add_group", buildJsonObject { put("group", plan.getValue("group")); put("entries", plan.getValue("entries")) })
        val rig = RigIrCompiler.compile(f.now.model)
        assertEquals(MotionGenerator.talkTracks.map { it.parameterId }.toSet(), rig.clips.single().curves.map { it.parameter }.toSet())
    }

    @Test fun scaffoldFeatureDisplacementPersistsWhenAppliedBeforeFaceBinding(): Unit = runBlocking<Unit> {
        val f = Fixture(document())
        f.install()
        val targets = f.now.model.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val settings = buildJsonObject {
            put("featureDisplacement", true)
            putJsonObject("corrections") {
                putJsonObject("moves") { putJsonObject("mouth") { putJsonArray("upper") { add(1f); add(-1f) } } }
            }
        }
        f.edit("modifier_add", f.addition("rig.scaffold", "structure", targets, settings))
        val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
        val structure = stack.instances.single()
        assertTrue(f.now.model.rig.puppet.deformers.any {
            structure.localOutputName(it.id.raw)?.endsWith("/deformer:DeformFeatureDisplacement") == true
        })
        f.edit("modifier_add", f.addition("face/legacy", "face", targets))
        f.edit("modifier_add", f.addition("mouth/fitted", "mouth", targets))
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_update", buildJsonObject {
            put("id", "structure"); put("type", "rig.scaffold"); put("config", JsonObject(settings - "corrections"))
        })
        assertNotEquals(expected.meshes, RigIrCompiler.compile(f.now.model).meshes)
        f.edit("modifier_update", buildJsonObject {
            put("id", "structure"); put("type", "rig.scaffold"); put("config", settings)
        })
        f.edit("modifier_apply", buildJsonObject { put("id", "structure") })
        val applied = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).appliedCapabilities.single()
        assertEquals(settings, applied.config)
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun conversionOfOrdinaryMeshesIsAtomicAndKeepsTheirGeometry(): Unit = runBlocking<Unit> {
        val art = document()
        val config = art.config().copy(meshOnly = true, generateDeformers = false, rigEdits = RigEditOverlay.Empty)
        val f = Fixture(art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits))
        f.install()
        val before = f.now
        val operation = WorkspaceDocumentOperation("modifier_convert", JsonObject(emptyMap()))
        assertFailsWith<java.util.concurrent.CancellationException> {
            f.commands.execute(before.projectId, before.state, "Cancel conversion", listOf(operation), MutationAuthor.USER,
                beforeCommit = { _, _, _ -> throw java.util.concurrent.CancellationException("Cancel") })
        }
        assertEquals(before.state, f.now.state)
        f.edit(operation.operation, operation.request)
        val after = f.now
        assertEquals(RigModifierStack(), RigModifierStack.fromJson(after.document.rigEdits.modifierStack!!))
        assertEquals(RigIrCompiler.compile(before.model).meshes, RigIrCompiler.compile(after.model).meshes)
        assertEquals(RigIrCompiler.compile(after.model), RigIrCompiler.compile(f.builder.build(after.document)))
        assertFailsWith<WorkspaceConflict> {
            f.commands.execute(before.projectId, before.state, "Stale conversion", listOf(operation), MutationAuthor.USER)
        }
        assertEquals(after.state, f.now.state)
        val undone = f.runtime.checkout(after.projectId, after.state, before.historyHead)
        assertNull(undone.document.rigEdits.modifierStack)
        val redone = f.runtime.checkout(undone.projectId, undone.state, after.historyHead)
        assertEquals(after.document, redone.document)
    }

    @Test fun conversionPreservesAnAuthoredSwingAndItsStableOutputIds(): Unit = runBlocking<Unit> {
        val art = document()
        val config = art.config().copy(meshOnly = false, generateDeformers = true, generatePhysics = true, rigEdits = RigEditOverlay.Empty)
        val f = Fixture(art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits))
        f.install()
        val mesh = f.now.model.rig.puppet.deformers.filterIsInstance<Deformer.Warp>().first().id.raw
        val swing = RigSwingEdit.single("authored-swing", "Authored swing", SwingKind.LATERAL, listOf(mesh), listOf("AuthoredSway"))
        f.edit("swing_put", swing.toJson())
        fun output(model: RigPreviewModel) = RigIrCompiler.compile(model).let { rig ->
            val parameters = rig.parameters.mapTo(HashSet()) { it.id }
            rig.copy(parameters = rig.parameters.sortedBy { it.id }, restPose = rig.restPose.filterKeys { it in parameters },
                parameterRoles = rig.parameterRoles.filter { role -> role.parameters.all { it in parameters } })
        }
        val expected = output(f.now.model)
        val before = f.now
        f.edit("modifier_convert", JsonObject(emptyMap()))
        val converted = f.now
        val entry = RigModifierStack.fromJson(converted.document.rigEdits.modifierStack!!).instances.single { it.type == "swing" }
        assertEquals("authored-swing", entry.outputId("swing"))
        assertEquals("AuthoredSway", entry.outputId("swing:lateral:0"))
        assertEquals(expected, output(converted.model))
        assertEquals(expected, output(f.builder.build(converted.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", entry.id) })
        assertEquals(expected, output(f.now.model))
        f.runtime.checkout(f.now.projectId, f.now.state, before.historyHead)
        assertNull(f.now.document.rigEdits.modifierStack)
        assertEquals(expected, output(f.now.model))
    }

    @Test fun conversionKeepsADisabledLegacySimulationAsADormantInstance(): Unit = runBlocking<Unit> {
        val art = document()
        val config = art.config().copy(meshOnly = true, generateDeformers = false, rigEdits = RigEditOverlay.Empty)
        val plain = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val mesh = WorkspacePreviewBuilder().build(plain).rig.puppet.drawables.first().id.raw
        val simulation = io.github.psd2live.core.sim.RigSimEdit("old-hair", "Old hair", io.github.psd2live.core.sim.SimKind.HAIR,
            listOf(mesh), enabled = false, modes = 3)
        val f = Fixture(plain.copy(rigEdits = config.rigEdits.copy(simEdits = listOf(simulation))))
        f.install()
        val before = f.now
        f.edit("modifier_convert", JsonObject(emptyMap()))
        assertTrue(f.now.document.rigEdits.simEdits.isEmpty())
        val entry = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single()
        assertFalse(entry.enabled)
        assertEquals(simulation.copy(enabled = true), io.github.psd2live.core.sim.RigSimEdit.fromJson(entry.config))
        assertEquals(RigIrCompiler.compile(before.model).meshes, RigIrCompiler.compile(f.now.model).meshes)

        val pending = Fixture(plain.copy(rigEdits = config.rigEdits.copy(simEdits = listOf(simulation.copy(enabled = true)))))
        pending.install()
        val old = pending.now
        val failure = assertFails { pending.edit("modifier_convert", JsonObject(emptyMap())) }
        assertContains(failure.message.orEmpty(), "cannot preserve the existing simulation bake")
        assertEquals(old.state, pending.now.state)
        assertEquals(old.document.rigEdits, pending.now.document.rigEdits)
    }

    @Test fun legacyBindingConversionPreservesCompleteOutputAndHistory(): Unit = runBlocking<Unit> {
        val art = document()
        val config = art.config().copy(meshOnly = false, generateDeformers = true, generatePhysics = true,
            physicsFrontHair = true, physicsBackHair = true, physicsEyeJelly = true, exportMotions = true,
            motionBasic = true, motionIdle = true, motionBlink = true, rigEdits = RigEditOverlay.Empty)
        val f = Fixture(art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits))
        f.install()
        val before = f.now
        val captured = WorkspaceModifierConversion.bindings(before.model).instances.filter { it.type == "physics" }
            .map { RigPhysicsEdit.fromJson(it.config) }
        assertTrue(captured.isNotEmpty())
        assertEquals(PhysicsCatalog.active(before.model).filter { it.id in PhysicsGenerator.presetIds }, captured)
        val motions = WorkspaceModifierConversion.bindings(before.model).instances.filter { it.type == "motion" }
            .map { MotionClips.fromJson(it.config) }
        assertTrue(motions.isNotEmpty())
        val oldClips = RigIrCompiler.compile(before.model).clips
        val convertedInstances = WorkspaceModifierConversion.bindings(before.model).instances
        assertEquals(captured.map { it.id }, convertedInstances.filter { it.type == "physics" }.map { it.outputId("physics") })
        assertEquals(oldClips.map { it.id }, convertedInstances.filter { it.type == "motion" }.map { it.outputId("motion") })
        assertEquals(oldClips.size, motions.size)
        for ((old, next) in oldClips.zip(motions)) {
            assertEquals(old.curves, next.curves.map(MotionGenerator::curve))
            assertEquals(old.file, next.fileStem)
            assertEquals(old.group, next.exportGroup)
            assertEquals(old.fadeIn == null && old.fadeOut == null, next.runtimeDefaultFades)
        }
        fun normalized(model: RigPreviewModel) = RigIrCompiler.compile(model, tileArt = true, simulations = true).let {
            it.copy(parameters = it.parameters.sortedBy { parameter -> parameter.id })
        }
        val expected = normalized(before.model)
        f.edit("modifier_convert", JsonObject(emptyMap()))
        val converted = f.now
        assertNotNull(converted.document.rigEdits.modifierStack)
        assertEquals(expected, normalized(converted.model))
        assertEquals(expected, normalized(f.builder.build(converted.document)))
        f.runtime.checkout(converted.projectId, converted.state, before.historyHead)
        assertNull(f.now.document.rigEdits.modifierStack)
        assertEquals(before.revision, f.now.revision)
        f.runtime.checkout(f.now.projectId, f.now.state, converted.historyHead)
        assertEquals(expected, normalized(f.now.model))
        f.edit("modifier_apply", buildJsonObject { put("id", convertedInstances.last().id) })
        assertTrue(RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.isEmpty())
        assertEquals(expected, normalized(f.builder.build(f.now.document)))
    }

    @Test fun fittedBindingConversionPreservesItsBasesAndAdditionalModules(): Unit = runBlocking<Unit> {
        val art = document()
        val base = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
        for (modules in listOf(emptyList(), listOf("eye.smile", "eye.squash"))) {
            val config = art.config().copy(meshOnly = false, generateDeformers = true, generatePhysics = false, exportMotions = false,
                recipe = base.copy(modules = base.modules + modules.associateWith { ModuleChoice(true) }), rigEdits = RigEditOverlay.Empty)
            val f = Fixture(art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits))
            f.install()
            fun normalized(model: RigPreviewModel) = RigIrCompiler.compile(model).let {
                it.copy(parameters = it.parameters.sortedBy { parameter -> parameter.id },
                    restPose = it.restPose.filterKeys { id -> it.parameters.any { p -> p.id == id } },
                    parameterRoles = it.parameterRoles.filter { role -> role.parameters.all { id -> it.parameters.any { p -> p.id == id } } })
            }
            val expected = normalized(f.now.model)
            try { f.edit("modifier_convert", JsonObject(emptyMap())) }
            catch (failure: Exception) { throw AssertionError("$modules: ${WorkspaceFailure.from(failure).toJson()}", failure) }
            val stack = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!)
            assertTrue(stack.instances.any { it.type == "eye/fitted" && it.version == 2 })
            assertTrue(modules.all { type -> stack.instances.any { it.type == type } })
            assertEquals(expected, normalized(f.now.model))
            assertEquals(expected, normalized(f.builder.build(f.now.document)))
            f.edit("modifier_apply", buildJsonObject { put("id", stack.instances.last().id) })
            assertEquals(expected, normalized(f.builder.build(f.now.document)))
        }
    }

    @Test fun modifierEditsReuseMeshStagesAndMatchColdEvaluation(): Unit = runBlocking<Unit> {
        val art = document()
        val pipeline = PSD2LivePipeline()
        val base = pipeline.buildPreview(art.source, art.config())
        val targets = base.rig.puppet.drawables.filter { it.mesh != null }.map { ModifierTarget("mesh", it.id.raw) }
        val stack = WorkspaceModifierPlan.plan(base, RigModifierStack(), buildJsonObject {
            put("group", "modern-minimal")
            put("targets", JsonArray(targets.map { buildJsonObject { put("kind", it.kind); put("id", it.id) } }))
        }, listOf("body/legacy", "face/fitted", "eye/fitted")).first
        val config = art.config().copy(rigEdits = art.rigEdits.copy(modifierStack = stack.toJson()))
        val start = System.nanoTime()
        pipeline.buildPreview(art.source, config)
        val coldMs = (System.nanoTime() - start) / 1_000_000
        val authorInput = pipeline.modifierBase
        val eye = stack.instances.single { it.type == "eye/fitted" }
        val changed = stack.update(eye.id) { it.copy(config = JsonObject(it.config + ("values" to buildJsonObject { put("roundness", 80f) }))) }
        val updatedConfig = config.copy(rigEdits = config.rigEdits.copy(modifierStack = changed.toJson()))
        val editStart = System.nanoTime()
        val warm = pipeline.buildPreview(art.source, updatedConfig)
        val warmMs = (System.nanoTime() - editStart) / 1_000_000
        assertSame(requireNotNull(authorInput).model, requireNotNull(pipeline.modifierBase).model)
        val fresh = PSD2LivePipeline().buildPreview(art.source, updatedConfig)
        assertEquals(RigIrCompiler.compile(fresh), RigIrCompiler.compile(warm))
        println("modifier stages: initial=${coldMs}ms edit=${warmMs}ms author input reused")
    }

    @Test fun simulationPanelEditsStayOwnedByTheirPresetInstance(): Unit = runBlocking<Unit> {
        val f = Fixture(document())
        f.install()
        val mesh = f.now.model.rig.puppet.drawables.first { it.mesh != null }
        val count = mesh.mesh!!.vertexCount
        val body = io.github.psd2live.core.sim.RigSimEdit("preset-body", "Clothes", io.github.psd2live.core.sim.SimKind.CLOTH, listOf(mesh.id.raw))
        val axis = io.github.psd2live.core.sim.SimBakedAxis("ClothMode", floatArrayOf(-1f, 0f, 1f), mapOf(mesh.id.raw to listOf(
            FloatArray(count * 2) { -1f }, FloatArray(count * 2), FloatArray(count * 2) { 1f })))
        val baked = body.copy(bake = io.github.psd2live.core.sim.SimBakeResult(
            io.github.psd2live.core.sim.SimBake.fingerprint(f.now.model.rig.puppet, body), mapOf(mesh.id.raw to count), emptyList(),
            listOf(io.github.psd2live.core.sim.SimBakedMode(axis, 1f, 1f))))
        val weights = JsonArray(emptyList())
        f.edit("modifier_add", f.addition("simulation", "clothes", listOf(ModifierTarget("mesh", mesh.id.raw)),
            JsonObject(baked.toJson() + ("automatic_weights" to weights))))
        val shown = io.github.psd2live.core.sim.SimulationCatalog.entries(f.now.document.rigEdits).single()
        assertEquals("clothes", shown.id)
        assertNotNull(shown.bake)
        assertTrue(f.now.model.rig.puppet.parameters.any { it.id.raw == shown.outputId("ClothMode") })
        f.edit("simulation_put", shown.copy(name = "Renamed clothes", enabled = false).toJson())
        val renamed = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single()
        assertEquals("Renamed clothes", io.github.psd2live.core.sim.RigSimEdit.fromJson(renamed.config).name)
        assertEquals(body.id, io.github.psd2live.core.sim.RigSimEdit.fromJson(renamed.config).id)
        assertEquals(weights, renamed.config["automatic_weights"])
        assertFalse(renamed.enabled)
        assertTrue(f.now.document.rigEdits.simEdits.isEmpty())
        f.edit("simulation_put", io.github.psd2live.core.sim.SimulationCatalog.entries(f.now.document.rigEdits).single().copy(enabled = true).toJson())
        assertTrue(f.now.model.rig.puppet.parameters.any { it.id.raw == shown.outputId("ClothMode") })
        f.edit("simulation_clear_bake", buildJsonObject { put("id", "clothes") })
        f.edit("simulation_delete", buildJsonObject { put("id", "clothes") })
        assertTrue(io.github.psd2live.core.sim.SimulationCatalog.entries(f.now.document.rigEdits).isEmpty())
    }

    @Test fun newPresetInitializationIncludesOutlineAndSeeThroughWithoutLegacyMigration(): Unit = runBlocking<Unit> {
        val art = document(eyeDetails = true, mouthDetails = true, expressionDetails = true,
            skinDetails = true, outfitDetails = true, sideLock = true, bangOverlay = true)
        val config = WorkspacePresetBaseline.configure(art.config(), "modern").copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val builder = WorkspacePreviewBuilder()
        val initialized = WorkspacePresetInitialization.create(source, WorkspaceRasterWork.Direct, WorkspaceInitialPreset(clothing = false))
        val stack = RigModifierStack.fromJson(initialized.rigEdits.modifierStack!!)
        assertTrue(stack.instances.all { it.outputAliases.isEmpty() })
        assertTrue(stack.instances.any { it.type == "mouth.outline" })
        assertTrue(stack.instances.any { it.type == "eye.see-through" })
        assertTrue(stack.instances.any { it.type == "brow.see-through" })
        val built = builder.build(initialized)
        val report = built.rig.modifierReport!!.getValue("entries").jsonArray
        assertTrue(report.all { it.jsonObject.getValue("status").jsonPrimitive.content == "active" })
        assertTrue(built.rig.modifierRasterAssets.isNotEmpty())
        val output = RigIrCompiler.compile(built, tileArt = true)
        assertEquals(output, RigIrCompiler.compile(WorkspacePreviewBuilder().build(initialized), tileArt = true))
    }

    @Test fun modernPresetConversionPreservesTheCompleteGeneratedModel(): Unit = runBlocking<Unit> {
        val art = document(eyeDetails = true, mouthDetails = true, expressionDetails = true, skinDetails = true,
            outfitDetails = true, sideLock = true, bangOverlay = true)
        val config = art.config().copy(meshOnly = false, generateDeformers = true, generatePhysics = true,
            recipe = RigRecipe.Modern, exportMotions = false, rigEdits = RigEditOverlay.Empty)
        val f = Fixture(art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits))
        f.install()
        val expected = RigIrCompiler.compile(f.now.model).let { it.copy(parameters = it.parameters.sortedBy { p -> p.id }) }
        try { f.edit("modifier_convert", JsonObject(emptyMap())) }
        catch (failure: Exception) { throw AssertionError(WorkspaceFailure.from(failure).toJson().toString(), failure) }
        fun normalized(model: RigPreviewModel) = RigIrCompiler.compile(model).let { it.copy(parameters = it.parameters.sortedBy { p -> p.id }) }
        assertEquals(expected, normalized(f.builder.build(f.now.document)))
        val converted = f.now
        val instances = RigModifierStack.fromJson(converted.document.rigEdits.modifierStack!!).instances
        f.edit("modifier_apply", buildJsonObject { put("id", instances.first().id) })
        assertEquals(expected, normalized(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", instances.last().id) })
        assertEquals(expected, normalized(f.builder.build(f.now.document)))
        assertTrue(RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.isEmpty())
        f.runtime.checkout(f.now.projectId, f.now.state, converted.historyHead)
        assertEquals(instances, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances)
        assertEquals(expected, normalized(f.now.model))
    }

    @Test fun applyingASharedScaffoldPreservesAliasesForRemainingBindings(): Unit = runBlocking<Unit> {
        val art = document()
        val config = art.config().copy(meshOnly = false, generateDeformers = true, generatePhysics = false,
            exportMotions = false, rigEdits = RigEditOverlay.Empty)
        val legacy = art.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val model = WorkspacePreviewBuilder().build(legacy)
        val stack = WorkspaceModifierConversion.bindings(model)
        val f = Fixture(legacy.copy(rigEdits = config.rigEdits.copy(modifierStack = stack.toJson())))
        f.install()
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", stack.instances.first().id) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model))
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        val applied = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).appliedCapabilities.first()
        assertEquals(stack.instances.first().outputAliases, applied.outputAliases)
        val remaining = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.first {
            it.config["scaffold"]?.jsonPrimitive?.content == applied.id
        }
        assertEquals(assertNotNull(applied.plugin), RigModifierSource.of(f.now.model).provider(remaining).plugin)
    }

    @Test fun anOwnedParameterRangeIsDormantWithItsProviderAndMaterializesOnApply(): Unit = runBlocking<Unit> {
        val f = Fixture(document())
        f.install()
        f.group(listOf("body/legacy"))
        val id = StandardParameters.BODY_X
        val original = f.now.model.rig.puppet.parameters.single { it.id == id }
        val max = original.max + 2f
        f.edit("parameter_update", buildJsonObject { put("parameter_id", id.raw); put("max", max) })
        assertEquals(max, f.now.model.rig.puppet.parameters.single { it.id == id }.max)
        assertTrue(f.now.model.authored.rig.puppet.parameters.none { it.id == id })
        val warp = f.now.model.rig.puppet.deformers.filterIsInstance<Deformer.Warp>().first {
            it.geometryGrid?.axes?.any { axis -> axis.parameterId == id } == true
        }
        fun keys() = f.now.model.rig.puppet.deformers.filterIsInstance<Deformer.Warp>().single { it.id == warp.id }
            .geometryGrid!!.axes.single { it.parameterId == id }.keys.toList()
        val oldKeys = keys()
        val value = (oldKeys[0] + oldKeys[1]) / 2f
        val moved = (oldKeys[0] + value) / 2f
        suspend fun key(action: String, at: Float, from: Float? = null, objectTrack: Boolean = true) {
            f.edit("keyform_apply", buildJsonObject {
                putJsonArray("changes") { addJsonObject {
                    put("op", "parameter_keys"); put("action", action)
                    if (objectTrack) {
                        put("target", "warp:${warp.id.raw}"); put("track", "geometry")
                    }
                    put("parameter", id.raw)
                    putJsonArray("values") { add(at) }
                    from?.let { put("from", it) }
                } }
            })
        }
        key("add", value)
        assertTrue(value in keys())
        key("move", moved, value)
        assertTrue(moved in keys() && value !in keys())
        key("delete", moved)
        assertEquals(oldKeys, keys())
        key("add", value)
        key("move", moved, value, objectTrack = false)
        assertTrue(moved in keys() && value !in keys())
        val stop = original.max + 1f
        key("add", stop, objectTrack = false)
        assertTrue(stop in f.now.model.rig.puppet.parameters.single { it.id == id }.keys.orEmpty())
        key("delete", stop, objectTrack = false)
        assertTrue(stop !in f.now.model.rig.puppet.parameters.single { it.id == id }.keys.orEmpty())
        assertTrue(f.now.model.authored.rig.puppet.parameters.none { it.id == id })
        f.edit("modifier_enable", buildJsonObject { put("id", "body"); put("enabled", false) })
        assertTrue(f.now.model.rig.puppet.parameters.none { it.id == id })
        f.edit("modifier_enable", buildJsonObject { put("id", "body"); put("enabled", true) })
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "body") })
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        assertEquals(max, f.now.model.authored.rig.puppet.parameters.single { it.id == id }.max)
    }

    @Test fun outlineMaterializesNativeLipMeshesAndTheirIndependentArt(): Unit = runBlocking<Unit> {
        val f = Fixture(document())
        f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy"))
        val targets = f.now.model.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        val original = mesh(f, "mouth")
        val withoutOutline = RigIrCompiler.compile(f.now.model, tileArt = true)
        f.edit("modifier_add", f.addition("mouth.outline", "outline", targets))
        val model = f.now.model
        val ribbons = model.rig.puppet.drawables.filter { it.id.raw.startsWith("modifier:outline:") }
        assertEquals(2, ribbons.size)
        assertTrue(ribbons.all { model.rig.pageByDrawableId[it.id.raw] != null })
        val recipe = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"), "mouth" to BaseChoice("mouth/fitted")))
        val legacy = PSD2LivePipeline().buildPreview(f.document.source, RigModifierPipeline.ordinaryConfig(f.document.config()).copy(
            meshOnly = false, generateDeformers = true, recipe = recipe, mouthOutlineEnabled = true))
        val owner = mesh(f, "mouth")
        val nativeOwner = legacy.rig.puppet.drawables.single { legacy.rig.layerIdByDrawableId[it.id.raw] == "mouth" }
        assertNotEquals(requireNotNull(original.mesh).indices.toList(), requireNotNull(owner.mesh).indices.toList())
        assertContentEquals(requireNotNull(nativeOwner.mesh).indices, requireNotNull(owner.mesh).indices)
        assertTrue(model.rig.puppet.deformPaths.none { it.drawableId == owner.id })
        val legacyRibbons = legacy.rig.puppet.drawables.filter { "_lip_" in it.id.raw }
        assertEquals(legacyRibbons.size, ribbons.size)
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(0f, .5f, 1f)) {
            val pose = mapOf(StandardParameters.MOUTH_OPEN to value, StandardParameters.ANGLE_X to 12f)
            val before = evaluator.evaluate(legacy.rig.puppet, pose)
            val after = evaluator.evaluate(model.rig.puppet, pose)
            for ((old, new) in legacyRibbons.zip(ribbons)) {
                val a = before.worldPositions.getValue(old.id)
                val b = after.worldPositions.getValue(new.id)
                assertEquals(a.size, b.size)
                assertTrue(a.indices.all { abs(a[it] - b[it]) < .03f }, "Ribbon differs at $pose")
            }
        }
        val expected = RigIrCompiler.compile(model, tileArt = true)
        f.edit("modifier_enable", buildJsonObject { put("id", "outline"); put("enabled", false) })
        assertEquals(withoutOutline, RigIrCompiler.compile(f.now.model, tileArt = true))
        f.edit("modifier_enable", buildJsonObject { put("id", "outline"); put("enabled", true) })
        assertEquals(expected, RigIrCompiler.compile(f.now.model, tileArt = true))

        val color = 0x7d2951
        f.edit("modifier_update", buildJsonObject {
            put("id", "outline"); put("type", "mouth.outline")
            putJsonObject("config") { put("scaffold", "structure"); put("color", color) }
        })
        assertTrue(f.now.model.rig.modifierRasterAssets.all { tile ->
            val bytes = tile.rgba.shared()
            (0 until bytes.size step 4).any {
                (bytes[it + 3].toInt() and 255) == 255 &&
                    (bytes[it].toInt() and 255) == (color shr 16) &&
                    (bytes[it + 1].toInt() and 255) == ((color shr 8) and 255) &&
                    (bytes[it + 2].toInt() and 255) == (color and 255)
            }
        })
        val ribbonId = ribbons.first().id
        f.edit("object_edit_appearance", buildJsonObject {
            putJsonArray("edits") {
                addJsonObject { put("action", "rename"); put("kind", "mesh"); put("id", ribbonId.raw); put("name", "Authored lip") }
                addJsonObject { put("action", "static"); put("kind", "mesh"); put("id", ribbonId.raw); put("opacity", .6f) }
                addJsonObject {
                    put("action", "bind"); put("kind", "mesh"); put("id", ribbonId.raw)
                    put("parent_id", JsonNull); put("space", "canvas")
                }
            }
        })
        f.edit("parameter_update", buildJsonObject {
            put("parameter_id", StandardParameters.MOUTH_OPEN.raw); put("name", "Authored mouth openness")
        })
        val edited = RigIrCompiler.compile(f.now.model, tileArt = true)
        assertEquals("Authored mouth openness", f.now.model.rig.puppet.parameters.single { it.id == StandardParameters.MOUTH_OPEN }.name)
        assertEquals("Authored lip", f.now.model.rig.puppet.drawables.single { it.id == ribbonId }.name)
        assertEquals(.6f, f.now.model.rig.puppet.drawables.single { it.id == ribbonId }.opacity)
        val overrides = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).overrides.getValue("outline")
        assertEquals(3, overrides.size)
        assertNull(f.now.model.rig.puppet.drawables.single { it.id == ribbonId }.parentDeformerId)
        f.edit("modifier_enable", buildJsonObject { put("id", "outline"); put("enabled", false) })
        val renamed = withoutOutline.parameters.map { parameter ->
            if (parameter.id == StandardParameters.MOUTH_OPEN.raw) parameter.copy(name = "Authored mouth openness") else parameter
        }
        assertEquals(withoutOutline.copy(parameters = renamed), RigIrCompiler.compile(f.now.model, tileArt = true))
        f.edit("modifier_enable", buildJsonObject { put("id", "outline"); put("enabled", true) })
        assertEquals(edited, RigIrCompiler.compile(f.now.model, tileArt = true))
        val editedHead = f.now
        f.edit("modifier_remove", buildJsonObject { put("id", "outline") })
        assertEquals(overrides, RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).overrides.getValue("outline"))
        f.edit("modifier_add", f.addition("mouth.outline", "another-outline", targets))
        assertTrue(f.now.model.rig.puppet.drawables.none { it.id == ribbonId || it.name == "Authored lip" })
        f.runtime.checkout(f.now.projectId, f.now.state, editedHead.historyHead)
        assertEquals(edited, RigIrCompiler.compile(f.now.model, tileArt = true))

        f.edit("modifier_apply", buildJsonObject { put("id", "outline") })
        val rebuilt = RigIrCompiler.compile(f.builder.build(f.now.document), tileArt = true)
        assertEquals(edited, rebuilt)
        val files = mutableMapOf<String, ByteArray>()
        io.github.psd2live.format.compile.Compiler.export(io.github.psd2live.targets.cubism.Cmo3Target(), rebuilt,
            io.github.psd2live.format.compile.ExportOptions("outline")) { path, bytes -> files[path] = bytes }
        val imported = Cmo3ModelImport.read(files.getValue("outline.cmo3")).puppet
        assertTrue(ribbons.all { ribbon -> imported.drawables.any { it.id == ribbon.id && it.mesh != null } })
    }

    @Test fun expressionStandardNamesResolveWithoutBuildingGeometry(): Unit = runBlocking<Unit> {
        val art = document(mouthDetails = true)
        val f = Fixture(art.copy(layerOverrides = art.layerOverrides + mapOf(
            "iris" to LayerClassificationOverride(LayerType.TOGGLE, parameter = StandardParameters.EYE_L_OPEN.raw),
            "lash" to LayerClassificationOverride(LayerType.SWITCH, parameter = StandardParameters.EYE_L_OPEN.raw, switchId = 1),
            "close" to LayerClassificationOverride(LayerType.TOGGLE, parameter = StandardParameters.MOUTH_OPEN.raw),
        )))
        f.install()
        val recipe = RigRecipe(true, modules = RigModules.modules.associate { it.id to ModuleChoice(it == RigModules.EXPRESSION_TOGGLES) })
        val reference = native(f.document, recipe).rig.puppet.parameters
        f.group(emptyList())
        f.edit("modifier_add", f.addition("expression.toggles", "expressions"))
        val actual = f.now.model.rig.puppet.parameters
        for (id in listOf(StandardParameters.EYE_L_OPEN, StandardParameters.MOUTH_OPEN)) {
            assertEquals(reference.single { it.id == id }, actual.single { it.id == id })
        }
        val before = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "expressions") })
        assertEquals(before, RigIrCompiler.compile(f.builder.build(f.now.document)))
    }

    @Test fun expressionModifierUsesNativeOpacityAndAddsInputsToChainsInEitherOrder(): Unit = runBlocking<Unit> {
        val art = document()
        val classified = art.layerOverrides + mapOf(
            "iris" to LayerClassificationOverride(LayerType.SWITCH, parameter = "Variant", switchId = 0),
            "lash" to LayerClassificationOverride(LayerType.SWITCH, parameter = "Variant", switchId = 1),
            "close" to LayerClassificationOverride(LayerType.TOGGLE, parameter = "Accessory"),
        )
        val f = Fixture(art.copy(layerOverrides = classified))
        f.install()
        f.group(listOf("body/legacy", "face/legacy", "hair/chains"))
        val initial = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_add", f.addition("expression.toggles", "expressions"))
        val active = f.now.model
        val output = RigIrCompiler.compile(active)
        val sourceMeshes = active.rig.layerIdByDrawableId.filterValues { it in setOf("iris", "lash", "close") }
        assertEquals(3, sourceMeshes.size)
        val nativeRecipe = RigRecipe(true, modules = RigModules.modules.associate { it.id to ModuleChoice(it == RigModules.EXPRESSION_TOGGLES) })
        val old = native(f.document, nativeRecipe)
        val nativeMeshes = old.rig.layerIdByDrawableId.entries.associate { (mesh, layer) -> layer to DrawableId(mesh) }
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(0f, .25f, .5f, .75f, 1f)) {
            val pose = mapOf(ParameterId("Variant") to value, ParameterId("Accessory") to value)
            val before = evaluator.evaluate(old.rig.puppet, pose)
            val after = evaluator.evaluate(active.rig.puppet, pose)
            for ((mesh, layer) in sourceMeshes)
                assertEquals(before.opacity.getValue(nativeMeshes.getValue(layer)), after.opacity.getValue(DrawableId(mesh)), .0001f)
        }
        val changed = output.physics.groups.filter { group -> group.inputs.any { it.parameter == "Variant" } }
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.all { it.inputs.takeLast(2).map { input -> input.parameter } == listOf("Accessory", "Variant") })
        assertTrue(initial.physics.groups.all { group -> group.inputs.none { it.parameter == "Variant" } })

        f.edit("modifier_move", buildJsonObject { put("id", "expressions"); put("index", 3) })
        assertEquals(output.physics, RigIrCompiler.compile(f.now.model).physics)
        f.edit("modifier_move", buildJsonObject { put("id", "expressions"); put("index", 4) })
        f.edit("modifier_apply", buildJsonObject { put("id", "hair") })
        val afterChain = RigIrCompiler.compile(f.now.model)
        assertEquals(output, afterChain)
        f.edit("modifier_apply", buildJsonObject { put("id", "expressions") })
        assertEquals(afterChain, RigIrCompiler.compile(f.builder.build(f.now.document)))
        assertTrue(f.now.document.rigEdits.physicsEdits.any { group -> group.inputs.any { it.parameter == "Variant" } })
    }

    @Test fun eyeSeeThroughCapturesCanonicalCopyOwnersBeforeAndAfterApplication(): Unit = runBlocking<Unit> {
        val f = Fixture(document(bangOverlay = true)); f.install()
        val artifact = requireNotNull(BundledAlgorithmArtifacts.find("eye.see-through", 1))
        val algorithm = artifact.manifest.algorithms.single()
        val target = ModifierTarget("mesh", f.now.model.rig.puppet.drawables.first().id.raw)
        val owner = RigModifierInstance("window", algorithm.type, algorithm.version, listOf(target),
            outputAliases = mapOf("see-through:${target.id}" to "window-copy"), plugin = artifact.reference)
        val follower = RigModifierInstance("closed-line", "eye.see-through", 1, listOf(target))
        val applied = ModifierCapability(owner.type, owner.version, owner.targets, owner.id, owner.config,
            owner.outputAliases, owner.plugin)

        fun capturedOwners(stack: RigModifierStack): List<RigModifierInstance> {
            val model = f.now.model
            val config = model.config.copy(rigEdits = model.config.rigEdits.copy(modifierStack = stack.toJson()))
            val context = RigModifierSource.of(model.copy(config = config)).seeThroughContext(follower)
            return Json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(RigModifierInstance.serializer()),
                context.values.getValue("earlier_owners"),
            )
        }

        assertEquals(listOf(owner), capturedOwners(RigModifierStack(instances = listOf(owner, follower))))
        assertEquals(listOf(owner), capturedOwners(RigModifierStack(instances = listOf(follower),
            retiredIds = setOf(owner.id), appliedCapabilities = listOf(applied))))
        assertEquals(emptyList(), capturedOwners(RigModifierStack(instances = listOf(owner.copy(enabled = false), follower))))
    }

    @Test fun seeThroughModifiersReuseTheNativeMasksAndKeepCopiesAfterApplication(): Unit = runBlocking<Unit> {
        val f = Fixture(document(bangOverlay = true)); f.install()
        f.group(listOf("body/legacy", "face/legacy", "eye/fitted", "brow/fitted", "mouth/fitted", "hair/legacy"))
        val recipe = fittedRecipe(mapOf("eye" to BaseChoice("eye/fitted", 2), "brow" to BaseChoice("brow/fitted"),
            "mouth" to BaseChoice("mouth/fitted")))
        val original = PSD2LivePipeline().buildPreview(f.document.source, RigModifierPipeline.ordinaryConfig(f.document.config()).copy(
            meshOnly = false, generateDeformers = true, recipe = recipe, seeThroughEyes = true, seeThroughBrows = true))
        val targets = f.now.model.rig.puppet.drawables.map { ModifierTarget("mesh", it.id.raw) }
        f.edit("modifier_add", f.addition("eye.see-through", "eyes", targets))
        f.edit("modifier_add", f.addition("brow.see-through", "brows", targets))
        val instances = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.takeLast(2)
        val browInstance = instances.single { it.id == "brows" }
        val rig = f.now.model.rig
        val browId = rig.layerIdByDrawableId.entries.single { (id, layer) ->
            layer == "brow" && browInstance.localOutputName(id) == null
        }.key
        val browCopyId = browInstance.outputId("see-through:$browId")
        val browCopy = rig.puppet.drawables.single { it.id.raw == browCopyId }
        assertEquals(browId, browCopy.textureSourceId?.raw)
        assertFalse(browCopy.invertMask)
        assertTrue(browCopy.maskedBy.isNotEmpty())
        val browReport = assertNotNull(rig.modifierReport).getValue("entries").jsonArray
            .map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "brows" }
        val browMeshes = browReport.getValue("contributions").jsonArray.map { it.jsonObject }
            .filter { it.getValue("kind").jsonPrimitive.content == "mesh" }
            .associate { it.getValue("id").jsonPrimitive.content to it.getValue("change").jsonPrimitive.content }
        assertEquals(mapOf(browCopyId to "added", browId to "reused") +
            browCopy.maskedBy.associate { it.raw to "reused" }, browMeshes)
        val aliases = f.now.model.rig.puppet.drawables.mapNotNull { mesh ->
            val originalId = instances.firstNotNullOfOrNull { it.localOutputName(mesh.id.raw) }?.removePrefix("see-through:")
                ?: return@mapNotNull null
            mesh.id to SeeThroughLayers.copyId(DrawableId(originalId))
        }.toMap()
        assertTrue(aliases.isNotEmpty())
        assertEquals(original.rig.seeThroughCopies.keys, aliases.values.map { it.raw }.toSet())
        assertTrue(aliases.keys.all { f.now.model.rig.pageByDrawableId[it.raw] != null })
        val aliased = RigIdRemap(drawables = aliases).model(f.now.model.rig.puppet)
        assertEquals(original.rig.puppet.drawables.associate { it.id to (it.maskedBy to it.invertMask) },
            aliased.drawables.associate { it.id to (it.maskedBy to it.invertMask) })
        compare(original, f.now.model.copy(rig = f.now.model.rig.copy(puppet = aliased)), listOf(emptyMap(),
            mapOf(StandardParameters.EYE_L_OPEN to .25f, StandardParameters.ANGLE_X to 18f)))
        if (System.getenv("PSD2LIVE_TOOLS") == "1") {
            val area = Bounds(24f, 8f, 53f, 31f)
            val poses = listOf("rest" to emptyMap(), "blink + turn" to mapOf("ParamEyeLOpen" to .25f, "ParamAngleX" to 18f))
            val frames = listOf("legacy" to original, "stack" to f.now.model).flatMap { (label, preview) ->
                val renderer = io.github.psd2live.tools.Renderer(preview, 480)
                poses.map { (pose, values) -> "$label 路 $pose" to renderer.render(values, area) }
            }
            io.github.psd2live.tools.sheet(frames,
                io.github.psd2live.tools.output("modifier-see-through").resolve("comparison.png"), columns = poses.size)
        }
        val expected = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "eyes") })
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))
        f.edit("modifier_apply", buildJsonObject { put("id", "brows") })
        assertEquals(expected, RigIrCompiler.compile(f.builder.build(f.now.document)))

        val files = mutableMapOf<String, ByteArray>()
        io.github.psd2live.format.compile.Compiler.export(io.github.psd2live.targets.cubism.Cmo3Target(),
            RigIrCompiler.compile(f.now.model, tileArt = true), io.github.psd2live.format.compile.ExportOptions("see-through")) { path, bytes -> files[path] = bytes }
        val imported = Cmo3ModelImport.read(files.getValue("see-through.cmo3")).puppet
        val finalModel = f.now.model.rig.puppet
        val importedMeshes = imported.drawables.associateBy { it.id }
        val affected = finalModel.drawables.filter { it.id in aliases || it.invertMask }
        for (mesh in affected) {
            val read = importedMeshes.getValue(mesh.id)
            assertEquals(mesh.maskedBy, read.maskedBy)
            assertEquals(mesh.invertMask, read.invertMask)
        }
        val evaluator = CpuDeformationEvaluator()
        for (pose in listOf(emptyMap(), mapOf(StandardParameters.EYE_L_OPEN to .25f, StandardParameters.ANGLE_X to 18f))) {
            val before = evaluator.evaluate(finalModel, pose)
            val after = evaluator.evaluate(imported, pose)
            for (mesh in affected) assertEquals(before.opacity.getValue(mesh.id), after.opacity.getValue(mesh.id), .0001f, mesh.id.raw)
        }
    }

}
