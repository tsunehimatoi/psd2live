package io.github.psd2live.targets.raster

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class RasterTargetsTest {
	/** Paints a square whose x offset is the parameter value, and records what it was asked. */
	private class FakeRenderer : FrameRenderer {
		val calls = mutableListOf<Pair<Map<String, Float>, Float>>()
		var physics: Boolean? = null
		override fun open(ir: RigIR, physics: Boolean): FrameSession {
			this.physics = physics
			return object : FrameSession {
				override val simulatesPhysics = physics
				override fun render(parameters: Map<String, Float>, deltaSeconds: Float, frame: FrameSpec, meshes: Set<String>?): RasterImage {
					calls += parameters to deltaSeconds
					val pixels = IntArray(frame.outputWidth * frame.outputHeight)
					val x = (parameters["P"] ?: 0f).toInt().coerceIn(0, frame.outputWidth - 4)
					for (dy in 0 until 4) for (dx in 0 until 4) pixels[dy * frame.outputWidth + x + dx] = 0xffff0000.toInt()
					return RasterImage(frame.outputWidth, frame.outputHeight, pixels)
				}
				override fun close() {}
			}
		}
	}

	private val clip = Clip("move", "Move", "Idle", "move", 1f, 4f, true,
		curves = listOf(Curve("P", 0f, 0f, listOf(CurveSegment.Linear(1f, 12f)))))
	private val rig = RigIR(canvas = Canvas(64f, 32f), parameters = listOf(Parameter("P", "P", 0f, 20f, 0f)), clips = listOf(clip),
		physics = Physics(listOf(PhysicsGroup("H", "H", emptyList(), emptyList(), listOf(PhysicsSegment(1f, 1f, 1f, 1f)),
			PhysicsNormalization(-1f, 0f, 1f, -1f, 0f, 1f)))))

	private fun export(target: ExportTarget, vararg settings: Pair<String, String>): Pair<Map<String, ByteArray>, ExportReport> {
		val files = LinkedHashMap<String, ByteArray>()
		val report = Compiler.export(target, rig, ExportOptions("anim", settings = mapOf(*settings))) { path, bytes -> files[path] = bytes }
		return files to report
	}

	@Test fun sequencesSampleTheClipAtItsFrameRate() {
		val renderer = FakeRenderer()
		val (files, report) = export(RasterTargets(renderer).sequence, "size" to "64")
		// A looping 1 s clip at 4 fps: four frames, the loop's end being its start.
		assertEquals(listOf("anim_0000.png", "anim_0001.png", "anim_0002.png", "anim_0003.png"), files.keys.toList())
		assertEquals(listOf(0f, 3f, 6f, 9f), renderer.calls.map { it.first.getValue("P") })
		assertEquals(listOf(0f, 0.25f, 0.25f, 0.25f), renderer.calls.map { it.second })
		assertEquals(true, renderer.physics)
		// Physics was simulated, so only the structure is reported lost.
		assertEquals(listOf(Feature.STRUCTURE), report.losses.map { it.feature })
		val frame = ImageIO.read(ByteArrayInputStream(files.getValue("anim_0002.png")))
		assertEquals(64 to 32, frame.width to frame.height)
		assertEquals(0xffff0000.toInt(), frame.getRGB(7, 1))
	}

	@Test fun sheetsTileFramesAndDescribeThem() {
		val (files, _) = export(RasterTargets(FakeRenderer()).sheet, "size" to "64", "fps" to "2", "physics" to "false")
		val sheet = ImageIO.read(ByteArrayInputStream(files.getValue("anim.png")))
		assertEquals(128 to 32, sheet.width to sheet.height)
		val json = files.getValue("anim.json").decodeToString()
		assertTrue("\"anim_0001\": {\"frame\": {\"x\": 64, \"y\": 0, \"w\": 64, \"h\": 32}" in json, json)
		assertTrue("\"size\": {\"w\": 128, \"h\": 32}" in json && "\"loop\": true" in json)
	}

	@Test fun gifsHoldEveryFrameWithTransparency() {
		val (files, report) = export(RasterTargets(FakeRenderer()).gif, "size" to "64", "physics" to "false")
		val reader = ImageIO.getImageReadersByFormatName("gif").next()
		reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(files.getValue("anim.gif")))
		assertEquals(4, reader.getNumImages(true))
		val first = reader.read(0)
		assertEquals(0, first.getRGB(40, 20) ushr 24, "background stays transparent")
		assertEquals(0xffff0000.toInt(), first.getRGB(1, 1))
		assertTrue(report.losses.any { it.feature == Feature.PHYSICS }, "physics turned off is reported")
	}

	@Test fun gifColorsComeFromTheFramesThemselves() {
		// A navy uniform, a skin tone and a lilac: a fixed color cube moved each by tens of levels.
		val colors = intArrayOf(0xff1e2350.toInt(), 0xfffadcc8.toInt(), 0xffbeb4d2.toInt())
		val frame = RasterImage(30, 10, IntArray(300) { colors[(it % 30) / 10] })
		val palette = GifPalette.of(listOf(frame))
		val indices = palette.map(frame, dither = false)
		for (i in frame.argb.indices) {
			val want = frame.argb[i]
			val k = indices[i].toInt() and 255
			assertNotEquals(0, k, "opaque pixels never take the transparent index")
			for ((got, shift) in listOf(palette.reds[k] to 16, palette.greens[k] to 8, palette.blues[k] to 0))
				assertEquals((want shr shift) and 255, got, "pixel $i is ${Integer.toHexString(want)}")
		}
	}

	@Test fun ditheringIsASetting() {
		val target = RasterTargets(FakeRenderer()).gif
		assertTrue(target.settings.any { it.key == "dither" })
		val (files, _) = export(target, "size" to "64", "physics" to "false", "dither" to "false")
		assertTrue(files.getValue("anim.gif").isNotEmpty())
	}

	@Test fun anOversizedSheetIsRefusedBeforeAnyFrameRenders() {
		val renderer = FakeRenderer()
		val failure = assertFailsWith<IllegalArgumentException> { export(RasterTargets(renderer).sheet, "size" to "8192", "fps" to "120") }
		assertTrue("exceeds" in failure.message.orEmpty(), failure.message)
		assertEquals(emptyList(), renderer.calls)
	}

	@Test fun aClipIsFoundByItsNameAndAnUnknownOneListsTheClips() {
		val renderer = FakeRenderer()
		export(RasterTargets(renderer).sequence, "size" to "64", "clip" to "Move")
		assertEquals(4, renderer.calls.size)
		val failure = assertFailsWith<IllegalArgumentException> { export(RasterTargets(FakeRenderer()).sequence, "clip" to "Nod") }
		assertTrue("move (Move)" in failure.message.orEmpty(), failure.message)
	}

	@Test fun invalidSettingsAreRejected() {
		val target = RasterTargets(FakeRenderer()).sequence
		for (bad in listOf("clip" to "missing", "fps" to "0", "size" to "8")) assertFailsWith<IllegalArgumentException> { export(target, bad) }
	}

	@Test fun aRigWithoutClipsRendersItsRestPose() {
		val renderer = FakeRenderer()
		val files = LinkedHashMap<String, ByteArray>()
		Compiler.export(RasterTargets(renderer).sequence, rig.copy(clips = emptyList()), ExportOptions("still")) { path, bytes -> files[path] = bytes }
		assertEquals(listOf("still_0000.png"), files.keys.toList())
		assertEquals(emptyMap(), renderer.calls.single().first)
	}

	private fun ffmpegAvailable() = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

	/** [file] decoded by ffmpeg into raw RGBA frames. */
	private fun decode(file: java.io.File, width: Int, height: Int): List<IntArray> {
		// VP9 keeps alpha in a side channel only libvpx decodes.
		val decoder = if (file.name.endsWith(".webm")) listOf("-c:v", "libvpx-vp9") else emptyList()
		val process = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-loglevel", "error") + decoder + listOf("-i", file.path, "-f", "rawvideo", "-pix_fmt", "rgba", "-")).start()
		val bytes = process.inputStream.readBytes(); check(process.waitFor() == 0)
		val size = width * height * 4
		return (0 until bytes.size / size).map { f -> IntArray(width * height) { i ->
			val o = f * size + i * 4
			((bytes[o + 3].toInt() and 255) shl 24) or ((bytes[o].toInt() and 255) shl 16) or ((bytes[o + 1].toInt() and 255) shl 8) or (bytes[o + 2].toInt() and 255)
		} }
	}

	@Test fun videosEncodeEveryFrameAndKeepAlphaWhereTheFormatHasIt() {
		org.junit.jupiter.api.Assumptions.assumeTrue(ffmpegAvailable(), "ffmpeg is not installed")
		val targets = RasterTargets(FakeRenderer())
		for (format in RasterTargets.Video.entries) {
			val target = targets.all.single { it.id == format.id }
			val (files, report) = export(target, "size" to "64", "physics" to "false")
			val file = kotlin.io.path.createTempFile(suffix = "." + format.extension).toFile()
			try {
				file.writeBytes(files.getValue("anim.${format.extension}"))
				if (format == RasterTargets.Video.WEBP) {
					// ffmpeg encodes animated WebP but cannot decode it: check the RIFF chunks instead.
					val bytes = file.readBytes()
					assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WEBP", String(bytes, 8, 4))
					assertEquals(4, Regex("ANMF").findAll(String(bytes, Charsets.ISO_8859_1)).count())
					assertTrue("ANIM" in String(bytes, Charsets.ISO_8859_1))
					continue
				}
				val frames = decode(file, 64, 32)
				assertEquals(4, frames.size, "${format.id} frames")
				// The red square of the third frame sits at x = 6; lossy codecs land near red.
				val red = frames[2][1 * 64 + 7]
				assertTrue(((red shr 16) and 255) > 200 && ((red shr 8) and 255) < 60, "${format.id} square: ${Integer.toHexString(red)}")
				val corner = frames[0][31 * 64 + 63]
				if (format.alpha) assertTrue((corner ushr 24) < 16, "${format.id} keeps transparency")
				else { assertEquals(255, corner ushr 24); assertTrue(report.losses.any { it.note.contains("no alpha") }) }
			} finally { file.delete() }
		}
	}

	@Test fun aPackagedAppEncodesWithTheFfmpegAmongItsResources() {
		org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("PSD2LIVE_FFMPEG").isNullOrBlank(), "PSD2LIVE_FFMPEG takes precedence")
		val resources = kotlin.io.path.createTempDirectory("psd2live-raster-resources-").toFile()
		val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "ffmpeg.exe" else "ffmpeg"
		// Not a program: starting it fails, and the failure names the file that was chosen.
		val bundled = resources.resolve("ffmpeg/$name").apply { parentFile.mkdirs(); writeText("not ffmpeg") }
		val previous = System.setProperty("compose.application.resources.dir", resources.path)
		try {
			val failure = assertFailsWith<IllegalStateException> { export(RasterTargets(FakeRenderer()).all.single { it.id == "mp4" }, "size" to "16", "physics" to "false") }
			assertTrue(bundled.path in failure.message.orEmpty(), failure.message)
		} finally {
			if (previous == null) System.clearProperty("compose.application.resources.dir") else System.setProperty("compose.application.resources.dir", previous)
			resources.deleteRecursively()
		}
	}
}
