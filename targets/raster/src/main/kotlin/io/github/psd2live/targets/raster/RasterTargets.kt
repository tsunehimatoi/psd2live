package io.github.psd2live.targets.raster

import io.github.psd2live.format.compile.*
import io.github.psd2live.format.model.Clip
import io.github.psd2live.format.model.RigIR
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/**
 * Frames rendered from the rig: each target samples one clip (or the rest pose) and writes pixels only.
 *
 * Settings shared by every raster target:
 * - `clip`: the clip id to render; the first clip by default, or the rest pose when the rig has none.
 * - `fps`: frames per second; the clip's own rate by default.
 * - `size`: the output's longer side in pixels, 1024 by default.
 * - `background`: an ARGB color in hex (for example `ffffffff`); transparent by default.
 * - `physics`: whether physics moves between frames, true by default.
 */
public class RasterTargets(private val renderer: FrameRenderer) {
	public val all: List<ExportTarget> get() = listOf(sequence, sheet, gif) + Video.entries.map(::video)

	/**
	 * Video and animated image formats encoded by ffmpeg. [alpha] formats keep transparency; the others are
	 * composited over `background` (white by default).
	 */
	public enum class Video(public val id: String, public val extension: String, public val alpha: Boolean, public val label: String,
	                        internal val arguments: List<String>) {
		MP4("mp4", "mp4", false, "MP4 video (H.264)", listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
			"-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2", "-movflags", "+faststart")),
		WEBM("webm", "webm", true, "WebM video (VP9 with alpha)", listOf("-c:v", "libvpx-vp9", "-pix_fmt", "yuva420p", "-b:v", "0", "-crf", "30", "-auto-alt-ref", "0")),
		MOV("mov", "mov", true, "QuickTime (ProRes 4444 with alpha)", listOf("-c:v", "prores_ks", "-profile:v", "4444", "-pix_fmt", "yuva444p10le")),
		APNG("apng", "png", true, "Animated PNG", listOf("-c:v", "apng", "-f", "apng", "-plays", "0")),
		WEBP("webp", "webp", true, "Animated WebP", listOf("-c:v", "libwebp_anim", "-lossless", "0", "-quality", "90", "-loop", "0")),
	}

	/**
	 * The ffmpeg to encode with: the `ffmpeg` setting, then PSD2LIVE_FFMPEG, then the one a packaged app ships
	 * among its resources (`ffmpeg/`), then ffmpeg on the PATH, then on macOS the Homebrew and MacPorts one: an app
	 * opened from Finder does not see the shell's PATH.
	 */
	private fun ffmpeg(options: ExportOptions): String =
		options.setting("ffmpeg") ?: System.getenv("PSD2LIVE_FFMPEG")?.takeIf { it.isNotBlank() } ?: bundledFfmpeg()?.path
			?: macPackageFfmpeg() ?: "ffmpeg"

	private fun macPackageFfmpeg(): String? {
		if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return null
		if (onPath("ffmpeg")) return null
		return listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/local/bin/ffmpeg")
			.firstOrNull { java.io.File(it).canExecute() }
	}

	private fun onPath(command: String): Boolean = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator)
		.any { it.isNotBlank() && java.io.File(it, command).canExecute() }

	private fun bundledFfmpeg(): java.io.File? {
		val resources = System.getProperty("compose.application.resources.dir") ?: return null
		val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "ffmpeg.exe" else "ffmpeg"
		return java.io.File(resources, "ffmpeg/$name").takeIf(java.io.File::isFile)
	}

	private fun video(format: Video): ExportTarget = target(format.id, format.label, extraLoss = if (format.alpha) null else
		LossEntry("*", Feature.TEXTURE_SIZE, Handling.APPROXIMATED, note = "${format.id} has no alpha; frames are composited over the background"),
		opaqueBackground = !format.alpha, extraSettings = listOf(TargetSetting.Text("ffmpeg", "path to ffmpeg"))) { frames, options, clip, fps ->
		val bytes = encode(ffmpeg(options), format, frames, fps)
		val write: (OutputSink) -> Unit = { sink -> sink.write("${options.baseName}.${format.extension}", bytes) }
		write
	}

	/** Streams raw RGBA frames into ffmpeg and returns the encoded file. Bit-exact flags keep the output reproducible. */
	private fun encode(ffmpeg: String, format: Video, frames: List<RasterImage>, fps: Float): ByteArray {
		val output = java.nio.file.Files.createTempFile("psd2live-video-", ".${format.extension}")
		try {
			val width = frames.first().width; val height = frames.first().height
			val command = listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba",
				"-s", "${width}x$height", "-r", fps.toString(), "-i", "-") + format.arguments +
				listOf("-fflags", "+bitexact", "-flags:v", "+bitexact", "-map_metadata", "-1", output.toString())
			val process = try { ProcessBuilder(command).redirectErrorStream(true).start() }
				catch (failure: java.io.IOException) { throw IllegalStateException("ffmpeg is not available ($ffmpeg); set the ffmpeg setting or PSD2LIVE_FFMPEG", failure) }
			val log = StringBuilder()
			val reader = Thread { process.inputStream.bufferedReader().forEachLine { log.appendLine(it) } }.apply { start() }
			process.outputStream.buffered().use { input ->
				val row = ByteArray(width * height * 4)
				for (frame in frames) {
					for (i in frame.argb.indices) {
						val argb = frame.argb[i]
						row[i * 4] = (argb shr 16).toByte(); row[i * 4 + 1] = (argb shr 8).toByte(); row[i * 4 + 2] = argb.toByte(); row[i * 4 + 3] = (argb ushr 24).toByte()
					}
					input.write(row)
				}
			}
			val status = process.waitFor(); reader.join()
			check(status == 0) { "ffmpeg failed ($status): ${log.toString().trim().take(500)}" }
			return java.nio.file.Files.readAllBytes(output)
		} finally {
			java.nio.file.Files.deleteIfExists(output)
		}
	}

	/** Numbered PNG frames. */
	public val sequence: ExportTarget = target("png-sequence", "PNG image sequence") { frames, options, clip, fps ->
		{ sink: OutputSink ->
			// Encoded in parallel a batch at a time, written in order.
			for (batch in frames.indices.chunked(maxOf(1, Runtime.getRuntime().availableProcessors() * 2))) {
				val encoded = batch.parallelStream().map { png(frames[it]) }.toList()
				batch.forEachIndexed { i, index -> sink.write("${options.baseName}_${index.toString().padStart(4, '0')}.png", encoded[i]) }
			}
		}
	}

	/** One sheet of frames in a grid plus a TexturePacker "hash" JSON describing them. */
	public val sheet: ExportTarget = target("sprite-sheet", "Sprite sheet (PNG + TexturePacker JSON)",
		precheck = { count, width, height -> sheetGrid(count, width, height) }) { frames, options, clip, fps ->
		val width = frames.first().width
		val height = frames.first().height
		val (columns, _) = sheetGrid(frames.size, width, height)
		val rows = (frames.size + columns - 1) / columns
		val sheetWidth = columns * width; val sheetHeight = rows * height
		val pixels = IntArray(sheetWidth * sheetHeight)
		frames.forEachIndexed { index, frame ->
			val left = index % columns * width; val top = index / columns * height
			for (y in 0 until height) System.arraycopy(frame.argb, y * width, pixels, (top + y) * sheetWidth + left, width)
		}
		val sheetName = "${options.baseName}.png"
		val json = buildString {
			append("{\n  \"frames\": {")
			frames.indices.forEach { index ->
				if (index > 0) append(',')
				val x = index % columns * width
				val y = index / columns * height
				append("\n    \"${options.baseName}_${index.toString().padStart(4, '0')}\": {\"frame\": {\"x\": $x, \"y\": $y, \"w\": $width, \"h\": $height}, ")
				append("\"rotated\": false, \"trimmed\": false, \"spriteSourceSize\": {\"x\": 0, \"y\": 0, \"w\": $width, \"h\": $height}, ")
				append("\"sourceSize\": {\"w\": $width, \"h\": $height}, \"duration\": ${(1000f / fps).toInt()}}")
			}
			append("\n  },\n  \"meta\": {\"app\": \"psd2live\", \"version\": \"${Compiler.version}\", \"image\": \"$sheetName\", ")
			append("\"format\": \"RGBA8888\", \"size\": {\"w\": $sheetWidth, \"h\": $sheetHeight}, \"scale\": \"1\", ")
			append("\"frameRate\": $fps, \"loop\": ${clip?.loop ?: false}}\n}\n")
		}
		// One large image, encoded in parallel bands.
		val write: (OutputSink) -> Unit = { sink ->
			sink.write(sheetName, PngEncoder.encode(sheetWidth, sheetHeight, pixels)); sink.write("${options.baseName}.json", json.encodeToByteArray())
		}
		write
	}

	/** An animated GIF: 255 colors cut from the clip itself plus transparency, looping when the clip loops. */
	public val gif: ExportTarget = target("gif", "Animated GIF", extraLoss = LossEntry("*", Feature.TEXTURE_SIZE, Handling.APPROXIMATED,
		note = "GIF holds 256 colors and 1-bit transparency"), extraSettings = listOf(TargetSetting.Flag("dither", true))) { frames, options, clip, fps ->
		{ sink: OutputSink -> sink.write("${options.baseName}.gif", gif(frames, fps, clip?.loop ?: false, options.flag("dither", true))) }
	}

	/**
	 * The columns and rows a sheet of [count] frames of [width] x [height] takes, refused past [MAX_SHEET]. Known from
	 * the settings alone, so it is checked before a single frame is rendered.
	 */
	private fun sheetGrid(count: Int, width: Int, height: Int): Pair<Int, Int> {
		val columns = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
		val rows = (count + columns - 1) / columns
		require(columns.toLong() * width <= MAX_SHEET && rows.toLong() * height <= MAX_SHEET) {
			"Sprite sheet ${columns.toLong() * width}x${rows.toLong() * height} ($count frames of ${width}x$height) exceeds " +
				"$MAX_SHEET px; lower the size or the fps, or pick a shorter clip"
		}
		return columns to rows
	}

	/** The clip [wanted] names, by id or else by name; refused with the clips there are. */
	private fun clipNamed(ir: RigIR, wanted: String): Clip =
		ir.clips.firstOrNull { it.id == wanted } ?: ir.clips.firstOrNull { it.name == wanted }
			?: ir.clips.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
			?: throw IllegalArgumentException("Unknown clip: $wanted. Clips: " +
				ir.clips.joinToString { if (it.name == it.id) it.id else "${it.id} (${it.name})" }.ifEmpty { "none" })

	private fun target(
		id: String, description: String, extraLoss: LossEntry? = null, opaqueBackground: Boolean = false,
		extraSettings: List<TargetSetting> = emptyList(),
		/** Refuses settings the writer would refuse, from the frame count and size, before anything renders. */
		precheck: ((count: Int, width: Int, height: Int) -> Unit)? = null,
		writer: (List<RasterImage>, ExportOptions, Clip?, Float) -> (OutputSink) -> Unit,
	): ExportTarget = object : ExportTarget {
		override val id: String = id
		override val family: TargetFamily = TargetFamily.RASTER
		override val description: String = description
		override val capabilities: CapabilityProfile = CapabilityProfile(structure = false)
		override val settings: List<TargetSetting> = listOf(
			TargetSetting.ClipChoice("clip", rest = false), TargetSetting.Number("fps", null, 1.0, 120.0, 1.0, 0),
			TargetSetting.Number("size", 1024.0, 16.0, 8192.0, 64.0, 0), TargetSetting.Flag("physics", true),
			TargetSetting.Text("background", "AARRGGBB"),
		) + extraSettings
		override fun plan(ir: RigIR, options: ExportOptions): LoweredExport {
			val clip = options.setting("clip")?.let { clipNamed(ir, it) } ?: ir.clips.firstOrNull()
			val fps = options.float("fps", clip?.fps ?: 30f)
			require(fps.isFinite() && fps in 1f..120f) { "FPS must be within 1..120" }
			val size = options.int("size", 1024)
			require(size in 16..8192) { "Size must be within 16..8192" }
			val background = options.setting("background")?.let { java.lang.Long.parseUnsignedLong(it.removePrefix("#"), 16).toInt() }
				?: if (opaqueBackground) 0xffffffff.toInt() else 0
			val physics = options.flag("physics", true) && ir.physics.groups.isNotEmpty()
			val spec = FrameSpec.canvas(ir, size, background)
			val times = clip?.let { ClipSampler.frameTimes(it, fps) } ?: listOf(0f)
			require(times.size <= MAX_FRAMES) { "${times.size} frames exceed the limit of $MAX_FRAMES" }
			precheck?.invoke(times.size, spec.outputWidth, spec.outputHeight)
			var simulated = false
			val frames = renderer.open(ir, physics).use { session ->
				var previous = 0f
				session.renderSequence(times.map { time ->
					(clip?.let { ClipSampler.valuesAt(it, time) } ?: emptyMap()) to (time - previous).also { previous = time }
				}, spec).also { simulated = session.simulatesPhysics }
			}
			val losses = CapabilityScan.scan(ir, capabilities, options).filter { it.feature != Feature.PHYSICS || !simulated } + listOfNotNull(extraLoss)
			val write = writer(frames, options, clip, fps)
			return object : LoweredExport {
				override val losses: List<LossEntry> = losses
				override fun write(sink: OutputSink) = write(sink)
			}
		}
	}

	private fun image(frame: RasterImage) = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).also {
		it.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
	}

	private fun png(frame: RasterImage): ByteArray = ByteArrayOutputStream().also { check(ImageIO.write(image(frame), "png", it)) }.toByteArray()

	private fun gif(frames: List<RasterImage>, fps: Float, loop: Boolean, dither: Boolean): ByteArray {
		val writer = ImageIO.getImageWritersByFormatName("gif").next()
		val output = ByteArrayOutputStream()
		ImageIO.createImageOutputStream(output).use { stream ->
			writer.output = stream
			writer.prepareWriteSequence(null)
			val delay = (100f / fps).toInt().coerceAtLeast(1)
			val palette = GifPalette.of(frames)
			val palettized = frames.parallelStream().map { indexed(it, palette, dither) }.toList()
			palettized.forEachIndexed { index, indexed ->
				val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(indexed), null)
				val format = metadata.nativeMetadataFormatName
				val root = metadata.getAsTree(format) as IIOMetadataNode
				child(root, "GraphicControlExtension").apply {
					setAttribute("disposalMethod", "restoreToBackgroundColor")
					setAttribute("userInputFlag", "FALSE")
					setAttribute("transparentColorFlag", "TRUE")
					setAttribute("delayTime", delay.toString())
					setAttribute("transparentColorIndex", "0")
				}
				val model = indexed.colorModel as java.awt.image.IndexColorModel
				child(root, "LocalColorTable").apply {
					while (hasChildNodes()) removeChild(firstChild)
					setAttribute("sizeOfLocalColorTable", "256"); setAttribute("sortFlag", "FALSE")
					for (entry in 0 until 256) appendChild(IIOMetadataNode("ColorTableEntry").apply {
						setAttribute("index", entry.toString()); setAttribute("red", model.getRed(entry).toString())
						setAttribute("green", model.getGreen(entry).toString()); setAttribute("blue", model.getBlue(entry).toString())
					})
				}
				if (index == 0 && loop) {
					val extensions = child(root, "ApplicationExtensions")
					extensions.appendChild(IIOMetadataNode("ApplicationExtension").apply {
						setAttribute("applicationID", "NETSCAPE"); setAttribute("authenticationCode", "2.0")
						userObject = byteArrayOf(1, 0, 0)
					})
				}
				metadata.setFromTree(format, root)
				writer.writeToSequence(IIOImage(indexed, null, metadata), null)
			}
			writer.endWriteSequence()
		}
		writer.dispose()
		return output.toByteArray()
	}

	private fun child(root: IIOMetadataNode, name: String): IIOMetadataNode {
		for (i in 0 until root.length) if (root.item(i).nodeName == name) return root.item(i) as IIOMetadataNode
		return IIOMetadataNode(name).also(root::appendChild)
	}

	/** [frame] as a 256-color image over [palette], whose index 0 is transparent. */
	private fun indexed(frame: RasterImage, palette: GifPalette, dither: Boolean): BufferedImage {
		val model = java.awt.image.IndexColorModel(8, 256, palette.reds.map(Int::toByte).toByteArray(),
			palette.greens.map(Int::toByte).toByteArray(), palette.blues.map(Int::toByte).toByteArray(), 0)
		val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_BYTE_INDEXED, model)
		// An 8-bit indexed image keeps one byte per pixel, row by row.
		palette.map(frame, dither).copyInto((image.raster.dataBuffer as java.awt.image.DataBufferByte).data)
		return image
	}

	private companion object {
		const val MAX_SHEET = 16384L
		const val MAX_FRAMES = 3600
	}
}
