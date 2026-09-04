package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.PreviewRenderer
import io.github.psd2live.core.RigKeyformChannelsEdit
import io.github.psd2live.core.RigKeyformCopyEdit
import io.github.psd2live.core.RigKeyformDeleteEdit
import io.github.psd2live.core.RigKeyformGeometryEdit
import io.github.psd2live.core.RigKeyformSetEdit
import io.github.psd2live.core.RigParameterEdit
import io.github.psd2live.core.RigTargetKind
import io.github.psd2live.core.RigTargetRef
import io.github.psd2live.core.findDrawable
import io.github.psd2live.history.StaleWorkspaceHeadException
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.PSD2LiveState
import org.umamo.format.art.isEffectivelyVisible
import org.umamo.format.art.SourceArt
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterKind
import org.umamo.runtime.model.PuppetModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val PARAMETER_ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")

class ViewModelAgentWorkspace(
	private val viewModel: PSD2LiveViewModel,
) : AgentWorkspace, AutoCloseable {
	private val editMutex = Mutex()
	private val historyLock = Any()
	private val workspaceStore = AgentWorkspaceStore()
	private val persistenceJob = SupervisorJob()
	private val persistenceScope = CoroutineScope(persistenceJob + Dispatchers.IO.limitedParallelism(1))
	private val recoveryJob = SupervisorJob()
	private val recoveryScope = CoroutineScope(recoveryJob + Dispatchers.Default)
	private val pendingPersistenceWrites = AtomicInteger()
	@Volatile private var persistenceError: String? = null
	@Volatile private var recoveringProjectId: String? = null
	private val spatialByViewId = ConcurrentHashMap<String, AgentViewSpatialMetadata>()
	private val layerTombstones = ConcurrentHashMap<String, AgentLayerSnapshot>()
	private val assetStore = AgentPngAssetStore()
	private var taskProjectId: String? = null
	private var taskManager = AgentTaskManager()
	private val rasterDigests = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())
	private var historyProjectId: String? = null
	private var historyTree: WorkspaceHistoryTree<AgentWorkspaceDocument>? = null

	override fun snapshot(): AgentProjectSnapshot {
		val state = viewModel.state.value
		val analysis = state.analysis
		val revisionId = revisionId(state)
		val activeLayers = analysis?.layers.orEmpty().map { layer ->
			val id = layer.source.id.raw
			val agentSource = layer.source as? AgentWorkspaceSourceLayer
			AgentLayerSnapshot(
				id = id,
				sourceName = layer.source.name,
				rasterWidth = layer.source.raster.width,
				rasterHeight = layer.source.raster.height,
				groupPath = layer.source.groupPath,
				order = layer.source.order,
				semanticTag = layer.semantic.tag.name.lowercase(),
				side = layer.semantic.side.name.lowercase(),
				confidence = layer.semantic.confidence,
				bounds = Bounds(
					layer.source.bounds.left.toFloat(),
					layer.source.bounds.top.toFloat(),
					(layer.source.bounds.left + layer.source.bounds.width).toFloat(),
					(layer.source.bounds.top + layer.source.bounds.height).toFloat(),
				),
				opaqueBounds = layer.bounds,
				visible = state.isLayerVisible(id, analysis!!.source.isEffectivelyVisible(layer.source)) && id !in state.deletedLayerIds,
				deleted = id in state.deletedLayerIds,
				derived = agentSource?.derived == true,
				sourceAssetId = agentSource?.sourceAssetId,
				sourceSpatialReferenceId = agentSource?.sourceSpatialReferenceId,
			)
		}
		activeLayers.forEach { layer -> layerTombstones[layer.id] = layer.copy(deleted = false) }
		val deletedLayers = state.deletedLayerIds.mapNotNull { id ->
			layerTombstones[id]?.copy(visible = false, deleted = true)
		}
		val layers = (activeLayers + deletedLayers).distinctBy(AgentLayerSnapshot::id)
		val base = AgentProjectSnapshot(
			projectId = analysis?.let { projectId(state) },
			revisionId = revisionId,
			historyHeadNodeId = null,
			loaded = analysis != null,
			inputName = workspaceInputPath(state).takeIf(String::isNotBlank)?.let { runCatching { Path.of(it).fileName.toString() }.getOrNull() },
			canvasWidth = analysis?.source?.widthPx,
			canvasHeight = analysis?.source?.heightPx,
			busy = state.isAnalyzing || state.isGenerating || recoveringProjectId != null,
			status = state.statusText,
			selectedLayerId = state.selectedLayerId,
			layers = layers,
			parameters = state.previewModel?.rig?.puppet?.parameters.orEmpty().map { parameter ->
				AgentParameterSnapshot(
					id = parameter.id.raw,
					name = parameter.name,
					min = parameter.min,
					max = parameter.max,
					default = parameter.default,
					current = state.parameterValues[parameter.id] ?: parameter.default,
					kind = parameter.kind.name.lowercase(),
				)
			},
			persistenceStatus = when {
				persistenceError != null -> "error"
				recoveringProjectId != null -> "restoring"
				pendingPersistenceWrites.get() > 0 -> "saving"
				else -> "ready"
			},
			persistenceError = persistenceError,
		)
		val headNodeId = if (analysis == null) null else synchronized(historyLock) {
			synchronizeHistory(base.projectId!!, revisionId, documentFrom(state)).head().node.id
		}
		return base.copy(historyHeadNodeId = headNodeId)
	}

	override fun history(): AgentHistorySnapshot {
		val project = snapshot()
		val projectId = project.projectId ?: throw IllegalStateException("No PSD is loaded")
		return synchronized(historyLock) {
			val tree = synchronizeHistory(projectId, project.revisionId, documentFrom(viewModel.state.value))
			val headId = tree.head().node.id
			AgentHistorySnapshot(
				headNodeId = headId,
				nodes = tree.nodes().map { node ->
					AgentHistoryNodeSnapshot(
						id = node.id,
						parentId = node.parentId,
						revisionId = node.revisionId,
						summary = node.summary,
						actor = node.actor,
						taskId = node.taskId,
						createdAt = node.createdAt.toString(),
						isHead = node.id == headId,
					)
				},
			)
		}
	}

	override suspend fun renderLayer(
		layerId: String,
		background: AgentViewBackground,
		output: AgentViewOutputSpec,
	): AgentRenderedView = withContext(Dispatchers.Default) {
		val state = viewModel.state.value
		val analysis = state.analysis ?: throw IllegalStateException("No PSD is loaded")
		val layer = analysis.layers.firstOrNull { it.source.id.raw == layerId }?.source
			?: throw IllegalArgumentException("Layer not found: $layerId")
		remember(AgentViewRenderer.isolatedLayer(
			layer,
			analysis.source.widthPx,
			analysis.source.heightPx,
			snapshot().revisionId,
			background,
			output,
		))
	}

	override suspend fun renderContext(
		layerId: String,
		objectScale: Float,
		aspectRatio: Float,
		background: AgentViewBackground,
		output: AgentViewOutputSpec,
	): AgentRenderedView = withContext(Dispatchers.Default) {
		val state = viewModel.state.value
		val analysis = state.analysis ?: throw IllegalStateException("No PSD is loaded")
		val classified = analysis.layers.firstOrNull { it.source.id.raw == layerId }
			?: throw IllegalArgumentException("Layer not found: $layerId")
		remember(AgentViewRenderer.context(
			currentComposite(state),
			classified.source,
			classified.bounds,
			snapshot().revisionId,
			objectScale,
			aspectRatio,
			background,
			output,
		))
	}

	override suspend fun renderModel(request: AgentModelViewRequest): AgentRenderedView = withContext(Dispatchers.Default) {
		val state = viewModel.state.value
		val model = state.previewModel ?: throw IllegalStateException("No rig preview is available")
		val parameterById = model.rig.puppet.parameters.associateBy { it.id.raw }
		val unknownParameters = request.parameters.keys - parameterById.keys
		require(unknownParameters.isEmpty()) { "Unknown parameter IDs: ${unknownParameters.sorted().joinToString()}" }
		require(request.parameters.values.all(Float::isFinite)) { "Parameter values must be finite" }
		val appliedParameters = parameterById.values.associate { it.id.raw to it.default } + request.parameters
		val includedLayers = request.includeLayerIds ?: state.effectiveVisibleLayerIds
		remember(AgentViewRenderer.modelComposite(
			model = model,
			revisionId = snapshot().revisionId,
			parameters = appliedParameters,
			includeLayerIds = includedLayers,
			annotateLayerIds = request.annotateLayerIds,
			frame = request.frame,
			background = request.background,
			output = request.output,
		))
	}

	override suspend fun importPng(request: AgentPngImportRequest): AgentImportedPngAsset = withContext(Dispatchers.Default) {
		val projectId = snapshot().projectId ?: throw IllegalStateException("No PSD is loaded")
		val spatial = spatialByViewId[request.spatialReferenceId] ?: withContext(Dispatchers.IO) {
			workspaceStore.loadSpatial(projectId, request.spatialReferenceId)
		}?.also { spatialByViewId[request.spatialReferenceId] = it }
			?: throw IllegalArgumentException("Spatial reference not found for this workspace: ${request.spatialReferenceId}")
		val imported = assetStore.import(request, spatial)
		withContext(Dispatchers.IO) { workspaceStore.persistAsset(projectId, assetStore.require(imported.id)) }
		imported
	}

	override suspend fun addLayer(request: AgentAddLayerRequest): AgentWorkspaceMutationResult = editMutex.withLock {
		val before = snapshot()
		val projectId = before.projectId ?: throw IllegalStateException("No PSD is loaded")
		require(recoveringProjectId != projectId) { "Persisted workspace HEAD is still being restored; retry shortly" }
		val current = viewModel.state.value
		require(!current.isAnalyzing && !current.isGenerating) { "Workspace is busy" }
		val baseDocument = documentFrom(current)
		val tree = synchronized(historyLock) {
			synchronizeHistory(projectId, before.revisionId, baseDocument).also {
				if (it.head().node.id != request.expectedHistoryHeadNodeId) {
					throw StaleWorkspaceHeadException(request.expectedHistoryHeadNodeId, it.head().node.id)
				}
			}
		}
		val asset = assetStore.find(request.assetId) ?: withContext(Dispatchers.IO) {
			workspaceStore.loadAsset(projectId, request.assetId)
		}?.let(assetStore::remember) ?: throw IllegalArgumentException("PNG asset not found: ${request.assetId}")
		val (nextDocument, layerId) = baseDocument.addLayer(asset, request)
		val preview = viewModel.buildAgentWorkspacePreview(nextDocument.source, nextDocument.toConfig(current))
		val nextRevision = revisionId(current, nextDocument)
		val summary = "Added generated layer '${request.name.trim()}' ($layerId)"
		val selection = synchronized(historyLock) {
			applyPreviewOrThrow(preview, baseDocument, nextDocument, summary)
			val committed = tree.commit(
				expectedHeadNodeId = request.expectedHistoryHeadNodeId,
				snapshot = nextDocument,
				revisionId = nextRevision,
				snapshotHash = nextRevision,
				summary = summary,
				actor = "agent",
				taskId = request.taskId,
			)
			committed
		}
		scheduleHistoryPersistence(projectId, tree)
		viewModel.loadAgentWorkspacePreview(preview)
		AgentWorkspaceMutationResult(selection.node.id, nextRevision, listOf(layerId), summary)
	}

	override suspend fun softDeleteLayer(
		layerId: String,
		expectedHistoryHeadNodeId: String,
		taskId: String?,
	): AgentWorkspaceMutationResult = editMutex.withLock {
		val before = snapshot()
		val projectId = before.projectId ?: throw IllegalStateException("No PSD is loaded")
		require(recoveringProjectId != projectId) { "Persisted workspace HEAD is still being restored; retry shortly" }
		require(before.layers.any { it.id == layerId }) { "Layer not found: $layerId" }
		val current = viewModel.state.value
		require(!current.isAnalyzing && !current.isGenerating) { "Workspace is busy" }
		val baseDocument = documentFrom(current)
		val tree = synchronized(historyLock) {
			synchronizeHistory(projectId, before.revisionId, baseDocument).also {
				if (it.head().node.id != expectedHistoryHeadNodeId) {
					throw StaleWorkspaceHeadException(expectedHistoryHeadNodeId, it.head().node.id)
				}
			}
		}
		val nextDocument = baseDocument.copy(deletedLayerIds = baseDocument.deletedLayerIds + layerId)
		val preview = viewModel.buildAgentWorkspacePreview(nextDocument.source, nextDocument.toConfig(current))
		val nextRevision = revisionId(current, nextDocument)
		val summary = "Soft-deleted layer $layerId"
		val selection = synchronized(historyLock) {
			applyPreviewOrThrow(preview, baseDocument, nextDocument, summary)
			val committed = tree.commit(
				expectedHeadNodeId = expectedHistoryHeadNodeId,
				snapshot = nextDocument,
				revisionId = nextRevision,
				snapshotHash = nextRevision,
				summary = summary,
				actor = "agent",
				taskId = taskId,
			)
			committed
		}
		scheduleHistoryPersistence(projectId, tree)
		viewModel.loadAgentWorkspacePreview(preview)
		AgentWorkspaceMutationResult(selection.node.id, nextRevision, listOf(layerId), summary)
	}

	override suspend fun createParameter(request: AgentCreateParameterRequest): AgentWorkspaceMutationResult {
		val id = request.id.trim()
		require(PARAMETER_ID.matches(id)) {
			"New parameter ID must be 1-64 ASCII letters, digits, or underscores and start with a letter"
		}
		val kind = parameterKind(request.kind)
		return mutateParameter(
			expectedHeadNodeId = request.expectedHistoryHeadNodeId,
			parameterId = id,
			taskId = request.taskId,
			summary = "Created parameter $id",
		) { document, parameters ->
			require(parameters.none { it.id.raw == id }) { "Parameter already exists: $id" }
			val edit = RigParameterEdit(
				id = id,
				name = request.name.trim(),
				min = request.min,
				max = request.max,
				default = request.default,
				kind = kind,
				repeat = request.repeat,
				created = true,
			)
			document.copy(rigEdits = document.rigEdits.upsert(edit))
		}
	}

	override suspend fun updateParameter(request: AgentUpdateParameterRequest): AgentWorkspaceMutationResult {
		val id = request.id.trim()
		require(
			request.name != null || request.min != null || request.max != null || request.default != null ||
				request.kind != null || request.repeat != null,
		) { "parameter_update requires at least one editable field" }
		return mutateParameter(
			expectedHeadNodeId = request.expectedHistoryHeadNodeId,
			parameterId = id,
			taskId = request.taskId,
			summary = "Updated parameter $id",
		) { document, parameters ->
			val current = parameters.firstOrNull { it.id.raw == id }
				?: throw IllegalArgumentException("Parameter not found: $id")
			val previousEdit = document.rigEdits.parameterEdits.firstOrNull { it.id == id }
			val edit = RigParameterEdit(
				id = id,
				name = request.name?.trim() ?: current.name,
				min = request.min ?: current.min,
				max = request.max ?: current.max,
				default = request.default ?: current.default,
				kind = request.kind?.let(::parameterKind) ?: current.kind,
				repeat = request.repeat ?: current.repeat,
				created = previousEdit?.created == true,
			)
			document.copy(rigEdits = document.rigEdits.upsert(edit))
		}
	}

	override suspend fun deleteParameter(
		parameterId: String,
		expectedHistoryHeadNodeId: String,
		taskId: String?,
	): AgentWorkspaceMutationResult {
		val id = parameterId.trim()
		return mutateParameter(
			expectedHeadNodeId = expectedHistoryHeadNodeId,
			parameterId = id,
			taskId = taskId,
			summary = "Deleted parameter $id and collapsed its keyform axes at the previous default",
		) { document, parameters ->
			require(parameters.any { it.id.raw == id }) { "Parameter not found: $id" }
			document.copy(rigEdits = document.rigEdits.delete(id))
		}
	}

	private suspend fun mutateParameter(
		expectedHeadNodeId: String,
		parameterId: String,
		taskId: String?,
		summary: String,
		mutation: (AgentWorkspaceDocument, List<Parameter>) -> AgentWorkspaceDocument,
	): AgentWorkspaceMutationResult = editMutex.withLock {
		val before = snapshot()
		val projectId = before.projectId ?: throw IllegalStateException("No PSD is loaded")
		require(recoveringProjectId != projectId) { "Persisted workspace HEAD is still being restored; retry shortly" }
		val current = viewModel.state.value
		require(!current.isAnalyzing && !current.isGenerating) { "Workspace is busy" }
		val parameters = current.previewModel?.rig?.puppet?.parameters
			?: throw IllegalStateException("No rig preview is available")
		val baseDocument = documentFrom(current)
		val tree = synchronized(historyLock) {
			synchronizeHistory(projectId, before.revisionId, baseDocument).also {
				if (it.head().node.id != expectedHeadNodeId) {
					throw StaleWorkspaceHeadException(expectedHeadNodeId, it.head().node.id)
				}
			}
		}
		val nextDocument = mutation(baseDocument, parameters)
		require(nextDocument != baseDocument) { "Parameter edit did not change the workspace" }
		val preview = viewModel.buildAgentWorkspacePreview(nextDocument.source, nextDocument.toConfig(current))
		val nextRevision = revisionId(current, nextDocument)
		val selection = synchronized(historyLock) {
			applyPreviewOrThrow(preview, baseDocument, nextDocument, summary)
			tree.commit(
				expectedHeadNodeId = expectedHeadNodeId,
				snapshot = nextDocument,
				revisionId = nextRevision,
				snapshotHash = nextRevision,
				summary = summary,
				actor = "agent",
				taskId = taskId,
			)
		}
		scheduleHistoryPersistence(projectId, tree)
		viewModel.loadAgentWorkspacePreview(preview)
		AgentWorkspaceMutationResult(
			historyNodeId = selection.node.id,
			revisionId = nextRevision,
			affectedLayerIds = emptyList(),
			summary = summary,
			affectedParameterIds = listOf(parameterId),
		)
	}

	override fun getObject(target: AgentKeyformTargetRef): AgentObjectSnapshot {
		val state = viewModel.state.value
		val rig = state.previewModel?.rig
			?: throw IllegalStateException("No rig is loaded")
		val puppet = rig.puppet
		val kind = RigTargetKind.fromString(target.kind)

		return when (kind) {
			RigTargetKind.WARP_DEFORMER, RigTargetKind.ROTATION_DEFORMER -> {
				val deformer = puppet.deformers.firstOrNull { it.id.raw == target.id }
					?: throw IllegalArgumentException("Deformer not found: ${target.id}")
				when (deformer) {
					is Deformer.Warp -> {
						val grid = deformer.geometryGrid
						val topo = mapOf(
							"type" to "warp",
							"rows" to deformer.rows.toString(),
							"columns" to deformer.columns.toString(),
							"controlPointsCount" to (deformer.rows * deformer.columns).toString(),
							"isQuadTransform" to deformer.isQuadTransform.toString(),
						)
						val geoSnapshot = grid?.let { g ->
							AgentObjectGeometrySnapshot(
								axes = g.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
								keyformCount = g.cells.size,
								cells = g.cells.map { cell ->
									val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
									AgentObjectCellSnapshot(
										coordinate = coord,
										controlPoints = cell.form.controlPoints.toList(),
									)
								},
							)
						}
						val channelSnapshots = deformer.channelGrids.gridsByChannel.map { (ch, track) ->
							AgentObjectChannelTrackSnapshot(
								channel = ch.name.lowercase(),
								staticValue = when (ch) {
									FormChannel.OPACITY -> deformer.opacity.toString()
									FormChannel.MULTIPLY_COLOR -> deformer.multiplyColor.toString()
									FormChannel.SCREEN_COLOR -> deformer.screenColor.toString()
									else -> ""
								},
								axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
								keyformCount = track.cells.size,
							)
						}
						AgentObjectSnapshot(
							target = target,
							name = deformer.name,
							parentId = deformer.parent?.raw,
							partId = deformer.partId?.raw,
							visible = deformer.isVisible,
							topologyInfo = topo,
							geometry = geoSnapshot,
							channels = channelSnapshots,
						)
					}
					is Deformer.Rotation -> {
						val grid = deformer.geometryGrid
						val topo = mapOf(
							"type" to "rotation",
							"baseAngle" to deformer.baseAngle.toString(),
						)
						val geoSnapshot = grid?.let { g ->
							AgentObjectGeometrySnapshot(
								axes = g.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
								keyformCount = g.cells.size,
								cells = g.cells.map { cell ->
									val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
									AgentObjectCellSnapshot(
										coordinate = coord,
										originX = cell.form.originX,
										originY = cell.form.originY,
										angle = cell.form.angle,
										scale = cell.form.scale,
									)
								},
							)
						}
						val channelSnapshots = deformer.channelGrids.gridsByChannel.map { (ch, track) ->
							AgentObjectChannelTrackSnapshot(
								channel = ch.name.lowercase(),
								staticValue = when (ch) {
									FormChannel.OPACITY -> deformer.opacity.toString()
									FormChannel.MULTIPLY_COLOR -> deformer.multiplyColor.toString()
									FormChannel.SCREEN_COLOR -> deformer.screenColor.toString()
									FormChannel.FLIP_X -> deformer.flipX.toString()
									FormChannel.FLIP_Y -> deformer.flipY.toString()
									else -> ""
								},
								axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
								keyformCount = track.cells.size,
							)
						}
						AgentObjectSnapshot(
							target = target,
							name = deformer.name,
							parentId = deformer.parent?.raw,
							partId = deformer.partId?.raw,
							visible = deformer.isVisible,
							topologyInfo = topo,
							geometry = geoSnapshot,
							channels = channelSnapshots,
						)
					}
				}
			}
			RigTargetKind.ART_MESH -> {
				val drawable = puppet.findDrawable(target.id)
					?: puppet.drawables.firstOrNull { rig.layerIdByDrawableId[it.id.raw] == target.id }
					?: throw IllegalArgumentException("Drawable not found: ${target.id}")
				val grid = drawable.geometryGrid
				val topo = mapOf(
					"type" to "art_mesh",
					"vertexCount" to (drawable.mesh?.vertexCount ?: 0).toString(),
					"triangleCount" to (drawable.mesh?.triangleCount ?: 0).toString(),
					"layerId" to (rig.layerIdByDrawableId[drawable.id.raw] ?: ""),
				)
				val geoSnapshot = grid?.let { g ->
					AgentObjectGeometrySnapshot(
						axes = g.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
						keyformCount = g.cells.size,
						cells = g.cells.map { cell ->
							val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
							AgentObjectCellSnapshot(
								coordinate = coord,
								positionDeltas = cell.form.positionDeltas.toList(),
							)
						},
					)
				}
				val channelSnapshots = drawable.channelGrids.gridsByChannel.map { (ch, track) ->
					AgentObjectChannelTrackSnapshot(
						channel = ch.name.lowercase(),
						staticValue = when (ch) {
							FormChannel.OPACITY -> drawable.opacity.toString()
							FormChannel.DRAW_ORDER -> drawable.drawOrder.toString()
							FormChannel.MULTIPLY_COLOR -> drawable.multiplyColor.toString()
							FormChannel.SCREEN_COLOR -> drawable.screenColor.toString()
							else -> ""
						},
						axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
						keyformCount = track.cells.size,
					)
				}
				AgentObjectSnapshot(
					target = target,
					name = drawable.name,
					parentId = drawable.parentDeformerId?.raw,
					partId = null,
					visible = drawable.isVisible,
					topologyInfo = topo,
					geometry = geoSnapshot,
					channels = channelSnapshots,
				)
			}
			RigTargetKind.PART -> {
				val part = puppet.parts.firstOrNull { it.id.raw == target.id }
					?: throw IllegalArgumentException("Part not found: ${target.id}")
				val channelSnapshots = part.channelGrids.gridsByChannel.map { (ch, track) ->
					AgentObjectChannelTrackSnapshot(
						channel = ch.name.lowercase(),
						staticValue = when (ch) {
							FormChannel.OPACITY -> part.composite.opacity.toString()
							FormChannel.DRAW_ORDER -> part.drawOrder.toString()
							FormChannel.MULTIPLY_COLOR -> part.composite.multiplyColor.toString()
							FormChannel.SCREEN_COLOR -> part.composite.screenColor.toString()
							else -> ""
						},
						axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
						keyformCount = track.cells.size,
					)
				}
				AgentObjectSnapshot(
					target = target,
					name = part.name,
					parentId = null,
					partId = part.id.raw,
					visible = part.isVisible,
					topologyInfo = mapOf("type" to "part", "childrenCount" to part.children.size.toString()),
					geometry = null,
					channels = channelSnapshots,
				)
			}
			RigTargetKind.GLUE -> {
				val glue = puppet.glues.firstOrNull { it.meshA.raw == target.id && it.meshB.raw == target.secondaryId }
					?: throw IllegalArgumentException("Glue not found: ${target.id} -> ${target.secondaryId}")
				val channelSnapshots = glue.channelGrids.gridsByChannel.map { (ch, track) ->
					AgentObjectChannelTrackSnapshot(
						channel = ch.name.lowercase(),
						staticValue = glue.intensity.toString(),
						axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
						keyformCount = track.cells.size,
					)
				}
				AgentObjectSnapshot(
					target = target,
					name = "Glue_${glue.meshA.raw}_${glue.meshB.raw}",
					parentId = null,
					partId = null,
					visible = true,
					topologyInfo = mapOf("type" to "glue", "meshA" to glue.meshA.raw, "meshB" to glue.meshB.raw),
					geometry = null,
					channels = channelSnapshots,
				)
			}
		}
	}

	override suspend fun setKeyform(request: AgentKeyformSetRequest): AgentWorkspaceMutationResult {
		val targetRef = RigTargetRef(
			kind = RigTargetKind.fromString(request.target.kind),
			id = request.target.id.trim(),
			secondaryId = request.target.secondaryId?.trim(),
		)
		val geo = request.geometry?.let {
			RigKeyformGeometryEdit(
				controlPoints = it.controlPoints,
				originX = it.originX,
				originY = it.originY,
				angle = it.angle,
				scale = it.scale,
				positionDeltas = it.positionDeltas,
			)
		}
		val ch = request.channels?.let {
			RigKeyformChannelsEdit(
				opacity = it.opacity,
				drawOrder = it.drawOrder,
				multiplyColor = it.multiplyColor,
				screenColor = it.screenColor,
				glueIntensity = it.glueIntensity,
				flipX = it.flipX,
				flipY = it.flipY,
			)
		}
		val edit = RigKeyformSetEdit(
			target = targetRef,
			coordinate = request.coordinate,
			geometry = geo,
			channels = ch,
		)
		val coordStr = request.coordinate.entries.joinToString(",") { "${it.key}=${it.value}" }
		return mutateRigKeyform(
			expectedHeadNodeId = request.expectedHistoryHeadNodeId,
			taskId = request.taskId,
			summary = "Set keyform on ${targetRef.id} at ($coordStr)",
			affectedObjectId = targetRef.id,
		) { document, _ ->
			document.copy(rigEdits = document.rigEdits.setKeyform(edit))
		}
	}

	override suspend fun deleteKeyform(request: AgentKeyformDeleteRequest): AgentWorkspaceMutationResult {
		val targetRef = RigTargetRef(
			kind = RigTargetKind.fromString(request.target.kind),
			id = request.target.id.trim(),
			secondaryId = request.target.secondaryId?.trim(),
		)
		val edit = RigKeyformDeleteEdit(
			target = targetRef,
			parameterId = request.parameterId.trim(),
			keyValue = request.keyValue,
			channel = request.channel?.trim(),
		)
		val detail = if (request.keyValue != null) "key ${request.keyValue} on ${request.parameterId}" else "axis ${request.parameterId}"
		return mutateRigKeyform(
			expectedHeadNodeId = request.expectedHistoryHeadNodeId,
			taskId = request.taskId,
			summary = "Deleted $detail on ${targetRef.id}",
			affectedObjectId = targetRef.id,
		) { document, _ ->
			document.copy(rigEdits = document.rigEdits.deleteKeyform(edit))
		}
	}

	override suspend fun copyKeyform(request: AgentKeyformCopyRequest): AgentWorkspaceMutationResult {
		val srcTarget = RigTargetRef(
			kind = RigTargetKind.fromString(request.sourceTarget.kind),
			id = request.sourceTarget.id.trim(),
			secondaryId = request.sourceTarget.secondaryId?.trim(),
		)
		val destTarget = request.destinationTarget?.let {
			RigTargetRef(
				kind = RigTargetKind.fromString(it.kind),
				id = it.id.trim(),
				secondaryId = it.secondaryId?.trim(),
			)
		} ?: srcTarget
		val edit = RigKeyformCopyEdit(
			sourceTarget = srcTarget,
			sourceCoordinate = request.sourceCoordinate,
			destinationTarget = destTarget,
			destinationCoordinate = request.destinationCoordinate,
			channels = request.channels,
		)
		return mutateRigKeyform(
			expectedHeadNodeId = request.expectedHistoryHeadNodeId,
			taskId = request.taskId,
			summary = "Copied keyform from ${srcTarget.id} to ${destTarget.id}",
			affectedObjectId = destTarget.id,
		) { document, _ ->
			document.copy(rigEdits = document.rigEdits.copyKeyform(edit))
		}
	}

	override suspend fun rigKPose(request: AgentRigKPoseRequest): AgentWorkspaceMutationResult {
		return setKeyform(
			AgentKeyformSetRequest(
				expectedHistoryHeadNodeId = request.expectedHistoryHeadNodeId,
				target = request.target,
				coordinate = request.parameters,
				geometry = request.geometry,
				channels = request.channels,
				taskId = request.taskId,
			),
		)
	}

	private suspend fun mutateRigKeyform(
		expectedHeadNodeId: String,
		taskId: String?,
		summary: String,
		affectedObjectId: String,
		mutation: (AgentWorkspaceDocument, PuppetModel) -> AgentWorkspaceDocument,
	): AgentWorkspaceMutationResult = editMutex.withLock {
		val before = snapshot()
		val projectId = before.projectId ?: throw IllegalStateException("No PSD is loaded")
		require(recoveringProjectId != projectId) { "Persisted workspace HEAD is still being restored; retry shortly" }
		val current = viewModel.state.value
		require(!current.isAnalyzing && !current.isGenerating) { "Workspace is busy" }
		val puppet = current.previewModel?.rig?.puppet
			?: throw IllegalStateException("No rig preview is available")
		val baseDocument = documentFrom(current)
		val tree = synchronized(historyLock) {
			synchronizeHistory(projectId, before.revisionId, baseDocument).also {
				if (it.head().node.id != expectedHeadNodeId) {
					throw StaleWorkspaceHeadException(expectedHeadNodeId, it.head().node.id)
				}
			}
		}
		val nextDocument = mutation(baseDocument, puppet)
		require(nextDocument != baseDocument) { "Rig edit did not change the workspace" }
		val preview = viewModel.buildAgentWorkspacePreview(nextDocument.source, nextDocument.toConfig(current))
		val nextRevision = revisionId(current, nextDocument)
		val selection = synchronized(historyLock) {
			applyPreviewOrThrow(preview, baseDocument, nextDocument, summary)
			tree.commit(
				expectedHeadNodeId = expectedHeadNodeId,
				snapshot = nextDocument,
				revisionId = nextRevision,
				snapshotHash = nextRevision,
				summary = summary,
				actor = "agent",
				taskId = taskId,
			)
		}
		scheduleHistoryPersistence(projectId, tree)
		viewModel.loadAgentWorkspacePreview(preview)
		AgentWorkspaceMutationResult(
			historyNodeId = selection.node.id,
			revisionId = nextRevision,
			affectedLayerIds = emptyList(),
			summary = summary,
			affectedObjectIds = listOf(affectedObjectId),
		)
	}

	override suspend fun checkoutHistory(nodeId: String): AgentWorkspaceMutationResult = editMutex.withLock {
		val before = snapshot()
		val projectId = before.projectId ?: throw IllegalStateException("No PSD is loaded")
		val tree = synchronized(historyLock) {
			synchronizeHistory(projectId, before.revisionId, documentFrom(viewModel.state.value))
		}
		val target = synchronized(historyLock) { tree.nodes().firstOrNull { it.id == nodeId } }
			?: throw IllegalArgumentException("History node not found: $nodeId")
		val document = synchronized(historyLock) { tree.selectionAt(nodeId).snapshot }
		val current = viewModel.state.value
		val baseDocument = documentFrom(current)
		val preview = viewModel.buildAgentWorkspacePreview(document.source, document.toConfig(current))
		synchronized(historyLock) {
			if (tree.head().node.id != before.historyHeadNodeId) {
				throw IllegalStateException("Workspace history changed while checkout was being built; retry")
			}
			applyPreviewOrThrow(preview, baseDocument, document, "Checked out history node $nodeId")
			tree.checkout(nodeId)
		}
		scheduleHistoryPersistence(projectId, tree)
		viewModel.loadAgentWorkspacePreview(preview)
		AgentWorkspaceMutationResult(target.id, target.revisionId, emptyList(), "Checked out history node $nodeId")
	}

	override fun startTask(objective: String, plan: List<String>): AgentTaskSnapshot {
		val project = snapshot()
		val head = project.historyHeadNodeId ?: throw IllegalStateException("No PSD is loaded")
		val manager = taskManagerFor(project.projectId!!)
		return manager.start(objective, plan, project.revisionId, head).also {
			scheduleTaskPersistence(project.projectId, manager)
		}
	}

	override fun updateTask(
		taskId: String,
		status: AgentTaskStatus,
		plan: List<String>?,
		currentStep: Int?,
		progress: Float?,
		message: String,
		artifactIds: List<String>,
	): AgentTaskSnapshot {
		val projectId = snapshot().projectId ?: throw IllegalStateException("No PSD is loaded")
		val manager = taskManagerFor(projectId)
		return manager.update(taskId, status, plan, currentStep, progress, message, artifactIds).also {
			scheduleTaskPersistence(projectId, manager)
		}
	}

	override fun task(taskId: String): AgentTaskSnapshot {
		val projectId = snapshot().projectId ?: throw IllegalStateException("No PSD is loaded")
		return taskManagerFor(projectId).get(taskId)
	}

	override fun tasks(): List<AgentTaskSnapshot> {
		val projectId = snapshot().projectId ?: return emptyList()
		return taskManagerFor(projectId).list()
	}

	private fun projectId(state: PSD2LiveState): String {
		val identity = buildString {
			append(normalizedPath(workspaceInputPath(state)))
			workspaceFileSignature(state)?.let { append("|file:").append(it) }
		}
		return "project-${sha256(identity).take(16)}"
	}

	private fun revisionId(state: PSD2LiveState): String {
		val analysis = state.analysis ?: return "revision-${sha256("unloaded|${normalizedPath(state.inputPath)}").take(16)}"
		return revisionId(state, documentFrom(state).copy(source = analysis.source))
	}

	private fun revisionId(state: PSD2LiveState, document: AgentWorkspaceDocument): String {
		val canonical = buildString {
			append(normalizedPath(workspaceInputPath(state)))
			workspaceFileSignature(state)?.let { append("|file:").append(it) }
			append("|canvas:").append(document.source.widthPx).append('x').append(document.source.heightPx)
			document.source.layers.forEachIndexed { painterIndex, layer ->
				append("|layer:").append(painterIndex).append(':').append(layer.id.raw)
				append(':').append(layer.name).append(':').append(layer.groupPath).append(':').append(layer.kind)
				append(':').append(layer.visible).append(':').append(layer.order).append(':').append(layer.bounds)
				append(':').append(layer.opacity).append(':').append(layer.clipped).append(':').append(layer.blend)
				append(':').append(layer.raster.width).append('x').append(layer.raster.height)
				append(':').append(rasterDigest(layer.raster.rgba))
			}
			document.layerVisibility.toSortedMap().forEach { (key, value) -> append("|v:").append(key).append('=').append(value) }
			document.deletedLayerIds.sorted().forEach { append("|d:").append(it) }
			document.layerOverrides.toSortedMap().forEach { (key, value) -> append("|o:").append(key).append('=').append(value) }
			document.parentOverrides.toSortedMap().forEach { (key, value) -> append("|p:").append(key).append('=').append(value) }
			document.rigEdits.deletedParameterIds.sorted().forEach { append("|pd:").append(it) }
			document.rigEdits.parameterEdits.forEach { edit -> append("|pe:").append(edit) }
		}
		return "revision-${sha256(canonical).take(16)}"
	}

	private fun documentFrom(state: PSD2LiveState): AgentWorkspaceDocument {
		val source = state.analysis?.source ?: throw IllegalStateException("No PSD is loaded")
		return AgentWorkspaceDocument(
			source = source,
			layerVisibility = state.layerVisibility.toMap(),
			deletedLayerIds = state.deletedLayerIds.toSet(),
			layerOverrides = state.layerOverrides.toMap(),
			parentOverrides = state.parentOverrides.toMap(),
			rigEdits = state.rigEdits,
		)
	}

	private fun AgentWorkspaceDocument.toConfig(state: PSD2LiveState): PipelineConfig = state.buildConfig().copy(
		layerVisibility = layerVisibility,
		deletedLayerIds = deletedLayerIds,
		layerOverrides = layerOverrides,
		parentOverrides = parentOverrides,
		rigEdits = rigEdits,
	)

	private fun synchronizeHistory(
		projectId: String,
		revisionId: String,
		document: AgentWorkspaceDocument,
	): WorkspaceHistoryTree<AgentWorkspaceDocument> {
		var changed = false
		if (historyTree == null || historyProjectId != projectId) {
			historyProjectId = projectId
			val restored = try {
				workspaceStore.loadHistory(projectId)
			} catch (failure: Exception) {
				persistenceError = failure.message ?: failure.javaClass.simpleName
				throw IllegalStateException("Unable to load persisted workspace history: ${persistenceError}", failure)
			}
			historyTree = restored ?: WorkspaceHistoryTree(document, revisionId, revisionId).also { changed = true }
			if (restored != null && restored.head().node.revisionId != revisionId) {
				recoveringProjectId = projectId
				scheduleAutomaticRestore(projectId, restored, document)
				return restored
			}
		}
		val tree = historyTree!!
		if (recoveringProjectId == projectId) return tree
		val head = tree.head().node
		if (head.revisionId != revisionId) {
			tree.commit(
				expectedHeadNodeId = head.id,
				snapshot = document,
				revisionId = revisionId,
				snapshotHash = revisionId,
				summary = "Workspace changed in the editor",
				actor = "user",
			)
			changed = true
		}
		if (changed) scheduleHistoryPersistence(projectId, tree)
		return tree
	}

	private fun scheduleAutomaticRestore(
		projectId: String,
		tree: WorkspaceHistoryTree<AgentWorkspaceDocument>,
		expectedCurrent: AgentWorkspaceDocument,
	) {
		val target = tree.head()
		recoveryScope.launch {
			try {
				editMutex.withLock {
					val currentState = viewModel.state.value
					val preview = viewModel.buildAgentWorkspacePreview(target.snapshot.source, target.snapshot.toConfig(currentState))
					var restored = false
					var branchState: io.github.psd2live.history.WorkspaceHistoryState<AgentWorkspaceDocument>? = null
					synchronized(historyLock) {
						if (historyTree !== tree || tree.head().node.id != target.node.id) return@synchronized
						restored = applyPreview(preview, expectedCurrent, target.snapshot, "Restored persisted workspace ${target.node.id}")
						if (!restored) {
							val latestState = viewModel.state.value
							val latest = documentFrom(latestState)
							val latestRevision = revisionId(latestState, latest)
							tree.commit(
								expectedHeadNodeId = target.node.id,
								snapshot = latest,
								revisionId = latestRevision,
								snapshotHash = latestRevision,
								summary = "Editor changed before automatic restore; preserved as a new branch",
								actor = "user",
							)
							branchState = tree.state()
						}
						recoveringProjectId = null
					}
					if (restored) viewModel.loadAgentWorkspacePreview(preview)
					branchState?.let { state -> schedulePersistence { workspaceStore.persistHistory(projectId, state) } }
				}
			} catch (failure: Exception) {
				persistenceError = "Automatic workspace restore failed: ${failure.message ?: failure.javaClass.simpleName}"
				recoveringProjectId = null
			}
		}
	}

	private fun taskManagerFor(projectId: String): AgentTaskManager = synchronized(historyLock) {
		if (taskProjectId != projectId) {
			val restored = try {
				workspaceStore.loadTasks(projectId)
			} catch (failure: Exception) {
				persistenceError = failure.message ?: failure.javaClass.simpleName
				throw IllegalStateException("Unable to load persisted Agent tasks: ${persistenceError}", failure)
			}
			taskProjectId = projectId
			taskManager = AgentTaskManager().also { it.restore(restored) }
		}
		taskManager
	}

	private fun scheduleHistoryPersistence(
		projectId: String,
		tree: WorkspaceHistoryTree<AgentWorkspaceDocument>,
	) {
		val state = tree.state()
		schedulePersistence { workspaceStore.persistHistory(projectId, state) }
	}

	private fun scheduleTaskPersistence(projectId: String, manager: AgentTaskManager) {
		val tasks = manager.list()
		schedulePersistence { workspaceStore.persistTasks(projectId, tasks) }
	}

	private fun schedulePersistence(block: () -> Unit) {
		pendingPersistenceWrites.incrementAndGet()
		persistenceScope.launch {
			try {
				block()
				persistenceError = null
			} catch (failure: Exception) {
				persistenceError = failure.message ?: failure.javaClass.simpleName
			} finally {
				pendingPersistenceWrites.decrementAndGet()
			}
		}
	}

	private fun remember(view: AgentRenderedView): AgentRenderedView = view.also {
		spatialByViewId[it.viewId] = it.spatial
		val state = viewModel.state.value
		if (state.analysis != null) {
			val projectId = projectId(state)
			schedulePersistence { workspaceStore.persistSpatial(projectId, it.viewId, it.spatial) }
		}
	}

	private fun applyPreviewOrThrow(
		preview: io.github.psd2live.core.RigPreviewModel,
		expected: AgentWorkspaceDocument,
		next: AgentWorkspaceDocument,
		status: String,
	) {
		if (!applyPreview(preview, expected, next, status)) {
			throw IllegalStateException("Workspace changed while the operation was being built; retry from current state")
		}
	}

	private fun applyPreview(
		preview: io.github.psd2live.core.RigPreviewModel,
		expected: AgentWorkspaceDocument,
		next: AgentWorkspaceDocument,
		status: String,
	): Boolean = viewModel.applyAgentWorkspacePreview(
			preview = preview,
			expectedSource = expected.source,
			expectedLayerVisibility = expected.layerVisibility,
			expectedDeletedLayerIds = expected.deletedLayerIds,
			expectedLayerOverrides = expected.layerOverrides,
			expectedParentOverrides = expected.parentOverrides,
			expectedRigEdits = expected.rigEdits,
			layerVisibility = next.layerVisibility,
			deletedLayerIds = next.deletedLayerIds,
			layerOverrides = next.layerOverrides,
			parentOverrides = next.parentOverrides,
			rigEdits = next.rigEdits,
			status = status,
		)

	private fun rasterDigest(rgba: ByteArray): String = rasterDigests[rgba] ?: sha256(rgba).also { digest ->
		rasterDigests[rgba] = digest
	}

	private fun parameterKind(raw: String): ParameterKind = runCatching {
		ParameterKind.valueOf(raw.trim().uppercase())
	}.getOrElse { throw IllegalArgumentException("Parameter kind must be normal or blend_shape") }

	private fun currentComposite(state: io.github.psd2live.ui.state.PSD2LiveState): BufferedImage {
		val analysis = state.analysis ?: throw IllegalStateException("No PSD is loaded")
		val canvas = BufferedImage(analysis.source.widthPx, analysis.source.heightPx, BufferedImage.TYPE_INT_ARGB)
		val graphics = canvas.createGraphics()
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
		try {
			for (classified in analysis.layers) {
				val layer = classified.source
				val sourceVisible = analysis.source.isEffectivelyVisible(layer)
				if (layer.id.raw in state.deletedLayerIds || !state.isLayerVisible(layer.id.raw, sourceVisible) || layer.opacity <= 0f) continue
				graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, layer.opacity.coerceIn(0f, 1f))
				graphics.drawImage(
					PreviewRenderer.rasterImage(layer.raster.width, layer.raster.height, layer.raster.rgba),
					layer.bounds.left,
					layer.bounds.top,
					null,
				)
			}
		} finally {
			graphics.dispose()
		}
		return canvas
	}

	private fun normalizedPath(inputPath: String): String = runCatching {
		Path.of(inputPath).toAbsolutePath().normalize().toString()
	}.getOrDefault(inputPath)

	private fun workspaceInputPath(state: PSD2LiveState): String = state.loadedInputPath ?: state.inputPath

	private fun workspaceFileSignature(state: PSD2LiveState): String? =
		state.loadedInputFileSignature ?: fileSignature(workspaceInputPath(state))

	private fun fileSignature(inputPath: String): String? = runCatching {
		val path = Path.of(inputPath)
		"${Files.size(path)}:${Files.getLastModifiedTime(path).toMillis()}"
	}.getOrNull()

	private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(StandardCharsets.UTF_8))
		.joinToString("") { "%02x".format(it.toInt() and 0xff) }

	private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
		.digest(value)
		.joinToString("") { "%02x".format(it.toInt() and 0xff) }

	override fun close() {
		recoveryJob.cancel()
		runBlocking { recoveryJob.join() }
		persistenceJob.complete()
		runBlocking { persistenceJob.join() }
	}
}
