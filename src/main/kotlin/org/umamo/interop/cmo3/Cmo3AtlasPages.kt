package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.interop.AtlasPageSet

/**
 * Extracts the atlas page(s) a CMO3 model's art meshes sample.  Walks each `CArtMeshSource`'s
 * `GTexture2D.srcImageResource`, collects each distinct page's embedded PNG once, and keys it by the
 * drawable id (matching `DrawableId` from [Cmo3Import]).
 *
 * The pixel lookup is injected rather than taken from a `Cmo3Model`, which is what keeps this in
 * commonMain: the CMO3 graph node types are all commonMain, and only the JDOM-built container wrapper
 * that owns the CAFF archive is JVM-bound.  The caller passes `Cmo3Model::extractLayerPng` (see the
 * document loader); a test passes a map lookup.
 *
 * A resource carrying no bytes at all is dropped here, leaving its drawables unmapped; one carrying
 * bytes that will not decode is the decoder's call, and for a CMO3 that means dropping the page rather
 * than refusing the document - its pixels are embedded in the model file, so one corrupt resource
 * inside an otherwise editable rig must not cost the rigger everything else.
 *
 * @param CModelSource modelSource The CMO3's root model source.
 * @param Function     readPng     Yields an image resource's embedded PNG bytes, or null when it has none.
 * @return AtlasPageSet The encoded pages + per-drawable index.
 */
public fun cmo3AtlasPages(modelSource: CModelSource, readPng: (CImageResource) -> ByteArray?): AtlasPageSet {
	val sources = (modelSource.drawableSourceSet as? CDrawableSourceSet)?._sources
	val artMeshes = Cmo3Import.elementsOf(sources).filterIsInstance<CArtMeshSource>()

	// Resolved once per DISTINCT resource, not per drawable: art meshes overwhelmingly share one atlas
	// page, so a per-drawable lookup would repeat the archive fetch hundreds of times on a real model.
	// A null value is a remembered failure (no bytes), which is why this is not a getOrPut - getOrPut
	// treats a stored null as absent and would recompute it every time.
	val resolvedPageIndexByResource = HashMap<CImageResource, Int?>()
	val pageBytes = ArrayList<ByteArray>()
	val atlasIndexByDrawableId = HashMap<String, Int>()
	var premultiplied = false

	for (mesh in artMeshes) {
		val drawableId = Cmo3Import.idStrOf(mesh.id) ?: continue
		val texture = mesh.texture as? GTexture2D ?: continue
		// CMO3: GTexture2D.isPremultiplied is an editor texture-upload-convention flag (serialized true on
		// nearly every model), NOT a claim the embedded PNG is premultiplied - PngCodec decodes straight
		// alpha and the shader premultiplies.  Aggregated here but deliberately unconsumed downstream; do
		// not drive rendering off it (that would over-darken the corpus).  The version-dependent
		// premultiplied-vs-straight COMPOSITING axis rides BlendMode.isLegacy, not this flag.  See
		// PuppetTextures.premultipliedAlpha and docs/format/CMO3.md, "Premultiplied vs straight alpha".
		premultiplied = premultiplied || texture.isPremultiplied
		val resource = texture.srcImageResource as? CImageResource ?: continue
		val pageIndex =
			if (resolvedPageIndexByResource.containsKey(resource)) {
				resolvedPageIndexByResource[resource]
			} else {
				val resolved =
					readPng(resource)?.let { png ->
						pageBytes.add(png)
						pageBytes.size - 1
					}
				resolvedPageIndexByResource[resource] = resolved
				resolved
			}
		if (pageIndex != null) {
			atlasIndexByDrawableId[drawableId] = pageIndex
		}
	}
	return AtlasPageSet(pageBytes, atlasIndexByDrawableId, premultiplied)
}