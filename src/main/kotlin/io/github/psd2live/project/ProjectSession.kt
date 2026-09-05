package io.github.psd2live.project

import io.github.psd2live.agent.ViewModelAgentWorkspace
import io.github.psd2live.agent.AgentWorkspaceStore
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Serializes saves, while model edits may continue against the next workspace revision. */
internal class ProjectSession(private val viewModel: PSD2LiveViewModel, private val writeArchive: (Path, Path, String) -> Unit = ProjectArchive::write) {
    private val saves = Mutex()
    suspend fun save(workspace: ViewModelAgentWorkspace, path: Path, actor: String = "user"): String {
        viewModel.projectSaveStarted()
        var staging: Path? = null
        try {
            val capture = workspace.captureProject("Save project", actor)
            return saves.withLock {
            workspace.flushProjectPersistence()
            val state = capture.uiState
            val root = withContext(Dispatchers.IO) { Files.createTempDirectory("psd2live-project-") }
            staging = root
            withContext(Dispatchers.IO) {
                val store = AgentWorkspaceStore(root.resolve("workspace"))
                store.persistHistory(capture.projectId, capture.history)
                capture.store.copyAuxiliary(capture.projectId, root.resolve("workspace").resolve(capture.projectId))
                capture.spatial.forEach { (id, spatial) -> store.persistSpatial(capture.projectId, id, spatial) }
                store.persistTasks(capture.projectId, capture.tasks)
                val original = Path.of(state.loadedInputPath ?: state.inputPath)
                require(Files.isRegularFile(original)) { "Original PSD is unavailable: $original" }
                Files.createDirectories(root.resolve("source"))
                Files.copy(original, root.resolve("source/original.psd"))
                val ui = WorkspaceStateCodec.encode(state).toMutableMap()
                ui["logEntries"] = JsonArray(ui.getValue("logEntries").jsonArray.map { entry ->
                    val log = entry.jsonObject.toMutableMap()
                    log.remove("image")?.jsonPrimitive?.content?.let { encoded ->
                        val bytes = java.util.Base64.getDecoder().decode(encoded)
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                        val imagePath = "images/$hash.png"
                        Files.createDirectories(root.resolve("images")); Files.write(root.resolve(imagePath), bytes)
                        log["imagePath"] = JsonPrimitive(imagePath)
                    }
                    JsonObject(log)
                })
                ProjectArchive.writeJson(root.resolve("workspace.json"), JsonObject(ui))
                Files.writeString(root.resolve("README.txt"), "PSD2Live project v1. Unencrypted ZIP. manifest.json inventories SHA-256 checksums. source/original.psd is the original source; workspace/ contains immutable history snapshots, PNG resources, tasks and spatial references; workspace.json restores the UI. See docs/PROJECT_FORMAT.md.\n")
                writeArchive(root, path, capture.projectId)
            }
            viewModel.projectSaveFinished(path, capture.history.headNodeId, state)
            capture.history.headNodeId
            }
        } catch (failure: Exception) {
            viewModel.projectSaveFailed(failure)
            throw failure
        } finally {
            staging?.let { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { ProjectArchive.deleteTemporaryDirectory(it) } }
        }
    }

    suspend fun open(workspace: ViewModelAgentWorkspace, path: Path) = saves.withLock {
        val expected = viewModel.state.value
        val root = withContext(Dispatchers.IO) { ProjectArchive.extract(path) }
        try {
            val manifest = ProjectArchive.readJson(root.resolve("manifest.json"))
            val id = manifest.getValue("projectId").jsonPrimitive.content
            val store = AgentWorkspaceStore(root.resolve("workspace"))
            val tree = withContext(Dispatchers.IO) { store.loadHistory(id) ?: error("Project has no history") }
            val ui = ProjectArchive.readJson(root.resolve("workspace.json")).toMutableMap()
            ui["logEntries"] = JsonArray(ui["logEntries"]?.jsonArray.orEmpty().map { entry ->
                val log = entry.jsonObject.toMutableMap()
                log.remove("imagePath")?.jsonPrimitive?.content?.let { name ->
                    val image = root.resolve(name).normalize()
                    require(!Path.of(name).isAbsolute && image.startsWith(root) && Files.isRegularFile(image)) { "Invalid log image reference" }
                    log["image"] = JsonPrimitive(java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(image)))
                }
                JsonObject(log)
            })
            val state = WorkspaceStateCodec.decode(JsonObject(ui))
            val source = root.resolve("source/original.psd")
            require(Files.isRegularFile(source)) { "Project has no original PSD" }
            workspace.installProject(id, path.toAbsolutePath().normalize(), source, state, tree, store, expected)
            // The opened archive's private extraction is the session's recovery store and source PSD.
            workspace.rememberProjectDirectory(root)
        } catch (failure: Throwable) { ProjectArchive.deleteTemporaryDirectory(root); throw failure }
    }
}
