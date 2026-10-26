package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Deferred
import org.umamo.runtime.model.PuppetModel
import io.github.psd2live.core.legacy.SupersededEntryNote

private val noSamplingProgress: (Float) -> Unit = {}
private val noSamplingCancellation: () -> Boolean = { false }

/** Application capabilities are mandatory contracts; implementations never inherit unavailable fallbacks. */
interface WorkspaceStatePort {
    fun snapshot(): WorkspaceProjectSnapshot
    /** The loaded aggregate's opaque version, without copying history, layers or parameter lists. */
    fun stateToken(): String?
    /** Whether the committed aggregate has changed since its last successful save. */
    fun hasUnsavedChanges(): Boolean
}

/**
 * An editor draft as submitted: the [document] the editor shows, the runtime document [origin] its untouched fields
 * mirror (the last commit it was shown, null when unknown), and the settings it yielded to another author's commit
 * during its session ([yieldedSettings]), which keep the runtime's value when its settings switches replay.
 */
data class WorkspaceEditorDraft(
    val document: WorkspaceDocument,
    val origin: WorkspaceDocument? = null,
    val yieldedSettings: Set<String> = emptySet(),
)

interface WorkspaceEditorDraftPort {
    suspend fun previewEditorSettingsDraft(projectId: String, state: String, changes: List<JsonObject>): RigPreviewModel
    /** [draft] is the editor's draft as it shows it; only [changes] are committed, the rest carries later drafts. */
    fun submitEditorSettingsDraft(projectId: String, state: String, draft: WorkspaceEditorDraft, changes: List<JsonObject>,
                                  summary: String, author: MutationAuthor): Deferred<WorkspaceMutationResult>
    /** Build a transient field preview from the captured committed rig, using the same preparation as submission. */
    suspend fun previewEditorDraft(projectId: String, state: String, document: WorkspaceDocument,
                                   settingsIntents: List<JsonObject>): RigPreviewModel
    /**
     * [settingsIntents] are the settings switches made during the field session, in order. They replay through the
     * same intent as settings_update, so their links and authored-pose releases land with the draft in one commit.
     */
    fun submitEditorDraft(projectId: String, state: String, draft: WorkspaceEditorDraft, settingsIntents: List<JsonObject>,
                          summary: String, author: MutationAuthor): Deferred<WorkspaceMutationResult>
    suspend fun awaitEditorDrafts()
    /** Advance an action only through this editor queue's own successful commits. */
    suspend fun settleEditorDrafts(projectId: String, state: String): String
}

interface WorkspaceQueries : WorkspaceStatePort {
    fun history(): WorkspaceHistorySnapshot
    fun currentPuppet(): PuppetModel?
    /** The authored armature, before its Cubism deformer/keyform bake. */
    fun skeletonSpec(): SkeletonSpec?
    /** The same proposal the canvas editor creates from the current tagged artwork. */
    fun proposeSkeleton(): SkeletonSpec
    /** Solve an FK/IK drag into parameter values without changing the model or preview. */
    fun solveSkeletonPose(request: JsonObject): JsonObject
    fun motionClips(): List<MotionClip>
    /** The captured model's expressions and pose ([WorkspaceSidecarEdits.sidecars]). */
    fun modelSidecars(): WorkspaceSidecars
    fun previewSession(): JsonObject
    fun layerMeshSettings(layerId: String): JsonObject
    fun sampleSourceColor(layerId: String, x: Int, y: Int): List<Int>
    fun sourceMeshComponents(layerId: String): JsonObject
    /** [WorkspaceStructureMeshEdits.preview] on the captured version. */
    fun structureMeshPreview(request: JsonObject, checkpoint: () -> Unit = {}): JsonObject

    companion object {
        /** A checkpoint for work under `runInterruptible`: an interrupted thread stops there, and the caller sees cancellation. */
        fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
    }
    /** [WorkspaceObjectEdits.find] on the captured version: the objects a find matches and what a replace makes of them. */
    fun findObjects(request: JsonObject): JsonObject
    /** [WorkspaceObjectEdits.planned] on the captured version: the request that records exactly what the result fields name. */
    fun planObjectEdit(operation: String, request: JsonObject): Pair<JsonObject, JsonObject>
    fun projectSettings(): JsonObject
    fun listRigObjectSummaries(): List<JsonObject>
    fun inspectRigGeometry(arguments: JsonObject): JsonObject
    /** Inspect a target drawable, deformer, part, or glue: topology, geometry, and keyforms. */
    fun getObject(target: WorkspaceKeyformTargetRef): WorkspaceObjectSnapshot
    /** Every physics group, generated and authored, as export resolves them. */
    fun listPhysics(): List<PhysicsGroup>
    /** Runs the exported physics on scripted inputs; read-only. */
    fun simulatePhysics(arguments: JsonObject, progress: (Float) -> Unit = noSamplingProgress,
                        cancelled: () -> Boolean = noSamplingCancellation): JsonObject
    /** The physics rate the model declares. */
    fun physicsFps(): Int
    fun listSimulations(): List<RigSimEdit>
    /** Runs simulation [id] on the current rig and reports how it moves; read-only. */
    fun reportSimulation(id: String, hold: Float, release: Float, wind: Pair<Float, Float>?,
                         progress: (Float) -> Unit = noSamplingProgress, cancelled: () -> Boolean = noSamplingCancellation): JsonObject
    /** Plays [motions] through baked simulation [id] and its reference and reports how the bake follows; read-only. */
    fun compareSimulation(id: String, motions: List<String>, progress: (Float) -> Unit = noSamplingProgress,
                          cancelled: () -> Boolean = noSamplingCancellation): JsonObject
    fun listInteriorEffects(): List<io.github.psd2live.core.interior.NativeInteriorEffect> = emptyList()
    fun listSwings(): List<RigSwingEdit>
    /** The captured document's hit areas, in test order. */
    fun hitAreas(): List<io.github.psd2live.core.RigHitArea>
    /** The captured model hit areas are tested on; null when nothing is loaded. */
    fun hitAreaModel(): io.github.psd2live.core.RigPreviewModel?
    /** Generated overrides of the captured model that did not apply as recorded, in journal order. */
    fun generatedOverrideIssues(): List<io.github.psd2live.core.GeneratedOverrideIssue>
    /** Journal entries of the captured model that replay skipped because they address only what a later split supersedes. */
    fun supersededEntryNotes(): List<SupersededEntryNote>
    /** The copies over the bangs the captured model's settings ask for that it could not make, as (original mesh, reason). */
    fun seeThroughNotes(): List<Pair<String, String>>
    /** What the captured document's last regeneration checkpoint could not carry over cleanly, in merge order. */
    fun regenerationIssues(): List<io.github.psd2live.core.RigRegeneration.Issue>
    /**
     * Regeneration merges of the captured document that this build makes differently, which an update of the generated
     * rig would repair (`remerged` issues, `journal:<index>`); empty when there is nothing to repair. The first call per
     * model merges again, which can take a while.
     */
    fun staleRegenerations(): List<io.github.psd2live.core.RigRegeneration.Issue>
    /** Where the captured model's skeleton does not reach what it should ([io.github.psd2live.core.quality.SkeletonBindingQuality]). */
    fun skeletonBindingIssues(): List<io.github.psd2live.core.quality.SkeletonBindingIssue>
    /** The captured document's Layout state with its source layers and those waiting for generation; null when nothing is loaded. */
    fun layoutState(): WorkspaceLayoutState?
    /** `module_catalog`: the rig's areas and modules, and with a project open how its recipe uses them ([RigModuleCatalog]). */
    fun rigModules(): kotlinx.serialization.json.JsonObject
}

/** A captured document's Layout state ([io.github.psd2live.project.WorkspaceLayout]) and the layers it applies to. */
data class WorkspaceLayoutState(
    val layout: io.github.psd2live.project.WorkspaceLayout,
    val layers: List<org.umamo.format.art.SourceLayer>,
    val pendingLayerIds: Set<String>,
    val deletedLayerIds: Set<String>,
    val canvasWidth: Int,
    val canvasHeight: Int,
)

/** Capture once before composing a response; the returned queries never revisit live state. */
interface WorkspaceReadPort : WorkspaceQueries {
    fun captureQueries(): WorkspaceQueries
}

/**
 * Where imported images go: under [parentDeformerId] (null = the model root), drawn just in front of layer
 * [anchorLayerId] or just behind it ([inFront] false) when given, each centred on [centre] in canvas units when given.
 */
data class ImageLayerInsertion(
    val parentDeformerId: String? = null,
    val anchorLayerId: String? = null,
    val inFront: Boolean = true,
    val centre: Pair<Float, Float>? = null,
    /** Import as a reference rectangle, bypassing pixel mesh generation. */
    val reference: Boolean = false,
)

interface WorkspaceSourcePort {
    /** Import a complete file batch as editable artwork in one history node, where [insertion] puts it. */
    suspend fun importImages(state: String, paths: List<java.nio.file.Path>, insertion: ImageLayerInsertion): WorkspaceMutationResult
    suspend fun importPsd(path: String, discardUnsaved: Boolean = false): WorkspaceMutationResult
    /** A new Layout project, not generated yet, from a layout manifest or a zip holding one ([LayoutManifest]). */
    suspend fun importLayout(path: String, discardUnsaved: Boolean = false): WorkspaceMutationResult
    suspend fun importCmo3(path: java.nio.file.Path, mode: Cmo3ImportMode,
                           discardUnsaved: Boolean = false): WorkspaceMutationResult
    suspend fun createArtwork(arguments: JsonObject): WorkspaceMutationResult
    /** A new empty project of [width] x [height] not generated yet, for placing materials first ([io.github.psd2live.project.WorkspaceGeneration]). */
    suspend fun createLayoutProject(width: Int, height: Int, discardUnsaved: Boolean = false): WorkspaceMutationResult
    suspend fun splitArtwork(arguments: JsonObject, author: MutationAuthor = MutationAuthor.AGENT): WorkspaceMutationResult
    /** Split one or several layers by their current mesh islands as one document commit. */
    suspend fun splitMeshComponents(state: String, splits: List<JsonObject>): WorkspaceMutationResult
    suspend fun splitDepth(state: String, request: JsonObject): WorkspaceMutationResult
    suspend fun paintSource(arguments: JsonObject): WorkspaceMutationResult
    suspend fun commitPaintRaster(state: String, request: WorkspacePaintRaster): WorkspaceMutationResult
    /** Delete a layer and its meshes; earlier history nodes keep them. */
    suspend fun deleteLayer(
        layerId: String,
        expectedState: String,
        taskId: String? = null,
    ): WorkspaceMutationResult
    /** Merge omitted fields against the captured candidate after checking the expected state. */
    suspend fun classifyLayer(
        layerId: String,
        fields: JsonObject,
        expectedState: String,
    ): WorkspaceMutationResult
    /** Restore selected deleted layers, or all of them when IDs are omitted. */
    suspend fun restoreDeletedLayers(layerIds: List<String>?, expectedState: String, taskId: String? = null): WorkspaceMutationResult
}

/**
 * The texture workspace: each layer's canvas rectangle, raster pixels and texture density, and the atlas they pack into.
 * Reads come from one captured version; every edit is one candidate, rebuild and CAS on [state] with one history node.
 */
interface WorkspaceTexturePort {
    /** A detached read of the committed textures and atlas; it never revisits live state. */
    fun captureTextures(): WorkspaceTextureView
    /** Commits [edit] on [state]; an edit that changes nothing returns applied=false and adds no history node. */
    suspend fun editTexture(state: String, edit: WorkspaceTextureEdit, author: MutationAuthor = MutationAuthor.AGENT): WorkspaceTextureResult
}

/** Private raster drafts share stroke history and only publish on an explicit once commit. */
interface WorkspacePaintPort {
    fun listPaintSessions(): List<JsonObject>
    fun beginPaintSession(state: String, layerId: String): WorkspacePaintSession
    /** A session painting material item [materialId] of the library; its commit writes `material_set_pixels`. */
    fun beginMaterialPaintSession(materialId: String): WorkspacePaintSession
    suspend fun controlPaintSession(request: JsonObject): WorkspaceWorkflowResult
    suspend fun commitPaintSession(state: String, sessionId: String, sessionState: String,
        rebuildMesh: Boolean, preserveSourceRaster: Boolean, author: MutationAuthor): WorkspaceMutationResult
}

interface WorkspaceSettingsPort {
    suspend fun setLayerMeshSettings(state: String, layerId: String, changes: JsonObject?, reset: Boolean): WorkspaceMutationResult
    suspend fun updateProjectSettings(state: String, changes: JsonObject): WorkspaceMutationResult
}

interface WorkspaceParameterPort {
    /** Create a real Cubism parameter and retain it across source/mesh rebuilds and export. */
    suspend fun createParameter(request: WorkspaceCreateParameterRequest): WorkspaceMutationResult
    /** Edit every persisted property of a real Cubism parameter except its stable ID. */
    suspend fun updateParameter(request: WorkspaceUpdateParameterRequest): WorkspaceMutationResult
    /** Delete a parameter and safely collapse every keyform grid that references its axis. */
    suspend fun deleteParameter(
        parameterId: String,
        expectedState: String,
        taskId: String? = null,
    ): WorkspaceMutationResult
}

interface WorkspaceRigPort {
    suspend fun authorRig(state: String, edits: JsonArray, author: MutationAuthor): WorkspaceMutationResult
    suspend fun createIndependentWarp(request: JsonObject, expectedState: String): WorkspaceMutationResult
    suspend fun editWarpControls(operation: String, expectedState: String, request: JsonObject, author: MutationAuthor): JsonObject
}

interface WorkspaceSkeletonPort {
    suspend fun editSkeleton(state: String, request: JsonObject): WorkspaceMutationResult
}

/** The Skeleton Edit draft as an application session; the canvas editor and public operations share it. */
interface WorkspaceSkeletonDraftPort {
    /** Resets the active workspace pose to rest as the draft's own CAS on [state]; the draft continues from its result. */
    suspend fun openSkeletonDraft(state: String): WorkspaceSkeletonDraft
    fun skeletonDrafts(): List<WorkspaceSkeletonDraft>
    fun skeletonDraft(sessionId: String): WorkspaceSkeletonDraft
    fun skeletonDraftRevision(sessionId: String, revision: Long): SkeletonSpec?
    /** [state] is the draft's lineage and [sessionState] its latest revision; all [intents] apply or none. */
    fun editSkeletonDraft(sessionId: String, state: String, sessionState: String, intents: List<SkeletonDraftIntent>): WorkspaceSkeletonDraft
    fun previewSkeletonWeightTransfer(sessionId: String, transfer: SkeletonDraftIntent.TransferWeights): Pair<Map<String, String>, SkeletonManualWeights.Transfer?>
    suspend fun commitSkeletonDraft(sessionId: String, state: String, sessionState: String, author: MutationAuthor): WorkspaceSkeletonDraftCommit
    fun cancelSkeletonDraft(sessionId: String): WorkspaceSkeletonDraft
}

interface WorkspaceMotionPort {
    suspend fun editMotion(state: String, request: JsonObject): WorkspaceMutationResult
}

interface WorkspacePreviewPort {
    suspend fun setPreviewSession(arguments: JsonObject): JsonObject
    /** Values, FK/IK targets and optional automatic timeline recording share one CAS. */
    suspend fun authorPose(arguments: JsonObject, author: MutationAuthor = MutationAuthor.AGENT): JsonObject
    /** Process playback controls and evaluated frames never advance durable document/pose state. */
    fun controlPlayback(arguments: JsonObject): JsonObject
    fun playbackFrame(dt: Float? = null): JsonObject
    /** The tracked pointer, normalized with Y up; the next [playbackFrame] evaluates it. */
    fun playbackPointer(pointer: Pair<Float, Float>?)
    fun previewPhysics(arguments: JsonObject): JsonObject
    /** Frozen GUI pose boundaries exclude animation/physics evaluation frames. */
    fun commitAuthoredPoses(state: String, poses: Map<String, WorkspacePose>)
    /** [workspaceId]'s committed authored pose, normalized against the committed model. */
    fun authoredPose(workspaceId: String): WorkspacePose
}

/** Per-canvas visibility and solo; the host supplies its live canvases and projects each committed record. */
interface WorkspaceCanvasVisibilityPort {
    fun canvasVisibility(): WorkspaceCanvasVisibilitySnapshot
    fun editCanvasVisibility(state: String, address: CanvasAddress, intent: CanvasVisibilityIntent): WorkspaceCanvasVisibilitySnapshot
}

interface WorkspacePhysicsPort {
    /** Panel intents resolve IDs, presets and observed response against the same isolated candidate. */
    suspend fun editPhysics(intent: WorkspacePhysicsIntent, expectedState: String): Pair<WorkspaceMutationResult, JsonObject>
    /** [arguments] are a `physics_put` request, laid over the group with that ID. */
    suspend fun putPhysics(arguments: JsonObject, expectedState: String, taskId: String?): WorkspaceMutationResult
    suspend fun deletePhysics(id: String, expectedState: String): WorkspaceMutationResult
    /** Sets the evaluation [order] (group IDs first, the rest after) and/or the physics [fps]. */
    suspend fun configurePhysics(order: List<String>?, fps: Int?, expectedState: String): WorkspaceMutationResult
    /** Imports a physics3.json at [path] as user groups; the result lists what it did. */
    suspend fun importPhysics(path: String, expectedState: String): Pair<WorkspaceMutationResult, JsonObject>
    /** Scales group [id]'s outputs so a standard head sway swings each to [target] of its parameter's end. */
    /** With [observedPeaks] (output index to reach, as an audition measured it) the standard sway is not traced. */
    suspend fun fitPhysics(id: String, target: Float, expectedState: String, observedPeaks: Map<Int, Float>? = null): WorkspaceMutationResult
}

interface WorkspaceSimulationPort {
    /**
     * [arguments] are a `simulation` put request, laid over the simulation with that ID. A simulation that
     * bakes on its own is baked again in the same step; the second value reports that bake or why it failed.
     * [autoBake] overrides the simulation's own setting when given.
     */
    suspend fun putSimulation(arguments: JsonObject, expectedState: String, taskId: String?,
        autoBake: Boolean? = null):
        Pair<WorkspaceMutationResult, JsonObject>
    suspend fun deleteSimulation(id: String, expectedState: String): WorkspaceMutationResult
    /**
     * Applies a model preset (weights, simulations, and for hair the switch from the legacy sway) and bakes
     * what it made, as one history step; [layers] narrows it, empty means every recognized part. The second
     * value reports the simulations, the garments read and any bake failure. [autoBake] overrides the
     * simulations' own setting when given; false leaves them unbaked for the caller to bake.
     */
    suspend fun applyModelPreset(preset: ModelPresets.Preset, layers: Set<String>, expectedState: String,
        author: MutationAuthor = MutationAuthor.AGENT, autoBake: Boolean? = null): Pair<WorkspaceMutationResult, JsonObject>
    /**
     * Removes the hair simulation preset of the [front] or back hair and brings back its legacy sway, running
     * when [sway], as one history step.
     */
    suspend fun restoreClassicHair(front: Boolean, expectedState: String, author: MutationAuthor = MutationAuthor.AGENT, sway: Boolean = true): WorkspaceMutationResult
    /** Removes every clothing simulation preset, as one history step. */
    suspend fun removeClothingPresets(expectedState: String, author: MutationAuthor = MutationAuthor.AGENT): WorkspaceMutationResult
    /**
     * Bakes simulation [id] into parameters, keyforms and pendulums on the current rig; slow. The second
     * value summarizes the bake (modes, pendulum fit, error).
     */
    suspend fun bakeSimulation(id: String, expectedState: String): Pair<WorkspaceMutationResult, JsonObject>
    /** Stores [bake] as simulation [id]'s bake (null clears it) and turns physics on for a bake. */
    suspend fun putSimulationBake(id: String, bake: SimBakeResult?, expectedState: String): WorkspaceMutationResult
    /** Stores several simulations' bakes (null clears one) as one history node. */
    suspend fun putSimulationBakes(bakes: Map<String, SimBakeResult?>, expectedState: String): WorkspaceMutationResult
}

/** The physics panel's selected-group pendulum as a process session; frames never reach the document. */
interface WorkspacePhysicsAuditionPort {
    fun controlPhysicsAudition(arguments: JsonObject): JsonObject
    fun stepPhysicsAudition(arguments: JsonObject): JsonObject
    fun physicsAudition(sessionId: String): JsonObject
}

interface WorkspaceSimulationPreviewPort {
    suspend fun controlSimulationPreview(arguments: JsonObject): WorkspaceSimulationPreview
    suspend fun stepSimulationPreview(arguments: JsonObject): WorkspaceSimulationPreview
    fun simulationPreviewFrame(sessionId: String): WorkspaceSimulationPreview
    suspend fun renderSimulationPreview(sessionId: String, request: WorkspaceModelViewRequest): WorkspaceRenderedView
}

interface WorkspaceSwingPort {
    suspend fun controlSwingPreview(arguments: JsonObject, author: MutationAuthor = MutationAuthor.AGENT): WorkspaceSwingPreview
    fun swingPreviewFrame(sessionId: String, time: Float? = null): WorkspaceSwingPreview
    suspend fun renderSwingPreview(sessionId: String, request: WorkspaceModelViewRequest): WorkspaceRenderedView
    /** [estimatePhysics] sizes the pendulum from the first target instead of taking the edit's. */
    suspend fun putSwing(edit: RigSwingEdit, estimatePhysics: Boolean, expectedState: String, taskId: String?,
        author: MutationAuthor = MutationAuthor.AGENT): WorkspaceMutationResult
    /** [bake] keeps the current forms as ordinary keys (and the pendulum); otherwise they go with the swing. */
    suspend fun deleteSwing(id: String, bake: Boolean, expectedState: String, author: MutationAuthor = MutationAuthor.AGENT): WorkspaceMutationResult
}

interface WorkspaceRenderPort : WorkspaceImageRenderer {
    /** A detached observation session captures its model, history and resource destination once. */
    fun captureObservation(): WorkspaceObservation
}

interface WorkspaceImageRenderer {
    suspend fun renderLayer(
        layerId: String,
        background: WorkspaceViewBackground = WorkspaceViewBackground.TRANSPARENT,
        output: WorkspaceViewOutputSpec = WorkspaceViewOutputSpec(),
    ): WorkspaceRenderedView
    suspend fun renderContext(
        layerId: String,
        objectScale: Float = 0.65f,
        aspectRatio: Float = 1f,
        background: WorkspaceViewBackground = WorkspaceViewBackground.TRANSPARENT,
        output: WorkspaceViewOutputSpec = WorkspaceViewOutputSpec(),
    ): WorkspaceRenderedView
    /** Render the evaluated rig at an explicit parameter pose with caller-selected layer composition. */
    suspend fun renderModel(request: WorkspaceModelViewRequest): WorkspaceRenderedView
    /** Render the model over a grid of keyforms of one or two parameters into one labelled sheet ([WorkspacePadViewRequest]). */
    suspend fun renderPad(request: WorkspacePadViewRequest): WorkspaceWorkflowResult
}

interface WorkspaceObservation : WorkspaceImageRenderer {
    fun snapshot(): WorkspaceProjectSnapshot
    suspend fun observeAuthoring(arguments: JsonObject): WorkspaceWorkflowResult
}

interface WorkspaceAssetPort {
    /** Stage an Agent-produced transparent PNG without changing the project history. */
    suspend fun importPng(arguments: JsonObject): WorkspaceWorkflowResult
    suspend fun inspectAsset(assetId: String): WorkspaceAssetPreview
    suspend fun assetWorkflow(operation: String, arguments: JsonObject): WorkspaceWorkflowResult
    suspend fun setLayerPlacement(layerId: String, registrationId: String, expectedState: String, taskId: String?): WorkspaceMutationResult
    suspend fun finalizeLayerPlacement(layerId: String, expectedState: String, taskId: String?): WorkspaceMutationResult
    /** Add a staged PNG as a real source layer, rebuild its mesh/rig, and append one history node. */
    suspend fun addLayer(request: WorkspaceAddLayerRequest): WorkspaceMutationResult
}

interface WorkspaceOutputPort {
    suspend fun saveProject(): WorkspaceMutationResult
    suspend fun exportPsd(state: String, path: String, scale: Int, includeGeneratedLayers: Boolean): JsonObject
    suspend fun exportModel(state: String, outputDirectory: String): JsonObject
    /** Exports the committed state through one neutral export target, with its loss report. */
    suspend fun exportTarget(state: String, targetId: String, outputDirectory: String, settings: Map<String, String>): JsonObject
}

interface WorkspaceHistoryPort {
    suspend fun checkpoint(summary: String): WorkspaceMutationResult
    /** Move workspace HEAD to an immutable prior snapshot and rebuild the editable preview. */
    suspend fun checkoutHistory(nodeId: String, author: MutationAuthor): WorkspaceMutationResult
}


/** The explicit regeneration with this build's generators; GUI and MCP commit through the same command. */
interface WorkspaceGenerationUpdatePort {
    /**
     * Merges what this build's generators make onto the user's edits in one history node, none when they make what
     * the journal's last checkpoint stores. Returns the lifecycle fields, `updated` and the merge's `issues`.
     */
    suspend fun updateGeneration(state: String, author: MutationAuthor = MutationAuthor.AGENT): JsonObject
}

/** Composition at the host boundary only. Command consumers depend on the relevant narrow port. */
interface WorkspaceBackend :
    WorkspaceEditorDraftPort,
    WorkspaceReadPort,
    WorkspaceSourcePort,
    WorkspaceGenerationUpdatePort,
    WorkspaceTexturePort,
    WorkspacePaintPort,
    WorkspaceSettingsPort,
    WorkspaceParameterPort,
    WorkspaceRigPort,
    WorkspaceSkeletonPort,
    WorkspaceSkeletonDraftPort,
    WorkspaceMotionPort,
    WorkspacePreviewPort,
    WorkspacePhysicsPort,
    WorkspaceSimulationPort,
    WorkspaceSimulationPreviewPort,
    WorkspacePhysicsAuditionPort,
    WorkspaceSwingPort,
    WorkspaceRenderPort,
    WorkspaceAssetPort,
    WorkspaceOutputPort,
    WorkspaceHistoryPort,
    WorkspaceProjectLifecycle,
    WorkspaceDocumentPort,
    WorkspaceAuxiliaryPort,
    WorkspaceCanvasVisibilityPort
