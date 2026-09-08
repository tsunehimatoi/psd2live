package org.umamo.format.psd

import org.umamo.format.FileKind
import org.umamo.format.art.ArtReader
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer

/**
 * Pure-Kotlin PSD reader (desktop JVM and Android).
 *
 * EN: [PsdLayerRecords] walks the "Layer and Mask Information" section for each layer's metadata
 *     (bounds/opacity/clipping/blend/name/folder structure) and channel layout; [PsdRaster] decodes
 *     each layer's channel pixels to straight-alpha RGBA.  Both use only java.nio and java.util.zip,
 *     which Android ships (unlike javax.imageio, which is desktop-only) - so this lives in
 *     jvmAndroidMain and lights up tablets too.
 * JA: 純 Kotlin の PSD 読み込み。メタデータは [PsdLayerRecords]、ピクセルは [PsdRaster]。Android でも動作。
 *
 * Scope: read-only; 8/16-bit RGB(A) and Grayscale, Indexed-color, and 1-bit Bitmap.  CMYK, Lab,
 * Multichannel, Duotone, and 32-bit float are rejected with a clear error.
 *
 * @see <a href="https://docs.umamo.org/format/PSD.md">PSD.md</a>
 */
object PsdReader : ArtReader {
	override val kind: FileKind = FileKind.Psd

	// PSD: color modes we ingest (PSD.java COLOR_MODE_*); everything else is rejected up front.
	private const val MODE_BITMAP = 0
	private const val MODE_GRAYSCALE = 1
	private const val MODE_INDEXED = 2
	private const val MODE_RGB = 3

	/**
	 * True if [candidateBytes] starts with the PSD file signature.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the PSD magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean = isPsd(candidateBytes)

	/**
	 * Decodes a `.psd` into the neutral [SourceArt] model: every layer with its placement, flags,
	 * and decoded RGBA pixels, ordered bottom-to-top.
	 *
	 * @param ByteArray bytes The complete `.psd` file.
	 * @return SourceArt the canvas size and the parsed layers.
	 */
	override fun read(bytes: ByteArray): SourceArt {
		val parse = PsdLayerRecords.parse(bytes)
		val header = parse.header

		// Fail fast on color modes / depths the channel decoder does not handle, with a clear message
		// rather than producing garbled pixels (CMYK/Lab need ICC color management we deliberately omit).
		require(header.colorMode == MODE_BITMAP || header.colorMode == MODE_GRAYSCALE || header.colorMode == MODE_INDEXED || header.colorMode == MODE_RGB) {
			"PSD color mode ${header.colorMode} is not supported (only Bitmap/Grayscale/Indexed/RGB; CMYK/Lab/Multichannel/Duotone rejected)"
		}
		require(header.depth == 1 || header.depth == 8 || header.depth == 16) {
			"PSD ${header.depth}-bit depth is not supported (only 1/8/16; 32-bit float rejected)"
		}

		// Walk records top-to-bottom (the reverse of bottom-to-top PSD storage) so a folder's named
		// header - which sits at the top of its run - opens the group before its children, and the
		// hidden bounding divider at the bottom closes it. Real layers carry the current stack path;
		// folder markers are structural and are not emitted as drawable layers.
		val groups = ArrayList<SourceGroup>()
		val folderStack = ArrayDeque<String>()
		val layersTopToBottom = ArrayList<SourceLayer>()
		var emittedOrder = 0
		for (recordIndex in parse.records.indices.reversed()) {
			val record = parse.records[recordIndex]
			when (record.dividerType) {
				1, 2 -> {
					// Folder header (open/closed): name the group and push it onto the path stack.
					val parentPath = folderStack.joinToString("/")
					val path = if (parentPath.isEmpty()) record.name else "$parentPath/${record.name}"
					folderStack.addLast(record.name)
					groups +=
						PsdSourceGroup(
							path = path,
							name = record.name,
							visible = record.visible,
							opacity = record.opacity,
							clipped = record.clipped,
							blend = record.blend,
							passThrough = record.passThrough,
						)
				}

				3 -> {
					// Bounding divider ("</Layer group>"): closes the innermost open folder.
					if (folderStack.isNotEmpty()) {
						folderStack.removeLast()
					}
				}

				else -> {
					layersTopToBottom +=
						PsdSourceLayer(
							// Stable identity: Photoshop's lyid (stable across rename/reorder) when present, else name+order. See docs/format/PSD.md.
							id = record.layerId?.let { layerId -> LayerId("lyid:$layerId") } ?: LayerId("${record.name}#$recordIndex"),
							name = record.name,
							visible = record.visible,
							groupPath = folderStack.joinToString("/"),
							order = emittedOrder++, // top-most emitted layer = 0
							bounds = record.bounds,
							opacity = record.opacity,
							clipped = record.clipped,
							blend = record.blend,
							raster = PsdRaster.decodeLayer(bytes, header, parse.colorModeData, record),
						)
				}
			}
		}
		// The compositor and re-import expect layers bottom-to-top (painter's order), matching KRA.
		return PsdSourceArt(
			widthPx = header.width,
			heightPx = header.height,
			layers = layersTopToBottom.asReversed(),
			groups = groups,
		)
	}
}

/**
 * True if [bytes] begins with the PSD file signature.
 *
 * @param ByteArray bytes Candidate file contents.
 * @return Boolean Whether the leading bytes are the PSD magic.
 */
private fun isPsd(bytes: ByteArray): Boolean {
	// PSD: File Header signature '8BPS' @ +0x00.
	val signature = byteArrayOf(0x38, 0x42, 0x50, 0x53) // "8BPS"
	if (bytes.size < signature.size) {
		return false
	}
	for (signatureIndex in signature.indices) {
		if (bytes[signatureIndex] != signature[signatureIndex]) {
			return false
		}
	}
	return true
}

/** Concrete [SourceLayer] backing a parsed PSD layer. */
private data class PsdSourceLayer(
	override val id: LayerId,
	override val name: String,
	override val visible: Boolean,
	override val groupPath: String,
	override val order: Int,
	override val bounds: LayerBounds,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val raster: LayerRaster,
) : SourceLayer

/** Concrete [SourceGroup] backing a parsed PSD folder (a section-divider header layer). */
private data class PsdSourceGroup(
	override val path: String,
	override val name: String,
	override val visible: Boolean,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val passThrough: Boolean,
) : SourceGroup

/** Concrete [SourceArt] backing a parsed PSD document. */
private data class PsdSourceArt(
	override val widthPx: Int,
	override val heightPx: Int,
	override val layers: List<SourceLayer>,
	override val groups: List<SourceGroup>,
) : SourceArt