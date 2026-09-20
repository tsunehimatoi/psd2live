package org.umamo.render

import org.umamo.format.png.PngCodec
import org.umamo.runtime.model.AtlasTileId
import kotlin.concurrent.Volatile

/**
 * A document's source-art pixels, decoded on demand and keyed by atlas tile.
 *
 * Pixels only.  Which tiles exist, how big they are, and where each one packs are model state
 * (`PuppetModel.atlas`), because a repack authors them; this holds the one thing a model must not,
 * since a raster in the model would ride along in every undo snapshot.
 *
 * Nothing is decoded until it is asked for: a CMO3 document loads synchronously on the UI thread, so
 * decoding every tile up front would stall the open by seconds on a real model.  A decoded tile is
 * retained for the store's life, which is the document's life - a replaced document drops the store.
 *
 * Format-agnostic by construction: the decoder is injected.  A CMO3 document supplies one over its
 * retained graph's PNG bytes ([fromPng]); an artwork-origin document, whose reader already decoded
 * every layer, supplies one over those rasters directly.
 *
 * @property Function decode Yields a tile's decoded pixels, or null when it has none or they will not
 *   decode.  Called from any thread, possibly several at once, so it must share nothing mutable - and
 *   it must return the SAME instance for the same tile on every call when it can, because the
 *   renderer's texture cache and the viewport's freshness test compare decoded images by identity.
 */
class SourceArtRasters(
	private val decode: (AtlasTileId) -> DecodedImage?,
) {
	// A null value is a remembered failure (no bytes, or bytes that will not decode), which is why this
	// is not a getOrPut - getOrPut treats a stored null as absent and would retry the decode every time.
	private val decodedByTile = HashMap<AtlasTileId, DecodedImage?>()

	// Rasters added after the store was built - artwork brought into an open document, the replacement
	// tiles a reload mints - read before the injected decoder.  An immutable map swapped whole behind a
	// volatile reference, so the off-thread repack decode sees a complete map or the previous one,
	// never a half-written one.
	@Volatile
	private var added: Map<AtlasTileId, DecodedImage> = emptyMap()

	/**
	 * Adds already-decoded rasters for tiles the injected decoder knows nothing about - the layers of
	 * an artwork file added to the document after it opened, and the replacement tiles a reload,
	 * relink, or match mints over new art.  Document-lifetime like the rest of the store: an added
	 * raster stays even if the edit that brought it is undone, which is harmless since its tile is then
	 * gone from the model.  Re-adding a tile replaces its raster.
	 *
	 * @param Map rasters The decoded pixels by tile.
	 */
	fun addDecoded(rasters: Map<AtlasTileId, DecodedImage>) {
		if (rasters.isEmpty()) {
			return
		}
		added = added + rasters
	}

	/**
	 * A tile's pixels, decoding them on first request and caching the result (failures included).
	 *
	 * A raster added through [addDecoded] wins over the cache: a reload's replacement id (`<root>~<n>`)
	 * is minted past the ids the model holds NOW, so reload, undo, reload mints the same id twice with
	 * different pixels, and a cache entry taken between the two would otherwise stand in for the second.
	 * Both twins then hand out the added instance, which is what the identity comparisons need.
	 *
	 * CALLER-CONFINED: the cache is a plain map with no synchronization, so every call must come from
	 * the same thread (today, the UI thread).  A second caller would not merely race the map - it could
	 * hand out a SECOND decoded instance for one tile, and both the renderer's texture cache and the
	 * viewport's freshness test compare decoded images by identity.  Anything off that thread uses
	 * [decodeRaster] instead, which shares nothing.
	 *
	 * @param AtlasTileId tileId The tile to read.
	 * @return DecodedImage? The decoded raster, or null when the tile has no usable pixels.
	 */
	fun rasterFor(tileId: AtlasTileId): DecodedImage? {
		added[tileId]?.let { raster -> return raster }
		if (decodedByTile.containsKey(tileId)) {
			return decodedByTile[tileId]
		}
		// The cached twin decodes through the uncached one: the two must produce the same image for the
		// same bytes, because callers compare a cached result against a freshly decoded one by identity.
		val decoded = decodeRaster(tileId)
		decodedByTile[tileId] = decoded
		return decoded
	}

	/**
	 * A tile's pixels, decoded fresh and cached nowhere - safe to call from any thread, including
	 * several at once.
	 *
	 * The uncached twin of [rasterFor], for callers that own their own result: the injected decoder
	 * shares nothing mutable, so nothing here is shared.  Costs a repeat decode when a tile is already
	 * cached (for a store over encoded bytes), which is the price of not sharing a cache across threads.
	 *
	 * @param AtlasTileId tileId The tile to read.
	 * @return DecodedImage? The decoded raster, or null when the tile has no usable pixels.
	 */
	fun decodeRaster(tileId: AtlasTileId): DecodedImage? = added[tileId] ?: decode(tileId)

	companion object {
		/** The store a document with no source art surfaces. */
		val EMPTY: SourceArtRasters = SourceArtRasters { null }

		/**
		 * A store over encoded PNG bytes - the CMO3 case, where every tile's pixels sit in the retained
		 * graph as an embedded PNG and decoding is deferred to first request.
		 *
		 * @param Function readBytes Yields a tile's PNG bytes, or null when it has none.
		 * @return SourceArtRasters The store; bytes that will not decode read as no raster.
		 */
		fun fromPng(readBytes: (AtlasTileId) -> ByteArray?): SourceArtRasters =
			SourceArtRasters { tileId ->
				readBytes(tileId)?.let { bytes ->
					try {
						val image = PngCodec.read(bytes)
						DecodedImage(image.rgba, image.width, image.height)
					} catch (_: Exception) {
						null
					}
				}
			}
	}
}