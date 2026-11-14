package io.github.psd2live.core

import io.github.psd2live.document.ArrangedTile
import io.github.psd2live.document.TextureFootprint
import org.umamo.format.art.LayerRaster
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The geometry of a stored atlas arrangement ([io.github.psd2live.document.AtlasArrangement]).
 *
 * A page is a grid of [CELL] x [CELL] texture pixel cells. A tile occupies the cells its rectangle touches, or,
 * with a mesh footprint, only the cells its meshes touch - so two tiles whose rectangles overlap fit side by
 * side when their meshes do not. Everything is integer and in a fixed order, so the same inputs always give the
 * same spots.
 *
 * Two jobs share it: [keep] places the tiles of a stored arrangement where it says, on every build, and puts
 * the ones it cannot - new layers, tiles grown into a neighbour or off the page - into free space; [arrange]
 * finds a compact arrangement once, when the user asks for one.
 */
internal object AtlasArrange {
	const val CELL = 4

	/** One tile to place: its size on the page at the arrangement's fit, its raster and its footprint. */
	class Request(
		val id: String,
		val name: String,
		val width: Int,
		val height: Int,
		val rasterWidth: Int,
		val rasterHeight: Int,
		val stored: ArrangedTile?,
		val footprint: TextureFootprint?,
	) {
		val area: Int get() = footprint?.area ?: (ceilDiv(width, CELL) * ceilDiv(height, CELL))
	}

	/** A tile's spot: its upright rectangle's top left on [page], turned [rotation] degrees about its centre. */
	data class Spot(val page: Int, val x: Int, val y: Int, val rotation: Float = 0f)

	/**
	 * Cells of a placed tile on the page grid: a [width] x [height] cell box at ([column], [row]) and, per box
	 * row, the occupied column runs as `from, to` pairs relative to the box.
	 */
	class Shape(val column: Int, val row: Int, val width: Int, val height: Int, val runs: Array<IntArray>) {
		/** A key of the cells, for page recipes. */
		val key: String by lazy { "$column,$row,$width,$height:" + runs.joinToString(";") { it.joinToString(",") } }
	}

	private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

	/**
	 * The cells a tile of [width] x [height] at texture pixel ([x], [y]) occupies; all of its rectangle without a
	 * footprint. Turned by [rotation] about its centre, it occupies every cell its turned footprint cells (or its turned
	 * rectangle) reach.
	 */
	fun shape(x: Int, y: Int, width: Int, height: Int, rasterWidth: Int, rasterHeight: Int, footprint: TextureFootprint?,
	          rotation: Float = 0f): Shape {
		if (rotation != 0f) return turnedShape(x, y, width, height, rasterWidth, rasterHeight, footprint, rotation)
		val column = x / CELL; val row = y / CELL
		val columns = ceilDiv(x + width, CELL) - column; val rows = ceilDiv(y + height, CELL) - row
		if (footprint == null) return Shape(column, row, columns, rows, Array(rows) { intArrayOf(0, columns) })
		val bits = java.util.BitSet(columns * rows)
		val sx = width.toDouble() / rasterWidth; val sy = height.toDouble() / rasterHeight
		for (j in 0 until footprint.rows) for (i in 0 until footprint.columns) {
			if (!footprint[i, j]) continue
			val left = x + i * footprint.cell * sx; val right = x + minOf((i + 1) * footprint.cell, rasterWidth) * sx
			val top = y + j * footprint.cell * sy; val bottom = y + minOf((j + 1) * footprint.cell, rasterHeight) * sy
			val c0 = (floor(left / CELL).toInt() - column).coerceIn(0, columns - 1)
			val c1 = (ceil(right / CELL).toInt() - column).coerceIn(c0 + 1, columns)
			val r0 = (floor(top / CELL).toInt() - row).coerceIn(0, rows - 1)
			val r1 = (ceil(bottom / CELL).toInt() - row).coerceIn(r0 + 1, rows)
			for (r in r0 until r1) bits.set(r * columns + c0, r * columns + c1)
		}
		return Shape(column, row, columns, rows, Array(rows) { r -> runs(bits, r * columns, columns) })
	}

	private fun turnedShape(x: Int, y: Int, width: Int, height: Int, rasterWidth: Int, rasterHeight: Int, footprint: TextureFootprint?,
	                        rotation: Float): Shape {
		val box = io.github.psd2live.core.TileTurn.bounds(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), rotation)
		// A thousandth of a pixel absorbs the trigonometry's rounding, so a quarter turn's box stays on its cells.
		val column = floor((box[0] + 1e-3f) / CELL).toInt(); val row = floor((box[1] + 1e-3f) / CELL).toInt()
		val columns = (ceil((box[2] - 1e-3f) / CELL).toInt() - column).coerceAtLeast(1); val rows = (ceil((box[3] - 1e-3f) / CELL).toInt() - row).coerceAtLeast(1)
		val bits = java.util.BitSet(columns * rows)
		fun quad(tx0: Float, ty0: Float, tx1: Float, ty1: Float) {
			val q = FloatArray(8)
			for ((k, corner) in listOf(tx0 to ty0, tx1 to ty0, tx1 to ty1, tx0 to ty1).withIndex()) {
				val p = io.github.psd2live.core.TileTurn.toPage(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), rotation, corner.first, corner.second)
				q[k * 2] = p[0]; q[k * 2 + 1] = p[1]
			}
			markQuad(bits, column, row, columns, rows, q)
		}
		if (footprint == null) quad(0f, 0f, width.toFloat(), height.toFloat())
		else {
			val sx = width.toFloat() / rasterWidth; val sy = height.toFloat() / rasterHeight
			for (j in 0 until footprint.rows) for (i in 0 until footprint.columns) {
				if (!footprint[i, j]) continue
				quad(i * footprint.cell * sx, j * footprint.cell * sy, minOf((i + 1) * footprint.cell, rasterWidth) * sx, minOf((j + 1) * footprint.cell, rasterHeight) * sy)
			}
		}
		return Shape(column, row, columns, rows, Array(rows) { r -> runs(bits, r * columns, columns) })
	}

	/** Sets every cell of the box at ([column], [row]) that the convex quad [q] (four page points) overlaps. */
	private fun markQuad(bits: java.util.BitSet, column: Int, row: Int, columns: Int, rows: Int, q: FloatArray) {
		val minX = minOf(q[0], q[2], q[4], q[6]); val maxX = maxOf(q[0], q[2], q[4], q[6])
		val minY = minOf(q[1], q[3], q[5], q[7]); val maxY = maxOf(q[1], q[3], q[5], q[7])
		val c0 = (floor(minX / CELL).toInt() - column).coerceIn(0, columns - 1); val c1 = (ceil(maxX / CELL).toInt() - column).coerceIn(c0 + 1, columns)
		val r0 = (floor(minY / CELL).toInt() - row).coerceIn(0, rows - 1); val r1 = (ceil(maxY / CELL).toInt() - row).coerceIn(r0 + 1, rows)
		// The quad's two edge normals; with the cell's own axes they separate any cell the quad misses.
		val n = floatArrayOf(-(q[3] - q[1]), q[2] - q[0], -(q[5] - q[3]), q[4] - q[2])
		for (r in r0 until r1) for (c in c0 until c1) {
			val left = (column + c) * CELL.toFloat(); val top = (row + r) * CELL.toFloat()
			var separated = false
			for (axis in 0..1) {
				val ax = n[axis * 2]; val ay = n[axis * 2 + 1]
				var qMin = Float.MAX_VALUE; var qMax = -Float.MAX_VALUE
				for (k in 0..3) { val d = q[k * 2] * ax + q[k * 2 + 1] * ay; qMin = minOf(qMin, d); qMax = maxOf(qMax, d) }
				var cMin = Float.MAX_VALUE; var cMax = -Float.MAX_VALUE
				for ((px, py) in listOf(left to top, left + CELL to top, left + CELL to top + CELL, left to top + CELL)) {
					val d = px * ax + py * ay; cMin = minOf(cMin, d); cMax = maxOf(cMax, d)
				}
				// Touching along an edge is no overlap, as for upright cells.
				if (cMax <= qMin + 1e-3f || qMax <= cMin + 1e-3f) { separated = true; break }
			}
			if (!separated) bits.set(r * columns + c)
		}
	}

	private fun runs(bits: java.util.BitSet, start: Int, length: Int): IntArray {
		val out = ArrayList<Int>()
		var at = bits.nextSetBit(start)
		while (at in start until start + length) {
			val end = minOf(bits.nextClearBit(at), start + length)
			out += at - start; out += end - start
			at = bits.nextSetBit(end)
		}
		return out.toIntArray()
	}

	/** [shape] grown by [cells] on every side, so a tile keeps the padding from its neighbours. */
	fun dilate(shape: Shape, cells: Int): Shape {
		if (cells <= 0) return shape
		val columns = shape.width + cells * 2; val rows = shape.height + cells * 2
		val bits = java.util.BitSet(columns * rows)
		for (r in 0 until shape.height) {
			val run = shape.runs[r]
			for (k in run.indices step 2) for (rr in r until r + cells * 2 + 1) bits.set(rr * columns + run[k], rr * columns + run[k + 1] + cells * 2)
		}
		return Shape(shape.column - cells, shape.row - cells, columns, rows, Array(rows) { r -> runs(bits, r * columns, columns) })
	}

	/** Whether [a] and [b] share a cell. */
	fun overlaps(a: Shape, b: Shape): Boolean {
		if (a.column >= b.column + b.width || b.column >= a.column + a.width || a.row >= b.row + b.height || b.row >= a.row + a.height) return false
		for (row in maxOf(a.row, b.row) until minOf(a.row + a.height, b.row + b.height)) {
			val ra = a.runs[row - a.row]; val rb = b.runs[row - b.row]
			for (i in ra.indices step 2) for (k in rb.indices step 2) {
				if (a.column + ra[i] < b.column + rb[k + 1] && b.column + rb[k] < a.column + ra[i + 1]) return true
			}
		}
		return false
	}

	/** Occupied cells of one page. */
	private class Grid(val columns: Int, val rows: Int) {
		private val words = (columns + 63) ushr 6
		private val bits = LongArray(words * rows)

		fun mark(shape: Shape) {
			for (r in 0 until shape.height) {
				val row = shape.row + r
				if (row !in 0 until rows) continue
				val run = shape.runs[r]
				for (k in run.indices step 2) for (c in maxOf(0, shape.column + run[k]) until minOf(columns, shape.column + run[k + 1])) {
					bits[row * words + (c ushr 6)] = bits[row * words + (c ushr 6)] or (1L shl (c and 63))
				}
			}
		}

		/** The first occupied column in [from, to) of [row], or -1. */
		fun firstSet(row: Int, from: Int, to: Int): Int {
			var c = maxOf(from, 0)
			val end = minOf(to, columns)
			while (c < end) {
				val word = bits[row * words + (c ushr 6)] ushr (c and 63)
				if (word != 0L) {
					val hit = c + java.lang.Long.numberOfTrailingZeros(word)
					return if (hit < end) hit else -1
				}
				c = (c or 63) + 1
			}
			return -1
		}

		/**
		 * The first column in 0..[lastColumn] at which a box of [span] cells - its [grow] margin included, so it
		 * starts [grow] cells left of the column - is free over rows [top] until [top] + [height]; -1 when none.
		 * Rows and columns off the page count as free. One pass merges the rows, so a rectangle tests each
		 * row of the page once rather than every column of it.
		 */
		fun firstFreeSpan(top: Int, height: Int, span: Int, grow: Int, lastColumn: Int): Int {
			val line = LongArray(words)
			for (y in maxOf(top, 0) until minOf(top + height, rows)) {
				val base = y * words
				for (w in 0 until words) line[w] = line[w] or bits[base + w]
			}
			var column = 0
			while (column <= lastColumn) {
				val hit = lastSet(line, maxOf(column - grow, 0), minOf(column - grow + span, columns))
				if (hit < 0) return column
				column = hit + grow + 1
			}
			return -1
		}

		/** The last set bit of [line] in [from, to), or -1. */
		private fun lastSet(line: LongArray, from: Int, to: Int): Int {
			if (to <= from) return -1
			var w = (to - 1) ushr 6
			val first = from ushr 6
			while (w >= first) {
				var word = line[w]
				if (w == (to - 1) ushr 6) { val keep = ((to - 1) and 63) + 1; if (keep < 64) word = word and ((1L shl keep) - 1) }
				if (w == first) word = word and (-1L shl (from and 63))
				if (word != 0L) return (w shl 6) + 63 - java.lang.Long.numberOfLeadingZeros(word)
				w--
			}
			return -1
		}

		/**
		 * Whether [probe] - a tile's shape grown by its padding, with its box moved so the tile's own box sits at
		 * ([column], [row]) - is free; else the next column worth trying on this row. Cells off the page count as free.
		 */
		fun next(probe: Shape, grow: Int, column: Int, row: Int): Int {
			val left = column - grow; val top = row - grow
			var skip = -1
			for (r in 0 until probe.height) {
				val y = top + r
				if (y !in 0 until rows) continue
				val run = probe.runs[r]
				for (k in run.indices step 2) {
					val hit = firstSet(y, left + run[k], left + run[k + 1])
					if (hit >= 0) skip = maxOf(skip, hit - (left + run[k]) + 1 + column)
				}
			}
			return skip
		}
	}

	/**
	 * Whether two kept tiles meet: only where their boxes (turned, grown by [padding]) overlap at all - so tiles a pixel
	 * pack leaves apart never meet by their cells' rounding - and then, for two upright tiles without footprints, by
	 * their rectangles, else by their shapes, the first grown by the padding. The one rule a stored arrangement is kept
	 * by and a placement is checked by.
	 */
	fun meet(ax: Int, ay: Int, aw: Int, ah: Int, ar: Float, aWhole: Boolean, aShape: Shape,
	         bx: Int, by: Int, bw: Int, bh: Int, br: Float, bWhole: Boolean, bShape: Shape, padding: Int): Boolean {
		val a = io.github.psd2live.core.TileTurn.bounds(ax.toFloat(), ay.toFloat(), aw.toFloat(), ah.toFloat(), ar)
		val b = io.github.psd2live.core.TileTurn.bounds(bx.toFloat(), by.toFloat(), bw.toFloat(), bh.toFloat(), br)
		if (a[0] >= b[2] + padding || b[0] >= a[2] + padding || a[1] >= b[3] + padding || b[1] >= a[3] + padding) return false
		if (aWhole && bWhole && ar == 0f && br == 0f) return true
		return overlaps(dilate(aShape, paddingCells(padding)), bShape)
	}

	/** Cells of padding between tiles: the shapes keep at least the rectangle packer's `2 x padding` pixels apart. */
	fun paddingCells(padding: Int): Int = ceilDiv(padding.coerceAtLeast(0) * 2, CELL)

	class Kept(val spots: Map<String, Spot>, val shapes: Map<String, Shape>, val pageCount: Int)

	/**
	 * The stored spots of [requests] that still hold - on a page of the budget, inside it, clear of the tiles
	 * already kept - and free spots for the rest, in [requests] order. A tile with no free spot within
	 * [maxPages] pages goes onto a page past them.
	 */
	fun keep(requests: List<Request>, pageSize: Int, padding: Int, maxPages: Int): Kept {
		val grow = paddingCells(padding)
		val spots = LinkedHashMap<String, Spot>()
		val shapes = HashMap<String, Shape>()
		val placed = HashMap<Int, MutableList<Pair<Request, Shape>>>()
		val pending = ArrayList<Request>()
		for (request in requests) {
			val stored = request.stored
			if (stored == null) { pending += request; continue }
			val box = io.github.psd2live.core.TileTurn.bounds(stored.x.toFloat(), stored.y.toFloat(), request.width.toFloat(), request.height.toFloat(), stored.rotation)
			val fits = stored.page < maxPages && box[0] >= -1e-3f && box[1] >= -1e-3f && box[2] <= pageSize + 1e-3f && box[3] <= pageSize + 1e-3f
			val shape = shape(stored.x, stored.y, request.width, request.height, request.rasterWidth, request.rasterHeight, request.footprint, stored.rotation)
			val clear = fits && placed[stored.page].orEmpty().none { (other, otherShape) ->
				val a = spots.getValue(other.id)
				meet(stored.x, stored.y, request.width, request.height, stored.rotation, request.footprint == null, shape,
					a.x, a.y, other.width, other.height, a.rotation, other.footprint == null, otherShape, padding)
			}
			if (!clear) { pending += request; continue }
			spots[request.id] = Spot(stored.page, stored.x, stored.y, stored.rotation)
			shapes[request.id] = shape
			placed.getOrPut(stored.page) { ArrayList() } += request to shape
		}
		val grids = HashMap<Int, Grid>()
		fun grid(page: Int) = grids.getOrPut(page) {
			Grid(pageSize / CELL, pageSize / CELL).also { grid -> placed[page].orEmpty().forEach { grid.mark(it.second) } }
		}
		var pageCount = (spots.values.maxOfOrNull { it.page } ?: -1) + 1
		for (request in pending) {
			var page = 0
			var found: Pair<Spot, Shape>? = null
			while (found == null) {
				found = search(grid(page), request, pageSize, grow, page)
				if (found == null) page++
				require(page < 4096) { "Texture ${request.name} does not fit an atlas page" }
			}
			val (spot, shape) = found
			grid(page).mark(shape)
			spots[request.id] = spot; shapes[request.id] = shape
			pageCount = maxOf(pageCount, page + 1)
		}
		return Kept(spots, shapes, maxOf(pageCount, 1))
	}

	/** The first free cell-aligned spot for [request] on a page, scanning rows top to bottom; null when none. */
	private fun search(grid: Grid, request: Request, pageSize: Int, grow: Int, page: Int): Pair<Spot, Shape>? {
		if (request.width > pageSize || request.height > pageSize) return null
		val origin = shape(0, 0, request.width, request.height, request.rasterWidth, request.rasterHeight, request.footprint)
		val probe = dilate(origin, grow)
		val lastRow = (pageSize - request.height) / CELL
		val lastColumn = (pageSize - request.width) / CELL
		if (request.footprint == null) {
			// A rectangle grown by its padding is a rectangle: the same first fit, a row at a time.
			for (row in 0..lastRow) {
				val column = grid.firstFreeSpan(row - grow, probe.height, probe.width, grow, lastColumn)
				if (column >= 0) {
					val spot = Spot(page, column * CELL, row * CELL)
					return spot to shape(spot.x, spot.y, request.width, request.height, request.rasterWidth, request.rasterHeight, null)
				}
			}
			return null
		}
		for (row in 0..lastRow) {
			var column = 0
			while (column <= lastColumn) {
				val skip = grid.next(probe, grow, column, row)
				if (skip < 0) {
					val spot = Spot(page, column * CELL, row * CELL)
					return spot to shape(spot.x, spot.y, request.width, request.height, request.rasterWidth, request.rasterHeight, request.footprint)
				}
				column = maxOf(skip, column + 1)
			}
		}
		return null
	}

	/**
	 * A compact arrangement of [requests] within [maxPages] pages around the [fixed] tiles (with their shapes);
	 * null when they do not all fit. Tiles go in by footprint, by height and by the longer side, largest first;
	 * the order that uses the fewest pages and then the fewest rows of its last page wins.
	 */
	fun arrange(requests: List<Request>, fixed: List<Pair<Spot, Shape>>, pageSize: Int, padding: Int, maxPages: Int): Map<String, Spot>? {
		var best: Placed? = null
		for (order in ORDERS) {
			val placed = place(requests.sortedWith(order), fixed, pageSize, padding, maxPages) ?: continue
			val current = best
			if (current == null || placed.pages < current.pages || placed.pages == current.pages && placed.bottom < current.bottom) best = placed
		}
		return best?.spots
	}

	private val ORDERS: List<Comparator<Request>> = listOf(
		compareByDescending<Request> { it.area }.thenByDescending { it.height }.thenBy { it.id },
		compareByDescending<Request> { it.height }.thenByDescending { it.area }.thenBy { it.id },
		compareByDescending<Request> { maxOf(it.width, it.height) }.thenByDescending { it.area }.thenBy { it.id },
	)

	private class Placed(val spots: Map<String, Spot>, val pages: Int, val bottom: Int)

	private fun place(requests: List<Request>, fixed: List<Pair<Spot, Shape>>, pageSize: Int, padding: Int, maxPages: Int): Placed? {
		val grow = paddingCells(padding)
		val grids = HashMap<Int, Grid>()
		fun grid(page: Int) = grids.getOrPut(page) {
			Grid(pageSize / CELL, pageSize / CELL).also { grid -> fixed.filter { it.first.page == page }.forEach { grid.mark(it.second) } }
		}
		val spots = LinkedHashMap<String, Spot>()
		val bottoms = HashMap<Int, Int>()
		for ((spot, shape) in fixed) bottoms.merge(spot.page, shape.row + shape.height, ::maxOf)
		for (request in requests) {
			var placed = false
			for (page in 0 until maxPages) {
				val found = search(grid(page), request, pageSize, grow, page) ?: continue
				grid(page).mark(found.second)
				spots[request.id] = found.first
				bottoms.merge(page, found.second.row + found.second.height, ::maxOf)
				placed = true
				break
			}
			if (!placed) return null
		}
		val pages = (bottoms.keys.maxOrNull() ?: 0) + 1
		return Placed(spots, pages, bottoms[pages - 1] ?: 0)
	}

	/**
	 * The footprint of meshes over a raster of [rasterWidth] x [rasterHeight]: every cell of [cell] raster pixels
	 * that one of [triangles] (raster pixel coordinates, three x,y points each) touches.
	 */
	fun footprint(rasterWidth: Int, rasterHeight: Int, cell: Int, triangles: List<FloatArray>): TextureFootprint? {
		if (triangles.isEmpty()) return null
		val columns = ceilDiv(rasterWidth, cell); val rows = ceilDiv(rasterHeight, cell)
		val bits = java.util.BitSet(columns * rows)
		for (t in triangles) {
			val minX = minOf(t[0], t[2], t[4]); val maxX = maxOf(t[0], t[2], t[4])
			val minY = minOf(t[1], t[3], t[5]); val maxY = maxOf(t[1], t[3], t[5])
			val c0 = floor(minX / cell).toInt().coerceIn(0, columns - 1); val c1 = floor(maxX / cell).toInt().coerceIn(0, columns - 1)
			val r0 = floor(minY / cell).toInt().coerceIn(0, rows - 1); val r1 = floor(maxY / cell).toInt().coerceIn(0, rows - 1)
			for (r in r0..r1) for (c in c0..c1) {
				if (!bits[r * columns + c] && touches(t, c * cell.toFloat(), r * cell.toFloat(), (c + 1) * cell.toFloat(), (r + 1) * cell.toFloat())) bits.set(r * columns + c)
			}
		}
		return if (bits.isEmpty) null else TextureFootprint(cell, columns, rows, bits)
	}

	/** Alpha from which a pixel is art a mesh covers: the mesh tracer's default threshold. */
	const val COVER_ALPHA = 8

	/**
	 * [footprint] as it holds for [raster] now: null when its cell grid no longer fits the raster (the raster was
	 * resized or re-cropped since, so its cells name other pixels), else with every cell that holds art added. A
	 * footprint comes from the meshes, which cover the art; art outside it was painted later and its mesh grows
	 * with it, so the tile must claim those cells before it writes them or lets a neighbour nest there.
	 */
	fun covering(footprint: TextureFootprint, raster: LayerRaster): TextureFootprint? {
		val cell = footprint.cell; val columns = footprint.columns
		if (columns != ceilDiv(raster.width, cell) || footprint.rows != ceilDiv(raster.height, cell)) return null
		val rgba = raster.rgba
		var grown: java.util.BitSet? = null
		for (y in 0 until raster.height) {
			val row = y / cell * columns
			var x = 0
			while (x < raster.width) {
				val index = row + x / cell
				val owned = footprint.bits[index] || grown?.get(index) == true
				if (!owned && (rgba[(y * raster.width + x) * 4 + 3].toInt() and 0xff) < COVER_ALPHA) { x++; continue }
				if (!owned) (grown ?: (footprint.bits.clone() as java.util.BitSet).also { grown = it }).set(index)
				x = (x / cell + 1) * cell
			}
		}
		return grown?.let { TextureFootprint(cell, columns, footprint.rows, it) } ?: footprint
	}

	/**
	 * Raster pixels a triangle must reach into a cell to cover it. Texture coordinates read back from another
	 * layout differ in their last bits; a vertex on a cell edge must not flip the footprint between layouts.
	 */
	private const val TOUCH_EPSILON = 0.01f

	/** Whether triangle [t] reaches into the rectangle, by separating axes (the rectangle's two and the triangle's three). */
	private fun touches(t: FloatArray, outerLeft: Float, outerTop: Float, outerRight: Float, outerBottom: Float): Boolean {
		val left = outerLeft + TOUCH_EPSILON; val top = outerTop + TOUCH_EPSILON
		val right = outerRight - TOUCH_EPSILON; val bottom = outerBottom - TOUCH_EPSILON
		if (maxOf(t[0], t[2], t[4]) < left || minOf(t[0], t[2], t[4]) > right || maxOf(t[1], t[3], t[5]) < top || minOf(t[1], t[3], t[5]) > bottom) return false
		val corners = floatArrayOf(left, top, right, top, right, bottom, left, bottom)
		for (e in 0 until 3) {
			val ax = t[e * 2]; val ay = t[e * 2 + 1]; val bx = t[(e * 2 + 2) % 6]; val by = t[(e * 2 + 3) % 6]
			val cx = t[(e * 2 + 4) % 6]; val cy = t[(e * 2 + 5) % 6]
			val nx = ay - by; val ny = bx - ax
			val side = nx * (cx - ax) + ny * (cy - ay)
			if (side == 0f) continue
			var allOutside = true
			for (k in 0 until 4) {
				val d = nx * (corners[k * 2] - ax) + ny * (corners[k * 2 + 1] - ay)
				if (d * side >= 0f) { allOutside = false; break }
			}
			if (allOutside) return false
		}
		return true
	}
}
