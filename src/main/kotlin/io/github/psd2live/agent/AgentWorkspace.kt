package io.github.psd2live.agent

import io.github.psd2live.core.Bounds

/** A stable, UI-independent description of the project currently open in PSD2Live. */
data class AgentProjectSnapshot(
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
	val layers: List<AgentLayerSnapshot>,
	val parameters: List<AgentParameterSnapshot>,
	val persistenceStatus: String = "memory_only",
	val persistenceError: String? = null,
)

data class AgentParameterSnapshot(
	val id: String,
	val name: String,
	val min: Float,
	val max: Float,
	val default: Float,
	val current: Float,
	val kind: String,
)

data class AgentLayerSnapshot(
	val id: String,
	val sourceName: String,
	val rasterWidth: Int,
	val rasterHeight: Int,
	val groupPath: String,
	val order: Int,
	val semanticTag: String,
	val side: String,
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

data class AgentHistoryNodeSnapshot(
	val id: String,
	val parentId: String?,
	val revisionId: String,
	val summary: String,
	val actor: String,
	val taskId: String?,
	val createdAt: String,
	val isHead: Boolean,
)

data class AgentHistorySnapshot(
	val headNodeId: String,
	val nodes: List<AgentHistoryNodeSnapshot>,
)

data class AgentPngImportRequest(
	val png: ByteArray,
	val spatialReferenceId: String,
	val sourcePixelRect: AgentPixelRect? = null,
)

data class AgentImportedPngAsset(
	val id: String,
	val sha256: String,
	val pixelWidth: Int,
	val pixelHeight: Int,
	val placement: AgentCanvasPlacement,
)

sealed interface AgentLayerInsertion {
	data object Top : AgentLayerInsertion
	data object Bottom : AgentLayerInsertion
	data class Above(val layerId: String) : AgentLayerInsertion
	data class Below(val layerId: String) : AgentLayerInsertion
}

data class AgentAddLayerRequest(
	val assetId: String,
	val expectedHistoryHeadNodeId: String,
	val name: String,
	val layerId: String? = null,
	val groupPath: String = "",
	val insertion: AgentLayerInsertion = AgentLayerInsertion.Top,
	val semanticTag: String = "unknown",
	val side: String = "none",
	val visible: Boolean = true,
	val opacity: Float = 1f,
	val trimTransparent: Boolean = true,
	val parentDeformerId: String? = null,
	val taskId: String? = null,
)

data class AgentWorkspaceMutationResult(
	val historyNodeId: String,
	val revisionId: String,
	val affectedLayerIds: List<String>,
	val summary: String,
	val affectedParameterIds: List<String> = emptyList(),
)

data class AgentCreateParameterRequest(
	val id: String,
	val expectedHistoryHeadNodeId: String,
	val name: String,
	val min: Float = -1f,
	val max: Float = 1f,
	val default: Float = 0f,
	val kind: String = "normal",
	val repeat: Boolean = false,
	val taskId: String? = null,
)

/** Null fields retain their current authoritative value. Parameter IDs are stable and not renamed. */
data class AgentUpdateParameterRequest(
	val id: String,
	val expectedHistoryHeadNodeId: String,
	val name: String? = null,
	val min: Float? = null,
	val max: Float? = null,
	val default: Float? = null,
	val kind: String? = null,
	val repeat: Boolean? = null,
	val taskId: String? = null,
)

enum class AgentTaskStatus {
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

data class AgentTaskEventSnapshot(
	val sequence: Long,
	val createdAt: String,
	val status: AgentTaskStatus,
	val message: String,
	val artifactIds: List<String>,
)

data class AgentTaskSnapshot(
	val id: String,
	val objective: String,
	val plan: List<String>,
	val status: AgentTaskStatus,
	val currentStep: Int?,
	val progress: Float,
	val inputRevisionId: String,
	val inputHistoryHeadNodeId: String,
	val createdAt: String,
	val updatedAt: String,
	val artifactIds: List<String>,
	val events: List<AgentTaskEventSnapshot>,
)

enum class AgentViewBackground {
	TRANSPARENT,
	CHECKERBOARD,
}

/** A camera window expressed in canonical canvas units (origin top-left, Y down). */
sealed interface AgentViewFrame {
	/** Observe this exact canvas rectangle. The rectangle may extend beyond the canvas. */
	data class CanvasRect(val rect: Bounds) : AgentViewFrame

	/**
	 * Center the camera on the deformed union of [layerIds]. [objectScale] is the fraction of the
	 * fitted viewport occupied by that union: values below one include surrounding context.
	 */
	data class FocusLayers(
		val layerIds: Set<String>,
		val objectScale: Float = 0.65f,
		val aspectRatio: Float = 1f,
	) : AgentViewFrame
}

data class AgentViewOutputSpec(
	/** Requested PNG long edge. Canvas units and output pixels deliberately remain independent. */
	val targetLongEdge: Int = 1024,
	/** PNG byte budget; the renderer reduces resolution while preserving the canvas rectangle if needed. */
	val maxBytes: Int = 4 * 1024 * 1024,
)

data class AgentModelViewRequest(
	val parameters: Map<String, Float> = emptyMap(),
	/** Null uses current workspace visibility; an empty set deliberately renders no layers. */
	val includeLayerIds: Set<String>? = null,
	val annotateLayerIds: Set<String> = emptySet(),
	val frame: AgentViewFrame,
	val background: AgentViewBackground = AgentViewBackground.TRANSPARENT,
	val output: AgentViewOutputSpec = AgentViewOutputSpec(),
)

data class AgentRenderedView(
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
	val spatial: AgentViewSpatialMetadata,
	val appliedParameters: Map<String, Float> = emptyMap(),
	val outOfRangeParameters: List<AgentParameterRangeDiagnostic> = emptyList(),
	val includedLayerIds: List<String> = emptyList(),
	val annotatedLayerIds: List<String> = emptyList(),
)

/** Everything required to map generated or edited PNG pixels back into the model without guessing. */
data class AgentViewSpatialMetadata(
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

data class AgentParameterRangeDiagnostic(
	val id: String,
	val value: Float,
	val min: Float,
	val max: Float,
)

/**
 * Boundary used by both the in-process chat runtime and external MCP clients.
 * Implementations must return direct model renders, never screenshots of the application UI.
 */
interface AgentWorkspace {
	fun snapshot(): AgentProjectSnapshot
	fun history(): AgentHistorySnapshot = throw UnsupportedOperationException("Workspace history is not available")

	suspend fun renderLayer(
		layerId: String,
		background: AgentViewBackground = AgentViewBackground.TRANSPARENT,
		output: AgentViewOutputSpec = AgentViewOutputSpec(),
	): AgentRenderedView

	suspend fun renderContext(
		layerId: String,
		objectScale: Float = 0.65f,
		aspectRatio: Float = 1f,
		background: AgentViewBackground = AgentViewBackground.TRANSPARENT,
		output: AgentViewOutputSpec = AgentViewOutputSpec(),
	): AgentRenderedView

	/** Render the evaluated rig at an explicit parameter pose with caller-selected layer composition. */
	suspend fun renderModel(request: AgentModelViewRequest): AgentRenderedView

	/** Stage an Agent-produced transparent PNG without changing the project history. */
	suspend fun importPng(request: AgentPngImportRequest): AgentImportedPngAsset =
		throw UnsupportedOperationException("PNG import is not available")

	/** Add a staged PNG as a real source layer, rebuild its mesh/rig, and append one history node. */
	suspend fun addLayer(request: AgentAddLayerRequest): AgentWorkspaceMutationResult =
		throw UnsupportedOperationException("Layer editing is not available")

	/** Soft-delete a layer while retaining all pixels and prior history nodes. */
	suspend fun softDeleteLayer(
		layerId: String,
		expectedHistoryHeadNodeId: String,
		taskId: String? = null,
	): AgentWorkspaceMutationResult = throw UnsupportedOperationException("Layer editing is not available")

	/** Create a real Cubism parameter and retain it across source/mesh rebuilds and export. */
	suspend fun createParameter(request: AgentCreateParameterRequest): AgentWorkspaceMutationResult =
		throw UnsupportedOperationException("Parameter editing is not available")

	/** Edit every persisted property of a real Cubism parameter except its stable ID. */
	suspend fun updateParameter(request: AgentUpdateParameterRequest): AgentWorkspaceMutationResult =
		throw UnsupportedOperationException("Parameter editing is not available")

	/** Delete a parameter and safely collapse every keyform grid that references its axis. */
	suspend fun deleteParameter(
		parameterId: String,
		expectedHistoryHeadNodeId: String,
		taskId: String? = null,
	): AgentWorkspaceMutationResult = throw UnsupportedOperationException("Parameter editing is not available")

	/** Move workspace HEAD to an immutable prior snapshot and rebuild the editable preview. */
	suspend fun checkoutHistory(nodeId: String): AgentWorkspaceMutationResult =
		throw UnsupportedOperationException("Workspace history is not available")

	fun startTask(objective: String, plan: List<String>): AgentTaskSnapshot =
		throw UnsupportedOperationException("Long tasks are not available")

	fun updateTask(
		taskId: String,
		status: AgentTaskStatus,
		plan: List<String>?,
		currentStep: Int?,
		progress: Float?,
		message: String,
		artifactIds: List<String>,
	): AgentTaskSnapshot = throw UnsupportedOperationException("Long tasks are not available")

	fun task(taskId: String): AgentTaskSnapshot = throw UnsupportedOperationException("Long tasks are not available")
	fun tasks(): List<AgentTaskSnapshot> = emptyList()
}
