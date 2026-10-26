package io.github.psd2live.scenario

import io.github.psd2live.core.*
import io.github.psd2live.core.recipe.BaseChoice
import io.github.psd2live.core.recipe.RigRecipe
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
	 * [figure] with an eyebrow on each side under the bangs, the hair reaching over both: what the copies over the bangs
	 * are made for ([SeeThroughLayers]).
	 */
	fun browed(): WorkspaceDocument = document(420, 420, figureParts(1f).map {
		// Lower orders list first, in front: the bangs over the brows over the face over the back hair.
		when (it.tag) {
			SemanticTag.FACE -> Part(it.id, 11, it.tag, it.side, it.inside)
			SemanticTag.BACK_HAIR -> Part(it.id, 12, it.tag, it.side, it.inside)
			else -> it
		}
	} + listOf(
		Part("brow_r", 9, SemanticTag.EYEBROW, Side.RIGHT, box(184, 50, 204, 56)),
		Part("brow_l", 10, SemanticTag.EYEBROW, Side.LEFT, box(216, 50, 236, 56)),
	), PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false))

	/**
	 * [figure] with an eye on each side under the bangs, the hair reaching over their tops: a white, an iris taller than
	 * the white (which clips it), a lash line over the white's top and a closed-eye line, as anime art draws them - what
	 * the eyes' window in the bangs is made for ([SeeThroughLayers]).
	 */
	fun eyed(legacyEyes: Boolean = false): WorkspaceDocument = document(420, 420, figureParts(1f).map {
		// Lower orders list first, in front: the bangs over the lashes, closed lines, irides and whites, over the face.
		when (it.tag) {
			SemanticTag.FACE -> Part(it.id, 17, it.tag, it.side, it.inside)
			SemanticTag.BACK_HAIR -> Part(it.id, 18, it.tag, it.side, it.inside)
			else -> it
		}
	} + listOf(
		Part("lash_r", 9, SemanticTag.EYELASH, Side.RIGHT, box(182, 58, 206, 61)),
		Part("lash_l", 10, SemanticTag.EYELASH, Side.LEFT, box(214, 58, 238, 61)),
		Part("close_r", 11, SemanticTag.EYE_CLOSE, Side.RIGHT, box(184, 66, 204, 68)),
		Part("close_l", 12, SemanticTag.EYE_CLOSE, Side.LEFT, box(216, 66, 236, 68)),
		Part("iris_r", 13, SemanticTag.IRIDES, Side.RIGHT, box(189, 56, 199, 76)),
		Part("iris_l", 14, SemanticTag.IRIDES, Side.LEFT, box(221, 56, 231, 76)),
		Part("white_r", 15, SemanticTag.EYEWHITE, Side.RIGHT, box(184, 59, 204, 72)),
		Part("white_l", 16, SemanticTag.EYEWHITE, Side.LEFT, box(216, 59, 236, 72)),
	), PipelineConfig(atlasSize = 512, generatePhysics = false, exportMoc3 = false, recipe = if (!legacyEyes) RigRecipe.Modern
		else RigRecipe(true, bases = mapOf("eye" to BaseChoice("eye/legacy")))))

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

	/** Writes [head]'s art as a PSD: the cheapest import that still generates a head turn. */
	fun headPsd(directory: Path): Path {
		val source = head(physics = false).source
		return directory.resolve("head.psd").also { Files.write(it, PsdWriter.write(source.widthPx, source.heightPx, source.layers, source.groups)) }
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
