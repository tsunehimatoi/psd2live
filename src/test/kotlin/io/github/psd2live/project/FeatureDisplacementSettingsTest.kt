package io.github.psd2live.project

import io.github.psd2live.ui.state.PSD2LiveState
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

class FeatureDisplacementSettingsTest {
    @Test fun `switch persists in projects and settings and defaults on for old projects`() {
        assertTrue(WorkspaceStateCodec.decode(buildJsonObject {}).featureDisplacementEnabled)
        for (enabled in listOf(true, false)) {
            val state = PSD2LiveState(featureDisplacementEnabled = enabled)
            for (json in listOf(WorkspaceStateCodec.encode(state), WorkspaceStateCodec.settings(state))) {
                val restored = WorkspaceStateCodec.decode(json)
                assertEquals(enabled, restored.featureDisplacementEnabled)
                assertEquals(enabled, restored.buildConfig().featureDisplacementEnabled)
            }
        }
    }
}
