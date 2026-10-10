package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlin.test.*

/**
 * Every setting a document stores - and so every setting the GUI can change, since its settings are this codec's text
 * ([io.github.psd2live.ui.state.WorkspaceStateCodec.settings]) - is one MCP `settings_update` accepts, unless another
 * operation edits it. A new GUI setting that is not added here fails this test instead of staying GUI-only.
 */
class SettingsParityTest {
	/**
	 * Per-layer maps, edited by the layer, mesh and draw-order operations; and the hair simulation switches, which
	 * `model_apply_preset` turns (front_hair / back_hair and their classic_ undo) together with the simulation they build.
	 */
	private val editedElsewhere = setOf("meshOverrides", "drawOrderOverrides", "hairSimulationFront", "hairSimulationBack")

	@Test fun everyStoredSettingIsAnOperationField() {
		val stored = WorkspaceSettingsCodec.encode(PipelineConfig(meshTrace = io.github.psd2live.core.MeshTrace.entries.last(), meshWrap = 1f)).keys
		assertEquals(emptySet(), stored - editedElsewhere - workspaceProjectSettingKeys)
	}
}
