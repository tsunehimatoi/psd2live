package io.github.psd2live.core.sim

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.format.art.LayerRaster
import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Where a garment hangs loose, read from its art; everything else is worn tight and follows the rig.
 *
 * Clothing on the torso and hips is walked row by row, top to bottom, against the body it wraps:
 * - The body column starts at the garment's outline below the shoulders (or at the waist for bottoms)
 *   and may widen downward only slowly, as a body does; cloth standing out of it by more than a tolerance
 *   is loose: ruffles, bows, flared hems, coat flaps.
 * - Cloth with nothing underneath hangs: a top below the waist (apron, coat tails, peplum), a skirt below
 *   the hips once it is wider than them, neckwear below the knot.
 *
 * Sleeves and legwear bend with their limb, so rows mean nothing there. The limb is what survives an
 * opening with a disk half its own thickness; what the opening strips off is loose: cuff and sock
 * ruffles, ribbons. Puffed shoulders and turned-up cuffs are as thick as the limb and stay with it.
 *
 * Skin is body, never loose, so a hand drawn on a sleeve stays put however its fingers spread. Looseness
 * then grows with the distance from the supported cloth, measured inside the garment, so a ruffle's edge
 * flutters less than the far end of a long coat flap.
 */
object ClothFit {
    enum class Wear(val jsonName: String) {
        TOP("top"), SKIRT("skirt"), TROUSERS("trousers"), NECKWEAR("neckwear"), SLEEVE("sleeve"), LEGWEAR("legwear");

        /** Worn on a limb that bends, rather than hanging from the torso or hips. */
        val onLimb: Boolean get() = this == SLEEVE || this == LEGWEAR
    }

    /** Looseness per cell of a garment's grid, 0 (worn tight) to 1 (hanging free), filled out to every cell. */
    class Field internal constructor(
        val wear: Wear,
        private val left: Float,
        private val top: Float,
        private val cell: Float,
        private val columns: Int,
        private val rows: Int,
        private val values: FloatArray,
        /** Share of the garment's cloth that is mostly loose (looseness above 0.5). */
        val looseShare: Float,
        /** Canvas y below which the cloth hangs, when it does. */
        val hangFrom: Float?,
    ) {
        /** Looseness at canvas ([x], [y]); outside the art it is that of the nearest cloth. */
        fun at(x: Float, y: Float): Float {
            val gx = ((x - left) / cell).toInt().coerceIn(0, columns - 1)
            val gy = ((y - top) / cell).toInt().coerceIn(0, rows - 1)
            return values[gy * columns + gx]
        }

        fun toJson() = buildJsonObject {
            put("loose", kotlin.math.round(looseShare * 100f) / 100f)
            hangFrom?.let { put("hang_from", kotlin.math.round(it * 10f) / 10f) }
        }
    }

    /** How fast the body column may widen per row down the garment; a body widens only at the hips. */
    private const val WIDEN_DOWN = 0.04f
    /** How fast it may widen per row up the garment, as a torso does from the waist to the shoulders. */
    private const val WIDEN_UP = 0.6f
    /** Grid cells along the garment's longer side; the art is read at this resolution. */
    private const val GRID = 240

    /**
     * The looseness of [raster], placed at ([rasterLeft], [rasterTop]) on the canvas and worn as [wear].
     * [waist] is the canvas y a top stops resting on the torso at, or a skirt or trousers start at.
     */
    fun analyze(
        raster: LayerRaster,
        rasterLeft: Float,
        rasterTop: Float,
        wear: Wear,
        waist: Float? = null,
        alphaThreshold: Int = 8,
    ): Field {
        // Read coarsely over the whole raster to find the garment, then finely over just the garment.
        var grid = Grid.read(raster, 0, 0, raster.width, raster.height, alphaThreshold)
        val box = requireNotNull(grid.opaqueBox()) { "Garment has no opaque pixels" }
        if (box[2] - box[0] < raster.width || box[3] - box[1] < raster.height) grid = Grid.read(raster, box[0], box[1], box[2], box[3], alphaThreshold)
        require(grid.mask.any { it }) { "Garment has no opaque pixels" }
        val left = rasterLeft + grid.x0
        val top = rasterTop + grid.y0

        val (supported, fade, hangRow) = if (wear.onLimb) limbSupport(grid) else rowSupport(grid, wear, waist?.let { ((it - top) / grid.cell).toInt() })
        for (c in supported.indices) supported[c] = supported[c] || grid.skin[c]

        // Distance from the supported cloth through the garment, eased into a looseness.
        val columns = grid.columns
        val rows = grid.rows
        val mask = grid.mask
        val distance = FloatArray(columns * rows) { Float.MAX_VALUE }
        val queue = PriorityQueue<Pair<Float, Int>>(compareBy { it.first })
        for (c in supported.indices) if (supported[c]) { distance[c] = 0f; queue += 0f to c }
        val diagonal = sqrt(2f)
        while (queue.isNotEmpty()) {
            val (d, c) = queue.poll()
            if (d > distance[c]) continue
            for (oy in -1..1) for (ox in -1..1) {
                if (ox == 0 && oy == 0) continue
                val x = c % columns + ox
                val y = c / columns + oy
                if (x !in 0 until columns || y !in 0 until rows) continue
                val j = y * columns + x
                if (!mask[j]) continue
                val next = d + if (ox != 0 && oy != 0) diagonal else 1f
                if (next < distance[j]) { distance[j] = next; queue += next to j }
            }
        }
        val values = FloatArray(columns * rows) { c ->
            when {
                !mask[c] -> Float.NaN
                distance[c] == Float.MAX_VALUE -> 1f
                else -> smoothstep(0f, fade, distance[c])
            }
        }
        val looseShare = mask.indices.count { mask[it] && values[it] > 0.5f }.toFloat() / mask.count { it }

        // Out from the cloth to every empty cell, by nearest cloth, for vertices off the art's edge.
        val fill = ArrayDeque<Int>()
        for (c in values.indices) if (!values[c].isNaN()) fill += c
        while (fill.isNotEmpty()) {
            val c = fill.removeFirst()
            for ((ox, oy) in NEIGHBOURS) {
                val x = c % columns + ox
                val y = c / columns + oy
                if (x !in 0 until columns || y !in 0 until rows) continue
                val j = y * columns + x
                if (values[j].isNaN()) { values[j] = values[c]; fill += j }
            }
        }
        return Field(wear, left, top, grid.cell.toFloat(), columns, rows, values, looseShare, hangRow?.let { top + it * grid.cell })
    }

    /** Canvas bounds (left, top, right, bottom) of the cloth in [raster] placed at ([left], [top]), specks left out. */
    fun clothBounds(raster: LayerRaster, left: Float, top: Float, alphaThreshold: Int = 8): FloatArray? =
        Grid.read(raster, 0, 0, raster.width, raster.height, alphaThreshold).opaqueBox()
            ?.let { box -> floatArrayOf(left + box[0], top + box[1], left + box[2], top + box[3]) }

    private data class Support(val cells: BooleanArray, val fade: Float, val hangRow: Int?)

    /** Torso and hip clothing: the body column row by row, and where the cloth hangs below it. */
    private fun rowSupport(grid: Grid, wear: Wear, waistRow: Int?): Support {
        val columns = grid.columns
        val rows = grid.rows
        val mask = grid.mask
        // Each row's outer edges; specks narrower than a cell or two do not count as cloth.
        val minRun = maxOf(1, columns / 100)
        val edgeL = FloatArray(rows) { Float.NaN }
        val edgeR = FloatArray(rows) { Float.NaN }
        for (y in 0 until rows) {
            var start = -1
            for (x in 0..columns) {
                val on = x < columns && mask[y * columns + x]
                if (on && start < 0) start = x
                if (!on && start >= 0) {
                    if (x - start >= minRun) { if (edgeL[y].isNaN()) edgeL[y] = start.toFloat(); edgeR[y] = x.toFloat() }
                    start = -1
                }
            }
        }
        val occupied = (0 until rows).filter { !edgeL[it].isNaN() }
        val first = occupied.first()
        val last = occupied.last()
        val span = (last - first).coerceAtLeast(1)
        fun width(y: Int) = if (edgeL[y].isNaN()) 0f else edgeR[y] - edgeL[y]
        val typicalWidth = occupied.map(::width).sorted()[occupied.size / 2]
        val tolerance = maxOf(0.06f * typicalWidth, 1.5f)

        // Where the body column starts: the waist for bottoms, the shoulders for tops.
        val start = when (wear) {
            Wear.SKIRT, Wear.TROUSERS -> waistRow?.coerceIn(first, last) ?: first
            else -> {
                val upper = occupied.filter { it <= first + span * 2 / 5 }
                val shoulders = upper.maxOf(::width) * 0.75f
                upper.first { width(it) >= shoulders }
            }
        }
        val body = occupied.filter { it >= start }
        val bodyL = FloatArray(rows) { Float.NaN }
        val bodyR = FloatArray(rows) { Float.NaN }
        for (y in body) {
            var l = -Float.MAX_VALUE
            var r = Float.MAX_VALUE
            for (s in body) {
                val reach = (if (y >= s) WIDEN_DOWN else WIDEN_UP) * kotlin.math.abs(y - s)
                l = maxOf(l, edgeL[s] - reach)
                r = minOf(r, edgeR[s] + reach)
            }
            bodyL[y] = l; bodyR[y] = r
        }

        // Rows the cloth hangs from without a body underneath.
        val hangRow: Int? = when (wear) {
            Wear.TOP -> waistRow?.takeIf { it in start + 1..last }
            Wear.SKIRT -> {
                val hips = start + (last - start) * 15 / 100
                val hipWidth = (start..hips).filter { !edgeL[it].isNaN() }.maxOfOrNull(::width) ?: 0f
                (hips..last).firstOrNull { width(it) > hipWidth + tolerance }
            }
            Wear.NECKWEAR -> (start + (last - start) * 35 / 100).takeIf { it < last }
            else -> null
        }

        val supported = BooleanArray(columns * rows) { c ->
            val y = c / columns
            val x = c % columns + 0.5f
            mask[c] && (y < start || edgeL[y].isNaN() || (hangRow == null || y < hangRow) && maxOf(bodyL[y] - x, x - bodyR[y]) <= tolerance)
        }
        return Support(supported, 0.25f * maxOf(span.toFloat(), typicalWidth), hangRow)
    }

    /** Limb clothing: the limb is the opening of the garment by a disk half the limb's thickness. */
    private fun limbSupport(grid: Grid): Support {
        val mask = grid.mask
        val inside = chamfer(grid.columns, grid.rows) { mask[it] }
        val radii = mask.indices.filter { mask[it] && !grid.skin[it] }.map { inside[it] }.sorted()
        // The limb's own half-thickness: most of the cloth lies no deeper than this.
        val limb = radii.getOrNull(radii.size * 85 / 100) ?: inside.max()
        val disk = maxOf(1f, limb * 0.5f)
        val eroded = BooleanArray(mask.size) { inside[it] > disk }
        val fromCore = chamfer(grid.columns, grid.rows) { !eroded[it] }
        val opened = BooleanArray(mask.size) { mask[it] && fromCore[it] <= disk }
        return Support(opened, 2f * limb, null)
    }

    /** Two-pass chamfer distance (1, √2) from each cell where [inside] holds to the nearest cell where it does not, or the border. */
    private fun chamfer(columns: Int, rows: Int, inside: (Int) -> Boolean): FloatArray {
        val diagonal = sqrt(2f)
        val d = FloatArray(columns * rows) { if (inside(it)) Float.MAX_VALUE else 0f }
        fun at(x: Int, y: Int) = if (x !in 0 until columns || y !in 0 until rows) 0f else d[y * columns + x]
        for (y in 0 until rows) for (x in 0 until columns) {
            val c = y * columns + x
            if (d[c] == 0f) continue
            d[c] = minOf(d[c], at(x - 1, y) + 1f, at(x, y - 1) + 1f, minOf(at(x - 1, y - 1), at(x + 1, y - 1)) + diagonal)
        }
        for (y in rows - 1 downTo 0) for (x in columns - 1 downTo 0) {
            val c = y * columns + x
            if (d[c] == 0f) continue
            d[c] = minOf(d[c], at(x + 1, y) + 1f, at(x, y + 1) + 1f, minOf(at(x + 1, y + 1), at(x - 1, y + 1)) + diagonal)
        }
        return d
    }

    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** Cloth and skin cells over the raster region from ([x0], [y0]), [cell] px square, specks dropped. */
    private class Grid(val x0: Int, val y0: Int, val cell: Int, val columns: Int, val rows: Int, val mask: BooleanArray, val skin: BooleanArray) {
        /** Raster px bounds (left, top, right, bottom) of the cloth, or null when there is none. */
        fun opaqueBox(): IntArray? {
            val cells = mask.indices.filter { mask[it] }.ifEmpty { return null }
            return intArrayOf(
                x0 + cells.minOf { it % columns } * cell, y0 + cells.minOf { it / columns } * cell,
                x0 + (cells.maxOf { it % columns } + 1) * cell, y0 + (cells.maxOf { it / columns } + 1) * cell,
            )
        }

        companion object {
            fun read(raster: LayerRaster, x0: Int, y0: Int, x1: Int, y1: Int, alphaThreshold: Int): Grid {
                val right = minOf(x1, raster.width)
                val bottom = minOf(y1, raster.height)
                val cell = maxOf(1, ceil(maxOf(right - x0, bottom - y0) / GRID.toFloat()).toInt())
                val columns = (right - x0 + cell - 1) / cell
                val rows = (bottom - y0 + cell - 1) / cell
                val opaque = IntArray(columns * rows)
                val skinCount = IntArray(columns * rows)
                for (y in y0 until bottom) for (x in x0 until right) {
                    val i = (y * raster.width + x) * 4
                    if ((raster.rgba[i + 3].toInt() and 255) <= alphaThreshold) continue
                    val c = ((y - y0) / cell) * columns + (x - x0) / cell
                    opaque[c]++
                    if (isSkin(raster.rgba[i].toInt() and 255, raster.rgba[i + 1].toInt() and 255, raster.rgba[i + 2].toInt() and 255)) skinCount[c]++
                }
                val mask = BooleanArray(columns * rows)
                val skin = BooleanArray(columns * rows)
                for (c in mask.indices) {
                    val area = minOf(cell, right - x0 - c % columns * cell) * minOf(cell, bottom - y0 - c / columns * cell)
                    mask[c] = opaque[c] * 5 >= area * 2
                    skin[c] = mask[c] && skinCount[c] * 2 >= opaque[c]
                }
                // Specks away from the garment are not cloth, and only a patch of skin is body: warm trim
                // and embroidery scatter into small specks.
                dropSpecks(mask, columns, rows, mask.count { it } / 200)
                for (c in skin.indices) skin[c] = skin[c] && mask[c]
                dropSpecks(skin, columns, rows, maxOf(4, mask.count { it } / 60))
                return Grid(x0, y0, cell, columns, rows, mask, skin)
            }
        }
    }

    /** Clears every 4-connected patch of [flags] smaller than [minSize] cells. */
    private fun dropSpecks(flags: BooleanArray, columns: Int, rows: Int, minSize: Int) {
        val seen = BooleanArray(flags.size)
        for (seed in flags.indices) {
            if (!flags[seed] || seen[seed]) continue
            val patch = ArrayList<Int>()
            val stack = ArrayDeque(listOf(seed))
            seen[seed] = true
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                patch += c
                for ((ox, oy) in NEIGHBOURS) {
                    val x = c % columns + ox
                    val y = c / columns + oy
                    if (x !in 0 until columns || y !in 0 until rows) continue
                    val j = y * columns + x
                    if (flags[j] && !seen[j]) { seen[j] = true; stack += j }
                }
            }
            if (patch.size < minSize) for (c in patch) flags[c] = false
        }
    }

    /**
     * Whether a colour reads as drawn skin: warm, light and a little saturated, falling off from red to
     * green at least as fast as from green to blue. White and grey cloth is too unsaturated, gold and
     * beige too yellow.
     */
    internal fun isSkin(r: Int, g: Int, b: Int): Boolean {
        if (r < 150 || r < g || g < b) return false
        val saturation = (r - b) / r.toFloat()
        return saturation in 0.05f..0.42f && r - g in 6..90 && g - b <= (r - g) + 6
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
