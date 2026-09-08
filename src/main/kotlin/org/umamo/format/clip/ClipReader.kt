package org.umamo.format.clip

import org.umamo.format.FileKind
import org.umamo.format.art.ArtReader
import org.umamo.format.art.SourceArt
import org.umamo.format.clip.db.ClipDatabase
import org.umamo.format.clip.db.SelectAllLayers
import kotlin.math.roundToInt

/**
 * Clip Studio Paint (.clip) reader (STRETCH, but the re-import-critical one).
 *
 * EN: `.clip` carries Celsys's rename/reorder-stable layer ids (Layer.MainId and Layer.LayerUuid),
 *     which give non-destructive re-import a reliable identity key PSD cannot.  This reader parses the
 *     CSFCHUNK container down to the embedded SQLite database (CHNKSQLi) for the Canvas + Layer tables,
 *     and decodes the tiled raster from the CHNKExta chunks: ids, names, group hierarchy, order,
 *     opacity, blend, bounds, kind, visibility, RGBA/greyscale/monochrome pixels, masks (baked into
 *     alpha), and clipping.  See docs/format/CLIP.md.
 * JA: CLIP 読み込み。安定レイヤー ID が再取り込みの信頼性を生む。ラスタ（RGBA/グレースケール/モノクロ）も復号する。
 *
 * Cross-platform (JVM + Android): the only platform-specific step - opening a SQLite driver over the
 * extracted database bytes - sits behind the [useClipDatabase] expect/actual seam (JVM JdbcSqliteDriver,
 * Android AndroidSqliteDriver).  The container parse and tile decode are pure jvmAndroidMain Kotlin.
 * On Android, ClipAndroid.applicationContext must be set once at startup (the driver needs a Context).
 */
object ClipReader : ArtReader {
	override val kind: FileKind = FileKind.Clip

	/**
	 * True if [candidateBytes] starts with the CSFCHUNK container magic.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the CLIP magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean = ClipContainer.isClip(candidateBytes)

	/**
	 * Parses a complete .clip file into the neutral SourceArt model (layer tree only, this pass).
	 *
	 * @param ByteArray bytes The complete .clip file contents.
	 * @return SourceArt The canvas size and the ordered, bottom-to-top layer list.
	 */
	override fun read(bytes: ByteArray): SourceArt {
		// CLIP: CSFCHUNK container -> CHNKSQLi payload holds the layer table; raster tiles live in the
		// CHNKExta chunks of the same byte stream, located via ExternalChunk offsets.
		val databaseBytes = ClipContainer.extractSqliteDatabase(bytes)
		return readDatabase(bytes, databaseBytes)
	}

	/**
	 * Reads the Canvas + Layer tables, decodes each layer's raster from [fileBytes], and assembles
	 * SourceArt.  The driver lifecycle (write a temp DB, open the platform driver, close, clean up)
	 * lives in [useClipDatabase]; everything here is platform-neutral.  The embedded database already
	 * has its tables, so the generated SELECTs run WITHOUT Schema.create.
	 *
	 * @param ByteArray fileBytes     The complete .clip file (raster tiles are read from it).
	 * @param ByteArray databaseBytes The raw "SQLite format 3" bytes from the CHNKSQLi chunk.
	 * @return SourceArt The parsed document.
	 */
	private fun readDatabase(fileBytes: ByteArray, databaseBytes: ByteArray): SourceArt =
		useClipDatabase(databaseBytes) { database ->
			val canvasRow =
				database.clipQueries.selectCanvas().executeAsOneOrNull()
					?: throw IllegalArgumentException("CLIP database has no Canvas row")
			val canvas =
				ClipCanvas(
					// CLIP: Canvas dimensions are REAL pixels; round to the integer pixel grid.
					widthPx = (canvasRow.CanvasWidth ?: 0.0).roundToInt(),
					heightPx = (canvasRow.CanvasHeight ?: 0.0).roundToInt(),
					rootFolderId = canvasRow.CanvasRootFolder ?: 0L,
				)

			val rows = database.clipQueries.selectAllLayers().executeAsList().map { row -> row.toClipLayerRow() }
			val rastersByMainId = decodeRasters(fileBytes, database, rows)
			val textLayerIds = readTextLayerIds(database)
			val tree = buildSourceTree(canvas, rows, rastersByMainId, textLayerIds)
			ClipSourceArt(
				widthPx = canvas.widthPx,
				heightPx = canvas.heightPx,
				layers = tree.layers,
				groups = tree.groups,
			)
		}

	/**
	 * Decodes every layer's base-resolution raster, keyed by layer MainId.
	 *
	 * Resolves each layer's external-chunk reference (Offscreen.BlockData) to a file offset via the
	 * ExternalChunk table, then hands the Attribute tile-index and chunk offset to [ClipRaster].
	 * Layers with no decodable content are simply omitted (the tree builder falls back to a
	 * placeholder for them).
	 *
	 * @param ByteArray fileBytes      The complete .clip file.
	 * @param ClipDatabase database    The open CLIP database.
	 * @param List rows                The layer rows (for per-layer canvas offsets).
	 * @return Map Decoded rasters keyed by layer MainId.
	 */
	private fun decodeRasters(
		fileBytes: ByteArray,
		database: ClipDatabase,
		rows: List<ClipLayerRow>,
	): Map<Long, ClipDecodedRaster> {
		// CLIP: ExternalChunk.ExternalID ("extrnlid"+GUID) -> absolute CHNKExta file offset.
		val offsetByExternalId = HashMap<String, Int>()
		for (external in database.clipQueries.selectExternalChunks().executeAsList()) {
			val externalId = external.ExternalID ?: continue
			val offset = external.Offset ?: continue
			offsetByExternalId[externalId.decodeToString()] = offset.toInt()
		}

		val layerOffsetsByMainId = rows.associateBy { row -> row.mainId }

		// Per-layer mask offscreen (Attribute + external chunk offset), keyed by layer MainId.
		val maskByMainId = HashMap<Long, MaskSource>()
		for (maskRow in database.clipQueries.selectLayerMaskRasters().executeAsList()) {
			val mainId = maskRow.MainId ?: continue
			val attribute = maskRow.Attribute ?: continue
			// BlockData is non-null by construction: selectLayerMaskRasters filters `O.BlockData IS NOT NULL`,
			// so SQLDelight types it non-nullable (unlike MainId/Attribute, which carry no such predicate).
			val blockData = maskRow.BlockData
			val chunkOffset = offsetByExternalId[blockData.decodeToString()] ?: continue
			maskByMainId[mainId] = MaskSource(chunkOffset = chunkOffset, attribute = attribute)
		}

		val rasters = HashMap<Long, ClipDecodedRaster>()
		for (rasterRow in database.clipQueries.selectLayerRasters().executeAsList()) {
			val mainId = rasterRow.MainId ?: continue
			val attribute = rasterRow.Attribute ?: continue
			// BlockData is non-null by construction: selectLayerRasters filters `O.BlockData IS NOT NULL`,
			// so SQLDelight types it non-nullable (unlike MainId/Attribute, which carry no such predicate).
			val blockData = rasterRow.BlockData
			val chunkOffset = offsetByExternalId[blockData.decodeToString()] ?: continue
			val layerRow = layerOffsetsByMainId[mainId] ?: continue
			val mask = maskByMainId[mainId]
			val decoded =
				ClipRaster.decodeLayer(
					fileBytes = fileBytes,
					colorChunkOffset = chunkOffset,
					colorAttribute = attribute,
					maskChunkOffset = mask?.chunkOffset ?: -1,
					maskAttribute = mask?.attribute,
					// CLIP: tile-grid anchor = LayerOffset + LayerRenderOffscrOffset (canvas-aligned).
					anchorX = (layerRow.offsetX + layerRow.renderOffscrX).toInt(),
					anchorY = (layerRow.offsetY + layerRow.renderOffscrY).toInt(),
				)
			if (decoded != null) {
				rasters[mainId] = decoded
			}
		}
		return rasters
	}
}

/**
 * Reads the set of text-layer MainIds, tolerating older CLIP schemas that lack the TextLayerType
 * column.  The CLIP SQLite schema gains columns across Clip Studio versions, so this version-specific
 * lookup is isolated and its failure is swallowed - an absent column simply means "no text layers
 * identified" (they fall back to Vector).
 *
 * @param ClipDatabase database The open CLIP database.
 * @return Set The text-layer MainIds, or empty if the column is absent.
 */
private fun readTextLayerIds(database: ClipDatabase): Set<Long> =
	runCatching {
		database.clipQueries.selectTextLayerIds().executeAsList().mapNotNull { row -> row.MainId }.toSet()
	}.getOrDefault(emptySet())

/** A layer's mask offscreen: the external chunk offset and the mask Attribute tile index. */
private class MaskSource(val chunkOffset: Int, val attribute: ByteArray)

/**
 * Maps a SQLDelight-generated Layer row to the internal [ClipLayerRow], defaulting NULL columns.
 *
 * @return ClipLayerRow The reduced, null-defaulted row.
 */
private fun SelectAllLayers.toClipLayerRow(): ClipLayerRow =
	ClipLayerRow(
		mainId = MainId ?: 0L,
		name = LayerName ?: "",
		folder = LayerFolder ?: 0L,
		type = LayerType ?: 0L,
		visibility = LayerVisibility ?: 1L,
		opacity = LayerOpacity ?: 256L, // CLIP: absent opacity defaults to fully opaque (256).
		composite = LayerComposite ?: 0L,
		clip = LayerClip ?: 0L,
		offsetX = LayerOffsetX ?: 0L,
		offsetY = LayerOffsetY ?: 0L,
		firstChildIndex = LayerFirstChildIndex ?: 0L,
		nextIndex = LayerNextIndex ?: 0L,
		uuid = LayerUuid,
		renderOffscrX = LayerRenderOffscrOffsetX ?: 0L,
		renderOffscrY = LayerRenderOffscrOffsetY ?: 0L,
	)