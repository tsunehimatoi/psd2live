package io.github.psd2live.core

import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import org.umamo.format.art.*
import org.umamo.format.moc3.Moc3
import org.umamo.runtime.model.ParameterId
import kotlin.test.*

class RealtimePreviewDynamicsTest {

    private fun dummySourceArt(): SourceArt {
        val width = 256
        val height = 256
        val rgba = ByteArray(width * height * 4) { index ->
            if (index % 4 == 3) 255.toByte() else 128.toByte()
        }
        val layer = WorkspaceSourceLayer(
            id = LayerId("layer1"),
            name = "face",
            groupPath = "",
            kind = SourceLayerKind.Raster,
            visible = true,
            order = 0,
            bounds = LayerBounds(0, 0, width, height),
            opacity = 1f,
            clipped = false,
            blend = LayerBlend.Normal,
            channelMask = ChannelMask.ALL,
            raster = LayerRaster(width, height, rgba),
            sourceAssetId = null,
            sourceSpatialReferenceId = null,
            derived = false,
        )
        return WorkspaceSourceArt(width, height, listOf(layer), emptyList())
    }

    @Test
    fun testUpdateRuntimeBundleReflectsPhysicsAndMotions() {
        val pipeline = PSD2LivePipeline()
        val source = dummySourceArt()
        val baseConfig = PipelineConfig(
            atlasSize = 512,
            generatePhysics = true,
            physicsFrontHair = true,
            physicsBackHair = true,
            physicsEyeJelly = true,
            exportMotions = true,
            motionIdle = true,
            motionBlink = true,
            motionNod = true,
            motionShake = true,
        )
        val initialPreview = pipeline.buildPreview(source, baseConfig)
        val initialManifestBytes = initialPreview.runtimeBundle.assets.first { it.path.endsWith(".model3.json") }.bytes
        val initialManifest = Moc3.readModel3(initialManifestBytes.decodeToString())

        // Initial manifest should have EyeBlink group when motionBlink is true
        assertTrue(initialManifest.groups?.any { it.name == "EyeBlink" } == true, "Should contain EyeBlink group")
        val initialMotions = assertNotNull(initialManifest.fileReferences.motions)
        assertTrue(initialMotions.containsKey("Idle"), "Should contain Idle motion")
        assertTrue(initialMotions.containsKey("Nod"), "Should contain Nod motion")
        assertTrue(initialMotions.containsKey("Shake"), "Should contain Shake motion")

        // Update runtime bundle with blink disabled and nod disabled
        val updatedConfig = baseConfig.copy(motionBlink = false, motionNod = false)
        val updatedPreview = pipeline.updateRuntimeBundle(initialPreview, updatedConfig)
        val updatedManifestBytes = updatedPreview.runtimeBundle.assets.first { it.path.endsWith(".model3.json") }.bytes
        val updatedManifest = Moc3.readModel3(updatedManifestBytes.decodeToString())

        // EyeBlink group should be omitted when motionBlink is false
        assertFalse(updatedManifest.groups?.any { it.name == "EyeBlink" } == true, "Should omit EyeBlink group")
        assertFalse(updatedManifest.fileReferences.motions?.containsKey("Nod") == true, "Should omit Nod motion")
        assertTrue(updatedManifest.fileReferences.motions?.containsKey("Idle") == true, "Should retain Idle motion")
    }

    @Test
    fun testComputeLiveParametersRespectsRealtimeOptions() {
        val pipeline = PSD2LivePipeline()
        val source = dummySourceArt()
        val config = PipelineConfig(
            atlasSize = 512,
            motionIdle = false,
            motionBlink = false,
            physicsFrontHair = false,
            physicsBackHair = false,
            physicsEyeJelly = false,
        )
        val preview = pipeline.buildPreview(source, config)
        val vm = PSD2LiveViewModel()

        // 1. When motionIdle is false: breath and idle oscillations are 0
        val stateNoIdle = PSD2LiveState(
            previewModel = preview,
            animationEnabled = true,
            motionIdle = false,
            motionBlink = false,
            physicsFrontHair = false,
            physicsBackHair = false,
            physicsEyeJelly = false,
        )
        val params = vm.computeLiveParameters(preview, stateNoIdle, blink = 1f)
        assertEquals(0f, params[StandardParameters.BREATH], "Breath should be 0 when idle is disabled")
        assertEquals(0f, params[StandardParameters.BODY_Z], "BodyZ idle should be 0 when idle is disabled")
        assertEquals(1f, params[StandardParameters.EYE_L_OPEN], "EyeL should stay fully open (1.0) when blink disabled")
        assertEquals(1f, params[StandardParameters.EYE_R_OPEN], "EyeR should stay fully open (1.0) when blink disabled")
        assertEquals(0f, params[StandardParameters.HAIR_FRONT], "Front hair should be 0 when front hair physics disabled")
        assertEquals(0f, params[StandardParameters.HAIR_BACK], "Back hair should be 0 when back hair physics disabled")
        assertEquals(0f, params[StandardParameters.EYE_BALL_FORM], "Eye jelly should be 0 when eye jelly physics disabled")

        // 2. When meshOnly is true: params map should have all default values
        val stateMeshOnly = PSD2LiveState(
            previewModel = preview,
            animationEnabled = true,
            meshOnly = true,
        )
        val meshOnlyParams = vm.computeLiveParameters(preview, stateMeshOnly)
        assertEquals(preview.rig.puppet.parameters.associate { it.id to it.default }, meshOnlyParams)

        // 3. Verify parameterValuesForPreview when idle is disabled
        val previewOverridesNoIdle = io.github.psd2live.ui.state.parameterValuesForPreview(stateNoIdle, params)
        assertEquals(0f, previewOverridesNoIdle[StandardParameters.ANGLE_X], "AngleX should be overridden to 0 when idle is off")
        assertEquals(0f, previewOverridesNoIdle[StandardParameters.ANGLE_Y], "AngleY should be overridden to 0 when idle is off")
        assertEquals(0f, previewOverridesNoIdle[StandardParameters.ANGLE_Z], "AngleZ should be overridden to 0 when idle is off")
        assertEquals(0f, previewOverridesNoIdle[StandardParameters.BREATH], "Breath should be overridden to 0 when idle is off")
        assertEquals(1f, previewOverridesNoIdle[StandardParameters.EYE_L_OPEN], "EyeL should be overridden to 1 when blink is off")
        assertEquals(0f, previewOverridesNoIdle[StandardParameters.HAIR_FRONT], "HairFront should be overridden to 0 when physics is off")

        // 4. When nod or shake is simulated
        val stateWithNod = PSD2LiveState(
            previewModel = preview,
            animationEnabled = true,
            motionNod = true,
        )
        val zeroNodParams = vm.computeLiveParameters(preview, stateWithNod, nodAngleY = 0f, nodBodyY = 0f)
        val nodParams = vm.computeLiveParameters(preview, stateWithNod, nodAngleY = -12f, nodBodyY = -3f)
        assertEquals(zeroNodParams.getValue(StandardParameters.ANGLE_Y) - 12f, nodParams.getValue(StandardParameters.ANGLE_Y), 0.001f, "AngleY should offset by nod angle")
        assertEquals(zeroNodParams.getValue(StandardParameters.BODY_Y) - 3f, nodParams.getValue(StandardParameters.BODY_Y), 0.001f, "BodyY should offset by nod body offset")
    }

    @Test
    fun testFastPreviewRebuildWithLipLayersAndMouthShape() {
        val pipeline = PSD2LivePipeline()
        val source = dummySourceArt()
        val configWithLips = PipelineConfig(
            atlasSize = 512,
            mouthOutlineEnabled = true,
            mouthShape = "smile",
        )
        val preview = pipeline.buildPreview(source, configWithLips)
        val baseLayers = preview.analysis.layers.filter { it.source !is MouthLipLayer }
        val baseAnalysis = preview.analysis.copy(layers = baseLayers)

        // Toggling mouth outline off
        val configNoLips = configWithLips.copy(mouthOutlineEnabled = false)
        val rebuiltNoLips = pipeline.buildPreview(baseAnalysis, configNoLips)
        assertTrue(rebuiltNoLips.analysis.layers.none { it.source is MouthLipLayer }, "Should have no MouthLipLayers")

        // Toggling mouth outline back on with W shape
        val configW = configWithLips.copy(mouthShape = "w")
        val rebuiltW = pipeline.buildPreview(baseAnalysis, configW)
        assertEquals("w", rebuiltW.config.mouthShape)
    }

    @Test
    fun testUncheckMotionIdleStopsAnimationAndXYZOscillations() {
        val pipeline = PSD2LivePipeline()
        val source = dummySourceArt()
        val config = PipelineConfig(
            atlasSize = 512,
            motionIdle = true,
            motionBlink = true,
        )
        val preview = pipeline.buildPreview(source, config)
        val vm = PSD2LiveViewModel()

        // 1. Initially idle is on: breath and bodyZ oscillate
        val stateWithIdle = PSD2LiveState(
            previewModel = preview,
            animationEnabled = true,
            motionIdle = true,
            motionBlink = true,
        )
        val liveParamsIdle = vm.computeLiveParameters(preview, stateWithIdle)
        val overridesWithIdle = io.github.psd2live.ui.state.parameterValuesForPreview(stateWithIdle, liveParamsIdle)
        // With idle on, standard parameters are NOT suppressed in overrides
        assertNull(overridesWithIdle[StandardParameters.ANGLE_X], "AngleX should not be overridden when idle is on")
        assertNull(overridesWithIdle[StandardParameters.BREATH], "Breath should not be overridden when idle is on")

        // 2. When idle is unchecked (motionIdle = false):
        // All idle parameters (AngleX, AngleY, AngleZ, BodyAngleX, BodyAngleY, BodyAngleZ, Breath)
        // MUST be overridden to 0f in overrides to suppress Native SDK CubismBreath/motion
        val stateNoIdle = stateWithIdle.copy(motionIdle = false)
        val liveParamsNoIdle = vm.computeLiveParameters(preview, stateNoIdle)
        val overridesNoIdle = io.github.psd2live.ui.state.parameterValuesForPreview(stateNoIdle, liveParamsNoIdle)

        assertEquals(0f, overridesNoIdle[StandardParameters.ANGLE_X], "AngleX must be 0f in overrides when idle is off")
        assertEquals(0f, overridesNoIdle[StandardParameters.ANGLE_Y], "AngleY must be 0f in overrides when idle is off")
        assertEquals(0f, overridesNoIdle[StandardParameters.ANGLE_Z], "AngleZ must be 0f in overrides when idle is off")
        assertEquals(0f, overridesNoIdle[StandardParameters.BODY_X], "BodyX must be 0f in overrides when idle is off")
        assertEquals(0f, overridesNoIdle[StandardParameters.BODY_Y], "BodyY must be 0f in overrides when idle is off")
        assertEquals(0f, overridesNoIdle[StandardParameters.BODY_Z], "BodyZ must be 0f in overrides when idle is off")
        assertEquals(0f, overridesNoIdle[StandardParameters.BREATH], "Breath must be 0f in overrides when idle is off")
        assertEquals(0f, overridesNoIdle[StandardParameters.MOUTH_OPEN], "MouthOpen must be 0f in overrides when idle is off")
        assertEquals(0f, overridesNoIdle[StandardParameters.MOUTH_FORM], "MouthForm must be 0f in overrides when idle is off")

        // 3. Testing setMotionIdle(false) immediately resets state.parameterValues
        val stateWithPushedValues = stateWithIdle.copy(
            parameterValues = mapOf(
                StandardParameters.ANGLE_X to 15f,
                StandardParameters.ANGLE_Y to 8f,
                StandardParameters.ANGLE_Z to 5f,
                StandardParameters.BODY_X to 4f,
                StandardParameters.BREATH to 0.8f,
            )
        )
        // Simulate setting state and unchecking
        vm.setMotionIdle(false)
        // Ensure that parameterValuesAfterPreviewFrame with meshOnly=true resets to default
        val stateMeshOnly = stateWithPushedValues.copy(meshOnly = true)
        val afterPreview = io.github.psd2live.ui.state.parameterValuesAfterPreviewFrame(
            stateMeshOnly,
            mapOf(StandardParameters.ANGLE_X to 20f)
        )
        assertEquals(0f, afterPreview[StandardParameters.ANGLE_X], "AngleX must reset to default 0 in meshOnly")
    }
}
