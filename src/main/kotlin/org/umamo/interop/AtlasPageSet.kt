package org.umamo.interop

/**
 * A document's atlas pages as its source format carries them: the encoded page bytes plus which page
 * each drawable samples.
 *
 * The format-neutral half of texture ingest.  Every container answers "which pages are there, and who
 * samples which" its own way - a CMO3 walks its drawable sources to embedded image resources, a MOC3
 * reads a per-mesh index into the manifest's texture list - but the answer is the same shape, and
 * decoding it is not a format concern.  So the walk lives here with the rest of the format ingest and
 * the decode stays in the renderer, which is what keeps container knowledge out of `:render`.
 *
 * Pages are referenced by position: a drawable's value in [atlasIndexByDrawableId] indexes [pageBytes].
 *
 * @property List<ByteArray>  pageBytes              The encoded pages, in the source's own page order.
 * @property Map<String, Int> atlasIndexByDrawableId Raw drawable id to its page's index in [pageBytes].
 * @property Boolean          premultipliedAlpha     The source's texture-upload-convention flag, carried
 *   through for the renderer's own record; it is not a claim about the encoded bytes, which are always
 *   straight alpha.  Only CMO3 sets it.
 */
public class AtlasPageSet(
	public val pageBytes: List<ByteArray>,
	public val atlasIndexByDrawableId: Map<String, Int>,
	public val premultipliedAlpha: Boolean = false,
) {
	public companion object {
		/** No pages and nothing bound - what a document with no atlas ingests to. */
		public val EMPTY: AtlasPageSet = AtlasPageSet(emptyList(), emptyMap())
	}
}