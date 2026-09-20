package org.umamo.interop.moc3.import

import org.umamo.format.moc3.MocDocument
import org.umamo.interop.AtlasPageSet

/**
 * Collects a MOC3-imported puppet's atlas pages from its sidecar page bytes.
 *
 * A `.moc3` embeds no pixels: the pages are external PNG files listed in model3.json's
 * `FileReferences.Textures` (in order - a drawable's `textureIndex` indexes that list), so the caller
 * reads those files and hands their bytes here in the same order.  All this adds is the per-drawable
 * index; the page list passes straight through.
 *
 * Unlike a CMO3, a page that will not decode should fail the whole import rather than be dropped - the
 * pages are sibling files named by the manifest, so one that will not decode means the family on disk
 * is incomplete or mismatched.  That is the decoder's policy and the loader states it there.
 *
 * @param MocDocument     mocDocument The decoded moc, whose art meshes carry the per-mesh textureIndex.
 * @param List<ByteArray> pageBytes   The PNG bytes per manifest texture, in Textures order.
 * @return AtlasPageSet The pages + per-drawable index.
 */
public fun moc3AtlasPages(mocDocument: MocDocument, pageBytes: List<ByteArray>): AtlasPageSet =
	AtlasPageSet(
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
	)