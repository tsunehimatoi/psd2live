package io.github.psd2live.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Serializes saves, while model edits may continue against the next workspace revision. */
internal class ProjectRepository(
    /** Whether saves add the optional head cache ([ProjectHeadCache]); opens always read a valid one. */
    private val writeHeadCache: Boolean = true,
    private val writeArchive: ((Path, Path, String) -> Unit)? = null,
) {
    private val saves = Mutex()
    suspend fun save(capture: ProjectSaveCapture, path: Path, progress: ProjectProgress = ProjectProgress.NONE,
                     onCommitted: () -> Unit = {}): String {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        var staging: Path? = null
        try {
            return saves.withLock {
                val root = withContext(Dispatchers.IO) {
                    Files.createTempDirectory("psd2live-project-").also { staging = it }
                }
                withContext(Dispatchers.IO) {
                    fun staged(step: Int) = progress.report(ProjectStage.STAGING, step / 6.0)
                    staged(0)
                    // Rasters the live store holds are linked from it rather than encoded again.
                    val store = WorkspaceStore(root.resolve("workspace"), rasterSource = capture.store)
                    store.persistHistory(capture.projectId, capture.history)
                    staged(1)
                    capture.store.copyAuxiliary(capture.projectId, root.resolve("workspace").resolve(capture.projectId),
                        capture.assetCatalog, viewImages = false)
                    capture.spatial.forEach { (id, spatial) -> store.persistSpatial(capture.projectId, id, spatial) }
                    store.persistTasks(capture.projectId, capture.tasks)
                    staged(2)
                    Files.createDirectories(root.resolve("source"))
                    val original = capture.originalSource
                    if (original == null) {
                        // Generated artwork has no file-backed origin. The root source, not the edited
                        // preview, becomes the portable v1 source; history still retains every revision.
                        val source = capture.history.selections.single { it.node.parentId == null }.snapshot.source
                        Files.write(root.resolve("source/original.psd"), org.umamo.format.psd.PsdWriter.write(source))
                    } else {
                        require(Files.isRegularFile(original)) { "Original source is unavailable: $original" }
                        val sourceName = if (original.fileName.toString().endsWith(".cmo3", true)) "original.cmo3" else "original.psd"
                        ProjectArchive.copyKeepingTime(original, root.resolve("source/$sourceName"))
                    }
                    val ui = capture.presentation.toMutableMap()
                    capture.auxiliary["assetCatalog"]?.let { ui["assetCatalog"] = it }
                    ui["logEntries"] = JsonArray(ui["logEntries"]?.jsonArray.orEmpty().map { entry ->
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
                    staged(3)
                    ProjectFormatV2.pack(root, capture.projectId)
                    staged(4)
                    MaterializedRigStore.write(root, capture.history)
                    staged(5)
                    if (writeHeadCache) capture.history.selections.firstOrNull { it.node.id == capture.history.headNodeId }?.let { head ->
                        ProjectHeadCache.write(root, head.snapshot, ProjectHeadCache.Key(head.node.revisionId))
                    }
                    Files.writeString(root.resolve("README.txt"), "PSD2Live project v2. Unencrypted ZIP. manifest.json inventories SHA-256 checksums. source/ holds the original source; history/ the history nodes and the document nodes each revision is made of; document/ the content-addressed document nodes, generator overrides and motion clips; assets/ the PNG rasters; rig/ each revision's authored rig as shared objects; auxiliary/ staged assets, views, workflow records and tasks; cache/ optional rebuild caches that may be deleted; workspace.json restores the UI. See docs/en/spec/PROJECT_FORMAT.md.\n")
                    staged(6)
                    caller.ensureActive()
                    if (writeArchive != null) writeArchive.invoke(root, path, capture.projectId)
                    else ProjectArchive.write(root, path, capture.projectId,
                        progress = { progress.report(ProjectStage.PACKING, it) }) {
                        caller.ensureActive()
                        // A v1 project being migrated keeps its original beside the verified v2 file.
                        ProjectFormatV2.backupV1(path)
                    }
                    // Runs before a dispatcher handoff can surface late cancellation.
                    onCommitted()
                }
                capture.history.headNodeId
            }
        } finally {
            staging?.let { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { ProjectArchive.deleteTemporaryDirectory(it) } }
        }
    }

    suspend fun open(path: Path, progress: ProjectProgress = ProjectProgress.NONE): OpenedProject = saves.withLock {
        var extracted: Path? = null
        try {
            withContext(Dispatchers.IO) {
                progress.report(ProjectStage.EXTRACTING, 0.0)
                val root = ProjectArchive.extract(path) { progress.report(ProjectStage.EXTRACTING, it) }.also { extracted = it }
                progress.report(ProjectStage.READING, 0.0)
                val manifest = ProjectArchive.readJson(root.resolve("manifest.json"))
                val id = manifest.getValue("projectId").jsonPrimitive.content
                val version = manifest.getValue("version").jsonPrimitive.int
                // v2 splits each revision into document nodes; the working store takes whole snapshots.
                if (version == ProjectFormatV2.VERSION) ProjectFormatV2.unpack(root, id)
                val store = WorkspaceStore(root.resolve("workspace"))
                progress.report(ProjectStage.READING, 0.3)
                val tree = withContext(Dispatchers.IO) { store.loadHistory(id) ?: error("Project has no history") }
                progress.report(ProjectStage.READING, 0.7)
                // Non-authoritative: seeds generator caches the head's rebuild then hits; never fails the open.
                ProjectHeadCache.seed(root, ProjectHeadCache.Key(tree.head().node.revisionId))
                // Each revision's authored rig, so its preview builds without generation or replay.
                MaterializedRigStore.adopt(root)
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
                val source = root.resolve("source/original.cmo3").takeIf(Files::isRegularFile) ?: root.resolve("source/original.psd")
                require(Files.isRegularFile(source)) { "Project has no original source" }
                store.validateAssetCatalog(id, WorkspaceAssetCatalog.read(JsonObject(ui)) ?: store.existingAssetCatalog(id))
                progress.report(ProjectStage.READING, 1.0)
                OpenedProject(id, path.toAbsolutePath().normalize(), root, source, JsonObject(ui), tree, store, version)
            }
        } catch (failure: Throwable) {
            extracted?.let { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { ProjectArchive.deleteTemporaryDirectory(it) } }
            throw failure
        }
    }
}

/** What a save or an open is doing; a stage runs once, in this order within a save or an open. */
internal enum class ProjectStage {
    /** Save: writing the history, assets and caches into a staging directory. */
    STAGING,
    /** Save: packing the staging directory into the archive and reading it back. */
    PACKING,
    /** Open: unpacking and checking the archive. */
    EXTRACTING,
    /** Open: reading the history and the caches out of the unpacked archive. */
    READING,
}

/** Sees each [ProjectStage] and the share of it done, from 0 to 1, on the thread doing the work. */
internal fun interface ProjectProgress {
    fun report(stage: ProjectStage, fraction: Double)

    companion object { val NONE = ProjectProgress { _, _ -> } }
}

/** A save reads one captured history and presentation, never a live UI object. */
internal data class ProjectSaveCapture(
    val projectId: String,
    val history: io.github.psd2live.history.WorkspaceHistoryState<WorkspaceDocument>,
    val presentation: JsonObject,
    val originalSource: Path?,
    val store: WorkspaceStore,
    val spatial: Map<String, WorkspaceViewSpatialMetadata> = emptyMap(),
    val tasks: List<WorkspaceTaskSnapshot> = emptyList(),
    val auxiliary: JsonObject = JsonObject(emptyMap()),
) {
    val assetCatalog = WorkspaceAssetCatalog.read(auxiliary) ?: store.existingAssetCatalog(projectId)
}

/** Extraction stays owned until a workspace adopts it; failed opens always clean up. */
internal class OpenedProject(
    val projectId: String,
    val file: Path,
    private val directory: Path,
    val source: Path,
    val presentation: JsonObject,
    val history: io.github.psd2live.history.WorkspaceHistoryTree<WorkspaceDocument>,
    val store: WorkspaceStore,
    /** The archive's format version; a v1 project migrates to v2 on its next save. */
    val formatVersion: Int = ProjectFormatV2.VERSION,
) : AutoCloseable {
    private var owned = true
    fun transferDirectory(): Path {
        check(owned) { "Project directory ownership already transferred" }
        owned = false
        return directory
    }
    override fun close() { if (owned) { ProjectArchive.deleteTemporaryDirectory(directory); owned = false } }
}
