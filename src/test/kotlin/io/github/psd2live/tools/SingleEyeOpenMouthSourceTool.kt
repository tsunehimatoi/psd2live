package io.github.psd2live.tools

import io.github.psd2live.application.CliPresetBindings
import io.github.psd2live.application.WorkspaceInitialPreset
import io.github.psd2live.application.WorkspacePresetInitialization
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRasterWork
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import io.github.psd2live.core.recipe.RecipeSubject
import io.github.psd2live.core.recipe.RigModules
import io.github.psd2live.format.model.Channel
import io.github.psd2live.format.model.Deformer
import io.github.psd2live.format.model.KeyGrid
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.WorkspaceSettingsCodec
import io.github.psd2live.project.WorkspaceSourceLayer
import kotlinx.coroutines.runBlocking
import org.umamo.format.art.*
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class SingleEyeOpenMouthSourceTool {
    @Test fun compareTongue(): Unit = runBlocking {
        requireTools()
        val document = withTongue(singleEyeOpenDocument(Sample.fromEnvironment()))
        val preceding = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.aftershock" to 4, "eye.squash" to 4, "brow.shape" to 4, "brow.lid-coupling" to 6,
            "brow.blink" to 4, "brow.spring" to 5, "mouth.vowel" to 9, "mouth.capture" to 8)
        val control = "ParamTongueOut"
        compareShapeModule("mouth.tongue", 8, preceding, setOf(control), emptySet(),
            RigMouthTongueModifier::fallbackFrameDefinition, document = document) { before, after ->
            val tongue = after.meshes.single { it.id == "ArtMeshTongue" }
            val previous = before.meshes.single { it.id == tongue.id }
            assertTrue(tongue.visible && tongue.opacity > 0f)
            assertTrue(tongue.maskedBy.any { id -> after.meshes.any { it.id == id && id.startsWith("ArtMeshMouth") } },
                "Tongue needs the live mouth opening mask: ${tongue.maskedBy}")
            val local = tongue.shapes.any { binding -> binding.parameter == control &&
                binding.shapes.any { it?.deltas?.toArray()?.any { delta -> delta != 0f } == true } } ||
                tongue.offsets?.let { grid -> grid.axes.any { it.parameter == control } &&
                    grid.cells.map { it.form }.distinct().size > 1 } == true
            val parent = after.deformers.singleOrNull { it.id == tongue.parent } as? Deformer.Warp
            val inherited = parent?.lattice?.let { grid -> grid.axes.any { it.parameter == control } &&
                grid.cells.map { it.form }.distinct().size > 1 } == true
            assertTrue(tongue != previous && (local || inherited), "TongueOut must change the tongue shape")
            println("Tongue mesh: ${tongue.id}; mask: ${tongue.maskedBy}; parent: ${tongue.parent}")
        }
    }

    private fun withTongue(original: WorkspaceDocument): WorkspaceDocument {
        val config = PipelineConfig(meshSpacing = 40)
        val analysis = RigGenerationSource.analyze(original.source, config)
        return if (analysis.layers.any { it.semantic.tag == SemanticTag.TONGUE && it.source.visible && it.opaquePixels > 0 }) {
            original
        } else {
            val mouth = analysis.layers.filter { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) &&
                it.semantic.role == null && it.source.visible && !it.source.clipped }.maxBy { it.opaquePixels }
            val owner = mouth.source
            val bounds = owner.bounds
            val width = (bounds.width / 3).coerceAtLeast(5)
            val height = (bounds.height / 5).coerceAtLeast(4)
            val tongueBounds = LayerBounds(bounds.left + (bounds.width - width) / 2, bounds.top + bounds.height / 2, width, height)
            val id = LayerId("single-eye-source-tongue")
            val color = byteArrayOf(182.toByte(), 78, 93, -1)
            val raster = LayerRaster(width, height, ByteArray(width * height * 4) { color[it % 4] })
            val tongue = WorkspaceSourceLayer(id, id.raw, owner.groupPath, SourceLayerKind.Raster, true, owner.order,
                tongueBounds, 1f, true, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
            val source = object : SourceArt by original.source {
                override val layers = original.source.layers.flatMap { layer ->
                    if (layer.id == owner.id) listOf(tongue, layer) else listOf(layer)
                }.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
            }
            val classification = LayerClassificationOverride(LayerType.PRESET, SemanticTag.TONGUE, mouth.semantic.side)
            println("Added a visible clipped tongue to ${owner.name}")
            original.copy(source = source, layerOverrides = original.layerOverrides + (id.raw to classification))
        }
    }

    @Test fun compareEyeHighlight(): Unit = runBlocking {
        requireTools()
        val (document, glintLayers) = withLeftHighlight(singleEyeOpenDocument(Sample.fromEnvironment()))
        val chain = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.aftershock" to 4, "eye.squash" to 4,
            "mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12)
        compareShapeModule("eye.highlight", 3, chain, emptySet(), emptySet(),
            { RigEyeHighlightModifier.definition(it, modern = true) }, document = document) { before, after ->
            val ids = rig.layerIdByDrawableId.filterValues { it in glintLayers }.keys
            assertTrue(ids.isNotEmpty(), "Left highlight artwork must produce a fitted mesh")
            for (id in ids) {
                val old = before.meshes.single { it.id == id }
                val glint = after.meshes.single { it.id == id }
                val oldParent = before.deformers.single { it.id == old.parent }
                val parent = after.deformers.single { it.id == glint.parent }
                assertTrue(parent.id == oldParent.id || parent.parent == oldParent.id,
                    "$id must stay on its fitted eye branch")
                val headAxes = setOf("ParamAngleX", "ParamAngleY")
                val changedShapes = glint.shapes - old.shapes.toSet()
                val meshDrift = changedShapes.any { binding ->
                    binding.parameter in headAxes &&
                        binding.shapes.any { it?.deltas?.toArray()?.any { delta -> delta != 0f } == true }
                }
                val lattice = (parent as? Deformer.Warp)?.lattice
                val warpDrift = parent != oldParent && lattice != null &&
                    lattice.axes.any { it.parameter in headAxes } && lattice.cells.map { it.form }.distinct().size > 1
                assertTrue(meshDrift || warpDrift, "$id must have head-angle parallax")
                println("Left highlight mesh $id parent ${old.parent} -> ${glint.parent}")
            }
        }
    }

    @Test fun compareEyeSquash(): Unit = runBlocking {
        val chain = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3, "eye.aftershock" to 4,
            "mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12)
        compareShapeModule("eye.squash", 4, chain, emptySet(), setOf("ParamEyeLOpen"), RigEyeSquashModifier::highlightDefinition) { before, after ->
            val id = "ArtMeshIridesL"
            assertNotEquals(before.meshes.single { it.id == id }.offsets, after.meshes.single { it.id == id }.offsets)
        }
    }

    @Test fun compareEyeAftershock(): Unit = runBlocking {
        val chain = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12)
        val controls = RigModules.EYE_AFTERSHOCK.defines.mapTo(HashSet()) { it.id }
        val bounce = "ParamEyeLBounce"
        compareShapeModule("eye.aftershock", 4, chain, controls, setOf(bounce), RigEyeAftershockModifier::fittedHeadDefinition) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
            val added = after.physics.groups - before.physics.groups.toSet()
            val group = added.single { it.outputs.any { output -> output.parameter == bounce } }
            val input = group.inputs.single()
            assertEquals("ParamEyeLOpen", input.parameter)
            assertEquals(100f, input.weight)
            val output = group.outputs.single()
            assertEquals(bounce, output.parameter)
            assertTrue(output.scale > 0f && output.weight > 0f)
            assertEquals(2, group.segments.size)
        }
    }

    @Test fun compareEyeEmotion(): Unit = runBlocking {
        val chain = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "mouth.vowel" to 9,
            "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12)
        val controls = RigModules.EYE_EMOTION.defines.mapTo(HashSet()) { it.id }
        compareShapeModule("eye.emotion", 3, chain, controls, setOf("ParamEyeLForm", "ParamEyeLSquint"),
            { source -> RigEyeEmotionModifier.definition(source, modern = true) }) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
        }
    }

    @Test fun compareEyeSmile(): Unit = runBlocking {
        val downstream = mapOf("eye.wide" to 3, "eye.gaze-lid" to 3, "mouth.vowel" to 9, "mouth.capture" to 8,
            "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12)
        val controls = RigModules.EYE_SMILE.defines.mapTo(HashSet()) { it.id }
        compareShapeModule("eye.smile", 3, downstream, controls, emptySet(), RigEyeSmileModifier::modernDefinition) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
        }
    }

    @Test fun compareWideEye(): Unit = runBlocking {
        val downstream = mapOf("eye.gaze-lid" to 3, "mouth.vowel" to 9, "mouth.capture" to 8,
            "mouth.jaw" to 11, "mouth.cheek-lid" to 11)
        compareShapeModule("eye.wide", 3, downstream, emptySet(), emptySet(), RigEyeWideModifier::modernDefinition) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
            val open = "ParamEyeLOpen"
            assertTrue(after.parameters.single { it.id == open }.max > before.parameters.single { it.id == open }.max)
        }
    }

    @Test fun compareGazeLid(): Unit = runBlocking {
        val speech = mapOf("mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11)
        val controls = RigModules.EYE_GAZE_LID.defines.mapTo(HashSet()) { it.id }
        compareShapeModule("eye.gaze-lid", 3, speech, controls, emptySet(),
            { source -> RigEyeGazeLidModifier.definition(source, modern = true) }) { before, after ->
            val id = "ArtMeshEyelashL"
            assertNotEquals(before.meshes.single { it.id == id }, after.meshes.single { it.id == id })
            assertNotEquals(before.parameterLinks, after.parameterLinks)
        }
    }

    @Test fun compareVowel(): Unit = runBlocking {
        val vowels = setOf("ParamA", "ParamI", "ParamU", "ParamE", "ParamO")
        compareShapeModule("mouth.vowel", 9, emptyMap(), vowels, vowels, RigMouthVowelModifier::fallbackFrameDefinition)
    }

    @Test fun compareCapture(): Unit = runBlocking {
        val controls = RigModules.MOUTH_CAPTURE.defines.mapTo(HashSet()) { it.id }
        val moved = setOf("ParamMouthX", "ParamMouthPuckerWiden", "ParamMouthFunnel")
        compareShapeModule("mouth.capture", 8, mapOf("mouth.vowel" to 9), controls, moved,
            RigMouthCaptureModifier::fallbackFrameDefinition)
    }

    @Test fun compareJawWithTongue(): Unit = runBlocking {
        requireTools()
        val document = withTongue(singleEyeOpenDocument(Sample.fromEnvironment()))
        val chain = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.aftershock" to 4, "eye.squash" to 4, "brow.shape" to 4, "brow.lid-coupling" to 6,
            "brow.blink" to 4, "brow.spring" to 5,
            "mouth.vowel" to 9, "mouth.capture" to 8, "mouth.tongue" to 8)
        val opening = "ParamMouthOpenY"
        compareShapeModule("mouth.jaw", 13, chain, emptySet(), setOf(opening),
            RigMouthJawModifier::fallbackTongueFrameDefinition, document = document) { before, after ->
            val face = after.meshes.single { it.id == "ArtMeshFace" }
            val oldFace = before.meshes.single { it.id == face.id }
            val shapes = face.shapes - oldFace.shapes.toSet()
            val offsets = face.offsets
            val motion = shapes.any { binding -> binding.parameter == opening &&
                binding.shapes.any { it?.deltas?.toArray()?.any { delta -> delta != 0f } == true } } ||
                (offsets != null && offsets != oldFace.offsets && offsets.axes.any { it.parameter == opening } &&
                    offsets.cells.map { it.form }.distinct().size > 1)
            assertTrue(motion, "Jaw must deform the face with mouth opening")
            val tongue = after.meshes.single { it.id == "ArtMeshTongue" }
            assertEquals(before.meshes.single { it.id == tongue.id }, tongue)
            assertEquals(listOf("ArtMeshMouth"), tongue.maskedBy)
            assertTrue(after.meshes.any { it.id == tongue.maskedBy.single() })
            assertTrue(tongue.visible && tongue.opacity > 0f)
            assertTrue(tongue.shapes.any { it.parameter == "ParamTongueOut" } ||
                tongue.offsets?.axes.orEmpty().any { it.parameter == "ParamTongueOut" })
            println("Jaw moves ${face.id}; retained tongue ${tongue.id} masked by ${tongue.maskedBy}")
        }
    }

    @Test fun compareJaw(): Unit = runBlocking {
        compareShapeModule("mouth.jaw", 11, mapOf("mouth.vowel" to 9, "mouth.capture" to 8),
            emptySet(), setOf("ParamMouthOpenY"), RigMouthJawModifier::fallbackFrameDefinition)
    }

    @Test fun compareCheekLidWithTongue(): Unit = runBlocking {
        requireTools()
        val document = withTongue(singleEyeOpenDocument(Sample.fromEnvironment()))
        val chain = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.aftershock" to 4, "eye.squash" to 4, "brow.shape" to 4, "brow.lid-coupling" to 6,
            "brow.blink" to 4, "brow.spring" to 5,
            "mouth.vowel" to 9, "mouth.capture" to 8, "mouth.tongue" to 8, "mouth.jaw" to 13)
        compareShapeModule("mouth.cheek-lid", 13, chain, emptySet(), emptySet(),
            RigMouthCheekLidModifier::fallbackTongueFrameDefinition, document = document) { before, after ->
            verifyCheekLidContribution(before, after)
            val tongue = after.meshes.single { it.id == "ArtMeshTongue" }
            assertEquals(before.meshes.single { it.id == tongue.id }, tongue)
            assertEquals(listOf("ArtMeshMouth"), tongue.maskedBy)
            assertTrue(after.meshes.any { it.id == tongue.maskedBy.single() })
            assertTrue(tongue.visible && tongue.opacity > 0f)
            assertTrue(tongue.shapes.any { it.parameter == "ParamTongueOut" } ||
                tongue.offsets?.axes.orEmpty().any { it.parameter == "ParamTongueOut" })
            println("Cheek and lid moves ArtMeshEyelashL and ArtMeshEyewhiteL; retained tongue ${tongue.id} masked by ${tongue.maskedBy}")
        }
    }

    @Test fun compareCheekLid(): Unit = runBlocking {
        compareShapeModule("mouth.cheek-lid", 11, mapOf("mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11),
            emptySet(), emptySet(), RigMouthCheekLidModifier::fallbackFrameDefinition) { before, after -> verifyCheekLidContribution(before, after) }
    }

    @Test fun compareSpring(): Unit = runBlocking {
        val preceding = mapOf("mouth.vowel" to 9, "mouth.capture" to 8, "mouth.jaw" to 11, "mouth.cheek-lid" to 11)
        val control = setOf("ParamMouthSpring")
        compareShapeModule("mouth.spring", 12, preceding, control, control, RigMouthSpringModifier::fallbackFrameDefinition) { before, after ->
            assertTrue(before.meshes.none { it.id.startsWith("ArtMeshTooth") || it.id.startsWith("ArtMeshTongue") })
            assertTrue(before.physics.groups.none { group -> group.outputs.any { it.parameter in control } })
            val group = (after.physics.groups - before.physics.groups.toSet()).single()
            assertEquals(mapOf("ParamMouthOpenY" to 80f, "ParamMouthForm" to 20f), group.inputs.associate { it.parameter to it.weight })
            val output = group.outputs.single()
            assertEquals(control.single(), output.parameter)
            assertTrue(output.scale > 0f && output.weight > 0f)
            assertEquals(2, group.segments.size)
        }
    }

    private fun verifyCheekLidContribution(before: RigIR, after: RigIR) {
        val original = before.meshes.associateBy { it.id }
        val changed = after.meshes.filter { it != original[it.id] }
        assertEquals(setOf("ArtMeshEyelashL", "ArtMeshEyewhiteL"), changed.mapTo(HashSet()) { it.id })
        val frames = after.deformers.associateBy { it.id }
        val oldFrames = before.deformers.associateBy { it.id }
        val mouth = "ParamMouthForm"
        fun <F> varies(grid: KeyGrid<F>?): Boolean {
            val axis = grid?.axes?.indexOfFirst { it.parameter == mouth } ?: return false
            if (axis < 0) return false
            return grid.cells.groupBy { cell -> cell.coordinate.toArray().filterIndexed { index, _ -> index != axis } }
                .values.any { cells -> cells.map { it.form }.distinct().size > 1 }
        }
        for (mesh in changed) {
            val previous = original.getValue(mesh.id)
            val local = mesh.offsets != previous.offsets && varies(mesh.offsets) ||
                mesh.shapes.any { it.parameter == mouth && it !in previous.shapes &&
                    it.shapes.any { shape -> shape?.deltas?.toArray()?.any { delta -> delta != 0f } == true } }
            val inherited = generateSequence(mesh.parent) { frames[it]?.parent }.any { id ->
                val warp = frames[id] as? Deformer.Warp
                warp != null && warp != oldFrames[id] && varies(warp.lattice)
            }
            assertTrue(local || inherited, "${mesh.id} must move with $mouth")
        }
    }

    private suspend fun compareShapeModule(type: String, version: Int, predecessors: Map<String, Int>,
                                           controls: Set<String>, moving: Set<String>,
                                           sourceDefinition: (RigModifierSource) -> RigModifierDefinition,
                                           document: WorkspaceDocument? = null,
                                           verifyContribution: RigPreviewModel.(RigIR, RigIR) -> Unit = { _, _ -> }) {
        requireTools()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3,
            "mouth/fitted" to 7, "mouth.outline" to 10, type to 1) + predecessors
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document ?: singleEyeOpenDocument(Sample.fromEnvironment()), WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val module = stack.instances.single { it.type == type }
        val stage = stack.index(module.id)
        val expected = assertNotNull(baseline.modifierEvaluation)
        for (entry in expected.entries.filter { it.instance.type in versions }) {
            assertEquals(ModifierStatus.ACTIVE, entry.status, entry.instance.type)
        }
        val before = expected.outputs[stage - 1]
        val after = expected.outputs[stage]
        baseline.verifyContribution(before, after)
        assertEquals(controls, after.parameters.mapTo(HashSet()) { it.id } - before.parameters.map { it.id }.toSet())
        val oldMeshes = before.meshes.associateBy { it.id }
        val moved = buildSet {
            for (mesh in after.meshes) {
                val old = oldMeshes[mesh.id]
                for (binding in mesh.shapes - old?.shapes.orEmpty().toSet()) {
                    if (binding.shapes.any { form -> form?.deltas?.toArray()?.any { it != 0f } == true }) add(binding.parameter)
                }
                val offsets = mesh.offsets ?: continue
                if (offsets != old?.offsets && offsets.cells.any { it.form.deltas.toArray().any { delta -> delta != 0f } }) {
                    addAll(offsets.axes.map { it.parameter })
                }
            }
        }
        assertTrue(moved.containsAll(moving), "$type must contribute nonzero source geometry for ${moving - moved}")
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(type, version))
        val selected = module.copy(version = version, plugin = artifact.reference)
        val modified = stack.copy(instances = stack.instances.map { if (it.id == selected.id) selected else it })
        val candidate = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val actual = assertNotNull(candidate.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        for (index in stack.instances.indices) {
            val before = expected.outputs[index]
            val after = actual.outputs[index]
            if (before == after) continue
            val meshes = before.meshes.associateBy { it.id }
            fail("${stack.instances[index].type} differs, meshes=${after.meshes.filter { it != meshes[it.id] }.map { it.id }}")
        }
        assertTrue(RigIrCompiler.compile(baseline, tileArt = true) == RigIrCompiler.compile(candidate, tileArt = true),
            "Complete single-eye $type IR differs")

        val instance = actual.entries[stage].instance
        val definition = sourceDefinition(RigModifierSource.of(candidate))
        val context = actual.readAlgorithms[instance.id]?.context ?: definition.authorContext(instance).withUpstream(actual.entries.take(stage)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        val root = Files.createTempDirectory("single-eye-open-package-")
        val preferences = Preferences.userRoot().node("single-eye-open-package/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val result = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), actual.outputs[stage - 1],
                instance, {}, store, context = context, declaredParameters = definition.parameters(instance))
            assertTrue(expected.outputs[stage] == result, "Installed $type worker differs from v1")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareExpressionCheek(): Unit = runBlocking {
        val chain = singleEyeExpressionChain()
        val type = "expression.cheek"
        val parameter = "ParamCheek"
        compareShapeModule(type, 3, chain, setOf(parameter), emptySet(), RigCheekModifier::carrierDefinition) { before, after ->
            val oldMeshes = before.meshes.associateBy { it.id }
            val colored = after.meshes.filter { mesh ->
                val grid = mesh.channels[Channel.MULTIPLY_COLOR]
                grid != null && grid != oldMeshes.getValue(mesh.id).channels[Channel.MULTIPLY_COLOR] &&
                    grid.axes.any { it.parameter == parameter } && grid.cells.map { it.form }.distinct().size > 1
            }
            assertTrue(colored.any { it.id == "ArtMeshFace" }, "Cheek must add a nonconstant face tint: ${colored.map { it.id }}")
            println("Cheek tint meshes: ${colored.map { it.id }}")
        }

        compareRawExpression(type, chain)
    }

    @Test fun compareExpressionGloom(): Unit = runBlocking {
        val chain = singleEyeExpressionChain() + ("expression.cheek" to 3)
        val type = "expression.gloom"
        val parameter = "ParamGloom"
        compareShapeModule(type, 3, chain, setOf(parameter), emptySet(), RigGloomModifier::carrierDefinition) { before, after ->
            val oldMeshes = before.meshes.associateBy { it.id }
            val shaded = after.meshes.filter { mesh ->
                val grid = mesh.channels[Channel.MULTIPLY_COLOR] ?: return@filter false
                if (grid == oldMeshes.getValue(mesh.id).channels[Channel.MULTIPLY_COLOR]) return@filter false
                val axis = grid.axes.indexOfFirst { it.parameter == parameter }
                if (axis < 0) return@filter false
                grid.cells.groupBy { cell -> grid.axes.indices.filterNot { it == axis }.map { cell.coordinate[it] } }
                    .values.any { cells -> cells.map { it.form }.distinct().size > 1 }
            }
            assertTrue(shaded.any { it.id == "ArtMeshFace" }, "Gloom must add nonconstant face shading: ${shaded.map { it.id }}")
            println("Gloom shading meshes: ${shaded.map { it.id }}")
        }

        compareRawExpression(type, chain)
    }

    private suspend fun compareRawExpression(type: String, chain: Map<String, Int>) {
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        val config = PipelineConfig()
        val source = singleEyeOpenDocument(Sample.fromEnvironment()).source
        val raw = pipeline.buildSourceExport(source, config)
        val rawStack = RigModifierStack.fromJson(assertNotNull(raw.config.rigEdits.modifierStack))
        val versions = rawStack.instances.filter { RigLegacyBindings.find(it.type) != null }.associate { it.type to it.version }
        assertEquals(chain - "expression.cheek", versions.filterKeys { it in chain && it != "expression.cheek" })
        val analyses = RigGenerationSource.prepare(RigGenerationSource.analyze(source, config), config)
        val bindings = CliPresetBindings(analyses, config)
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(),
            config.rigEdits.copy(modifierStack = RigModifierStack().toJson()))
        suspend fun compile(version: Int): RigIR {
            val (_, evaluated) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
                WorkspaceInitialPreset(clothing = false), requestedVersions = versions + chain + (type to version),
                cliBindings = bindings, restoreSourceRotations = true)
            val entries = assertNotNull(evaluated.modifierEvaluation).entries.associateBy { it.instance.type }
            for ((lockedType, lockedVersion) in chain + (type to version)) {
                val entry = entries.getValue(lockedType)
                assertEquals(lockedVersion, entry.instance.version, lockedType)
                assertEquals(ModifierStatus.ACTIVE, entry.status, lockedType)
            }
            return RigIrCompiler.compile(CliPresetPresentation.apply(evaluated, analyses, bindings.moduleParameters, config), tileArt = true)
        }
        val expected = RigIrCompiler.compile(raw, tileArt = true)
        assertTrue(expected == compile(1), "Locked RAW $type v1 differs from the preset")
        assertTrue(expected == compile(3), "Complete RAW $type IR differs")
    }

    private fun singleEyeExpressionChain() = mapOf("eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.aftershock" to 4, "eye.squash" to 4, "brow.shape" to 4, "brow.lid-coupling" to 6,
            "brow.blink" to 4, "brow.spring" to 5, "mouth.vowel" to 9, "mouth.capture" to 8,
            "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12)

    @Test fun compareRawSingleEyePreset() {
        requireTools()
        val sample = Sample.fromEnvironment()
        val pipeline = PSD2LivePipeline().apply { deferRuntimeBundles = true }
        val config = PipelineConfig()
        val source = singleEyeArtwork(pipeline.readPsd(sample.path), config)
        val expected = RigIrCompiler.compile(pipeline.buildPreview(source, config), tileArt = true)
        RigBuildProfile.reset()
        val recording = RigBuildProfile.recording
        val preview = try {
            RigBuildProfile.recording = true
            pipeline.buildSourceExport(source, config)
        } finally {
            RigBuildProfile.recording = recording
        }
        val actual = RigIrCompiler.compile(preview, tileArt = true)
        val stack = RigModifierStack.fromJson(assertNotNull(preview.config.rigEdits.modifierStack))
        val versions = mapOf(
            "eye.smile" to 3, "eye.wide" to 3, "eye.gaze-lid" to 3, "eye.emotion" to 3,
            "eye.aftershock" to 4, "eye.squash" to 4,
            "brow.shape" to 4, "brow.lid-coupling" to 6, "brow.blink" to 4, "brow.spring" to 5,
            "mouth/fitted" to 7, "mouth.outline" to 10, "mouth.vowel" to 9, "mouth.capture" to 8,
            "mouth.jaw" to 11, "mouth.cheek-lid" to 11, "mouth.spring" to 12,
            "body.arms" to 3, "expression.cheek" to 3, "expression.gloom" to 3,
        )
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val templateConsumers = legacyTemplateConsumers()
        assertTrue(templateConsumers.isEmpty(), "Unexpected legacy template consumers: $templateConsumers")
        fun differences(a: Any, b: Any): List<String> = a.javaClass.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { it.isAccessible = true; it.get(a) != it.get(b) }.map { it.name }
        val report = buildString {
            appendLine("IR: ${differences(expected, actual)}")
            appendLine("Legacy template consumers: ${templateConsumers.sorted()}")
            val meshes = expected.meshes.associateBy { it.id }
            for (mesh in actual.meshes) meshes[mesh.id]?.takeIf { it != mesh }?.let { old ->
                appendLine("${mesh.id}: ${differences(old, mesh)}; geometry: ${old.geometry?.let { a -> mesh.geometry?.let { b -> differences(a, b) } }}; parents: ${old.parent} -> ${mesh.parent}")
                appendLine("  offsets ${old.offsets?.axes?.map { it.parameter }} -> ${mesh.offsets?.axes?.map { it.parameter }}")
                appendLine("  shapes ${old.shapes.map { it.parameter }} -> ${mesh.shapes.map { it.parameter }}")
                appendLine("  positions ${old.geometry?.positions?.toArray()?.take(8)} -> ${mesh.geometry?.positions?.toArray()?.take(8)}")
            }
            val deformers = expected.deformers.associateBy { it.id }
            for (frame in actual.deformers) deformers[frame.id]?.takeIf { it != frame }?.let { old ->
                appendLine("${frame.id}: ${differences(old, frame)}")
                if (old is io.github.psd2live.format.model.Deformer.Rotation && frame is io.github.psd2live.format.model.Deformer.Rotation)
                    appendLine("  angle ${old.baseAngle} -> ${frame.baseAngle}; handle ${old.handleLength} -> ${frame.handleLength}")
            }
            val order = expected.deformers.map { it.id }.zip(actual.deformers.map { it.id })
                .mapIndexedNotNull { index, (a, b) -> if (a == b) null else "$index: $a -> $b" }
            if (order.isNotEmpty()) appendLine("Deformer order: $order")
            appendLine("Missing deformers: ${deformers.keys - actual.deformers.map { it.id }.toSet()}")
            appendLine("Extra deformers: ${actual.deformers.map { it.id }.toSet() - deformers.keys}")
        }
        output("single-eye-open-mouth").resolve("raw-${sample.name}.txt").writeText(report)
        assertTrue(expected == actual, report)
        val exported = pipeline.export(preview, sample.path.fileName.toString(), output("single-eye-open-mouth/readback").toPath())
        assertTrue(exported.exportedFiles.any { it.path.fileName.toString().endsWith(".cmo3") })
        assertTrue(exported.exportedFiles.any { it.path.fileName.toString().endsWith(".moc3") })
    }

    @Test fun compareFittedMouth(): Unit = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val document = singleEyeOpenDocument(sample)
        val measured = RigGenerationSource.analyze(document.source, PipelineConfig(meshSpacing = 40))
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3, "mouth/fitted" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(document, WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val mouth = stack.instances.single { it.type == "mouth/fitted" }
        val stage = stack.index(mouth.id)
        val expected = assertNotNull(baseline.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, expected.entries[stage].status)
        val mouthLayers = measured.layers.filter { it.semantic.tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN) }
            .mapTo(HashSet()) { it.source.id.raw }
        val beforeMouth = expected.outputs[stage - 1].meshes.associateBy { it.id }
        assertTrue(expected.outputs[stage].meshes.any {
            baseline.rig.ownLayerIdByDrawableId[it.id] in mouthLayers && it != beforeMouth[it.id]
        }, "Fitting must affect the source mouth mesh")
        val beforeFrames = expected.outputs[stage - 1].deformers.associateBy { it.id }
        val changedMeshes = expected.outputs[stage].meshes.filter { it != beforeMouth[it.id] }.map { it.id }
        val changedFrames = expected.outputs[stage].deformers.filter { it != beforeFrames[it.id] }.map { it.id }
        output("single-eye-open-mouth").resolve("${sample.name}.txt").writeText(
            "Fitting v1 changes meshes: $changedMeshes\nFitting v1 changes deformers: $changedFrames\n")
        val sourceVersion = assertNotNull(BundledAlgorithmArtifacts.find(mouth.type, 7))
        val modified = stack.copy(instances = stack.instances.map { entry ->
            if (entry.id == mouth.id) entry.copy(version = 7, plugin = sourceVersion.reference) else entry
        })
        val evaluated = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val actual = assertNotNull(evaluated.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        for (index in stack.instances.indices) {
            val before = expected.outputs[index]
            val after = actual.outputs[index]
            if (before != after) {
                val originalMeshes = before.meshes.associateBy { it.id }
                val changed = after.meshes.filter { it != originalMeshes[it.id] }.map { it.id }
                fail("${stack.instances[index].type} differs, changed meshes=$changed, deformers match=${before.deformers == after.deformers}")
            }
        }
        assertTrue(RigIrCompiler.compile(baseline, tileArt = true) == RigIrCompiler.compile(evaluated, tileArt = true),
            "Complete workspace IR differs")

        val instance = actual.entries[stage].instance
        val definition = RigFittedMouthDefinition.fallbackFrameDefinition(RigModifierSource.of(evaluated))
        val context = definition.authorContext(instance).withUpstream(actual.entries.take(stage)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        val root = Files.createTempDirectory("single-eye-open-mouth-package-")
        val preferences = Preferences.userRoot().node("single-eye-open-mouth/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(sourceVersion))
            val worker = store.snapshot().single()
            val result = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), actual.outputs[stage - 1],
                instance, {}, store, context = context, declaredParameters = definition.parameters(instance))
            assertTrue(expected.outputs[stage] == result, "Installed worker differs from v1")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }

    @Test fun compareOutline(): Unit = runBlocking {
        requireTools()
        val sample = Sample.fromEnvironment()
        val versions = mapOf("face/fitted" to 3, "eye/fitted" to 5, "brow/fitted" to 3, "mouth/fitted" to 7, "mouth.outline" to 1)
        val (planned, baseline) = WorkspacePresetInitialization.prepare(singleEyeOpenDocument(sample), WorkspaceRasterWork.Direct,
            WorkspaceInitialPreset(clothing = false), requestedVersions = versions)
        val stack = RigModifierStack.fromJson(assertNotNull(planned.rigEdits.modifierStack))
        assertEquals(versions, stack.instances.filter { it.type in versions }.associate { it.type to it.version })
        val outline = stack.instances.single { it.type == "mouth.outline" }
        val stage = stack.index(outline.id)
        val expected = assertNotNull(baseline.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, expected.entries[stage].status)
        assertTrue(expected.outputs[stage].meshes.size > expected.outputs[stage - 1].meshes.size)
        val artifact = assertNotNull(BundledAlgorithmArtifacts.find(outline.type, 10))
        val modified = stack.copy(instances = stack.instances.map {
            if (it.id == outline.id) it.copy(version = 10, plugin = artifact.reference) else it
        })
        val model = WorkspacePreviewBuilder().build(planned.copy(rigEdits = planned.rigEdits.copy(modifierStack = modified.toJson())))
        val actual = assertNotNull(model.modifierEvaluation)
        assertEquals(ModifierStatus.ACTIVE, actual.entries[stage].status)
        val mismatch = expected.outputs.zip(actual.outputs).indexOfFirst { (before, after) -> before != after }
        if (mismatch >= 0) {
            val old = expected.outputs[mismatch]
            val new = actual.outputs[mismatch]
            val fields = old.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { it.isAccessible = true; it.get(old) != it.get(new) }.map { it.name }
            val beforeMeshes = old.meshes.associateBy { it.id }
            val beforeFrames = old.deformers.associateBy { it.id }
            fail("${stack.instances[mismatch].type} differs in $fields, meshes=" +
                new.meshes.filter { it != beforeMeshes[it.id] }.map { it.id } + ", frames=" +
                new.deformers.filter { it != beforeFrames[it.id] }.map { it.id })
        }
        assertTrue(RigIrCompiler.compile(baseline, tileArt = true) == RigIrCompiler.compile(model, tileArt = true))
        assertEquals(baseline.rig.modifierRasterAssets, model.rig.modifierRasterAssets)

        val instance = actual.entries[stage].instance
        val definition = RigFittedMouthOutlineDefinition.fallbackFrameDefinition(RigModifierSource.of(model))
        val context = definition.authorContext(instance).withUpstream(actual.entries.take(stage)
            .filter { it.status == ModifierStatus.ACTIVE && it.instance.type in definition.requires + definition.optionalUpstream }
            .map { entry -> entry.instance.let {
                ModifierUpstreamCapability(it.type, it.type, it.version, it.targets, it.id, it.config, it.outputAliases, it.plugin)
            } })
        val root = Files.createTempDirectory("single-eye-open-outline-package-")
        val preferences = Preferences.userRoot().node("single-eye-open-outline/" + UUID.randomUUID())
        val store = ModifierPluginStore(root.resolve("installed"), preferences)
        try {
            BundledAlgorithmArtifacts.retain(store, listOf(artifact))
            val worker = store.snapshot().single()
            val result = ModifierPluginWorkers.evaluate(worker, worker.manifest.algorithms.single(), actual.outputs[stage - 1],
                instance, {}, store, context = context, resolveRasters = { rig, rasters, checkpoint ->
                    RigModifierRasterAssets.attach(rig, rasters, model.config, checkpoint)
                })
            assertTrue(expected.outputs[stage] == result, "Installed outline worker differs from v1")
        } finally {
            store.snapshot().forEach { ModifierPluginWorkers.stop(it.reference) }
            preferences.removeNode()
            ModifierPluginStore.deleteTree(root)
        }
    }
}

internal fun singleEyeOpenDocument(sample: Sample, meshSpacing: Int = 40): WorkspaceDocument {
    val artwork = PSD2LivePipeline().readPsd(sample.path)
    val config = PipelineConfig(meshSpacing = meshSpacing, rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()))
    val singleEye = singleEyeArtwork(artwork, config)
    val measured = RigGenerationSource.analyze(singleEye, config)
    val subject = RecipeSubject.of(measured)
    for (tag in listOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYEBROW)) {
        assertEquals(setOf(Side.LEFT), subject.sides(tag), tag.name)
    }
    val mouthFit = assertNotNull(RigBuilder.rigContext(measured, config, PreviewMeshCache()).fittedMouth(config))
    assertNotNull(mouthFit.source)
    assertFalse(mouthFit.closedLine || mouthFit.layered)
    return WorkspaceDocument(singleEye, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits,
        WorkspaceSettingsCodec.encode(config))
}

private fun singleEyeArtwork(artwork: SourceArt, config: PipelineConfig): SourceArt {
    val eyeTags = setOf(SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.IRIDES, SemanticTag.EYE_CLOSE, SemanticTag.EYEBROW)
    val original = RigGenerationSource.analyze(artwork, config)
    val removed = original.layers.filter { it.semantic.tag in eyeTags && it.semantic.side == Side.RIGHT }
        .mapTo(HashSet()) { it.source.id }
    assertTrue(removed.isNotEmpty())
    return object : SourceArt by artwork {
        override val layers = artwork.layers.filterNot { it.id in removed }
    }
}

private fun withLeftHighlight(document: WorkspaceDocument): Pair<WorkspaceDocument, Set<String>> {
    val analysis = RigGenerationSource.analyze(document.source, PipelineConfig(meshSpacing = 40))
    val irides = analysis.layers.filter {
        it.semantic.tag == SemanticTag.IRIDES && it.semantic.side == Side.LEFT &&
            it.source.visible && it.opaquePixels > 0
    }
    val existing = irides.filter { it.semantic.role == PartRole.HIGHLIGHT }.mapTo(HashSet()) { it.source.id.raw }
    if (existing.isNotEmpty()) {

        return document to existing
    }
    val iris = irides.first { it.semantic.role == null }.source
    val size = (minOf(iris.bounds.width, iris.bounds.height) / 4).coerceAtLeast(4)
    val id = LayerId("single-eye-left-glint")
    val bounds = LayerBounds(iris.bounds.left + iris.bounds.width / 3, iris.bounds.top + iris.bounds.height / 3, size, size)
    val raster = LayerRaster(size, size, ByteArray(size * size * 4) { -1 })
    val glint = WorkspaceSourceLayer(id, id.raw, iris.groupPath, SourceLayerKind.Raster, true, iris.order,
        bounds, 1f, true, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, false)
    val artwork = document.source
    val source = object : SourceArt by artwork {
        override val layers = artwork.layers.flatMap { layer ->
            if (layer.id == iris.id) listOf(glint, layer) else listOf(layer)
        }.mapIndexed { index, layer -> WorkspaceSourceLayer.copyOf(layer, index) }
    }
    val classification = LayerClassificationOverride(LayerType.PRESET, SemanticTag.IRIDES, Side.LEFT, role = PartRole.HIGHLIGHT)

    return document.copy(source = source, layerOverrides = document.layerOverrides + (id.raw to classification)) to setOf(id.raw)
}
