package org.umamo.edit

import org.umamo.runtime.model.DrawableMesh
import kotlin.math.abs

/*
 * Mesh refinement: the ops that re-triangulate a mesh without changing what it draws.
 *
 * ## The one rule
 *
 * A drawable's UV is an affine function of its position - `uv = Φ(p)` - and that is what lets the canvas
 * move a vertex without moving the picture. So a NEW vertex is only safe when its position and its UV are
 * the SAME affine combination of the SAME source vertices: `p = Σ wᵢ·pᵢ` and `u = Σ wᵢ·uᵢ` with `Σ wᵢ = 1`.
 *
 * [MeshAppender] is that one place. It is the only thing in this file that creates a vertex and the only
 * thing that writes a [VertexSource]. Compute both from one set of weights there, and never compute a
 * position one way and a UV another - that divergence is exactly the drift this rule exists to prevent.
 *
 * ## The append contract
 *
 * An op that CREATES vertices keeps every existing vertex at its existing index and appends the created
 * ones at the tail, in a deterministic order. The editor leans on this to select what an op just made
 * (`oldCount until newCount`) without the model having to report indices back through the journal.
 * Everything in this file satisfies it. `MeshTopologyOps`' merge and delete do not - they remove vertices
 * and therefore renumber - which is why the editor reads their result through a different route.
 *
 * メッシュの再分割。新しい頂点は、位置と UV を同じ重みで作る（`MeshAppender` が唯一の生成点）。
 */

/** How close to an endpoint a split may land. Below this a new vertex is indistinguishable from one. */
private const val EDGE_T_EPS = 1e-4f

/**
 * The only place in this file that creates a vertex.
 *
 * Both halves of a new vertex - where it sits and which texel it samples - are computed from one set of
 * weights, in one call. That is the whole design: the file KDoc's rule is not something a caller has to
 * remember, it is the only thing this class can do.
 *
 * Created vertices are appended after the old ones, which is the append contract; [baseCount] is
 * therefore both the first new index and the old vertex count.
 */
private class MeshAppender(private val old: DrawableMesh) {
	val baseCount = old.vertexCount
	val sources = ArrayList<VertexSource>()

	private val created = ArrayList<Float>()

	/** Reads a source vertex's [component] (0 = x/u, 1 = y/v), or 0 when the array does not carry it. */
	private fun at(values: FloatArray, index: Int, component: Int): Float {
		val slot = index * 2 + component
		return if (index in 0 until baseCount && slot < values.size) values[slot] else 0f
	}

	private fun add(source: VertexSource, x: Float, y: Float, u: Float, v: Float): Int {
		sources += source
		created += x
		created += y
		created += u
		created += v
		return baseCount + sources.size - 1
	}

	/** A point at parameter [t] along the old edge ([oldA], [oldB]). */
	fun appendLerp(oldA: Int, oldB: Int, t: Float): Int {
		fun mix(values: FloatArray, component: Int): Float {
			val a = at(values, oldA, component)
			return a + (at(values, oldB, component) - a) * t
		}
		return add(
			VertexSource.LerpOf(oldA, oldB, t),
			mix(old.positions, 0), mix(old.positions, 1),
			mix(old.uvs, 0), mix(old.uvs, 1),
		)
	}

	/** The interior point of the old triangle ([oldA], [oldB], [oldC]) at those barycentric weights. */
	fun appendBarycentric(oldA: Int, oldB: Int, oldC: Int, wa: Float, wb: Float, wc: Float): Int {
		fun mix(values: FloatArray, component: Int): Float =
			at(values, oldA, component) * wa + at(values, oldB, component) * wb + at(values, oldC, component) * wc
		return add(
			VertexSource.BarycentricOf(oldA, oldB, oldC, wa, wb, wc),
			mix(old.positions, 0), mix(old.positions, 1),
			mix(old.uvs, 0), mix(old.uvs, 1),
		)
	}

	/**
	 * The edit for [indices], with the old vertices untouched at their existing indices and the created
	 * ones appended - each carried by the [VertexSource] it was created with.
	 */
	fun build(indices: IntArray): MeshTopologyEdit {
		val count = sources.size
		val positions = FloatArray((baseCount + count) * 2)
		val uvs = FloatArray((baseCount + count) * 2)
		old.positions.copyInto(positions, 0, 0, minOf(old.positions.size, baseCount * 2))
		old.uvs.copyInto(uvs, 0, 0, minOf(old.uvs.size, baseCount * 2))
		// `created` interleaves position and UV - four floats per vertex - so it is walked per vertex,
		// not per array. Reading it as two interleaved-by-two runs is what put a position in the UV
		// array and ran off the end of it.
		for (index in 0 until count) {
			val source = index * 4
			val target = (baseCount + index) * 2
			positions[target] = created[source]
			positions[target + 1] = created[source + 1]
			uvs[target] = created[source + 2]
			uvs[target + 1] = created[source + 3]
		}
		val allSources = ArrayList<VertexSource>(baseCount + count)
		for (index in 0 until baseCount) {
			allSources += VertexSource.FromOld(index)
		}
		allSources += sources
		return MeshTopologyEdit(DrawableMesh(positions, uvs, indices), allSources)
	}
}

/**
 * Emits the quad (a, m, m2, b) - what a triangle becomes once a wedge corner has been cut off - split
 * along the shorter of its two diagonals.
 *
 * The shorter one because the other leaves a sliver wherever the split edge's midpoint sits close to a
 * corner, and a sliver is exactly what a later ear clipper or edge collapse turns into degenerate output.
 * Ties go to (m, b).
 */
private fun addQuad(target: MutableList<Int>, positions: FloatArray, a: Int, m: Int, m2: Int, b: Int) {
	if (distanceSquared(positions, m, b) <= distanceSquared(positions, a, m2)) {
		target += listOf(a, m, b, m, m2, b)
	} else {
		target += listOf(a, m, m2, a, m2, b)
	}
}

/** Squared distance between two vertices of [positions]; squared so the comparison stays exact. */
private fun distanceSquared(positions: FloatArray, a: Int, b: Int): Float {
	val dx = positions[a * 2] - positions[b * 2]
	val dy = positions[a * 2 + 1] - positions[b * 2 + 1]
	return dx * dx + dy * dy
}

/**
 * Where [a] and [b] appear as consecutive corners of [triangle], in the triangle's winding order, or -1.
 *
 * The slot matters, not just the fact of a shared edge: emitting the two halves from that slot is what
 * preserves the original winding, and nothing here measures area to find out.
 */
private fun edgeSlot(triangle: List<Int>, a: Int, b: Int): Int {
	for (slot in 0..2) {
		if ((triangle[slot] == a && triangle[(slot + 1) % 3] == b) || (triangle[slot] == b && triangle[(slot + 1) % 3] == a)) {
			return slot
		}
	}
	return -1
}

/**
 * The refinement ops. Each reads one mesh and returns a [TopologyOpResult] for `withMeshTopologyEdit` to
 * commit, or null to refuse; none of them touches the model.
 *
 * メッシュ再分割の純粋な構築関数。
 */
object MeshRefinementOps {
	/**
	 * Splits the edge ([a], [b]) of [mesh] at parameter [t], inserting one vertex and re-triangulating
	 * every triangle that used the edge: each becomes two, the new corner being the inserted point.
	 *
	 * This generalises what was the `split` action's inline body, which hardcoded `t = 0.5` and always
	 * took the first qualifying edge. The retriangulation is unchanged - `(x, n, z)` and `(n, y, z)` from
	 * the edge's winding slot - and is the k = 1 case of the region subdivision to come.
	 *
	 * [t] is clamped away from both endpoints so the inserted vertex can never coincide with one of them,
	 * matching the epsilon discipline the segment-crossing code already uses. The created vertex is
	 * appended, so it is the last index of the result.
	 *
	 * @param DrawableMesh mesh The mesh to split.
	 * @param MeshElement.Edge edge The edge to split.
	 * @param Float t Where along the edge, 0 at [MeshElement.Edge.endpointLow].
	 * @return TopologyOpResult? The edit, or null when the edge is not part of the mesh.
	 */
	fun insertPointOnEdge(mesh: DrawableMesh, edge: MeshElement.Edge, t: Float): TopologyOpResult? {
		val a = edge.endpointLow
		val b = edge.endpointHigh
		if (a == b || a !in 0 until mesh.vertexCount || b !in 0 until mesh.vertexCount) return null
		val triangles = mesh.indices.toList().chunked(3)
		if (triangles.none { edgeSlot(it, a, b) >= 0 }) return null

		val appender = MeshAppender(mesh)
		val inserted = appender.appendLerp(a, b, t.coerceIn(EDGE_T_EPS, 1f - EDGE_T_EPS))
		val rebuild = ArrayList<Int>(mesh.indices.size + 6)
		for (triangle in triangles) {
			val slot = edgeSlot(triangle, a, b)
			if (slot < 0) {
				rebuild += triangle
				continue
			}
			val x = triangle[slot]
			val y = triangle[(slot + 1) % 3]
			val z = triangle[(slot + 2) % 3]
			rebuild += listOf(x, inserted, z, inserted, y, z)
		}
		return TopologyOpResult(appender.build(rebuild.toIntArray()), setOf(MeshElement.Vertex(inserted)))
	}

	/** A point to insert inside one triangle, at barycentric weights over its three corners. */
	class FacePoint(val triangleIndex: Int, val wa: Float, val wb: Float, val wc: Float)

	/**
	 * Splits every edge in [edges] at its midpoint and re-triangulates each triangle that touches one: a
	 * triangle with k split edges becomes k + 1 triangles.
	 *
	 * **No propagation pass is needed, and none is done.** Every triangle touching a split edge is rebuilt
	 * here, so a T-junction cannot survive. That is also why the classic red/green distinction - which
	 * exists to stop a split spreading - has no role: nothing is spreading.
	 *
	 * @param DrawableMesh mesh The mesh to subdivide.
	 * @param Set<MeshElement.Edge> edges The edges to split at their midpoints.
	 * @return TopologyOpResult? The edit and the created midpoints, or null when no edge applies.
	 */
	fun subdivideEdges(mesh: DrawableMesh, edges: Set<MeshElement.Edge>): TopologyOpResult? =
		refineTriangles(mesh, edges, emptyList())

	/**
	 * Inserts one point inside a triangle at those barycentric weights, splitting it 1 -> 3.
	 *
	 * @param DrawableMesh mesh The mesh to edit.
	 * @param Int triangleIndex The triangle to split.
	 * @param Float wa The weight on the triangle's first corner; likewise wb, wc.
	 * @return TopologyOpResult? The edit and the created point, or null when the weights do not apply.
	 */
	fun insertPointInFace(mesh: DrawableMesh, triangleIndex: Int, wa: Float, wb: Float, wc: Float): TopologyOpResult? =
		refineTriangles(mesh, emptySet(), listOf(FacePoint(triangleIndex, wa, wb, wc)))

	/**
	 * The one refinement pass: midpoint-split [edges], insert [facePoints], and rebuild every triangle
	 * either touched. Both public entry points above are this with one side empty, so the case analysis
	 * and the winding rules live once.
	 *
	 * A triangle carrying an interior point is fanned from it over its own (possibly subdivided) boundary,
	 * which covers the no-point cases' neighbours uniformly and keeps the winding. At most one interior
	 * point per triangle per pass: a second one would land in a sub-triangle that does not exist yet, and
	 * the pass is single-shot by design - so it refuses rather than dropping the extra point silently.
	 *
	 * Every created vertex is an affine combination of that triangle's ORIGINAL corners, so its position
	 * and its UV are the same combination and the picture does not move.
	 *
	 * @param DrawableMesh mesh The mesh to refine.
	 * @param Set<MeshElement.Edge> edges The edges to split at their midpoints.
	 * @param List<FacePoint> facePoints The interior points to insert.
	 * @return TopologyOpResult? The edit and everything it created, or null when nothing applies.
	 */
	fun refineTriangles(mesh: DrawableMesh, edges: Set<MeshElement.Edge>, facePoints: List<FacePoint>): TopologyOpResult? {
		val existingEdges = MeshTopology.uniqueEdges(mesh.indices).toHashSet()
		val ordered = edges
			.filter { it in existingEdges && it.endpointLow != it.endpointHigh && it.endpointLow in 0 until mesh.vertexCount && it.endpointHigh in 0 until mesh.vertexCount }
			.sortedWith(compareBy({ it.endpointLow }, { it.endpointHigh }))
		val byTriangle = facePoints
			.filter { it.triangleIndex in 0 until mesh.indices.size / 3 }
			.groupBy { it.triangleIndex }
		if (ordered.isEmpty() && byTriangle.isEmpty()) return null
		if (byTriangle.values.any { it.size > 1 }) return null

		val appender = MeshAppender(mesh)
		val midpoint = HashMap<MeshElement.Edge, Int>(ordered.size)
		for (edge in ordered) {
			midpoint[edge] = appender.appendLerp(edge.endpointLow, edge.endpointHigh, 0.5f)
		}

		// The two-edge case compares diagonals through new midpoints; include their coordinates.
		val positions = appender.build(mesh.indices).newMesh.positions
		val created = LinkedHashSet<Int>()
		midpoint.values.forEach { created += it }
		val rebuild = ArrayList<Int>(mesh.indices.size * 2)
		mesh.indices.toList().chunked(3).forEachIndexed { ordinal, triangle ->
			val (c0, c1, c2) = triangle
			val m0 = midpoint[MeshElement.Edge.of(c0, c1)]
			val m1 = midpoint[MeshElement.Edge.of(c1, c2)]
			val m2 = midpoint[MeshElement.Edge.of(c2, c0)]

			val interior = byTriangle[ordinal]?.first()
			if (interior != null) {
				// Fan from the interior point over the boundary walk, in the triangle's winding order, so
				// every wedge keeps the original orientation without anything measuring area.
				val centre = appender.appendBarycentric(c0, c1, c2, interior.wa, interior.wb, interior.wc)
				created += centre
				val boundary = listOfNotNull(c0, m0, c1, m1, c2, m2)
				for (slot in boundary.indices) {
					rebuild += listOf(boundary[slot], boundary[(slot + 1) % boundary.size], centre)
				}
				return@forEachIndexed
			}

			when (listOf(m0, m1, m2).count { it != null }) {
				0 -> rebuild += triangle

				1 -> {
					// The same emission insertPointOnEdge makes, so a one-edge subdivide and a `split`
					// cannot disagree.
					val slot = listOf(m0, m1, m2).indexOfFirst { it != null }
					val corners = listOf(c0, c1, c2)
					val m = listOf(m0, m1, m2)[slot]!!
					rebuild += listOf(corners[slot], m, corners[(slot + 2) % 3])
					rebuild += listOf(m, corners[(slot + 1) % 3], corners[(slot + 2) % 3])
				}

				2 -> {
					// Written out rather than rotated into a canonical case: the rotation is exactly where
					// this would go wrong, and there are only three cases.
					when {
						// Split edges are (c0,c1) and (c1,c2): the wedge at c1 survives intact.
						m2 == null -> {
							rebuild += listOf(m0!!, c1, m1!!)
							addQuad(rebuild, positions, c0, m0, m1, c2)
						}
						// Split edges are (c1,c2) and (c2,c0): the wedge at c2 survives intact.
						m0 == null -> {
							rebuild += listOf(m1!!, c2, m2)
							addQuad(rebuild, positions, c1, m1, m2, c0)
						}
						// Split edges are (c2,c0) and (c0,c1): the wedge at c0 survives intact.
						else -> {
							rebuild += listOf(m2, c0, m0)
							addQuad(rebuild, positions, c2, m2, m0, c1)
						}
					}
				}

				else -> {
					// The medial triangle is the affine image of the outer one, so its winding follows.
					rebuild += listOf(c0, m0!!, m2!!)
					rebuild += listOf(m0, c1, m1!!)
					rebuild += listOf(m2, m1, c2)
					rebuild += listOf(m0, m1, m2)
				}
			}
		}
		return TopologyOpResult(
			appender.build(rebuild.toIntArray()),
			created.mapTo(LinkedHashSet()) { MeshElement.Vertex(it) },
		)
	}

	/**
	 * Splits the edge ([a], [b]) at several parameters at once, inserting one vertex per cut.
	 *
	 * [insertPointOnEdge] is this with a single parameter. Several at once is what a knife polyline needs
	 * when two of its anchors land on the same edge: resolving them one at a time would put the second
	 * point on a sub-edge of the first, and a vertex lerped on a vertex this same gesture created has no
	 * `VertexSource` - so the whole cut would have to be refused for a perfectly ordinary gesture.
	 *
	 * Each adjacent triangle is fanned from its opposite corner through the cut chain, which keeps the
	 * winding, and the chain is reversed for whichever of the two triangles walks the edge the other way.
	 *
	 * @param DrawableMesh mesh The mesh to split.
	 * @param MeshElement.Edge edge The edge to split.
	 * @param List<Float> ts Where along the edge, 0 at [MeshElement.Edge.endpointLow].
	 * @return TopologyOpResult? The edit and the created vertices, or null when the edge is not there.
	 */
	fun splitEdgeAt(mesh: DrawableMesh, edge: MeshElement.Edge, ts: List<Float>): TopologyOpResult? {
		val a = edge.endpointLow
		val b = edge.endpointHigh
		if (a == b || a !in 0 until mesh.vertexCount || b !in 0 until mesh.vertexCount) return null
		val cuts = ts.map { it.coerceIn(EDGE_T_EPS, 1f - EDGE_T_EPS) }.distinct().sorted()
		if (cuts.isEmpty()) return null
		val triangles = mesh.indices.toList().chunked(3)
		if (triangles.none { edgeSlot(it, a, b) >= 0 }) return null

		val appender = MeshAppender(mesh)
		// In a -> b order; each triangle re-orders this to match its own walk of the edge.
		val along = cuts.map { appender.appendLerp(a, b, it) }
		val rebuild = ArrayList<Int>(mesh.indices.size + cuts.size * 6)
		for (triangle in triangles) {
			val slot = edgeSlot(triangle, a, b)
			if (slot < 0) {
				rebuild += triangle
				continue
			}
			val x = triangle[slot]
			val y = triangle[(slot + 1) % 3]
			val z = triangle[(slot + 2) % 3]
			val chain = listOf(x) + (if (x == a) along else along.asReversed()) + listOf(y)
			for (index in 0 until chain.size - 1) {
				rebuild += listOf(chain[index], chain[index + 1], z)
			}
		}
		return TopologyOpResult(
			appender.build(rebuild.toIntArray()),
			along.mapTo(LinkedHashSet()) { MeshElement.Vertex(it) },
		)
	}

	/** One anchor of a knife polyline: a vertex the mesh already has, or a position to be created. */
	sealed interface KnifeAnchor {
		data class AtVertex(val index: Int) : KnifeAnchor

		/** A free position in the mesh own space; where it lands is decided when the cut is built. */
		data class AtPoint(val x: Float, val y: Float) : KnifeAnchor
	}

	/**
	 * Several append-only steps folded into one edit whose every [VertexSource] points at the ORIGINAL
	 * mesh, so the whole gesture commits once and keyform deltas are interpolated once.
	 */
	private class ComposedSteps(original: DrawableMesh) {
		private val originalCount = original.vertexCount
		var working = original
			private set
		val composed = ArrayList<VertexSource>(originalCount).apply { repeat(originalCount) { add(VertexSource.FromOld(it)) } }

		/** The created vertex [index] as weights over the original vertices. */
		fun rootTerms(index: Int): List<Pair<Int, Float>> = terms(composed[index])

		fun fold(result: TopologyOpResult): Boolean {
			val step = result.edit.vertexSources
			val previous = working.vertexCount
			if (step.size != result.edit.newMesh.vertexCount || step.size < previous) return false
			for (index in previous until step.size) {
				val weights = sortedMapOf<Int, Float>()
				for ((oldIndex, weight) in terms(step[index])) {
					if (oldIndex !in 0 until composed.size) return false
					for ((root, rootWeight) in terms(composed[oldIndex])) {
						if (root !in 0 until originalCount) return false
						weights[root] = (weights[root] ?: 0f) + weight * rootWeight
					}
				}
				composed += VertexSource.WeightedOf(weights.keys.toIntArray(), weights.values.toFloatArray())
			}
			working = result.edit.newMesh
			return true
		}

		fun edit(): MeshTopologyEdit = MeshTopologyEdit(working, composed)

		private fun terms(source: VertexSource): List<Pair<Int, Float>> = when (source) {
			is VertexSource.FromOld -> listOf(source.oldIndex to 1f)
			is VertexSource.AverageOf -> source.oldIndices.map { it to 1f / source.oldIndices.size }
			is VertexSource.LerpOf -> listOf(source.oldA to (1f - source.t), source.oldB to source.t)
			is VertexSource.BarycentricOf -> listOf(source.oldA to source.wa, source.oldB to source.wb, source.oldC to source.wc)
			is VertexSource.WeightedOf -> source.indices.indices.map { source.indices[it] to source.weights[it] }
		}
	}

	/**
	 * The result of [insertPoints]: the composed edit, the vertex each requested point became (null when
	 * it could not be placed), and [frame] extended with the created vertices.
	 */
	class PointInsertion(val result: TopologyOpResult, val placed: List<Int?>, val frame: FloatArray)

	/**
	 * Inserts one vertex at each of [points], all in one composed edit.
	 *
	 * [frame] is the mesh's vertices in the space [points] are given in - typically the deformed world
	 * positions - with the mesh's own topology. A point is located in [frame] (on an edge, inside a face,
	 * or, when [extend] allows, just outside the silhouette), and the created vertex is that same
	 * combination of the rest positions and UVs. So the picture does not move, and at the frame's pose
	 * the new vertex lands exactly on the requested point. Points are placed in order, so a later one can
	 * land in a triangle an earlier one created.
	 *
	 * @param DrawableMesh mesh The mesh to edit.
	 * @param FloatArray frame The mesh's vertex positions in the points' space.
	 * @param List<Pair<Float, Float>> points The positions to create vertices at.
	 * @param Boolean extend Whether a point outside the mesh may grow the silhouette to reach it.
	 * @param Float edgeSnap A point this close to an edge (in frame units) splits the edge at its projection instead.
	 * @return PointInsertion? The edit, or null when no point could be placed.
	 */
	fun insertPoints(
		mesh: DrawableMesh,
		frame: FloatArray,
		points: List<Pair<Float, Float>>,
		extend: Boolean = true,
		edgeSnap: Float = 0f,
	): PointInsertion? {
		if (frame.size != mesh.positions.size || points.isEmpty()) return null
		val originalFrame = frame.copyOf()
		val steps = ComposedSteps(mesh)
		var currentFrame = frame.copyOf()
		val placed = ArrayList<Int?>(points.size)
		for ((x, y) in points) {
			if (!x.isFinite() || !y.isFinite()) {
				placed += null
				continue
			}
			val working = steps.working
			val spot = locatePoint(currentFrame, working.indices, x, y, edgeSnap)
			val step = when (spot) {
				is PointSpot.OnEdge -> splitEdgeAt(working, spot.edge, listOf(spot.t))
				is PointSpot.InFace -> insertPointInFace(working, spot.triangleIndex, spot.wa, spot.wb, spot.wc)
				null -> if (extend) extendOutside(working, currentFrame, x, y, null) else null
			}
			if (step == null || !steps.fold(step)) {
				placed += null
				continue
			}
			val created = steps.working.vertexCount
			val grown = currentFrame.copyOf(created * 2)
			for (index in currentFrame.size / 2 until created) {
				var fx = 0f
				var fy = 0f
				for ((root, weight) in steps.rootTerms(index)) {
					fx += originalFrame[root * 2] * weight
					fy += originalFrame[root * 2 + 1] * weight
				}
				grown[index * 2] = fx
				grown[index * 2 + 1] = fy
			}
			currentFrame = grown
			placed += step.newElements.filterIsInstance<MeshElement.Vertex>().firstOrNull()?.index
		}
		if (placed.all { it == null }) return null
		val created = placed.filterNotNull().mapTo(LinkedHashSet()) { MeshElement.Vertex(it) }
		return PointInsertion(TopologyOpResult(steps.edit(), created), placed, currentFrame)
	}

	/**
	 * Flips the interior edges near [around] toward a Delaunay triangulation, judged in [frame].
	 *
	 * Inserting points one by one leaves the slivers every 1 -> 3 split makes; this is what tidies them.
	 * A flip is only taken when it cannot change the picture: the quad must be convex in the rest positions
	 * and in [frame], and its four UVs must lie on one affine map of position, so the two new triangles
	 * sample exactly the texels the old two did. No vertex is created or moved, so indices, UVs and every
	 * keyform stay valid.
	 *
	 * @param DrawableMesh mesh The mesh to tidy.
	 * @param FloatArray frame The mesh's vertices in the space quality is judged in.
	 * @param Set<Int> around Only edges with a vertex of this set among their four are considered.
	 * @param Int maxFlips A bound on the work, far above what one gesture needs.
	 * @return IntArray The new index buffer.
	 */
	fun flipTowardDelaunay(mesh: DrawableMesh, frame: FloatArray, around: Set<Int>, maxFlips: Int = 512): IntArray {
		val triangles = mesh.indices.toList().chunked(3).map { it.toIntArray() }.toMutableList()
		if (around.isEmpty() || frame.size != mesh.positions.size) return mesh.indices.copyOf()
		fun area(values: FloatArray, a: Int, b: Int, c: Int): Float =
			(values[b * 2] - values[a * 2]) * (values[c * 2 + 1] - values[a * 2 + 1]) -
				(values[b * 2 + 1] - values[a * 2 + 1]) * (values[c * 2] - values[a * 2])
		fun inCircle(a: Int, b: Int, c: Int, d: Int): Boolean {
			val ax = (frame[a * 2] - frame[d * 2]).toDouble(); val ay = (frame[a * 2 + 1] - frame[d * 2 + 1]).toDouble()
			val bx = (frame[b * 2] - frame[d * 2]).toDouble(); val by = (frame[b * 2 + 1] - frame[d * 2 + 1]).toDouble()
			val cx = (frame[c * 2] - frame[d * 2]).toDouble(); val cy = (frame[c * 2 + 1] - frame[d * 2 + 1]).toDouble()
			val det = (ax * ax + ay * ay) * (bx * cy - cx * by) - (bx * bx + by * by) * (ax * cy - cx * ay) +
				(cx * cx + cy * cy) * (ax * by - bx * ay)
			val orientation = area(frame, a, b, c)
			return if (orientation > 0f) det > 1e-9 else det < -1e-9
		}
		fun affineUv(a: Int, b: Int, c: Int, d: Int): Boolean {
			if (mesh.uvs.size != mesh.positions.size) return false
			val p = mesh.positions
			val weights = barycentricAt(p, a, b, c, p[d * 2], p[d * 2 + 1]) ?: return false
			for (component in 0..1) {
				val predicted = mesh.uvs[a * 2 + component] * weights[0] + mesh.uvs[b * 2 + component] * weights[1] +
					mesh.uvs[c * 2 + component] * weights[2]
				if (abs(predicted - mesh.uvs[d * 2 + component]) > 2e-4f) return false
			}
			return true
		}
		var flips = 0
		var changed = true
		while (changed && flips < maxFlips) {
			changed = false
			val owners = HashMap<MeshElement.Edge, MutableList<Int>>()
			triangles.forEachIndexed { ordinal, tri ->
				for (slot in 0..2) owners.getOrPut(MeshElement.Edge.of(tri[slot], tri[(slot + 1) % 3])) { ArrayList(2) } += ordinal
			}
			for ((edge, pair) in owners) {
				if (pair.size != 2) continue
				val first = triangles[pair[0]]
				val second = triangles[pair[1]]
				val slot = edgeSlot(first.toList(), edge.endpointLow, edge.endpointHigh)
				if (slot < 0) continue
				val a = first[slot]
				val b = first[(slot + 1) % 3]
				val c = first[(slot + 2) % 3]
				val d = second.firstOrNull { it != a && it != b } ?: continue
				if (a !in around && b !in around && c !in around && d !in around) continue
				// The shared edge must run the other way in the second triangle, or the pair is not a proper quad.
				val otherSlot = edgeSlot(second.toList(), b, a)
				if (otherSlot < 0 || second[otherSlot] != b) continue
				if (!inCircle(a, b, c, d)) continue
				val keep = area(mesh.positions, a, b, c)
				val keepFrame = area(frame, a, b, c)
				val convex = listOf(intArrayOf(a, d, c), intArrayOf(d, b, c)).all { tri ->
					val rest = area(mesh.positions, tri[0], tri[1], tri[2])
					val seen = area(frame, tri[0], tri[1], tri[2])
					rest * keep > 0f && seen * keepFrame > 0f &&
						abs(rest) > abs(keep) * 1e-4f && abs(seen) > abs(keepFrame) * 1e-4f
				}
				if (!convex || !affineUv(a, b, c, d)) continue
				triangles[pair[0]] = intArrayOf(a, d, c)
				triangles[pair[1]] = intArrayOf(d, b, c)
				flips++
				changed = true
				break
			}
		}
		return triangles.flatMap { it.toList() }.toIntArray()
	}

	/** A knife gesture is applied incrementally, so each new anchor can lie in a triangle created by the previous segment. */
	fun knifeCut(mesh: DrawableMesh, anchors: List<KnifeAnchor>): TopologyOpResult? {
		if (anchors.size < 2) return null
		val originalCount = mesh.vertexCount
		val steps = ComposedSteps(mesh)
		val resolved = ArrayList<Int>(anchors.size)
		for (anchor in anchors) {
			val current = when (anchor) {
				is KnifeAnchor.AtVertex -> anchor.index.takeIf { it in 0 until originalCount } ?: return null
				is KnifeAnchor.AtPoint -> {
					if (!anchor.x.isFinite() || !anchor.y.isFinite()) return null
					val working = steps.working
					val spot = locatePoint(working.positions, working.indices, anchor.x, anchor.y)
					val placed = when (spot) {
						is PointSpot.OnEdge -> splitEdgeAt(working, spot.edge, listOf(spot.t))
						is PointSpot.InFace -> insertPointInFace(working, spot.triangleIndex, spot.wa, spot.wb, spot.wc)
						null -> extendOutside(working, working.positions, anchor.x, anchor.y, resolved.lastOrNull())
					} ?: return null
					if (!steps.fold(placed)) return null
					placed.newElements.filterIsInstance<MeshElement.Vertex>().firstOrNull()?.index ?: return null
				}
			}
			val previous = resolved.lastOrNull()
			if (previous != null) {
				if (previous == current) return null
				val edge = MeshElement.Edge.of(previous, current)
				if (edge !in MeshTopology.uniqueEdges(steps.working.indices)) {
					val joined = MeshTopologyOps.connectVertices(steps.working, previous, current) ?: return null
					if (!steps.fold(joined)) return null
				}
			}
			resolved += current
		}
		return TopologyOpResult(steps.edit(), resolved.mapTo(LinkedHashSet()) { MeshElement.Vertex(it) })
	}

	/** Extend the silhouette to a freely placed exterior point. Its UV and pose deltas extrapolate from the adjacent face. */
	private fun extendOutside(mesh: DrawableMesh, frame: FloatArray, x: Float, y: Float, preferred: Int?): TopologyOpResult? {
		val triangles = mesh.indices.toList().chunked(3)
		val adjacency = HashMap<MeshElement.Edge, MutableList<Int>>()
		triangles.forEachIndexed { index, tri ->
			for (slot in 0..2) adjacency.getOrPut(MeshElement.Edge.of(tri[slot], tri[(slot + 1) % 3])) { mutableListOf() } += index
		}
		val boundary = adjacency.filterValues { it.size == 1 }
		fun properCross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
			val abx = bx - ax; val aby = by - ay
			val cdx = dx - cx; val cdy = dy - cy
			val denominator = abx * cdy - aby * cdx
			if (abs(denominator) < 1e-8f) return false
			val t = ((cx - ax) * cdy - (cy - ay) * cdx) / denominator
			val u = ((cx - ax) * aby - (cy - ay) * abx) / denominator
			return t > 1e-6f && t < 1f - 1e-6f && u > 1e-6f && u < 1f - 1e-6f
		}
		val candidates = boundary.mapNotNull { (edge, owners) ->
			val tri = triangles[owners.single()]
			val slot = edgeSlot(tri, edge.endpointLow, edge.endpointHigh)
			val a = tri[slot]; val b = tri[(slot + 1) % 3]; val c = tri[(slot + 2) % 3]
			val ax = frame[a * 2]; val ay = frame[a * 2 + 1]
			val bx = frame[b * 2]; val by = frame[b * 2 + 1]
			val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
			val inside = (bx - ax) * (frame[c * 2 + 1] - ay) - (by - ay) * (frame[c * 2] - ax)
			if (cross * inside >= -1e-8f) return@mapNotNull null
			val blocked = boundary.keys.any { other ->
				if (other == edge) false else {
					val cx = frame[other.endpointLow * 2]; val cy = frame[other.endpointLow * 2 + 1]
					val dx = frame[other.endpointHigh * 2]; val dy = frame[other.endpointHigh * 2 + 1]
					properCross(ax, ay, x, y, cx, cy, dx, dy) || properCross(bx, by, x, y, cx, cy, dx, dy)
				}
			}
			val proposed = floatArrayOf(bx, by, ax, ay, x, y)
			val coversExistingVertex = (0 until mesh.vertexCount).any { vertex ->
				if (vertex == a || vertex == b) false else {
					// Check the proposed (b,a,new) wedge directly, including a vertex on a new edge.
					val v = barycentricAt(proposed, 0, 1, 2,
						frame[vertex * 2], frame[vertex * 2 + 1])
					v != null && v.all { it >= -1e-5f }
				}
			}
			if (blocked || coversExistingVertex) return@mapNotNull null
			val t = (((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / ((bx - ax) * (bx - ax) + (by - ay) * (by - ay))).coerceIn(0f, 1f)
			val distance = (x - ax - t * (bx - ax)) * (x - ax - t * (bx - ax)) + (y - ay - t * (by - ay)) * (y - ay - t * (by - ay))
			Triple(intArrayOf(a, b, c), distance, preferred == a || preferred == b)
		}.sortedWith(compareByDescending<Triple<IntArray, Float, Boolean>> { it.third }.thenBy { it.second })
		for ((tri, _, _) in candidates) {
			val weights = barycentricAt(frame, tri[0], tri[1], tri[2], x, y) ?: continue
			val appender = MeshAppender(mesh)
			val point = appender.appendBarycentric(tri[0], tri[1], tri[2], weights[0], weights[1], weights[2])
			val indices = mesh.indices.toMutableList()
			indices += listOf(tri[1], tri[0], point)
			return TopologyOpResult(appender.build(indices.toIntArray()), setOf(MeshElement.Vertex(point)))
		}
		return null
	}

	/** Where a free point lands in the current mesh, including triangles created earlier in the gesture. */
	private sealed interface PointSpot {
		data class OnEdge(val edge: MeshElement.Edge, val t: Float) : PointSpot
		data class InFace(val triangleIndex: Int, val wa: Float, val wb: Float, val wc: Float) : PointSpot
	}

	/** Floating point tolerance only; actual snapping is a user gesture resolved by the editor. */
	private const val EDGE_SNAP_FRACTION = 1e-6f

	private fun locatePoint(positions: FloatArray, indices: IntArray, x: Float, y: Float, edgeTolerance: Float = 0f): PointSpot? {
		val bounds = meshBounds(positions)
		val tolerance = maxOf(edgeTolerance, EDGE_SNAP_FRACTION * maxOf(bounds[2], bounds[3]))
		val toleranceSquared = tolerance * tolerance

		// Only exact-on-edge points (within float tolerance) are classified as edge points. The cutter must
		// not silently snap a freely placed point while the editor's snapping option is off.
		var bestEdge: MeshElement.Edge? = null
		var bestT = 0f
		var bestDistance = Float.MAX_VALUE
		for (edge in MeshTopology.uniqueEdges(indices)) {
			val ax = positions[edge.endpointLow * 2]
			val ay = positions[edge.endpointLow * 2 + 1]
			val dx = positions[edge.endpointHigh * 2] - ax
			val dy = positions[edge.endpointHigh * 2 + 1] - ay
			val lengthSquared = dx * dx + dy * dy
			if (lengthSquared < 1e-12f) continue
			val t = (((x - ax) * dx + (y - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
			val px = ax + dx * t
			val py = ay + dy * t
			val distance = (x - px) * (x - px) + (y - py) * (y - py)
			if (distance < bestDistance) {
				bestDistance = distance
				bestEdge = edge
				bestT = t
			}
		}
		if (bestEdge != null && bestDistance <= toleranceSquared) return PointSpot.OnEdge(bestEdge, bestT)

		val triangles = indices.toList().chunked(3)
		for (ordinal in triangles.indices) {
			val triangle = triangles[ordinal]
			val weights = barycentricAt(positions, triangle[0], triangle[1], triangle[2], x, y) ?: continue
			if (weights.all { it >= -1e-4f }) {
				return PointSpot.InFace(ordinal, weights[0], weights[1], weights[2])
			}
		}
		return null
	}

	/** Barycentric weights of (x, y) in the triangle (a, b, c), or null when it is degenerate. */
	private fun barycentricAt(positions: FloatArray, a: Int, b: Int, c: Int, x: Float, y: Float): FloatArray? {
		val ax = positions[a * 2]
		val ay = positions[a * 2 + 1]
		val bx = positions[b * 2]
		val by = positions[b * 2 + 1]
		val cx = positions[c * 2]
		val cy = positions[c * 2 + 1]
		val denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
		if (abs(denominator) < 1e-12f) return null
		val wa = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / denominator
		val wb = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / denominator
		return floatArrayOf(wa, wb, 1f - wa - wb)
	}

	/** The mesh bounds as [left, top, width, height] in its own space, for scaling the snap tolerance. */
	private fun meshBounds(positions: FloatArray): FloatArray {
		if (positions.isEmpty()) return floatArrayOf(0f, 0f, 1f, 1f)
		var minX = Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE
		for (index in positions.indices step 2) {
			val x = positions[index]
			val y = positions[index + 1]
			if (x < minX) minX = x
			if (y < minY) minY = y
			if (x > maxX) maxX = x
			if (y > maxY) maxY = y
		}
		return floatArrayOf(minX, minY, (maxX - minX).coerceAtLeast(1e-6f), (maxY - minY).coerceAtLeast(1e-6f))
	}

	/**
	 * Removes [removedVertices] and re-triangulates the rim they leave, so the mesh keeps covering the
	 * area instead of developing a hole.
	 *
	 * Dropping every triangle that touched a removed vertex is what left the hole, and the rendered mesh
	 * is raw triangles, so the hole is a literal transparent gap in the artwork. The rim loop is the ring
	 * of surviving vertices around it; ear-clipping that ring puts triangles back.
	 *
	 * **The fill is pixel-identical, not an approximation.** No vertex is created and none moves, so every
	 * fill corner still satisfies `uv = Φ(position)` - a subset of a mesh that satisfies Φ still satisfies
	 * it. UV inside a triangle is barycentric, so for any point of the filled region the interpolated UV
	 * is `Φ(p)` regardless of which triangle contains it: the removed triangles' corners and the new ones
	 * all lie on the same Φ plane. That is also what licenses drawing the patch faintly - the artist is
	 * looking at a provisional mesh, never at provisional art.
	 *
	 * A rim that ran off the silhouette comes back as an open chain, which is closed with a chord; the
	 * chord is a straight line where the outline used to curve, so the tip of the shape straightens
	 * slightly. A loop the clipper cannot handle refuses the WHOLE op rather than leaving a half-filled
	 * mesh, which is the bug being fixed.
	 *
	 * @param DrawableMesh mesh The mesh to delete from.
	 * @param Set<Int> removedVertices The vertices to remove.
	 * @return TopologyOpResult? The edit and the created fill faces, or null when it cannot be done.
	 */
	fun deleteWithFill(mesh: DrawableMesh, removedVertices: Set<Int>): TopologyOpResult? {
		val removed = removedVertices.filter { it in 0 until mesh.vertexCount }.toSet()
		if (removed.isEmpty()) return null
		val keep = (0 until mesh.vertexCount).filter { it !in removed }
		if (keep.size < 3) return null
		val remap = keep.withIndex().associate { it.value to it.index }

		val positions = FloatArray(keep.size * 2)
		val uvs = FloatArray(keep.size * 2)
		keep.forEachIndexed { newIndex, oldIndex ->
			positions[newIndex * 2] = mesh.positions[oldIndex * 2]
			positions[newIndex * 2 + 1] = mesh.positions[oldIndex * 2 + 1]
			val slot = oldIndex * 2
			if (slot + 1 < mesh.uvs.size) {
				uvs[newIndex * 2] = mesh.uvs[slot]
				uvs[newIndex * 2 + 1] = mesh.uvs[slot + 1]
			}
		}

		val triangles = mesh.indices.toList().chunked(3)
		fun survives(ordinal: Int) = triangles[ordinal].none { it in removed }
		val rebuild = ArrayList<Int>(mesh.indices.size)
		for (ordinal in triangles.indices) {
			if (!survives(ordinal)) continue
			for (corner in triangles[ordinal]) rebuild += remap.getValue(corner)
		}
		if (rebuild.isEmpty()) return null

		val filled = LinkedHashSet<Int>()
		for (loop in MeshTopology.rimLoops(mesh.indices) { survives(it) }) {
			// A rim too small to triangulate refuses the op: filling the rest and leaving this one open is
			// the gap this whole function exists to remove.
			if (loop.size < 3) return null
			val remapped = IntArray(loop.size) { remap[loop[it]] ?: return null }
			val clipped = earClipLoop(remapped, positions) ?: return null
			for (corner in clipped.indices step 3) {
				filled.add(rebuild.size / 3)
				rebuild += listOf(clipped[corner], clipped[corner + 1], clipped[corner + 2])
			}
		}

		return TopologyOpResult(
			MeshTopologyEdit(DrawableMesh(positions, uvs, rebuild.toIntArray()), keep.map { VertexSource.FromOld(it) }),
			filled.mapTo(LinkedHashSet()) { MeshElement.Face(it) },
		)
	}
}

/** The clipper's zero, matching AdaptiveMeshGenerator's GEOMETRY_EPSILON. */
private const val CLIP_EPSILON = 1e-8f

/** Twice the signed area of the triangle (a, b, c); positive and negative follow the winding. */
private fun cross(positions: FloatArray, a: Int, b: Int, c: Int): Float {
	val abx = positions[b * 2] - positions[a * 2]
	val aby = positions[b * 2 + 1] - positions[a * 2 + 1]
	val acx = positions[c * 2] - positions[a * 2]
	val acy = positions[c * 2 + 1] - positions[a * 2 + 1]
	return abx * acy - aby * acx
}

/** Twice the signed area of the polygon [loop]; its sign is the polygon's orientation. */
private fun signedAreaTwice(loop: IntArray, positions: FloatArray): Float {
	var area = 0f
	for (index in loop.indices) {
		val current = loop[index]
		val next = loop[(index + 1) % loop.size]
		area += positions[current * 2] * positions[next * 2 + 1] - positions[next * 2] * positions[current * 2 + 1]
	}
	return area
}

/** Whether [p] is inside or on the triangle (a, b, c), whatever the winding. */
private fun inTriangleInclusive(positions: FloatArray, p: Int, a: Int, b: Int, c: Int): Boolean {
	val ab = cross(positions, a, b, p)
	val bc = cross(positions, b, c, p)
	val ca = cross(positions, c, a, p)
	val hasNegative = ab < -CLIP_EPSILON || bc < -CLIP_EPSILON || ca < -CLIP_EPSILON
	val hasPositive = ab > CLIP_EPSILON || bc > CLIP_EPSILON || ca > CLIP_EPSILON
	return !(hasNegative && hasPositive)
}

/**
 * Ear-clips the closed polygon [loop] - vertex indices into [positions] - into triangle corners, or null
 * when the polygon is not clean enough to clip.
 *
 * **Ported from `AdaptiveMeshGenerator.earClip` rather than shared with it.** The two cannot share without
 * one of them converting: the generator works in `Double` on its own private `Point` and its `Triangle`
 * holds indices into a boundary list, while this works in `Float` on an `IntArray` of vertex indices.
 * Sharing would mean generifying both or making the generator convert, and the generator is a working,
 * delicate, 1100-line file that this feature has no business reaching into. The algorithm's shape - the
 * `guard = n*n` backstop, the orientation taken from the signed area, the epsilon, the refusal instead of
 * a partial clip - is deliberately identical.
 *
 * Refusing is the whole point. A partial fill is the bug this exists to fix, so a polygon it cannot clip
 * cleanly yields null and the caller refuses the entire op.
 */
private fun earClipLoop(loop: IntArray, positions: FloatArray): IntArray? {
	if (loop.size < 3) return null
	val orientation = if (signedAreaTwice(loop, positions) >= 0f) 1f else -1f
	val remaining = loop.indices.toMutableList()
	val out = ArrayList<Int>(loop.size * 3)
	var guard = loop.size * loop.size
	while (remaining.size > 3 && guard-- > 0) {
		var clipped = false
		for (slot in remaining.indices) {
			val previous = loop[remaining[(slot - 1 + remaining.size) % remaining.size]]
			val current = loop[remaining[slot]]
			val next = loop[remaining[(slot + 1) % remaining.size]]
			if (cross(positions, previous, current, next) * orientation <= CLIP_EPSILON) continue
			var containsVertex = false
			for (candidateSlot in remaining) {
				val candidate = loop[candidateSlot]
				if (candidate == previous || candidate == current || candidate == next) continue
				if (inTriangleInclusive(positions, candidate, previous, current, next)) {
					containsVertex = true
					break
				}
			}
			if (containsVertex) continue
			out += listOf(previous, current, next)
			remaining.removeAt(slot)
			clipped = true
			break
		}
		if (!clipped) return null
	}
	if (remaining.size == 3) {
		val a = loop[remaining[0]]
		val b = loop[remaining[1]]
		val c = loop[remaining[2]]
		if (abs(cross(positions, a, b, c)) > CLIP_EPSILON) out += listOf(a, b, c)
	}
	return out.takeIf { it.isNotEmpty() }?.toIntArray()
}
