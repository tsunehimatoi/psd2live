package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

enum class WorkspaceInitialModifiers { RECOMMENDED, EMPTY, MINIMAL, DEFAULT, FULL, CUSTOM }

/** New source projects use one isolated read/rebuild/install boundary in both adapters. */
internal class WorkspaceSourceImporter(
    private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val build: suspend (WorkspaceDocument) -> RigPreviewModel = { WorkspacePreviewBuilder().build(it) },
    private val readPsd: suspend (Path) -> SourceArt = { path -> runInterruptible(Dispatchers.IO) {
        val bytes = RigBuildProfile.threadStage("source import: read PSD bytes") { Files.readAllBytes(path) }
        RigBuildProfile.threadStage("source import: decode PSD") {
            require(PsdReader.matches(bytes)) { "Invalid PSD file" }
            PsdReader.read(bytes)
        }
    } },
    private val readLayout: suspend (Path) -> LayoutManifest.Layout = { path -> runInterruptible(Dispatchers.IO) { LayoutManifest.read(path) } },
    private val readArtwork: suspend (JsonObject) -> Pair<WorkspaceSourceArt, Map<String, LayerClassificationOverride>> = {
        runInterruptible(Dispatchers.IO) { sourceArtwork(it) }
    },
    private val newProjectId: () -> String = { UUID.randomUUID().toString() },
    private val initialWorkspaceIds: () -> List<String> = { emptyList() },
    private val preparePreset: (WorkspaceDocument, WorkspaceRasterWork, WorkspaceInitialPreset) -> Pair<WorkspaceDocument, RigPreviewModel> =
        { document, work, choices -> WorkspacePresetInitialization.prepare(document, work, choices) },
) {
    suspend fun importPsd(path: Path, projectId: String?, state: String, discardUnsaved: Boolean = false,
                         initialConfig: PipelineConfig = PipelineConfig(),
                         sourceAuxiliary: JsonObject = JsonObject(emptyMap()),
                         /** The file name the user chose, when [path] is a captured copy under another name. */
                         displayName: String = path.fileName.toString(),
                         modifiers: WorkspaceInitialModifiers? = null,
                         preset: WorkspaceInitialPreset? = null,
                         project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        return install(projectId, state, discardUnsaved, initialConfig, "Imported PSD $displayName",
            prepare = {
                require(path.isAbsolute && Files.isRegularFile(path) && path.fileName.toString().endsWith(".psd", true)) { "Provide an absolute local PSD path" }
                Prepared(readPsd(path), emptyMap(), materials = displayName)
            }, project = project, sourceAuxiliary = sourceAuxiliary, modifiers = modifiers, preset = preset)
    }

    /**
     * A new Layout project, not generated yet, from a layout manifest or a zip holding one ([LayoutManifest]): each image
     * keeps its pixels over its rectangle, tags and variants classify the layers, and the layers are also materials. The
     * project keeps no copy of the manifest; its pixels live in the history, as any Layout project's do.
     */
    suspend fun importLayout(path: Path, projectId: String?, state: String, discardUnsaved: Boolean = false,
                             initialConfig: PipelineConfig = PipelineConfig(),
                             project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> =
        install(projectId, state, discardUnsaved, initialConfig, "Imported layout ${path.fileName}", {
            require(path.isAbsolute && Files.isRegularFile(path)) { "Provide an absolute local layout manifest path" }
            val layout = readLayout(path)
            val source = LayoutManifest.source(layout)
            Prepared(source.art, source.overrides, source.userTags, materials = path.fileName.toString(), materialFolder = layout.name,
                references = source.references)
        }, project, layout = true, modifiers = WorkspaceInitialModifiers.EMPTY)

    suspend fun createArtwork(arguments: JsonObject, projectId: String?, state: String, discardUnsaved: Boolean = false,
                              initialConfig: PipelineConfig = PipelineConfig(),
                              project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> =
        install(projectId, state, discardUnsaved, initialConfig, "Created source artwork", { readArtwork(arguments).let { Prepared(it.first, it.second) } }, project, modifiers = WorkspaceInitialModifiers.RECOMMENDED)

    /** A new empty canvas of [width] x [height], not generated: materials are placed first and the rig generated later. */
    suspend fun createLayout(width: Int, height: Int, projectId: String?, state: String, discardUnsaved: Boolean = false,
                             initialConfig: PipelineConfig = PipelineConfig(),
                             project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216) { "Canvas must be 1..8192 per side and at most 16 megapixels" }
        return install(projectId, state, discardUnsaved, initialConfig, "Created layout project",
            { Prepared(WorkspaceSourceArt(width, height, emptyList(), emptyList()), emptyMap()) }, project, layout = true, modifiers = WorkspaceInitialModifiers.EMPTY)
    }

    /**
     * What a new project starts from: its source, classifications and the user's own tags; [materials] names the file
     * whose layers also become materials (in a folder named [materialFolder], else after the file).
     */
    private class Prepared(val source: SourceArt, val overrides: Map<String, LayerClassificationOverride>,
                           val userTags: Map<String, Set<String>> = emptyMap(), val materials: String? = null, val materialFolder: String? = null,
                           val references: Set<String> = emptySet())

    private suspend fun install(projectId: String?, state: String, discardUnsaved: Boolean, initialConfig: PipelineConfig,
                                summary: String, prepare: suspend () -> Prepared,
                                project: (WorkspaceDocument, RigPreviewModel, String) -> Unit,
                                sourceAuxiliary: JsonObject = JsonObject(emptyMap()), layout: Boolean = false,
                                modifiers: WorkspaceInitialModifiers? = null, preset: WorkspaceInitialPreset? = null): WorkspaceCommit<RigPreviewModel> {
        require(preset == null || modifiers == WorkspaceInitialModifiers.CUSTOM) { "Custom choices require the custom modifier mode" }
        val choices = when (modifiers) {
            WorkspaceInitialModifiers.RECOMMENDED -> WorkspaceModelPresetLibrary.shared.get("modern").choices
            WorkspaceInitialModifiers.CUSTOM -> requireNotNull(preset) { "Custom modifier choices are required" }
            WorkspaceInitialModifiers.MINIMAL, WorkspaceInitialModifiers.DEFAULT, WorkspaceInitialModifiers.FULL -> WorkspaceInitialPreset.forMode(modifiers)
            else -> null
        }
        val before = runtime.state.value
        currentCoroutineContext().ensureActive()
        // Capture before source I/O. Another edit or reload cannot be hidden by taking a newer token.
        WorkspaceExecution(projectId, state, MutationAuthor.AGENT).check(before.capture?.projectId, before.state)
        if (before.capture?.dirty == true && !discardUnsaved) throw WorkspaceUnsavedChanges()
        suspend fun progress(value: Float, message: String) {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[WorkspaceJobContext]?.progress(value, message)
        }
        progress(0.1f, "Reading source artwork")
        val input = prepare()
        val source = input.source; val overrides = input.overrides
        progress(0.4f, "Preparing new source document")
        val baseline = if (modifiers == WorkspaceInitialModifiers.RECOMMENDED)
            WorkspacePresetBaseline.configure(initialConfig, "modern") else initialConfig
        val config = (choices?.configure(baseline) ?: baseline).copy(layerVisibility = emptyMap(), deletedLayerIds = emptySet(),
            layerOverrides = overrides, parentOverrides = emptyMap(), drawOrderOverrides = emptyMap(), meshOverrides = emptyMap(),
            rigEdits = RigEditOverlay.Empty, generationSource = null, meshSource = null,
            hairSimulationFront = choices?.frontHair == WorkspaceInitialHair.SIMULATION,
            hairSimulationBack = choices?.backHair == WorkspaceInitialHair.SIMULATION)
        val overlay = if (modifiers == null) RigEditOverlay.Empty else RigEditOverlay(
            modifierStack = io.github.psd2live.core.modifier.RigModifierStack().toJson())
        val created = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), overlay,
            WorkspaceSettingsCodec.encode(config),
            layout = if (layout) WorkspaceLayout(generation = WorkspaceGeneration.NotGenerated, layerTags = input.userTags,
                referenceLayerIds = input.references) else WorkspaceLayout.Empty)
        // An imported file's layers are also materials of the new project, each layer showing its own: they can be
        // replaced, placed again or exported from the material browser. The rig reads only the source, as before.
        val context = currentCoroutineContext()
        var document = runInterruptible(Dispatchers.Default) {
            RigBuildProfile.threadStage("source import: linked materials and raster hashes") {
                input.materials?.let { file ->
                    input.materialFolder?.let { WorkspaceMaterialEdits.addLinked(created, file, it, context::ensureActive) }
                        ?: WorkspaceMaterialEdits.addLinked(created, file, checkCancelled = context::ensureActive)
                } ?: created
            }
        }
        progress(0.6f, "Rebuilding new source project")
        var built = RigBuildProfile.stage("source import: preview build (wall time)") {
            if (choices == null) build(document) else {
                val prepared = runInterruptible(Dispatchers.Default) {
                    preparePreset(document, WorkspaceRasterWork.Direct.cancellable(context), choices)
                }
                document = prepared.first
                prepared.second
            }
        }
        if (input.references.isNotEmpty()) {
            document = runInterruptible(Dispatchers.Default) { WorkspaceReferenceMeshes.ensure(document, built, context::ensureActive) }
            built = build(document)
        }
        if (layout) {
            progress(0.9f, "Installing new layout project")
            val id = newProjectId()
            val installed = installPrepared(state, id, document, built,
                auxiliary = JsonObject(sourceAuxiliary + WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData()) +
                    PreviewSessions.initial(built.rig.puppet.parameters, initialWorkspaceIds()) +
                    ("assetCatalog" to WorkspaceAssetCatalog().encode())), discardUnsaved = discardUnsaved, dirty = true) {
                project(document, built, id)
            }
            val result = WorkspaceCommit(installed, true)
            currentCoroutineContext()[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(result.mutation(summary).lifecycleResult()))
            return result
        }
        // A new project keeps its first layout: the atlas is arranged on request, not on every edit. The stored
        // arrangement is that layout exactly, so the model it builds is this one.
        val arrangement = AtlasLayout.frozen(built.modifierAuthorAtlas ?: built.atlas)
        val preview = built.copy(config = built.config.copy(atlasArrangement = arrangement), atlas = built.atlas.copy(arranged = true))
            .retainModifierCachesFrom(built)
        // The preview was generated from effective settings; the document keeps the raw ones it was given.
        val prepared = document.copy(rigEdits = preview.config.rigEdits,
            settings = AtlasArrangementCodec.with(WorkspaceSettingsCodec.encode(WorkspaceSettingsPolicy.restoreRaw(preview.config, config)), arrangement))
        progress(0.9f, "Installing new source project")
        val id = newProjectId()
        val installed = installPrepared(state, id, prepared, preview,
            auxiliary = JsonObject(sourceAuxiliary + WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData()) +
                PreviewSessions.initial(preview.rig.puppet.parameters, initialWorkspaceIds()) +
                ("assetCatalog" to WorkspaceAssetCatalog().encode())), discardUnsaved = discardUnsaved, dirty = true) {
            project(prepared, preview, id)
        }
        val result = WorkspaceCommit(installed, true)
        currentCoroutineContext()[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(result.mutation(summary).sourceResult()))
        return result
    }

    /** Source pixels are private and immutable here. Never move the presentation callback to the worker. */
    private suspend fun installPrepared(state: String, id: String, document: WorkspaceDocument, preview: RigPreviewModel,
                                        auxiliary: JsonObject, discardUnsaved: Boolean, dirty: Boolean,
                                        project: () -> Unit): WorkspaceCapture<RigPreviewModel> {
        val installation = runInterruptible(Dispatchers.Default) {
            RigBuildProfile.threadStage("source import: revision and initial history") {
                WorkspaceInstallation.prepare(document)
            }
        }
        // Cancellation after preparation must not install a candidate, even if its state token is still current.
        currentCoroutineContext().ensureActive()
        return RigBuildProfile.threadStage("source import: runtime publication") {
            runtime.installPrepared(state, id, installation, preview, auxiliary, discardUnsaved, dirty) {
                RigBuildProfile.threadStage("source import: presentation callback", project)
            }
        }
    }

}
