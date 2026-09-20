package org.umamo.interop.cmo3

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.png.PngCodec
import org.umamo.runtime.model.ArtSourceId

/*
 * A CMO3's decomposed artwork as source art: the official editor stores every layer of an imported
 * file as its own PNG at its canvas bounds (the CLayeredImage tree), so a CMO3-origin document can
 * hand the reload planner a layer's pixels without the original file - what a relink reads when the
 * file the document recorded is not on this machine.  It is the art as the editor imported it, not
 * as the artist last saved it, so a reload (which asks "what changed") reads the file, never this.
 */

/**
 * One decomposed layer as a source layer.  The raster decodes on first use - a relink needs one
 * layer's pixels, not the whole file's.
 *
 * @property WalkedCmo3Layer walked      The layer as the walk found it (its key, folder, and order).
 * @property Function        extractPng  The CMO3's PNG lookup for a resource.
 */
private class Cmo3SourceLayer(
	private val walked: WalkedCmo3Layer,
	private val extractPng: (CImageResource) -> ByteArray?,
) : SourceLayer {
	// CMO3: CLayer field imageResource - the layer's own PNG, at the size of boundsOnImageDoc.
	private val resource: CImageResource? = walked.layer.imageResource as? CImageResource

	override val id: LayerId = LayerId(walked.key)
	override val name: String = walked.layer.name.orEmpty()
	override val groupPath: String = walked.groupPath
	override val kind: SourceLayerKind = if (resource?.imageFileBuf != null) SourceLayerKind.Raster else SourceLayerKind.Unknown
	override val visible: Boolean = walked.layer.isVisible
	override val idIsStable: Boolean = walked.stable
	override val order: Int = walked.order
	override val bounds: LayerBounds =
		(walked.layer.boundsOnImageDoc as? CRect).let { rect ->
			// CMO3: CLayer field boundsOnImageDoc (x / y / width / height on the source document).
			LayerBounds(rect?.x ?: 0, rect?.y ?: 0, rect?.width ?: (resource?.width ?: 0), rect?.height ?: (resource?.height ?: 0))
		}

	// CMO3: ACLayerEntry fields opacity255 / isClipping / blend.
	override val opacity: Float = walked.layer.opacity255 / 255f
	override val clipped: Boolean = walked.layer.isClipping
	override val blend: LayerBlend = layerBlendOf(walked.layer.blend)
	override val raster: LayerRaster by lazy {
		val decoded = resource?.let(extractPng)?.let { png -> runCatching { PngCodec.read(png) }.getOrNull() }
		if (decoded != null) {
			LayerRaster(decoded.width, decoded.height, decoded.rgba)
		} else {
			// A layer whose PNG is missing or undecodable reads as fully transparent at its bounds, which
			// the planner treats as a layer with no art to give.
			LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4))
		}
	}
}

/**
 * The decomposed file as a source document.
 *
 * @property List layers   The image layers, in the stored order.
 * @property Int  widthPx  The source document's width.
 * @property Int  heightPx The source document's height.
 */
private class Cmo3LayeredArt(
	override val layers: List<SourceLayer>,
	override val widthPx: Int,
	override val heightPx: Int,
) : SourceArt

/**
 * The blend a decomposed layer composites with, as the readers name it.
 *
 * @param Any? blend The layer's ACBlend node.
 * @return LayerBlend The blend; Normal for a node the CMO3 marks unsupported or one this does not name.
 */
private fun layerBlendOf(blend: Any?): LayerBlend =
	// CMO3: ACLayerEntry field blend - one of the CBlend_* classes; the editor keeps only the modes it
	// composites itself and marks the rest CBlend_NotSupported.
	when (blend?.let { node -> node::class.simpleName }) {
		"CBlend_Multiply" -> LayerBlend.Multiply
		"CBlend_LinearDodge" -> LayerBlend.Add
		"CBlend_Color" -> LayerBlend.Color
		else -> LayerBlend.Normal
	}

/**
 * The artwork file [sourceId] names, as the CMO3 decomposed it at import: one source layer per image
 * layer of the matching layered image, keyed exactly as the atlas ingest keys the document's
 * bindings, with pixels read from the layer's own PNG.  Null when the document lists no such image.
 *
 * @param CModelSource modelSource The parsed CMO3 root.
 * @param ArtSourceId  sourceId    The source to read - the layered image's guid.
 * @param Function     extractPng  The CMO3's PNG lookup for a resource (the codec's extractLayerPng).
 * @return SourceArt? The decomposed file, or null.
 */
fun cmo3SourceArtOf(modelSource: CModelSource, sourceId: ArtSourceId, extractPng: (CImageResource) -> ByteArray?): SourceArt? {
	// CMO3: CModelSource field textureManager -> CTextureManager field _rawImages -> LayeredImageWrapper
	// field image -> CLayeredImage fields guid / width / height.
	val textureManager = modelSource.textureManager as? CTextureManager ?: return null
	val image =
		Cmo3Import.elementsOf(textureManager._rawImages)
			.mapNotNull { wrapper -> (wrapper as? LayeredImageWrapper)?.image as? CLayeredImage }
			.firstOrNull { candidate -> Cmo3Import.uuidOf(candidate.guid) == sourceId.raw }
			?: return null
	val layers = walkLayeredImage(image).map { walked -> Cmo3SourceLayer(walked, extractPng) }
	return Cmo3LayeredArt(layers, image.width, image.height)
}