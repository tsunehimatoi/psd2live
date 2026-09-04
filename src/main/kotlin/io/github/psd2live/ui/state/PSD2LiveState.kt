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

enum class WorkspaceTab {
	HIERARCHY,
	TOPOLOGY,
	PREVIEW,
	LOG,
}

enum class InspectorTab {
	LAYERS,
	PARAMETERS,
}

@Immutable
data class PSD2LiveState(
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
	val analysis: PipelineAnalysis? = null,
	val previewModel: RigPreviewModel? = null,
	val selectedLayerId: String? = null,
	val selectedDeformerId: String? = null,
	val layerVisibility: Map<String, Boolean> = emptyMap(),
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
		val hasAnyPhysics = physicsFrontHair || physicsBackHair || physicsEyeJelly
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
			generatePhysics = !meshOnly && hasAnyPhysics,
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

	val effectiveVisibleLayerIds: Set<String>
		get() {
			val model = previewModel ?: return emptySet()
			return model.analysis.layers
				.asSequence()
				.filter { isLayerVisible(it.source.id.raw, it.source.visible) }
				.mapTo(linkedSetOf()) { it.source.id.raw }
		}
}
