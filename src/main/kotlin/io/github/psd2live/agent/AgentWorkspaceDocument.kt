package io.github.psd2live.agent

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.Side
import org.umamo.format.art.ChannelMask
import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceGroup
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Immutable aggregate captured by each append-only history node. Raster buffers are never mutated. */
internal data class AgentWorkspaceDocument(
	val source: SourceArt,
	val layerVisibility: Map<String, Boolean>,
	val deletedLayerIds: Set<String>,
	val layerOverrides: Map<String, LayerClassificationOverride>,
	val parentOverrides: Map<String, String?>,
	val rigEdits: RigEditOverlay,
    val settings: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

/** Marker used to distinguish Agent-created source layers from layers loaded from the artist file. */
internal interface AgentWorkspaceSourceLayer : SourceLayer {
	val derived: Boolean
	val sourceAssetId: String?
	val sourceSpatialReferenceId: String?
}

internal data class AgentPngAsset(
	val public: AgentImportedPngAsset,
	val rgba: ByteArray,
    val originalPng: ByteArray? = null,
)

internal fun AgentPngAsset.preview(): AgentAssetPreview {
    val png = java.io.ByteArrayOutputStream().also { ImageIO.write(rgbaImage(public.pixelWidth, public.pixelHeight, rgba), "png", it) }.toByteArray()
    var transparent = 0
    var translucent = 0
    for (i in 3 until rgba.size step 4) {
        val alpha = rgba[i].toInt() and 255
        if (alpha == 0) transparent++ else if (alpha < 255) translucent++
    }
    return AgentAssetPreview(public, png, transparent, translucent, originalPng)
}

internal class AgentPngAssetStore {
	private val assets = ConcurrentHashMap<String, AgentPngAsset>()

	fun import(request: AgentPngImportRequest, spatial: AgentViewSpatialMetadata): AgentImportedPngAsset {
		require(request.png.size >= PNG_SIGNATURE.size && request.png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
			"asset_import_png accepts PNG data only"
		}
		val decoded = ImageIO.read(request.png.inputStream())
			?: throw IllegalArgumentException("The supplied bytes are not a decodable PNG")
		require(decoded.width.toLong() * decoded.height <= 16_777_216) { "PNG exceeds 16 megapixels" }
        val matte = if (request.referenceId != null) processGeneratedMatte(decoded,
            requireNotNull(request.solidBackground) { "Reference imports require the actual solid_background" }, request.backgroundTolerance, request.processing) else null
        val image = matte?.image ?: request.solidBackground?.let { cleanGeneratedMatte(decoded, it, request.backgroundTolerance) } ?: decoded
        if (request.requireTransparency) {
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            require(pixels.any { it ushr 24 == 0 } && pixels.any { it ushr 24 > 0 }) {
                "Hair asset must contain transparent background and visible pixels. Regenerate on an explicit solid background and use solid_background; a checkerboard is not transparency."
            }
        }
        require(image.width > 0 && image.height > 0) { "PNG dimensions must be positive" }
		val placement = if (request.referenceId != null) AgentCanvasPlacement(spatial.coordinateSpace, spatial.viewRect, image.width, image.height,
            spatial.viewRect.width / image.width, spatial.viewRect.height / image.height, request.spatialReferenceId) else spatial.placementForGeneratedPng(
			sourceViewId = request.spatialReferenceId,
			imagePixelWidth = image.width,
			imagePixelHeight = image.height,
			sourcePixelRect = request.sourcePixelRect,
		)
		val rgba = image.toRgba()
		val digest = sha256(if (request.solidBackground == null) request.png else java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
		val placementKey = listOf(
			placement.canvasRect.left,
			placement.canvasRect.top,
			placement.canvasRect.right,
			placement.canvasRect.bottom,
		).joinToString(":")
		val details = if (request.referenceId == null) kotlinx.serialization.json.JsonObject(emptyMap()) else kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(2)); put("reference_id", kotlinx.serialization.json.JsonPrimitive(request.referenceId))
            put("registration_required", kotlinx.serialization.json.JsonPrimitive(true)); put("processing", request.processing)
            put("diagnostics", matte!!.diagnostics); put("raw_sha256", kotlinx.serialization.json.JsonPrimitive(sha256(request.png)))
        }
        val id = "asset-${sha256("$digest|$placementKey|${placement.sourceViewId}|$details".encodeToByteArray()).take(24)}"
		val imported = AgentImportedPngAsset(id, digest, image.width, image.height, placement, details)
		assets.putIfAbsent(id, AgentPngAsset(imported, rgba, request.png.copyOf()))
		return assets.getValue(id).public
	}

	fun require(assetId: String): AgentPngAsset =
		assets[assetId] ?: throw IllegalArgumentException("PNG asset not found: $assetId")

	fun find(assetId: String): AgentPngAsset? = assets[assetId]
    fun clear() { assets.clear() }

	fun remember(asset: AgentPngAsset): AgentPngAsset = asset.also { assets.putIfAbsent(it.public.id, it) }

	private companion object {
		val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
	}
}

internal fun AgentWorkspaceDocument.addLayer(
	asset: AgentPngAsset,
	request: AgentAddLayerRequest,
): Pair<AgentWorkspaceDocument, String> {
	val rawId = request.layerId?.trim().orEmpty().ifEmpty { "agent:${UUID.randomUUID()}" }
	require(rawId.none(Char::isISOControl)) { "Layer ID contains control characters" }
	require(source.layers.none { it.id.raw == rawId }) { "Layer ID already exists: $rawId" }
	val name = request.name.trim()
	require(name.isNotEmpty()) { "Layer name must not be blank" }
	require(request.opacity.isFinite() && request.opacity in 0f..1f) { "Layer opacity must be within 0..1" }
	val tag = enumValue<SemanticTag>(request.semanticTag, "semantic_tag")
	val side = enumValue<Side>(request.side, "side")
	val normalized = normalizeAssetRaster(asset, request.trimTransparent)
	val added = WorkspaceSourceLayer(
		id = LayerId(rawId),
		name = name,
		groupPath = request.groupPath.trim('/'),
		kind = SourceLayerKind.Raster,
		visible = request.visible,
		order = 0,
		bounds = normalized.bounds,
		opacity = request.opacity,
		clipped = false,
		blend = LayerBlend.Normal,
		channelMask = ChannelMask.ALL,
		raster = normalized.raster,
		sourceAssetId = asset.public.id,
		sourceSpatialReferenceId = asset.public.placement.sourceViewId,
		derived = true,
	)
	val painterOrder = source.layers.toMutableList()
	val insertionIndex = when (val insertion = request.insertion) {
		AgentLayerInsertion.Top -> painterOrder.size
		AgentLayerInsertion.Bottom -> 0
		is AgentLayerInsertion.Above -> painterOrder.anchorIndex(insertion.layerId) + 1
		is AgentLayerInsertion.Below -> painterOrder.anchorIndex(insertion.layerId)
	}
	painterOrder.add(insertionIndex, added)
	val ordered = painterOrder.mapIndexed { index, layer ->
		WorkspaceSourceLayer.copyOf(layer, order = painterOrder.lastIndex - index)
	}
	val nextSource = WorkspaceSourceArt(source.widthPx, source.heightPx, ordered, source.groups.toList())
	val override = LayerClassificationOverride(
		type = LayerType.PRESET,
		tag = tag,
		side = side,
	)
	val nextParents = if (request.parentDeformerId == null) parentOverrides else parentOverrides + (rawId to request.parentDeformerId)
	return copy(
		source = nextSource,
		layerVisibility = layerVisibility + (rawId to request.visible),
		deletedLayerIds = deletedLayerIds - rawId,
		layerOverrides = layerOverrides + (rawId to override),
		parentOverrides = nextParents,
	) to rawId
}

private fun MutableList<SourceLayer>.anchorIndex(layerId: String): Int {
	indexOfFirst { it.id.raw == layerId }.takeIf { it >= 0 }?.let { return it }
	val baseId = layerId.removeSuffix(":l").removeSuffix(":r")
	return indexOfFirst { it.id.raw == baseId }.takeIf { it >= 0 }
		?: throw IllegalArgumentException("Insertion anchor layer not found: $layerId")
}

internal fun AgentWorkspaceDocument.replacePlacedLayer(layerId: String, asset: AgentPngAsset): AgentWorkspaceDocument {
    val normalized = normalizeAssetRaster(asset, true)
    val next = source.layers.map { layer ->
        if (layer.id.raw != layerId) layer else (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
            bounds = normalized.bounds, raster = normalized.raster)
    }
    require(next != source.layers) { "Placement does not change layer pixels or bounds" }
    return copy(source = WorkspaceSourceArt(source.widthPx, source.heightPx, next, source.groups))
}

private data class NormalizedRaster(val bounds: LayerBounds, val raster: LayerRaster)

/** Converts arbitrary generated resolution back to canonical canvas units before source ingestion. */
private fun normalizeAssetRaster(asset: AgentPngAsset, trimTransparent: Boolean): NormalizedRaster {
	val rect = asset.public.placement.canvasRect
	val left = kotlin.math.round(rect.left).toInt()
	val top = kotlin.math.round(rect.top).toInt()
	val width = (kotlin.math.round(rect.right).toInt() - left).coerceAtLeast(1)
	val height = (kotlin.math.round(rect.bottom).toInt() - top).coerceAtLeast(1)
	val sourceImage = rgbaImage(asset.public.pixelWidth, asset.public.pixelHeight, asset.rgba)
	// Premultiplied interpolation prevents dark/coloured fringes around transparent painted edges.
	val normalized = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
	normalized.createGraphics().use { graphics ->
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
		graphics.drawImage(sourceImage, 0, 0, width, height, null)
	}
	val alphaBounds = (if (trimTransparent) normalized.alphaBounds() else Bounds(0f, 0f, width.toFloat(), height.toFloat()))
		?: throw IllegalArgumentException("Generated PNG is fully transparent")
	val cropLeft = alphaBounds.left.toInt()
	val cropTop = alphaBounds.top.toInt()
	val cropWidth = alphaBounds.width.toInt()
	val cropHeight = alphaBounds.height.toInt()
	val cropped = normalized.getSubimage(cropLeft, cropTop, cropWidth, cropHeight)
	return NormalizedRaster(
		bounds = LayerBounds(left + cropLeft, top + cropTop, cropWidth, cropHeight),
		raster = LayerRaster(cropWidth, cropHeight, cropped.toRgba()),
	)
}

private fun BufferedImage.alphaBounds(): Bounds? {
	var minX = width
	var minY = height
	var maxX = -1
	var maxY = -1
	for (y in 0 until height) for (x in 0 until width) {
		if ((getRGB(x, y) ushr 24) == 0) continue
		minX = minOf(minX, x)
		minY = minOf(minY, y)
		maxX = maxOf(maxX, x)
		maxY = maxOf(maxY, y)
	}
	return if (maxX < minX || maxY < minY) null else Bounds(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
}

private fun BufferedImage.toRgba(): ByteArray {
	val rgba = ByteArray(width * height * 4)
	var offset = 0
	for (y in 0 until height) for (x in 0 until width) {
		val argb = getRGB(x, y)
		rgba[offset++] = (argb ushr 16).toByte()
		rgba[offset++] = (argb ushr 8).toByte()
		rgba[offset++] = argb.toByte()
		rgba[offset++] = (argb ushr 24).toByte()
	}
	return rgba
}

private fun rgbaImage(width: Int, height: Int, rgba: ByteArray): BufferedImage {
	require(rgba.size == width * height * 4) { "Invalid RGBA buffer length" }
	val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
	val argb = IntArray(width * height)
	for (index in argb.indices) {
		val offset = index * 4
		argb[index] = ((rgba[offset + 3].toInt() and 0xff) shl 24) or
			((rgba[offset].toInt() and 0xff) shl 16) or
			((rgba[offset + 1].toInt() and 0xff) shl 8) or
			(rgba[offset + 2].toInt() and 0xff)
	}
	image.setRGB(0, 0, width, height, argb, 0, width)
	return image
}

private inline fun <reified T : Enum<T>> enumValue(raw: String, field: String): T =
	runCatching { enumValueOf<T>(raw.trim().uppercase()) }
		.getOrElse { throw IllegalArgumentException("Unknown $field: $raw") }

internal data class WorkspaceSourceArt(
	override val widthPx: Int,
	override val heightPx: Int,
	override val layers: List<SourceLayer>,
	override val groups: List<SourceGroup>,
) : SourceArt

internal data class WorkspaceSourceLayer(
	override val id: LayerId,
	override val name: String,
	override val groupPath: String,
	override val kind: SourceLayerKind,
	override val visible: Boolean,
	override val order: Int,
	override val bounds: LayerBounds,
	override val opacity: Float,
	override val clipped: Boolean,
	override val blend: LayerBlend,
	override val channelMask: ChannelMask,
	override val raster: LayerRaster,
	override val sourceAssetId: String?,
	override val sourceSpatialReferenceId: String?,
	override val derived: Boolean,
) : AgentWorkspaceSourceLayer {
	companion object {
		fun copyOf(layer: SourceLayer, order: Int): SourceLayer = WorkspaceSourceLayer(
			id = layer.id,
			name = layer.name,
			groupPath = layer.groupPath,
			kind = layer.kind,
			visible = layer.visible,
			order = order,
			bounds = layer.bounds,
			opacity = layer.opacity,
			clipped = layer.clipped,
			blend = layer.blend,
			channelMask = layer.channelMask,
			raster = layer.raster,
			sourceAssetId = (layer as? AgentWorkspaceSourceLayer)?.sourceAssetId,
			sourceSpatialReferenceId = (layer as? AgentWorkspaceSourceLayer)?.sourceSpatialReferenceId,
			derived = (layer as? AgentWorkspaceSourceLayer)?.derived == true,
		)
	}
}

private fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
	try { block(this) } finally { dispose() }
}

internal fun decodePngBase64(raw: String): ByteArray {
	val payload = raw.substringAfter("base64,", raw).filterNot(Char::isWhitespace)
	return runCatching { Base64.getDecoder().decode(payload) }
		.getOrElse { throw IllegalArgumentException("png_base64 is not valid Base64") }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
	.digest(bytes)
	.joinToString("") { "%02x".format(it.toInt() and 0xff) }
