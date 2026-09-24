package io.github.psd2live.core.mesh

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Graded, pattern-based interior fills.
 *
 * Every layout lists the domain loop vertices first, in loop order, followed by generated vertices.
 * Fixed triangles keep their exact pattern; the remaining free regions are handed to the constrained
 * Delaunay triangulator (the thin gap between a lattice core and the contour, or a paved center).
 */
internal object StructuredMeshFill {
	enum class Kind { QUADTREE, TRIANGLE_FRACTAL, CONTOUR_PAVING }

	/** A region for constrained triangulation: loops are global vertex ids, outer loop first. */
	class Region(val loops: List<IntArray>, val candidates: List<Point>, val spacing: Double)

	class Layout(
		val points: List<Point>,
		val triangles: List<Triangle>,
		val regions: List<Region>,
		/** Generated vertices, for a point-insertion fallback when the gap cannot be triangulated. */
		val interior: List<Point>,
	)

	/** Size growth per unit of depth. One 2:1 level per ring of its own size, so the bulk reaches the coarse spacing. */
	private const val GRADATION = 1.0
	private const val CLEARANCE = 0.6
	/** Gap points only where the gap is at least two contour spacings wide. */
	private const val GAP_CLEARANCE = 1.0
	/** Coarsest leaf next to the contour, relative to its spacing: 4-8 levels are sqrt(2) apart, red-green levels 2. */
	private const val FINEST_QUADTREE = 1.2
	private const val FINEST_FRACTAL = 1.5
	private const val SQRT3_2 = 0.8660254037844386
	private const val MAX_CORE_TRIANGLES = 24_000

	private class SizingField(val loops: List<List<Point>>, val fine: Double, val coarse: Double) {
		val index = SegmentIndex(loops, fine)
		fun distance(p: Point) = index.distance(p)
		fun inside(p: Point) = inDomain(p, loops)
		fun target(depth: Double) = (fine + GRADATION * depth).coerceIn(fine, coarse)
	}

	fun layout(kind: Kind, loops: List<List<Point>>, coarseSpacing: Double): Layout? {
		if (loops.isEmpty() || loops.any { it.size < 3 }) return null
		val segments = loops.flatMap { loop -> loop.indices.map { distance(loop[it], loop[(it + 1) % loop.size]) } }.sorted()
		val fine = segments[segments.size / 2].coerceAtLeast(2.0)
		val coarse = max(coarseSpacing, fine)
		val field = SizingField(loops, fine, coarse)
		return when (kind) {
			Kind.QUADTREE -> coreLayout(field, quadtreeTriangles(field))
			Kind.TRIANGLE_FRACTAL -> coreLayout(field, fractalTriangles(field))
			Kind.CONTOUR_PAVING -> pavingLayout(field)
		}
	}

	/** A candidate lattice triangle in world coordinates with its nominal vertex spacing. */
	private class LatticeTriangle(val a: Long, val b: Long, val c: Long, val size: Double)

	private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
	private fun keyX(key: Long): Int = (key shr 32).toInt()
	private fun keyY(key: Long): Int = key.toInt()

	private fun bounds(loops: List<List<Point>>): DoubleArray {
		val all = loops.flatten()
		return doubleArrayOf(all.minOf { it.x }, all.minOf { it.y }, all.maxOf { it.x }, all.maxOf { it.y })
	}

	/** Whether a lattice triangle of nominal [size] still needs refinement. */
	private fun needsRefinement(field: SizingField, a: Point, b: Point, c: Point, size: Double, finest: Double): Boolean {
		// The gap triangulation absorbs a step up from the contour spacing, so the core may stop early.
		if (size <= field.fine * finest) return false
		val center = Point((a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3)
		val radius = max(distance(center, a), max(distance(center, b), distance(center, c)))
		val d = field.distance(center)
		val inside = field.inside(center)
		if (!inside && d > radius) return false
		val clearance = if (inside) max(0.0, d - radius * 0.5) else 0.0
		return size > field.target(clearance) * 1.0001
	}

	// ---------------------------------------------------------------- 4-8 quadtree

	private class RightTriangle(val apex: Long, val left: Long, val right: Long) { var leaf = true }
	private data class LatticeEdge(val low: Long, val high: Long)
	private fun latticeEdge(a: Long, b: Long) = if (a < b) LatticeEdge(a, b) else LatticeEdge(b, a)

	/**
	 * Right isosceles triangles bisected across their hypotenuse (a 4-8 mesh). A triangle is only
	 * split together with its hypotenuse partner, forcing coarser partners first, so the mesh never
	 * has hanging vertices and every vertex has degree 4 or 8 in uniform areas.
	 */
	private fun quadtreeTriangles(field: SizingField): Pair<List<LatticeTriangle>, (Long) -> Point> {
		val box = bounds(field.loops)
		val minX = box[0]; val minY = box[1]; val maxX = box[2]; val maxY = box[3]
		val levels = max(0, floor(ln(field.coarse / field.fine) / ln(2.0)).toInt()).coerceAtMost(10)
		val rootUnits = 1 shl (levels + 1)
		val unit = field.coarse / rootUnits
		val columns = max(1, ceil((maxX - minX) / field.coarse).toInt())
		val rows = max(1, ceil((maxY - minY) / field.coarse).toInt())
		val originX = minX - (columns * field.coarse - (maxX - minX)) * 0.5
		val originY = minY - (rows * field.coarse - (maxY - minY)) * 0.5
		val world = { k: Long -> Point(originX + keyX(k) * unit, originY + keyY(k) * unit) }
		val byEdge = HashMap<LatticeEdge, MutableList<RightTriangle>>()
		val all = mutableListOf<RightTriangle>()
		val queue = ArrayDeque<RightTriangle>()
		fun add(t: RightTriangle) {
			all += t; queue += t
			for (e in listOf(latticeEdge(t.apex, t.left), latticeEdge(t.apex, t.right), latticeEdge(t.left, t.right)))
				byEdge.getOrPut(e) { mutableListOf() } += t
		}
		fun remove(t: RightTriangle) {
			t.leaf = false
			for (e in listOf(latticeEdge(t.apex, t.left), latticeEdge(t.apex, t.right), latticeEdge(t.left, t.right)))
				byEdge[e]?.remove(t)
		}
		for (j in 0 until rows) for (i in 0 until columns) {
			val x0 = i * rootUnits; val y0 = j * rootUnits
			val a = key(x0, y0); val b = key(x0 + rootUnits, y0)
			val c = key(x0 + rootUnits, y0 + rootUnits); val d = key(x0, y0 + rootUnits)
			// Alternate the root diagonals so root vertices already follow the 4-8 degree pattern.
			if ((i + j) % 2 == 0) { add(RightTriangle(b, a, c)); add(RightTriangle(d, c, a)) }
			else { add(RightTriangle(a, d, b)); add(RightTriangle(c, b, d)) }
		}
		fun splittable(t: RightTriangle) =
			(keyX(t.left) + keyX(t.right)) % 2 == 0 && (keyY(t.left) + keyY(t.right)) % 2 == 0
		fun partner(t: RightTriangle) = byEdge[latticeEdge(t.left, t.right)]?.firstOrNull { it !== t && it.leaf }
		fun sameHypotenuse(a: RightTriangle, b: RightTriangle) = latticeEdge(a.left, a.right) == latticeEdge(b.left, b.right)
		fun bisect(t: RightTriangle) {
			val m = key((keyX(t.left) + keyX(t.right)) / 2, (keyY(t.left) + keyY(t.right)) / 2)
			remove(t)
			add(RightTriangle(m, t.left, t.apex))
			add(RightTriangle(m, t.apex, t.right))
		}
		fun split(t: RightTriangle, depth: Int = 0): Boolean {
			if (!t.leaf) return true
			if (!splittable(t) || depth > 64 || all.size > MAX_CORE_TRIANGLES) return false
			var other = partner(t)
			if (other != null && !sameHypotenuse(t, other)) {
				if (!split(other, depth + 1)) return false
				other = partner(t)
				if (other != null && !sameHypotenuse(t, other)) return false
			}
			bisect(t)
			if (other != null) bisect(other)
			return true
		}
		while (queue.isNotEmpty()) {
			val t = queue.removeFirst()
			if (!t.leaf) continue
			val a = world(t.apex)
			if (needsRefinement(field, a, world(t.left), world(t.right), distance(a, world(t.left)), FINEST_QUADTREE)) split(t)
		}
		val leaves = all.filter { it.leaf }.map {
			LatticeTriangle(it.apex, it.left, it.right, distance(world(it.apex), world(it.left)))
		}
		return leaves to world
	}

	// ---------------------------------------------------------------- equilateral red-green

	private class EquilateralTriangle(val a: Long, val b: Long, val c: Long, val size: Int)

	/**
	 * Equilateral triangles split into four (red) where the sizing field asks for it, balanced so that
	 * edge neighbors differ by at most one level. A leaf with one hanging midpoint is closed by a green
	 * bisection; two or more hanging midpoints upgrade it to a red split.
	 */
	private fun fractalTriangles(field: SizingField): Pair<List<LatticeTriangle>, (Long) -> Point> {
		val box = bounds(field.loops)
		val levels = max(0, floor(ln(field.coarse / field.fine) / ln(2.0)).toInt()).coerceAtMost(10)
		val rootSize = 1 shl levels
		val unit = field.coarse / rootSize
		val rowHeight = field.coarse * SQRT3_2
		val rows = max(1, ceil((box[3] - box[1]) / rowHeight).toInt())
		val originY = box[1] - (rows * rowHeight - (box[3] - box[1])) * 0.5
		val originX = box[0]
		val world = { k: Long ->
			val p = keyX(k); val q = keyY(k)
			Point(originX + (p + q * 0.5) * unit, originY + q * SQRT3_2 * unit)
		}
		val vertices = HashSet<Long>()
		val leaves = LinkedHashSet<EquilateralTriangle>()
		val queue = ArrayDeque<EquilateralTriangle>()
		fun add(t: EquilateralTriangle) { leaves += t; queue += t; vertices += t.a; vertices += t.b; vertices += t.c }
		fun mid(a: Long, b: Long) = key((keyX(a) + keyX(b)) / 2, (keyY(a) + keyY(b)) / 2)
		fun refine(t: EquilateralTriangle) {
			leaves -= t
			val ab = mid(t.a, t.b); val bc = mid(t.b, t.c); val ca = mid(t.c, t.a)
			val s = t.size / 2
			add(EquilateralTriangle(t.a, ab, ca, s)); add(EquilateralTriangle(ab, t.b, bc, s))
			add(EquilateralTriangle(ca, bc, t.c, s)); add(EquilateralTriangle(bc, ca, ab, s))
		}
		val width = box[2] - box[0]
		for (j in -1..rows) {
			val q = j * rootSize
			val from = floor((-q * 0.5) / rootSize).toInt() - 1
			val to = ceil((width / unit - q * 0.5) / rootSize).toInt() + 1
			for (i in from..to) {
				val p = i * rootSize
				add(EquilateralTriangle(key(p, q), key(p + rootSize, q), key(p, q + rootSize), rootSize))
				add(EquilateralTriangle(key(p + rootSize, q), key(p + rootSize, q + rootSize), key(p, q + rootSize), rootSize))
			}
		}
		while (queue.isNotEmpty() && leaves.size < MAX_CORE_TRIANGLES) {
			val t = queue.removeFirst()
			if (t !in leaves || t.size < 2) continue
			if (needsRefinement(field, world(t.a), world(t.b), world(t.c), t.size * unit, FINEST_FRACTAL)) refine(t)
		}
		queue.clear()
		fun edges(t: EquilateralTriangle) = listOf(t.a to t.b, t.b to t.c, t.c to t.a)
		do {
			var changed = false
			for (t in leaves.toList()) {
				if (t !in leaves || t.size < 2 || leaves.size >= MAX_CORE_TRIANGLES) continue
				var hanging = 0
				var deep = false
				for ((p, q) in edges(t)) {
					if (mid(p, q) !in vertices) continue
					hanging++
					if (t.size % 4 == 0) {
						val dx = (keyX(q) - keyX(p)) / 4; val dy = (keyY(q) - keyY(p)) / 4
						if (key(keyX(p) + dx, keyY(p) + dy) in vertices || key(keyX(p) + dx * 3, keyY(p) + dy * 3) in vertices) deep = true
					}
				}
				if (deep || hanging >= 2) { refine(t); changed = true }
			}
		} while (changed)
		val output = mutableListOf<LatticeTriangle>()
		for (t in leaves) {
			val s = t.size * unit
			val hanging = if (t.size < 2) null else edges(t).firstOrNull { (p, q) -> mid(p, q) in vertices }
			if (hanging == null) { output += LatticeTriangle(t.a, t.b, t.c, s); continue }
			val (p, q) = hanging
			val o = listOf(t.a, t.b, t.c).first { it != p && it != q }
			val m = mid(p, q)
			output += LatticeTriangle(p, m, o, s * 0.5)
			output += LatticeTriangle(m, q, o, s * 0.5)
		}
		return output to world
	}

	// ---------------------------------------------------------------- lattice core + gap

	private fun coreLayout(field: SizingField, lattice: Pair<List<LatticeTriangle>, (Long) -> Point>): Layout? {
		val (candidates, world) = lattice
		val positions = HashMap<Long, Point>()
		val clearance = HashMap<Long, Double>()
		fun point(k: Long) = positions.getOrPut(k) { snap(world(k)) }
		fun depth(p: Point): Double = if (field.inside(p)) field.distance(p) else -1.0
		fun vertexDepth(k: Long) = clearance.getOrPut(k) { depth(point(k)) }
		val vertexIds = HashMap<Long, Int>()
		val corePoints = mutableListOf<Point>()
		val kept = mutableListOf<IntArray>()
		for (t in candidates) {
			val limit = CLEARANCE * t.size
			if (vertexDepth(t.a) < limit || vertexDepth(t.b) < limit || vertexDepth(t.c) < limit) continue
			val a = point(t.a); val b = point(t.b); val c = point(t.c)
			val probes = listOf(lerp(a, b, 0.5), lerp(b, c, 0.5), lerp(c, a, 0.5),
				Point((a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3))
			if (probes.any { depth(it) < limit * 0.5 }) continue
			val ids = listOf(t.a, t.b, t.c).map { k -> vertexIds.getOrPut(k) { corePoints += point(k); corePoints.lastIndex } }
			kept += if (cross(a, b, c) > 0) intArrayOf(ids[0], ids[1], ids[2]) else intArrayOf(ids[0], ids[2], ids[1])
		}
		val domainPoints = field.loops.flatten()
		var cursor = 0
		val domainIds = field.loops.map { loop -> IntArray(loop.size) { cursor + it }.also { cursor += loop.size } }
		val cleaned = cleanCore(kept)
		if (cleaned.isEmpty()) {
			return Layout(domainPoints, emptyList(), listOf(Region(domainIds, emptyList(), field.fine)), corePoints)
		}
		// Compact the surviving core vertices after the domain vertices.
		val remap = HashMap<Int, Int>()
		val points = domainPoints.toMutableList()
		fun global(id: Int) = remap.getOrPut(id) { points += corePoints[id]; points.lastIndex }
		val triangles = cleaned.map { Triangle(global(it[0]), global(it[1]), global(it[2])) }
		val coreLoops = traceBoundary(triangles.map { intArrayOf(it.a, it.b, it.c) }) ?: return null
		val regions = gapRegions(points, domainIds, coreLoops) ?: return null
		// Where the core recedes (curved or diagonal contours), contour-spaced points keep the gap free of fans.
		return Layout(points, triangles, regions.map { loops ->
			Region(loops, hexLattice(loops.map { ids -> ids.map { points[it] } }, field.fine, GAP_CLEARANCE), field.fine)
		}, corePoints)
	}

	/** Removes isolated triangles and rim triangles at pinch vertices until the core is a manifold. */
	private fun cleanCore(input: List<IntArray>): List<IntArray> {
		var triangles = input
		repeat(64) {
			val directed = HashSet<Long>()
			for (t in triangles) for (s in 0..2) directed += pair(t[s], t[(s + 1) % 3])
			val outgoing = HashMap<Int, Int>()
			val boundaryCount = IntArray(triangles.size)
			for ((index, t) in triangles.withIndex()) for (s in 0..2) {
				val a = t[s]; val b = t[(s + 1) % 3]
				if (pair(b, a) !in directed) { boundaryCount[index]++; outgoing[a] = (outgoing[a] ?: 0) + 1 }
			}
			val pinches = outgoing.filterValues { it > 1 }.keys
			val survivors = triangles.filterIndexed { index, t ->
				boundaryCount[index] < 3 && !(boundaryCount[index] > 0 && t.any { it in pinches })
			}
			if (survivors.size == triangles.size) return triangles
			triangles = survivors
		}
		return triangles
	}

	private fun pair(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

	/** Boundary loops of a manifold, consistently wound triangle set (outer loops positive). */
	private fun traceBoundary(triangles: List<IntArray>): List<IntArray>? {
		val directed = HashSet<Long>()
		for (t in triangles) for (s in 0..2) directed += pair(t[s], t[(s + 1) % 3])
		val next = HashMap<Int, Int>()
		for (t in triangles) for (s in 0..2) {
			val a = t[s]; val b = t[(s + 1) % 3]
			if (pair(b, a) !in directed) { if (next.put(a, b) != null) return null }
		}
		val loops = mutableListOf<IntArray>()
		val visited = HashSet<Int>()
		for (start in next.keys) {
			if (start in visited) continue
			val loop = mutableListOf<Int>()
			var v = start
			while (v !in visited) {
				visited += v; loop += v
				v = next[v] ?: return null
			}
			if (v != start || loop.size < 3) return null
			loops += loop.toIntArray()
		}
		return loops
	}

	/**
	 * Faces of (domain minus core). A face is bounded outside by a domain outer loop or a core hole
	 * loop, and inside by the loops directly nested in it. Faces are returned with a positive outer
	 * loop followed by negative hole loops, as the triangulator expects.
	 */
	private fun gapRegions(points: List<Point>, domain: List<IntArray>, core: List<IntArray>): List<List<IntArray>>? {
		val loops = domain + core
		val polygons = loops.map { loop -> loop.map { points[it] } }
		val areas = polygons.map { signedAreaTwice(it) }
		val parents = IntArray(loops.size) { -1 }
		for (i in loops.indices) {
			var best = -1
			for (j in loops.indices) {
				if (i == j || abs(areas[j]) <= abs(areas[i])) continue
				if (!pointInPolygon(polygons[i][0], polygons[j])) continue
				if (best < 0 || abs(areas[j]) < abs(areas[best])) best = j
			}
			parents[i] = best
		}
		fun oriented(index: Int, positive: Boolean): IntArray =
			if ((areas[index] > 0) == positive) loops[index] else loops[index].reversedArray()
		fun bindsFace(i: Int) = i == 0 || (i >= domain.size && areas[i] < 0)
		// Domain holes and core outer loops must sit directly inside a face-bounding loop.
		if (loops.indices.any { !bindsFace(it) && (parents[it] < 0 || !bindsFace(parents[it])) }) return null
		if (loops.indices.any { it > 0 && bindsFace(it) && (parents[it] < 0 || bindsFace(parents[it])) }) return null
		return loops.indices.filter(::bindsFace).map { i ->
			listOf(oriented(i, true)) + loops.indices.filter { parents[it] == i }.map { oriented(it, false) }
		}
	}

	// ---------------------------------------------------------------- contour paving

	/**
	 * Rows advance inward from the domain loops. Rows with equal counts are staggered (`/\/\`); when
	 * the sizing field reaches about sqrt(2) times the current spacing, a 2:1 row halves the count.
	 * A loop stops at a bottleneck, a collapse, or once its spacing reaches the interior density; the
	 * remaining center is filled with a triangular lattice.
	 */
	private fun pavingLayout(field: SizingField): Layout? {
		val points = field.loops.flatten().toMutableList()
		var cursor = 0
		val rows = field.loops.map { loop -> IntArray(loop.size) { cursor + it }.also { cursor += loop.size } }.toMutableList()
		val depths = DoubleArray(rows.size)
		val active = BooleanArray(rows.size) { true }
		val triangles = mutableListOf<Triangle>()
		repeat(24) {
			if (active.none { it }) return@repeat
			for (i in rows.indices) {
				if (!active[i]) continue
				val row = rows[i]
				val next = advanceRow(field, points, rows, i, depths[i])
				if (next == null) { active[i] = false; continue }
				val (positions, anchors, step) = next
				val ids = IntArray(positions.size) { points.size + it }
				points += positions
				triangles += EdgeBandBuilder.zipRows(row, ids, anchors)
				rows[i] = ids
				depths[i] += step
			}
		}
		val free = rows.map { row -> row.map { points[it] } }
		if (!validDomain(free)) return null
		val paved = points.drop(field.loops.sumOf { it.size })
		// The center continues the gradation from the last row up to the interior density.
		val segments = free.flatMap { loop -> loop.indices.map { distance(loop[it], loop[(it + 1) % loop.size]) } }.sorted()
		val centerField = SizingField(free, segments[segments.size / 2].coerceAtLeast(2.0), field.coarse)
		val center = coreLayout(centerField, fractalTriangles(centerField))
		if (center == null) {
			val candidates = hexLattice(free, centerField.coarse)
			return Layout(points, triangles, listOf(Region(rows.toList(), candidates, centerField.fine)), paved + candidates)
		}
		val rowIds = rows.flatMap { it.toList() }
		val shift = points.size - rowIds.size
		fun global(local: Int) = if (local < rowIds.size) rowIds[local] else local + shift
		points += center.points.drop(rowIds.size)
		triangles += center.triangles.map { Triangle(global(it.a), global(it.b), global(it.c)) }
		val regions = center.regions.map { region ->
			Region(region.loops.map { loop -> IntArray(loop.size) { global(loop[it]) } }, region.candidates, region.spacing)
		}
		return Layout(points, triangles, regions, paved + center.interior)
	}

	private data class RowStep(val positions: List<Point>, val anchors: IntArray, val step: Double)

	private fun advanceRow(field: SizingField, points: List<Point>, rows: List<IntArray>, index: Int, depth: Double): RowStep? {
		val row = rows[index].map { points[it] }
		val n = row.size
		if (n < 6) return null
		val segments = DoubleArray(n) { distance(row[it], row[(it + 1) % n]) }
		val spacing = segments.average()
		val target = field.target(depth)
		val coarsen = n >= 12 && target >= spacing * 1.41 && spacing * 2 <= field.coarse * 1.41
		if (!coarsen && target >= field.coarse * 0.95) return null
		// Offset rows cross where the normal turns faster than 1/offset. A [1,2,1] pass adds half a
		// sample of variance, so spread the turn over about offset/spacing samples.
		val reach = if (coarsen) 2 * SQRT3_2 else SQRT3_2
		val (nx, ny) = EdgeBandBuilder.smoothedNormals(row, max(2, ceil(2 * reach * reach).toInt()))
		val anchors: IntArray
		val bases: List<Point>
		val normals: List<Pair<Double, Double>>
		val nominal: DoubleArray
		if (coarsen) {
			val m = n / 2
			anchors = IntArray(m) { (2 * it + 1) % n }
			bases = anchors.map { row[it] }
			normals = anchors.map { nx[it] to ny[it] }
			nominal = DoubleArray(m) {
				val a = anchors[it]
				val local = (segments[(a - 1 + n) % n] + segments[a]) * 0.5
				2 * SQRT3_2 * (local + spacing) * 0.5
			}
		} else {
			anchors = IntArray(n) { it }
			bases = List(n) { lerp(row[it], row[(it + 1) % n], 0.5) }
			normals = List(n) {
				val x = nx[it] + nx[(it + 1) % n]; val y = ny[it] + ny[(it + 1) % n]
				val length = hypot(x, y).coerceAtLeast(1e-9)
				x / length to y / length
			}
			nominal = DoubleArray(n) { SQRT3_2 * (segments[it] + spacing) * 0.5 }
		}
		val boundary = SegmentIndex(rows.map { ids -> ids.map { points[it] } }, spacing)
		val m = bases.size
		val amounts = DoubleArray(m) { j ->
			min(nominal[j], 0.45 * boundary.rayDistance(bases[j], normals[j].first, normals[j].second))
		}
		val arcs = DoubleArray(m) { distance(bases[it], bases[(it + 1) % m]) }
		EdgeBandBuilder.minSmooth(amounts, arcs, 0.4)
		if ((0 until m).any { amounts[it] < nominal[it] * 0.6 }) return null
		val positions = List(m) { j ->
			snap(Point(bases[j].x + normals[j].first * amounts[j], bases[j].y + normals[j].second * amounts[j]))
		}
		if (hasSelfIntersection(positions) || abs(signedAreaTwice(positions)) < 1e-6) return null
		for ((other, ids) in rows.withIndex()) {
			if (other != index && loopsTouch(positions, ids.map { points[it] })) return null
		}
		// Local ids: current row first, then the candidate row.
		val local = row + positions
		val outerIds = IntArray(n) { it }
		val innerIds = IntArray(m) { n + it }
		for (t in EdgeBandBuilder.zipRows(outerIds, innerIds, anchors)) {
			val a = local[t.a]; val b = local[t.b]; val c = local[t.c]
			val longest = max(distanceSquared(a, b), max(distanceSquared(b, c), distanceSquared(c, a)))
			if (cross(a, b, c) <= longest * 0.08) return null
		}
		return RowStep(positions, anchors, amounts.average())
	}

	/** Triangular lattice points inside [loops], at least [clearance] spacings from every loop. */
	fun hexLattice(loops: List<List<Point>>, spacing: Double, clearance: Double = CLEARANCE): List<Point> {
		if (spacing <= 0) return emptyList()
		val box = bounds(loops)
		val index = SegmentIndex(loops, spacing)
		val rowStep = spacing * SQRT3_2
		val points = mutableListOf<Point>()
		val rowCount = floor((box[3] - box[1]) / rowStep).toInt()
		val originY = box[1] + ((box[3] - box[1]) - rowCount * rowStep) * 0.5
		for (row in 0..rowCount) {
			val y = originY + row * rowStep
			var x = box[0] + if (row % 2 == 0) 0.0 else spacing * 0.5
			while (x <= box[2]) {
				val p = Point(x, y)
				if (inDomain(p, loops) && index.distance(p) >= clearance * spacing) points += p
				x += spacing
			}
		}
		return points
	}
}
