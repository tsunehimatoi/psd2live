package io.github.psd2live.project

import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class RigModifierPipelineTest {
    @TempDir lateinit var temp: Path
    private val parameters = listOf(RigParameterEdit("ParamInput", "Input", -1f, 1f, 0f, created = true),
        RigParameterEdit("ParamOutput", "Output", -1f, 1f, 0f, created = true))
    private fun config(stack: RigModifierStack?) = PipelineConfig(atlasSize = 64, meshSpacing = 8,
        rigEdits = RigEditOverlay(parameterEdits = parameters, modifierStack = stack?.toJson()))

    @Test fun emptyStackBuildsOrdinaryAuthorContentWithoutAnyImplicitPresets() {
        val source = SyntheticProject(temp, 1, 12, 1).tree.head().snapshot.source
        val pipeline = PSD2LivePipeline()
        val preview = pipeline.buildPreview(source, config(RigModifierStack()))
        assertTrue(preview.rig.puppet.deformers.isEmpty())
        val ir = RigIrCompiler.compile(preview)
        assertTrue(ir.physics.groups.isEmpty())
        assertTrue(ir.clips.isEmpty())
        assertTrue(ir.parameters.any { it.id == "ParamInput" })
        assertTrue(ir.parameters.any { it.id == "ParamOutput" })
        assertEquals(setOf("ParamInput", "ParamOutput"), ir.parameters.map { it.id }.toSet())
        assertTrue(ir.parts.isEmpty(), "an empty stack cannot create semantic binding groups")
        assertTrue(ir.restPose.isEmpty())
        assertTrue(ir.parameterRoles.isEmpty())
        assertFalse(pipeline.canFastUpdateRig(preview, source, preview.config))
    }

    @Test fun emptyStacksKeepSourceFoldersAndClippingRatherThanImplicitSemanticRelations() {
        val source = SyntheticProject(temp, 2, 12, 1).tree.head().snapshot.source
        val layers = source.layers.mapIndexed { index, layer -> (layer as WorkspaceSourceLayer).copy(
            groupPath = "Art/Eyes", clipped = index == 0, name = if (index == 0) "Iris" else "White") }
        val art = WorkspaceSourceArt(source.widthPx, source.heightPx, layers, emptyList())
        val config = config(RigModifierStack()).copy(rigEdits = RigEditOverlay(modifierStack = RigModifierStack().toJson()),
            layerOverrides = mapOf(layers[0].id.raw to LayerClassificationOverride(LayerType.TOGGLE, SemanticTag.IRIDES, Side.LEFT,
                parameter = "ParamVisible"), layers[1].id.raw to LayerClassificationOverride(LayerType.PRESET, SemanticTag.EYEWHITE, Side.LEFT)))
        val pipeline = PSD2LivePipeline()
        val model = pipeline.buildPreview(art, config)
        val ir = RigIrCompiler.compile(model)
        assertTrue(ir.parameters.isEmpty())
        assertEquals(setOf("Art", "Eyes"), ir.parts.map { it.name }.toSet())
        val first = model.rig.layerIdByDrawableId.entries.single { it.value == layers[0].id.raw }.key
        val second = model.rig.layerIdByDrawableId.entries.single { it.value == layers[1].id.raw }.key
        assertEquals(listOf(second), ir.meshes.single { it.id == first }.maskedBy)
        assertTrue(ir.meshes.all { it.channels.isEmpty() && it.offsets == null && it.shapes.isEmpty() })
        val unclipped = art.copy(layers = listOf(layers[0].copy(clipped = false), layers[1]))
        assertTrue(RigIrCompiler.compile(pipeline.buildPreview(unclipped, config)).meshes.all { it.maskedBy.isEmpty() },
            "an iris's semantic tag must not silently recreate a binding mask")
    }

    @Test fun ordinaryParametersDoNotImplicitlyRecreateRemovedPresetPhysics() {
        val source = SyntheticProject(temp, 1, 12, 1).tree.head().snapshot.source
        val config = config(RigModifierStack())
        val overlay = config.rigEdits.copy(parameterEdits = parameters + listOf(
            RigParameterEdit("ParamEyeLOpen", "Open", 0f, 1f, 1f, created = true),
            RigParameterEdit("ParamEyeLBounce", "Bounce", -1f, 1f, 0f, created = true)))
        val preview = PSD2LivePipeline().buildPreview(source, config.copy(rigEdits = overlay))
        assertTrue(RigIrCompiler.compile(preview).physics.groups.isEmpty())
    }

    @Test fun returningToALegacyRevisionDoesNotMergeOrCheckpointTheStackResultIntoIt(): Unit = runBlocking<Unit> {
        val document = SyntheticProject(temp, 1, 12, 1).tree.head().snapshot.copy(settings = WorkspaceSettingsCodec.encode(config(null)),
            rigEdits = config(null).rigEdits)
        val stacked = document.copy(rigEdits = document.rigEdits.copy(modifierStack = RigModifierStack().toJson()))
        val builder = WorkspacePreviewBuilder()
        val preview = builder.build(stacked)
        val restored = builder.build(document, preview)
        assertNull(restored.config.rigEdits.modifierStack)
        assertEquals(document.rigEdits.authoringJournal, restored.config.rigEdits.authoringJournal)
        val cold = PSD2LivePipeline().buildPreview(document.source, document.config())
        assertEquals(RigIrCompiler.compile(cold), RigIrCompiler.compile(restored))
    }

    @Test fun workspacePreviewColdRebuildAndRuntimeExportShareTheExplicitStackResult(): Unit = runBlocking<Unit> {
        val project = SyntheticProject(temp, 1, 12, 1)
        val source = project.tree.head().snapshot
        val pipeline = PSD2LivePipeline()
        val empty = pipeline.buildPreview(source.source, config(RigModifierStack()))
        val target = ModifierTarget("mesh", empty.rig.puppet.drawables.single().id.raw)
        val rule = RigPhysicsEdit("original", "Physics", listOf(PhysicsInput("ParamInput")), listOf(PhysicsOutput("ParamOutput")))
        val clip = MotionClip("original", "Motion", curves = listOf(MotionCurve("ParamInput", listOf(MotionKey(0f, 0f), MotionKey(1f, 1f)))))
        val stack = RigModifierStack().add("physics", 1, listOf(target), rule.toJson())
            .add("motion", 1, listOf(target), MotionClips.toJson(clip))
        val config = config(stack)
        val document = source.copy(rigEdits = config.rigEdits, settings = WorkspaceSettingsCodec.encode(config))
        val preview = WorkspacePreviewBuilder().build(document)
        val expected = RigIrCompiler.compile(preview)
        assertEquals(listOf(stack.instances[0].outputId("physics")), expected.physics.groups.map { it.id })
        assertEquals(listOf(stack.instances[1].outputId("motion")), expected.clips.map { it.id })
        val report = preview.rig.modifierReport!!.getValue("entries").jsonArray
        assertEquals(listOf("m1", "m2"), report.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertTrue(report.all { it.jsonObject.getValue("status").jsonPrimitive.content == "active" })
        val cold = PSD2LivePipeline().buildPreview(document.source, document.config())
        assertEquals(expected, RigIrCompiler.compile(cold))
        assertTrue(preview.runtimeBundle.assets.any { it.path.endsWith(".physics3.json") })
        assertTrue(preview.runtimeBundle.assets.any { it.path.endsWith(".motion3.json") })
        val physicsAsset = preview.runtimeBundle.assets.single { it.path.endsWith(".physics3.json") }
        assertEquals(expected.physics.groups, Physics3Json.read(physicsAsset.bytes.decodeToString()).settings.map(Physics3Json::group))
        val motionAsset = preview.runtimeBundle.assets.single { it.path.endsWith(".motion3.json") }
        val motion = Json.parseToJsonElement(motionAsset.bytes.decodeToString()).jsonObject
        assertEquals("ParamInput", motion.getValue("Curves").jsonArray.single().jsonObject.getValue("Id").jsonPrimitive.content)
        val rebuilt = pipeline.rebuildPreview(preview, preview.config)
        assertEquals(expected, RigIrCompiler.compile(rebuilt))
        val revision = WorkspaceRevisions.of(document)
        project.tree.commit(project.tree.head().node.id, document, revision, revision, "Add modifiers", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val saved = temp.resolve("stack.psd2live")
        ProjectRepository(writeHeadCache = false).save(project.capture(), saved)
        ProjectRepository().open(saved).use { reopened ->
            val restored = WorkspacePreviewBuilder().build(reopened.history.head().snapshot)
            assertEquals(expected, RigIrCompiler.compile(restored))
        }
        val disabled = stack.update("m2") { it.copy(enabled = false) }
        val changed = document.copy(rigEdits = document.rigEdits.copy(modifierStack = disabled.toJson()))
        val refreshed = WorkspacePreviewBuilder().build(changed, preview)
        assertTrue(RigIrCompiler.compile(refreshed).clips.isEmpty())
        assertEquals(expected.physics, RigIrCompiler.compile(refreshed).physics)
        assertFailsWith<IllegalArgumentException> {
            io.github.psd2live.application.WorkspaceSettingsIntent.parse(document, preview, buildJsonObject { put("headStrength", 0.5f) })
        }
    }
}
