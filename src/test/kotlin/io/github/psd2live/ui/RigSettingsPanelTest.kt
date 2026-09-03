package io.github.psd2live.ui

import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RigSettingsPanelTest {

	@Test
	fun `default settings produce valid standard pipeline config`() {
		val panel = RigSettingsPanel()
		val config = panel.buildConfig()

		assertEquals(4096, config.atlasSize)
		assertEquals(64, config.meshSpacing)
		assertEquals(2, config.texturePadding)
		assertEquals(8, config.alphaThreshold)
		assertEquals(1.0f, config.headTurnStrength)
		assertEquals(1.0f, config.bodyStrength)
		assertTrue(config.generatePhysics)
		assertTrue(config.exportCmo3)
		assertTrue(config.exportMoc3)
	}

	@Test
	fun `applying presets updates settings and config correctly`() {
		var notified = 0
		val panel = RigSettingsPanel(onSettingsChanged = { notified++ })

		// Apply High Detail Preset
		panel.applyPreset(RigSettingsPanel.Preset.HIGH_DETAIL)
		val highConfig = panel.buildConfig()
		assertEquals(8192, highConfig.atlasSize)
		assertEquals(32, highConfig.meshSpacing)
		assertEquals(1.1f, highConfig.headTurnStrength)
		assertEquals(1.0f, highConfig.bodyStrength)
		assertTrue(notified > 0)

		// Apply Performance Preset
		val prevNotified = notified
		panel.applyPreset(RigSettingsPanel.Preset.PERFORMANCE)
		val fastConfig = panel.buildConfig()
		assertEquals(2048, fastConfig.atlasSize)
		assertEquals(96, fastConfig.meshSpacing)
		assertEquals(0.9f, fastConfig.headTurnStrength)
		assertEquals(0.9f, fastConfig.bodyStrength)
		assertTrue(notified > prevNotified)

		// Reset to Defaults
		panel.resetToDefaults()
		val resetConfig = panel.buildConfig()
		assertEquals(4096, resetConfig.atlasSize)
		assertEquals(64, resetConfig.meshSpacing)
		assertEquals(1.0f, resetConfig.headTurnStrength)
	}

	@Test
	fun `controls enabled state can be toggled`() {
		val panel = RigSettingsPanel()
		panel.setControlsEnabled(false)
		panel.setControlsEnabled(true)
		val config = panel.buildConfig()
		assertEquals(4096, config.atlasSize)
	}

	@Test
	fun `refreshTexts works across different languages without errors`() {
		val panel = RigSettingsPanel()
		for (lang in I18n.supportedLanguages) {
			I18n.setLanguage(lang, persist = false)
			panel.refreshTexts()
		}
		I18n.setLanguage(AppLanguage.CHINESE, persist = false)
		panel.refreshTexts()
	}
}

