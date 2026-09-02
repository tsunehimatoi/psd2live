package io.github.autolive2d.ui

import io.github.autolive2d.core.StandardParameters
import io.github.autolive2d.i18n.AppLanguage
import io.github.autolive2d.i18n.I18n
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParameterPanelTest {

	@Test
	fun `resetAll resets all parameters to their default values`() {
		var changedOverrides: Map<ParameterId, Float>? = null
		val panel = ParameterPanel { changedOverrides = it }

		val paramAngleX = Parameter(StandardParameters.ANGLE_X, "Angle X", -45f, 45f, 0f)
		val paramEyeL = Parameter(StandardParameters.EYE_L_OPEN, "Eye L Open", 0f, 1f, 1f)
		panel.setParameters(listOf(paramAngleX, paramEyeL))

		// Set overrides to non-default values
		panel.setOverride(paramAngleX, 30f)
		panel.setOverride(paramEyeL, 0.2f)

		assertEquals(30f, panel.parameterOverrides[paramAngleX.id])
		assertEquals(0.2f, panel.parameterOverrides[paramEyeL.id])

		// Reset all
		panel.resetAll()

		val overrides = panel.parameterOverrides
		assertEquals(2, overrides.size)
		assertEquals(0f, overrides[paramAngleX.id], "Angle X should be reset to default 0.0")
		assertEquals(1f, overrides[paramEyeL.id], "Eye L Open should be reset to default 1.0")
		assertEquals(overrides, changedOverrides)
	}

	@Test
	fun `reset single parameter resets it to default value`() {
		var changedOverrides: Map<ParameterId, Float>? = null
		val panel = ParameterPanel { changedOverrides = it }

		val paramAngleX = Parameter(StandardParameters.ANGLE_X, "Angle X", -45f, 45f, 0f)
		val paramEyeL = Parameter(StandardParameters.EYE_L_OPEN, "Eye L Open", 0f, 1f, 1f)
		panel.setParameters(listOf(paramAngleX, paramEyeL))

		panel.setOverride(paramAngleX, 25f)
		panel.setOverride(paramEyeL, 0.5f)

		panel.reset(paramAngleX)

		assertEquals(0f, panel.parameterOverrides[paramAngleX.id])
		assertEquals(0.5f, panel.parameterOverrides[paramEyeL.id])
	}

	@Test
	fun `refreshTexts works across all languages`() {
		val panel = ParameterPanel {}
		for (lang in I18n.supportedLanguages) {
			I18n.setLanguage(lang, persist = false)
			panel.refreshTexts()
		}
		I18n.setLanguage(AppLanguage.CHINESE, persist = false)
		panel.refreshTexts()
	}
}

