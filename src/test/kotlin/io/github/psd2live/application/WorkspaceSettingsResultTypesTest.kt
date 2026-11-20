package io.github.psd2live.application

import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.WorkspaceSettingsCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceSettingsResultTypesTest {
    @Test
    fun settingsInspectionAcceptsBothSavedMeshGenerationVersions() {
        for (fullCoverage in listOf(false, true)) {
            val settings = WorkspaceSettingsCodec.encode(PipelineConfig(meshAlphaCoverage = fullCoverage))
            // The inspection schema does not list drawOrderStep, which the codec writes; that is outside this setting.
            validateOperationSchema(kotlinx.serialization.json.JsonObject(settings - "drawOrderStep"),
                WorkspaceSettingsResultTypes.settings, "settings")
            assertEquals(fullCoverage, WorkspaceSettingsCodec.decode(settings).meshAlphaCoverage, "the saved setting reads back")
        }
    }
}
