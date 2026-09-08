package org.umamo.render

import org.umamo.format.moc3.MocDocument

/**
 * Builds the [PuppetTextures] for a MOC3-imported puppet from its sidecar atlas pages.  A `.moc3`
 * embeds no pixels: the pages are external PNG files listed in model3.json's `FileReferences.Textures`
 * (in order - a drawable's `textureIndex` indexes that list), so the caller reads those files and
 * hands their bytes here in the same order.
 *
 * Unlike the CMO3 adapter (which skips an undecodable page), a broken page fails the whole build so
 * the loader surfaces it as an import error rather than silently rendering fallback colors - see
 * [UndecodablePagePolicy.Fail].
 *
 * @param MocDocument     mocDocument The decoded moc, whose art meshes carry the per-mesh textureIndex.
 * @param List<ByteArray> pageBytes   The PNG bytes per manifest texture, in Textures order.
 * @return PuppetTextures? The decoded pages + index, or null when any page fails to decode or any
 *                         index falls outside the page list.
 */
fun moc3PuppetTextures(mocDocument: MocDocument, pageBytes: List<ByteArray>): PuppetTextures? =
	buildPuppetTextures(
		pageBytes,
		// MOC3: ArtMesh.textureIndex, decoded from section 41 (Section.ARTMESH_TEXTURE) - the mesh's page
		// slot, indexing model3's Textures list.
		mocDocument.artMeshes.associate { artMesh -> artMesh.id to artMesh.textureIndex },
		// Cubism texture files are straight-alpha PNGs (premultiplication is a runtime load option, not a
		// property of the files), matching the straight-alpha stream PngCodec yields.  The premultiplied-
		// vs-straight COMPOSITING distinction (legacy vs 5.3+ blend modes) rides BlendMode.isLegacy, not
		// this flag; see PuppetTextures.premultipliedAlpha and docs/format/CMO3.md, "Premultiplied vs
		// straight alpha".
		premultipliedAlpha = false,
		undecodablePage = UndecodablePagePolicy.Fail,
	)