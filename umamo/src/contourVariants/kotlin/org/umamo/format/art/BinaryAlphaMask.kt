package org.umamo.format.art

/** Shared binary coverage for contour tracing, wrapping and mesh sampling. */
public class BinaryAlphaMask(public val width: Int, public val height: Int) {
    private val words = LongArray(((width.toLong() * height + 63L) / 64L).toInt())
    private val mask = AlphaMask(width, height, words)
    private var left = width
    private var top = height
    private var right = -1
    private var bottom = -1

    public fun add(x: Int, y: Int) {
        require(x in 0 until width && y in 0 until height)
        val index = y * width + x
        words[index ushr 6] = words[index ushr 6] or (1L shl (index and 63))
        if (x < left) left = x
        if (x > right) right = x
        if (y < top) top = y
        if (y > bottom) bottom = y
    }

    public fun isOpaque(x: Int, y: Int): Boolean = mask.isOpaque(x, y)

    public fun contours(): List<AlphaContour> =
        if (right < left) emptyList()
        else traceAlphaContours(mask, LayerBounds(left, top, right - left + 1, bottom - top + 1))
}
