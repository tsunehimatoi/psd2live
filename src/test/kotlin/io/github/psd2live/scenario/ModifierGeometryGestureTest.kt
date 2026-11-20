package io.github.psd2live.scenario

import io.github.psd2live.application.WorkspaceInitialModifiers
import io.github.psd2live.core.*
import io.github.psd2live.core.modifier.RigModifierStack
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.ui.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.test.*

class ModifierGeometryGestureTest {
    @TempDir lateinit var temp: Path

    @Test fun skeletonPoseDragChangesParametersWithoutTakingTheGeometryPoseLock() = runBlocking {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temp.resolve("skeleton-store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.figurePsd(temp).toString(), true, WorkspaceInitialModifiers.EMPTY)
                workspace.applyDocumentEdits(requireNotNull(workspace.stateToken()), "Skeleton", listOf(
                    io.github.psd2live.application.WorkspaceDocumentOperation("skeleton_put", buildJsonObject {
                        put("spec", SkeletonSpec(bones = listOf(SkeletonBone("arm", "Arm", null, BoneRole.FOREARM, Side.LEFT,
                            150f, 150f, 200f, 150f, drawableIds = listOf(vm.state.value.previewModel!!.rig.puppet.drawables.first().id.raw)))).toJson())
                    })
                ), MutationAuthor.USER)
                withContext(Dispatchers.Main) {
                    val editor = vm.canvasEditor
                    editor.beginSkeletonPose()
                    editor.skeletonPoseSubTool = SkeletonPoseSubTool.FK
                    assertTrue(editor.posing())
                    val bone = editor.posedBones().first { it.bone.role == BoneRole.FOREARM }
                    val parameter = bone.bone.parameterId
                    val before = editor.pose[parameter] ?: 0f
                    val viewport = CanvasViewport(2.0, 100.0, 100.0, 1000f, 1000f)
                    val position = androidx.compose.ui.geometry.Offset(
                        viewport.x((bone.headX + bone.tailX) * .5f).toFloat(),
                        (viewport.offsetY + (bone.headY + bone.tailY) * .5f * viewport.scale).toFloat())
                    assertTrue(editor.press(position, viewport, false, false))
                    assertTrue(editor.inGesture)
                    assertNull(editor.capturedEditPose)
                    editor.move(position + androidx.compose.ui.geometry.Offset(0f, -20f), viewport, false)
                    assertNull(editor.error)
                    assertNull(editor.capturedEditPose)
                    assertNotEquals(before, editor.pose[parameter], "Bone posing must remain able to change its parameter")
                    editor.cancel()
                    assertFalse(editor.inGesture)
                    assertNull(editor.capturedEditPose)
                }
            }
        }
    }

    private data class MouthDeformFixture(
        val vm: PSD2LiveViewModel,
        val workspace: DesktopWorkspace,
        val parameter: ParameterId,
        val before: RigPreviewModel,
        val drawableId: String,
        val layer: String,
    )

    private suspend fun withMouthAtIntermediatePose(
        storeName: String,
        block: suspend MouthDeformFixture.() -> Unit,
    ) {
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temp.resolve(storeName)).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.importPsd(Characters.mouthPsd(temp).toString(), true, WorkspaceInitialModifiers.RECOMMENDED)
                val parameter = ParameterId("ParamMouthOpenY")
                workspace.authorPose(buildJsonObject {
                    put("state", workspace.stateToken()); putJsonObject("values") { put(parameter.raw, .35f) }
                }, MutationAuthor.USER)
                val before = requireNotNull(vm.state.value.previewModel)
                val drawable = before.rig.puppet.drawables.firstOrNull {
                    it.geometryGrid?.axes.orEmpty().any { axis -> axis.parameterId == parameter }
                } ?: error("Synthetic mouth has no geometry driven by ${parameter.raw}")
                val layer = before.rig.ownLayerIdByDrawableId.getValue(drawable.id.raw)
                MouthDeformFixture(vm, workspace, parameter, before, drawable.id.raw, layer).block()
            }
        }
    }

    private fun prepareMouthVertexPress(
        fixture: MouthDeformFixture,
        viewport: CanvasViewport,
        snapToKeys: Boolean,
    ): androidx.compose.ui.geometry.Offset {
        fixture.vm.setStateForTest(fixture.vm.state.value.copy(selectedDeformerId = null, selectedLayerId = fixture.layer))
        val editor = fixture.vm.canvasEditor
        editor.hierarchyMode = EditHierarchyMode.DEFORM
        editor.tool = CanvasTool.SELECT
        editor.editLevel = 1
        editor.snapDeformationToKeys = snapToKeys
        val target = assertNotNull(editor.target())
        val badges = editor.deformerCorners(viewport).values
        return editor.screen(target.geometry.points, target, viewport).first { point ->
            badges.none { it.contains(point.x.toDouble(), point.y.toDouble()) }
        }
    }

    @Test fun intermediatePoseSurvivesRealGestureCommitUndoRedoAndParameterChange() = runBlocking {
        withMouthAtIntermediatePose("store") {
            val history = workspace.history()
            val original = RigIrCompiler.compile(before)
            val viewport = CanvasViewport(100.0, 200.0, 200.0, 500f, 500f)
            withContext(Dispatchers.Main) {
                val editor = vm.canvasEditor
                val position = prepareMouthVertexPress(this@withMouthAtIntermediatePose, viewport, snapToKeys = false)
                assertTrue(editor.press(position, viewport, false, false))
                assertEquals(.35f, assertNotNull(editor.capturedEditPose)[parameter])
                vm.setParameterValues(mapOf(parameter to 0f))
                editor.move(position + androidx.compose.ui.geometry.Offset(10f, 0f), viewport, false)
                assertNull(editor.error)
                editor.release()
                assertTrue(editor.busy)
                assertEquals(.35f, assertNotNull(editor.capturedEditPose)[parameter])
                vm.setParameterValues(mapOf(parameter to 0f))
            }
            withTimeout(30_000) { vm.state.first { !it.canvasEditBusy } }
            assertFalse(vm.canvasEditor.busy)
            assertNull(vm.canvasEditor.error)
            assertNull(vm.canvasEditor.capturedEditPose)
            assertEquals(.35f, vm.state.value.parameterValues[parameter])
            val edited = RigIrCompiler.compile(requireNotNull(vm.state.value.previewModel))
            assertNotEquals(original, edited)
            val committed = workspace.history()
            assertEquals(history.nodes.size + 1, committed.nodes.size)
            assertTrue(RigModifierStack.fromJson(vm.state.value.rigEdits.modifierStack!!).overrides.isNotEmpty())
            workspace.checkoutHistory(history.headNodeId, MutationAuthor.USER)
            assertEquals(original, RigIrCompiler.compile(requireNotNull(vm.state.value.previewModel)))
            workspace.checkoutHistory(committed.headNodeId, MutationAuthor.USER)
            assertEquals(edited, RigIrCompiler.compile(requireNotNull(vm.state.value.previewModel)))
            withContext(Dispatchers.Main) { vm.setParameterValues(mapOf(parameter to .2f)) }
            withTimeout(30_000) { vm.state.first { it.parameterValues[parameter] == .2f } }
            assertNull(vm.canvasEditor.capturedEditPose)
        }
    }

    @Test fun defaultSnapDeformationToKeysSnapsOffKeyPressAndShowsNotice() = runBlocking {
        withMouthAtIntermediatePose("snap-store") {
            val puppet = before.rig.puppet
            val axes = puppet.keyformAxesFor("mesh", drawableId) + puppet.parameterKeyMarks().mapNotNull { (paramId, marks) ->
                marks.allKeys.takeIf { it.isNotEmpty() }?.let { KeyformAxis(paramId, it.toFloatArray()) }
            }
            val defaults = puppet.parameters.associate { it.id to it.default }
            val expectedSnapped = assertNotNull(io.github.psd2live.ui.nearestKeyPose(axes, mapOf(parameter to .35f), defaults)[parameter])
            assertNotEquals(.35f, expectedSnapped)

            val viewport = CanvasViewport(100.0, 200.0, 200.0, 500f, 500f)
            withContext(Dispatchers.Main) {
                val editor = vm.canvasEditor
                assertTrue(editor.snapDeformationToKeys)
                val position = prepareMouthVertexPress(this@withMouthAtIntermediatePose, viewport, snapToKeys = editor.snapDeformationToKeys)
                assertTrue(editor.press(position, viewport, false, false))
                assertNotNull(vm.parameterSnapNotice.value)
                assertNull(editor.capturedEditPose)
            }
            withTimeout(30_000) {
                vm.state.first { it.parameterValues[parameter] == expectedSnapped }
                withContext(Dispatchers.Main) {
                    while (vm.isSnappingParameters) delay(10)
                }
            }
            withContext(Dispatchers.Main) {
                val editor = vm.canvasEditor
                assertEquals(expectedSnapped, vm.state.value.parameterValues[parameter])
                val snappedPosition = prepareMouthVertexPress(this@withMouthAtIntermediatePose, viewport, snapToKeys = editor.snapDeformationToKeys)
                assertTrue(editor.press(snappedPosition, viewport, false, false))
                assertEquals(expectedSnapped, assertNotNull(editor.capturedEditPose)[parameter])
                editor.cancel()
                assertNull(editor.capturedEditPose)
            }
        }
    }
}
