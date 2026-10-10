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
	fun figure(physics: Boolean = false, atlas: Int = 512, scale: Float = 1f): WorkspaceDocument {
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
		return document((420 * scale).toInt(), (420 * scale).toInt(), parts, PipelineConfig(atlasSize = atlas, generatePhysics = physics, exportMoc3 = false))
	}

	/** A 64² head: back hair and a face. The smallest document the hair generators and simulation presets act on. */
	fun head(physics: Boolean = true): WorkspaceDocument = document(64, 64, listOf(
		Part("hair", 0, SemanticTag.BACK_HAIR, Side.NONE, box(16, 8, 48, 56)),
		Part("face", 1, SemanticTag.FACE, Side.NONE, box(20, 12, 44, 36)),
	), PipelineConfig(atlasSize = 256, generatePhysics = physics, exportMoc3 = false))

	/** Writes [figure]'s art as a PSD, as an artist hands it over; generation then classifies it by override. */
	fun figurePsd(directory: Path): Path {
		val source = figure().source
		return directory.resolve("figure.psd").also { Files.write(it, PsdWriter.write(source.widthPx, source.heightPx, source.layers, source.groups)) }
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

	private class Part(val id: String, val order: Int, val tag: SemanticTag, val side: Side, val inside: (Int, Int) -> Boolean)

	private fun box(x0: Int, y0: Int, x1: Int, y1: Int): (Int, Int) -> Boolean = { x, y -> x in x0 until x1 && y in y0 until y1 }

	private fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int): (Int, Int) -> Boolean = { x, y ->
		val dx = (x + 0.5f - cx) / rx; val dy = (y + 0.5f - cy) / ry
		dx * dx + dy * dy <= 1f
	}

	private fun document(width: Int, height: Int, parts: List<Part>, config: PipelineConfig): WorkspaceDocument {
		val layers = parts.map { part ->
			val rgba = ByteArray(width * height * 4)
			for (y in 0 until height) for (x in 0 until width) if (part.inside(x, y)) {
				val o = (y * width + x) * 4
				rgba[o] = (40 + part.order * 20 + x % 32).toByte(); rgba[o + 1] = (60 + y % 64).toByte(); rgba[o + 2] = 120; rgba[o + 3] = -1
			}
			WorkspaceSourceLayer(LayerId(part.id), part.id, "", SourceLayerKind.Raster, true, part.order, LayerBounds(0, 0, width, height), 1f,
				false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
		}
		val overrides = parts.associate { it.id to LayerClassificationOverride(type = LayerType.PRESET, tag = it.tag, side = it.side) }
		return WorkspaceDocument(WorkspaceSourceArt(width, height, layers, emptyList()), emptyMap(), emptySet(), overrides, emptyMap(),
			RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
	}
}
