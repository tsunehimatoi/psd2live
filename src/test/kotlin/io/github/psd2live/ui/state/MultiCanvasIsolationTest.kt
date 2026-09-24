package io.github.psd2live.ui.state

import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.core.CubismSdkFrame
import io.github.psd2live.core.StandardParameters
import java.awt.image.BufferedImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class MultiCanvasIsolationTest {
    @Test fun newWorkspaceDefaultCanvasHasTheSameEditSessionAsAnAddedCanvas() {
        PSD2LiveViewModel().use { vm ->
            vm.addWorkspace()
            val builtIn = vm.state.value.activeCanvas
            val addedId = vm.addCanvas(CanvasMode.EDIT)
            val added = vm.state.value.activeWorkspace.canvases.first { it.id == addedId }

            assertEquals(builtIn.editSession, added.editSession)
            assertEquals(builtIn.previewSession, added.previewSession)
            assertNotSame(vm.canvasEditorFor(builtIn.id), vm.canvasEditorFor(addedId))
        }
    }

    @Test fun toolsAndGesturesBelongToTheirCanvas() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            val first = vm.canvasEditorFor(firstId)
            first.tool = CanvasTool.BRUSH
            first.space = true
            first.objects = setOf("layer-a")
            val secondId = vm.addCanvas(CanvasMode.EDIT)
            val second = vm.canvasEditorFor(secondId)
            assertNotSame(first, second)
            assertSame(second, vm.canvasEditor)
            assertEquals(CanvasTool.SELECT, second.tool)
            assertFalse(second.space)
            assertTrue(second.objects.isEmpty())
            second.cancel()
            assertTrue(first.space)
            assertEquals(setOf("layer-a"), first.objects)
            vm.focusCanvas(firstId)
            assertSame(first, vm.canvasEditor)
            assertEquals(CanvasTool.BRUSH, vm.canvasEditor.tool)
        }
    }

    @Test fun cameraUpdatesDoNotStealFocusOrModifyOtherCameras() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            val secondId = vm.addCanvas(CanvasMode.EDIT)
            vm.setCanvasView(2f, 30f, -20f, firstId)
            assertEquals(secondId, vm.state.value.activeCanvas.id)
            assertEquals(TabCamera(), vm.state.value.activeCanvas.camera)
            assertEquals(TabCamera(2f, 30f, -20f), vm.state.value.activeWorkspace.canvases.first { it.id == firstId }.camera)
            vm.ensureEditCanvas()
            assertEquals(secondId, vm.state.value.activeCanvas.id)
        }
    }

    @Test fun workspaceAndProjectIdentityIsolateReusedCanvasIds() {
        PSD2LiveViewModel().use { vm ->
            val original = vm.state.value
            val first = vm.canvasEditor
            val firstKey = vm.canvasRenderKey(original.activeCanvas.id)
            val secondWorkspace = EditorWorkspace(id = "second")
            vm.setStateForTest(original.copy(workspaces = original.workspaces + secondWorkspace, activeWorkspaceId = "second"))
            assertNotSame(first, vm.canvasEditor)
            assertNotEquals(firstKey, vm.canvasRenderKey(original.activeCanvas.id))
            vm.setStateForTest(original)
            assertSame(first, vm.canvasEditor)
            vm.setStateForTest(original.copy(projectOpenGeneration = original.projectOpenGeneration + 1))
            assertNotSame(first, vm.canvasEditor)
            assertNotEquals(firstKey, vm.canvasRenderKey(original.activeCanvas.id))
        }
    }

    @Test fun frameSubscriptionsArePerViewAndRemovedOnDisposal() {
        PSD2LiveViewModel().use { vm ->
            val first = vm.sdkFrameFor("a")
            assertSame(first, vm.sdkFrameFor("a"))
            assertNotSame(first, vm.sdkFrameFor("b"))
            vm.releaseCanvasFrame("a")
            assertNotSame(first, vm.sdkFrameFor("a"))

            val oldMount = vm.retainCanvasFrame("remount")
            val newMount = vm.retainCanvasFrame("remount")
            assertSame(oldMount, newMount)
            vm.releaseRetainedCanvasFrame("remount")
            assertSame(newMount, vm.sdkFrameFor("remount"))
            vm.releaseRetainedCanvasFrame("remount")
            assertNotSame(newMount, vm.sdkFrameFor("remount"))
        }
    }
    @Test fun displaySelectionAndHoverDoNotFollowAnotherCanvasFocus() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            vm.selectDeformer("warp-a")
            vm.setHoveredItem(null, "warp-hover")
            vm.setCanvasViewOptions(firstId, TabViewOptions(showWarp = false, showRotation = false, dimUnselected = false))
            val first = vm.canvasEditorFor(firstId)
            val secondId = vm.addCanvas(CanvasMode.EDIT)
            vm.selectLayer("layer-b")
            vm.setHoveredItem("layer-hover", null)
            vm.setCanvasViewOptions(secondId, TabViewOptions(showWarp = true, showRotation = true, dimUnselected = true, filterSelectedOnly = true))
            assertFalse(first.state.showWarp)
            assertFalse(first.state.showRotation)
            assertFalse(first.state.dimUnselected)
            assertFalse(first.state.filterSelectedOnly)
            assertEquals("warp-a", first.state.selectedDeformerId)
            assertEquals("warp-hover", first.state.hoveredDeformerId)
            assertNull(first.state.selectedLayerId)
            vm.focusCanvas(firstId)
            assertEquals("warp-a", vm.state.value.selectedDeformerId)
            assertEquals("layer-b", vm.canvasEditorFor(secondId).state.selectedLayerId)
            assertTrue(vm.canvasEditorFor(secondId).state.dimUnselected)
        }
    }

    @Test fun backgroundHoverAndPresetAddressTheirOwner() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            val workspace = vm.state.value.activeWorkspace.id
            val secondId = vm.addCanvas(CanvasMode.EDIT)
            vm.selectLayer("second")
            vm.updateCanvasPresentation(workspace, firstId) { it.copy(hoveredLayerId = "first") }
            vm.applyHierarchyModeViewPreset(io.github.psd2live.ui.EditHierarchyMode.PAINT, firstId, workspace)
            assertEquals(secondId, vm.state.value.activeCanvas.id)
            assertNull(vm.state.value.hoveredLayerId)
            assertEquals("first", vm.canvasEditorFor(firstId).state.hoveredLayerId)
            assertFalse(vm.canvasEditorFor(firstId).state.showWarp)
            assertTrue(vm.state.value.showWarp)
        }
    }

    @Test fun visibilityAndSoloStayLocalAndNeverEnterModelConfiguration() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            vm.setLayerVisibility("layer", false)
            vm.setDeformerVisibility("warp", false)
            assertTrue(vm.state.value.buildConfig().layerVisibility.isEmpty())
            val secondId = vm.addCanvas(CanvasMode.EDIT)
            assertTrue(vm.state.value.isLayerVisible("layer"))
            assertTrue(vm.state.value.isDeformerVisible("warp"))
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, secondId) {
                it.copy(isolatedLayerId = "solo", isolationSnapshot = mapOf("other" to true))
            }
            vm.focusCanvas(firstId)
            assertFalse(vm.state.value.isLayerVisible("layer"))
            assertFalse(vm.state.value.isDeformerVisible("warp"))
            assertNull(vm.state.value.isolatedLayerId)
            assertEquals("solo", vm.canvasEditorFor(secondId).state.isolatedLayerId)
        }
    }

    @Test fun parameterPoseAndPlaybackRemainOnTheirCanvas() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            val parameter = org.umamo.runtime.model.ParameterId("pose")
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, firstId) {
                it.copy(parameterValues = mapOf(parameter to 0.5f), lockedParameters = setOf(parameter), animationEnabled = true, mouseTrackingEnabled = false)
            }
            val secondId = vm.addCanvas(CanvasMode.PREVIEW)
            assertTrue(vm.state.value.parameterValues.isEmpty())
            assertFalse(vm.state.value.animationEnabled)
            assertTrue(vm.state.value.mouseTrackingEnabled)
            vm.focusCanvas(firstId)
            assertEquals(0.5f, vm.state.value.parameterValues[parameter])
            assertTrue(vm.state.value.animationEnabled)
            assertFalse(vm.canvasEditorFor(secondId).state.animationEnabled)
        }
    }

    @Test fun presentationSurvivesProjectRoundTripWithoutSharingSelection() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            vm.selectLayer("first")
            vm.setLayerVisibility("hidden", false)
            val secondId = vm.addCanvas(CanvasMode.EDIT)
            vm.selectDeformer("second")
            vm.setCanvasViewOptions(secondId, TabViewOptions(dimUnselected = false, showWarp = false))
            val restored = io.github.psd2live.project.WorkspaceStateCodec.decode(io.github.psd2live.project.WorkspaceStateCodec.encode(vm.state.value))
            assertEquals("second", restored.selectedDeformerId)
            assertEquals("first", restored.forCanvas(firstId).selectedLayerId)
            assertFalse(restored.forCanvas(firstId).isLayerVisible("hidden"))
            assertTrue(restored.isLayerVisible("hidden"))
            assertFalse(restored.forCanvas(secondId).showWarp)
        }
    }

    @Test fun previewFramesStayInTheirCanvasAndOnlySampleActivePanelState() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            vm.setCanvasMode(firstId, CanvasMode.PREVIEW)
            val workspaceId = vm.state.value.activeWorkspace.id
            vm.updateCanvasPresentation(workspaceId, firstId) { it.copy(animationEnabled = true) }
            val secondId = vm.addCanvas(CanvasMode.PREVIEW, focus = false)
            vm.updateCanvasPresentation(workspaceId, secondId) { it.copy(animationEnabled = true) }
            val firstKey = vm.canvasRenderKey(firstId)
            val secondKey = vm.canvasRenderKey(secondId)
            val firstFlow = vm.sdkFrameFor(firstKey)
            val secondFlow = vm.sdkFrameFor(secondKey)
            val parameter = org.umamo.runtime.model.ParameterId("pose")
            fun frame(key: String, value: Float) = CubismSdkFrame(
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                mapOf(parameter to value),
                viewId = key,
            )

            val first = frame(firstKey, 0.1f)
            vm.acceptSdkFrame(first, 1_000_000_000L)
            assertSame(first, firstFlow.value)
            assertEquals(0.1f, vm.state.value.previewParameterValues[parameter])
            val published = vm.state.value

            vm.acceptSdkFrame(frame(firstKey, 0.2f), 1_016_000_000L)
            assertSame(published, vm.state.value)
            assertEquals(0.2f, firstFlow.value?.parameters?.get(parameter))

            val second = frame(secondKey, 0.8f)
            vm.acceptSdkFrame(second, 1_040_000_000L)
            assertSame(second, secondFlow.value)
            assertSame(published, vm.state.value)
            assertSame(firstFlow.value, vm.sdkFrame.value)

            vm.focusCanvas(secondId)
            vm.acceptSdkFrame(frame(secondKey, 0.9f), 1_050_000_000L)
            assertEquals(0.9f, vm.state.value.previewParameterValues[parameter])
            assertEquals(0.1f, vm.state.value.forCanvas(firstId).previewParameterValues[parameter])
        }
    }

    @Test fun canvasPickKeepsItsOwnerWithoutChangingFocus() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            val first = vm.canvasEditorFor(firstId)
            val secondId = vm.addCanvas(CanvasMode.EDIT)
            first.selectLayer("first-layer")
            assertEquals(secondId, vm.state.value.activeCanvas.id)
            assertNull(vm.state.value.selectedLayerId)
            assertEquals("first-layer", first.state.selectedLayerId)
        }
    }

    @Test fun playbackControlsTargetThePreviewCanvas() {
        PSD2LiveViewModel().use { vm ->
            val editId = vm.state.value.activeCanvas.id
            val previewId = vm.addCanvas(CanvasMode.PREVIEW, focus = false)
            vm.setAnimationEnabled(true)
            assertEquals(previewId, vm.state.value.activeCanvas.id)
            assertTrue(vm.state.value.animationEnabled)
            assertFalse(vm.state.value.forCanvas(editId).animationEnabled)

            vm.focusCanvas(editId)
            vm.setAnimationEnabled(false)
            assertEquals(editId, vm.state.value.activeCanvas.id)
            assertFalse(vm.state.value.previewPanelState().animationEnabled)
            vm.triggerMotion("Blink")
            assertEquals(previewId, vm.state.value.activeCanvas.id)
            assertTrue(vm.state.value.animationEnabled)
        }
    }

    @Test fun previewOverridesUseTheTargetCanvasParameters() {
        PSD2LiveViewModel().use { vm ->
            val firstId = vm.state.value.activeCanvas.id
            vm.setCanvasMode(firstId, CanvasMode.PREVIEW)
            val secondId = vm.addCanvas(CanvasMode.PREVIEW, focus = false)
            val parameter = StandardParameters.ANGLE_X
            val workspaceId = vm.state.value.activeWorkspace.id
            vm.updateCanvasPresentation(workspaceId, secondId) {
                it.copy(animationEnabled = true, parameterValues = mapOf(parameter to 12f), lockedParameters = setOf(parameter))
            }
            val snapshot = vm.state.value
            val target = snapshot.activeWorkspace.canvases.first { it.id == secondId }.presentation
            val live = mapOf(parameter to -8f)
            assertEquals(
                parameterValuesForPreview(snapshot.forCanvas(secondId), live),
                parameterValuesForPreview(snapshot, target.animationEnabled, target.parameterValues, target.lockedParameters, live),
            )
            assertEquals(firstId, snapshot.activeCanvas.id)
        }
    }

    @Test fun editAndPreviewSessionsOfOneCanvasNeverExchangeViewOrInteractionState() {
        PSD2LiveViewModel().use { vm ->
            val canvasId = vm.state.value.activeCanvas.id
            val workspaceId = vm.state.value.activeWorkspace.id
            val editor = vm.canvasEditorFor(canvasId)
            val parameter = StandardParameters.ANGLE_X
            editor.tool = CanvasTool.BRUSH
            vm.selectLayer("edit-layer")
            vm.setCanvasViewOptions(canvasId, TabViewOptions(showWarp = true, showRotation = true, showMesh = true))
            vm.setCanvasView(2f, 40f, -15f, canvasId, CanvasMode.EDIT)
            vm.updateCanvasPresentation(workspaceId, canvasId, CanvasMode.EDIT) {
                it.copy(parameterValues = mapOf(parameter to 0.4f), layerVisibility = mapOf("hidden-edit" to false))
            }

            vm.setCanvasMode(canvasId, CanvasMode.PREVIEW)
            assertFalse(vm.state.value.showWarp)
            assertFalse(vm.state.value.showRotation)
            assertFalse(vm.state.value.showMesh)
            assertEquals(TabCamera(), vm.state.value.activeCanvas.camera)
            assertNull(vm.state.value.selectedLayerId)
            assertTrue(vm.state.value.parameterValues.isEmpty())
            assertTrue(vm.state.value.isLayerVisible("hidden-edit"))
            assertEquals("edit-layer", editor.state.selectedLayerId)
            assertEquals(CanvasTool.BRUSH, editor.tool)

            vm.setCanvasViewOptions(canvasId, TabViewOptions(showWarp = true, showRotation = false, dimUnselected = false), CanvasMode.PREVIEW)
            vm.setCanvasView(0.5f, -20f, 30f, canvasId, CanvasMode.PREVIEW)
            vm.updateCanvasPresentation(workspaceId, canvasId, CanvasMode.PREVIEW) {
                it.copy(selectedLayerId = "preview-layer", parameterValues = mapOf(parameter to -0.6f),
                    layerVisibility = mapOf("hidden-preview" to false), animationEnabled = true)
            }
            editor.selectLayer("edit-again")
            assertEquals("preview-layer", vm.state.value.selectedLayerId)

            vm.setCanvasMode(canvasId, CanvasMode.EDIT)
            assertEquals("edit-again", vm.state.value.selectedLayerId)
            assertEquals(2f, vm.state.value.canvasZoom)
            assertTrue(vm.state.value.showRotation)
            assertTrue(vm.state.value.showMesh)
            assertEquals(0.4f, vm.state.value.parameterValues[parameter])
            assertFalse(vm.state.value.isLayerVisible("hidden-edit"))
            assertTrue(vm.state.value.isLayerVisible("hidden-preview"))
            assertFalse(vm.state.value.animationEnabled)

            vm.setCanvasMode(canvasId, CanvasMode.PREVIEW)
            assertEquals("preview-layer", vm.state.value.selectedLayerId)
            assertEquals(0.5f, vm.state.value.canvasZoom)
            assertTrue(vm.state.value.showWarp)
            assertFalse(vm.state.value.showRotation)
            assertFalse(vm.state.value.dimUnselected)
            assertEquals(-0.6f, vm.state.value.parameterValues[parameter])
            assertFalse(vm.state.value.isLayerVisible("hidden-preview"))
            assertTrue(vm.state.value.animationEnabled)
            assertNotEquals(vm.canvasRenderKey(canvasId, CanvasMode.EDIT), vm.canvasRenderKey(canvasId, CanvasMode.PREVIEW))
        }
    }

    @Test fun bothModeSessionsSurviveProjectRoundTrip() {
        PSD2LiveViewModel().use { vm ->
            val id = vm.state.value.activeCanvas.id
            vm.setCanvasViewOptions(id, TabViewOptions(showWarp = true, showMesh = true), CanvasMode.EDIT)
            vm.selectDeformer("edit-warp")
            vm.setCanvasMode(id, CanvasMode.PREVIEW)
            vm.setCanvasViewOptions(id, TabViewOptions(showWarp = false, showRotation = true), CanvasMode.PREVIEW)
            vm.selectLayer("preview-layer")
            val restored = io.github.psd2live.project.WorkspaceStateCodec.decode(
                io.github.psd2live.project.WorkspaceStateCodec.encode(vm.state.value)
            )
            assertEquals("preview-layer", restored.selectedLayerId)
            assertFalse(restored.showWarp)
            assertTrue(restored.showRotation)
            val edit = restored.forCanvas(id, mode = CanvasMode.EDIT)
            assertEquals("edit-warp", edit.selectedDeformerId)
            assertTrue(edit.showWarp)
            assertTrue(edit.showMesh)
            assertFalse(edit.showRotation)
        }
    }

    @Test fun oldSingleSessionCanvasLoadsIntoOnlyItsSavedMode() {
        PSD2LiveViewModel().use { vm ->
            val id = vm.state.value.activeCanvas.id
            vm.setCanvasViewOptions(id, TabViewOptions(showWarp = false, showMesh = true))
            vm.setCanvasView(1.8f, 11f, -9f, id)
            vm.selectLayer("legacy-edit")
            val encoded = io.github.psd2live.project.WorkspaceStateCodec.encode(vm.state.value)
            val workspace = encoded.getValue("workspaces").jsonArray.first().jsonObject
            val canvas = workspace.getValue("canvases").jsonArray.first().jsonObject
            val edit = canvas.getValue("editSession").jsonObject
            val oldCanvas = JsonObject(canvas.filterKeys { it != "editSession" && it != "previewSession" } + mapOf(
                "view" to edit.getValue("view"),
                "camera" to edit.getValue("camera"),
                "presentation" to edit.getValue("presentation"),
            ))
            val oldWorkspace = JsonObject(workspace + ("canvases" to JsonArray(listOf(oldCanvas))))
            val oldDocument = JsonObject(encoded + ("workspaces" to JsonArray(listOf(oldWorkspace))))
            val restored = io.github.psd2live.project.WorkspaceStateCodec.decode(oldDocument)
            assertEquals("legacy-edit", restored.selectedLayerId)
            assertTrue(restored.showMesh)
            assertEquals(1.8f, restored.canvasZoom)
            val preview = restored.forCanvas(id, mode = CanvasMode.PREVIEW)
            assertNull(preview.selectedLayerId)
            assertFalse(preview.showMesh)
            assertEquals(TabCamera(), preview.activeCanvas.camera)
        }
    }

    @Test fun frameFromPreviousModeCannotUpdateCurrentSession() {
        PSD2LiveViewModel().use { vm ->
            val id = vm.state.value.activeCanvas.id
            vm.setCanvasMode(id, CanvasMode.PREVIEW)
            val previewKey = vm.canvasRenderKey(id)
            val flow = vm.sdkFrameFor(previewKey)
            vm.setCanvasMode(id, CanvasMode.EDIT)
            val before = vm.state.value
            vm.acceptSdkFrame(CubismSdkFrame(
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), emptyMap(), viewId = previewKey,
            ))
            assertSame(before, vm.state.value)
            assertNull(flow.value)
            assertNotEquals(previewKey, vm.canvasRenderKey(id))
        }
    }

    @Test fun playbackPanelsAndTrackingUsePreviewSessionWhileEditingHasFocus() {
        PSD2LiveViewModel().use { vm ->
            val id = vm.state.value.activeCanvas.id
            val parameter = StandardParameters.ANGLE_X
            vm.setParameterValue(parameter, 0.25f)
            vm.setCanvasMode(id, CanvasMode.PREVIEW)
            vm.setParameterValue(parameter, -0.5f)
            vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, id, CanvasMode.PREVIEW) {
                it.copy(animationEnabled = true)
            }
            vm.setCanvasMode(id, CanvasMode.EDIT)
            assertFalse(vm.state.value.animationEnabled)
            assertTrue(vm.state.value.previewPanelState().animationEnabled)
            assertEquals(-0.5f, vm.state.value.previewPanelState().parameterValues[parameter])

            vm.setMouseTrackingEnabled(false)
            assertEquals(CanvasMode.EDIT, vm.state.value.activeCanvas.mode)
            assertFalse(vm.state.value.previewPanelState().mouseTrackingEnabled)
            assertTrue(vm.state.value.forCanvas(id, mode = CanvasMode.EDIT).mouseTrackingEnabled)

            vm.resetPreviewParameters()
            assertEquals(0.25f, vm.state.value.forCanvas(id, mode = CanvasMode.EDIT).parameterValues[parameter])
        }
    }

    @Test fun playingFromEditSwitchesTheSameCanvasToItsPreviewSession() {
        PSD2LiveViewModel().use { vm ->
            val id = vm.state.value.activeCanvas.id
            vm.selectLayer("edit-only")
            vm.setAnimationEnabled(true)
            assertEquals(1, vm.state.value.activeWorkspace.canvases.size)
            assertEquals(id, vm.state.value.activeCanvas.id)
            assertEquals(CanvasMode.PREVIEW, vm.state.value.activeCanvas.mode)
            assertTrue(vm.state.value.animationEnabled)
            assertNull(vm.state.value.selectedLayerId)
            assertEquals("edit-only", vm.state.value.forCanvas(id, mode = CanvasMode.EDIT).selectedLayerId)
        }
    }

    @Test fun panelsKeepStructuralEditsOnTheFocusedCanvas() {
        PSD2LiveViewModel().use { vm ->
            val previewId = vm.state.value.activeCanvas.id
            vm.setCanvasMode(previewId, CanvasMode.PREVIEW)
            val editId = vm.addCanvas(CanvasMode.EDIT, focus = false)
            vm.focusCanvas(previewId)
            vm.selectLayer("picked")
            vm.requestCanvasPathTool()
            assertEquals(previewId, vm.state.value.activeCanvas.id)
            assertEquals(CanvasMode.EDIT, vm.state.value.activeCanvas.mode)
            assertEquals("picked", vm.state.value.selectedLayerId)
            assertEquals(CanvasTool.CREATE_DEFORM_PATH, vm.canvasEditorFor(previewId).deferredMode?.tool)
            assertNull(vm.canvasEditorFor(editId).deferredMode)
            assertSame(vm.state.value, vm.uiState.value)
        }
    }

    @Test fun missingWarpIdsDoNotFailTheGuide() {
        val model = org.umamo.runtime.model.PuppetModel(
            parameters = emptyList(),
            parts = emptyList(),
            deformers = emptyList(),
            drawables = emptyList(),
            rootChildren = emptyList(),
            rootPartId = null,
        )
        assertTrue(io.github.psd2live.ui.RigInformationOverlay.warpPoints(model, emptyMap(), setOf("gone")).isEmpty())
    }

}
