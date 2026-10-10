package io.github.psd2live.project

import kotlinx.serialization.json.JsonObject


import io.github.psd2live.core.Bounds

/** A stable, UI-independent description of the project currently open in PSD2Live. */
data class WorkspaceProjectSnapshot(
	val projectId: String?,
	val revisionId: String,
	val historyHeadNodeId: String? = null,
	val loaded: Boolean,
	val inputName: String?,
	val canvasWidth: Int?,
	val canvasHeight: Int?,
	val busy: Boolean,
	val status: String,
	val selectedLayerId: String?,
	val layers: List<WorkspaceLayerSnapshot>,
	val parameters: List<WorkspaceParameterSnapshot>,
	val projectFile: String? = null,
    val projectDirty: Boolean = false,
    val projectSaving: Boolean = false,
    val projectSaveError: String? = null,
    val persistenceStatus: String = "memory_only",
	val persistenceError: String? = null,
    val state: String = "unavailable",
)

data class WorkspaceParameterSnapshot(
	val id: String,
	val name: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val current: Float,
	val kind: String,
)

data class WorkspaceLayerSnapshot(
	val id: String,
	val sourceName: String,
	val rasterWidth: Int,
	val rasterHeight: Int,
	val groupPath: String,
	val order: Int,
	val semanticTag: String,
	val side: String,
	val classificationType: String = "preset",
	val parameterBinding: String = "",
	val switchId: Int = 0,
	val confidence: Float,
	/** Full raster placement on the source canvas. */
	val bounds: Bounds,
	/** Tight alpha-derived content bounds used by classification and fitting. */
	val opaqueBounds: Bounds,
	val visible: Boolean,
	val deleted: Boolean,
	val derived: Boolean = false,
	val sourceAssetId: String? = null,
	val sourceSpatialReferenceId: String? = null,
)

data class WorkspaceHistoryNodeSnapshot(
	val id: String,
	val parentId: String?,
	val revisionId: String,
	val summary: String,
	val actor: String,
	val taskId: String?,
	val createdAt: String,
	val isHead: Boolean,
)

data class WorkspaceHistorySnapshot(
	val headNodeId: String,
	val nodes: List<WorkspaceHistoryNodeSnapshot>,
)

data class WorkspacePngImportRequest(
	val png: ByteArray,
	val spatialReferenceId: String = "",
	val sourcePixelRect: WorkspacePixelRect? = null,
    val solidBackground: String? = null,
    val backgroundTolerance: Int = 16,
    val requireTransparency: Boolean = false,
    val referenceId: String? = null,
    val processing: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

data class WorkspaceImportedPngAsset(
	val id: String,
	val sha256: String,
	val pixelWidth: Int,
	val pixelHeight: Int,
	val placement: WorkspaceCanvasPlacement,
    val details: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

data class WorkspaceAssetPreview(val asset: WorkspaceImportedPngAsset, val png: ByteArray, val transparentPixels: Int, val translucentPixels: Int, val originalPng: ByteArray? = null)

sealed interface WorkspaceLayerInsertion {
	data object Top : WorkspaceLayerInsertion
	data object Bottom : WorkspaceLayerInsertion
	data class Above(val layerId: String) : WorkspaceLayerInsertion
	data class Below(val layerId: String) : WorkspaceLayerInsertion
}

data class WorkspaceAddLayerRequest(
	val assetId: String,
	val expectedState: String,
	val name: String,
	val layerId: String? = null,
	val groupPath: String = "",
	val insertion: WorkspaceLayerInsertion = WorkspaceLayerInsertion.Top,
	val semanticTag: String = "unknown",
	val side: String = "none",
	val visible: Boolean = true,
	val opacity: Float = 1f,
	val trimTransparent: Boolean = true,
    val registrationId: String? = null,
	val parentDeformerId: String? = null,
	val taskId: String? = null,
)

data class WorkspaceMutationResult(
	val historyNodeId: String,
	val revisionId: String,
	val affectedLayerIds: List<String> = emptyList(),
	val summary: String,
	val affectedParameterIds: List<String> = emptyList(),
	val affectedObjectIds: List<String> = emptyList(),
	/**
	 * False when the request described the state the workspace was already in. Nothing was committed,
	 * nothing was logged, and every affected list is empty — the caller asked for an edit and the answer
	 * is that there was nothing to edit. Deliberately not named `changed`: the authoring tools already
	 * use that JSON key for the affected-object list.
	 */
	val applied: Boolean = true,
    val state: String? = null,
    val projectId: String? = null,
    val geometryDiagnostics: JsonObject? = null,
    /** The atlas fit after a batch that edited textures; null when the batch left them alone. */
    val atlasFit: Float? = null,
    /** What that atlas reports about fitting its budget. */
    val atlasNotices: List<String> = emptyList(),
)

data class WorkspaceCreateParameterRequest(
	val id: String,
	val expectedState: String,
	val name: String,
	val min: Float = -1f,
	val max: Float = 1f,
	val default: Float = 0f,
	val kind: String = "normal",
	val repeat: Boolean = false,
	val taskId: String? = null,
)

/** Null fields retain their current authoritative value. Parameter IDs are stable and not renamed. */
data class WorkspaceUpdateParameterRequest(
	val id: String,
	val expectedState: String,
	val name: String? = null,
	val min: Float? = null,
	val max: Float? = null,
	val default: Float? = null,
	val kind: String? = null,
	val repeat: Boolean? = null,
	val taskId: String? = null,
)

data class WorkspaceKeyformTargetRef(
	val kind: String,
	val id: String,
	val secondaryId: String? = null,
	val glueId: String? = null,
)

data class WorkspaceObjectAxisSnapshot(
	val parameterId: String,
	val keys: List<Float>,
)

data class WorkspaceObjectCellSnapshot(
	val coordinate: Map<String, Float>,
	val controlPoints: List<Float>? = null,
	val originX: Float? = null,
	val originY: Float? = null,
	val angle: Float? = null,
	val scale: Float? = null,
	val positionDeltas: List<Float>? = null,
)

data class WorkspaceObjectGeometrySnapshot(
	val axes: List<WorkspaceObjectAxisSnapshot>,
	val keyformCount: Int,
	val cells: List<WorkspaceObjectCellSnapshot>,
)

data class WorkspaceObjectChannelTrackSnapshot(
	val channel: String,
	val staticValue: String,
	val axes: List<WorkspaceObjectAxisSnapshot>,
	val keyformCount: Int,
)

data class WorkspaceObjectSnapshot(
	val target: WorkspaceKeyformTargetRef,
	val name: String,
	val parentId: String?,
	val partId: String?,
	val visible: Boolean,
	val topologyInfo: Map<String, String> = emptyMap(),
	val geometry: WorkspaceObjectGeometrySnapshot? = null,
	val channels: List<WorkspaceObjectChannelTrackSnapshot> = emptyList(),
)

enum class WorkspaceTaskStatus {
	PLANNING,
	INSPECTING,
	EXECUTING,
	VALIDATING,
	COMMITTING,
	WAITING_FOR_USER,
	PAUSED,
	DONE,
	FAILED,
	CANCELLED,
}

data class WorkspaceTaskEventSnapshot(
	val sequence: Long,
	val createdAt: String,
	val status: WorkspaceTaskStatus,
	val message: String,
	val artifactIds: List<String>,
)

data class WorkspaceTaskSnapshot(
	val id: String,
	val objective: String,
	val plan: List<String>,
	val status: WorkspaceTaskStatus,
	val currentStep: Int?,
	val progress: Float,
	val inputRevisionId: String,
	val inputHistoryHeadNodeId: String,
	val createdAt: String,
	val updatedAt: String,
	val artifactIds: List<String>,
	val events: List<WorkspaceTaskEventSnapshot>,
)

enum class WorkspaceViewBackground {
	TRANSPARENT,
	CHECKERBOARD,
}

/** A camera window expressed in canonical canvas units (origin top-left, Y down). */
sealed interface WorkspaceViewFrame {
	/** Observe this exact canvas rectangle. The rectangle may extend beyond the canvas. */
	data class CanvasRect(val rect: Bounds) : WorkspaceViewFrame

	/**
	 * Center the camera on the deformed union of [layerIds]. [objectScale] is the fraction of the
	 * fitted viewport occupied by that union: values below one include surrounding context.
	 */
	data class FocusLayers(
		val layerIds: Set<String>,
		val objectScale: Float = 0.65f,
		val aspectRatio: Float = 1f,
	) : WorkspaceViewFrame
}

data class WorkspaceViewOutputSpec(
	/** Requested PNG long edge. Canvas units and output pixels deliberately remain independent. */
	val targetLongEdge: Int = 1024,
	/** PNG byte budget; the renderer reduces resolution while preserving the canvas rectangle if needed. */
	val maxBytes: Int = 4 * 1024 * 1024,
)

data class WorkspaceModelViewRequest(
	val annotateDeformerIds: Set<String> = emptySet(),
	val annotatePathIds: Set<String> = emptySet(),
	val annotatePathWidth: Boolean = false,
	val annotatePathHardness: Boolean = false,
	val annotatePathRadius: Boolean = false,
	val pointIndices: Boolean = false,
	val parameters: Map<String, Float> = emptyMap(),
	/** Null uses current workspace visibility; an empty set deliberately renders no layers. */
	val includeLayerIds: Set<String>? = null,
	val annotateLayerIds: Set<String> = emptySet(),
	val frame: WorkspaceViewFrame,
	val background: WorkspaceViewBackground = WorkspaceViewBackground.TRANSPARENT,
	val output: WorkspaceViewOutputSpec = WorkspaceViewOutputSpec(),
)

data class WorkspaceRenderedView(
	val viewId: String,
	val revisionId: String,
	val kind: String,
	val objectIds: List<String>,
	val png: ByteArray,
	val originalWidth: Int,
	val originalHeight: Int,
	val renderedWidth: Int,
	val renderedHeight: Int,
	val canvasRect: Bounds,
	val scale: Float,
	val sha256: String,
	val spatial: WorkspaceViewSpatialMetadata,
	val appliedParameters: Map<String, Float> = emptyMap(),
	val outOfRangeParameters: List<WorkspaceParameterRangeDiagnostic> = emptyList(),
	val includedLayerIds: List<String> = emptyList(),
	val annotatedLayerIds: List<String> = emptyList(),
	val annotatedDeformerIds: List<String> = emptyList(),
	val annotatedPathIds: List<String> = emptyList(),
	val annotatedPathWidth: Boolean = false,
	val annotatedPathHardness: Boolean = false,
	val annotatedPathRadius: Boolean = false,
	val pointIndices: Boolean = false,
)

/** Everything required to map generated or edited PNG pixels back into the model without guessing. */
data class WorkspaceViewSpatialMetadata(
	val coordinateSpace: String = "canvas_top_left_y_down",
	val pixelWidth: Int,
	val pixelHeight: Int,
	val canvasWidth: Float,
	val canvasHeight: Float,
	/** Camera rectangle requested by the Agent before sub-pixel raster alignment. */
	val requestedViewRect: Bounds,
	/** Exact canvas area represented by the complete output PNG. */
	val viewRect: Bounds,
	/** Deformed object bounds used to derive a focus view, if applicable. */
	val focusRect: Bounds? = null,
	val focusLayerIds: List<String> = emptyList(),
	val objectScale: Float? = null,
	/** Canvas units represented by one output pixel on each axis. */
	val canvasUnitsPerPixelX: Float,
	val canvasUnitsPerPixelY: Float,
)

data class WorkspaceParameterRangeDiagnostic(
	val id: String,
	val value: Float,
	val min: Float,
	val max: Float,
)

/**
 * UI-independent workspace contracts shared by application commands and external adapters.
 * Implementations must return direct model renders, never screenshots of the application UI.
 */
data class WorkspaceWorkflowResult(val metadata: kotlinx.serialization.json.JsonObject, val images: List<ByteArray> = emptyList())

/**
 * Who asked for a mutation, for the entry points the editor and the AI host both reach. The
 * workspace cannot tell them apart from the call itself, so the caller must say: history nodes
 * record [historyActor] verbatim; presentation adapters choose their own log category.
 *
 * Adapters supply this identity through explicit ports or trusted application execution context;
 * client request fields never choose the actor recorded by the runtime.
 */
enum class MutationAuthor(val historyActor: String) {
    USER("user"),
    AGENT("agent"),
}
