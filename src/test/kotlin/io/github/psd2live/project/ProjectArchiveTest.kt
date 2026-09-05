package io.github.psd2live.project

import io.github.psd2live.agent.*
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.ui.state.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ProjectArchiveTest {
    private fun temp() = Files.createTempDirectory("psd2live-project-")
    private val pixels = byteArrayOf(11, 22, 33, 0, 44, 55, 66, 127, 77, 88, 99, -1, 3, 2, 1, 0)
    private fun document() = AgentWorkspaceDocument(
        WorkspaceSourceArt(2, 2, listOf(WorkspaceSourceLayer(
            LayerId("layer-1"), "原画", "", SourceLayerKind.Raster, true, 0, LayerBounds(0, 0, 2, 2),
            1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(2, 2, pixels), null, null, false,
        )), emptyList()), emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty,
        WorkspaceStateCodec.settings(PSD2LiveState(meshSpacing = 32)),
    )

    @Test fun `portable archive retains all branches and exact transparent RGB without external cache`() {
        val work = temp(); val destination = temp()
        var opened: Path? = null
        try {
            val doc = document()
            val tree = WorkspaceHistoryTree(doc, "r0", "h0")
            val root = tree.head().node.id
            tree.commit(root, doc.copy(deletedLayerIds = setOf("layer-1")), "r1", "h1", "delete")
            tree.checkout(root)
            val branch = tree.commit(root, doc.copy(settings = WorkspaceStateCodec.settings(PSD2LiveState(meshSpacing = 96))), "r2", "h2", "config")
            repeat(100) { tree.commit(tree.head().node.id, branch.snapshot, "r2", "h2", "save") }
            AgentWorkspaceStore(work.resolve("workspace")).persistHistory("stable-id", tree.state())
            Files.writeString(work.resolve("source.psd"), "source bytes")
            val target = destination.resolve("中文工程.psd2live")
            ProjectArchive.write(work, target, "stable-id")
            ProjectArchive.deleteTemporaryDirectory(work)
            opened = ProjectArchive.extract(target)
            val restored = AgentWorkspaceStore(opened.resolve("workspace")).loadHistory("stable-id")!!
            assertEquals(tree.nodes(), restored.nodes())
            assertEquals(tree.head().node.id, restored.head().node.id)
            assertContentEquals(pixels, restored.selectionAt(root).snapshot.source.layers.single().raster.rgba)
            assertEquals(96, restored.head().snapshot.settings.getValue("meshSpacing").jsonPrimitive.int)
            assertEquals(1L, Files.walk(opened).use { paths -> paths.filter { it.toString().endsWith(".png") }.count() })
            assertTrue(Files.walk(opened).use { paths -> paths.filter { it.toString().endsWith(".json") }.allMatch { Files.readString(it).contains('\n') } })
        } finally {
            if (Files.exists(work)) ProjectArchive.deleteTemporaryDirectory(work)
            opened?.let(ProjectArchive::deleteTemporaryDirectory)
            ProjectArchive.deleteTemporaryDirectory(destination)
        }
    }

    @Test fun `workspace restores annotations parameters layout isolation and logs`() {
        val state = PSD2LiveState(workspaceSplitRatio = .73f, canvasZoom = 2.5f, canvasPanX = 42f,
            selectedLayerId = "layer-1", activeWorkspaceTab = WorkspaceTab.HISTORY,
            parameterValues = mapOf(ParameterId("AngleX") to 12f), lockedParameters = setOf(ParameterId("AngleX")),
            isolatedLayerId = "layer-1", isolationSnapshot = mapOf("layer-1" to false),
            historyAnnotations = mapOf("node-1" to HistoryAnnotation("名称", "备注", true)),
            logEntries = listOf(AppLogEntry(message = "test", imageBytes = pixels)),
            projectSaving = true, isAnalyzing = true,
        )
        val restored = WorkspaceStateCodec.decode(WorkspaceStateCodec.encode(state))
        assertEquals(.73f, restored.workspaceSplitRatio)
        assertEquals(state.parameterValues, restored.parameterValues)
        assertEquals(state.lockedParameters, restored.lockedParameters)
        assertEquals(state.historyAnnotations, restored.historyAnnotations)
        assertEquals(state.isolationSnapshot, restored.isolationSnapshot)
        assertContentEquals(pixels, restored.logEntries.single().imageBytes)
        assertFalse(restored.projectSaving)
        assertFalse(restored.isAnalyzing)
    }

    @Test fun `archive rejects traversal unsupported versions and corrupt resources`() {
        val root = temp()
        try {
            val zip = root.resolve("bad.psd2live")
            for (name in listOf("../outside", "/absolute", "C:/outside", "folder\\outside")) {
                ZipOutputStream(Files.newOutputStream(zip)).use { it.putNextEntry(ZipEntry(name)); it.write(1); it.closeEntry() }
                assertFailsWith<IllegalArgumentException> { ProjectArchive.extract(zip) }
            }
            fun invalid(version: Int, checksum: String) {
                ZipOutputStream(Files.newOutputStream(zip)).use {
                    it.putNextEntry(ZipEntry("manifest.json"))
                    it.write("""{"format":"PSD2Live","version":$version,"files":{"payload":"$checksum"}}""".toByteArray()); it.closeEntry()
                    it.putNextEntry(ZipEntry("payload")); it.write(1); it.closeEntry()
                }
                assertFailsWith<IllegalArgumentException> { ProjectArchive.extract(zip) }
            }
            invalid(999, "bad")
            invalid(1, "bad")
        } finally { ProjectArchive.deleteTemporaryDirectory(root) }
    }

    @Test fun `failed save does not replace existing destination`() {
        val root = temp(); val output = temp()
        try {
            Files.writeString(root.resolve("payload"), "new")
            // A nonempty destination directory cannot be replaced with an archive.
            val destination = Files.createDirectory(output.resolve("existing.psd2live"))
            Files.writeString(destination.resolve("old"), "original")
            assertFails { ProjectArchive.write(root, destination, "id") }
            assertEquals("original", Files.readString(destination.resolve("old")))
            assertEquals(1L, Files.list(output).use { it.count() })
        } finally { ProjectArchive.deleteTemporaryDirectory(root); ProjectArchive.deleteTemporaryDirectory(output) }
    }
}
