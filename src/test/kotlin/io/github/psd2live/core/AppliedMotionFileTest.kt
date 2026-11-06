package io.github.psd2live.core

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceModifierEdits
import io.github.psd2live.core.modifier.ModifierTarget
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.*
import io.github.psd2live.targets.cubism.Cubism3Json
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AppliedMotionFileTest {
    @TempDir lateinit var temp: Path

    @Test fun generatedMotionKeepsItsOutputAfterApplyingAndReopening() {
        val project = SyntheticProject(temp, 1, 12, 1)
        val pipeline = PSD2LivePipeline()
        val overlay = RigEditOverlay.Empty.copy(
            parameterEdits = listOf(RigParameterEdit("Drive", "Drive", -1f, 1f, 0f, created = true)),
            modifierStack = RigModifierStack().toJson())
        val config = PipelineConfig(atlasSize = 64, meshSpacing = 8, rigEdits = overlay)
        val document = project.tree.head().snapshot.copy(settings = WorkspaceSettingsCodec.encode(config), rigEdits = overlay)
        val base = pipeline.buildPreview(document.source, document.config())
        val target = ModifierTarget("mesh", base.rig.puppet.drawables.single().id.raw)
        val clip = MotionClip("draft", "Turn", duration = 1f,
            curves = listOf(MotionCurve("Drive", listOf(MotionKey(0f, 0f), MotionKey(1f, .5f)))),
            events = listOf(MotionEvent(.5f, "halfway")))
        val stack = RigModifierStack().add("motion", 1, listOf(target), MotionClips.toJson(clip))
        val instance = stack.instances.single()
        val generated = document.copy(rigEdits = overlay.copy(modifierStack = stack.toJson()))
        val preview = pipeline.buildPreview(generated.source, generated.config())
        val expected = RigIrCompiler.compile(preview)
        val output = expected.clips.single()
        assertEquals(instance.outputId("motion"), output.id)
        assertEquals(RigIrCompiler.compile(base), expected.copy(clips = emptyList()))

        val applied = WorkspaceModifierEdits.apply(WorkspaceDocumentOperation("modifier_apply",
            buildJsonObject { put("id", instance.id) }), generated, preview)
        val revision = WorkspaceRevisions.of(applied)
        project.tree.commit(project.tree.head().node.id, applied, revision, revision, "Apply motion", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val restored = WorkspaceStore(project.store.root).loadHistory(SyntheticProject.PROJECT)!!.head().snapshot
        assertTrue(RigModifierStack.fromJson(restored.rigEdits.modifierStack!!).instances.isEmpty())
        assertEquals(output.id, restored.rigEdits.motionClips.single().id)
        assertEquals(output.file, restored.rigEdits.motionClips.single().fileStem)
        val rebuilt = RigIrCompiler.compile(PSD2LivePipeline().buildPreview(restored.source, restored.config()))
        assertEquals(expected, rebuilt)
        val export = assertNotNull(Cubism3Json.motion3(rebuilt.clips.single(), rebuilt.parameters.map { it.id }.toSet()))
        val json = Json.parseToJsonElement(export).jsonObject
        assertEquals("Drive", json.getValue("Curves").jsonArray.single().jsonObject.getValue("Id").jsonPrimitive.content)
        assertEquals("halfway", json.getValue("UserData").jsonArray.single().jsonObject.getValue("Value").jsonPrimitive.content)
    }

    @Test fun oldRevisionIdentityPreservesFieldLikeTextInClipNamesAndEvents() {
        val clip = MotionClip("old", "Name, fileStem=null)", events = listOf(MotionEvent(0f, "Value, fileStem=null)")))
        val overlay = RigEditOverlay.Empty.copy(motionClips = listOf(clip))
        val oldClip = (clip.toString().removeSuffix(", capturedRanges={})") + ")").removeSuffix(", fileStem=null)") + ")"
        val oldOverlay = overlay.toString().replace("[${clip}]", "[$oldClip]")
            .replace(", hitAreas=[]", "").replace(", interiorEffects=[]", "")
            .replace(", pose=null", "").replace(", modifierStack=null", "").replace(", expressions=null", "")
        val expected = "revision-" + WorkspaceRevisions.sha256(("|settings:{}|rig:$oldOverlay|canvas:1x1").encodeToByteArray())
        val document = WorkspaceDocument(WorkspaceSourceArt(1, 1, emptyList(), emptyList()),
            emptyMap(), emptySet(), emptyMap(), emptyMap(), overlay)
        repeat(3) {
            assertEquals(expected, WorkspaceRevisions.reference(document))
            assertEquals(expected, WorkspaceRevisions.of(document))
        }
    }

    @Test fun materializedFileNamesSurviveRenamingAndJsonRoundTrip() {
        val clip = MotionClip("motion", "Original", fileStem = "modifier_6d31")
        assertEquals(clip, MotionClips.fromJson(MotionClips.toJson(clip)))
        assertEquals("modifier_6d31", MotionClips.exportStems(listOf(clip.copy(name = "Renamed"))).getValue(clip.id))
        assertFailsWith<IllegalArgumentException> { clip.copy(fileStem = "../outside") }
        assertFailsWith<IllegalArgumentException> { MotionClips.exportStems(listOf(clip, clip.copy(id = "other"))) }
    }

    @Test fun appliedLegacyNamesRemainPlayableAndOnlyConflictWithExistingGeneratedFiles() {
        val clip = MotionClip("Blink", "Renamed", fileStem = "blink", curves = listOf(MotionCurve("ParamEyeLOpen", listOf(MotionKey(0f, 1f)))))
        assertEquals("blink", MotionClips.exportStems(listOf(clip)).getValue(clip.id))
        assertEquals(clip, OneShotMotionPlayer.clipOf("blink", null, listOf(clip)))
        assertFailsWith<IllegalArgumentException> { MotionClips.exportStems(listOf(clip), setOf("BLINK")) }
        val unnamed = MotionClip("custom", "Blink")
        assertEquals("blink2", MotionClips.exportStems(listOf(clip, unnamed)).getValue(unnamed.id))
    }

    @Test fun capturedJumpKeysSurviveSerializationWithoutNormalizingAwayTheJump() {
        val keys = listOf(MotionKey(0f, 0f), MotionKey(.4f, .02f, MotionInterpolation.LINEAR),
            MotionKey(.4f, 0f, MotionInterpolation.LINEAR), MotionKey(1f, 1f))
        val clip = MotionClip("talk", "Talk", curves = listOf(MotionCurve("Mouth", keys)),
            capturedRanges = mapOf("Mouth" to MotionParameterRange(0f, 1f)))
        val restored = MotionClips.fromJson(MotionClips.toJson(clip))
        assertEquals(clip, restored)
        assertEquals(MotionGenerator.curve(clip.curves.single()), MotionGenerator.curve(restored.curves.single()))
        assertEquals(3, MotionClips.fromJson(MotionClips.toJson(clip.copy(capturedRanges = emptyMap()))).curves.single().keys.size)
    }

    @Test fun capturedRangesPreserveControlsUntilTheParameterActuallyChanges() {
        val parameter = org.umamo.runtime.model.Parameter(org.umamo.runtime.model.ParameterId("ParamBreath"), "Breath", 0f, 1f, 0f)
        val curve = MotionCurve("ParamBreath", listOf(MotionKey(0f, 0f, outHandle = MotionHandle(y = -0.000000167f)), MotionKey(1f, 1f)))
        val original = MotionClip("motion", "Motion", curves = listOf(curve),
            capturedRanges = mapOf("ParamBreath" to MotionParameterRange(0f, 1f)))
        val restored = MotionClips.fromJson(MotionClips.toJson(original))
        assertEquals(original, restored)
        assertEquals(curve, MotionClips.reconcileParameters(listOf(restored), listOf(parameter)).single().curves.single())
        assertEquals(curve, MotionClips.withParameterRange(listOf(restored), "ParamBreath", 0f, 1f).single().curves.single())
        val changed = MotionClips.reconcileParameters(listOf(restored), listOf(parameter.copy(max = .5f))).single()
        assertEquals(.5f, changed.curves.single().keys.last().value)
        assertEquals(0f, changed.curves.single().keys.first().outHandle.y)
        assertEquals(changed, MotionClips.reconcileParameters(listOf(changed), listOf(parameter.copy(max = .5f))).single())
        val renamed = ModelSidecars.withIdRenamed(RigEditOverlay.Empty.copy(motionClips = listOf(original)),
            "parameter", "ParamBreath", "Breathing").motionClips.single()
        assertEquals(setOf("Breathing"), renamed.capturedRanges.keys)
        assertTrue(MotionClips.withoutParameter(listOf(renamed), "Breathing").single().capturedRanges.isEmpty())
        assertTrue(MotionClips.reconcileParameters(listOf(restored), emptyList()).single().capturedRanges.isEmpty())
    }

    @Test fun newFileMetadataUsesANewerClipsSchemaAndPersistsInTheWorkingStore() {
        val project = SyntheticProject(temp, 1, 8, 1)
        val before = project.tree.head().snapshot
        val ordinary = MotionClip("motion", "Original")
        val clip = ordinary.copy(fileStem = "modifier_6d31", capturedRanges = mapOf("ParamBreath" to MotionParameterRange(0f, 1f)))
        val document = before.copy(rigEdits = before.rigEdits.copy(motionClips = listOf(clip)))
        val revision = WorkspaceRevisions.of(document)
        assertNotEquals(WorkspaceRevisions.of(document.copy(rigEdits = document.rigEdits.copy(motionClips = listOf(ordinary)))), revision)
        project.tree.commit(project.tree.head().node.id, document, revision, revision, "Apply motion", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val restored = WorkspaceStore(project.store.root).loadHistory(SyntheticProject.PROJECT)!!.head().snapshot
        assertEquals(clip, restored.rigEdits.motionClips.single())
        assertEquals(revision, WorkspaceRevisions.of(restored))
        val clips = buildJsonObject { put("motions", JsonArray(listOf(MotionClips.toJson(clip)))) }
        assertEquals(ProjectFormatV2.NODE_SCHEMA_MOTION_RANGES, ProjectFormatV2.clipsSchema(clips))
        val index = ProjectFormatV2.split(temp.resolve("split"), buildJsonObject { put("rigEdits", clips) })
        val joined = ProjectFormatV2.join(temp.resolve("split"), index).getValue("rigEdits").jsonObject
        assertEquals(clips.getValue("motions"), joined.getValue("motions"))
    }
}
