package io.github.psd2live.targets.cubism

import io.github.psd2live.format.compile.Feature
import io.github.psd2live.format.compile.Handling
import io.github.psd2live.format.compile.LossEntry
import io.github.psd2live.format.compile.RasterResample
import io.github.psd2live.format.model.Bytes
import io.github.psd2live.format.model.RigIR
import io.github.psd2live.format.model.SourceLayer
import io.github.psd2live.format.model.TextureTile
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.inversePlacementAffine

/**
 * How a cmo3 holds a tile whose source raster is not its layer's canvas rectangle at one pixel per canvas unit
 * (a dense or sparse layer).
 *
 * A Cubism Editor layer is drawn one pixel per canvas unit, and the editor rebuilds the texture atlas from the
 * layers. [CANVAS] - the default - writes such a layer resampled to its canvas rectangle and keeps the full
 * resolution only on the stored atlas page, through the tile's packing scale: the file opens as the export
 * looks, but regenerating the atlas in the editor loses the extra texels. [NATIVE] writes the layer at its own
 * resolution and maps it onto the canvas through the model image's transform; whether the editor keeps that
 * through atlas regeneration is not verified yet, so it is opt-in (`layer_art=native`).
 */
public enum class Cmo3LayerArt {
	CANVAS, NATIVE;

	public companion object {
		/** Setting key of [Cmo3Target]: `canvas` (default) or `native`. */
		public const val SETTING: String = "layer_art"

		public fun of(value: String?): Cmo3LayerArt = when (value?.lowercase()) {
			null, "", "canvas" -> CANVAS
			"native" -> NATIVE
			else -> throw IllegalArgumentException("Unknown $SETTING: $value")
		}
	}
}

internal object Cmo3LayerArtLowering {
	/** A tile with source art whose art size is not its source layer's canvas size, with that layer. */
	private class Dense(val tile: TextureTile, val layer: SourceLayer)

	private fun dense(ir: RigIR): List<Dense> {
		val art = ir.textures.tileArt.associateBy { it.tile }
		val rows = ir.authoring.sources.associate { file -> file.id to file.layers.associateBy { it.key } }
		return ir.textures.tiles.mapNotNull { tile ->
			val pixels = art[tile.id] ?: return@mapNotNull null
			val ref = tile.source ?: return@mapNotNull null
			val layer = rows[ref.source]?.get(ref.layer) ?: return@mapNotNull null
			if (layer.width <= 0 || layer.height <= 0 || (pixels.width == layer.width && pixels.height == layer.height)) null
			else Dense(tile, layer)
		}
	}

	/**
	 * [ir] with every dense tile's art resampled to its layer's canvas size, its tile that size and its packing
	 * scale grown to keep the same page texels ([Cmo3LayerArt.CANVAS]). The pages and mesh uvs do not change.
	 */
	fun canvasResolution(ir: RigIR): RigIR {
		val dense = dense(ir).associateBy { it.tile.id }
		if (dense.isEmpty()) return ir
		val tiles = ir.textures.tiles.map { tile ->
			val layer = dense[tile.id]?.layer ?: return@map tile
			tile.copy(width = layer.width, height = layer.height, placement = tile.placement?.let {
				it.copy(scaleX = it.scaleX * tile.width / layer.width, scaleY = it.scaleY * tile.height / layer.height)
			})
		}
		val art = ir.textures.tileArt.map { pixels ->
			val layer = dense[pixels.tile]?.layer ?: return@map pixels
			pixels.copy(width = layer.width, height = layer.height,
				rgba = Bytes.wrap(RasterResample.resize(pixels.rgba.shared(), pixels.width, pixels.height, layer.width, layer.height)))
		}
		return ir.copy(textures = ir.textures.copy(tiles = tiles, tileArt = art))
	}

	/**
	 * A loss entry per placed tile whose page holds more texels per canvas unit than its cmo3 layer: the editor
	 * rebuilds the atlas from those layers, so the extra resolution survives only until it does.
	 */
	fun losses(lowered: RigIR): List<LossEntry> = denser(lowered).map { (tile, density) ->
		LossEntry(tile.id, Feature.TEXTURE_SIZE, Handling.APPROXIMATED,
			note = "Atlas holds ${"%.2f".format(java.util.Locale.ROOT, density)}x the canvas resolution of '${tile.name}'; $REBUILDS")
	}

	/**
	 * [losses] as one line, for a log that would otherwise repeat the same sentence for every layer of a
	 * higher-resolution model; null when there are none.
	 */
	fun summary(lowered: RigIR): String? {
		val dense = denser(lowered)
		if (dense.size <= 1) return losses(lowered).singleOrNull()?.note
		val names = dense.map { it.first.name }
		val shown = names.take(SUMMARY_NAMES).joinToString() + if (names.size > SUMMARY_NAMES) " and ${names.size - SUMMARY_NAMES} more" else ""
		return "Atlas holds up to ${"%.2f".format(java.util.Locale.ROOT, dense.maxOf { it.second })}x the canvas resolution of " +
			"${dense.size} layers ($shown); $REBUILDS"
	}

	/** The placed tiles whose page holds more texels per canvas unit than their cmo3 layer, with that density. */
	private fun denser(lowered: RigIR): List<Pair<TextureTile, Float>> {
		val rows = lowered.authoring.sources.associate { file -> file.id to file.layers.associateBy { it.key } }
		return lowered.textures.tiles.mapNotNull { tile ->
			val placement = tile.placement ?: return@mapNotNull null
			val ref = tile.source ?: return@mapNotNull null
			val layer = rows[ref.source]?.get(ref.layer) ?: return@mapNotNull null
			if (layer.width <= 0 || layer.height <= 0) return@mapNotNull null
			val densityX = placement.scaleX * tile.width / layer.width
			val densityY = placement.scaleY * tile.height / layer.height
			if (maxOf(densityX, densityY) <= 1.0001f) null else tile to maxOf(densityX, densityY)
		}
	}

	private const val REBUILDS = "Cubism Editor rebuilds the atlas from canvas-resolution layers"
	private const val SUMMARY_NAMES = 5

	/**
	 * [Cmo3LayerArt.NATIVE]: each dense tile's model image maps its full-resolution layer onto the canvas
	 * rectangle, and its atlas entry and the meshes' atlas-region transforms are recomposed to match. Tiles are
	 * matched to model images by name, so a tile whose name is not unique is left as written.
	 */
	fun nativeResolution(ir: RigIR): (CModelSource) -> Unit {
		val dense = dense(ir)
		if (dense.isEmpty()) return {}
		val names = ir.textures.tiles.groupingBy { it.name }.eachCount()
		val fixes = dense.filter { names[it.tile.name] == 1 }.associate { it.tile.name to it }
		return { root -> fix(root, fixes) }
	}

	private fun fix(root: CModelSource, fixes: Map<String, Dense>) {
		if (fixes.isEmpty()) return
		val manager = root.textureManager as CTextureManager
		val entries = Cmo3Import.elementsOf(manager._textureAtlases).filterIsInstance<CTextureAtlas>()
			.flatMap { atlas -> Cmo3Import.elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>() }
			.associateBy { Cmo3Import.uuidOf(it.modelImageGuid) }
		val regions = HashMap<String, MutableList<CTextureInput_TextureAtlasRegion>>()
		for (mesh in Cmo3Import.elementsOf((root.drawableSourceSet as CDrawableSourceSet)._sources).filterIsInstance<CArtMeshSource>()) {
			val inputs = Cmo3Import.elementsOf(mesh._extensions).filterIsInstance<CTextureInputExtension>().firstOrNull()
				?.let { Cmo3Import.elementsOf(it._textureInputs) } ?: continue
			val guid = inputs.filterIsInstance<CTextureInput_ModelImage>().firstOrNull()?.let { Cmo3Import.uuidOf(it._modelImageGuid) } ?: continue
			regions.getOrPut(guid) { ArrayList() } += inputs.filterIsInstance<CTextureInput_TextureAtlasRegion>()
		}
		val images = Cmo3Import.elementsOf(manager._modelImageGroups).filterIsInstance<CModelImageGroup>()
			.flatMap { Cmo3Import.elementsOf(it._modelImages).filterIsInstance<CModelImage>() }
		for (image in images) {
			val dense = fixes[image.name] ?: continue
			val layer = dense.layer; val tile = dense.tile
			val material = floatArrayOf(layer.width.toFloat() / tile.width, 0f, layer.left.toFloat(),
				0f, layer.height.toFloat() / tile.height, layer.top.toFloat())
			image._materialLocalToCanvasTransform = affine(material)
			val guid = Cmo3Import.uuidOf(image.guid)
			val entry = entries[guid] ?: continue
			val transform = entry.materialLocalToAtlasTransform as GTransform2
			val position = transform.position as? GVector2; val scale = transform.scale as? GVector2
			val packing = AtlasPlacement(0, position?.x ?: 0f, position?.y ?: 0f, scale?.x ?: 1f, scale?.y ?: 1f, transform.eulerAngle)
			val half = compose(material, inversePlacementAffine(packing) ?: continue)
			entry.atlasLocalToCanvasTransform = affine(half)
			regions[guid].orEmpty().forEach { it.inputImageLocalToCanvasTransform = affine(half) }
		}
	}

	/** a ∘ b for 2x3 affines (m00, m01, m02, m10, m11, m12). */
	private fun compose(a: FloatArray, b: FloatArray) = floatArrayOf(
		a[0] * b[0] + a[1] * b[3], a[0] * b[1] + a[1] * b[4], a[0] * b[2] + a[1] * b[5] + a[2],
		a[3] * b[0] + a[4] * b[3], a[3] * b[1] + a[4] * b[4], a[3] * b[2] + a[4] * b[5] + a[5],
	)

	private fun affine(m: FloatArray) = CAffine().also { it.m00 = m[0]; it.m01 = m[1]; it.m02 = m[2]; it.m10 = m[3]; it.m11 = m[4]; it.m12 = m[5] }
}
