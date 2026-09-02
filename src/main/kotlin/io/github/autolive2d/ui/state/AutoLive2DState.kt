package io.github.autolive2d.ui.state

import androidx.compose.runtime.Immutable
import io.github.autolive2d.core.LayerClassificationOverride
import io.github.autolive2d.core.PipelineAnalysis
import io.github.autolive2d.core.PipelineConfig
import io.github.autolive2d.core.RigPreviewModel
import io.github.autolive2d.i18n.AppLanguage
import io.github.autolive2d.i18n.I18n
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
data class AutoLive2DState(
	val inputPath: String = "",
	val outputPath: String = "",
	val atlasSize: Int = 4096,
	val meshSpacing: Int = 64,
	val texturePadding: Int = 2,
	val alphaThreshold: Int = 8,
	val headStrength: Float = 1.0f,
	val bodyStrength: Float = 1.0f,
	val generatePhysics: Boolean = true,
	val exportCmo3: Boolean = true,
	val exportMoc3: Boolean = true,
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
	val errorMessage: String? = null,
	val successExportMessage: String? = null,
) {
	fun buildConfig(): PipelineConfig = PipelineConfig(
		atlasSize = atlasSize,
		texturePadding = texturePadding,
		meshSpacing = meshSpacing,
		alphaThreshold = alphaThreshold,
		headTurnStrength = headStrength,
		bodyStrength = bodyStrength,
		generatePhysics = generatePhysics,
		exportCmo3 = exportCmo3,
		exportMoc3 = exportMoc3,
		layerOverrides = layerOverrides,
		layerVisibility = layerVisibility,
	)

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
