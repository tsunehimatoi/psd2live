package io.github.psd2live.ui.state

import io.github.psd2live.core.MeshSettings

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
import kotlinx.coroutines.cancel
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
    private val projectSession = io.github.psd2live.project.ProjectSession(this)
    private var pendingDestructiveAction: (() -> Unit)? = null
    var confirmUnsavedChanges: (() -> Int)? = null

    fun withSavedChanges(action: () -> Unit) {
        if (_state.value.projectSaving) return
        if (!_state.value.projectDirty) { action(); return }
        when (confirmUnsavedChanges?.invoke() ?: 2) {
            0 -> { pendingDestructiveAction = action; requestProjectSave() }
            1 -> action()
        }
    }
    fun requestProjectSave(saveAs: Boolean = false) {
        if (_state.value.analysis == null) return
        if (saveAs || _state.value.projectFile == null) {
            _state.update { it.copy(showProjectLocationDialog = true, projectSaveError = null) }
        } else saveProjectTo(Path.of(_state.value.projectFile!!))
    }
    fun clearProjectSaveError() { _state.update { it.copy(projectSaveError = null) } }
    fun cancelProjectLocation() {
        pendingDestructiveAction = null
        _state.update { it.copy(showProjectLocationDialog = false) }
    }
    fun saveProjectTo(path: Path) {
        scope.launch {
            try {
                saveProjectNow(path)
                _state.update { it.copy(showProjectLocationDialog = false) }
                if (!_state.value.projectDirty) pendingDestructiveAction?.also { pendingDestructiveAction = null; it() }
            } catch (_: Exception) { pendingDestructiveAction = null }
        }
    }
    internal suspend fun saveProjectNow(path: Path? = null, actor: String = "user"): String {
        val target = path ?: _state.value.projectFile?.let(Path::of) ?: error("Choose a project save location in the application first")
        val workspace = agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace ?: error("Project workspace unavailable")
        return projectSession.save(workspace, target, actor)
    }
    fun openProject(path: Path) = withSavedChanges {
        scope.launch {
            try {
                val workspace = agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace ?: error("Project workspace unavailable")
                projectSession.open(workspace, path)
            } catch (failure: Exception) { _state.update { it.copy(errorMessage = failure.message) } }
        }
    }
    internal fun installProjectState(state: PSD2LiveState) {
        previewRebuildJob?.cancel()
        activeWorkJob?.cancel()
        _state.value = state.copy(projectDirty = false, projectOpenGeneration = _state.value.projectOpenGeneration + 1)
    }
    private val pendingProjectSaves = java.util.concurrent.atomic.AtomicInteger()
    internal fun projectSaveStarted() { pendingProjectSaves.incrementAndGet(); _state.update { it.copy(projectSaving = true, projectSaveError = null) } }
    internal fun projectSaveFailed(failure: Exception) { val saving = pendingProjectSaves.decrementAndGet() > 0; _state.update { it.copy(projectSaving = saving, projectDirty = true, projectSaveError = failure.message ?: "Save failed") } }
    internal fun projectSaveFinished(path: Path, headId: String, captured: PSD2LiveState) {
        val saving = pendingProjectSaves.decrementAndGet() > 0
        _state.update { current -> current.copy(projectFile = path.toAbsolutePath().normalize().toString(), projectSaving = saving,
            projectDirty = current.historySnapshot?.headNodeId != headId || current.projectAuxiliaryVersion != captured.projectAuxiliaryVersion || io.github.psd2live.project.WorkspaceStateCodec.editableIdentity(current) != io.github.psd2live.project.WorkspaceStateCodec.editableIdentity(captured), projectSaveError = null) }
    }
    internal fun markProjectAuxiliaryChanged() { _state.update { it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1, projectAuxiliaryVersion = it.projectAuxiliaryVersion + 1) } }
    private fun markWorkspaceChanged() { _state.update { if (it.analysis == null) it else it.copy(projectDirty = true, projectEditVersion = it.projectEditVersion + 1) } }
    private var editorGestureActive = false
    fun beginEditorGesture() { editorGestureActive = true }
    fun endEditorGesture() { editorGestureActive = false; editorChanged() }
    private fun editorChanged() {
        if (editorGestureActive) { markWorkspaceChanged(); return }
        if (_state.value.analysis == null) return
        (agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace)?.editorChanged()
    }
    fun editHistoryAnnotation(id: String, title: String, note: String, hidden: Boolean) {
        require(_state.value.historySnapshot?.nodes?.any { it.id == id } == true)
        _state.update { it.copy(historyAnnotations = it.historyAnnotations + (id to HistoryAnnotation(title.trim(), note, hidden)), projectDirty = true, projectEditVersion = it.projectEditVersion + 1) }
    }
    fun undoHistory() {
        val history = _state.value.historySnapshot ?: return
        history.nodes.firstOrNull { it.id == history.headNodeId }?.parentId?.let(::checkoutHistoryNode)
    }
    fun redoHistory() {
        val history = _state.value.historySnapshot ?: return
        val children = history.nodes.filter { it.parentId == history.headNodeId }
        if (children.size == 1) checkoutHistoryNode(children.single().id)
        else setWorkspaceTab(io.github.psd2live.ui.state.WorkspaceTab.HISTORY)
    }
    fun setHistoryView(zoom: Float, x: Float, y: Float, search: String, showHidden: Boolean) {
        _state.update { if (it.historyZoom == zoom && it.historyPanX == x && it.historyPanY == y && it.historySearch == search && it.historyShowHidden == showHidden) it
            else it.copy(historyZoom = zoom, historyPanX = x, historyPanY = y, historySearch = search, historyShowHidden = showHidden, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1) }
    }
    fun setHierarchyView(width: Float = _state.value.hierarchyWidth, collapsed: Boolean = _state.value.hierarchyCollapsed, search: String = _state.value.hierarchySearch) {
        val clampedWidth = width.coerceIn(140f, 600f)
        _state.update {
            if (it.hierarchyWidth == clampedWidth && it.hierarchyCollapsed == collapsed && it.hierarchySearch == search) it
            else it.copy(hierarchyWidth = clampedWidth, hierarchyCollapsed = collapsed, hierarchySearch = search, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }
    fun adjustHierarchyWidth(deltaDp: Float, min: Float = 140f, max: Float = 600f) {
        _state.update {
            val next = (it.hierarchyWidth + deltaDp).coerceIn(min, max)
            if (next == it.hierarchyWidth) it
            else it.copy(hierarchyWidth = next, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }
    fun setModelSettingsExpanded(expanded: Boolean) { _state.update { it.copy(modelSettingsExpanded = expanded, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1) } }
    fun setWorkspaceSplitRatio(value: Float) {
        val clamped = value.coerceIn(0.25f, 0.85f)
        _state.update {
            if (it.workspaceSplitRatio == clamped) it
            else it.copy(workspaceSplitRatio = clamped, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }
    fun adjustWorkspaceSplitRatio(deltaRatio: Float, min: Float = 0.25f, max: Float = 0.85f) {
        _state.update {
            val next = (it.workspaceSplitRatio + deltaRatio).coerceIn(min, max)
            if (next == it.workspaceSplitRatio) it
            else it.copy(workspaceSplitRatio = next, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1)
        }
    }
    fun setCanvasView(zoom: Float, x: Float, y: Float) { _state.update { it.copy(canvasZoom = zoom, canvasPanX = x, canvasPanY = y, projectDirty = it.analysis != null, projectEditVersion = it.projectEditVersion + 1) } }

	fun attachAgentWorkspace(workspace: AgentWorkspace) {
		agentWorkspace = workspace
		runCatching {
			val snapshot = workspace.history()
			_state.update { it.copy(historySnapshot = snapshot, projectDirty = it.projectDirty || (it.historySnapshot != null && it.historySnapshot.headNodeId != snapshot.headNodeId), projectEditVersion = it.projectEditVersion + if (it.historySnapshot?.headNodeId != snapshot.headNodeId) 1 else 0) }
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
				val publishParameters = current.animationEnabled && !current.meshOnly &&
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
	    markWorkspaceChanged()
	}

	fun setAtlasSize(size: Int) {
		_state.update { it.copy(atlasSize = size) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshSpacing(spacing: Int) {
		_state.update { it.copy(meshSpacing = spacing.coerceIn(16, 128), meshMaxEdgeDistance = spacing.toFloat(), meshInteriorDensity = spacing.toFloat()) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshOuterMargin(margin: Float) {
		_state.update { it.copy(meshOuterMargin = margin.coerceIn(0f, 32f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshInnerMargin(margin: Float) {
		_state.update { it.copy(meshInnerMargin = margin.coerceIn(0.5f, 32f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshMaxEdgeDistance(distance: Float) {
		_state.update { it.copy(meshMaxEdgeDistance = distance.coerceIn(6f, 128f), meshSpacing = distance.toInt().coerceIn(16, 128)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setMeshInteriorDensity(density: Float) {
		_state.update { it.copy(meshInteriorDensity = density.coerceIn(6f, 128f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setPartMeshSettings(layerId: String, settings: MeshSettings) {
		_state.update { it.copy(meshOverrides = it.meshOverrides + (layerId to settings)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun resetPartMeshSettings(layerId: String) {
		_state.update { it.copy(meshOverrides = it.meshOverrides - layerId) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setHeadStrength(strength: Float) {
		_state.update { it.copy(headStrength = strength.coerceIn(0f, 4f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setBodyStrength(strength: Float) {
		_state.update { it.copy(bodyStrength = strength.coerceIn(0f, 4f)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setTexturePadding(padding: Int) {
		_state.update { it.copy(texturePadding = padding.coerceIn(0, 32)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setAlphaThreshold(threshold: Int) {
		_state.update { it.copy(alphaThreshold = threshold.coerceIn(0, 255)) }
		schedulePreviewRebuild()
	    editorChanged()
	}

    fun setMouthOutlineEnabled(enabled: Boolean) {
        _state.update { it.copy(mouthOutlineEnabled = enabled) }
        schedulePreviewRebuild()
        editorChanged()
    }
    fun setMouthShape(shape: String) {
        require(shape in listOf("flat", "smile", "w"))
        _state.update { it.copy(mouthShape = shape, mouthCurve = io.github.psd2live.core.MouthCurve.preset(shape)) }
        schedulePreviewRebuild()
        editorChanged()
    }
    fun setMouthSettings(shape: String, curve: io.github.psd2live.core.MouthCurve, color: Int?, thickness: Float) {
        require(shape in io.github.psd2live.core.MouthCurve.presets + "custom")
        require(color == null || color in 0..0xFFFFFF)
        require(thickness.isFinite() && thickness in 0.5f..8f)
        _state.update { it.copy(mouthShape = shape, mouthCurve = curve, mouthColor = color, mouthThickness = thickness) }
        schedulePreviewRebuild()
        editorChanged()
    }

	fun setMeshOnly(enabled: Boolean) {
		_state.update { current ->
			val updated = current.copy(meshOnly = enabled, generateDeformers = !enabled)
			if (enabled) {
				val defaults = current.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
				val resetMap = defaults.filterKeys { it !in current.lockedParameters }
				updated.copy(parameterValues = current.parameterValues + resetMap)
			} else updated
		}
		if (enabled) {
			frontHair = 0f
			frontHairVelocity = 0f
			backHair = 0f
			backHairVelocity = 0f
			eyeJellyDynamics.reset()
			followX = 0f
			followY = 0f
			previousFollowX = 0f
			activeSoftwareMotionName = null
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setGenerateDeformers(enabled: Boolean) {
		_state.update { it.copy(generateDeformers = enabled) }
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setFeatureDisplacementEnabled(enabled: Boolean) {
		_state.update { it.copy(featureDisplacementEnabled = enabled) }
		schedulePreviewRebuild()
		editorChanged()
	}

	fun setExportMotions(enabled: Boolean) {
		_state.update { it.copy(exportMotions = enabled) }
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setMotionIdle(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(motionIdle = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
			if (!enabled) {
				val idleReset = mapOf(
					StandardParameters.ANGLE_X to 0f,
					StandardParameters.ANGLE_Y to 0f,
					StandardParameters.ANGLE_Z to 0f,
					StandardParameters.BODY_X to 0f,
					StandardParameters.BODY_Y to 0f,
					StandardParameters.BODY_Z to 0f,
					StandardParameters.BREATH to 0f,
					StandardParameters.MOUTH_OPEN to 0f,
					StandardParameters.MOUTH_FORM to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + idleReset)
			} else updated
		}
		if (!enabled) {
			followX = 0f
			followY = 0f
			previousFollowX = 0f
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setMotionBlink(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(motionBlink = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
			if (!enabled) {
				val blinkReset = mapOf(
					StandardParameters.EYE_L_OPEN to 1.0f,
					StandardParameters.EYE_R_OPEN to 1.0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + blinkReset)
			} else updated
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setMotionNod(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(motionNod = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
			if (!enabled && activeSoftwareMotionName == "nod") {
				val nodReset = mapOf(
					StandardParameters.ANGLE_Y to 0f,
					StandardParameters.BODY_Y to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + nodReset)
			} else updated
		}
		if (!enabled && activeSoftwareMotionName == "nod") activeSoftwareMotionName = null
		scheduleRuntimeBundleUpdate()
		if (enabled) triggerMotion("Nod")
	    editorChanged()
	}

	fun setMotionShake(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(motionShake = enabled)
			val updated = next.copy(exportMotions = next.motionIdle || next.motionBlink || next.motionNod || next.motionShake)
			if (!enabled && activeSoftwareMotionName == "shake") {
				val shakeReset = mapOf(
					StandardParameters.ANGLE_X to 0f,
					StandardParameters.BODY_X to 0f,
					StandardParameters.ANGLE_Z to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + shakeReset)
			} else updated
		}
		if (!enabled && activeSoftwareMotionName == "shake") activeSoftwareMotionName = null
		scheduleRuntimeBundleUpdate()
		if (enabled) triggerMotion("Shake")
	    editorChanged()
	}

	fun setGeneratePhysics(enabled: Boolean) {
		_state.update { current ->
			val updated = current.copy(generatePhysics = enabled)
			if (!enabled) {
				val physReset = mapOf(
					StandardParameters.HAIR_FRONT to 0f,
					StandardParameters.HAIR_BACK to 0f,
					StandardParameters.EYE_BALL_FORM to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + physReset)
			} else updated
		}
		if (!enabled) {
			frontHair = 0f
			frontHairVelocity = 0f
			backHair = 0f
			backHairVelocity = 0f
			eyeJellyDynamics.reset()
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setPhysicsFrontHair(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(physicsFrontHair = enabled)
			val updated = next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
			if (!enabled) {
				val hairReset = mapOf(
					StandardParameters.HAIR_FRONT to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + hairReset)
			} else updated
		}
		if (!enabled) {
			frontHair = 0f
			frontHairVelocity = 0f
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setPhysicsBackHair(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(physicsBackHair = enabled)
			val updated = next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
			if (!enabled) {
				val hairReset = mapOf(
					StandardParameters.HAIR_BACK to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + hairReset)
			} else updated
		}
		if (!enabled) {
			backHair = 0f
			backHairVelocity = 0f
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setPhysicsEyeJelly(enabled: Boolean) {
		_state.update { current ->
			val next = current.copy(physicsEyeJelly = enabled)
			val updated = next.copy(generatePhysics = next.physicsFrontHair || next.physicsBackHair || next.physicsEyeJelly)
			if (!enabled) {
				val jellyReset = mapOf(
					StandardParameters.EYE_BALL_FORM to 0f,
				).filterKeys { key -> key !in updated.lockedParameters }
				updated.copy(parameterValues = updated.parameterValues + jellyReset)
			} else updated
		}
		if (!enabled) {
			eyeJellyDynamics.reset()
		}
		scheduleRuntimeBundleUpdate()
	    editorChanged()
	}

	fun setExportCmo3(enabled: Boolean) {
		_state.update { it.copy(exportCmo3 = enabled) }
	    editorChanged()
	}

	fun setExportMoc3(enabled: Boolean) {
		_state.update { it.copy(exportMoc3 = enabled) }
	    editorChanged()
	}

	fun setExportJson(enabled: Boolean) {
		_state.update { it.copy(exportJson = enabled) }
	    editorChanged()
	}

	fun setExportOptionsExpanded(expanded: Boolean) {
		_state.update { it.copy(exportOptionsExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setMotionSubExpanded(expanded: Boolean) {
		_state.update { it.copy(motionSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setPhysicsSubExpanded(expanded: Boolean) {
		_state.update { it.copy(physicsSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setDynamicsSubExpanded(expanded: Boolean) {
		_state.update { it.copy(dynamicsSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setProjectOutputsExpanded(expanded: Boolean) {
		_state.update { it.copy(projectOutputsExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setTextureSubExpanded(expanded: Boolean) {
		_state.update { it.copy(textureSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setMeshSubExpanded(expanded: Boolean) {
		_state.update { it.copy(meshSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setStrengthSubExpanded(expanded: Boolean) {
		_state.update { it.copy(strengthSubExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun setAdvancedExpanded(expanded: Boolean) {
		_state.update { it.copy(advancedExpanded = expanded) }
	    markWorkspaceChanged()
	}

	fun resetSettingsToDefault() {
		_state.update {
			it.copy(
				atlasSize = 4096,
				textureSubExpanded = false,
				meshSubExpanded = false,
				strengthSubExpanded = false,
				dynamicsSubExpanded = false,
				meshSpacing = 40,
				meshOuterMargin = 1.0f,
				meshInnerMargin = 10.0f,
				meshMaxEdgeDistance = 6.0f,
				meshInteriorDensity = 40.0f,
				meshOverrides = emptyMap(),
				texturePadding = 2,
				alphaThreshold = 8,
				headStrength = 1.0f,
				bodyStrength = 1.0f,
				meshOnly = false,
				generateDeformers = true,
				featureDisplacementEnabled = true,
                mouthOutlineEnabled = true,
                mouthShape = "smile",
                mouthCurve = io.github.psd2live.core.MouthCurve.preset("smile"),
                mouthColor = null,
                mouthThickness = 1.5f,
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
	    editorChanged()
	}

	fun setLanguage(language: AppLanguage) {
		I18n.setLanguage(language)
		_state.update { it.copy(currentLanguage = language) }
		schedulePreviewRebuild()
	}

	fun setWorkspaceTab(tab: WorkspaceTab) {
		val effectiveTab = if (tab == WorkspaceTab.HIERARCHY) WorkspaceTab.PREVIEW else tab
		_state.update { current ->
			val updateShowMesh = if (effectiveTab == WorkspaceTab.TOPOLOGY && !current.showMesh) true else current.showMesh
			val updateShowTexture = if (effectiveTab == WorkspaceTab.PREVIEW && !current.showTexture) true else current.showTexture
			current.copy(
				activeWorkspaceTab = effectiveTab,
				showMesh = updateShowMesh,
				showTexture = updateShowTexture,
			)
		}
	    markWorkspaceChanged()
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
	    markWorkspaceChanged()
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
	    markWorkspaceChanged()
	}

	fun setLogPanelHeight(height: Float) {
		val clamped = height.coerceIn(80f, 450f)
		var changed = false
		_state.update {
			if (it.logPanelHeight == clamped) it
			else {
				changed = true
				it.copy(logPanelHeight = clamped)
			}
		}
		if (changed) markWorkspaceChanged()
	}

	fun adjustLogPanelHeight(deltaDp: Float, min: Float = 80f, max: Float = 450f) {
		_state.update {
			val next = (it.logPanelHeight + deltaDp).coerceIn(min, max)
			if (next == it.logPanelHeight) it
			else it.copy(logPanelHeight = next)
		}
		markWorkspaceChanged()
	}

	fun openLightbox(imageBytes: ByteArray, title: String? = null) {
		_state.update { it.copy(lightboxImage = imageBytes, lightboxTitle = title) }
	}

	fun closeLightbox() {
		_state.update { it.copy(lightboxImage = null, lightboxTitle = null) }
	}

	fun updateHistorySnapshot(snapshot: AgentHistorySnapshot) {
		_state.update { it.copy(historySnapshot = snapshot, projectDirty = it.projectDirty || (it.historySnapshot != null && it.historySnapshot.headNodeId != snapshot.headNodeId), projectEditVersion = it.projectEditVersion + if (it.historySnapshot?.headNodeId != snapshot.headNodeId) 1 else 0) }
	}

	fun selectHistoryNode(nodeId: String?) {
		_state.update { it.copy(selectedHistoryNodeId = nodeId) }
	    markWorkspaceChanged()
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
	    markWorkspaceChanged()
	}

	fun setAnimationEnabled(enabled: Boolean) {
		_state.update { it.copy(animationEnabled = enabled) }
		lastTick = System.nanoTime()
	    markWorkspaceChanged()
	}

	fun setParameterSearchQuery(query: String) {
		_state.update { it.copy(parameterSearchQuery = query) }
	    markWorkspaceChanged()
	}

	fun selectLayer(layerId: String?) {
		_state.update {
			it.copy(
				selectedLayerId = layerId,
				selectedDeformerId = if (layerId != null) null else it.selectedDeformerId,
			)
		}
	    markWorkspaceChanged()
	}

	fun selectDeformer(deformerId: String?) {
		_state.update {
			it.copy(
				selectedDeformerId = deformerId,
				selectedLayerId = if (deformerId != null) null else it.selectedLayerId,
			)
		}
	    markWorkspaceChanged()
	}

	fun setShowWarp(show: Boolean) {
		_state.update { it.copy(showWarp = show) }
		markWorkspaceChanged()
	}

	fun setShowMesh(show: Boolean) {
		_state.update { it.copy(showMesh = show) }
		markWorkspaceChanged()
	}

	fun setShowTexture(show: Boolean) {
		_state.update { it.copy(showTexture = show) }
		markWorkspaceChanged()
	}

	fun setWarpShowNames(show: Boolean) {
		_state.update { it.copy(warpShowNames = show) }
		markWorkspaceChanged()
	}

	fun setWarpShowIndices(show: Boolean) {
		_state.update { it.copy(warpShowIndices = show, showWarp = if (show) true else it.showWarp) }
		markWorkspaceChanged()
	}

	fun setFilterSelectedOnly(selectedOnly: Boolean) {
		_state.update { it.copy(filterSelectedOnly = selectedOnly) }
		markWorkspaceChanged()
	}

	fun setDimUnselected(enabled: Boolean) {
		_state.update { it.copy(dimUnselected = enabled) }
		markWorkspaceChanged()
	}

	fun setContextualWarp(enabled: Boolean) {
		_state.update { it.copy(contextualWarp = enabled) }
		markWorkspaceChanged()
	}

	fun setShowSelectionBounds(show: Boolean) {
		_state.update { it.copy(showSelectionBounds = show) }
		markWorkspaceChanged()
	}

	fun setHoveredItem(layerId: String?, deformerId: String?) {
		_state.update {
			if (it.hoveredLayerId == layerId && it.hoveredDeformerId == deformerId) it
			else it.copy(hoveredLayerId = layerId, hoveredDeformerId = deformerId)
		}
	}

	fun toggleDeformerVisibility(deformerId: String) {
		val current = _state.value.isDeformerVisible(deformerId)
		setDeformerVisibility(deformerId, !current)
	}

	fun setDeformerVisibility(deformerId: String, visible: Boolean) {
		_state.update {
			val updated = it.deformerVisibility + (deformerId to visible)
			it.copy(
				deformerVisibility = updated,
				statusText = tr("status.visibilityChanged"),
			)
		}
		schedulePreviewRebuild()
		editorChanged()
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
	    editorChanged()
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
	    editorChanged()
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
	    editorChanged()
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
	    editorChanged()
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
	    editorChanged()
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
	    editorChanged()
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
	    editorChanged()
	}

	fun restoreAllDeletedLayers() {
		_state.update { current ->
			current.copy(
				deletedLayerIds = emptySet(),
				statusText = tr("status.allLayersRestored"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
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
	    editorChanged()
	}

	fun resetHierarchyOverrides() {
		_state.update { current ->
			current.copy(
				parentOverrides = emptyMap(),
				statusText = tr("status.hierarchyReset"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun resetItemHierarchy(itemId: String) {
		_state.update { current ->
			current.copy(
				parentOverrides = current.parentOverrides - itemId,
				statusText = tr("status.hierarchyUpdated"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
	}

	fun setLayerDrawOrder(targetId: String, order: Float) {
		val clamped = order.coerceIn(0f, 1000f)
		val model = _state.value.previewModel
		val layerId = model?.rig?.layerIdByDrawableId?.get(targetId) ?: targetId
		_state.update { current ->
			val updated = current.drawOrderOverrides + (layerId to clamped)
			current.copy(drawOrderOverrides = updated)
		}
		editorChanged()
	}

	fun resetLayerDrawOrder(targetId: String) {
		val model = _state.value.previewModel
		val layerId = model?.rig?.layerIdByDrawableId?.get(targetId) ?: targetId
		_state.update { current ->
			current.copy(drawOrderOverrides = current.drawOrderOverrides - layerId - targetId)
		}
		editorChanged()
	}

	fun resetAllDrawOrders() {
		_state.update { current ->
			current.copy(drawOrderOverrides = emptyMap())
		}
		editorChanged()
	}

	fun setLayerClassification(layerId: String, override: LayerClassificationOverride) {
		_state.update {
			it.copy(
				layerOverrides = it.layerOverrides + (layerId to override),
				statusText = tr("status.classificationChanged"),
			)
		}
		schedulePreviewRebuild()
	    editorChanged()
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
	    markWorkspaceChanged()
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
	    markWorkspaceChanged()
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
	    markWorkspaceChanged()
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
		activeSoftwareMotionName = null
		lastTick = System.nanoTime()

		_state.update { current ->
			val model = current.previewModel
			val defaults = model?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
			current.copy(
				animationEnabled = false,
				lockedParameters = emptySet(),
				parameterValues = defaults,
			)
		}
	    markWorkspaceChanged()
	}

	fun unlockAllParameters() {
		_state.update { current ->
			current.copy(
				lockedParameters = emptySet(),
			)
		}
	    markWorkspaceChanged()
	}

	fun setMouseTrackingEnabled(enabled: Boolean) {
		_state.update { it.copy(mouseTrackingEnabled = enabled) }
		if (!enabled) {
			pointerActive = false
			pointerX = 0f
			pointerY = 0f
		}
	    markWorkspaceChanged()
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
				val config = _state.value.copy(layerVisibility = emptyMap(), layerOverrides = emptyMap(), deletedLayerIds = emptySet(), parentOverrides = emptyMap(), rigEdits = RigEditOverlay.Empty).buildConfig()
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
						isIndeterminateProgress = false,
						projectId = java.util.UUID.randomUUID().toString(),
                        projectSourceName = input.fileName.toString(),
                        projectFile = null, projectDirty = true, showProjectLocationDialog = false, isAnalyzing = true,
                        layerVisibility = emptyMap(), layerOverrides = emptyMap(), deletedLayerIds = emptySet(), parentOverrides = emptyMap(), rigEdits = RigEditOverlay.Empty,
                        selectedLayerId = null, selectedDeformerId = null, isolatedLayerId = null, isolationSnapshot = null,
                        canvasZoom = 1f, canvasPanX = 0f, canvasPanY = 0f,
                        historySnapshot = null, historyAnnotations = emptyMap(),
                        projectOpenGeneration = current.projectOpenGeneration + 1,
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
                (agentWorkspace as? io.github.psd2live.agent.ViewModelAgentWorkspace)?.importedPsd()
                _state.update { it.copy(isAnalyzing = false) }
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
						pipeline.run(workspaceSource, _state.value.projectSourceName ?: input.fileName.toString(), output, config, progress)
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
        expectedSettings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
		layerVisibility: Map<String, Boolean>,
		deletedLayerIds: Set<String>,
		layerOverrides: Map<String, LayerClassificationOverride>,
		parentOverrides: Map<String, String?>,
		rigEdits: RigEditOverlay,
		status: String,
        settings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
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
				current.rigEdits != expectedRigEdits ||
                (expectedSettings.isNotEmpty() && io.github.psd2live.project.WorkspaceStateCodec.settings(current) != expectedSettings)
			) return@update current
			applied = true
			io.github.psd2live.project.WorkspaceStateCodec.decode(settings, current).copy(
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

	private fun scheduleRuntimeBundleUpdate() {
		val previous = _state.value.previewModel ?: return
		if (_state.value.isAnalyzing || _state.value.isGenerating) return

		previewRebuildJob?.cancel()
		previewRebuildJob = scope.launch {
			delay(30)
			try {
				val config = _state.value.buildConfig()
				val updated = withContext(Dispatchers.Default) {
					pipeline.updateRuntimeBundle(previous, config)
				}
				_state.update {
					it.copy(previewModel = updated)
				}
				sdkSession.load(updated.runtimeBundle, updated.rig.puppet.parameters.map { it.id })
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

	private fun schedulePreviewRebuild() {
		val previous = _state.value.previewModel ?: return
		if (_state.value.isAnalyzing || _state.value.isGenerating) return

		previewRebuildJob?.cancel()
		previewRebuildJob = scope.launch {
			delay(60)
			_state.update { it.copy(statusText = tr("status.applyingLayerChanges")) }
			try {
				val config = _state.value.buildConfig()
				val baseLayers = previous.analysis.layers.filter { it.source !is io.github.psd2live.core.MouthLipLayer }
				val baseAnalysis = previous.analysis.copy(layers = baseLayers)
				val rebuilt = withContext(Dispatchers.Default) {
					pipeline.buildPreview(baseAnalysis, config)
				}
				_state.update { current ->
					val validParamIds = rebuilt.rig.puppet.parameters.mapTo(mutableSetOf()) { it.id }
					current.copy(
						previewModel = rebuilt,
						analysis = rebuilt.analysis,
						lockedParameters = current.lockedParameters.intersect(validParamIds),
						parameterValues = rebuilt.rig.puppet.parameters.associate { param ->
							param.id to (current.parameterValues[param.id] ?: param.default).coerceIn(param.min, param.max)
						},
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

	private var activeSoftwareMotionName: String? = null
	private var activeSoftwareMotionElapsed: Float = 0f
	private var activeSoftwareMotionDuration: Float = 2.0f
	@Volatile private var latestLiveParameters: Map<ParameterId, Float> = emptyMap()

	fun triggerMotion(group: String) {
		sdkSession.startMotion(group, index = 0, priority = 3)
		when (group.lowercase()) {
			"nod" -> {
				activeSoftwareMotionName = "nod"
				activeSoftwareMotionElapsed = 0f
				activeSoftwareMotionDuration = 2.0f
			}
			"shake" -> {
				activeSoftwareMotionName = "shake"
				activeSoftwareMotionElapsed = 0f
				activeSoftwareMotionDuration = 2.0f
			}
			"blink" -> {
				activeSoftwareMotionName = "blink"
				activeSoftwareMotionElapsed = 0f
				activeSoftwareMotionDuration = 1.2f
			}
			"idle" -> {
				elapsed = 0.0
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
				val isMeshOnly = current.meshOnly
				val anim = current.animationEnabled && !isMeshOnly
				val tracking = current.mouseTrackingEnabled && !isMeshOnly
				if (anim) elapsed += dt

				// 1. Advance one-shot software motion (Nod / Shake / Blink)
				var nodAngleY = 0f
				var nodBodyY = 0f
				var nodEyeBlink = 1f

				var shakeAngleX = 0f
				var shakeBodyX = 0f
				var shakeAngleZ = 0f

				val activeMotion = activeSoftwareMotionName
				if (activeMotion != null && anim) {
					activeSoftwareMotionElapsed += dt
					val t = activeSoftwareMotionElapsed
					when (activeMotion) {
						"nod" -> {
							if (t <= 2.0f) {
								nodAngleY = when {
									t < 0.55f -> -18f * (t / 0.55f)
									t < 1.25f -> -18f + 24f * ((t - 0.55f) / 0.70f)
									else -> 6f * (1f - (t - 1.25f) / 0.75f)
								}
								nodBodyY = when {
									t < 0.55f -> -4f * (t / 0.55f)
									t < 1.25f -> -4f + 5.5f * ((t - 0.55f) / 0.70f)
									else -> 1.5f * (1f - (t - 1.25f) / 0.75f)
								}
								nodEyeBlink = when {
									t < 0.55f -> 1f - 0.25f * (t / 0.55f)
									t < 1.25f -> 0.75f + 0.25f * ((t - 0.55f) / 0.70f)
									else -> 1f
								}
							} else {
								activeSoftwareMotionName = null
							}
						}
						"shake" -> {
							if (t <= 2.0f) {
								shakeAngleX = when {
									t < 0.4f -> -20f * (t / 0.4f)
									t < 0.9f -> -20f + 40f * ((t - 0.4f) / 0.5f)
									t < 1.4f -> 20f - 28f * ((t - 0.9f) / 0.5f)
									else -> -8f * (1f - (t - 1.4f) / 0.6f)
								}
								shakeBodyX = when {
									t < 0.4f -> -3f * (t / 0.4f)
									t < 0.9f -> -3f + 6f * ((t - 0.4f) / 0.5f)
									t < 1.4f -> 3f - 4.2f * ((t - 0.9f) / 0.5f)
									else -> -1.2f * (1f - (t - 1.4f) / 0.6f)
								}
								shakeAngleZ = when {
									t < 0.4f -> 2f * (t / 0.4f)
									t < 0.9f -> 2f - 4f * ((t - 0.4f) / 0.5f)
									t < 1.4f -> -2f + 3f * ((t - 0.9f) / 0.5f)
									else -> 1f * (1f - (t - 1.4f) / 0.6f)
								}
							} else {
								activeSoftwareMotionName = null
							}
						}
						"blink" -> {
							if (t > 1.2f) {
								activeSoftwareMotionName = null
							}
						}
					}
				}

				// 2. Eye Blink (Periodic + Triggered)
				val hasBlink = anim && current.motionBlink
				val periodicBlink = if (hasBlink) blinkAt(elapsed % 4.6) else 1f
				val blink = minOf(periodicBlink, nodEyeBlink)

				// 3. Eye Jelly Dynamics
				val hasEyeJelly = anim && current.generatePhysics && current.physicsEyeJelly
				eyeJellyDynamics.advance(blink, dt, hasEyeJelly)

				// 4. Idle Motion (Head & Body Sway, Mouse Tracking)
				val hasIdle = anim && current.motionIdle
				val idleX = if (hasIdle) (sin(elapsed * 0.47) * 0.12).toFloat() else 0f
				val idleY = if (hasIdle) (sin(elapsed * 0.31 + 1.1) * 0.08).toFloat() else 0f
				val targetX = if (pointerActive && tracking) pointerX else idleX
				val targetY = if (pointerActive && tracking) pointerY else idleY
				val response = (dt * 7.5f).coerceAtMost(1f)
				previousFollowX = followX
				followX += (targetX - followX) * response
				followY += (targetY - followY) * response

				if (!pointerActive && kotlin.math.abs(followX - targetX) < 0.001f) followX = targetX
				if (!pointerActive && kotlin.math.abs(followY - targetY) < 0.001f) followY = targetY

				// 5. Hair Physics Simulation
				val hasFrontHair = anim && current.generatePhysics && current.physicsFrontHair
				val hasBackHair = anim && current.generatePhysics && current.physicsBackHair
				if (anim && (hasFrontHair || hasBackHair)) {
					val headVelocity = ((followX - previousFollowX) / dt).coerceIn(-5f, 5f)
					val hairTarget = (-followX * 0.42f - headVelocity * 0.085f).coerceIn(-1f, 1f)
					if (hasFrontHair) {
						frontHairVelocity += ((hairTarget - frontHair) * 22f - frontHairVelocity * 7.2f) * dt
						frontHair += frontHairVelocity * dt
					} else {
						frontHair = 0f
						frontHairVelocity = 0f
					}
					if (hasBackHair) {
						backHairVelocity += ((hairTarget - backHair) * 10f - backHairVelocity * 4.2f) * dt
						backHair += backHairVelocity * dt
					} else {
						backHair = 0f
						backHairVelocity = 0f
					}
				} else {
					frontHair = 0f
					frontHairVelocity = 0f
					backHair = 0f
					backHairVelocity = 0f
				}

				val model = current.previewModel
				if (model != null) {
					val liveParams = if (isMeshOnly) {
						model.rig.puppet.parameters.associate { it.id to it.default }
					} else computeLiveParameters(
						model = model,
						current = current,
						blink = blink,
						nodAngleY = nodAngleY,
						nodBodyY = nodBodyY,
						shakeAngleX = shakeAngleX,
						shakeBodyX = shakeBodyX,
						shakeAngleZ = shakeAngleZ,
					)
					latestLiveParameters = liveParams
					_state.update { latest ->
						val mergedValues = parameterValuesAfterSoftwareFrame(latest, liveParams)
						if (mergedValues === latest.parameterValues) latest else latest.copy(parameterValues = mergedValues)
					}
				}

				delay(33)
			}
		}
	}

	fun computeLiveParameters(
		model: RigPreviewModel,
		current: PSD2LiveState = _state.value,
		blink: Float = blinkAt(elapsed % 4.6),
		nodAngleY: Float = 0f,
		nodBodyY: Float = 0f,
		shakeAngleX: Float = 0f,
		shakeBodyX: Float = 0f,
		shakeAngleZ: Float = 0f,
	): Map<ParameterId, Float> {
		if (current.meshOnly) {
			return model.rig.puppet.parameters.associate { it.id to it.default }
		}

		val hasIdle = current.animationEnabled && current.motionIdle
		val hasFrontHair = current.animationEnabled && current.generatePhysics && current.physicsFrontHair
		val hasBackHair = current.animationEnabled && current.generatePhysics && current.physicsBackHair
		val hasEyeJelly = current.animationEnabled && current.generatePhysics && current.physicsEyeJelly

		val mouthPhase = elapsed % 5.8
		val mouthOpen = if (mouthPhase in 1.25..2.45 && current.animationEnabled && hasIdle) {
			sin((mouthPhase - 1.25) / 1.20 * PI).toFloat().coerceAtLeast(0f)
		} else 0f

		val idleAngleZ = if (hasIdle) (sin(elapsed * PI / 1.5) * 2.0).toFloat() else 0f
		val idleBodyX = if (hasIdle) sin(elapsed * 0.72).toFloat() * 1.2f else 0f
		val idleBodyZ = if (hasIdle) sin(elapsed * 0.92).toFloat() * 2.2f else 0f
		val breath = if (hasIdle) ((sin(elapsed * 1.45) + 1.0) * 0.5).toFloat() else 0f

		val isTracking = pointerActive && current.mouseTrackingEnabled && !current.meshOnly
		val headAngleX = if (hasIdle || isTracking) followX * 38f else 0f
		val headAngleY = if (hasIdle || isTracking) -followY * 24f else 0f
		val bodyAngleX = if (hasIdle || isTracking) followX * 4f else 0f
		val bodyAngleY = if (hasIdle || isTracking) -followY * 2f else 0f
		val eyeBallX = if (hasIdle || isTracking) followX.coerceIn(-1f, 1f) else 0f
		val eyeBallY = if (hasIdle || isTracking) (-followY).coerceIn(-1f, 1f) else 0f

		return mapOf(
			StandardParameters.ANGLE_X to (headAngleX + shakeAngleX),
			StandardParameters.ANGLE_Y to (headAngleY + nodAngleY),
			StandardParameters.ANGLE_Z to (idleAngleZ + shakeAngleZ),
			StandardParameters.BODY_X to (bodyAngleX + idleBodyX + shakeBodyX),
			StandardParameters.BODY_Y to (bodyAngleY + nodBodyY),
			StandardParameters.BODY_Z to idleBodyZ,
			StandardParameters.EYE_BALL_X to eyeBallX,
			StandardParameters.EYE_BALL_Y to eyeBallY,
			StandardParameters.EYE_BALL_FORM to if (hasEyeJelly) eyeJellyDynamics.value else 0f,
			StandardParameters.EYE_L_OPEN to blink,
			StandardParameters.EYE_R_OPEN to blink,
			StandardParameters.MOUTH_FORM to if (current.animationEnabled && hasIdle) sin(elapsed * 0.41).toFloat() * 0.18f else 0f,
			StandardParameters.MOUTH_OPEN to mouthOpen,
			StandardParameters.BREATH to breath,
			StandardParameters.HAIR_FRONT to if (hasFrontHair) frontHair.coerceIn(-1f, 1f) else 0f,
			StandardParameters.HAIR_BACK to if (hasBackHair) backHair.coerceIn(-1f, 1f) else 0f,
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
		val tracking = current.mouseTrackingEnabled && !current.meshOnly
		val liveParams = latestLiveParameters.ifEmpty {
			computeLiveParameters(model, current)
		}
		val previewValues = parameterValuesForPreview(current, liveParams)
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
				animationEnabled = current.animationEnabled && !current.meshOnly,
				parameterOverrides = previewValues,
				frameTimeNanos = frameTimeNanos,
			),
		)
	}

	private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)

	override fun close() {
		if (!isClosed.compareAndSet(false, true)) return
		motionJob?.cancel()
		previewRebuildJob?.cancel()
		activeWorkJob?.cancel()
		scope.cancel()
		sdkSession.close()
	}

	internal fun setStateForTest(state: PSD2LiveState) {
		_state.value = state
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

internal fun parameterValuesForPreview(
	state: PSD2LiveState,
	liveParams: Map<ParameterId, Float> = emptyMap(),
): Map<ParameterId, Float> {
	if (!state.animationEnabled) {
		return state.parameterValues
	}
	if (state.meshOnly) {
		val defaults = state.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
		return defaults + state.parameterValues.filterKeys { it in state.lockedParameters }
	}

	val standardIds = StandardParameters.all.map { it.id }.toSet()
	val overrides = state.parameterValues.filterKeys { it in state.lockedParameters || it !in standardIds }.toMutableMap()

	// 1. Idle animation disabled:
	// Silences Native SDK's hardcoded CubismBreath and Idle motion.
	// Overrides AngleX/Y/Z, BodyAngleX/Y/Z, Breath, and Mouth to controlled values (neutral 0 unless moving mouse/motion).
	if (!state.motionIdle) {
		val idleSuppressedIds = listOf(
			StandardParameters.ANGLE_X,
			StandardParameters.ANGLE_Y,
			StandardParameters.ANGLE_Z,
			StandardParameters.BODY_X,
			StandardParameters.BODY_Y,
			StandardParameters.BODY_Z,
			StandardParameters.BREATH,
			StandardParameters.MOUTH_OPEN,
			StandardParameters.MOUTH_FORM,
		)
		for (id in idleSuppressedIds) {
			if (id !in state.lockedParameters) {
				overrides[id] = liveParams[id] ?: 0f
			}
		}
	}

	// 2. Blink motion disabled:
	// Silences Native SDK eye blinking; keeps eyes fully open (1.0f).
	if (!state.motionBlink) {
		if (StandardParameters.EYE_L_OPEN !in state.lockedParameters) {
			overrides[StandardParameters.EYE_L_OPEN] = liveParams[StandardParameters.EYE_L_OPEN] ?: 1.0f
		}
		if (StandardParameters.EYE_R_OPEN !in state.lockedParameters) {
			overrides[StandardParameters.EYE_R_OPEN] = liveParams[StandardParameters.EYE_R_OPEN] ?: 1.0f
		}
	}

	// 3. Physics disabled or specific chains disabled:
	val physicsActive = state.generatePhysics && !state.meshOnly
	if (!physicsActive || !state.physicsFrontHair) {
		if (StandardParameters.HAIR_FRONT !in state.lockedParameters) {
			overrides[StandardParameters.HAIR_FRONT] = 0f
		}
	}
	if (!physicsActive || !state.physicsBackHair) {
		if (StandardParameters.HAIR_BACK !in state.lockedParameters) {
			overrides[StandardParameters.HAIR_BACK] = 0f
		}
	}
	if (!physicsActive || !state.physicsEyeJelly) {
		if (StandardParameters.EYE_BALL_FORM !in state.lockedParameters) {
			overrides[StandardParameters.EYE_BALL_FORM] = 0f
		}
	}

	return overrides
}

internal fun parameterValuesAfterPreviewFrame(
	state: PSD2LiveState,
	incoming: Map<ParameterId, Float>,
): Map<ParameterId, Float> =
	if (state.animationEnabled && !state.meshOnly) {
		mergeUnlockedParameterValues(state.parameterValues, incoming, state.lockedParameters)
	} else if (state.meshOnly) {
		val defaults = state.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
		mergeUnlockedParameterValues(state.parameterValues, defaults, state.lockedParameters)
	} else {
		state.parameterValues
	}

internal fun parameterValuesAfterSoftwareFrame(
	state: PSD2LiveState,
	incoming: Map<ParameterId, Float>,
): Map<ParameterId, Float> =
	if (state.animationEnabled && state.sdkStatus != "ready" && !state.meshOnly) {
		mergeUnlockedParameterValues(state.parameterValues, incoming, state.lockedParameters)
	} else if (state.meshOnly) {
		val defaults = state.previewModel?.rig?.puppet?.parameters?.associate { it.id to it.default } ?: emptyMap()
		mergeUnlockedParameterValues(state.parameterValues, defaults, state.lockedParameters)
	} else {
		state.parameterValues
	}

internal fun previewFrameMatchesState(
	state: PSD2LiveState,
	frameAnimationEnabled: Boolean,
): Boolean = frameAnimationEnabled == (state.animationEnabled && !state.meshOnly)
