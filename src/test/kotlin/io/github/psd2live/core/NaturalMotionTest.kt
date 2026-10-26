package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalMotionTest {
	@Test fun theNaturalIdleBlinksUnevenlyAndGlances() {
		val natural = MotionPresets.tracks("Idle", null, MotionPresetSettings(natural = true))
		val legacy = MotionPresets.tracks("Idle", null, MotionPresetSettings())
		assertTrue(natural.any { it.parameterId == "ParamEyeBallX" } && legacy.none { it.parameterId == "ParamEyeBallX" }, "the natural idle glances")
		val left = natural.single { it.parameterId == "ParamEyeLOpen" }; val right = natural.single { it.parameterId == "ParamEyeROpen" }
		// Shut fast, open slowly, the right eye a frame behind the left.
		val shut = left.keys.first { it.value == 0f }.time
		assertEquals(0.07f, shut - left.keys.last { it.time < shut && it.value == 1f }.time, 1e-4f)
		assertTrue(left.keys.first { it.time > shut && it.value == 1f }.time - shut > 0.15f, "it opens more slowly than it shuts")
		assertEquals(1f / 60f, right.keys.first { it.value == 0f }.time - shut, 1e-4f)
		assertEquals(MotionPresets.tracks("Idle", null, MotionPresetSettings()), legacy, "the legacy idle is as it was")
	}
}
