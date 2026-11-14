package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceDocumentOperation
import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.core.modifier.ModifierPartConfig
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ModifierParameterNamingTest {
    @TempDir lateinit var temp: Path

    @Test fun fittedAndLegacyMouthParametersUseTranslatedNamesInEveryLanguage() = runBlocking {
        val previous = I18n.currentLanguage
        val source = Characters.mouthPsd(temp)
        try {
            for (language in AppLanguage.entries) {
                I18n.setLanguage(language, persist = false)
                PSD2LiveViewModel().use { vm ->
                    vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
                    DesktopWorkspace(vm, temp.resolve(language.tag)).use { workspace ->
                        vm.attachWorkspace(workspace)
                        workspace.importPsd(source.toString(), true, WorkspaceInitialModifiers.RECOMMENDED)
                        fun assertNames(mode: String) {
                            val parameters = vm.state.value.previewModel!!.rig.puppet.parameters
                            val expected = I18n.text("model.parameter.mouthOpen")
                            assertFalse(expected.startsWith("!"), "${language.tag}: missing canonical translation")
                            assertEquals(expected, parameters.single { it.id.raw == "ParamMouthOpenY" }.name, "${language.tag}: $mode")
                            assertTrue(parameters.none { it.name.startsWith("!model.") }, "${language.tag}: $mode contains unresolved parameter names")
                        }
                        assertNames("fitted")
                        val mouth = RigModifierStack.fromJson(vm.state.value.rigEdits.modifierStack!!).instances.single { it.type == "mouth" }
                        workspace.applyDocumentEdits(workspace.stateToken()!!, "Use legacy mouth", listOf(
                            WorkspaceDocumentOperation("modifier_update", buildJsonObject {
                                put("id", mouth.id); put("type", mouth.type)
                                // Modern v2 subfeatures require a fitted provider; exercise the supported plain legacy base.
                                put("config", JsonObject(mouth.config + ("mode" to JsonPrimitive("legacy")) +
                                    ModifierPartConfig.features.getValue("mouth").keys.associateWith { JsonPrimitive(false) }))
                            }),
                        ), MutationAuthor.USER)
                        assertNames("legacy")
                    }
                }
            }
        } finally { I18n.setLanguage(previous, persist = false) }
    }
}
