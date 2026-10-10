package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.psd.PsdReader
import org.umamo.interop.cmo3.Cmo3Import
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.*

class WorkspaceExportSessionTest {
    @TempDir lateinit var temporary: Path

    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Drive", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                put("parameter_id", "Drive"); put("name", "Original drive"); put("min", -30); put("max", 30)
            })), MutationAuthor.USER)
        return runtime
    }

    @Test fun modelAndPsdUseOneCommittedInputAfterAnotherProjectIsInstalled() = runBlocking<Unit> {
        val runtime = fixture(); val original = runtime.capture()
        val session = WorkspaceExportSession(original, "Captured.psd")
        val changed = WorkspaceDocumentCommands(runtime).execute(original.projectId, original.state, "Rename", listOf(
            WorkspaceDocumentOperation("parameter_update", buildJsonObject { put("parameter_id", "Drive"); put("name", "Later drive") })), MutationAuthor.AGENT).capture
        runtime.install(changed.state, "replacement", changed.document, changed.model, discardUnsaved = true)
        val replacement = runtime.capture(); val history = runtime.history()
        val fractions = mutableListOf<Float>()
        val exported = withContext(WorkspaceJobContext { fraction, _ -> fractions += fraction }) {
            session.model(temporary.resolve("model"))
        }
        assertTrue(fractions.isNotEmpty() && fractions.last() >= 0.8f)
        assertTrue(fractions.zipWithNext().all { (before, after) -> before <= after })
        validateWorkspaceResult("project_export_model", WorkspaceJobResultSchemas.result("project_export_model"), exported)
        assertEquals(original.state, exported.getValue("state").jsonPrimitive.content)
        assertEquals(original.revision, exported.getValue("revision").jsonPrimitive.content)
        val cmo3 = exported.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
            .single { it.toString().endsWith(".cmo3") }
        val readBack = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo3)).root as CModelSource)
        assertEquals("Original drive", readBack.parameters.single { it.id.raw == "Drive" }.name)
        assertEquals(original.model.rig.puppet.drawables.map { it.id }, readBack.drawables.map { it.id })
        val psd = temporary.resolve("captured.psd")
        val output = session.psd(psd, 1, false)
        validateWorkspaceResult("project_export_psd", WorkspaceJobResultSchemas.result("project_export_psd"), output)
        assertEquals(original.state, output.getValue("state").jsonPrimitive.content)
        assertContentEquals(original.document.source.layers.single().raster.rgba,
            PsdReader.read(Files.readAllBytes(psd)).layers.single().raster.rgba)
        assertEquals(replacement, runtime.capture()); assertEquals(history, runtime.history())
        Files.list(temporary).use { files -> assertFalse(files.anyMatch { it.fileName.toString().startsWith(".psd2live-") }) }
    }

    @Test fun theModelExportWritesTheCommittedModelWithoutRebuildingIt() = runBlocking<Unit> {
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }, rebuildFrom = { document, previous -> builder.build(document, previous) })
        val root = simulationFixture(runtime)
        val committed = WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Drive", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Drive"); put("name", "Drive") }),
            WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("exportMoc3", true) } })),
            MutationAuthor.USER).capture
        // A cold build from the journal's checkpoint, as a reopened project has: no generated base.
        MaterializedRigStore.clear()
        val cold = committed.copy(model = builder.build(committed.document))
        assertFalse(cold.model.sources.baseKnown)
        val exported = WorkspaceExportSession(cold, "Committed.psd").model(temporary.resolve("committed"))
        val moc3 = exported.getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
            .single { it.toString().endsWith(".moc3") }
        assertContentEquals(cold.model.runtimeBundle.assets.single { it.path.endsWith(".moc3") }.bytes, Files.readAllBytes(moc3),
            "the file is the preview's own compile")
        assertFalse(cold.model.sources.baseKnown, "exporting never generated the base")
    }

    @Test fun publishingFailureRestoresOverwrittenFilesAndRemovesTransactionBackups() {
        val stage = Files.createDirectories(temporary.resolve("stage")); val target = Files.createDirectories(temporary.resolve("target"))
        val files = (1..2).map { index ->
            val source = stage.resolve("$index.txt"); val destination = target.resolve("$index.txt")
            Files.writeString(source, "new $index"); Files.writeString(destination, "old $index")
            source to destination
        }
        var moves = 0
        assertFailsWith<java.io.IOException> {
            WorkspaceExportFiles.publish(files) { source, destination ->
                if (++moves == 2) throw java.io.IOException("Second file failed")
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING); Unit
            }
        }
        assertEquals("old 1", Files.readString(files[0].second)); assertEquals("old 2", Files.readString(files[1].second))
        Files.list(target).use { paths -> assertEquals(setOf("1.txt", "2.txt"), paths.map { it.fileName.toString() }.toList().toSet()) }
    }

    @Test fun aCancelledExportBeforePublicationWritesNoFilesAndLateCancellationRetainsTheExactResult() = runBlocking<Unit> {
        val runtime = fixture(); val original = runtime.capture(); val history = runtime.history()
        val early = temporary.resolve("early.psd")
        val cancelled = Job().apply { cancel() }
        assertFailsWith<CancellationException> { withContext(cancelled) { WorkspaceExportSession(original, "Art.psd").psd(early, 1, false) } }
        assertFalse(Files.exists(early))
        val failed = temporary.resolve("failed.psd")
        assertFailsWith<java.io.IOException> {
            WorkspaceExportSession(original, "Art.psd", publish = { throw java.io.IOException("Publisher rejected") }).psd(failed, 1, false)
        }
        assertFalse(Files.exists(failed))
        WorkspaceJobs().use { jobs ->
            val target = temporary.resolve("completed.psd")
            val started = jobs.start("project_export_psd", original.projectId, original.state,
                WorkspaceJobResultSchemas.result("project_export_psd")) {
                val owner = requireNotNull(currentCoroutineContext()[Job])
                val session = WorkspaceExportSession(original, "Art.psd", publish = {
                    WorkspaceExportFiles.publish(it); owner.cancel(CancellationException("Cancellation after file publication"))
                })
                WorkspaceOperationOutput(session.psd(target, 1, false))
            }
            val terminal = withTimeout(60000) {
                var result = jobs.wait(started.id)
                while (!result.status.terminal) result = jobs.wait(started.id)
                result
            }
            assertEquals(WorkspaceJobStatus.COMPLETED, terminal.status)
            assertEquals(original.state, assertNotNull(terminal.result).data.getValue("state").jsonPrimitive.content)
            assertEquals(Files.size(target), terminal.result!!.data.getValue("bytes").jsonPrimitive.long)
            assertContentEquals(original.document.source.layers.single().raster.rgba,
                PsdReader.read(Files.readAllBytes(target)).layers.single().raster.rgba)
        }
        assertEquals(original, runtime.capture()); assertEquals(history, runtime.history())
    }
}
