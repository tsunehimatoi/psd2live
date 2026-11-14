package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.SyntheticProject
import io.github.psd2live.util.*
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WorkspacePresentationOriginTest {
    @TempDir lateinit var temp: Path
    private fun capture(): WorkspaceCapture<RigPreviewModel> = kotlinx.coroutines.runBlocking {
        val doc = SyntheticProject(temp.resolve("source"), 1, 8, 1).tree.head().snapshot
        val model = WorkspacePreviewBuilder().build(doc)
        val edit = WorkspaceEditCorrelation(9, null, "replace", "project", "load", "before", "old", 10)
        val build = WorkspaceBuildCorrelation(edit, 2, "candidate-before-normalization")
        WorkspaceCapture("project", "state", "head", "adopted", doc, model, JsonObject(emptyMap()), true, "load",
            WorkspacePublishedObservation(edit, build, "state", "adopted", 20))
    }

    @Test fun exactCapturedModelCopiesAdoptedPublicationWithoutRenamingItsBuildCandidate() {
        val capture = capture()
        val origin = assertNotNull(WorkspaceTextureView(capture).presentationOrigin(capture.model))
        assertSame(capture.observation, origin.observation)
        assertEquals("adopted", origin.revision)
        assertEquals("candidate-before-normalization", origin.observation!!.build!!.candidateRevision)
    }

    @Test fun runtimeBundleCopiesAndReplacedModelsCannotBorrowTheSourceObservation() {
        val capture = capture()
        val view = WorkspaceTextureView(capture)
        assertNull(view.presentationOrigin(capture.model.copy()))
        assertNull(view.presentationOrigin(capture.model.copy(config = capture.model.config.copy(generatePhysics = true))))
        val newer = capture.copy(model = capture.model.copy(), state = "next", observation = null)
        assertNull(WorkspaceTextureView(newer).presentationOrigin(capture.model))
    }

    @Test fun changedStateReloadOrProjectDropsObservationEvenWhenTheModelAndRevisionAreUnchanged() {
        val capture = capture()
        for (changed in listOf(capture.copy(state = "next"), capture.copy(loadingIdentity = "reloaded"), capture.copy(projectId = "other"))) {
            assertNull(assertNotNull(WorkspaceTextureView(changed).presentationOrigin(capture.model)).observation)
        }
    }
}
