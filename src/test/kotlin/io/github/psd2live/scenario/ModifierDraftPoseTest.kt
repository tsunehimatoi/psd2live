package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class ModifierDraftPoseTest {
    @TempDir lateinit var temp: Path

    @Test fun aModifierDraftSurvivesPreviewSlidersButStillRejectsDocumentAndSessionChanges() = runBlocking {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.mouthPsd(temp).toString(), true, WorkspaceInitialModifiers.RECOMMENDED)
                fun mouth() = RigModifierStack.fromJson(requireNotNull(vm.state.value.rigEdits.modifierStack))
                    .instances.single { it.type == "mouth" }
                val original = mouth()
                val origin = assertNotNull(vm.modifierEditOrigin())
                val captured = assertNotNull(vm.modifierEditState())
                suspend fun pose(value: Float) = workspace.authorPose(buildJsonObject {
                    put("state", assertNotNull(vm.currentWorkspaceState()))
                    putJsonObject("values") { put("ParamMouthOpenY", value) }
                }, MutationAuthor.USER)
                for (value in listOf(.25f, .5f, 1f)) pose(value)
                assertNotEquals(captured, vm.currentWorkspaceState(), "Slider commits advance workspace state")
                assertEquals(origin.historyHead, workspace.history().headNodeId, "Previewing must not change the rig document")
                assertEquals(original, mouth())
                val refreshed = assertNotNull(vm.modifierEditState(origin))
                assertEquals(vm.currentWorkspaceState(), refreshed)
                val updated = JsonObject(original.config + ("values" to JsonObject(
                    original.config["values"]?.jsonObject.orEmpty() + ("early" to JsonPrimitive(45f)))))
                suspend fun commit(expected: String): String? {
                    val result = CompletableDeferred<String?>()
                    vm.editModifier("modifier_update", buildJsonObject {
                        put("id", original.id); put("type", original.type); put("config", updated)
                    }, expected) { result.complete(it) }
                    return withTimeout(20_000) { result.await() }
                }
                assertNull(commit(refreshed))
                assertEquals(updated, mouth().config)
                assertNull(vm.modifierEditState(origin), "A rig edit must still invalidate the old draft")

                val nextOrigin = assertNotNull(vm.modifierEditOrigin())
                val nextState = assertNotNull(vm.modifierEditState(nextOrigin))
                pose(.2f)
                assertTrue(assertNotNull(commit(nextState)).contains("Workspace changed"),
                    "A race after capturing the fresh token must still fail the atomic state check")
                val path = temp.resolve("same-project.psd2live")
                vm.saveProjectNow(path)
                vm.openProjectNow(path, discardUnsaved = true)
                assertEquals(nextOrigin.historyHead, workspace.history().headNodeId)
                assertNull(vm.modifierEditState(nextOrigin), "Reopening the same file starts another editing session")
            }
        }
    }
}
