package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionClipsTest {
	private fun curve(vararg keys: MotionKey) = MotionCurve("ParamAngleX", keys.toList())

	@Test fun linearSegmentsInterpolateAndHoldTheirEnds() {
		val c = curve(MotionKey(0.5f, 0f), MotionKey(1.5f, 10f))
		assertEquals(0f, MotionClips.sample(c, 0f))
		assertEquals(5f, MotionClips.sample(c, 1f), 1e-4f)
		assertEquals(10f, MotionClips.sample(c, 3f))
	}

	@Test fun steppedHoldsTheStartAndInverseSteppedTheEnd() {
		val stepped = curve(MotionKey(0f, 1f, MotionInterpolation.STEPPED), MotionKey(1f, 5f))
		assertEquals(1f, MotionClips.sample(stepped, 0.99f))
		assertEquals(5f, MotionClips.sample(stepped, 1f))
		val inverse = curve(MotionKey(0f, 1f, MotionInterpolation.INVERSE_STEPPED), MotionKey(1f, 5f))
		assertEquals(5f, MotionClips.sample(inverse, 0.01f))
	}

	@Test fun aDefaultBezierEasesThroughTheMiddleAndStaysMonotonic() {
		val c = curve(MotionKey(0f, 0f, MotionInterpolation.BEZIER), MotionKey(1f, 1f))
		assertEquals(0.5f, MotionClips.sample(c, 0.5f), 1e-3f)
		// Eased: slower than linear near the start.
		assertTrue(MotionClips.sample(c, 0.1f) < 0.1f)
		val samples = (0..50).map { MotionClips.sample(c, it / 50f) }
		assertTrue(samples.zipWithNext().all { (a, b) -> b >= a - 1e-5f })
	}

	@Test fun convertedTracksSampleLikeTheGeneratedMotion() {
		val clip = MotionClips.fromTracks("c", "Nod", "Nod", loop = false, tracks = MotionGenerator.nodTracks)
		for (time in listOf(0f, 0.3f, 0.55f, 1f, 1.7f, 2f)) {
			for ((id, points) in MotionGenerator.nodTracks) {
				assertEquals(
					SkeletonMotions.sample(points, time.toDouble(), loop = false),
					MotionClips.sampleAll(clip, time.toDouble()).getValue(org.umamo.runtime.model.ParameterId(id)),
					1e-4f,
				)
			}
		}
		assertEquals(2f, clip.duration)
	}

	@Test fun normalizedKeepsTheLaterKeyAtOneTime() {
		val keys = MotionClips.normalized(listOf(MotionKey(1f, 1f), MotionKey(0f, 0f), MotionKey(1f, 2f)))
		assertEquals(listOf(0f to 0f, 1f to 2f), keys.map { it.time to it.value })
	}

	@Test fun jsonRoundTripKeepsEveryField() {
		val clip = MotionClip(
			id = "motion_1", name = "Wave hello", loop = true, duration = 3f, fps = 60f, fadeIn = 0.2f, fadeOut = 0.4f, enabled = false,
			curves = listOf(
				MotionCurve("ParamAngleX", listOf(
					MotionKey(0f, 0f, MotionInterpolation.BEZIER, outHandle = MotionHandle(0.5f, 2f), inHandle = MotionHandle(0.2f, -1f)),
					MotionKey(1f, 10f, MotionInterpolation.STEPPED),
					MotionKey(3f, 0f),
				)),
			),
		)
		val restored = MotionClips.fromJson(Json.parseToJsonElement(MotionClips.toJson(clip).toString()).jsonObject)
		assertEquals(clip, restored)
		val override = clip.copy(id = "motion_2", builtin = "Nod", name = "Nod")
		assertEquals(override, MotionClips.fromJson(MotionClips.toJson(override)))
	}

	@Test fun clipExportWritesEachSegmentTypeAndCountsItsPoints() {
		val clip = MotionClip(
			id = "m", name = "m", duration = 2f, fps = 24f, fadeIn = 0.25f, fadeOut = 0.5f,
			curves = listOf(
				MotionCurve("ParamAngleX", listOf(
					MotionKey(0f, 0f, MotionInterpolation.BEZIER),
					MotionKey(1f, 10f, MotionInterpolation.STEPPED),
					MotionKey(2f, 0f),
				)),
				// Starts late: a hold from zero is added.
				MotionCurve("ParamAngleY", listOf(MotionKey(0.5f, 3f), MotionKey(1f, 4f))),
				MotionCurve("ParamMissing", listOf(MotionKey(0f, 1f), MotionKey(1f, 2f))),
			),
		)
		val json = assertNotNull(MotionGenerator.clip(clip, setOf("ParamAngleX", "ParamAngleY")))
		val root = Json.parseToJsonElement(CubismJson.normalize(json)).jsonObject
		val meta = root.getValue("Meta").jsonObject
		assertEquals(2, meta.getValue("CurveCount").jsonPrimitive.int)
		assertEquals(24f, meta.getValue("Fps").jsonPrimitive.float)
		assertEquals(0.25f, meta.getValue("FadeInTime").jsonPrimitive.float)
		assertEquals(0.5f, meta.getValue("FadeOutTime").jsonPrimitive.float)
		// X: bezier (3 points) + stepped (1); Y: hold (1) + linear (1); plus each curve's first point.
		assertEquals(4, meta.getValue("TotalSegmentCount").jsonPrimitive.int)
		assertEquals(4 + 1 + 2 + 1, meta.getValue("TotalPointCount").jsonPrimitive.int)
		val curves = root.getValue("Curves").jsonArray
		val x = curves[0].jsonObject.getValue("Segments").jsonArray.map { it.jsonPrimitive.float }
		assertEquals(listOf(0f, 0f, 1f), x.take(3))
		assertEquals(2f, x[9]) // stepped segment type after the Bezier's 6 numbers
		val y = curves[1].jsonObject.getValue("Segments").jsonArray.map { it.jsonPrimitive.float }
		assertEquals(listOf(0f, 3f, 0f, 0.5f, 3f, 0f, 1f, 4f), y)
		assertNull(MotionGenerator.clip(clip, emptySet()))
	}

	@Test fun generatedMotionsExportUnchangedWithoutFades() {
		val nod = assertNotNull(MotionGenerator.nod(setOf("ParamAngleY")))
		val meta = Json.parseToJsonElement(CubismJson.normalize(nod)).jsonObject.getValue("Meta").jsonObject
		assertTrue("FadeInTime" !in meta)
		assertEquals(30f, meta.getValue("Fps").jsonPrimitive.float)
	}

	@Test fun exportStemsStayUniqueBesideTheGeneratedMotions() {
		val clips = listOf(
			MotionClip("a", "Nod"),
			MotionClip("b", "happy jump"),
			MotionClip("c", "Happy-Jump"),
			MotionClip("d", "打招呼"),
			MotionClip("e", "Nod", builtin = "Nod"),
		)
		val stems = MotionClips.exportStems(clips)
		assertEquals(mapOf("a" to "nod2", "b" to "happyJump", "c" to "happyJump2", "d" to "motion"), stems)
	}

	@Test fun uniqueNamesAvoidClipsAndGeneratedMotions() {
		val clips = listOf(MotionClip("a", "Motion"), MotionClip("b", "Motion 2"))
		assertEquals("Motion 3", MotionClips.uniqueName(clips, "Motion"))
		assertEquals("Blink 2", MotionClips.uniqueName(clips, "Blink"))
		assertEquals("motion_3", MotionClips.newId(clips))
	}

	@Test fun builtinTracksMatchWhatTheExportWrites() {
		assertEquals(MotionGenerator.blinkTracks, MotionClips.builtinTracks("Blink", null))
		val idle = MotionClips.builtinTracks("Idle", null)
		assertTrue(idle.any { it.first == "ParamEyeLOpen" })
		assertEquals(SkeletonMotions.IDLE_DURATION, MotionClips.builtinDuration("Idle", idle))
		assertTrue(MotionClips.isLoopBuiltin("idle"))
		assertTrue(MotionClips.isLoopBuiltin("IdleCute"))
	}
}
