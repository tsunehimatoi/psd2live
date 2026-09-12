package io.github.psd2live.ui

import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayScalingSettingsTest {

	@Test
	fun testDisplayMetricsDetection() {
		val metrics = AppSettings.detectDisplayMetrics()
		assertTrue(metrics.physicalWidth > 0, "Physical width should be positive")
		assertTrue(metrics.physicalHeight > 0, "Physical height should be positive")
		assertTrue(metrics.systemScalePercent >= 100, "System scale percent should be at least 100")
		assertTrue(metrics.recommendedScale in 0.75f..2.5f, "Recommended scale should be within reasonable bounds")
	}

	@Test
	fun testUiScaleClampingAndPersistence() {
		val initialCustom = AppSettings.hasCustomUiScale

		try {
			AppSettings.uiScale = 1.25f
			assertEquals(1.25f, AppSettings.uiScale, 0.001f)
			assertTrue(AppSettings.hasCustomUiScale)

			AppSettings.uiScale = 0.5f // below minimum
			assertEquals(0.75f, AppSettings.uiScale, 0.001f)

			AppSettings.uiScale = 4.0f // above maximum
			assertEquals(3.0f, AppSettings.uiScale, 0.001f)
		} finally {
			AppSettings.resetToDefaults()
		}
	}

	@Test
	fun testFontScaleClamping() {
		try {
			AppSettings.fontScale = 1.15f
			assertEquals(1.15f, AppSettings.fontScale, 0.001f)

			AppSettings.fontScale = 0.5f // below minimum
			assertEquals(0.85f, AppSettings.fontScale, 0.001f)

			AppSettings.fontScale = 2.0f // above maximum
			assertEquals(1.5f, AppSettings.fontScale, 0.001f)
		} finally {
			AppSettings.resetToDefaults()
		}
	}

	@Test
	fun testViewModelZoomStepActions() {
		val viewModel = PSD2LiveViewModel()
		try {
			viewModel.setUiScale(1.0f)
			assertEquals(1.0f, viewModel.state.value.uiScale, 0.01f)

			viewModel.zoomIn()
			val zoomedIn = viewModel.state.value.uiScale
			assertTrue(zoomedIn > 1.0f, "Zoom in should increase scale")

			viewModel.zoomIn()
			val zoomedIn2 = viewModel.state.value.uiScale
			assertTrue(zoomedIn2 > zoomedIn, "Second zoom in should increase further")

			viewModel.zoomOut()
			val zoomedOut = viewModel.state.value.uiScale
			assertTrue(zoomedOut < zoomedIn2, "Zoom out should decrease scale")

			viewModel.setFontScale(1.15f)
			assertEquals(1.15f, viewModel.state.value.fontScale, 0.01f)

			viewModel.resetZoom()
			assertEquals(AppSettings.defaultUiScale(), viewModel.state.value.uiScale, 0.01f)
			assertEquals(1.0f, viewModel.state.value.fontScale, 0.01f)
		} finally {
			viewModel.close()
			AppSettings.resetToDefaults()
		}
	}

	@Test
	fun testSettingsDialogToggle() {
		val viewModel = PSD2LiveViewModel()
		try {
			assertEquals(false, viewModel.state.value.showSettingsDialog)
			viewModel.openSettingsDialog()
			assertEquals(true, viewModel.state.value.showSettingsDialog)
			viewModel.closeSettingsDialog()
			assertEquals(false, viewModel.state.value.showSettingsDialog)
		} finally {
			viewModel.close()
		}
	}

	@Test
	fun testClickToSelectLayerToggleAndPersistence() {
		val viewModel = PSD2LiveViewModel()
		try {
			assertTrue(viewModel.state.value.clickToSelectLayer, "clickToSelectLayer should default to true")
			viewModel.setClickToSelectLayer(false)
			assertEquals(false, viewModel.state.value.clickToSelectLayer)
			assertEquals(false, AppSettings.clickToSelectLayer)

			viewModel.setClickToSelectLayer(true)
			assertEquals(true, viewModel.state.value.clickToSelectLayer)
			assertEquals(true, AppSettings.clickToSelectLayer)
		} finally {
			viewModel.close()
			AppSettings.resetToDefaults()
		}
	}
}

