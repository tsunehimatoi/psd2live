package io.github.psd2live.ui.state

import androidx.compose.runtime.Immutable
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.PipelineAnalysis
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import org.umamo.runtime.model.ParameterId

import io.github.psd2live.agent.AgentHistorySnapshot

enum class WorkspaceTab {
	HIERARCHY,
	TOPOLOGY,
	PREVIEW,
	HISTORY,
	LOG,
}

enum class LogSource {
	SYSTEM,
	MCP_SERVER,
	AGENT,
}

enum class LogLevel {
	INFO,
	SUCCESS,
	WARNING,
	ERROR,
}

@Immutable
data class AppLogEntry(
	val id: String = java.util.UUID.randomUUID().toString(),
	val timestamp: java.time.Instant = java.time.Instant.now(),
	val source: LogSource = LogSource.SYSTEM,
	val level: LogLevel = LogLevel.INFO,
	val tag: String = "",
	val message: String,
	val detail: String? = null,
	val imageBytes: ByteArray? = null,
	val imageLabel: String? = null,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is AppLogEntry) return false
		return id == other.id
	}

	override fun hashCode(): Int = id.hashCode()
}

enum class InspectorTab {
	LAYERS,
	PARAMETERS,
}

data class HistoryAnnotation(val title: String = "", val note: String = "", val hidden: Boolean = false)

@Immutable
data class PSD2LiveState(
	val projectId: String? = null,
    val projectFile: String? = null,
    val projectDirty: Boolean = false,
    val projectSaving: Boolean = false,
    val projectSaveError: String? = null,
    val showProjectLocationDialog: Boolean = false,
    val projectOpenGeneration: Long = 0,
    val projectEditVersion: Long = 0,
    val projectAuxiliaryVersion: Long = 0,
    val projectSourceName: String? = null,
    val historyZoom: Float = 1f,
    val historyPanX: Float = 0f,
    val historyPanY: Float = 0f,
    val historySearch: String = "",
    val historyShowHidden: Boolean = false,
    val hierarchyWidth: Float = 260f,
    val hierarchyCollapsed: Boolean = false,
    val hierarchySearch: String = "",
    val modelSettingsExpanded: Boolean = true,
    val workspaceSplitRatio: Float = 0.60f,
    val canvasZoom: Float = 1f,
    val canvasPanX: Float = 0f,
    val canvasPanY: Float = 0f,
    val historyAnnotations: Map<String, HistoryAnnotation> = emptyMap(),
    val inputPath: String = "",
	/** Input identity that produced [analysis]; remains stable while the user edits the next path field. */
	val loadedInputPath: String? = null,
	val loadedInputFileSignature: String? = null,
	val outputPath: String = "",
	val atlasSize: Int = 4096,
	val meshSpacing: Int = 64,
	val texturePadding: Int = 2,
	val alphaThreshold: Int = 8,
	val headStrength: Float = 1.0f,
	val bodyStrength: Float = 1.0f,
	val meshOnly: Boolean = false,
	val generateDeformers: Boolean = true,
	val exportMotions: Boolean = true,
	val motionIdle: Boolean = true,
	val motionBlink: Boolean = true,
	val motionNod: Boolean = true,
	val motionShake: Boolean = true,
	val generatePhysics: Boolean = true,
	val physicsFrontHair: Boolean = true,
	val physicsBackHair: Boolean = true,
	val physicsEyeJelly: Boolean = true,
	val exportCmo3: Boolean = true,
	val exportMoc3: Boolean = true,
	val exportJson: Boolean = true,
	val exportOptionsExpanded: Boolean = true,
	val motionSubExpanded: Boolean = false,
	val physicsSubExpanded: Boolean = false,
	val projectOutputsExpanded: Boolean = false,
	val advancedExpanded: Boolean = false,
	val isAnalyzing: Boolean = false,
	val isGenerating: Boolean = false,
	val progress: Float = 0f,
	val isIndeterminateProgress: Boolean = false,
	val statusText: String = "",
	val logLines: List<String> = emptyList(),
	val logEntries: List<AppLogEntry> = emptyList(),
	val logPanelExpanded: Boolean = true,
	val logPanelHeight: Float = 190f,
	val historySnapshot: AgentHistorySnapshot? = null,
	val selectedHistoryNodeId: String? = null,
	val lightboxImage: ByteArray? = null,
	val lightboxTitle: String? = null,
	val analysis: PipelineAnalysis? = null,
	val previewModel: RigPreviewModel? = null,
	val selectedLayerId: String? = null,
	val selectedDeformerId: String? = null,
	val showWarp: Boolean = false,
	val showMesh: Boolean = false,
	val showTexture: Boolean = true,
	val warpShowNames: Boolean = true,
	val warpShowIndices: Boolean = false,
	val filterSelectedOnly: Boolean = false,
	val layerVisibility: Map<String, Boolean> = emptyMap(),
	val deformerVisibility: Map<String, Boolean> = emptyMap(),
	val layerOverrides: Map<String, LayerClassificationOverride> = emptyMap(),
	val isolationSnapshot: Map<String, Boolean>? = null,
	val isolatedLayerId: String? = null,
	val lockedParameters: Set<ParameterId> = emptySet(),
	val parameterValues: Map<ParameterId, Float> = emptyMap(),
	val parameterSearchQuery: String = "",
	val animationEnabled: Boolean = true,
	val mouseTrackingEnabled: Boolean = true,
	val sdkStatus: String? = null,
	val activeWorkspaceTab: WorkspaceTab = WorkspaceTab.PREVIEW,
	val activeInspectorTab: InspectorTab = InspectorTab.LAYERS,
	val currentLanguage: AppLanguage = I18n.currentLanguage,
	val deletedLayerIds: Set<String> = emptySet(),
	val parentOverrides: Map<String, String?> = emptyMap(),
	/** Durable parameter/keyform edits replayed after each generated-rig rebuild. */
	val rigEdits: RigEditOverlay = RigEditOverlay.Empty,
	val errorMessage: String? = null,
	val successExportMessage: String? = null,
) {
	fun buildConfig(): PipelineConfig {
		val hasAnyMotion = motionIdle || motionBlink || motionNod || motionShake
		val hasAnyPhysics = physicsFrontHair || physicsBackHair || physicsEyeJelly || rigEdits.physicsEdits.isNotEmpty()
		return PipelineConfig(
			atlasSize = atlasSize,
			texturePadding = texturePadding,
			meshSpacing = meshSpacing,
			alphaThreshold = alphaThreshold,
			headTurnStrength = headStrength,
			bodyStrength = bodyStrength,
			meshOnly = meshOnly,
			generateDeformers = !meshOnly,
			exportMotions = !meshOnly && hasAnyMotion,
			motionIdle = motionIdle,
			motionBlink = motionBlink,
			motionNod = motionNod,
			motionShake = motionShake,
			generatePhysics = generatePhysics && !meshOnly && hasAnyPhysics,
			physicsFrontHair = physicsFrontHair,
			physicsBackHair = physicsBackHair,
			physicsEyeJelly = physicsEyeJelly,
			exportCmo3 = exportCmo3,
			exportMoc3 = exportMoc3,
			exportJson = exportJson,
			layerOverrides = layerOverrides,
			layerVisibility = layerVisibility,
			deletedLayerIds = deletedLayerIds,
			parentOverrides = parentOverrides,
			rigEdits = rigEdits,
		)
	}

	fun isLayerVisible(layerId: String, defaultVisible: Boolean = true): Boolean {
		layerVisibility[layerId]?.let { return it }
		val parentId = when {
			layerId.endsWith(":l") || layerId.endsWith(":r") -> layerId.dropLast(2)
			else -> null
		}
		return parentId?.let(layerVisibility::get) ?: defaultVisible
	}

	fun isDeformerVisible(deformerId: String, defaultVisible: Boolean = true): Boolean {
		return deformerVisibility[deformerId] ?: defaultVisible
	}

	val effectiveVisibleLayerIds: Set<String>
		get() {
			val model = previewModel ?: return emptySet()
			val hiddenDeformers = deformerVisibility.filterValues { !it }.keys
			val layerHiddenByDeformer: (String) -> Boolean = if (hiddenDeformers.isEmpty()) {
				{ false }
			} else {
				val drawableIdByLayerId = model.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
				val drawableById = model.rig.puppet.drawables.associateBy { it.id.raw }
				val deformerById = model.rig.puppet.deformers.associateBy { it.id.raw }
				fun isHidden(layerId: String): Boolean {
					val drawId = drawableIdByLayerId[layerId]
					var parent: String? = drawId?.let { parentOverrides[it] ?: drawableById[it]?.parentDeformerId?.raw }
					val visited = mutableSetOf<String>()
					while (parent != null && visited.add(parent)) {
						if (parent in hiddenDeformers) {
							return true
						}
						parent = parentOverrides[parent] ?: deformerById[parent]?.parent?.raw
					}
					return false
				}
				::isHidden
			}

			return model.analysis.layers
				.asSequence()
				.filter { isLayerVisible(it.source.id.raw, it.source.visible) && !layerHiddenByDeformer(it.source.id.raw) }
				.mapTo(linkedSetOf()) { it.source.id.raw }
		}
}
