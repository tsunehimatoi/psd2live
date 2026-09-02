package io.github.autolive2d.core

import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CubismSdkPreviewSessionTest {
	@Test
	fun `mouse tracking never owns angle z`() {
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
		val rendered = CountDownLatch(1)
		val failure = AtomicReference<String?>()
		val frame = AtomicReference<CubismSdkFrame?>()
		val session = CubismSdkPreviewSession(
			onFrame = {
				frame.set(it)
				rendered.countDown()
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
			assertTrue(rendered.await(15, TimeUnit.SECONDS), "Cubism SDK frame timed out: ${failure.get()}")
			val result = assertNotNull(frame.get())
			assertEquals(320, result.image.width)
			assertEquals(20f, assertNotNull(result.parameters[ParameterId("ParamAngleX")]), 0.001f)
			assertTrue((0 until result.image.height).any { y ->
				(0 until result.image.width).any { x -> result.image.getRGB(x, y) ushr 24 != 0 }
			})
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
