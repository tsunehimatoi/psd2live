package io.github.psd2live.ui.state

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.CubismSdkFrame
import io.github.psd2live.core.CubismSdkPreviewSession
import io.github.psd2live.core.EyeJellyDynamics
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.agent.AgentWorkspace
import io.github.psd2live.agent.AgentHistorySnapshot
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.runtime.model.ParameterId
import org.umamo.format.art.SourceArt
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences
import kotlin.math.PI
import kotlin.math.sin

class PSD2LiveViewModel : AutoCloseable {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private val pipeline = PSD2LivePipeline()
	private val preferences by lazy { Preferences.userNodeForPackage(PSD2LiveViewModel::class.java) }
	private var agentWorkspace: AgentWorkspace? = null

	fun attachAgentWorkspace(workspace: AgentWorkspace) {
		agentWorkspace = workspace
		runCatching {
			val snapshot = workspace.history()
			_state.update { it.copy(historySnapshot = snapshot) }
		}
	}

	var lastExportDirectory: String?
		get() = runCatching { preferences.get(PREF_LAST_EXPORT_DIR, null) }.getOrNull()?.takeIf(String::isNotBlank)
		private set(value) {
			runCatching {
				if (value.isNullOrBlank()) preferences.remove(PREF_LAST_EXPORT_DIR)
				else preferences.put(PREF_LAST_EXPORT_DIR, value.trim())
			}
		}

	private val _state = MutableStateFlow(PSD2LiveState(statusText = tr("status.ready")))
	val state: StateFlow<PSD2LiveState> = _state.asStateFlow()
	private val _sdkFrame = MutableStateFlow<CubismSdkFrame?>(null)
	val sdkFrame: StateFlow<CubismSdkFrame?> = _sdkFrame.asStateFlow()

	private var previewRebuildJob: Job? = null
	private var motionJob: Job? = null
	private var activeWorkJob: Job? = null

	private var pointerActive = false
	private var pointerX = 0f
	private var pointerY = 0f
	private var followX = 0f
	private var followY = 0f
	private var previousFollowX = 0f
	private var frontHair = 0f
	private var frontHairVelocity = 0f
	private var backHair = 0f
	private var backHairVelocity = 0f
	private val eyeJellyDynamics = EyeJellyDynamics()
	private var elapsed = 0.0
	private var lastTick = System.nanoTime()
	private var lastSdkParameterPublishNanos = 0L

	private val sdkSession = CubismSdkPreviewSession(
		onFrame = { frame ->
			val now = System.nanoTime()
			var accepted = false
			_state.update { current ->
				if (!previewFrameMatchesState(current, frame.animationEnabled)) {
					return@update current
				}
				accepted = true
				val publishParameters = current.animationEnabled &&
					(now - lastSdkParameterPublishNanos >= SDK_PARAMETER_PUBLISH_INTERVAL_NANOS)
				if (!publishParameters && current.sdkStatus == "ready") return@update current
				if (publishParameters) lastSdkParameterPublishNanos = now
				current.copy(
					sdkStatus = "ready",
					parameterValues = if (publishParameters) {
						parameterValuesAfterPreviewFrame(current, frame.parameters)
					} else {
						current.parameterValues
					},
				)
			}
			if (accepted) _sdkFrame.value = frame
		},
		onStatus = { status ->
			if (status != "ready") _sdkFrame.value = null
			_state.update { it.copy(sdkStatus = status) }
		},
	)

	init {
		startMotionLoop()
	}

	fun setInputPath(path: String) {
		val normalized = path.trim()
		_state.update { current ->
			val currentOutput = current.outputPath
			val nextOutput = if (currentOutput.isBlank() && normalized.isNotBlank()) {
				try {
					val p = Path.of(normalized)
					val parent = p.toAbsolutePath().parent
					val name = p.fileName.toString().substringBeforeLast('.')
					parent.resolve("$name-psd2live").toString()
				} catch (_: Exception) {
					currentOutput
				}
			} else currentOutput
			current.copy(inputPath = normalized, outputPath = nextOutput)
		}
	}

	fun setOutputPath(path: String) {
		val trimmed = path.trim()
		if (trimmed.isNotBlank()) {
			lastExportDirectory = trimmed
		}
		_state.update { it.copy(outputPath = trimmed) }
	}

	fun setAtlasSize(size: Int) {
		_state.update { it.copy(atlasSize = size) }
		schedulePreviewRebuild()
	}

	fun setMeshSpacing(spacing: Int) {
		_state.update { it.copy(meshSpacing = spacing.coerceIn(16, 128)) }
		schedulePreviewRebuild()
	}

	fun setHeadStrength(strength: Float) {
		_state.update { it.copy(headStrength = strength.coerceIn(0f, 4f)) }
		schedulePreviewRebuild()
	}

	fun setBodyStrength(strength: Float) {
		_state.update { it.copy(bodyStrength = strength.coerceIn(0f, 4f)) }
		schedulePreviewRebuild()
	}

	fun setTexturePadding(padding: Int) {
		_state.update { it.copy(texturePadding = padding.coerceIn(0, 32)) }
		schedulePreviewRebuild()
	}

	fun setAlphaThreshold(threshold: Int) {
		_state.update { it.copy(alphaThreshold = threshold.coerceIn(0, 255)) }
		schedulePreviewRebuild()
	}

	fun setMeshOnly(enabled: Boolean) {
		_state.update { it.copy(meshOnly = enabled, generateDeformers = !enabled) }
		schedulePreviewRebuild()
	}

	fun setGenerateDeformers(enabled: Boolean) {
		_state.update { it.copy(generateDeformers = enabled) }
		schedulePreviewRebuild()
	}

	fun setExportMotions(enabled: Boolean) {
		_state.update { it.copy(exportMotions = enabled) }
	}

	fun setMotionIdle(enabled: Boolean) {
		_state.update {
			val next = it.copy(motionIdle = enabled)
			next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
		}
	}

	fun setMotionBlink(enabled: Boolean) {
		_state.update {
			val next = it.copy(motionBlink = enabled)
			next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
		}
	}

	fun setMotionNod(enabled: Boolean) {
		_state.update {
			val next = it.copy(motionNod = enabled)
			next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
		}
	}

	fun setMotionShake(enabled: Boolean) {
		_state.update {
			val next = it.copy(motionShake = enabled)
			next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
		}
	}

	fun setGeneratePhysics(enabled: Boolean) {
		_state.update { it.copy(generatePhysics = enabled) }
		schedulePreviewRebuild()
	}

	fun setPhysicsFrontHair(enabled: Boolean) {
		_state.update {
			val next = it.copy(physicsFrontHair = enabled)
			next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
		}
		schedulePreviewRebuild()
	}

	fun setPhysicsBackHair(enabled: Boolean) {
		_state.update {
			val next = it.copy(physicsBackHair = enabled)
			next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
		}
		schedulePreviewRebuild()
	}

	fun setPhysicsEyeJelly(enabled: Boolean) {
		_state.update {
			val next = it.copy(physicsEyeJelly = enabled)
			next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
		}
		schedulePreviewRebuild()
	}

	fun setExportCmo3(enabled: Boolean) {
		_state.update { it.copy(exportCmo3 = enabled) }
	}

	fun setExportMoc3(enabled: Boolean) {
		_state.update { it.copy(exportMoc3 = enabled) }
	}

	fun setExportJson(enabled: Boolean) {
		_state.update { it.copy(exportJson = enabled) }
	}

	fun setExportOptionsExpanded(expanded: Boolean) {
		_state.update { it.copy(exportOptionsExpanded = expanded) }
	}

	fun setMotionSubExpanded(expanded: Boolean) {
		_state.update { it.copy(motionSubExpanded = expanded) }
	}

	fun setPhysicsSubExpanded(expanded: Boolean) {
		_state.update { it.copy(physicsSubExpanded = expanded) }
	}

	fun setProjectOutputsExpanded(expanded: Boolean) {
		_state.update { it.copy(projectOutputsExpanded = expanded) }
	}

	fun setAdvancedExpanded(expanded: Boolean) {
		_state.update { it.copy(advancedExpanded = expanded) }
	}

	fun resetSettingsToDefault() {
		_state.update {
			it.copy(
				atlasSize = 4096,
				meshSpacing = 64,
				texturePadding = 2,
				alphaThreshold = 8,
				headStrength = 1.0f,
				bodyStrength = 1.0f,
				meshOnly = false,
				generateDeformers = true,
				exportMotions = true,
				motionIdle = true,
				motionBlink = true,
				motionNod = true,
				motionShake = true,
				generatePhysics = true,
				physicsFrontHair = true,
				physicsBackHair = true,
				physicsEyeJelly = true,
				exportCmo3 = true,
				exportMoc3 = true,
				exportJson = true,
			)
		}
		schedulePreviewRebuild()
	}

	fun setLanguage(language: AppLanguage) {
		I18n.setLanguage(language)
		_state.update { it.copy(currentLanguage = language) }
		schedulePreviewRebuild()
	}

	fun setWorkspaceTab(tab: WorkspaceTab) {
		_state.update { it.copy(activeWorkspaceTab = tab) }
	}

	fun addLog(
		message: String,
		level: LogLevel = LogLevel.INFO,
		source: LogSource = LogSource.SYSTEM,
		tag: String = "",
		imageBytes: ByteArray? = null,
		imageLabel: String? = null,
		detail: String? = null,
	) {
		val entry = AppLogEntry(
			source = source,
			level = level,
			tag = tag,
			message = message,
			detail = detail,
			imageBytes = imageBytes,
			imageLabel = imageLabel,
		)
		_state.update { current ->
			current.copy(
				logLines = current.logLines + message,
				logEntries = current.logEntries + entry,
			)
		}
	}

	fun clearLogs() {
		_state.update { it.copy(logLines = emptyList(), logEntries = emptyList()) }
	}

	private fun PSD2LiveState.withLog(
		message: String,
		level: LogLevel = LogLevel.INFO,
		tag: String = "System",
	): PSD2LiveState {
		val entry = AppLogEntry(source = LogSource.SYSTEM, level = level, tag = tag, message = message)
		return copy(logLines = logLines + message, logEntries = logEntries + entry)
	}

	private fun PSD2LiveState.withLogs(
		messages: List<String>,
		level: LogLevel = LogLevel.INFO,
		tag: String = "System",
	): PSD2LiveState {
		val entries = messages.map { AppLogEntry(source = LogSource.SYSTEM, level = level, tag = tag, message = it) }
		return copy(logLines = logLines + messages, logEntries = logEntries + entries)
	}

	fun setLogPanelExpanded(expanded: Boolean) {
		_state.update { it.copy(logPanelExpanded = expanded) }
	}

	fun setLogPanelHeight(height: Float) {
		_state.update { it.copy(logPanelHeight = height.coerceIn(80f, 450f)) }
	}

	fun openLightbox(imageBytes: ByteArray, title: String? = null) {
		_state.update { it.copy(lightboxImage = imageBytes, lightboxTitle = title) }
	}

	fun closeLightbox() {
		_state.update { it.copy(lightboxImage = null, lightboxTitle = null) }
	}

	fun updateHistorySnapshot(snapshot: AgentHistorySnapshot) {
		_state.update { it.copy(historySnapshot = snapshot) }
	}

	fun selectHistoryNode(nodeId: String?) {
		_state.update { it.copy(selectedHistoryNodeId = nodeId) }
	}

	fun checkoutHistoryNode(nodeId: String) {
		scope.launch {
			try {
				val ws = agentWorkspace ?: throw IllegalStateException("Agent workspace is not attached")
				val result = withContext(Dispatchers.Default) {
					ws.checkoutHistory(nodeId)
				}
				addLog(
					message = "Checked out history node: $nodeId (${result.summary})",
					level = LogLevel.SUCCESS,
					source = LogSource.AGENT,
					tag = "History",
				)
				_state.update { current ->
					current.copy(
						statusText = result.summary,
						selectedHistoryNodeId = nodeId,
					)
				}
			} catch (failure: Throwable) {
				val err = failure.message ?: failure.javaClass.simpleName
				addLog(
					message = "History checkout failed: $err",
					level = LogLevel.ERROR,
					source = LogSource.AGENT,
					tag = "History",
				)
				_state.update { it.copy(errorMessage = err) }
			}
		}
	}

	fun setInspectorTab(tab: InspectorTab) {
		_state.update { it.copy(activeInspectorTab = tab) }
	}

	fun setAnimationEnabled(enabled: Boolean) {
		_state.update { it.copy(animationEnabled = enabled) }
		lastTick = System.nanoTime()
	}

	fun setParameterSearchQuery(query: String) {
		_state.update { it.copy(parameterSearchQuery = query) }
	}

	fun selectLayer(layerId: String?) {
		_state.update {
			it.copy(
				selectedLayerId = layerId,
				selectedDeformerId = if (layerId != null) null else it.selectedDeformerId,
			)
		}
	}

	fun selectDeformer(deformerId: String?) {
		_state.update {
			it.copy(
				selectedDeformerId = deformerId,
				selectedLayerId = if (deformerId != null) null else it.selectedLayerId,
			)
		}
	}

	fun toggleLayerVisibility(layerId: String) {
		val current = _state.value.isLayerVisible(layerId)
		setLayerVisibility(layerId, !current)
	}

	fun setLayerVisibility(layerId: String, visible: Boolean) {
		_state.update {
			val updated = it.layerVisibility + (layerId to visible)
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
		schedulePreviewRebuild()
	}

	fun setAllLayersVisibility(visible: Boolean) {
		val analysis = _state.value.analysis ?: return
		val updated = analysis.layers.associate { it.source.id.raw to visible }
		_state.update {
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
		schedulePreviewRebuild()
	}

	fun invertLayerVisibility() {
		val analysis = _state.value.analysis ?: return
		val current = _state.value
		val updated = analysis.layers.associate { layer ->
			val id = layer.source.id.raw
			id to !current.isLayerVisible(id, layer.source.visible)
		}
		_state.update {
			it.copy(
				layerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
				isolationSnapshot = null,
				isolatedLayerId = null,
			)
		}
		schedulePreviewRebuild()
	}

	fun isolateLayer(layerId: String) {
		val analysis = _state.value.analysis ?: return
		val current = _state.value
		if (current.isolatedLayerId == layerId && current.isolationSnapshot != null) {
			_state.update {
				it.copy(
					layerVisibility = it.isolationSnapshot.orEmpty(),
					isolationSnapshot = null,
					isolatedLayerId = null,
					statusText = tr("status.visibilityChanged"),
				)
			}
		} else {
			val snapshot = current.layerVisibility
			val updated = analysis.layers.associate { it.source.id.raw to (it.source.id.raw == layerId) }
			_state.update {
				it.copy(
					layerVisibility = updated,
					isolationSnapshot = snapshot,
					isolatedLayerId = layerId,
					statusText = tr("status.visibilityChanged"),
				)
			}
		}
		schedulePreviewRebuild()
	}

	fun showOnlyLayers(layerIds: Set<String>) {
		if (layerIds.isEmpty()) return
		val analysis = _state.value.analysis ?: return
		val updated = analysis.layers.associate { it.source.id.raw to (it.source.id.raw in layerIds) }
		_state.update {
			it.copy(
				layerVisibility = updated,
				isolationSnapshot = null,
				isolatedLayerId = null,
				statusText = tr("status.visibilityChanged"),
			)
		}
		schedulePreviewRebuild()
	}

	fun deleteLayer(layerId: String) {
		val analysis = _state.value.analysis
		val layerName = analysis?.layers?.firstOrNull { it.source.id.raw == layerId }?.source?.name ?: layerId
		_state.update { current ->
			current.copy(
				deletedLayerIds = current.deletedLayerIds + layerId,
				selectedLayerId = if (current.selectedLayerId == layerId) null else current.selectedLayerId,
				statusText = tr("status.layerDeleted", layerName),
			)
		}
		schedulePreviewRebuild()
	}

	fun restoreLayer(layerId: String) {
		val analysis = _state.value.analysis
		val layerName = analysis?.layers?.firstOrNull { it.source.id.raw == layerId }?.source?.name ?: layerId
		_state.update { current ->
			current.copy(
				deletedLayerIds = current.deletedLayerIds - layerId,
				statusText = tr("status.layerRestored", layerName),
			)
		}
		schedulePreviewRebuild()
	}

	fun restoreAllDeletedLayers() {
		_state.update { current ->
			current.copy(
				deletedLayerIds = emptySet(),
				statusText = tr("status.allLayersRestored"),
			)
		}
		schedulePreviewRebuild()
	}

	fun reparentItem(childId: String, newParentId: String?) {
		val model = _state.value.previewModel
		val isDeformer = model?.rig?.puppet?.deformers?.any { it.id.raw == childId } ?: false
		val deformerById = model?.rig?.puppet?.deformers?.associateBy { it.id.raw } ?: emptyMap()

		// If child is a deformer, check for cycle
		if (isDeformer && newParentId != null) {
			if (childId == newParentId) return
			var cur: String? = newParentId
			val visited = mutableSetOf(childId)
			while (cur != null) {
				if (!visited.add(cur)) return
				cur = _state.value.parentOverrides[cur] ?: deformerById[cur]?.parent?.raw
			}
		}

		_state.update { current ->
			val updated = current.parentOverrides + (childId to newParentId)
			current.copy(
				parentOverrides = updated,
				statusText = tr("status.hierarchyUpdated"),
			)
		}
		schedulePreviewRebuild()
	}

	fun resetHierarchyOverrides() {
		_state.update { current ->
			current.copy(
				parentOverrides = emptyMap(),
				statusText = tr("status.hierarchyReset"),
			)
		}
		schedulePreviewRebuild()
	}

	fun resetItemHierarchy(itemId: String) {
		_state.update { current ->
			current.copy(
				parentOverrides = current.parentOverrides - itemId,
				statusText = tr("status.hierarchyUpdated"),
			)
		}
		schedulePreviewRebuild()
	}

	fun setLayerClassification(layerId: String, override: LayerClassificationOverride) {
		_state.update {
			it.copy(
				layerOverrides = it.layerOverrides + (layerId to override),
				statusText = tr("status.classificationChanged"),
			)
		}
		schedulePreviewRebuild()
	}

	fun toggleParameterLock(id: ParameterId, currentValue: Float? = null) {
		_state.update { current ->
			val wasLocked = id in current.lockedParameters
			if (wasLocked) {
				current.copy(
					lockedParameters = current.lockedParameters - id,
				)
			} else {
				val model = current.previewModel
				val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
				val defaultVal = param?.default ?: 0f
				val valueToLock = (currentValue ?: current.parameterValues[id] ?: defaultVal).let { v ->
					if (param != null) v.coerceIn(param.min, param.max) else v
				}
				current.copy(
					lockedParameters = current.lockedParameters + id,
					parameterValues = current.parameterValues + (id to valueToLock),
				)
			}
		}
	}

	fun setParameterValue(id: ParameterId, value: Float) {
		_state.update { current ->
			val model = current.previewModel
			val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
			val clamped = if (param != null) value.coerceIn(param.min, param.max) else value
			current.copy(
				parameterValues = current.parameterValues + (id to clamped),
			)
		}
	}

	fun resetParameter(id: ParameterId) {
		_state.update { current ->
			val model = current.previewModel
			val param = model?.rig?.puppet?.parameters?.firstOrNull { it.id == id }
			val defaultVal = param?.default ?: 0f
			current.copy(
				lockedParameters = current.lockedParameters + id,
				parameterValues = current.parameterValues + (id to defaultVal),
			)
		}
		if (id == StandardParameters.ANGLE_X || id == StandardParameters.EYE_BALL_X || id == StandardParameters.BODY_X) {
			followX = 0f
			pointerX = 0f
		}
		if (id == StandardParameters.ANGLE_Y || id == StandardParameters.EYE_BALL_Y || id == StandardParameters.BODY_Y) {
			followY = 0f
			pointerY = 0f
		}
	}

	fun resetAllParameters() {
		pointerActive = false
		pointerX = 0f
		pointerY = 0f
		followX = 0f
		followY = 0f
		previousFollowX = 0f
		frontHair = 0f
		backHair = 0f
		frontHairVelocity = 0f
		backHairVelocity = 0f
		eyeJellyDynamics.reset()
		elapsed = 0.0

		_state.update { current ->
			val model = current.previewModel
			val defaults = model?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
			current.copy(
				lockedParameters = defaults.keys,
				parameterValues = defaults,
			)
		}
	}

	fun unlockAllParameters() {
		_state.update { current ->
			current.copy(
				lockedParameters = emptySet(),
			)
		}
	}

	fun setMouseTrackingEnabled(enabled: Boolean) {
		_state.update { it.copy(mouseTrackingEnabled = enabled) }
		if (!enabled) {
			pointerActive = false
			pointerX = 0f
			pointerY = 0f
		}
	}

	fun updatePointer(screenNormX: Float, screenNormY: Float) {
		pointerActive = true
		pointerX = screenNormX.coerceIn(-1f, 1f)
		pointerY = screenNormY.coerceIn(-1f, 1f)
	}

	fun clearPointer() {
		pointerActive = false
	}

	fun clearErrorMessage() {
		_state.update { it.copy(errorMessage = null) }
	}

	fun clearSuccessExportMessage() {
		_state.update { it.copy(successExportMessage = null) }
	}

	fun analyze() {
		val rawInput = _state.value.inputPath.trim()
		if (rawInput.isEmpty()) {
			_state.update { it.copy(errorMessage = tr("dialog.inputRequired")) }
			return
		}
		val input = Path.of(rawInput)
		if (!Files.isRegularFile(input) || !input.fileName.toString().endsWith(".psd", true)) {
			_state.update { it.copy(errorMessage = tr("dialog.inputInvalid", input)) }
			return
		}

		activeWorkJob?.cancel()
		activeWorkJob = scope.launch {
			_state.update {
				it.copy(
					isAnalyzing = true,
					isIndeterminateProgress = true,
					progress = 0f,
					statusText = tr("status.analyzing"),
					errorMessage = null,
				)
			}
			try {
				val config = _state.value.buildConfig()
				val preview = withContext(Dispatchers.Default) {
					pipeline.buildPreview(input, config)
				}
				val inputSignature = runCatching {
					"${Files.size(input)}:${Files.getLastModifiedTime(input).toMillis()}"
				}.getOrNull()
				_state.update { current ->
					val recognized = preview.analysis.layers.count { it.semantic.tag != SemanticTag.UNKNOWN }
					val summary = tr(
						"status.analysisSummary",
						preview.analysis.source.widthPx,
						preview.analysis.source.heightPx,
						preview.analysis.layers.size,
						recognized,
					)
					val logLinesList = listOf(
						tr(
							"log.analysis",
							preview.analysis.layers.size,
							preview.analysis.anchors.character.width.toInt(),
							preview.analysis.anchors.character.height.toInt(),
						),
					) + preview.analysis.warnings.map { tr("log.warning", it) }
					current.withLogs(logLinesList, level = LogLevel.INFO, tag = "Analysis").copy(
						isAnalyzing = false,
						isIndeterminateProgress = false,
						analysis = preview.analysis,
						loadedInputPath = input.toAbsolutePath().normalize().toString(),
						loadedInputFileSignature = inputSignature,
						previewModel = preview,
						statusText = summary,
						lockedParameters = emptySet(),
						parameterValues = preview.rig.puppet.parameters.associate { it.id to it.default },
					)
				}
				sdkSession.load(preview.runtimeBundle, preview.rig.puppet.parameters.map { it.id })
			} catch (failure: Throwable) {
				val detail = failure.message ?: failure.javaClass.simpleName
				_state.update {
					it.withLog(tr("log.failed", detail), level = LogLevel.ERROR, tag = "Analysis").copy(
						isAnalyzing = false,
						isIndeterminateProgress = false,
						statusText = tr("status.failed", detail),
						errorMessage = detail,
					)
				}
			}
		}
	}

	fun generateRig(targetOutputPath: String? = null) {
		if (!targetOutputPath.isNullOrBlank()) {
			setOutputPath(targetOutputPath)
		}
		val rawInput = _state.value.inputPath.trim()
		if (rawInput.isEmpty()) {
			_state.update { it.copy(errorMessage = tr("dialog.inputRequired")) }
			return
		}
		var rawOutput = _state.value.outputPath.trim()
		if (rawOutput.isEmpty()) {
			try {
				val p = Path.of(rawInput)
				val parent = p.toAbsolutePath().parent
				val name = p.fileName.toString().substringBeforeLast('.')
				rawOutput = parent.resolve("$name-psd2live").toString()
				setOutputPath(rawOutput)
			} catch (_: Exception) {
				_state.update { it.copy(errorMessage = tr("dialog.outputRequired")) }
				return
			}
		}
		lastExportDirectory = rawOutput
		val input = Path.of(rawInput)
		val output = Path.of(rawOutput)
		val config = _state.value.buildConfig()
		val workspaceSource = _state.value.analysis?.source
		if (!config.exportCmo3 && !config.exportMoc3) {
			_state.update { it.copy(errorMessage = tr("dialog.exportFormatRequired")) }
			return
		}

		activeWorkJob?.cancel()
		activeWorkJob = scope.launch {
			_state.update {
				it.withLog(tr("status.generating"), level = LogLevel.INFO, tag = "Export").copy(
					isGenerating = true,
					isIndeterminateProgress = false,
					progress = 0f,
					statusText = tr("status.generating"),
					errorMessage = null,
					successExportMessage = null,
				)
			}
			try {
				val result = withContext(Dispatchers.Default) {
					val progress = ProgressListener { stage, fraction ->
							_state.update {
								it.withLog("%3d%%  %s".format((fraction * 100).toInt(), stage), level = LogLevel.INFO, tag = "Export").copy(
									progress = fraction.toFloat().coerceIn(0f, 1f),
									statusText = stage,
								)
							}
						}
					if (workspaceSource != null) {
						pipeline.run(workspaceSource, input.fileName.toString(), output, config, progress)
					} else {
						pipeline.run(input, output, config, progress)
					}
				}
				_state.update { current ->
					val outputLogs = listOf(
						tr("log.outputFiles"),
					) + result.exportedFiles.map { "• ${it.path} (${it.bytes} bytes)" } +
						(if (result.warnings.isNotEmpty()) listOf(tr("log.warnings")) + result.warnings.map { "• $it" } else emptyList())
					val summary = tr("status.completed", result.exportedFiles.size, result.warnings.size)
					current.withLogs(outputLogs, level = if (result.warnings.isNotEmpty()) LogLevel.WARNING else LogLevel.SUCCESS, tag = "Export").copy(
						isGenerating = false,
						progress = 1f,
						analysis = result.previewModel.analysis,
						loadedInputPath = current.loadedInputPath ?: input.toAbsolutePath().normalize().toString(),
						loadedInputFileSignature = current.loadedInputFileSignature ?: runCatching {
							"${Files.size(input)}:${Files.getLastModifiedTime(input).toMillis()}"
						}.getOrNull(),
						previewModel = result.previewModel,
						statusText = summary,
						successExportMessage = tr("dialog.exportSuccess", result.exportedFiles.size, output),
					)
				}
				sdkSession.load(result.previewModel.runtimeBundle, result.previewModel.rig.puppet.parameters.map { it.id })
			} catch (failure: Throwable) {
				val detail = failure.message ?: failure.javaClass.simpleName
				_state.update {
					it.withLog(tr("log.failed", detail), level = LogLevel.ERROR, tag = "Export").copy(
						isGenerating = false,
						statusText = tr("status.failed", detail),
						errorMessage = detail,
					)
				}
			}
		}
	}

	/** CPU-heavy rebuild used by the authenticated Agent transaction boundary. */
	internal suspend fun buildAgentWorkspacePreview(source: SourceArt, config: PipelineConfig): RigPreviewModel =
		withContext(Dispatchers.Default) { pipeline.buildPreview(source, config) }

	/** Publish one already-built authoritative workspace snapshot atomically to Compose and preview. */
	internal fun applyAgentWorkspacePreview(
		preview: RigPreviewModel,
		expectedSource: SourceArt,
		expectedLayerVisibility: Map<String, Boolean>,
		expectedDeletedLayerIds: Set<String>,
		expectedLayerOverrides: Map<String, LayerClassificationOverride>,
		expectedParentOverrides: Map<String, String?>,
		expectedRigEdits: RigEditOverlay,
		layerVisibility: Map<String, Boolean>,
		deletedLayerIds: Set<String>,
		layerOverrides: Map<String, LayerClassificationOverride>,
		parentOverrides: Map<String, String?>,
		rigEdits: RigEditOverlay,
		status: String,
	): Boolean {
		previewRebuildJob?.cancel()
		var applied = false
		_state.update { current ->
			applied = false
			if (
				current.analysis?.source !== expectedSource ||
				current.layerVisibility != expectedLayerVisibility ||
				current.deletedLayerIds != expectedDeletedLayerIds ||
				current.layerOverrides != expectedLayerOverrides ||
				current.parentOverrides != expectedParentOverrides ||
				current.rigEdits != expectedRigEdits
			) return@update current
			applied = true
			current.copy(
				analysis = preview.analysis,
				previewModel = preview,
				layerVisibility = layerVisibility,
				deletedLayerIds = deletedLayerIds,
				layerOverrides = layerOverrides,
				parentOverrides = parentOverrides,
				rigEdits = rigEdits,
				selectedLayerId = current.selectedLayerId?.takeIf { selected ->
					preview.analysis.layers.any { it.source.id.raw == selected } && selected !in deletedLayerIds
				},
				isolationSnapshot = null,
				isolatedLayerId = null,
				lockedParameters = current.lockedParameters.intersect(preview.rig.puppet.parameters.mapTo(mutableSetOf()) { it.id }),
				parameterValues = preview.rig.puppet.parameters.associate { parameter ->
					parameter.id to (current.parameterValues[parameter.id] ?: parameter.default).coerceIn(parameter.min, parameter.max)
				},
				statusText = status,
				logLines = current.logLines + status,
				errorMessage = null,
			)
		}
		return applied
	}

	internal fun loadAgentWorkspacePreview(preview: RigPreviewModel) {
		sdkSession.load(preview.runtimeBundle, preview.rig.puppet.parameters.map { it.id })
	}

	private fun schedulePreviewRebuild() {
		val previous = _state.value.previewModel ?: return
		if (_state.value.isAnalyzing || _state.value.isGenerating) return

		previewRebuildJob?.cancel()
		previewRebuildJob = scope.launch {
			delay(220)
			_state.update { it.copy(statusText = tr("status.applyingLayerChanges")) }
			try {
				val config = _state.value.buildConfig()
				val rebuilt = withContext(Dispatchers.Default) {
					pipeline.buildPreview(previous.analysis.source, config)
				}
				_state.update {
					it.copy(
						previewModel = rebuilt,
						analysis = rebuilt.analysis,
						statusText = tr("status.layerChangesApplied"),
					)
				}
				sdkSession.load(rebuilt.runtimeBundle, rebuilt.rig.puppet.parameters.map { it.id })
			} catch (failure: Throwable) {
				val detail = failure.message ?: failure.javaClass.simpleName
				_state.update {
					it.copy(
						statusText = tr("status.previewUpdateFailed", detail),
						logLines = it.logLines + listOf(tr("log.previewUpdateFailed", detail)),
					)
				}
			}
		}
	}

	private fun startMotionLoop() {
		motionJob = scope.launch {
			while (isActive) {
				val now = System.nanoTime()
				val dt = ((now - lastTick) / 1_000_000_000.0).coerceIn(0.001, 0.08).toFloat()
				lastTick = now

				val current = _state.value
				val anim = current.animationEnabled
				val tracking = current.mouseTrackingEnabled
				if (anim) elapsed += dt
				val blink = blinkAt(elapsed % 4.6)
				eyeJellyDynamics.advance(
					blink,
					dt,
					anim && current.generatePhysics && current.physicsEyeJelly,
				)

				val idleX = if (anim) (sin(elapsed * 0.47) * 0.12).toFloat() else 0f
				val idleY = if (anim) (sin(elapsed * 0.31 + 1.1) * 0.08).toFloat() else 0f
				val targetX = if (pointerActive && tracking) pointerX else idleX
				val targetY = if (pointerActive && tracking) pointerY else idleY
				val response = (dt * 7.5f).coerceAtMost(1f)
				previousFollowX = followX
				followX += (targetX - followX) * response
				followY += (targetY - followY) * response

				if (!pointerActive && kotlin.math.abs(followX - targetX) < 0.001f) followX = targetX
				if (!pointerActive && kotlin.math.abs(followY - targetY) < 0.001f) followY = targetY

				if (anim) {
					val headVelocity = ((followX - previousFollowX) / dt).coerceIn(-5f, 5f)
					val hairTarget = (-followX * 0.42f - headVelocity * 0.085f).coerceIn(-1f, 1f)
					frontHairVelocity += ((hairTarget - frontHair) * 22f - frontHairVelocity * 7.2f) * dt
					backHairVelocity += ((hairTarget - backHair) * 10f - backHairVelocity * 4.2f) * dt
					frontHair += frontHairVelocity * dt
					backHair += backHairVelocity * dt
				}

				val model = current.previewModel
				if (model != null && anim) {
					val liveParams = computeLiveParameters(model)
					_state.update { latest ->
						val mergedValues = parameterValuesAfterSoftwareFrame(latest, liveParams)
						if (mergedValues === latest.parameterValues) latest else latest.copy(parameterValues = mergedValues)
					}
				}

				delay(33)
			}
		}
	}

	fun computeLiveParameters(model: RigPreviewModel): Map<ParameterId, Float> {
		val phase = elapsed % 4.6
		val blink = blinkAt(phase)
		val mouthPhase = elapsed % 5.8
		val mouthOpen = if (mouthPhase in 1.25..2.45) {
			sin((mouthPhase - 1.25) / 1.20 * PI).toFloat().coerceAtLeast(0f)
		} else 0f

		return mapOf(
			StandardParameters.ANGLE_X to followX * 38f,
			StandardParameters.ANGLE_Y to -followY * 24f,
			StandardParameters.ANGLE_Z to (sin(elapsed * PI / 1.5) * 2.0).toFloat(),
			StandardParameters.BODY_X to (followX * 4f + sin(elapsed * 0.72).toFloat() * 1.2f),
			StandardParameters.BODY_Y to -followY * 2f,
			StandardParameters.BODY_Z to sin(elapsed * 0.92).toFloat() * 2.2f,
			StandardParameters.EYE_BALL_X to followX.coerceIn(-1f, 1f),
			StandardParameters.EYE_BALL_Y to (-followY).coerceIn(-1f, 1f),
			StandardParameters.EYE_BALL_FORM to if (model.config.generatePhysics && model.config.physicsEyeJelly) eyeJellyDynamics.value else 0f,
			StandardParameters.EYE_L_OPEN to blink,
			StandardParameters.EYE_R_OPEN to blink,
			StandardParameters.MOUTH_FORM to sin(elapsed * 0.41).toFloat() * 0.18f,
			StandardParameters.MOUTH_OPEN to mouthOpen,
			StandardParameters.BREATH to ((sin(elapsed * 1.45) + 1.0) * 0.5).toFloat(),
			StandardParameters.HAIR_FRONT to if (model.config.generatePhysics && model.config.physicsFrontHair) frontHair.coerceIn(-1f, 1f) else 0f,
			StandardParameters.HAIR_BACK to if (model.config.generatePhysics && model.config.physicsBackHair) backHair.coerceIn(-1f, 1f) else 0f,
		)
	}

	private fun blinkAt(phase: Double): Float = if (phase in 4.18..4.46) {
		(1.0 - sin((phase - 4.18) / 0.28 * PI)).toFloat().coerceIn(0f, 1f)
	} else 1f

	fun requestSdkFrame(
		width: Int,
		height: Int,
		scale: Float,
		offsetX: Float,
		offsetY: Float,
		deltaTime: Float = 1f / 60f,
		frameTimeNanos: Long = System.nanoTime(),
	) {
		val current = _state.value
		val model = current.previewModel ?: return
		val tracking = current.mouseTrackingEnabled
		val previewValues = parameterValuesForPreview(current)
		sdkSession.render(
			CubismSdkPreviewSession.RenderRequest(
				width = width,
				height = height,
				scale = scale,
				offsetX = offsetX,
				offsetY = offsetY,
				deltaTime = deltaTime,
				// Cubism receives the pointer target and performs its own critically damped tracking.
				// X stays raw for native hair inertia; Y uses UI smoothing because it is applied
				// separately to keep mouse tracking from owning ParamAngleZ.
				pointerX = if (pointerActive && tracking) pointerX else 0f,
				pointerY = if (pointerActive && tracking) -followY else 0f,
				animationEnabled = current.animationEnabled,
				parameterOverrides = previewValues,
				frameTimeNanos = frameTimeNanos,
			),
		)
	}

	override fun close() {
		motionJob?.cancel()
		previewRebuildJob?.cancel()
		activeWorkJob?.cancel()
		sdkSession.close()
	}

	private companion object {
		const val SDK_PARAMETER_PUBLISH_INTERVAL_NANOS = 33_333_333L
		const val PREF_LAST_EXPORT_DIR = "last_export_dir"
	}
}

internal fun mergeUnlockedParameterValues(
	current: Map<ParameterId, Float>,
	incoming: Map<ParameterId, Float>,
	locked: Set<ParameterId>,
): Map<ParameterId, Float> {
	if (incoming.isEmpty()) return current
	val merged = current.toMutableMap()
	var changed = false
	for ((id, value) in incoming) {
		if (id !in locked && merged[id] != value) {
			merged[id] = value
			changed = true
		}
	}
	return if (changed) merged else current
}

internal fun parameterValuesForPreview(state: PSD2LiveState): Map<ParameterId, Float> {
	val standardIds = StandardParameters.all.map { it.id }.toSet()
	return if (state.animationEnabled) {
		state.parameterValues.filterKeys { it in state.lockedParameters || it !in standardIds }
	} else {
		state.parameterValues
	}
}

internal fun parameterValuesAfterPreviewFrame(
	state: PSD2LiveState,
	incoming: Map<ParameterId, Float>,
): Map<ParameterId, Float> =
	if (state.animationEnabled) {
		mergeUnlockedParameterValues(state.parameterValues, incoming, state.lockedParameters)
	} else {
		state.parameterValues
	}

internal fun parameterValuesAfterSoftwareFrame(
	state: PSD2LiveState,
	incoming: Map<ParameterId, Float>,
): Map<ParameterId, Float> =
	if (state.animationEnabled && state.sdkStatus != "ready") {
		mergeUnlockedParameterValues(state.parameterValues, incoming, state.lockedParameters)
	} else {
		state.parameterValues
	}

internal fun previewFrameMatchesState(
	state: PSD2LiveState,
	frameAnimationEnabled: Boolean,
): Boolean = frameAnimationEnabled == state.animationEnabled
