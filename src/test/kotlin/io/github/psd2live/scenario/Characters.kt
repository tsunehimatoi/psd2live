package io.github.psd2live.scenario

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import org.umamo.format.art.*
import org.umamo.format.png.PngCodec
import org.umamo.format.psd.PsdWriter
import org.umamo.format.raster.RasterImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * The synthetic characters scenarios start from. Each part is an opaque, position-coloured shape on its own layer,
 * classified by override so that generation does not depend on name heuristics; settings keep a build cheap.
 */
internal object Characters {
	/**
	 * A 420² full figure: hair front and back, face, top, skirt, both sleeves and legs. Enough for the skeleton to find
	 * a torso and limbs, for the hair generators, and for splits.
	 */
	fun figure(physics: Boolean = false, atlas: Int = 512, scale: Float = 1f): WorkspaceDocument =
		document((420 * scale).toInt(), (420 * scale).toInt(), figureParts(scale), PipelineConfig(atlasSize = atlas, generatePhysics = physics, exportMoc3 = false))

	private fun figureParts(scale: Float): List<Part> {
		fun box(x0: Int, y0: Int, x1: Int, y1: Int) = Characters.box((x0 * scale).toInt(), (y0 * scale).toInt(), (x1 * scale).toInt(), (y1 * scale).toInt())
		fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int) = Characters.ellipse((cx * scale).toInt(), (cy * scale).toInt(), (rx * scale).toInt(), (ry * scale).toInt())
		val parts = listOf(
			Part("back_hair", 0, SemanticTag.BACK_HAIR, Side.NONE, box(150, 20, 270, 200)),
			Part("leg_r", 1, SemanticTag.LEGWEAR, Side.RIGHT, box(175, 285, 205, 405)),
			Part("leg_l", 2, SemanticTag.LEGWEAR, Side.LEFT, box(215, 285, 245, 405)),
			Part("skirt", 3, SemanticTag.BOTTOMWEAR, Side.NONE, box(165, 235, 255, 300)),
			Part("top", 4, SemanticTag.TOPWEAR, Side.NONE, box(160, 115, 260, 240)),
			Part("sleeve_r", 5, SemanticTag.HANDWEAR, Side.RIGHT, box(80, 120, 162, 148)),
			Part("sleeve_l", 6, SemanticTag.HANDWEAR, Side.LEFT, box(258, 120, 340, 148)),
			Part("face", 7, SemanticTag.FACE, Side.NONE, ellipse(210, 70, 42, 46)),
			Part("front_hair", 8, SemanticTag.FRONT_HAIR, Side.NONE, box(165, 22, 255, 62)),
		)
		return parts
	}

	/**
	 * [figure] with a layer for a tail and one for wings that hold a few pixels each, the way a template with empty slots
	 * does: tagged parts with nothing to put a bone on.
	 */
	fun withPlaceholders(): WorkspaceDocument = document(420, 420, figureParts(1f) + listOf(
		Part("tail", 9, SemanticTag.TAIL, Side.NONE, box(400, 400, 406, 406)),
		Part("wings", 10, SemanticTag.WINGS, Side.NONE, box(400, 400, 406, 406)),
	), PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false))

	/**
	 * [figure] with a tail out of the hips. [curled]: a band to the right that turns up and runs back above itself,
	 * the two strands 4 px apart on the canvas and far apart along the art. Otherwise a fish tail: a stalk to the right
	 * that forks into an upper and a lower lobe with a notch between them.
	 */
	fun tailed(curled: Boolean): WorkspaceDocument = tailed(if (curled) TailShape.CURLED else TailShape.FORKED)

	enum class TailShape { CURLED, FORKED, ARC }

	/** [ARC]: a band 30 px across curving a quarter turn about (240, 170) at radius 100, down from the hips round to the right. */
	fun tailed(shape: TailShape): WorkspaceDocument {
		val curled = shape == TailShape.CURLED
		val tail: (Int, Int) -> Boolean = if (shape == TailShape.ARC) { x, y ->
			val dx = x + 0.5 - 240.0; val dy = y + 0.5 - 170.0
			dx >= 0 && dy >= 0 && kotlin.math.hypot(dx, dy) in 85.0..115.0
		} else if (curled) { x, y ->
			(x in 240 until 372 && y in 266 until 282) || (x in 356 until 372 && y in 240 until 282) || (x in 280 until 372 && y in 240 until 262)
		} else { x, y ->
			val stalk = x in 240 until 330 && y in 263 until 279
			fun lobe(dy: Int): Boolean {
				// A band from the stalk's end out to (390, 271 + dy), 14 px across.
				val ax = 322.0; val ay = 271.0; val bx = 390.0; val by = 271.0 + dy
				val t = (((x + 0.5 - ax) * (bx - ax) + (y + 0.5 - ay) * (by - ay)) / ((bx - ax) * (bx - ax) + (by - ay) * (by - ay))).coerceIn(0.0, 1.0)
				return kotlin.math.hypot(x + 0.5 - (ax + (bx - ax) * t), y + 0.5 - (ay + (by - ay) * t)) < 7.0
			}
			stalk || lobe(-48) || lobe(48)
		}
		// Behind the figure, as a tail is drawn.
		val parts = listOf(Part("tail", 0, SemanticTag.TAIL, Side.NONE, tail)) + figureParts(1f).map { Part(it.id, it.order + 1, it.tag, it.side, it.inside) }
		return document(420, 420, parts, PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false))
	}

	/** A 64² head: back hair and a face. The smallest document the hair generators and simulation presets act on. */
	fun head(physics: Boolean = true): WorkspaceDocument = document(64, 64, listOf(
		Part("hair", 0, SemanticTag.BACK_HAIR, Side.NONE, box(16, 8, 48, 56)),
		Part("face", 1, SemanticTag.FACE, Side.NONE, box(20, 12, 44, 36)),
	), PipelineConfig(atlasSize = 256, generatePhysics = physics, exportMoc3 = false))

	/** A 1200² canvas holding one 1100×1000 top: a part larger than a million square pixels (issue #56). */
	fun largePart(): WorkspaceDocument = document(1200, 1200, listOf(
		Part("top", 0, SemanticTag.TOPWEAR, Side.NONE, box(50, 100, 1150, 1100)),
	), PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false))

	/** Writes [figure]'s art as a PSD, as an artist hands it over; generation then classifies it by override. */
	fun figurePsd(directory: Path): Path {
		val source = figure().source
		return directory.resolve("figure.psd").also { Files.write(it, PsdWriter.write(source.widthPx, source.heightPx, source.layers, source.groups)) }
	}

	/** [figure] at [scale] with both eyes on one layer, two islands a mesh split offers to separate. */
	fun eyesPsd(directory: Path, scale: Float = 1f): Path {
		fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int) = Characters.ellipse((cx * scale).toInt(), (cy * scale).toInt(), (rx * scale).toInt(), (ry * scale).toInt())
		val left = ellipse(195, 68, 9, 6); val right = ellipse(225, 68, 9, 6)
		val size = (420 * scale).toInt()
		val source = document(size, size, figureParts(scale) + Part("eyes", 9, SemanticTag.EYEWHITE, Side.NONE) { x, y -> left(x, y) || right(x, y) },
			PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false)).source
		return directory.resolve("eyes.psd").also { Files.write(it, PsdWriter.write(source.widthPx, source.heightPx, source.layers, source.groups)) }
	}

	/** A face and mouth, with enough contour for the generated lip ribbons. */
	fun mouthPsd(directory: Path): Path {
		val source = document(128, 128, listOf(
			Part("face", 0, SemanticTag.FACE, Side.NONE, ellipse(64, 64, 48, 56)),
			Part("mouth", 1, SemanticTag.MOUTH, Side.NONE, ellipse(64, 92, 22, 6)),
		), PipelineConfig(atlasSize = 256, generatePhysics = false, exportMoc3 = false)).source
		return directory.resolve("mouth.psd").also { Files.write(it, PsdWriter.write(source.widthPx, source.heightPx, source.layers, source.groups)) }
	}

	/** A [size]² PNG of an opaque disc, coloured by position so that resampling and misplacement show. */
	fun disc(directory: Path, name: String = "disc.png", size: Int = 24): Path {
		val rgba = ByteArray(size * size * 4)
		val r = size / 2f
		for (y in 0 until size) for (x in 0 until size) {
			val dx = x + 0.5f - r; val dy = y + 0.5f - r
			if (dx * dx + dy * dy > r * r * 0.81f) continue
			val o = (y * size + x) * 4
			rgba[o] = (40 + 160 * x / size).toByte(); rgba[o + 1] = 60; rgba[o + 2] = (200 - 120 * y / size).toByte(); rgba[o + 3] = -1
		}
		return directory.resolve(name).also { Files.write(it, PngCodec.write(RasterImage(size, size, rgba))) }
	}

	/**
	 * [figure] with a hat placed over the hair since the rig was generated ([WorkspaceGeneration.pendingLayerIds]): the
	 * generators have not read it yet.
	 */
	fun withPlacedHat(): WorkspaceDocument {
		val figure = figure()
		val hat = Part("hat", 9, SemanticTag.HEADWEAR, Side.NONE, box(170, 2, 250, 30))
		val source = WorkspaceSourceArt(420, 420, figure.source.layers + layer(hat, 420, 420), figure.source.groups)
		return figure.copy(source = source, layerOverrides = figure.layerOverrides + ("hat" to LayerClassificationOverride(tag = hat.tag, side = hat.side)),
			layout = WorkspaceLayout(generation = WorkspaceGeneration(pendingLayerIds = setOf("hat"))))
	}

	private class Part(val id: String, val order: Int, val tag: SemanticTag, val side: Side, val inside: (Int, Int) -> Boolean)

	private fun box(x0: Int, y0: Int, x1: Int, y1: Int): (Int, Int) -> Boolean = { x, y -> x in x0 until x1 && y in y0 until y1 }

	private fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int): (Int, Int) -> Boolean = { x, y ->
		val dx = (x + 0.5f - cx) / rx; val dy = (y + 0.5f - cy) / ry
		dx * dx + dy * dy <= 1f
	}

	private fun layer(part: Part, width: Int, height: Int): WorkspaceSourceLayer {
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) for (x in 0 until width) if (part.inside(x, y)) {
			val o = (y * width + x) * 4
			rgba[o] = (40 + part.order * 20 + x % 32).toByte(); rgba[o + 1] = (60 + y % 64).toByte(); rgba[o + 2] = 120; rgba[o + 3] = -1
		}
		return WorkspaceSourceLayer(LayerId(part.id), part.id, "", SourceLayerKind.Raster, true, part.order, LayerBounds(0, 0, width, height), 1f,
			false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
	}

	private fun document(width: Int, height: Int, parts: List<Part>, config: PipelineConfig): WorkspaceDocument {
		val layers = parts.map { layer(it, width, height) }
		val overrides = parts.associate { it.id to LayerClassificationOverride(type = LayerType.PRESET, tag = it.tag, side = it.side) }
		return WorkspaceDocument(WorkspaceSourceArt(width, height, layers, emptyList()), emptyMap(), emptySet(), overrides, emptyMap(),
			RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
	}
}
