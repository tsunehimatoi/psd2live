package org.umamo.format.kra

import org.jdom.Element
import org.jdom.input.SAXBuilder
import org.umamo.format.FileKind
import org.umamo.format.art.ArtReader
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Krita .kra reader (desktop JVM and Android).
 *
 * A .kra is a ZIP holding maindoc.xml (the DOC/IMAGE layer tree) plus one tiled pixel-data file per
 * raster layer under imageName/layers/. This reader unzips it, walks the layer tree, and decodes
 * each paint layer into the neutral SourceArt model - the same shape the PSD reader produces, so it
 * drops straight into the render and re-import paths.
 *
 * Scope: read-only; paint layers only (groups are traversed for hierarchy, all other node types
 * skipped); RGBA and GRAYA color in 8/16/32-bit (see resolveKraPixelFormat).
 *
 * Krita .kra 読み込み（デスクトップ JVM と Android）。ZIP と maindoc.xml とタイル化レイヤーを中立モデルへ。
 * java.util.zip と JDOM のみで Android でも動作する。ペイントレイヤーのみ対応。
 */
object KraReader : ArtReader {
	override val kind: FileKind = FileKind.Kra

	/**
	 * True if [candidateBytes] looks like a Krita archive (see isKra).
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the archive announces itself as a Krita document.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean = isKra(candidateBytes)

	/**
	 * Decodes a .kra into the neutral SourceArt model: every paint layer with its placement, flags,
	 * stable uuid, and decoded RGBA pixels, ordered bottom-to-top (matching the PSD reader).
	 *
	 * @param ByteArray bytes The complete .kra file.
	 * @return SourceArt the canvas size and the parsed layers.
	 */
	override fun read(bytes: ByteArray): SourceArt {
		val entries = unzipEntries(bytes)
		val mainXml =
			entries["maindoc.xml"] ?: entries["root"]
				?: error("not a .kra: no maindoc.xml in the archive")

		// JDOM finds elements by namespace-qualified name; KRA files carry a default namespace that
		// also differs between schema versions (calligra.org vs koffice.org). So we match by local
		// name throughout (see childrenNamed) and read attributes, which carry no namespace, directly.
		val documentRoot = parseMainDocument(mainXml)
		val imageElement =
			documentRoot.childrenNamed("IMAGE").firstOrNull()
				?: error("malformed .kra: DOC has no IMAGE")

		// KRA: kis_kra_saver.cpp - IMAGE name/width/height/colorspacename attributes.
		val imageName = imageElement.getAttributeValue("name") ?: "unnamed"
		val canvasWidth = imageElement.getAttributeValue("width")?.toIntOrNull() ?: 0
		val canvasHeight = imageElement.getAttributeValue("height")?.toIntOrNull() ?: 0
		val imageColorSpace = imageElement.getAttributeValue("colorspacename") ?: "RGBA"

		// Depth-first, document order = top-to-bottom (the first layer element is the topmost).
		val paintLayersTopToBottom = ArrayList<Element>()
		val groupPaths = ArrayList<String>()
		val groups = ArrayList<SourceGroup>()
		val topLayers =
			imageElement.childrenNamed("layers").firstOrNull() // newer schema: lowercase layers
				?: imageElement.childrenNamed("LAYERS").firstOrNull() // legacy schema: uppercase LAYERS
		if (topLayers != null) {
			collectPaintLayers(topLayers, parentPath = "", paintLayersTopToBottom, groupPaths, groups)
		}

		// Build in document order so order is top-most = 0, then reverse to bottom-to-top for the
		// painter's-algorithm list the compositor expects (same contract as the PSD reader).
		val layersTopToBottom =
			paintLayersTopToBottom.mapIndexed { documentIndex, layerElement ->
				buildLayer(
					layerElement = layerElement,
					groupPath = groupPaths[documentIndex],
					order = documentIndex,
					imageName = imageName,
					imageColorSpace = imageColorSpace,
					entries = entries,
				)
			}

		return KraSourceArt(
			widthPx = canvasWidth,
			heightPx = canvasHeight,
			layers = layersTopToBottom.reversed(),
			groups = groups,
		)
	}
}

/**
 * True if bytes is a Krita archive: a ZIP whose first, stored entry is the mimetype file holding
 * application/x-krita. Krita writes that entry first and uncompressed (like ODF/EPUB), so the marker
 * sits in the first few dozen bytes and a short prefix scan identifies the format without unzipping.
 * A .kra re-saved by a tool that drops the mimetype entry will not match here, in which case the
 * format registry's extension fallback covers it.
 *
 * @param ByteArray bytes Candidate file contents.
 * @return Boolean Whether the archive announces itself as a Krita document.
 */
private fun isKra(bytes: ByteArray): Boolean {
	// KRA: ZIP local-file-header magic @ +0x00, then the mimetype entry content (KoQuaZipStore.cpp:165
	// writes it first/uncompressed).  A .kra carries TWO similar-looking Krita mime strings and they
	// are not interchangeable: the mimetype entry holds "application/x-krita", while "application/x-kra"
	// is the <IMAGE mime> attribute inside maindoc.xml (see docs/format/KRA.md §1).  Matching the
	// latter here finds nothing — it is not even a prefix of the former, the two diverge at "kr|a" vs
	// "kr|ita" — so scan for the real entry content.  Both are accepted anyway: neither string can
	// occur this early in a non-Krita ZIP, so allowing both only buys tolerance of writer variation.
	val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\x03\x04"
	if (bytes.size < zipMagic.size) {
		return false
	}
	for (magicIndex in zipMagic.indices) {
		if (bytes[magicIndex] != zipMagic[magicIndex]) {
			return false
		}
	}
	val scanLimit = min(bytes.size, 128)
	return KRA_MIMETYPE_MARKERS.any { marker -> indexOfBytes(bytes, marker.encodeToByteArray(), scanLimit) >= 0 }
}

/**
 * The mimetype-entry contents that identify a Krita archive.
 *
 * "application/x-krita" is what Krita actually writes; "application/x-kra" is accepted defensively
 * (it is the maindoc.xml `<IMAGE mime>` value, and is what an earlier version of this check looked
 * for by mistake).
 */
private val KRA_MIMETYPE_MARKERS = listOf("application/x-krita", "application/x-kra")

/**
 * Returns the start index of the first occurrence of needle within haystack[0, scanLimit), or -1.
 *
 * @param ByteArray haystack The buffer to search.
 * @param ByteArray needle The byte sequence to find.
 * @param Int scanLimit Exclusive upper bound on where a match may start.
 * @return Int the start index of the match, or -1 if absent.
 */
private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, scanLimit: Int): Int {
	if (needle.isEmpty()) {
		return -1
	}
	val lastStart = min(scanLimit, haystack.size - needle.size + 1)
	var startIndex = 0
	while (startIndex < lastStart) {
		var matchIndex = 0
		while (matchIndex < needle.size && haystack[startIndex + matchIndex] == needle[matchIndex]) {
			matchIndex++
		}
		if (matchIndex == needle.size) {
			return startIndex
		}
		startIndex++
	}
	return -1
}

/**
 * Recursively gathers paint-layer elements in document order, building each one's slash-joined
 * group path from its ancestor group names, and emits a neutral SourceGroup for every group layer
 * encountered (so folder-level visibility and blend survive the flattening to a leaf list).
 *
 * @param Element container               A layers/LAYERS element to walk.
 * @param String parentPath               Slash-joined names of the enclosing groups (empty at root).
 * @param MutableList paintLayers         Accumulates paint-layer elements in order.
 * @param MutableList groupPaths          Accumulates each paint layer's group path (parallel list).
 * @param MutableList groups              Accumulates a SourceGroup per group layer, by path.
 */
private fun collectPaintLayers(
	container: Element,
	parentPath: String,
	paintLayers: MutableList<Element>,
	groupPaths: MutableList<String>,
	groups: MutableList<SourceGroup>,
) {
	for (layerElement in container.childrenNamed("layer")) {
		// KRA: nodetype (newer) or layertype (legacy) names the node class.
		val rawNodeType =
			layerElement.getAttributeValue("nodetype")
				?: layerElement.getAttributeValue("layertype")
				?: ""
		val nodeType = rawNodeType.lowercase()
		val name = layerElement.getAttributeValue("name") ?: ""
		when (nodeType) {
			"grouplayer" -> {
				val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
				groups.add(buildGroup(layerElement, childPath, name))
				val nested =
					layerElement.childrenNamed("layers").firstOrNull()
						?: layerElement.childrenNamed("LAYERS").firstOrNull()
				if (nested != null) {
					collectPaintLayers(nested, childPath, paintLayers, groupPaths, groups)
				}
			}

			"paintlayer" -> {
				paintLayers.add(layerElement)
				groupPaths.add(parentPath)
			}
			// adjustment/generator/clone/shape/file layers and masks carry no plain raster - skip.
			else -> Unit
		}
	}
}

/**
 * Builds a neutral SourceGroup from a Krita group-layer element.
 *
 * KRA group layers carry the same visibility/opacity/composite attributes as paint layers, plus a
 * passthrough flag (a non-isolating folder). Krita has no per-folder "clip to below" concept that
 * maps cleanly onto the neutral clipped flag (its inherit-alpha lives in channelflags and needs the
 * resolved color space), so clipped is left false here; the source-art compositor only consumes
 * group visibility today.
 *
 * @param Element groupElement The grouplayer element.
 * @param String path          The slash-joined folder path including this folder's name.
 * @param String name          The folder name.
 * @return SourceGroup the neutral folder descriptor.
 */
private fun buildGroup(groupElement: Element, path: String, name: String): SourceGroup =
	KraSourceGroup(
		path = path,
		name = name,
		// KRA: a group layer's @visible "0" hides the whole subtree (kis_kra_loader sets node visible).
		visible = groupElement.getAttributeValue("visible") != "0",
		opacity = (groupElement.getAttributeValue("opacity")?.toIntOrNull() ?: 255) / 255f,
		clipped = false,
		blend = parseCompositeOp(groupElement.getAttributeValue("compositeop")),
		// KRA: @passthrough "1" is a non-isolating folder; its blend then does not apply.
		passThrough = groupElement.getAttributeValue("passthrough") == "1",
	)

/**
 * Decodes one paint layer into a SourceLayer: reads its tile file, resolves the color format,
 * applies the default pixel, and assembles a cropped RGBA8888 raster placed on the canvas.
 *
 * @param Element layerElement       The paintlayer element.
 * @param String groupPath           Slash-joined ancestor group names.
 * @param Int order                  Draw order, top-most = 0.
 * @param String imageName           The IMAGE name (the layer-data directory prefix).
 * @param String imageColorSpace     Fallback color space if the layer omits its own.
 * @param Map entries                All decompressed ZIP entries by name.
 * @return SourceLayer the decoded layer.
 */
private fun buildLayer(
	layerElement: Element,
	groupPath: String,
	order: Int,
	imageName: String,
	imageColorSpace: String,
	entries: Map<String, ByteArray>,
): SourceLayer {
	val name = layerElement.getAttributeValue("name") ?: ""
	val filename =
		layerElement.getAttributeValue("filename")
			?: error("KRA paint layer '$name' has no filename")
	// KRA: layer uuid is rename/reorder-stable - the re-import key advantage over PSD name+order.
	val uuid = layerElement.getAttributeValue("uuid") ?: "$groupPath/$name#$order"
	// KRA: @visible "0" is the eye-off layer; absent or any other value means shown.
	val visible = layerElement.getAttributeValue("visible") != "0"
	val opacity = (layerElement.getAttributeValue("opacity")?.toIntOrNull() ?: 255) / 255f
	val layerX = layerElement.getAttributeValue("x")?.toIntOrNull() ?: 0
	val layerY = layerElement.getAttributeValue("y")?.toIntOrNull() ?: 0
	val colorspaceName = layerElement.getAttributeValue("colorspacename") ?: imageColorSpace
	val blend = parseCompositeOp(layerElement.getAttributeValue("compositeop"))

	val layerDataPath = "$imageName/layers/$filename"
	val tileBytes = entries[layerDataPath] ?: error("KRA layer data missing: $layerDataPath")
	val tiles = parseKraLayerTiles(tileBytes)
	val format = resolveKraPixelFormat(colorspaceName, tiles.pixelSize)
	val defaultPixelBytes = entries["$layerDataPath.defaultpixel"]

	// Per-channel enable mask (channelflags); its width is the channel count, so the format must be
	// resolved first. A disabled alpha channel is Krita's "Inherit Alpha" - the clipping signal.
	val channelMask = channelMaskFrom(layerElement.getAttributeValue("channelflags"), format)

	val assembled = assembleRaster(tiles, format, defaultPixelBytes, layerX, layerY)

	return KraSourceLayer(
		id = LayerId(uuid),
		name = name,
		visible = visible,
		groupPath = groupPath,
		order = order,
		bounds = assembled.bounds,
		opacity = opacity,
		clipped = !channelMask.alpha, // KRA: a disabled alpha channel is "Inherit Alpha" = clipping.
		blend = blend,
		channelMask = channelMask,
		raster = assembled.raster,
	)
}

private class AssembledRaster(val bounds: LayerBounds, val raster: LayerRaster)

/**
 * Composes a layer's tiles into one cropped RGBA8888 buffer sized to the union of its tile rects,
 * pre-filled with the converted default pixel, and positioned on the canvas via the layer offset.
 *
 * @param KraLayerTiles tiles            The decoded tiles and geometry.
 * @param KraPixelFormat format          The source pixel format and converter.
 * @param ByteArray? defaultPixelBytes   The defaultpixel bytes (native order), or null.
 * @param Int layerX                     Layer canvas X offset (KRA: node->setX).
 * @param Int layerY                     Layer canvas Y offset (KRA: node->setY).
 * @return AssembledRaster the bounds (top-left origin) and packed pixels.
 */
private fun assembleRaster(
	tiles: KraLayerTiles,
	format: KraPixelFormat,
	defaultPixelBytes: ByteArray?,
	layerX: Int,
	layerY: Int,
): AssembledRaster {
	if (tiles.tiles.isEmpty()) {
		// An empty paint layer: a 1x1 transparent placeholder keeps downstream dimensions valid.
		return AssembledRaster(LayerBounds(layerX, layerY, 1, 1), LayerRaster(1, 1, ByteArray(4)))
	}

	var minLeft = Int.MAX_VALUE
	var minTop = Int.MAX_VALUE
	var maxRight = Int.MIN_VALUE
	var maxBottom = Int.MIN_VALUE
	for (tile in tiles.tiles) {
		minLeft = min(minLeft, tile.left)
		minTop = min(minTop, tile.top)
		maxRight = max(maxRight, tile.left + tiles.tileWidth)
		maxBottom = max(maxBottom, tile.top + tiles.tileHeight)
	}
	val width = maxRight - minLeft
	val height = maxBottom - minTop
	val rgba = ByteArray(width * height * 4)

	// Pre-fill with the converted default pixel (the color Krita uses for untiled gaps). A
	// transparent default leaves the zero-initialised buffer untouched.
	val fillPixel = ByteArray(4)
	if (defaultPixelBytes != null && defaultPixelBytes.size >= format.pixelSize) {
		format.convertPixel(defaultPixelBytes, 0, fillPixel, 0)
	}
	if (fillPixel[0].toInt() != 0 || fillPixel[1].toInt() != 0 || fillPixel[2].toInt() != 0 || fillPixel[3].toInt() != 0) {
		for (pixelIndex in 0 until width * height) {
			fillPixel.copyInto(rgba, pixelIndex * 4, 0, 4)
		}
	}

	for (tile in tiles.tiles) {
		for (rowIndex in 0 until tiles.tileHeight) {
			val destinationY = tile.top - minTop + rowIndex
			val rowBase = (destinationY * width + (tile.left - minLeft)) * 4
			val sourceRowBase = rowIndex * tiles.tileWidth * tiles.pixelSize
			for (columnIndex in 0 until tiles.tileWidth) {
				format.convertPixel(
					tile.pixels,
					sourceRowBase + columnIndex * tiles.pixelSize,
					rgba,
					rowBase + columnIndex * 4,
				)
			}
		}
	}

	val bounds = LayerBounds(left = minLeft + layerX, top = minTop + layerY, width = width, height = height)
	return AssembledRaster(bounds, LayerRaster(width = width, height = height, rgba = rgba))
}

/**
 * Maps a Krita compositeop id to the neutral LayerBlend set; unknown ops degrade to Normal.
 *
 * @param String? compositeOp The compositeop attribute value.
 * @return LayerBlend the closest supported blend mode.
 */
private fun parseCompositeOp(compositeOp: String?): LayerBlend =
	// KRA: composite op ids (libs/pigment/compositeops).
	when (compositeOp?.lowercase()) {
		"multiply" -> LayerBlend.Multiply
		"screen" -> LayerBlend.Screen
		"add", "linear_dodge" -> LayerBlend.Add
		else -> LayerBlend.Normal
	}

/**
 * Returns this element's direct child elements whose local name equals localName (case-insensitive),
 * ignoring XML namespaces - KRA's default namespace varies by schema version.
 *
 * @param String localName The unqualified element name to match.
 * @return List the matching child elements (empty if none).
 */
@Suppress("UNCHECKED_CAST")
private fun Element.childrenNamed(localName: String): List<Element> =
	(this.children as List<Element>).filter { it.name.equals(localName, ignoreCase = true) }

/**
 * Parses maindoc.xml into its root element with external-DTD loading disabled.
 *
 * KRA's maindoc.xml declares a DOCTYPE pointing at an external DTD (calligra.org or koffice.org)
 * that we never need - we only read element/attribute structure. Left enabled, the parser tries to
 * fetch that URL and fails (offline, or the URL no longer serves a valid DTD). So we turn off
 * external-DTD loading and install a no-op entity resolver as a portable backstop.
 *
 * @param ByteArray xmlBytes The raw maindoc.xml bytes.
 * @return Element the document root (DOC).
 */
private fun parseMainDocument(xmlBytes: ByteArray): Element {
	val builder = SAXBuilder()
	runCatching {
		builder.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
	}
	builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
	return builder.build(ByteArrayInputStream(xmlBytes)).rootElement
}

/**
 * Decompresses every non-directory ZIP entry into memory, keyed by entry name.
 *
 * ZipInputStream is sequential-only, so we read each entry fully up front; a .kra's working set
 * fits comfortably in memory (the PSD reader likewise takes the whole file as bytes).
 *
 * @param ByteArray bytes The complete ZIP archive.
 * @return Map every entry's bytes by name.
 */
private fun unzipEntries(bytes: ByteArray): Map<String, ByteArray> {
	val entries = HashMap<String, ByteArray>()
	ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
		var entry = zip.nextEntry
		while (entry != null) {
			if (!entry.isDirectory) {
				entries[entry.name] = zip.readBytes()
			}
			zip.closeEntry()
			entry = zip.nextEntry
		}
	}
	return entries
}

/** Concrete SourceLayer backing a parsed KRA paint layer. */
private data class KraSourceLayer(
	override val id: LayerId,
	override val name: String,
	override val visible: Boolean,
	override val groupPath: String,
	override val order: Int,
	override val bounds: LayerBounds,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val channelMask: ChannelMask,
	override val raster: LayerRaster,
) : SourceLayer

/** Concrete SourceGroup backing a parsed KRA group layer. */
private data class KraSourceGroup(
	override val path: String,
	override val name: String,
	override val visible: Boolean,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val passThrough: Boolean,
) : SourceGroup

/** Concrete SourceArt backing a parsed KRA document. */
private data class KraSourceArt(
	override val widthPx: Int,
	override val heightPx: Int,
	override val layers: List<SourceLayer>,
	override val groups: List<SourceGroup>,
) : SourceArt