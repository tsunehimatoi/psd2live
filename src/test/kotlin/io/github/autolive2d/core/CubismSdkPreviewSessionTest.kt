package io.github.autolive2d.core

import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CubismSdkPreviewSessionTest {
	@Test
	fun `mouse tracking never owns angle z`() {
		assertEquals(0f, CUBISM_NATIVE_POINTER_Y, "native drag Y would introduce an AngleZ cross term")
		val trackedIds = CUBISM_POINTER_TRACKING_BINDINGS.mapTo(linkedSetOf()) { it.parameterId }
		assertEquals(
			setOf("ParamAngleX", "ParamAngleY", "ParamBodyAngleX", "ParamEyeBallX", "ParamEyeBallY"),
			trackedIds,
		)
		assertFalse("ParamAngleZ" in trackedIds)
	}

	@Test
	fun `preview transport preserves every runtime asset byte`() {
		val bundle = CubismRuntimeBundle(
			"sample.model3.json",
			listOf(
				CubismRuntimeAsset("sample.model3.json", "{}".encodeToByteArray()),
				CubismRuntimeAsset("sample.moc3", byteArrayOf(0, 1, 2, -1)),
			),
		)
		val encoded = bundle.encodePreviewBundle()
		val magic = "QDPREVIEW".encodeToByteArray()
		assertTrue(encoded.copyOfRange(0, magic.size).contentEquals(magic))
		assertEquals(1, readU32(encoded, 9))
		assertEquals("sample.model3.json".length, readU32(encoded, 13))
		assertEquals(2, readU32(encoded, 17))
		assertTrue(encoded.asList().containsAll(byteArrayOf(0, 1, 2, -1).asList()))
	}

	@Test
	fun `official runtime renders generated export and returns parameter values`() {
		if (!System.getProperty("os.name").contains("windows", ignoreCase = true) ||
			System.getProperty("autolive2d.cubism.smoke") != "true"
		) return

		val preview = AutoLive2DPipeline().buildPreview(Path.of("..", "Anime2.5DRig", "sample.psd"))
		val ready = CountDownLatch(1)
		val failure = AtomicReference<String?>()
		val frames = LinkedBlockingQueue<CubismSdkFrame>()
		val session = CubismSdkPreviewSession(
			onFrame = {
				frames.offer(it)
			},
			onStatus = {
				when {
					it == "ready" -> ready.countDown()
					it != null -> {
						failure.set(it)
						ready.countDown()
					}
				}
			},
		)
		try {
			session.load(preview.runtimeBundle, preview.rig.puppet.parameters.map { it.id })
			assertTrue(ready.await(15, TimeUnit.SECONDS), "Cubism SDK load timed out")
			assertEquals(null, failure.get(), failure.get())
			session.render(
				CubismSdkPreviewSession.RenderRequest(
					width = 320,
					height = 320,
					scale = 0.95f,
					offsetX = 0f,
					offsetY = 0f,
					deltaTime = 1f / 30f,
					pointerX = 0.35f,
					pointerY = -0.2f,
					parameterOverrides = mapOf(ParameterId("ParamAngleX") to 20f),
				),
			)
			val result = assertNotNull(frames.poll(15, TimeUnit.SECONDS), "Cubism SDK frame timed out: ${failure.get()}")
			assertEquals(320, result.image.width)
			assertEquals(20f, assertNotNull(result.parameters[ParameterId("ParamAngleX")]), 0.001f)
			assertTrue((0 until result.image.height).any { y ->
				(0 until result.image.width).any { x -> result.image.getRGB(x, y) ushr 24 != 0 }
			})

			// Drive the official look tracker in both directions. Look runs before physics in
			// Cubism's scheduler, so at least one generated hair output must visibly respond.
			val startNanos = System.nanoTime()
			var maximumHairResponse = 0f
			repeat(36) { index ->
				session.render(
					CubismSdkPreviewSession.RenderRequest(
						width = 160,
						height = 160,
						scale = 0.95f,
						offsetX = 0f,
						offsetY = 0f,
						deltaTime = 1f / 30f,
						pointerX = if (index < 18) 0.9f else -0.9f,
						pointerY = 0f,
						parameterOverrides = emptyMap(),
						frameTimeNanos = startNanos + (index + 1L) * 33_333_333L,
					),
				)
				val physicsFrame = assertNotNull(
					frames.poll(5, TimeUnit.SECONDS),
					"Cubism SDK physics frame timed out at $index: ${failure.get()}",
				)
				maximumHairResponse = maxOf(
					maximumHairResponse,
					abs(physicsFrame.parameters[StandardParameters.HAIR_FRONT] ?: 0f),
					abs(physicsFrame.parameters[StandardParameters.HAIR_BACK] ?: 0f),
				)
			}
			assertTrue(maximumHairResponse > 0.02f, "mouse-driven hair physics did not respond: $maximumHairResponse")
		} finally {
			session.close()
		}
	}

	private fun readU32(bytes: ByteArray, offset: Int): Int =
		(bytes[offset].toInt() and 0xff) or
			((bytes[offset + 1].toInt() and 0xff) shl 8) or
			((bytes[offset + 2].toInt() and 0xff) shl 16) or
			((bytes[offset + 3].toInt() and 0xff) shl 24)
}
