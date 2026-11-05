package io.github.psd2live.core

import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AppliedMotionFileTest {
    @TempDir lateinit var temp: Path

    @Test fun oldRevisionIdentityPreservesFieldLikeTextInClipNamesAndEvents() {
        val clip = MotionClip("old", "Name, fileStem=null)", events = listOf(MotionEvent(0f, "Value, fileStem=null)")))
        val overlay = RigEditOverlay.Empty.copy(motionClips = listOf(clip))
        val oldClip = clip.toString().removeSuffix(", fileStem=null)") + ")"
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

    @Test fun newFileMetadataUsesANewerClipsSchemaAndPersistsInTheWorkingStore() {
        val project = SyntheticProject(temp, 1, 8, 1)
        val before = project.tree.head().snapshot
        val ordinary = MotionClip("motion", "Original")
        val clip = ordinary.copy(fileStem = "modifier_6d31")
        val document = before.copy(rigEdits = before.rigEdits.copy(motionClips = listOf(clip)))
        val revision = WorkspaceRevisions.of(document)
        assertNotEquals(WorkspaceRevisions.of(document.copy(rigEdits = document.rigEdits.copy(motionClips = listOf(ordinary)))), revision)
        project.tree.commit(project.tree.head().node.id, document, revision, revision, "Apply motion", "user")
        project.store.persistHistory(SyntheticProject.PROJECT, project.tree.state())
        val restored = WorkspaceStore(project.store.root).loadHistory(SyntheticProject.PROJECT)!!.head().snapshot
        assertEquals(clip, restored.rigEdits.motionClips.single())
        assertEquals(revision, WorkspaceRevisions.of(restored))
        val clips = buildJsonObject { put("motions", JsonArray(listOf(MotionClips.toJson(clip)))) }
        assertEquals(ProjectFormatV2.NODE_SCHEMA_MOTION_FILES, ProjectFormatV2.clipsSchema(clips))
        val index = ProjectFormatV2.split(temp.resolve("split"), buildJsonObject { put("rigEdits", clips) })
        val joined = ProjectFormatV2.join(temp.resolve("split"), index).getValue("rigEdits").jsonObject
        assertEquals(clips.getValue("motions"), joined.getValue("motions"))
    }
}
