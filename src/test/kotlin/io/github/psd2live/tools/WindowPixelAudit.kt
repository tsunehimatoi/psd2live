package io.github.psd2live.tools

import io.github.psd2live.util.PresentationCamera
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.floor

/** Synthetic fixture oracle in source coordinates; samples the final window, including its clipping/overlays. */
internal object WindowPixelAudit {
    fun color(ordinal: Int, tile: Int): Int = (255 shl 24) or
        ((40 + (ordinal * 29 + tile * 43) % 190) shl 16) or
        ((40 + (ordinal * 47 + tile * 17) % 190) shl 8) or
        (40 + (ordinal * 13 + tile * 61) % 190)

    fun verify(ordinal: Int, camera: PresentationCamera, left: Float, top: Float,
               width: Int, height: Int, pixel: (Int, Int) -> Int): JsonArray = buildJsonArray {
        val scale = camera.scale * minOf(camera.width, camera.height) / 64f
        check(scale.isFinite() && scale > 0 && left.isFinite() && top.isFinite()) { "Invalid window placement" }
        for (tile in 0..3) {
            val localX = camera.width * .5f * (1 + camera.offsetX) + (tile % 2 * 32 + 16 - 32) * scale
            val localY = camera.height * .5f * (1 - camera.offsetY) + (tile / 2 * 32 + 16 - 32) * scale
            val x = floor(left + localX).toInt()
            val y = floor(top + localY).toInt()
            val expected = color(ordinal, tile)
            var previousMatches = false
            for (dy in -1..1) for (dx in -1..1) {
                val px = x + dx; val py = y + dy
                check(px >= left && px < left + camera.width && py >= top && py < top + camera.height &&
                    px in 0 until width && py in 0 until height) { "Tile $tile patch is clipped by viewport/window" }
                val actual = pixel(px, py)
                check(matches(expected, actual)) {
                    "Tile $tile ($px,$py): expected ${hex(expected)}, actual ${hex(actual)}, ordinal=$ordinal"
                }
                previousMatches = previousMatches || matches(color(ordinal - 1, tile), actual)
            }
            check(!previousMatches) { "Tile $tile failed previous-revision negative control" }
            add(buildJsonObject {
                put("tile", tile); put("x", x); put("y", y); put("patchPixels", 9)
                put("expectedArgb", hex(expected)); put("actualCenterArgb", hex(pixel(x, y)))
                put("previousRevisionArgb", hex(color(ordinal - 1, tile))); put("previousRevisionRejected", true)
            })
        }
    }

    private fun matches(a: Int, b: Int) = listOf(0, 8, 16, 24).all { abs((a ushr it and 255) - (b ushr it and 255)) <= 2 }
    private fun hex(color: Int) = "%08x".format(color)
}
