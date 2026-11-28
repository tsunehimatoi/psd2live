package io.github.psd2live.algorithms.shadow

import io.github.psd2live.core.modifier.ModifierAlgorithmContext
import io.github.psd2live.format.model.RigIR
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Collections
import java.util.HexFormat
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ShadowRasterResult(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val padLeftPx: Float,
    val padTopPx: Float,
    val padRightPx: Float,
    val padBottomPx: Float,
    val digest: String,
)

internal object ShadowRasterGen {
    private data class CacheKey(
        val sourceDigest: String,
        val blurPx: Int,
        val rgb: Int,
        val downscale: Int,
    )

    private val cache: MutableMap<CacheKey, ShadowRasterResult> = Collections.synchronizedMap(
        object : LinkedHashMap<CacheKey, ShadowRasterResult>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ShadowRasterResult>?): Boolean = size > 128
        }
    )

    fun parseHexRgb(hex: String, fallback: Int = 0x5A4658): Int {
        val clean = hex.trim().removePrefix("#")
        if (clean.length != 6) return fallback
        return clean.toIntOrNull(16) ?: fallback
    }

    fun formatHexRgb(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

    /** Derive a clean, non-muddy anime shadow color from opaque pixels of the receiver skin. */
    fun deriveShadowColorRgb(rgba: ByteArray): Int {
        if (rgba.size < 4) return 0xC8A29C
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        val step = max(1, (rgba.size / 4) / 2048) * 4
        var i = 0
        while (i + 3 < rgba.size) {
            val a = rgba[i + 3].toInt() and 0xFF
            if (a >= 128) {
                sumR += (rgba[i].toInt() and 0xFF) / 255.0
                sumG += (rgba[i + 1].toInt() and 0xFF) / 255.0
                sumB += (rgba[i + 2].toInt() and 0xFF) / 255.0
                count++
            }
            i += step
        }
        if (count == 0) return 0xC8A29C
        val r = (sumR / count).toFloat()
        val g = (sumG / count).toFloat()
        val b = (sumB / count).toFloat()
        // Multiply-friendly shadow tint: keep high luminance enough for Multiply not to turn muddy,
        // shift slightly cooler/violet-rose so skin shadows stay vibrant.
        val outR = (r * 0.78f + 0.04f).coerceIn(0.15f, 0.92f)
        val outG = (g * 0.64f + 0.02f).coerceIn(0.12f, 0.88f)
        val outB = (b * 0.72f + 0.06f).coerceIn(0.18f, 0.92f)
        return ((outR * 255f).roundToInt() shl 16) or
            ((outG * 255f).roundToInt() shl 8) or
            (outB * 255f).roundToInt()
    }

    /** Look up source raster RGBA from ModifierAlgorithmContext resources, falling back to RigIR's tileArt. */
    fun findMeshRgba(
        rig: RigIR,
        meshId: String,
        context: ModifierAlgorithmContext = ModifierAlgorithmContext.Empty,
    ): Pair<Int, Pair<Int, ByteArray>>? {
        val meta = context.values["shadow.rasters"]?.jsonObject?.get(meshId)?.jsonObject
        val res = context.resources["shadow.rgba:$meshId"]
        if (meta != null && res != null) {
            val w = meta["w"]?.jsonPrimitive?.int ?: 0
            val h = meta["h"]?.jsonPrimitive?.int ?: 0
            if (w > 0 && h > 0) return w to (h to res.read())
        }
        val mesh = rig.meshes.firstOrNull { it.id == meshId } ?: return null
        val tileId = mesh.tile ?: return null
        val tileArt = rig.textures.tileArt.firstOrNull { it.tile == tileId } ?: return null
        if (tileArt.width <= 0 || tileArt.height <= 0) return null
        return tileArt.width to (tileArt.height to tileArt.rgba.toArray())
    }

    fun generateSolidShadow(
        srcWidth: Int,
        srcHeight: Int,
        srcRgba: ByteArray,
        blurPx: Float,
        rgb: Int,
        downscale: Int = 2,
    ): ShadowRasterResult {
        val safeScale = downscale.coerceIn(1, 4)
        val quantBlur = (blurPx.coerceIn(0f, 48f) * 2f).roundToInt()
        val srcDigest = sha256(srcRgba)
        val key = CacheKey(srcDigest, quantBlur, rgb and 0xFFFFFF, safeScale)
        cache[key]?.let { return it }

        val blurScaled = (blurPx / safeScale).coerceAtLeast(0f)
        val padScaled = if (blurScaled <= 0.1f) 2 else ceil(blurScaled * 2f).toInt() + 2
        val dw = max(1, (srcWidth + safeScale - 1) / safeScale)
        val dh = max(1, (srcHeight + safeScale - 1) / safeScale)
        val outW = dw + padScaled * 2
        val outH = dh + padScaled * 2
        val alpha = FloatArray(outW * outH)

        for (y in 0 until dh) {
            val sy0 = y * safeScale
            val sy1 = min(srcHeight, sy0 + safeScale)
            for (x in 0 until dw) {
                val sx0 = x * safeScale
                val sx1 = min(srcWidth, sx0 + safeScale)
                var sum = 0f
                var cnt = 0
                for (sy in sy0 until sy1) {
                    val row = sy * srcWidth
                    for (sx in sx0 until sx1) {
                        sum += (srcRgba[(row + sx) * 4 + 3].toInt() and 0xFF) / 255f
                        cnt++
                    }
                }
                alpha[(y + padScaled) * outW + (x + padScaled)] = if (cnt > 0) sum / cnt else 0f
            }
        }

        val blurred = if (blurScaled > 0.25f) gaussianBlur(alpha, outW, outH, blurScaled) else alpha
        val rByte = ((rgb shr 16) and 0xFF).toByte()
        val gByte = ((rgb shr 8) and 0xFF).toByte()
        val bByte = (rgb and 0xFF).toByte()
        val outRgba = ByteArray(outW * outH * 4)
        for (i in 0 until outW * outH) {
            val a = (blurred[i].coerceIn(0f, 1f) * 255f).roundToInt().toByte()
            val o = i * 4
            outRgba[o] = rByte
            outRgba[o + 1] = gByte
            outRgba[o + 2] = bByte
            outRgba[o + 3] = a
        }

        val padSrcPx = (padScaled * safeScale).toFloat()
        val result = ShadowRasterResult(
            width = outW,
            height = outH,
            rgba = outRgba,
            padLeftPx = padSrcPx,
            padTopPx = padSrcPx,
            padRightPx = padSrcPx,
            padBottomPx = padSrcPx,
            digest = sha256(outRgba),
        )
        cache[key] = result
        return result
    }

    /** Simple 1x1-style solid color swatch (for form shadow mesh using target's own silhouette). */
    fun generateFlatColorSwatch(width: Int, height: Int, rgb: Int): ByteArray {
        val w = width.coerceAtLeast(4)
        val h = height.coerceAtLeast(4)
        val rByte = ((rgb shr 16) and 0xFF).toByte()
        val gByte = ((rgb shr 8) and 0xFF).toByte()
        val bByte = (rgb and 0xFF).toByte()
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val o = i * 4
            out[o] = rByte
            out[o + 1] = gByte
            out[o + 2] = bByte
            out[o + 3] = 255.toByte()
        }
        return out
    }

    private fun gaussianBlur(src: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        val radius = ceil(sigma * 2.2f).toInt().coerceIn(1, 32)
        val kernel = FloatArray(radius * 2 + 1)
        val denom = 2f * sigma * sigma
        var kSum = 0f
        for (k in -radius..radius) {
            val v = exp(-(k * k) / denom)
            kernel[k + radius] = v
            kSum += v
        }
        for (i in kernel.indices) kernel[i] /= kSum

        val tmp = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    acc += src[row + sx] * kernel[k + radius]
                }
                tmp[row + x] = acc
            }
        }
        val dst = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var acc = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    acc += tmp[sy * width + x] * kernel[k + radius]
                }
                dst[y * width + x] = acc
            }
        }
        return dst
    }

    fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
