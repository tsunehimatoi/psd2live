package io.github.psd2live.application

import io.github.psd2live.core.quality.*

import io.github.psd2live.project.WorkspaceAddLayerRequest
import io.github.psd2live.project.WorkspaceCanvasPlacement
import io.github.psd2live.project.WorkspaceImportedPngAsset
import io.github.psd2live.project.WorkspaceLayerInsertion
import io.github.psd2live.project.WorkspacePngAsset
import io.github.psd2live.project.WorkspacePngImportRequest
import io.github.psd2live.project.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceViewSpatialMetadata
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.placementForGeneratedPng

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

internal class WorkspacePngAssetStore(private val checkCancelled: () -> Unit = {}) {
	private val assets = ConcurrentHashMap<String, WorkspacePngAsset>()

	fun import(request: WorkspacePngImportRequest, spatial: WorkspaceViewSpatialMetadata): WorkspaceImportedPngAsset {
		require(request.png.size >= PNG_SIGNATURE.size && request.png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
			"asset import accepts PNG data only"
		}
		checkCancelled()
        val decoded = javax.imageio.stream.MemoryCacheImageInputStream(request.png.inputStream()).use { input ->
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "The supplied bytes are not a decodable PNG" }
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216) { "PNG exceeds 16 megapixels" }
                checkCancelled()
                reader.read(0)
            } finally { reader.dispose() }
        }
        checkCancelled()
        val matte = if (request.referenceId != null && request.solidBackground != null) processGeneratedMatte(decoded,
            request.solidBackground, request.backgroundTolerance, request.processing, checkCancelled) else null
        val image = matte?.image ?: request.solidBackground?.let { cleanGeneratedMatte(decoded, it, request.backgroundTolerance, checkCancelled) } ?: decoded
        if (request.requireTransparency) {
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            require(pixels.any { it ushr 24 == 0 } && pixels.any { it ushr 24 > 0 }) {
                "Asset needs transparent background and visible pixels. Supply native alpha or an explicit solid_background for matte removal; a checkerboard is not transparency."
            }
        }
        require(image.width > 0 && image.height > 0) { "PNG dimensions must be positive" }
		val placement = if (request.referenceId != null) WorkspaceCanvasPlacement(spatial.coordinateSpace, spatial.viewRect, image.width, image.height,
            spatial.viewRect.width / image.width, spatial.viewRect.height / image.height, request.spatialReferenceId) else spatial.placementForGeneratedPng(
			sourceViewId = request.spatialReferenceId,
			imagePixelWidth = image.width,
			imagePixelHeight = image.height,
			sourcePixelRect = request.sourcePixelRect,
		)
		val rgba = image.toRgba(checkCancelled)
		val digest = sha256(if (request.solidBackground == null) request.png else java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
		val placementKey = listOf(
			placement.canvasRect.left,
			placement.canvasRect.top,
			placement.canvasRect.right,
			placement.canvasRect.bottom,
		).joinToString(":")
		val details = if (request.referenceId == null) kotlinx.serialization.json.JsonObject(emptyMap()) else kotlinx.serialization.json.buildJsonObject {
            put("version", kotlinx.serialization.json.JsonPrimitive(2)); put("reference_id", kotlinx.serialization.json.JsonPrimitive(request.referenceId))
            put("solid_background", kotlinx.serialization.json.JsonPrimitive(request.solidBackground)); put("background_tolerance", kotlinx.serialization.json.JsonPrimitive(request.backgroundTolerance))
            put("registration_required", kotlinx.serialization.json.JsonPrimitive(true)); put("processing", request.processing)
            put("diagnostics", matte?.diagnostics ?: kotlinx.serialization.json.buildJsonObject { put("mode", kotlinx.serialization.json.JsonPrimitive("native_alpha")); put("quality", QualityInspection.combine(QualityFence.OBSERVATION,
                listOf(QualityCheckResult("asset.native_alpha", "Supplied alpha channel; no matte removal", emptyList()))).toJson()) }); put("raw_sha256", kotlinx.serialization.json.JsonPrimitive(sha256(request.png)))
            var left=image.width; var top=image.height; var right=0; var bottom=0
            for (i in 0 until image.width*image.height) {
                if (i % 4096 == 0) checkCancelled()
                if ((rgba[i*4+3].toInt() and 255)>0) {
                left=minOf(left,i%image.width);top=minOf(top,i/image.width)
                right=maxOf(right,i%image.width+1);bottom=maxOf(bottom,i/image.width+1)
                }
            }
            if (right>left && bottom>top) put("content_pixel_rect", Bounds(left.toFloat(),top.toFloat(),right.toFloat(),bottom.toFloat()).json())
        }
        val id = "asset-${sha256("$digest|$placementKey|${placement.sourceViewId}|$details".encodeToByteArray()).take(24)}"
		val imported = WorkspaceImportedPngAsset(id, digest, image.width, image.height, placement, details)
		checkCancelled()
		assets.putIfAbsent(id, WorkspacePngAsset(imported, rgba, request.png.copyOf()))
		return assets.getValue(id).public
	}

	fun require(assetId: String): WorkspacePngAsset =
		assets[assetId] ?: throw IllegalArgumentException("PNG asset not found: $assetId")

	fun find(assetId: String): WorkspacePngAsset? = assets[assetId]
    fun clear() { assets.clear() }

	fun remember(asset: WorkspacePngAsset): WorkspacePngAsset = asset.also { assets.putIfAbsent(it.public.id, it) }

	private companion object {
		val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
	}
}

internal fun WorkspaceDocument.addLayer(
	asset: WorkspacePngAsset,
	request: WorkspaceAddLayerRequest,
    checkCancelled: () -> Unit = {},
): Pair<WorkspaceDocument, String> {
	val rawId = request.layerId?.trim().orEmpty().ifEmpty { "agent:${UUID.randomUUID()}" }
	require(rawId.none(Char::isISOControl)) { "Layer ID contains control characters" }
	require(source.layers.none { it.id.raw == rawId }) { "Layer ID already exists: $rawId" }
	val name = request.name.trim()
	require(name.isNotEmpty()) { "Layer name must not be blank" }
	require(request.opacity.isFinite() && request.opacity in 0f..1f) { "Layer opacity must be within 0..1" }
	val tag = enumValue<SemanticTag>(request.semanticTag, "semantic_tag")
	val side = enumValue<Side>(request.side, "side")
	val normalized = normalizeAssetRaster(asset, request.trimTransparent, checkCancelled)
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
		WorkspaceLayerInsertion.Top -> painterOrder.size
		WorkspaceLayerInsertion.Bottom -> 0
		is WorkspaceLayerInsertion.Above -> painterOrder.anchorIndex(insertion.layerId) + 1
		is WorkspaceLayerInsertion.Below -> painterOrder.anchorIndex(insertion.layerId)
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

internal fun WorkspaceDocument.replacePlacedLayer(layerId: String, asset: WorkspacePngAsset, checkCancelled: () -> Unit = {}): WorkspaceDocument {
    val normalized = normalizeAssetRaster(asset, true, checkCancelled)
    val next = source.layers.map { layer ->
        if (layer.id.raw != layerId) layer else (WorkspaceSourceLayer.copyOf(layer, layer.order) as WorkspaceSourceLayer).copy(
            bounds = normalized.bounds, raster = normalized.raster)
    }
    return copy(source = WorkspaceSourceArt(source.widthPx, source.heightPx, next, source.groups))
}

private data class NormalizedRaster(val bounds: LayerBounds, val raster: LayerRaster)

/** Converts arbitrary generated resolution back to canonical canvas units before source ingestion. */
private fun normalizeAssetRaster(asset: WorkspacePngAsset, trimTransparent: Boolean, checkCancelled: () -> Unit): NormalizedRaster {
    checkCancelled()
	val rect = asset.public.placement.canvasRect
    require(listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() &&
        it.toDouble() >= Int.MIN_VALUE.toDouble() && it.toDouble() <= Int.MAX_VALUE.toDouble() }) { "Asset placement is outside the canvas coordinate range" }
	val left = kotlin.math.round(rect.left).toInt()
	val top = kotlin.math.round(rect.top).toInt()
    val widthLong = (kotlin.math.round(rect.right).toLong() - left).coerceAtLeast(1)
    val heightLong = (kotlin.math.round(rect.bottom).toLong() - top).coerceAtLeast(1)
    require(widthLong in 1..16_777_216 && heightLong in 1..16_777_216 && widthLong * heightLong <= 16_777_216) { "Asset placement exceeds 16 megapixels" }
    val width = widthLong.toInt(); val height = heightLong.toInt()
	val sourceImage = rgbaImage(asset.public.pixelWidth, asset.public.pixelHeight, asset.rgba, checkCancelled)
	// Premultiplied interpolation prevents dark/coloured fringes around transparent painted edges.
	val normalized = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
	normalized.createGraphics().use { graphics ->
		graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
		graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
		graphics.drawImage(sourceImage, 0, 0, width, height, null)
	}
    checkCancelled()
	val alphaBounds = (if (trimTransparent) normalized.alphaBounds(checkCancelled) else Bounds(0f, 0f, width.toFloat(), height.toFloat()))
		?: throw IllegalArgumentException("Generated PNG is fully transparent")
	val cropLeft = alphaBounds.left.toInt()
	val cropTop = alphaBounds.top.toInt()
	val cropWidth = alphaBounds.width.toInt()
	val cropHeight = alphaBounds.height.toInt()
	val cropped = normalized.getSubimage(cropLeft, cropTop, cropWidth, cropHeight)
	return NormalizedRaster(
		bounds = LayerBounds(left + cropLeft, top + cropTop, cropWidth, cropHeight),
		raster = LayerRaster(cropWidth, cropHeight, cropped.toRgba(checkCancelled)),
	)
}

private fun BufferedImage.alphaBounds(checkCancelled: () -> Unit): Bounds? {
	var minX = width
	var minY = height
	var maxX = -1
	var maxY = -1
	for (y in 0 until height) for (x in 0 until width) {
        if (x == 0) checkCancelled()
		if ((getRGB(x, y) ushr 24) == 0) continue
		minX = minOf(minX, x)
		minY = minOf(minY, y)
		maxX = maxOf(maxX, x)
		maxY = maxOf(maxY, y)
	}
	return if (maxX < minX || maxY < minY) null else Bounds(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
}

private fun BufferedImage.toRgba(checkCancelled: () -> Unit = {}): ByteArray {
	val rgba = ByteArray(width * height * 4)
	var offset = 0
	for (y in 0 until height) for (x in 0 until width) {
        if (x == 0) checkCancelled()
		val argb = getRGB(x, y)
		rgba[offset++] = (argb ushr 16).toByte()
		rgba[offset++] = (argb ushr 8).toByte()
		rgba[offset++] = argb.toByte()
		rgba[offset++] = (argb ushr 24).toByte()
	}
	return rgba
}

private fun rgbaImage(width: Int, height: Int, rgba: ByteArray, checkCancelled: () -> Unit): BufferedImage {
	require(rgba.size == width * height * 4) { "Invalid RGBA buffer length" }
	val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
	val argb = IntArray(width * height)
	for (index in argb.indices) {
        if (index % width == 0) checkCancelled()
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
