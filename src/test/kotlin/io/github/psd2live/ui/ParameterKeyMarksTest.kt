package io.github.psd2live.ui

import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParameterKeyMarksTest {
	@Test
	fun nearestKeyPoseSnapsOffKeyAxesOnly() {
		val axis = KeyformAxis(ParameterId("ParamAngleX"), floatArrayOf(-30f, 0f, 30f))
		val pose = mapOf(ParameterId("ParamAngleX") to 12f)
		val defaults = mapOf(ParameterId("ParamAngleX") to 0f)
		val snapped = nearestKeyPose(listOf(axis), pose, defaults)
		assertEquals(mapOf(ParameterId("ParamAngleX") to 0f), snapped)
	}

	@Test
	fun nearestKeyPoseIgnoresAlreadyOnKey() {
		val axis = KeyformAxis(ParameterId("ParamAngleX"), floatArrayOf(-30f, 0f, 30f))
		val pose = mapOf(ParameterId("ParamAngleX") to 0.0005f)
		assertTrue(isOnParameterKey(0.0005f, axis.keys))
		assertEquals(emptyMap(), nearestKeyPose(listOf(axis), pose, emptyMap()))
	}

	@Test
	fun nearestParameterKeyPicksClosest() {
		assertEquals(30f, nearestParameterKey(22f, floatArrayOf(-30f, 0f, 30f)))
		assertFalse(isOnParameterKey(15f, floatArrayOf(-30f, 0f, 30f)))
	}
}
