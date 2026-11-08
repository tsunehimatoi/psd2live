package io.github.psd2live.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.github.psd2live.application.WorkspaceViewRenderer
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewFrame
import io.github.psd2live.project.WorkspaceViewOutputSpec
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.ToolColors
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.psd.PsdReader
import org.umamo.format.psd.PsdWriter
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

/*
 * Shared plumbing for the development tools in this package. They are tests only so they can reach the
 * pipeline's internals; each runs only with PSD2LIVE_TOOLS=1 and is skipped otherwise, so `./gradlew
 * test` never runs them. See docs/zh/guide/DEVELOPMENT.md.
 */

/** Skips the calling tool unless PSD2LIVE_TOOLS=1. */
internal fun requireTools() {
	assumeTrue(System.getenv("PSD2LIVE_TOOLS") == "1", "Set PSD2LIVE_TOOLS=1 to run the development tools")
}

internal fun legacyTemplateConsumers(): Set<String> = RigBuildProfile.snapshot().keys
	.filter { it.startsWith("modifier template consumer:") }
	.mapTo(linkedSetOf()) { it.substringBefore(" -> ").substringAfterLast(": ") }

/** An environment setting of a tool, or [default]. */
internal fun setting(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

/**
 * The sample PSD2LIVE_SAMPLE names: a bundled example (tml, ds) or a path to a PSD. Its name labels
 * the outputs.
 */
internal class Sample(val name: String, val path: Path) {
	/** This sample at [path] instead, under the same name. */
	fun at(path: Path) = Sample(name, path)

	companion object {
		fun fromEnvironment(): Sample {
			val sample = setting("PSD2LIVE_SAMPLE", "tml")
			val file = File(sample)
			return if (file.isFile) Sample(file.nameWithoutExtension, file.toPath())
			else Sample(sample, Path.of("examples/$sample/psd-input/$sample.psd"))
		}
	}
}

/** [source] with every layer and the canvas [factor] times larger by pixel repetition; a layer's pixels are scaled when first read. */
internal fun upscaled(source: SourceArt, factor: Int): SourceArt = if (factor == 1) source else object : SourceArt {
	override val warnings: List<String> = source.warnings
	override val groups = source.groups
	override val widthPx: Int = source.widthPx * factor
	override val heightPx: Int = source.heightPx * factor
	override val layers: List<SourceLayer> = source.layers.map { layer ->
		object : SourceLayer by layer {
			override val bounds = layer.bounds.let { LayerBounds(it.left * factor, it.top * factor, it.width * factor, it.height * factor) }
			override val raster: LayerRaster by lazy {
				val r = layer.raster
				val w = r.width * factor; val h = r.height * factor
				val rgba = ByteArray(w * h * 4)
				for (y in 0 until h) for (x in 0 until w) System.arraycopy(r.rgba, ((y / factor) * r.width + x / factor) * 4, rgba, (y * w + x) * 4, 4)
				LayerRaster(w, h, rgba)
			}
		}
	}
}

/** [sample]'s PSD [upscaled] [factor] times, written to [out] once. */
internal fun scaledSample(sample: Sample, factor: Int, out: File): File {
	val file = File(out, "${sample.name}-x$factor.psd")
	if (file.isFile) return file
	val art = upscaled(PsdReader.read(Files.readAllBytes(sample.path)), factor)
	val layers = art.layers.map { l ->
		WorkspaceSourceLayer(l.id, l.name, l.groupPath, l.kind, l.visible, l.order, l.bounds, l.opacity, l.clipped, l.blend,
			l.channelMask, l.raster, null, null, false)
	}
	file.writeBytes(PsdWriter.write(WorkspaceSourceArt(art.widthPx, art.heightPx, layers, art.groups)))
	return file
}

/** [sample] built plain, and [spec] (or its auto skeleton) built on it. */
internal class Built(val plain: RigPreviewModel, val spec: SkeletonSpec, val skeletal: RigPreviewModel)

internal fun build(sample: Sample, spec: (RigPreviewModel) -> SkeletonSpec = { SkeletonAutoBuilder.build(it.analysis, it.rig) }): Built {
	val plain = PSD2LivePipeline().buildPreview(sample.path)
	val skeleton = spec(plain)
	val skeletal = PSD2LivePipeline().buildPreview(plain.analysis, plain.config.copy(rigEdits = plain.config.rigEdits.copy(skeleton = skeleton)))
	return Built(plain, skeleton, skeletal)
}

/** Renders [preview] posed at parameter values, over [rect] (the whole canvas by default), [size] pixels across. */
internal class Renderer(private val preview: RigPreviewModel, private val size: Int) {
	private val layers = preview.rig.layerIdByDrawableId.values.toSet()
	val canvas = Bounds(0f, 0f, preview.analysis.source.widthPx.toFloat(), preview.analysis.source.heightPx.toFloat())

	fun render(values: Map<String, Float>, rect: Bounds = canvas): BufferedImage {
		val view = WorkspaceViewRenderer.modelComposite(preview, "r", values, layers, emptySet(),
			WorkspaceViewFrame.CanvasRect(rect), WorkspaceViewBackground.CHECKERBOARD, WorkspaceViewOutputSpec(size))
		return ImageIO.read(view.png.inputStream())
	}
}

/** The directory build/tools/[name], created. */
internal fun output(name: String): File = File("build/tools/$name").apply { mkdirs() }

/** Labelled [frames] tiled [columns] across into [file]. */
internal fun sheet(frames: List<Pair<String, BufferedImage>>, file: File, columns: Int = 5) {
	val tw = frames.first().second.width
	val th = frames.first().second.height
	val across = minOf(columns, frames.size)
	val rows = (frames.size + across - 1) / across
	val image = BufferedImage(across * tw, rows * (th + LABEL), BufferedImage.TYPE_INT_RGB)
	val g = image.createGraphics()
	g.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
	frames.forEachIndexed { i, (label, frame) ->
		val x = i % across * tw
		val y = i / across * (th + LABEL)
		g.color = Color.DARK_GRAY
		g.fillRect(x, y, tw, LABEL)
		g.color = Color.WHITE
		g.drawString(label, x + 4, y + 15)
		g.drawImage(frame, x, y + LABEL, null)
	}
	g.dispose()
	ImageIO.write(image, "png", file)
	println("wrote $file")
}

private const val LABEL = 20

/** Milliseconds since [start], a [System.nanoTime] reading. */
internal fun since(start: Long): Double = (System.nanoTime() - start) / 1e6

/** [block]'s result and its wall time in milliseconds. */
internal inline fun <T> timed(block: () -> T): Pair<T, Double> {
	val start = System.nanoTime()
	return block() to since(start)
}

/** The mean milliseconds of [runs] calls of [block], after [warmups] calls that are not timed. */
internal inline fun mean(runs: Int = 10, warmups: Int = 1, block: () -> Unit): Double {
	repeat(warmups) { block() }
	val start = System.nanoTime()
	repeat(runs) { block() }
	return since(start) / runs
}

/** The SHA-256 of [bytes] in hex. */
internal fun sha256(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/**
 * What a cmo3 holds, comparable across exports: its editor GUIDs are random, so the SHA-256 of its read-back rig
 * lowered to moc3, and its physics group count. `error:<type>` when it does not read back.
 */
internal fun cmo3Readback(bytes: ByteArray): String = runCatching {
	val source = org.umamo.format.cmo3.Cmo3.read(bytes).root as org.umamo.format.cmo3.model.custom.CModelSource
	val puppet = org.umamo.interop.cmo3.Cmo3Import.fromModelSource(source)
	val bundle = org.umamo.interop.moc3.Moc3Sidecars.bundle(puppet, "readback", pages = emptyList())
	val physics = (source.physicsSettingsSourceSet as? org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet)
		?._sourceCubismPhysics.let { (it as? Iterable<*>)?.count() ?: 0 }
	sha256(bundle.files.single { it.name.endsWith(".moc3") }.bytes) + " physics=$physics"
}.getOrElse { "error:${it.javaClass.simpleName}" }

/**
 * Runs [block] with stored authored rigs off (`-Dpsd2live.materializedRigs=false`, see MaterializedRigStore): builds
 * generate the base and replay the journal, saves write no rig/revisions/. The property is set back afterwards.
 */
internal inline fun <T> withoutMaterializedRigs(block: () -> T): T {
	val previous = System.getProperty("psd2live.materializedRigs")
	System.setProperty("psd2live.materializedRigs", "false")
	try {
		return block()
	} finally {
		if (previous == null) System.clearProperty("psd2live.materializedRigs") else System.setProperty("psd2live.materializedRigs", previous)
	}
}

/** Runs [block] in [language] (or the current one) and sets the app's language back afterwards, unsaved either way. */
internal inline fun <T> keepingLanguage(language: AppLanguage? = null, block: () -> T): T {
	val previous = I18n.currentLanguage
	language?.let { I18n.setLanguage(it, persist = false) }
	try {
		return block()
	} finally {
		I18n.setLanguage(previous, persist = false)
	}
}

/**
 * Renders [scene] into the PNG [file] and closes it: [frames] frames 16 ms apart from [startNanos], each followed by
 * [settleMillis] of waiting for work done off the UI thread (images made in the background), then the frame written.
 */
internal fun writePng(scene: ImageComposeScene, file: File, frames: Int = 1, settleMillis: Long = 0, startNanos: Long = 0) {
	try {
		repeat(frames) { frame ->
			scene.render(startNanos + frame * 16_000_000L).close()
			if (settleMillis > 0) Thread.sleep(settleMillis)
		}
		val rendered = scene.render(startNanos + frames * 16_000_000L)
		try { file.writeBytes(requireNotNull(rendered.encodeToData()).bytes) } finally { rendered.close() }
	} finally { scene.close() }
}

/** [content] in the app's theme ([colors], [fontScale]) on a [width] x [height] scene at [density], into the PNG [file] (see [writePng]). */
internal fun renderPng(file: File, width: Int, height: Int, colors: ToolColors = ToolColors.Dark, density: Float = 1f, fontScale: Float = 1f,
	frames: Int = 1, settleMillis: Long = 0, content: @Composable () -> Unit) =
	writePng(ImageComposeScene(width, height, density = Density(density)) {
		CompactToolTheme(colors = colors, fontScale = fontScale) { content() }
	}, file, frames, settleMillis)
