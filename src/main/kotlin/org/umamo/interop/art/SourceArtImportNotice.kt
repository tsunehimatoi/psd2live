package org.umamo.interop.art

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.BlendMode

/**
 * One thing an artwork import could not carry onto the model as drawn, named so the rigger can act
 * on it.  Anything unrepresentable surfaces as a notice, never as a silent drop - the same rule the
 * export notices follow.  Presentation-free: the shell logs each one and raises a single status
 * notice.
 */
sealed interface SourceArtImportNotice {
	/** A text, vector, adjustment, or fill layer: no stored pixels, so no drawable. */
	data class NonRasterLayer(val layerName: String, val kind: SourceLayerKind) : SourceArtImportNotice

	/** A raster layer with no pixel over the alpha threshold: nothing to mesh, so no drawable. */
	data class EmptyLayer(val layerName: String) : SourceArtImportNotice

	/** A layer blend the model has no equivalent for; the drawable falls back to Normal. */
	data class BlendUnsupported(val layerName: String, val blend: LayerBlend) : SourceArtImportNotice

	/** A layer blend carried onto its nearest model equivalent, which is not the same formula. */
	data class BlendApproximated(val layerName: String, val blend: LayerBlend, val mappedTo: BlendMode) : SourceArtImportNotice

	/** A clipping layer with no non-clipped layer below it in its folder to clip to; imported unclipped. */
	data class ClipBaseMissing(val layerName: String) : SourceArtImportNotice

	/** A layer that writes only some of its channels; the model has no per-channel masking, so all four write. */
	data class ChannelMaskDropped(val layerName: String) : SourceArtImportNotice

	/** A folder blend the model has no equivalent for; the part composites with Normal. */
	data class FolderBlendUnsupported(val groupPath: String, val blend: LayerBlend) : SourceArtImportNotice

	/** A folder that is itself a clipping layer; the part imports unclipped. */
	data class FolderClipDropped(val groupPath: String) : SourceArtImportNotice

	/** Art whose trimmed footprint does not fit the largest page the import packs against; left unpacked. */
	data class LayerLargerThanPage(val layerName: String) : SourceArtImportNotice

	/** Art the packer could not place for any other reason; left unpacked, with the packer's reason. */
	data class LayerNotPacked(val layerName: String, val reason: String) : SourceArtImportNotice
}