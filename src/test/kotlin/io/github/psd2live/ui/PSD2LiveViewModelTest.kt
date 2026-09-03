package io.github.psd2live.ui

import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.mergeUnlockedParameterValues
import io.github.psd2live.ui.state.parameterValuesAfterPreviewFrame
import io.github.psd2live.ui.state.parameterValuesAfterSoftwareFrame
import io.github.psd2live.ui.state.parameterValuesForPreview
import io.github.psd2live.ui.state.previewFrameMatchesState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PSD2LiveViewModelTest {

	@Test
	fun `default state builds valid standard pipeline config`() {
		val state = PSD2LiveState()
		val config = state.buildConfig()

		assertEquals(4096, config.atlasSize)
		assertEquals(64, config.meshSpacing)
		assertEquals(2, config.texturePadding)
		assertEquals(8, config.alphaThreshold)
		assertEquals(1.0f, config.headTurnStrength)
		assertEquals(1.0f, config.bodyStrength)
		assertFalse(config.meshOnly)
		assertTrue(config.generateDeformers)
		assertTrue(config.exportMotions)
		assertTrue(config.motionIdle)
		assertTrue(config.motionBlink)
		assertTrue(config.motionNod)
		assertTrue(config.motionShake)
		assertTrue(config.generatePhysics)
		assertTrue(config.physicsFrontHair)
		assertTrue(config.physicsBackHair)
		assertTrue(config.physicsEyeJelly)
		assertTrue(config.exportCmo3)
		assertTrue(config.exportMoc3)
		assertTrue(config.exportJson)
	}

	@Test
	fun `export options setters update state properly`() {
		val vm = PSD2LiveViewModel()
		try {
			// Sub-items toggle updates parent exportMotions and generatePhysics
			vm.setMotionIdle(false)
			vm.setMotionBlink(false)
			vm.setMotionNod(false)
			vm.setMotionShake(false)
			assertFalse(vm.state.value.exportMotions)
			assertFalse(vm.state.value.buildConfig().exportMotions)

			vm.setMotionIdle(true)
			assertTrue(vm.state.value.exportMotions)
			assertTrue(vm.state.value.buildConfig().exportMotions)

			vm.setPhysicsFrontHair(false)
			vm.setPhysicsBackHair(false)
			vm.setPhysicsEyeJelly(false)
			assertFalse(vm.state.value.generatePhysics)
			assertFalse(vm.state.value.buildConfig().generatePhysics)

			vm.setPhysicsEyeJelly(true)
			assertTrue(vm.state.value.generatePhysics)
			assertTrue(vm.state.value.buildConfig().generatePhysics)

			// Setting meshOnly=true automatically deactivates deformers, motions, physics in buildConfig
			vm.setMeshOnly(true)
			val meshOnlyState = vm.state.value
			assertTrue(meshOnlyState.meshOnly)
			assertFalse(meshOnlyState.generateDeformers)
			val meshOnlyConfig = meshOnlyState.buildConfig()
			assertTrue(meshOnlyConfig.meshOnly)
			assertFalse(meshOnlyConfig.generateDeformers)
			assertFalse(meshOnlyConfig.exportMotions)
			assertFalse(meshOnlyConfig.generatePhysics)

			// Setting meshOnly=false restores deformers and evaluates motions/physics from sub-items
			vm.setMeshOnly(false)
			val restoredState = vm.state.value
			assertFalse(restoredState.meshOnly)
			assertTrue(restoredState.generateDeformers)
			val restoredConfig = restoredState.buildConfig()
			assertFalse(restoredConfig.meshOnly)
			assertTrue(restoredConfig.generateDeformers)
			assertTrue(restoredConfig.exportMotions)
			assertTrue(restoredConfig.generatePhysics)
		} finally {
			vm.close()
		}
	}

	@Test
	fun `manual settings updates and resetSettingsToDefault work correctly`() {
		val vm = PSD2LiveViewModel()
		try {
			vm.setAtlasSize(8192)
			vm.setMeshSpacing(32)
			vm.setHeadStrength(1.5f)

			val modifiedState = vm.state.value
			assertEquals(8192, modifiedState.atlasSize)
			assertEquals(32, modifiedState.meshSpacing)
			assertEquals(1.5f, modifiedState.headStrength)

			// Reset to Defaults
			vm.resetSettingsToDefault()
			val resetState = vm.state.value
			assertEquals(4096, resetState.atlasSize)
			assertEquals(64, resetState.meshSpacing)
			assertEquals(1.0f, resetState.headStrength)
		} finally {
			vm.close()
		}
	}

	@Test
	fun `layer overrides and visibility toggle accurately`() {
		val vm = PSD2LiveViewModel()
		try {
			vm.setLayerVisibility("layer_1", false)
			assertFalse(vm.state.value.isLayerVisible("layer_1"))

			vm.setLayerVisibility("layer_1", true)
			assertTrue(vm.state.value.isLayerVisible("layer_1"))

			vm.setLayerClassification("layer_2", LayerClassificationOverride(SemanticTag.FACE, Side.NONE))
			assertEquals(LayerType.PRESET, vm.state.value.layerOverrides["layer_2"]?.type)
			assertEquals(SemanticTag.FACE, vm.state.value.layerOverrides["layer_2"]?.tag)
			assertEquals(Side.NONE, vm.state.value.layerOverrides["layer_2"]?.side)

			vm.setLayerClassification(
				"layer_3",
				LayerClassificationOverride(
					type = LayerType.TOGGLE,
					parameter = "show_tears",
				),
			)
			assertEquals(LayerType.TOGGLE, vm.state.value.layerOverrides["layer_3"]?.type)
			assertEquals("show_tears", vm.state.value.layerOverrides["layer_3"]?.parameter)

			vm.setLayerClassification(
				"layer_4",
				LayerClassificationOverride(
					type = LayerType.SWITCH,
					parameter = "arm_pose",
					switchId = 2,
				),
			)
			assertEquals(LayerType.SWITCH, vm.state.value.layerOverrides["layer_4"]?.type)
			assertEquals("arm_pose", vm.state.value.layerOverrides["layer_4"]?.parameter)
			assertEquals(2, vm.state.value.layerOverrides["layer_4"]?.switchId)
		} finally {
			vm.close()
		}
	}

	@Test
	fun `parameter locking, unlocking, and resets work as expected`() {
		val vm = PSD2LiveViewModel()
		try {
			// Moving a slider writes the shared value without changing the explicit lock.
			vm.setParameterValue(StandardParameters.ANGLE_X, 15.0f)
			assertFalse(StandardParameters.ANGLE_X in vm.state.value.lockedParameters)
			assertEquals(15.0f, vm.state.value.parameterValues[StandardParameters.ANGLE_X])

			// Explicitly lock the current animated value.
			vm.toggleParameterLock(StandardParameters.ANGLE_X, 25.0f)
			assertTrue(StandardParameters.ANGLE_X in vm.state.value.lockedParameters)
			assertEquals(25.0f, vm.state.value.parameterValues[StandardParameters.ANGLE_X])

			// Adjusting a locked parameter updates the shared value.
			vm.setParameterValue(StandardParameters.ANGLE_X, 30.0f)
			assertEquals(30.0f, vm.state.value.parameterValues[StandardParameters.ANGLE_X])

			// Explicitly unlock parameter releases animation control.
			vm.toggleParameterLock(StandardParameters.ANGLE_X)
			assertFalse(StandardParameters.ANGLE_X in vm.state.value.lockedParameters)

			// Lock again and reset
			vm.toggleParameterLock(StandardParameters.ANGLE_Y, -10.0f)
			assertTrue(StandardParameters.ANGLE_Y in vm.state.value.lockedParameters)
			vm.resetParameter(StandardParameters.ANGLE_Y)
			assertTrue(StandardParameters.ANGLE_Y in vm.state.value.lockedParameters)
			assertEquals(0f, vm.state.value.parameterValues[StandardParameters.ANGLE_Y])

			// Lock multiple and unlock all
			vm.toggleParameterLock(StandardParameters.ANGLE_X, 10.0f)
			assertEquals(2, vm.state.value.lockedParameters.size)
			vm.unlockAllParameters()
			assertEquals(0, vm.state.value.lockedParameters.size)
		} finally {
			vm.close()
		}
	}

	@Test
	fun `preview frames update only unlocked values in the shared parameter state`() {
		val angleX = StandardParameters.ANGLE_X
		val angleY = StandardParameters.ANGLE_Y
		val current = mapOf(angleX to 18f, angleY to 2f)

		val merged = mergeUnlockedParameterValues(
			current = current,
			incoming = mapOf(angleX to -12f, angleY to 7f),
			locked = setOf(angleX),
		)

		assertEquals(18f, merged[angleX])
		assertEquals(7f, merged[angleY])
	}

	@Test
	fun `paused preview receives the shared snapshot instead of animation data`() {
		val angleX = StandardParameters.ANGLE_X
		val angleY = StandardParameters.ANGLE_Y
		val values = mapOf(angleX to 14f, angleY to -3f)

		val paused = PSD2LiveState(
			animationEnabled = false,
			parameterValues = values,
		)
		assertEquals(values, parameterValuesForPreview(paused))
		assertEquals(
			values,
			parameterValuesAfterPreviewFrame(paused, mapOf(angleX to -20f, angleY to 9f)),
		)
		assertEquals(
			values,
			parameterValuesAfterSoftwareFrame(paused, mapOf(angleX to -20f, angleY to 9f)),
		)
		assertTrue(previewFrameMatchesState(paused, false))
		assertFalse(previewFrameMatchesState(paused, true))
		assertTrue(previewFrameMatchesState(paused.copy(parameterValues = values + (angleX to 20f)), false))

		val playing = paused.copy(
			animationEnabled = true,
			lockedParameters = setOf(angleY),
		)
		assertEquals(mapOf(angleY to -3f), parameterValuesForPreview(playing))
	}

	@Test
	fun `mouse tracking toggling and pointer clearing work as expected`() {
		val vm = PSD2LiveViewModel()
		try {
			assertTrue(vm.state.value.mouseTrackingEnabled)

			vm.updatePointer(0.5f, -0.5f)
			vm.setMouseTrackingEnabled(false)
			assertFalse(vm.state.value.mouseTrackingEnabled)

			vm.setMouseTrackingEnabled(true)
			assertTrue(vm.state.value.mouseTrackingEnabled)

			vm.clearPointer()
			vm.resetAllParameters()
			assertEquals(0, vm.state.value.lockedParameters.size)
		} finally {
			vm.close()
		}
	}

	@Test
	fun `language switching updates state reactively`() {
		val vm = PSD2LiveViewModel()
		try {
			for (lang in I18n.supportedLanguages) {
				vm.setLanguage(lang)
				assertEquals(lang, vm.state.value.currentLanguage)
			}
			vm.setLanguage(AppLanguage.CHINESE)
			assertEquals(AppLanguage.CHINESE, vm.state.value.currentLanguage)
		} finally {
			vm.close()
		}
	}

	@Test
	fun `output path and last export directory tracking work accurately`() {
		val vm = PSD2LiveViewModel()
		try {
			vm.setInputPath("D:\\test\\sample.psd")
			assertEquals("D:\\test\\sample.psd", vm.state.value.inputPath)
			assertTrue(vm.state.value.outputPath.endsWith("sample-psd2live"))

			vm.setOutputPath("D:\\custom_export\\dir")
			assertEquals("D:\\custom_export\\dir", vm.state.value.outputPath)
			assertEquals("D:\\custom_export\\dir", vm.lastExportDirectory)

			vm.generateRig("D:\\another_export\\dir")
			assertEquals("D:\\another_export\\dir", vm.state.value.outputPath)
			assertEquals("D:\\another_export\\dir", vm.lastExportDirectory)
		} finally {
			vm.close()
		}
	}
}
