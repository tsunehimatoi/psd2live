package io.github.psd2live.project

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class ProjectRepositoryTest {
    @TempDir lateinit var temporary: Path

    private fun capture(): ProjectSaveCapture {
        val raster = LayerRaster(2, 2, ByteArray(16) { if (it % 4 == 3) 255.toByte() else 40 })
        val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster, true,
            0, LayerBounds(1, 1, 2, 2), 1f, false, LayerBlend.Normal, ChannelMask.ALL, raster,
            null, null, true)
        val root = WorkspaceDocument(WorkspaceSourceArt(4, 4, listOf(layer), emptyList()),
            emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)
        val revision = WorkspaceRevisions.of(root)
        val tree = WorkspaceHistoryTree(root, revision, revision)
        val edit = root.copy(layerVisibility = mapOf("art" to false))
        val next = WorkspaceRevisions.of(edit)
        tree.commit(tree.head().node.id, edit, next, next, "Hide artwork", "agent")
        val presentation = buildJsonObject {
            putJsonArray("parameterSnapshots") { add(buildJsonObject { put("id", "pose"); put("name", "Rest") }) }
            putJsonArray("historyAnnotations") { add(buildJsonObject { put("node", tree.head().node.id); put("text", "Ready") }) }
            putJsonArray("logEntries") { add(buildJsonObject {
                put("text", "Saved"); put("image", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
            }) }
        }
        return ProjectSaveCapture("project", tree.state(), presentation, null,
            WorkspaceStore(temporary.resolve("workspace")))
    }

    @Test fun generatedSourceHistoryAndAuxiliaryDataRoundTripWithoutDesktop() = runBlocking {
        val before = capture()
        val repository = ProjectRepository()
        val target = temporary.resolve("artwork.psd2live")
        assertEquals(before.history.headNodeId, repository.save(before, target))
        lateinit var extracted: Path
        repository.open(target).use { opened ->
            assertEquals("project", opened.projectId)
            assertEquals(before.history.headNodeId, opened.history.head().node.id)
            assertEquals(before.history.selections.map { it.node }, opened.history.state().selections.map { it.node })
            assertEquals(before.history.selections.map { WorkspaceRevisions.of(it.snapshot) },
                opened.history.state().selections.map { WorkspaceRevisions.of(it.snapshot) })
            assertEquals(before.presentation, opened.presentation)
            assertTrue(Files.isRegularFile(opened.source))
            extracted = opened.source.parent.parent
        }
        assertFalse(Files.exists(extracted), "Unadopted extraction must be disposed")
    }

    @Test fun renderedViewImagesStayOutOfTheSavedProject() = runBlocking {
        val before = capture()
        val project = before.store.projectRoot(before.projectId)
        Files.createDirectories(project.resolve("view-images"))
        Files.write(project.resolve("view-images/rendered.png"), byteArrayOf(1, 2, 3))
        val target = temporary.resolve("artwork.psd2live")
        ProjectRepository().save(before, target)
        val entries = java.util.zip.ZipFile(target.toFile()).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.none { "view-images" in it }, entries.toString())
    }

    @Test fun transferredExtractionSurvivesCloseUntilItsOwnerDisposesIt() = runBlocking {
        val repository = ProjectRepository()
        val target = temporary.resolve("adopted.psd2live")
        repository.save(capture(), target)
        val directory = repository.open(target).use { opened ->
            val transferred = opened.transferDirectory()
            assertFailsWith<IllegalStateException> { opened.transferDirectory() }
            transferred
        }
        assertTrue(Files.isDirectory(directory))
        ProjectArchive.deleteTemporaryDirectory(directory)
        assertFalse(Files.exists(directory))
    }

    @Test fun failedSaveCleansStagingAndKeepsExistingDestination() = runBlocking {
        val target = temporary.resolve("existing.psd2live")
        val original = byteArrayOf(9, 8, 7)
        Files.write(target, original)
        lateinit var staging: Path
        val repository = ProjectRepository { root, _, _ -> staging = root; error("Injected write failure") }
        assertFailsWith<IllegalStateException> { repository.save(capture(), target) }
        assertContentEquals(original, Files.readAllBytes(target))
        assertFalse(Files.exists(staging))
    }

    @Test fun cancellationBeforeArchiveReplacementKeepsOriginalFile() = runBlocking {
        val directory = Files.createTempDirectory("psd2live-project-")
        try {
            Files.writeString(directory.resolve("payload.txt"), "new payload")
            val target = temporary.resolve("original.psd2live")
            val original = byteArrayOf(3, 2, 1)
            Files.write(target, original)
            assertFailsWith<CancellationException> {
                ProjectArchive.write(directory, target, "project") { throw CancellationException("Cancelled before commit") }
            }
            assertContentEquals(original, Files.readAllBytes(target))
            Files.list(temporary).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".tmp") }) }
        } finally { ProjectArchive.deleteTemporaryDirectory(directory) }
    }

    @Test fun successfulArchiveCommitIsRecordedBeforeLateCancellation() = runBlocking {
        val before = capture()
        val caller = Job()
        var committed = false
        val target = temporary.resolve("committed.psd2live")
        val operation = CoroutineScope(Dispatchers.Default + caller).async {
            ProjectRepository().save(before, target) {
                committed = true
                caller.cancel()
            }
        }
        assertFailsWith<CancellationException> { operation.await() }
        operation.join()
        assertTrue(committed)
        ProjectRepository().open(target).use { opened ->
            assertEquals(before.history.headNodeId, opened.history.head().node.id)
        }
    }
}
