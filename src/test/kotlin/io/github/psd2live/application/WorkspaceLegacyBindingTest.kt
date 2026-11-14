package io.github.psd2live.application

import io.github.psd2live.algorithms.binding.ModelIdRemap
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.ModuleChoice
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.core.recipe.RigRecipe
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
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

    private fun native(document: WorkspaceDocument, recipe: RigRecipe = RigRecipe.Legacy): RigPreviewModel = PSD2LivePipeline().buildPreview(document.source,
        RigModifierPipeline.ordinaryConfig(document.config()).copy(meshOnly = false, generateDeformers = true, recipe = recipe))

    private fun fittedRecipe(choices: Map<String, BaseChoice>) = RigRecipe(true,
        bases = RigModules.areas.associate { it.id to (choices[it.id] ?: BaseChoice(it.legacy.id, it.legacy.version)) },
        modules = RigModules.modules.associate { it.id to ModuleChoice(false, it.version) })

    @Test fun recommendedPresetAcceptsUnsidedIrisesWithPairedEyes(): Unit = runBlocking {
        val art = document(pairedBrows = true, bilateralEyes = true)
        val unsided = art.copy(layerOverrides = art.layerOverrides.mapValues { (_, value) ->
            if (value.tag == SemanticTag.IRIDES) value.copy(side = Side.NONE) else value
        })
        val choices = WorkspaceInitialPreset()
        val baseline = choices.configure(WorkspacePresetBaseline.configure(unsided.config(), "modern"))
        val config = baseline.copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
        val source = unsided.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = config.rigEdits)
        val (initialized, preview) = WorkspacePresetInitialization.prepare(source, WorkspaceRasterWork.Direct, choices)
        val stack = RigModifierStack.fromJson(assertNotNull(initialized.rigEdits.modifierStack))
        assertEquals(1, stack.instances.single { it.type == "face/fitted" }.version)
        assertEquals(1, stack.instances.single { it.type == "eye/fitted" }.version)
        assertTrue(stack.instances.any { it.type == "brow/fitted" })
        assertEquals(setOf("iris", "iris-right"), preview.rig.layerIdByDrawableId.values.filter { it.startsWith("iris") }.toSet())
        assertTrue(preview.rig.puppet.drawables.isNotEmpty())
        for (model in listOf(preview, native(source, source.config().recipe))) {
            val puppet = model.rig.puppet
            val ids = puppet.deformers.mapTo(hashSetOf()) { it.id }
            assertTrue(puppet.deformers.all { it.parent == null || it.parent in ids })
            assertTrue(puppet.drawables.all { it.parentDeformerId == null || it.parentDeformerId in ids })
        }
        val rebuilt = WorkspacePreviewBuilder().build(initialized)
        assertEquals(RigIrCompiler.compile(preview, tileArt = true), RigIrCompiler.compile(rebuilt, tileArt = true))
    }

    @Test fun fittedBrowWorkerMatchesNativeHeightAndHeadMotion(): Unit = runBlocking {
        val document = document()
        val artifact = requireNotNull(BundledAlgorithmArtifacts.find("brow/fitted", 1))
        val algorithm = artifact.manifest.algorithms.single()
        for (hasEye in listOf(false, true)) {
            val f = Fixture(document)
            f.install()
            f.group((if (hasEye) listOf("eye/fitted") else emptyList()) + listOf("body/legacy", "face/legacy"))
            val before = f.now.model.rig.puppet
            val brow = before.drawables.single { f.now.model.rig.layerIdByDrawableId[it.id.raw] == "brow" }
            f.edit("modifier_add", JsonObject(f.addition(algorithm.type, "brow") +
                ("version" to JsonPrimitive(1))))
            val actual = f.now.model
            val recipe = fittedRecipe(buildMap {
                put("brow", BaseChoice("brow/fitted"))
                if (hasEye) put("eye", BaseChoice("eye/fitted", 2))
            })
            val neutralHead = listOf(-1f, 0f, .45f, 1f).map { mapOf(StandardParameters.BROW_L_Y to it) }
            val native = native(document, recipe)
            val after = actual.rig.puppet.drawables.single { it.id == brow.id }
            assertEquals(brow.parentDeformerId, after.parentDeformerId)
            assertEquals(listOf(StandardParameters.BROW_L_Y), after.geometryGrid?.axes?.map { it.parameterId })
            val group = actual.rig.puppet.parameterTree.filterIsInstance<ParameterNode.Group>().single {
                it.children.contains(ParameterNode.Param(StandardParameters.BROW_L_Y))
            }
            val instance = RigModifierStack.fromJson(f.now.document.rigEdits.modifierStack!!).instances.single { it.id == "brow" }
            assertEquals(instance.outputId("parameter-folder:ParamGroupBrows"), group.id.raw)
            compare(native, actual, neutralHead, targets = setOf(brow.id))
            assertEquals(RigIrCompiler.compile(actual), RigIrCompiler.compile(f.builder.build(f.now.document)))
        }
    }

    @Test fun fittedBrowPreservesStyledHeadTurnInMixedStack(): Unit = runBlocking {
        val document = document(pairedBrows = true)
        val f = Fixture(document)
        f.install()
        f.edit("modifier_add_group", buildJsonObject {
            put("group", "legacy")
            putJsonArray("entries") {
                add(f.addition("rig.scaffold", "structure"))
                add(f.addition("eye/fitted", "eye"))
                add(f.addition("body/legacy", "body"))
                add(f.addition("face/fitted", "face"))
            }
        })
        val before = f.now.model
        val face = before.rig.puppet.deformers.filterIsInstance<Deformer.Warp>().single { it.name == io.github.psd2live.i18n.tr("model.deformer.face") }
        assertTrue(face.geometryGrid!!.axes.single { it.parameterId == StandardParameters.ANGLE_X }.keys.size > 3)
        f.edit("modifier_add", f.addition("brow/fitted", "brow"))
        val shape = f.addition("brow.shape", "shape", config = buildJsonObject {
            put("scaffold", "structure")
            put("base", "brow")
        })
        f.edit("modifier_add", shape)
        val beforeApply = RigIrCompiler.compile(f.now.model)
        f.edit("modifier_apply", buildJsonObject { put("id", "brow") })
        assertEquals(beforeApply, RigIrCompiler.compile(f.now.model))
    }

    private fun compare(native: RigPreviewModel, actual: RigPreviewModel, poses: List<Map<ParameterId, Float>>,
                        aliases: Map<String, String> = emptyMap(), targets: Set<DrawableId>? = null) {
        val evaluator = CpuDeformationEvaluator()
        fun canvas(model: PuppetModel, mesh: Drawable): DrawableMesh {
            val geometry = requireNotNull(mesh.mesh)
            val uv = RasterMeshJournal.TextureCoordinates(model, mesh).toCanvas(geometry.uvs)
            return DrawableMesh(uv, uv, geometry.indices)
        }
        var referenceRig = ModelIdRemap(parameters = aliases.mapKeys { ParameterId(it.key) }.mapValues { ParameterId(it.value) }).model(native.rig.puppet)
        for (mesh in actual.rig.puppet.drawables) {
            if (targets != null && mesh.id !in targets) continue
            val original = native.rig.puppet.drawables.single { it.id == mesh.id }
            val plan = RasterMeshJournal.prepare(canvas(native.rig.puppet, original), canvas(actual.rig.puppet, mesh))
            val texture = RasterMeshJournal.TextureCoordinates(native.rig.puppet, original)
            val migrated = DrawableMesh(PrimitiveResidual.transfer(requireNotNull(original.mesh).positions, plan.sources),
                texture.toUvs(canvas(actual.rig.puppet, mesh).uvs), requireNotNull(mesh.mesh).indices)
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
}
