package org.umamo.render

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.PuppetModel

/**
 * The texture data backing a puppet preview: the distinct atlas page(s) the art meshes sample, plus a
 * per-drawable index into them.  Art meshes typically share one atlas page in `TEXTURE_ATLAS` mode, but
 * this structure generalises to multiple pages.
 *
 * For an IMPORTED document [atlases] is ordered by the renderer's own page walk and shares no
 * numbering with the model's `PuppetAtlas.pages`.  A page set Umamo generates itself (a repack's,
 * built by `deriveAtlasTextures`) is index-parallel to `PuppetAtlas.pages` instead, so for generated
 * sets a placement's page index is valid against both lists.
 *
 * [premultipliedAlpha] is the OR-fold of the CMO3 `GTexture2D.isPremultiplied` bits and is currently
 * UNCONSUMED - and is deliberately kept that way.  It is an editor texture-upload-convention flag
 * (serialized true on nearly every model), NOT a claim that the decoded bytes are premultiplied: every
 * ingested page is straight-alpha (PngCodec yields straight alpha; the fragment shader premultiplies
 * in-shader with source factor GL_ONE), so wiring this to "unpremultiply the bytes" would over-darken
 * the whole corpus.  The version-dependent premultiplied-vs-straight COMPOSITING distinction (Cubism
 * 5.2 and earlier plus the "(Before 5.3)" modes composite premultiplied; 5.3+ Color blend composites
 * straight) is a separate axis, carried by `BlendMode.isLegacy` and handled in
 * `BlendMath.compositeReference`, not by this field.  See docs/format/CMO3.md, "Premultiplied vs
 * straight alpha".
 *
 * @property List<DecodedImage> atlases               The distinct decoded atlas pages.
 * @property Map<String,Int>    atlasIndexByDrawableId Drawable id (`ArtMesh…`) → index into [atlases].
 * @property Boolean            premultipliedAlpha     Aggregated `GTexture2D.isPremultiplied`; unconsumed
 *                                                     (see the note above) - do not drive rendering off it.
 */
class PuppetTextures(
	val atlases: List<DecodedImage>,
	val atlasIndexByDrawableId: Map<String, Int>,
	val premultipliedAlpha: Boolean,
)

/**
 * How [buildPuppetTextures] treats an atlas page whose bytes will not decode.
 *
 * The two ingest paths want opposite answers and each is right for its format, so the policy is the
 * caller's to state rather than something the shared builder decides for them.
 */
enum class UndecodablePagePolicy {
	/**
	 * Drop the page and unmap every drawable that sampled it; the build still succeeds.  What a CMO3
	 * wants: its pixels are embedded in the model file, so a page that will not decode is one corrupt
	 * resource inside an otherwise editable rig, and refusing to open the document over it would cost
	 * the rigger everything else in the file.  Those drawables render in fallback color.
	 */
	Skip,

	/**
	 * Fail the whole build so the loader can report a broken import.  What a MOC3 wants: its pages are
	 * sibling files named by model3.json, so one that will not decode means the family on disk is
	 * incomplete or mismatched - an import error to surface, not a puppet to render half of.
	 */
	Fail,
}

/**
 * Builds the [PuppetTextures] for a puppet from its atlas page bytes and a per-drawable index into
 * them - the format-agnostic core both ingest paths end at.
 *
 * Each format's own atlas-page walk (`cmo3AtlasPages`, `moc3AtlasPages`, in `:interop`) produces a
 * neutral `AtlasPageSet` that the document loader unpacks into these two arguments and hands here, so
 * the decode, the page-index validation, and the PuppetTextures shape are written once.  Nothing about
 * this function knows a format; it is deliberately reachable with hand-built arguments so tests need
 * no corpus file.
 *
 * @param List<ByteArray>       pageBytes              The PNG bytes per atlas page, in index order.
 * @param Map<String, Int>      atlasIndexByDrawableId Drawable id (`ArtMesh…`) → index into [pageBytes].
 * @param Boolean               premultipliedAlpha     Passed through to [PuppetTextures.premultipliedAlpha];
 *                                                     see its docblock - aggregated by the CMO3 adapter
 *                                                     and unconsumed, false everywhere else.
 * @param UndecodablePagePolicy undecodablePage        What an undecodable page costs.
 * @return PuppetTextures? The decoded pages + index, or null when [undecodablePage] is
 *                         [UndecodablePagePolicy.Fail] and a page failed to decode or a drawable's
 *                         index fell outside [pageBytes].  Never null under
 *                         [UndecodablePagePolicy.Skip].
 */
fun buildPuppetTextures(
	pageBytes: List<ByteArray>,
	atlasIndexByDrawableId: Map<String, Int>,
	premultipliedAlpha: Boolean = false,
	undecodablePage: UndecodablePagePolicy = UndecodablePagePolicy.Fail,
): PuppetTextures? {
	val failOnBadPage = undecodablePage == UndecodablePagePolicy.Fail
	// An index outside the page list must never reach the renderer: PuppetRenderer resolves pages by
	// direct list indexing, so broken atlas wiring is caught here rather than crashing the render
	// thread at first frame.  Under Skip the same guard applies per drawable instead of per build -
	// an unresolvable page number simply leaves that drawable out of the rebuilt map below.
	if (failOnBadPage && atlasIndexByDrawableId.values.any { pageIndex -> pageIndex !in pageBytes.indices }) {
		return null
	}

	// Skip can drop pages, which shifts every later page down by however many preceded it, so decoding
	// records where each surviving page landed and the drawable map is rebuilt against that numbering.
	// Under Fail nothing is ever dropped and the remap is an identity copy.
	val atlases = ArrayList<DecodedImage>(pageBytes.size)
	val keptIndexBySourceIndex = HashMap<Int, Int>(pageBytes.size)
	for ((sourceIndex, bytes) in pageBytes.withIndex()) {
		val image =
			try {
				PngCodec.read(bytes)
			} catch (_: Exception) {
				if (failOnBadPage) {
					return null
				}
				continue
			}
		keptIndexBySourceIndex[sourceIndex] = atlases.size
		atlases.add(DecodedImage(image.rgba, image.width, image.height))
	}

	val keptIndexByDrawableId = LinkedHashMap<String, Int>(atlasIndexByDrawableId.size)
	for ((drawableId, sourceIndex) in atlasIndexByDrawableId) {
		keptIndexBySourceIndex[sourceIndex]?.let { keptIndex -> keptIndexByDrawableId[drawableId] = keptIndex }
	}
	return PuppetTextures(atlases, keptIndexByDrawableId, premultipliedAlpha)
}

/**
 * Re-encodes a decoded atlas page as PNG, for a page the document retains no source bytes for.
 *
 * The fallback half of both exports' page payload: a retained source PNG is written verbatim, which is
 * faster and lossless, and this covers every page there is no such PNG for.  A CMO3-origin document
 * reaches it for every page (its pixels are embedded per drawable, so it retains no atlas PNGs at all).
 * A MOC3-origin one retains a PNG per decoded page and so does not reach it, and falls back here only
 * if those two lists ever diverge - which would otherwise leave that page with no bytes to write.
 *
 * Re-encoding rather than dropping the page is what keeps page INDICES stable - drawables address
 * pages by number, so omitting one would renumber every page after it.
 *
 * @param DecodedImage atlas The decoded page.
 * @return ByteArray The PNG bytes.
 */
fun encodeAtlasPng(atlas: DecodedImage): ByteArray =
	PngCodec.write(RasterImage(width = atlas.width, height = atlas.height, rgba = atlas.rgba))

/**
 * [puppet] with every drawable's atlas page taken from [textures].
 *
 * A MOC3 addresses its pages by index on the art mesh, so the export needs one on every drawable.  A
 * MOC3-origin document already carries them (the import reads them straight off the art mesh), but a
 * CMO3 has no page index at all - its pixels are embedded per drawable - so those documents reach the
 * export with every drawable still on the -1 sentinel, which the MOC3 lowering clamps to page 0,
 * pointing a multi-page rig at one atlas.  The decoded texture set is what knows the answer either
 * way, so it is the one asked.
 *
 * @param PuppetModel    puppet   The rig being exported.
 * @param PuppetTextures textures The document's decoded atlas set.
 * @return PuppetModel The rig with its page bindings resolved, or [puppet] when none moved.
 */
fun withTexturePagesFrom(puppet: PuppetModel, textures: PuppetTextures): PuppetModel {
	var moved = false
	val drawables =
		puppet.drawables.map { drawable ->
			val page = textures.atlasIndexByDrawableId[drawable.id.raw] ?: return@map drawable
			if (page == drawable.texturePage) {
				drawable
			} else {
				moved = true
				drawable.copy(texturePage = page)
			}
		}
	return if (moved) puppet.copy(drawables = drawables) else puppet
}